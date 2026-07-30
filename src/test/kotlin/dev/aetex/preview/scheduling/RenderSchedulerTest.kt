package dev.aetex.preview.scheduling

import dev.aetex.preview.FakeRenderer
import dev.aetex.preview.cache.PageCache
import dev.aetex.preview.cache.PageCachePolicy
import dev.aetex.preview.domain.DocumentMetadata
import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewConsumerId
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RenderPriority
import dev.aetex.preview.domain.RenderRequest
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.domain.RequestToken
import dev.aetex.preview.testGeneration
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RenderSchedulerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        closeables.reversed().forEach(AutoCloseable::close)
    }

    @Test
    fun `coalesces identical in-flight keys into one physical render`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = renderer { started.countDown(); release.await() }
        val generation = generation(temporaryDirectory, renderer)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        val key = PageRenderKey(generation.id, 0, RenderScale.DEFAULT)

        val first = scheduler.request(request(key, 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val second = scheduler.request(request(key, 2, RenderPriority.NEIGHBOR))
        release.countDown()

        assertIs<PreviewResult.Success<*>>(first.future.get(2, TimeUnit.SECONDS))
        assertIs<PreviewResult.Success<*>>(second.future.get(2, TimeUnit.SECONDS))
        assertEquals(1, renderer.renderCount.get())
        assertEquals(1, scheduler.stats().coalescedRequests)
    }

    @Test
    fun `duplicate consumer token cannot orphan the first coalesced future`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = renderer { started.countDown(); release.await() }
        val generation = generation(temporaryDirectory, renderer)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        val identical = request(
            PageRenderKey(generation.id, 0, RenderScale.DEFAULT),
            1,
            RenderPriority.CURRENT_VISIBLE
        )

        val first = scheduler.request(identical)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val second = scheduler.request(identical)
        release.countDown()

        assertIs<PreviewResult.Success<*>>(first.future.get(2, TimeUnit.SECONDS))
        assertIs<PreviewResult.Success<*>>(second.future.get(2, TimeUnit.SECONDS))
        assertEquals(1, renderer.renderCount.get())
    }

    @Test
    fun `cancelling one coalesced consumer preserves the remaining consumer`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = renderer { started.countDown(); release.await() }
        val generation = generation(temporaryDirectory, renderer)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        val key = PageRenderKey(generation.id, 0, RenderScale.DEFAULT)

        val cancelled = scheduler.request(request(key, 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val remaining = scheduler.request(request(key, 2, RenderPriority.CURRENT_VISIBLE))
        cancelled.cancel()
        release.countDown()

        assertEquals(
            PreviewErrorKind.GENERATION_OBSOLETE,
            assertIs<PreviewResult.Failure>(
                cancelled.future.get(2, TimeUnit.SECONDS)
            ).error.kind
        )
        assertIs<PreviewResult.Success<*>>(remaining.future.get(2, TimeUnit.SECONDS))
        assertEquals(1, renderer.renderCount.get())
    }

    @Test
    fun `stage observer exception does not kill a worker or strand futures`() {
        val generation = generation(
            temporaryDirectory,
            renderer(pageCount = 2) {}
        )
        val cache = PageCache(PageCachePolicy(2_000_000))
        closeables += cache
        val scheduler = RenderScheduler(
            cache,
            RenderSchedulerPolicy(1, 8),
            stageListener = { _, _ -> error("synthetic observer failure") }
        )
        closeables += scheduler
        scheduler.registerGeneration(generation)

        val first = scheduler.request(
            request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE)
        )
        val second = scheduler.request(
            request(key(generation.id, 1), 2, RenderPriority.CURRENT_VISIBLE)
        )

        assertIs<PreviewResult.Success<*>>(first.future.get(2, TimeUnit.SECONDS))
        assertIs<PreviewResult.Success<*>>(second.future.get(2, TimeUnit.SECONDS))
        assertEquals(2, scheduler.stats().rendersCompleted)
    }

    @Test
    fun `visible work runs before an older speculative request`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val renderer = renderer(pageCount = 3) { key ->
            order += key.pageIndex
            if (key.pageIndex == 0) {
                started.countDown()
                release.await()
            }
        }
        val generation = generation(temporaryDirectory, renderer)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        scheduler.request(request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val neighbor = scheduler.request(request(key(generation.id, 1), 2, RenderPriority.NEIGHBOR))
        val visible = scheduler.request(request(key(generation.id, 2), 3, RenderPriority.OTHER_VISIBLE))
        release.countDown()
        visible.future.get(2, TimeUnit.SECONDS)
        neighbor.future.get(2, TimeUnit.SECONDS)

        assertEquals(listOf(0, 2, 1), order)
    }

    @Test
    fun `coalesced request raises queued job priority without duplicate render`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val renderer = renderer(pageCount = 3) { key ->
            order += key.pageIndex
            if (key.pageIndex == 0) {
                started.countDown()
                release.await()
            }
        }
        val generation = generation(temporaryDirectory, renderer)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        scheduler.request(request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val low = scheduler.request(request(key(generation.id, 1), 2, RenderPriority.NEIGHBOR))
        val other = scheduler.request(request(key(generation.id, 2), 3, RenderPriority.OTHER_VISIBLE))
        val raised = scheduler.request(
            request(key(generation.id, 1), 4, RenderPriority.CURRENT_VISIBLE)
        )
        release.countDown()
        low.future.get(2, TimeUnit.SECONDS)
        raised.future.get(2, TimeUnit.SECONDS)
        other.future.get(2, TimeUnit.SECONDS)

        assertEquals(listOf(0, 1, 2), order)
        assertEquals(3, renderer.renderCount.get())
    }

    @Test
    fun `cancelling a queued request prevents its render`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = renderer(pageCount = 2) { key ->
            if (key.pageIndex == 0) {
                started.countDown()
                release.await()
            }
        }
        val generation = generation(temporaryDirectory, renderer)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        val first = scheduler.request(request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val queued = scheduler.request(request(key(generation.id, 1), 2, RenderPriority.NEIGHBOR))
        queued.cancel()
        release.countDown()
        first.future.get(2, TimeUnit.SECONDS)

        val failure = assertIs<PreviewResult.Failure>(queued.future.get(2, TimeUnit.SECONDS))
        assertEquals(PreviewErrorKind.GENERATION_OBSOLETE, failure.error.kind)
        assertEquals(1, renderer.renderCount.get())
    }

    @Test
    fun `late result from cancelled generation is discarded before cache and publication`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = renderer { started.countDown(); release.await() }
        val generation = generation(temporaryDirectory, renderer)
        val cache = PageCache(PageCachePolicy(2_000_000))
        closeables += cache
        val scheduler = RenderScheduler(
            cache,
            RenderSchedulerPolicy(1, 8, shutdownWait = Duration.ofSeconds(1))
        )
        closeables += scheduler
        scheduler.registerGeneration(generation)
        val handle = scheduler.request(
            request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE)
        )
        assertTrue(started.await(2, TimeUnit.SECONDS))
        scheduler.cancelGeneration(generation.id)
        release.countDown()

        val failure = assertIs<PreviewResult.Failure>(handle.future.get(2, TimeUnit.SECONDS))
        assertEquals(PreviewErrorKind.GENERATION_OBSOLETE, failure.error.kind)
        assertTrue(
            awaitCondition {
                scheduler.stats().inFlight == 0 &&
                    scheduler.stats().lateResultsDiscarded == 1L
            }
        )
        assertEquals(0, cache.stats().entryCount)
        assertEquals(1, scheduler.stats().lateResultsDiscarded)
    }

    @Test
    fun `worker concurrency never exceeds configured bound`() {
        val release = CountDownLatch(1)
        val bothStarted = CountDownLatch(2)
        val renderers = (0 until 4).map {
            renderer {
                bothStarted.countDown()
                release.await()
            }
        }
        val generations = renderers.mapIndexed { index, renderer ->
            generation(
                temporaryDirectory.resolve("g$index").also(java.nio.file.Files::createDirectory),
                renderer
            )
        }
        val scheduler = scheduler(workerCount = 2)
        generations.forEach(scheduler::registerGeneration)
        val handles = generations.mapIndexed { index, generation ->
            scheduler.request(
                request(key(generation.id, 0), index.toLong(), RenderPriority.OTHER_VISIBLE)
            )
        }
        assertTrue(bothStarted.await(2, TimeUnit.SECONDS))
        assertEquals(2, scheduler.stats().inFlight)
        release.countDown()
        handles.forEach { it.future.get(2, TimeUnit.SECONDS) }

        assertTrue(scheduler.stats().maximumObservedWorkers <= 2)
    }

    @Test
    fun `starvation aging eventually promotes old low priority work`() {
        val now = AtomicLong()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val renderer = renderer(pageCount = 3) { key ->
            order += key.pageIndex
            if (key.pageIndex == 0) {
                started.countDown()
                release.await()
            }
        }
        val generation = generation(temporaryDirectory, renderer)
        val cache = PageCache(PageCachePolicy(2_000_000))
        closeables += cache
        val scheduler = RenderScheduler(
            cache,
            RenderSchedulerPolicy(1, 8, starvationPromotionNanos = 10),
            nanoTime = now::get
        )
        closeables += scheduler
        scheduler.registerGeneration(generation)
        scheduler.request(request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val low = scheduler.request(request(key(generation.id, 1), 2, RenderPriority.LOW))
        now.set(100)
        val visible = scheduler.request(request(key(generation.id, 2), 3, RenderPriority.OTHER_VISIBLE))
        release.countDown()
        low.future.get(2, TimeUnit.SECONDS)
        visible.future.get(2, TimeUnit.SECONDS)

        assertEquals(listOf(0, 1, 2), order)
    }

    @Test
    fun `shutdown is idempotent and rejects new requests`() {
        val generation = generation(temporaryDirectory)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        scheduler.close()
        scheduler.close()

        val failure = assertIs<PreviewResult.Failure>(
            scheduler.request(
                request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE)
            ).future.get(2, TimeUnit.SECONDS)
        )
        assertEquals(PreviewErrorKind.MANAGER_CLOSED, failure.error.kind)
    }

    @Test
    fun `cancelled generation cannot receive an otherwise cached page`() {
        val generation = generation(temporaryDirectory)
        val scheduler = scheduler(workerCount = 1)
        scheduler.registerGeneration(generation)
        val request = request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE)
        assertIs<PreviewResult.Success<*>>(
            scheduler.request(request).future.get(2, TimeUnit.SECONDS)
        )

        scheduler.cancelGeneration(generation.id)
        val failure = assertIs<PreviewResult.Failure>(
            scheduler.request(
                request.copy(token = RequestToken(2))
            ).future.get(2, TimeUnit.SECONDS)
        )

        assertEquals(PreviewErrorKind.GENERATION_OBSOLETE, failure.error.kind)
    }

    @Test
    fun `queue capacity discards speculative work before visible work`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = renderer(pageCount = 3) { key ->
            if (key.pageIndex == 0) {
                started.countDown()
                release.await()
            }
        }
        val generation = generation(temporaryDirectory, renderer)
        val cache = PageCache(PageCachePolicy(2_000_000))
        closeables += cache
        val scheduler = RenderScheduler(cache, RenderSchedulerPolicy(1, 1))
        closeables += scheduler
        scheduler.registerGeneration(generation)
        val running = scheduler.request(request(key(generation.id, 0), 1, RenderPriority.CURRENT_VISIBLE))
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val neighbor = scheduler.request(request(key(generation.id, 1), 2, RenderPriority.NEIGHBOR))
        val visible = scheduler.request(request(key(generation.id, 2), 3, RenderPriority.OTHER_VISIBLE))
        release.countDown()
        running.future.get(2, TimeUnit.SECONDS)
        assertIs<PreviewResult.Success<*>>(visible.future.get(2, TimeUnit.SECONDS))
        val rejected = assertIs<PreviewResult.Failure>(neighbor.future.get(2, TimeUnit.SECONDS))
        assertEquals(PreviewErrorKind.SCHEDULER_OVERLOADED, rejected.error.kind)
    }

    private fun scheduler(workerCount: Int): RenderScheduler {
        val cache = PageCache(PageCachePolicy(2_000_000))
        val scheduler = RenderScheduler(cache, RenderSchedulerPolicy(workerCount, 16))
        closeables += cache
        closeables += scheduler
        return scheduler
    }

    private fun generation(
        root: Path,
        renderer: FakeRenderer = renderer {}
    ) = testGeneration(root, renderer = renderer).also(closeables::add)

    private fun renderer(
        pageCount: Int = 1,
        hook: (PageRenderKey) -> Unit
    ): FakeRenderer = FakeRenderer(
        DocumentMetadata(
            List(pageCount) { PageGeometry(100f, 100f) },
            "fake",
            "1"
        ),
        hook
    )

    private fun key(
        generationId: dev.aetex.preview.domain.GenerationId,
        page: Int
    ) = PageRenderKey(generationId, page, RenderScale.DEFAULT)

    private fun request(
        key: PageRenderKey,
        token: Long,
        priority: RenderPriority
    ) = RenderRequest(
        key,
        PreviewConsumerId("test"),
        RequestToken(token),
        priority
    )

    private fun awaitCondition(
        timeoutNanos: Long = TimeUnit.SECONDS.toNanos(2),
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + timeoutNanos
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.onSpinWait()
        }
        return condition()
    }
}
