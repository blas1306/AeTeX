package dev.aetex.preview.scheduling

import dev.aetex.preview.cache.CacheAdmission
import dev.aetex.preview.cache.CacheReservation
import dev.aetex.preview.cache.PageCache
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RenderPriority
import dev.aetex.preview.domain.RenderRequest
import dev.aetex.preview.domain.RenderedPage
import dev.aetex.preview.generation.DocumentGeneration
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.math.ceil

data class RenderSchedulerPolicy(
    val workerCount: Int = 2,
    val queueCapacity: Int = 64,
    val starvationPromotionNanos: Long = TimeUnit.SECONDS.toNanos(2),
    val shutdownWait: java.time.Duration = java.time.Duration.ofSeconds(3),
    val maximumRasterWidth: Long = 8_192,
    val maximumRasterHeight: Long = 8_192,
    val maximumRasterPixels: Long = 32_000_000
) {
    init {
        require(workerCount in 1..32)
        require(queueCapacity in 1..4_096)
        require(starvationPromotionNanos > 0)
        require(!shutdownWait.isNegative)
        require(shutdownWait <= java.time.Duration.ofSeconds(30))
        require(maximumRasterWidth > 0)
        require(maximumRasterHeight > 0)
        require(maximumRasterPixels > 0)
    }
}

enum class RenderStage {
    QUEUED,
    RENDERING
}

data class RenderSchedulerStats(
    val queued: Int,
    val inFlight: Int,
    val pendingConsumers: Int,
    val coalescedRequests: Long,
    val rendersStarted: Long,
    val rendersCompleted: Long,
    val lateResultsDiscarded: Long,
    val maximumObservedWorkers: Int,
    val workerPoolTerminated: Boolean
)

class RenderHandle internal constructor(
    val future: CompletableFuture<PreviewResult<RenderedPage>>,
    private val cancelAction: () -> Unit
) : AutoCloseable {
    fun cancel() = cancelAction()
    override fun close() = cancel()
}

class RenderScheduler(
    private val cache: PageCache,
    private val policy: RenderSchedulerPolicy = RenderSchedulerPolicy(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val stageListener: (RenderRequest, RenderStage) -> Unit = { _, _ -> }
) : AutoCloseable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val generations = LinkedHashMap<GenerationId, DocumentGeneration>()
    private val jobs = LinkedHashMap<PageRenderKey, Job>()
    private val subscriberIds = AtomicLong()
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        policy.workerCount,
        PreviewThreadFactory()
    )
    private var closed = false
    private var sequence = 0L
    private var runningWorkers = 0
    private var maximumWorkers = 0
    private var coalesced = 0L
    private var started = 0L
    private var completed = 0L
    private var discarded = 0L

    init {
        repeat(policy.workerCount) {
            executor.execute(::workerLoop)
        }
    }

    fun registerGeneration(generation: DocumentGeneration) = synchronized(lock) {
        check(!closed) { "The render scheduler is closed." }
        check(generations[generation.id].let { it == null || it === generation }) {
            "A generation identity cannot be registered for two documents."
        }
        generations[generation.id] = generation
    }

    fun request(request: RenderRequest): RenderHandle {
        synchronized(lock) {
            if (closed) {
                return RenderHandle(
                    CompletableFuture.completedFuture(
                        failure(request, PreviewErrorKind.MANAGER_CLOSED)
                    )
                ) {}
            }
            if (generations[request.key.generationId] == null) {
                return RenderHandle(
                    CompletableFuture.completedFuture(
                        failure(request, PreviewErrorKind.GENERATION_OBSOLETE)
                    )
                ) {}
            }
        }
        cache.get(request.key)?.let { cached ->
            val stillEligible = synchronized(lock) {
                !closed && generations[request.key.generationId] != null
            }
            return RenderHandle(
                CompletableFuture.completedFuture(
                    if (stillEligible) {
                        PreviewResult.Success(cached)
                    } else {
                        failure(request, PreviewErrorKind.GENERATION_OBSOLETE)
                    }
                )
            ) {}
        }

        val subscriber = Subscriber(
            subscriberIds.incrementAndGet(),
            request,
            CompletableFuture()
        )
        var displacedSubscribers: List<Subscriber> = emptyList()
        synchronized(lock) {
            if (closed) {
                subscriber.future.complete(failure(request, PreviewErrorKind.MANAGER_CLOSED))
                return RenderHandle(subscriber.future) {}
            }
            val generation = generations[request.key.generationId]
            if (generation == null) {
                subscriber.future.complete(failure(request, PreviewErrorKind.GENERATION_OBSOLETE))
                return RenderHandle(subscriber.future) {}
            }
            val existing = jobs[request.key]
            if (existing != null) {
                existing.subscribers[subscriber.id] = subscriber
                if (request.priority.rank < existing.priority.rank) {
                    existing.priority = request.priority
                }
                coalesced++
                return RenderHandle(subscriber.future) { cancelSubscriber(subscriber) }
            }

            if (queuedCount() >= policy.queueCapacity) {
                displacedSubscribers = makeRoomFor(request.priority) ?: run {
                    subscriber.future.complete(
                        failure(request, PreviewErrorKind.SCHEDULER_OVERLOADED)
                    )
                    return RenderHandle(subscriber.future) {}
                }
            }
            val job = Job(
                key = request.key,
                generation = generation,
                priority = request.priority,
                enqueuedAt = nanoTime(),
                sequence = sequence++
            )
            job.subscribers[subscriber.id] = subscriber
            jobs[request.key] = job
            lock.notifyAll()
        }
        displacedSubscribers.forEach {
            it.cancel(PreviewErrorKind.SCHEDULER_OVERLOADED)
        }
        return RenderHandle(subscriber.future) { cancelSubscriber(subscriber) }
    }

    fun cancelGeneration(generationId: GenerationId) {
        val cancelled = mutableListOf<Subscriber>()
        synchronized(lock) {
            generations.remove(generationId)
            val iterator = jobs.entries.iterator()
            while (iterator.hasNext()) {
                val job = iterator.next().value
                if (job.key.generationId != generationId) continue
                job.cancelled = true
                if (!job.running) iterator.remove()
                cancelled += job.subscribers.values
                job.subscribers.clear()
            }
            lock.notifyAll()
        }
        cancelled.forEach {
            it.cancel(PreviewErrorKind.GENERATION_OBSOLETE)
        }
    }

    fun cancelConsumer(consumerId: String) {
        val cancelled = mutableListOf<Subscriber>()
        synchronized(lock) {
            val iterator = jobs.entries.iterator()
            while (iterator.hasNext()) {
                val job = iterator.next().value
                val interested = job.subscribers.entries.iterator()
                while (interested.hasNext()) {
                    val subscriber = interested.next().value
                    if (subscriber.request.consumerId.value == consumerId) {
                        cancelled += subscriber
                        interested.remove()
                    }
                }
                if (job.subscribers.isEmpty()) {
                    job.cancelled = true
                    if (!job.running) iterator.remove()
                }
            }
        }
        cancelled.forEach {
            it.cancel(PreviewErrorKind.GENERATION_OBSOLETE)
        }
    }

    fun stats(): RenderSchedulerStats = synchronized(lock) {
        RenderSchedulerStats(
            queued = jobs.values.count { !it.running },
            inFlight = jobs.values.count(Job::running),
            pendingConsumers = jobs.values.sumOf { it.subscribers.size },
            coalescedRequests = coalesced,
            rendersStarted = started,
            rendersCompleted = completed,
            lateResultsDiscarded = discarded,
            maximumObservedWorkers = maximumWorkers,
            workerPoolTerminated = executor.isTerminated
        )
    }

    override fun close() {
        val cancelled = mutableListOf<Subscriber>()
        synchronized(lock) {
            if (closed) return
            closed = true
            generations.clear()
            jobs.values.forEach { job ->
                job.cancelled = true
                cancelled += job.subscribers.values
                job.subscribers.clear()
            }
            jobs.entries.removeIf { !it.value.running }
            lock.notifyAll()
        }
        cancelled.forEach {
            it.cancel(PreviewErrorKind.MANAGER_CLOSED)
        }
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(policy.shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warning(
                    "Preview scheduler shutdown timed out with non-interruptible renderer work."
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun workerLoop() {
        while (true) {
            val job = synchronized(lock) {
                while (!closed && jobs.values.none { !it.running && !it.cancelled }) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        if (closed) return
                    }
                }
                if (closed) return
                selectNext()?.also {
                    it.running = true
                    runningWorkers++
                    maximumWorkers = maxOf(maximumWorkers, runningWorkers)
                    started++
                }
            } ?: continue
            synchronized(lock) { job.subscribers.values.toList() }.forEach { subscriber ->
                try {
                    stageListener(subscriber.request, RenderStage.RENDERING)
                } catch (error: Throwable) {
                    LOGGER.warning(
                        "Preview render-stage observer failed without stopping a worker: " +
                            error.message.orEmpty()
                    )
                }
            }

            val preflight = preflight(job)
            val result = if (preflight.error != null) {
                PreviewResult.Failure(preflight.error)
            } else {
                try {
                    job.generation.render(job.key)
                } catch (error: Throwable) {
                    PreviewResult.Failure(
                        PreviewError(
                            PreviewErrorKind.RENDER_FAILED,
                            "The renderer failed unexpectedly.",
                            job.key.generationId,
                            job.key.pageIndex,
                            job.key.scale,
                            error
                        )
                    )
                }
            }

            val subscribers: List<Subscriber>
            val accepted: PreviewResult<RenderedPage>
            synchronized(lock) {
                runningWorkers--
                jobs.remove(job.key)
                val stillEligible =
                    !closed &&
                        !job.cancelled &&
                        generations[job.key.generationId] === job.generation &&
                        job.subscribers.isNotEmpty()
                if (!stillEligible) {
                    preflight.reservation?.close()
                    discarded++
                    subscribers = job.subscribers.values.filter(Subscriber::claim)
                    job.subscribers.clear()
                    accepted = PreviewResult.Failure(
                        PreviewError(
                            PreviewErrorKind.GENERATION_OBSOLETE,
                            "An obsolete render result was discarded.",
                            job.key.generationId,
                            job.key.pageIndex,
                            job.key.scale
                        )
                    )
                } else {
                    subscribers = job.subscribers.values.filter(Subscriber::claim)
                    accepted = when (result) {
                        is PreviewResult.Failure -> {
                            preflight.reservation?.close()
                            result
                        }
                        is PreviewResult.Success -> when (
                            preflight.reservation?.let {
                                cache.putReserved(result.value, job.priority, it)
                            } ?: cache.put(result.value, job.priority)
                        ) {
                            CacheAdmission.ADMITTED,
                            CacheAdmission.REPLACED -> result

                            CacheAdmission.REJECTED_TOO_LARGE,
                            CacheAdmission.REJECTED_PINNED_BUDGET ->
                                PreviewResult.Failure(
                                    PreviewError(
                                        PreviewErrorKind.MEMORY_LIMIT,
                                        "The rendered page does not fit in the preview memory budget.",
                                        job.key.generationId,
                                        job.key.pageIndex,
                                        job.key.scale
                                    )
                                )
                        }
                    }
                    job.subscribers.clear()
                    completed++
                }
                lock.notifyAll()
            }
            subscribers.forEach { subscriber ->
                subscriber.deliver(accepted)
            }
        }
    }

    private fun preflight(job: Job): Preflight {
        synchronized(lock) {
            if (
                closed ||
                job.cancelled ||
                generations[job.key.generationId] !== job.generation ||
                job.subscribers.isEmpty()
            ) {
                return Preflight(
                    PreviewError(
                        PreviewErrorKind.GENERATION_OBSOLETE,
                        "The render request became obsolete before rendering.",
                        job.key.generationId,
                        job.key.pageIndex,
                        job.key.scale
                    )
                )
            }
        }
        val geometry = job.generation.metadata.pages.getOrNull(job.key.pageIndex)
            ?: return Preflight(
                PreviewError(
                    PreviewErrorKind.INVALID_PAGE,
                    "The requested page is outside the document.",
                    job.key.generationId,
                    job.key.pageIndex,
                    job.key.scale
                )
            )
        val width = ceil(geometry.displayedWidthPoints * job.key.scale.value).toLong()
        val height = ceil(geometry.displayedHeightPoints * job.key.scale.value).toLong()
        val pixels = try {
            Math.multiplyExact(width, height)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        if (
            width <= 0L ||
            height <= 0L ||
            width > policy.maximumRasterWidth ||
            height > policy.maximumRasterHeight ||
            pixels > policy.maximumRasterPixels
        ) {
            return Preflight(
                PreviewError(
                    PreviewErrorKind.RASTER_LIMIT,
                    "The requested page raster exceeds the configured safety limit.",
                    job.key.generationId,
                    job.key.pageIndex,
                    job.key.scale
                )
            )
        }
        val estimate = try {
            Math.addExact(
                Math.addExact(
                    Math.multiplyExact(pixels, 7L),
                    Math.multiplyExact(width, 4L)
                ),
                4_096L
            )
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val reservation = cache.reserve(estimate)
        return if (reservation == null) {
            Preflight(
                PreviewError(
                    PreviewErrorKind.MEMORY_LIMIT,
                    "The requested page exceeds the available preview memory budget.",
                    job.key.generationId,
                    job.key.pageIndex,
                    job.key.scale
                )
            )
        } else {
            Preflight(reservation = reservation)
        }
    }

    private fun cancelSubscriber(subscriber: Subscriber) {
        synchronized(lock) {
            val job = jobs[subscriber.request.key]
            if (job != null) {
                job.subscribers.remove(subscriber.id)
                if (job.subscribers.isEmpty()) {
                    job.cancelled = true
                    if (!job.running) jobs.remove(job.key)
                }
            }
        }
        subscriber.cancel(PreviewErrorKind.GENERATION_OBSOLETE)
    }

    private fun makeRoomFor(priority: RenderPriority): List<Subscriber>? {
        val worst = jobs.values
            .filter { !it.running }
            .maxWithOrNull(compareBy<Job>({ it.priority.rank }, { it.sequence }))
            ?: return null
        if (priority.rank >= worst.priority.rank) return null
        jobs.remove(worst.key)
        worst.cancelled = true
        val subscribers = worst.subscribers.values.toList()
        worst.subscribers.clear()
        return subscribers
    }

    private fun selectNext(): Job? {
        val now = nanoTime()
        return jobs.values
            .asSequence()
            .filter { !it.running && !it.cancelled }
            .minWithOrNull(
                compareBy<Job>(
                    {
                        val elapsed = (now - it.enqueuedAt).coerceAtLeast(0L)
                        val promotions = minOf(
                            elapsed / policy.starvationPromotionNanos,
                            Int.MAX_VALUE.toLong()
                        ).toInt()
                        (it.priority.rank - promotions).coerceAtLeast(0)
                    },
                    { it.sequence }
                )
            )
    }

    private fun queuedCount(): Int = jobs.values.count { !it.running }

    private fun failure(
        request: RenderRequest,
        kind: PreviewErrorKind
    ): PreviewResult.Failure {
        val message = when (kind) {
            PreviewErrorKind.MANAGER_CLOSED -> "The preview scheduler is closed."
            PreviewErrorKind.SCHEDULER_OVERLOADED -> "The preview render queue is full."
            else -> "The render request is no longer current."
        }
        return PreviewResult.Failure(
            PreviewError(
                kind,
                message,
                request.key.generationId,
                request.key.pageIndex,
                request.key.scale
            )
        )
    }

    private inner class Subscriber(
        val id: Long,
        val request: RenderRequest,
        val future: CompletableFuture<PreviewResult<RenderedPage>>
    ) {
        private val completed = AtomicBoolean(false)

        fun claim(): Boolean = completed.compareAndSet(false, true)

        fun deliver(result: PreviewResult<RenderedPage>) {
            check(completed.get())
            future.complete(result)
        }

        fun cancel(kind: PreviewErrorKind) {
            if (completed.compareAndSet(false, true)) {
                future.complete(failure(request, kind))
            }
        }
    }

    private data class Preflight(
        val error: PreviewError? = null,
        val reservation: CacheReservation? = null
    )

    private data class Job(
        val key: PageRenderKey,
        val generation: DocumentGeneration,
        var priority: RenderPriority,
        val enqueuedAt: Long,
        val sequence: Long,
        var running: Boolean = false,
        var cancelled: Boolean = false,
        val subscribers: LinkedHashMap<Long, Subscriber> = LinkedHashMap()
    )

    private class PreviewThreadFactory : ThreadFactory {
        private val counter = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            Thread(task, "aetex-preview-render-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
    }

    companion object {
        private val LOGGER = Logger.getLogger(RenderScheduler::class.java.name)
    }
}
