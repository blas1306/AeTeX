package dev.aetex.preview.coordination

import dev.aetex.compilation.BuildResult
import dev.aetex.compilation.BuildSessionId
import dev.aetex.compilation.BuildSessionSnapshot
import dev.aetex.compilation.BuildState
import dev.aetex.compilation.CompilationManager
import dev.aetex.preview.cache.PageCache
import dev.aetex.preview.cache.PageCachePolicy
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PagePreviewState
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewConsumerId
import dev.aetex.preview.domain.PreviewDocument
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.domain.RenderPriority
import dev.aetex.preview.domain.RenderRequest
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.domain.RequestToken
import dev.aetex.preview.generation.DocumentGeneration
import dev.aetex.preview.generation.DefaultGenerationFactory
import dev.aetex.preview.generation.GenerationFactory
import dev.aetex.preview.scheduling.RenderHandle
import dev.aetex.preview.scheduling.RenderScheduler
import dev.aetex.preview.scheduling.RenderSchedulerPolicy
import dev.aetex.preview.scheduling.RenderStage
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

data class PreviewPolicy(
    val neighborRadius: Int = 1,
    val maximumReportedVisiblePages: Int = 128,
    val cache: PageCachePolicy = PageCachePolicy(),
    val scheduler: RenderSchedulerPolicy = RenderSchedulerPolicy()
) {
    init {
        require(neighborRadius in 0..16)
        require(maximumReportedVisiblePages in 1..1_024)
    }
}

class PreviewManager internal constructor(
    compilationManager: CompilationManager?,
    projectRoot: Path,
    private val generationFactory: GenerationFactory = DefaultGenerationFactory,
    private val policy: PreviewPolicy = PreviewPolicy(),
    private val preparationExecutor: ExecutorService =
        defaultPreparationExecutor(),
    cacheFactory: (PageCachePolicy) -> PageCache = ::PageCache,
    schedulerFactory: (
        PageCache,
        RenderSchedulerPolicy,
        (RenderRequest, RenderStage) -> Unit
    ) -> RenderScheduler = { cache, schedulerPolicy, listener ->
        RenderScheduler(cache, schedulerPolicy, stageListener = listener)
    }
) : AutoCloseable {
    private val lock = Any()
    private val notificationLock = Any()
    private val normalizedProjectRoot = projectRoot.toAbsolutePath().normalize()
    private val projectRealRoot = requireRealProjectRoot(normalizedProjectRoot)
    private val projectRootFileKey = rootFileKey(projectRealRoot)
    private val cache = cacheFactory(policy.cache)
    private val scheduler =
        schedulerFactory(cache, policy.scheduler, ::onRenderStage)
    private val stateListeners = CopyOnWriteArrayList<(PreviewState) -> Unit>()
    private val subscription: AutoCloseable? =
        compilationManager?.addSessionListener(::acceptCompilationSnapshot)
    private var lastProcessedTerminalSessionId: String? = null
    private val pageStates = LinkedHashMap<Int, PagePreviewState>()
    private val viewHandles = LinkedHashMap<PageRenderKey, RequestedRender>()
    private var activeGeneration: DocumentGeneration? = null
    private var pendingGeneration: DocumentGeneration? = null
    private var preparationVersion = 0L
    private var requestToken = 0L
    private var latestBuildCreatedAt: Instant? = null
    private var latestBuildRequestSequence = 0L
    private var latestBuildSessionId: BuildSessionId? = null
    private var desiredScale = RenderScale.DEFAULT
    private var desiredCurrentPage = 0
    private var desiredVisiblePages: Set<Int> = setOf(0)
    private var activeStale = false
    private var activeNotice: PreviewError? = null
    private var activeGenerationError: PreviewError? = null
    private var buildInProgress = false
    private var closed = false
    private var publicationSequence = 0L
    private var lastDeliveredPublication = 0L
    private val closeCompleted = CountDownLatch(1)
    @Volatile
    private var closingThread: Thread? = null

    @Volatile
    private var publishedState: PreviewState = PreviewState.Empty

    val state: PreviewState
        get() = publishedState

    fun addStateListener(listener: (PreviewState) -> Unit): AutoCloseable {
        stateListeners += listener
        listener(publishedState)
        return AutoCloseable { stateListeners -= listener }
    }

    fun updateViewport(
        visiblePages: Set<Int>,
        currentPageIndex: Int,
        scale: RenderScale,
        scrollDirection: Int = 0
    ) {
        val generation: DocumentGeneration
        synchronized(lock) {
            if (closed) return
            desiredScale = scale
            desiredCurrentPage = currentPageIndex.coerceAtLeast(0)
            desiredVisiblePages = visiblePages.asSequence()
                .filter { it >= 0 }
                .take(policy.maximumReportedVisiblePages)
                .toCollection(linkedSetOf())
                .ifEmpty { setOf(desiredCurrentPage) }
            generation = activeGeneration ?: return
            if (pendingGeneration != null) return
        }
        requestViewport(generation, scrollDirection)
    }

    fun retryPage(pageIndex: Int) {
        val generation = synchronized(lock) {
            if (closed || pendingGeneration != null) return
            activeGeneration
        } ?: return
        if (pageIndex !in generation.metadata.pages.indices) return
        requestPage(generation, pageIndex, desiredScale, RenderPriority.CURRENT_VISIBLE)
    }

    fun cacheStats() = cache.stats()
    fun schedulerStats() = scheduler.stats()

    override fun close() {
        val generations: List<DocumentGeneration>
        var handlesToCancel: List<RenderHandle> = emptyList()
        var ownsClose = false
        synchronized(lock) {
            if (!closed) {
                closed = true
                ownsClose = true
                closingThread = Thread.currentThread()
                preparationVersion++
                handlesToCancel = viewHandles.values.map(RequestedRender::handle)
                viewHandles.clear()
                generations = listOfNotNull(activeGeneration, pendingGeneration).distinct()
                activeGeneration = null
                pendingGeneration = null
                pageStates.clear()
                activeStale = false
                activeNotice = null
                activeGenerationError = null
                buildInProgress = false
            } else {
                generations = emptyList()
            }
        }
        if (!ownsClose) {
            if (closingThread !== Thread.currentThread()) {
                try {
                    closeCompleted.await(
                        policy.scheduler.shutdownWait.plusSeconds(3).toMillis(),
                        TimeUnit.MILLISECONDS
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            return
        }
        handlesToCancel.forEach(RenderHandle::cancel)
        try {
            subscription?.close()
        } catch (_: Exception) {
            // Closing the manager remains idempotent even if an observer source fails.
        }
        preparationExecutor.shutdownNow()
        generations.forEach { generation ->
            scheduler.cancelGeneration(generation.id)
            cache.releasePins(PreviewConsumerId.PRIMARY_VIEW.value)
            cache.invalidateGeneration(generation.id)
            generation.retire()
        }
        scheduler.close()
        cache.stats().let {
            LOGGER.fine(
                "Closing preview cache: entries=${it.entryCount}, bytes=${it.retainedBytes}, " +
                    "hits=${it.hits}, misses=${it.misses}, evictions=${it.evictions}."
            )
        }
        cache.close()
        try {
            if (!preparationExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warning(
                    "Preview preparation shutdown timed out; private snapshot cleanup " +
                        "may still be draining on a daemon worker."
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        publish(PreviewState.Closed)
        stateListeners.clear()
        closingThread = null
        closeCompleted.countDown()
    }

    internal fun acceptCompilationSnapshot(snapshot: BuildSessionSnapshot) {
        if (!belongsToCurrentProject(snapshot)) {
            return
        }
        if (!snapshot.state.isTerminal) {
            var pendingToRetire: DocumentGeneration? = null
            var handlesToCancel: List<RenderHandle> = emptyList()
            var publication: StatePublication? = null
            synchronized(lock) {
                if (closed || isOlder(snapshot)) return
                if (isNewerRequest(snapshot)) {
                    preparationVersion++
                    pendingToRetire = pendingGeneration
                    pendingGeneration = null
                    handlesToCancel = detachViewRequestsLocked()
                }
                recordLatest(snapshot)
                buildInProgress = true
                val current = activeDocument()
                publication = setStateLocked(
                    if (current == null) {
                        PreviewState.LoadingGeneration(buildInProgress = true)
                    } else {
                        activePresentation(current)
                    }
                )
            }
            handlesToCancel.forEach(RenderHandle::cancel)
            publication?.let(::dispatchPublication)
            pendingToRetire?.let(::retireGeneration)
            return
        }
        val result = snapshot.result ?: return
        if (
            result.sessionId != snapshot.id ||
            result.state != snapshot.state ||
            result.plan !== snapshot.plan
        ) {
            return
        }
        synchronized(lock) {
            if (
                closed ||
                isOlder(snapshot) ||
                lastProcessedTerminalSessionId == result.sessionId.value
            ) return
            lastProcessedTerminalSessionId = result.sessionId.value
            recordLatest(snapshot)
        }
        when (result.state) {
            BuildState.SUCCEEDED -> prepareGeneration(result)
            BuildState.FAILED -> retainLastGood(
                PreviewError(
                    PreviewErrorKind.BUILD_FAILED,
                    "The latest compilation failed; the previous preview is stale.",
                    technicalCause = result.failure?.technicalCause?.let {
                        IllegalStateException("${it.type}: ${it.message.orEmpty()}")
                    }
                )
            )

            BuildState.CANCELLED -> retainLastGood(
                PreviewError(
                    PreviewErrorKind.BUILD_CANCELLED,
                    "The latest compilation was cancelled; the previous preview is stale."
                )
            )

            else -> Unit
        }
    }

    private fun prepareGeneration(result: BuildResult) {
        val version: Long
        val previous: PreviewDocument?
        val oldPending: DocumentGeneration?
        val handlesToCancel: List<RenderHandle>
        val publication: StatePublication
        synchronized(lock) {
            if (closed) return
            version = ++preparationVersion
            oldPending = pendingGeneration
            pendingGeneration = null
            handlesToCancel = detachViewRequestsLocked()
            buildInProgress = false
            activeGenerationError = null
            previous = activeDocument()
            publication = setStateLocked(PreviewState.LoadingGeneration(previous))
        }
        handlesToCancel.forEach(RenderHandle::cancel)
        dispatchPublication(publication)
        oldPending?.let(::retireGeneration)

        try {
            preparationExecutor.execute {
                synchronized(lock) {
                    if (closed || version != preparationVersion) return@execute
                }
                try {
                    when (val created = generationFactory.create(result)) {
                        is PreviewResult.Failure -> preparationFailed(version, created.error)
                        is PreviewResult.Success -> startInitialRender(version, created.value)
                    }
                } catch (error: Throwable) {
                    preparationFailed(
                        version,
                        PreviewError(
                            PreviewErrorKind.INTERNAL,
                            "The PDF preview generation could not be prepared.",
                            technicalCause = error
                        )
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            preparationFailed(
                version,
                PreviewError(
                    PreviewErrorKind.MANAGER_CLOSED,
                    "The PDF preview manager closed before preparation could start."
                )
            )
        }
    }

    private fun startInitialRender(version: Long, generation: DocumentGeneration) {
        val initialPage: Int
        val scale: RenderScale
        synchronized(lock) {
            if (closed || version != preparationVersion) {
                generation.retire()
                return
            }
            pendingGeneration = generation
            scheduler.registerGeneration(generation)
            initialPage = desiredCurrentPage.coerceIn(generation.metadata.pages.indices)
            scale = desiredScale
            cache.updatePins(
                pendingConsumerKey(generation.id),
                setOf(PageRenderKey(generation.id, initialPage, scale))
            )
        }
        val request = RenderRequest(
            PageRenderKey(generation.id, initialPage, scale),
            PreviewConsumerId.PRIMARY_VIEW,
            nextToken(),
            RenderPriority.INITIAL
        )
        val handle = scheduler.request(request)
        handle.future.whenComplete { result, throwable ->
            val resolved = result ?: PreviewResult.Failure(
                PreviewError(
                    PreviewErrorKind.RENDER_FAILED,
                    "The first preview page could not be rendered.",
                    generation.id,
                    initialPage,
                    scale,
                    throwable
                )
            )
            when (resolved) {
                is PreviewResult.Success ->
                    promote(version, generation, initialPage, scale, resolved.value)

                is PreviewResult.Failure ->
                    preparationFailed(version, resolved.error, generation)
            }
        }
    }

    private fun promote(
        version: Long,
        generation: DocumentGeneration,
        initialPage: Int,
        scale: RenderScale,
        renderedPage: dev.aetex.preview.domain.RenderedPage
    ) {
        val old: DocumentGeneration?
        var rejected = false
        var publication: StatePublication? = null
        synchronized(lock) {
            if (
                closed ||
                version != preparationVersion ||
                pendingGeneration !== generation ||
                !projectIdentityIsCurrent() ||
                !generation.activate()
            ) {
                rejected = true
                old = null
            } else {
                old = activeGeneration
                activeGeneration = generation
                pendingGeneration = null
                desiredCurrentPage = initialPage
                desiredScale = scale
                pageStates.clear()
                pageStates[initialPage] = PagePreviewState.Ready(renderedPage)
                activeStale = false
                activeNotice = null
                activeGenerationError = null
                buildInProgress = false
                cache.updatePins(PreviewConsumerId.PRIMARY_VIEW.value, setOf(renderedPage.key))
                cache.releasePins(pendingConsumerKey(generation.id))
                publication =
                    setStateLocked(activePresentation(checkNotNull(activeDocument())))
            }
        }
        publication?.let(::dispatchPublication)
        if (rejected) {
            retireGeneration(generation)
            return
        }
        old?.let(::retireGeneration)
        requestViewport(generation, 0)
        LOGGER.fine("Promoted preview generation ${generation.id.value}.")
    }

    private fun preparationFailed(
        version: Long,
        error: PreviewError,
        generation: DocumentGeneration? = null
    ) {
        val obsolete = synchronized(lock) {
            version != preparationVersion || closed
        }
        if (obsolete) {
            generation?.let(::retireGeneration)
            return
        }
        val resume: DocumentGeneration?
        val publication: StatePublication
        synchronized(lock) {
            if (pendingGeneration === generation) pendingGeneration = null
            val previous = activeDocument()
            activeGenerationError = error
            activeStale = previous != null
            buildInProgress = false
            publication = setStateLocked(PreviewState.GenerationError(error, previous))
            resume = activeGeneration
        }
        dispatchPublication(publication)
        generation?.let(::retireGeneration)
        resume?.let { requestViewport(it, 0) }
        LOGGER.log(Level.WARNING, error.message, error.technicalCause)
    }

    private fun retainLastGood(error: PreviewError) {
        val pending: DocumentGeneration?
        val resume: DocumentGeneration?
        val publication: StatePublication
        synchronized(lock) {
            if (closed) return
            preparationVersion++
            pending = pendingGeneration
            pendingGeneration = null
            val previous = activeDocument()
            activeGenerationError = null
            activeStale = previous != null
            activeNotice = error
            buildInProgress = false
            publication = setStateLocked(
                if (previous == null) PreviewState.GenerationError(error)
                else activePresentation(previous)
            )
            resume = activeGeneration
        }
        dispatchPublication(publication)
        pending?.let(::retireGeneration)
        resume?.let { requestViewport(it, 0) }
    }

    private fun requestViewport(generation: DocumentGeneration, scrollDirection: Int) {
        val visible: Set<Int>
        val current: Int
        val scale: RenderScale
        synchronized(lock) {
            if (closed || activeGeneration !== generation || pendingGeneration != null) return
            visible = desiredVisiblePages.filterTo(linkedSetOf()) {
                it in generation.metadata.pages.indices
            }
            current = desiredCurrentPage.coerceIn(generation.metadata.pages.indices)
            scale = desiredScale
        }
        val requested = LinkedHashMap<Int, RenderPriority>()
        requested[current] = RenderPriority.CURRENT_VISIBLE
        visible.forEach { page ->
            requested.putIfAbsent(page, RenderPriority.OTHER_VISIBLE)
        }
        neighborPages(visible, generation.metadata.pageCount, policy.neighborRadius).forEach { page ->
            requested.putIfAbsent(
                page,
                if (
                    scrollDirection != 0 &&
                    visible.isNotEmpty() &&
                    (page > visible.max() && scrollDirection > 0 ||
                        page < visible.min() && scrollDirection < 0)
                ) {
                    RenderPriority.NEIGHBOR_IN_DIRECTION
                } else {
                    RenderPriority.NEIGHBOR
                }
            )
        }

        val desiredKeys = requested.keys.mapTo(linkedSetOf()) {
            PageRenderKey(generation.id, it, scale)
        }
        val handlesToCancel = synchronized(lock) {
            val detached = mutableListOf<RenderHandle>()
            viewHandles.entries.removeIf { (key, requestedRender) ->
                if (key !in desiredKeys) {
                    detached += requestedRender.handle
                    true
                } else {
                    false
                }
            }
            val displayedPages = visible + current
            pageStates.keys.removeIf { it !in displayedPages }
            refreshPinsLocked()
            detached
        }
        handlesToCancel.forEach(RenderHandle::cancel)
        requested.forEach { (page, priority) ->
            val key = PageRenderKey(generation.id, page, scale)
            val alreadyReady = synchronized(lock) {
                val state = pageStates[page]
                state is PagePreviewState.Ready && state.page.key == key
            }
            if (!alreadyReady) requestPage(generation, page, scale, priority)
        }
        publishReady()
    }

    private fun requestPage(
        generation: DocumentGeneration,
        pageIndex: Int,
        scale: RenderScale,
        priority: RenderPriority
    ) {
        val key = PageRenderKey(generation.id, pageIndex, scale)
        val previousRequest: RequestedRender?
        val shouldPublishState: Boolean
        synchronized(lock) {
            if (
                closed ||
                activeGeneration !== generation
            ) return
            previousRequest = viewHandles[key]
            if (
                previousRequest != null &&
                priority.rank >= previousRequest.priority.rank
            ) return
            shouldPublishState =
                pageIndex == desiredCurrentPage || pageIndex in desiredVisiblePages
            if (shouldPublishState) {
                val previous = (pageStates[pageIndex] as? PagePreviewState.Ready)?.page
                pageStates[pageIndex] = PagePreviewState.Queued(scale, previous)
            }
        }
        val request = RenderRequest(
            key,
            PreviewConsumerId.PRIMARY_VIEW,
            nextToken(),
            priority
        )
        val handle = scheduler.request(request)
        synchronized(lock) {
            if (closed || activeGeneration !== generation) {
                handle.cancel()
                return
            }
            viewHandles[key] = RequestedRender(handle, priority)
        }
        previousRequest?.handle?.cancel()
        handle.future.whenComplete { result, throwable ->
            var publication: StatePublication? = null
            synchronized(lock) {
                if (closed || activeGeneration !== generation) return@whenComplete
                if (viewHandles[key]?.handle !== handle) return@whenComplete
                viewHandles.remove(key)
                val resolved = result ?: PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.RENDER_FAILED,
                        "The page render failed unexpectedly.",
                        generation.id,
                        pageIndex,
                        scale,
                        throwable
                    )
                )
                val stillDisplayed =
                    pageIndex == desiredCurrentPage || pageIndex in desiredVisiblePages
                if (!stillDisplayed) {
                    pageStates.remove(pageIndex)
                } else {
                    pageStates[pageIndex] = when (resolved) {
                        is PreviewResult.Success -> PagePreviewState.Ready(resolved.value)
                        is PreviewResult.Failure -> {
                            if (resolved.error.kind == PreviewErrorKind.GENERATION_OBSOLETE) {
                                pageStates[pageIndex] ?: PagePreviewState.NotRequested
                            } else {
                                LOGGER.log(
                                    Level.WARNING,
                                    "Preview render failed for generation ${generation.id.value}, " +
                                        "page=$pageIndex, scale=${scale.milliScale}.",
                                    resolved.error.technicalCause
                                )
                                PagePreviewState.Failed(resolved.error)
                            }
                        }
                    }
                }
                refreshPinsLocked()
                publication =
                    setStateLocked(activePresentation(checkNotNull(activeDocument())))
            }
            publication?.let(::dispatchPublication)
        }
        publishReady()
    }

    private fun onRenderStage(request: RenderRequest, stage: RenderStage) {
        var publication: StatePublication? = null
        synchronized(lock) {
            val generation = activeGeneration ?: return
            if (closed || request.key.generationId != generation.id) return
            if (
                request.key.pageIndex != desiredCurrentPage &&
                request.key.pageIndex !in desiredVisiblePages
            ) return
            val previous = when (val current = pageStates[request.key.pageIndex]) {
                is PagePreviewState.Ready -> current.page
                is PagePreviewState.Queued -> current.previous
                is PagePreviewState.Rendering -> current.previous
                else -> null
            }
            pageStates[request.key.pageIndex] = when (stage) {
                RenderStage.QUEUED -> PagePreviewState.Queued(request.key.scale, previous)
                RenderStage.RENDERING -> PagePreviewState.Rendering(request.key.scale, previous)
            }
            publication = setStateLocked(activePresentation(activeDocument() ?: return))
        }
        publication?.let(::dispatchPublication)
    }

    private fun retireGeneration(generation: DocumentGeneration) {
        scheduler.cancelGeneration(generation.id)
        cache.releasePins(pendingConsumerKey(generation.id))
        cache.invalidateGeneration(generation.id)
        generation.retire()
    }

    private fun activeDocument(): PreviewDocument? {
        val generation = activeGeneration ?: return null
        return PreviewDocument(
            generation.id,
            generation.provenance,
            generation.metadata,
            pageStates,
            desiredCurrentPage.coerceIn(generation.metadata.pages.indices),
            desiredScale
        )
    }

    private fun publishReady() {
        val publication = synchronized(lock) {
            activeDocument()?.let { setStateLocked(activePresentation(it)) }
        }
        publication?.let(::dispatchPublication)
    }

    private fun activePresentation(document: PreviewDocument): PreviewState =
        activeGenerationError?.let { PreviewState.GenerationError(it, document) }
            ?: PreviewState.Ready(
                document,
                stale = activeStale,
                notice = activeNotice,
                buildInProgress = buildInProgress
            )

    private fun refreshPinsLocked() {
        val generationId = activeGeneration?.id ?: return
        val keys = linkedSetOf<PageRenderKey>()
        val generation = activeGeneration ?: return
        val visiblePages = desiredVisiblePages + desiredCurrentPage
        visiblePages
            .filter { it in generation.metadata.pages.indices }
            .mapTo(keys) { PageRenderKey(generationId, it, desiredScale) }
        pageStates.values.mapNotNullTo(keys) { state ->
            val page = when (state) {
                is PagePreviewState.Ready -> state.page
                is PagePreviewState.Queued -> state.previous
                is PagePreviewState.Rendering -> state.previous
                else -> null
            }
            page?.key?.takeIf { it.generationId == generationId }
        }
        cache.updatePins(PreviewConsumerId.PRIMARY_VIEW.value, keys)
    }

    private fun detachViewRequestsLocked(): List<RenderHandle> {
        val handles = viewHandles.values.map(RequestedRender::handle)
        viewHandles.clear()
        val iterator = pageStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.setValue(
                when (val state = entry.value) {
                    is PagePreviewState.Queued ->
                        state.previous?.let { PagePreviewState.Ready(it) }
                            ?: PagePreviewState.NotRequested

                    is PagePreviewState.Rendering ->
                        state.previous?.let { PagePreviewState.Ready(it) }
                            ?: PagePreviewState.NotRequested

                    else -> state
                }
            )
        }
        if (activeGeneration != null) refreshPinsLocked()
        return handles
    }

    private fun pendingConsumerKey(generationId: GenerationId): String =
        "${PreviewConsumerId.PRIMARY_VIEW.value}:pending:${generationId.value}"

    private fun setStateLocked(state: PreviewState): StatePublication {
        publishedState = state
        return StatePublication(++publicationSequence, state)
    }

    private fun dispatchPublication(publication: StatePublication) {
        synchronized(notificationLock) {
            if (publication.sequence <= lastDeliveredPublication) return
            lastDeliveredPublication = publication.sequence
            stateListeners.forEach { listener ->
                try {
                    listener(publication.state)
                } catch (_: Throwable) {
                    // Presentation observers cannot alter preview lifecycle.
                }
            }
        }
    }

    private fun publish(state: PreviewState) {
        val publication = synchronized(lock) { setStateLocked(state) }
        dispatchPublication(publication)
    }

    private fun nextToken(): RequestToken = synchronized(lock) {
        RequestToken(++requestToken)
    }

    private fun isOlder(snapshot: BuildSessionSnapshot): Boolean {
        if (snapshot.requestSequence > 0L && latestBuildRequestSequence > 0L) {
            return snapshot.requestSequence < latestBuildRequestSequence
        }
        val latestCreatedAt = latestBuildCreatedAt ?: return false
        return snapshot.createdAt.isBefore(latestCreatedAt) ||
            (
                snapshot.createdAt == latestCreatedAt &&
                    latestBuildSessionId != null &&
                    latestBuildSessionId != snapshot.id
                )
    }

    private fun isNewerRequest(snapshot: BuildSessionSnapshot): Boolean =
        when {
            snapshot.requestSequence > 0L ->
                snapshot.requestSequence > latestBuildRequestSequence

            latestBuildCreatedAt == null -> true
            snapshot.createdAt.isAfter(latestBuildCreatedAt) -> true
            snapshot.createdAt == latestBuildCreatedAt ->
                latestBuildSessionId != snapshot.id

            else -> false
        }

    private fun recordLatest(snapshot: BuildSessionSnapshot) {
        if (snapshot.requestSequence > 0L) {
            latestBuildRequestSequence =
                maxOf(latestBuildRequestSequence, snapshot.requestSequence)
        }
        latestBuildCreatedAt = maxOfInstant(latestBuildCreatedAt, snapshot.createdAt)
        if (
            snapshot.requestSequence == 0L ||
            snapshot.requestSequence >= latestBuildRequestSequence
        ) {
            latestBuildSessionId = snapshot.id
        }
    }

    private fun belongsToCurrentProject(snapshot: BuildSessionSnapshot): Boolean {
        if (
            snapshot.plan.workingDirectory.toAbsolutePath().normalize() != normalizedProjectRoot
        ) {
            return false
        }
        return projectIdentityIsCurrent()
    }

    private fun projectIdentityIsCurrent(): Boolean = try {
        val currentReal = normalizedProjectRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
        currentReal == projectRealRoot &&
            !Files.isSymbolicLink(normalizedProjectRoot) &&
            rootFileKey(currentReal) == projectRootFileKey
    } catch (_: Exception) {
        false
    }

    companion object {
        private val LOGGER = Logger.getLogger(PreviewManager::class.java.name)

        fun neighborPages(
            visiblePages: Set<Int>,
            pageCount: Int,
            radius: Int
        ): Set<Int> {
            if (visiblePages.isEmpty() || pageCount <= 0 || radius <= 0) return emptySet()
            val result = linkedSetOf<Int>()
            visiblePages.forEach { visible ->
                for (distance in 1..radius) {
                    listOf(visible - distance, visible + distance)
                        .filterTo(result) { it in 0 until pageCount && it !in visiblePages }
                }
            }
            return result
        }

        private fun maxOfInstant(first: Instant?, second: Instant): Instant =
            if (first == null || second.isAfter(first)) second else first

        private fun rootFileKey(path: Path): String? =
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            ).fileKey()?.toString()

        private fun requireRealProjectRoot(path: Path): Path {
            require(!Files.isSymbolicLink(path)) {
                "The preview project root cannot be a symbolic link."
            }
            val real = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            require(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                "The preview project root must be a real directory."
            }
            return real
        }

        private fun defaultPreparationExecutor(): ExecutorService =
            ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(1),
                PreviewPreparationThreadFactory(),
                ThreadPoolExecutor.DiscardOldestPolicy()
            )
    }

    private data class RequestedRender(
        val handle: RenderHandle,
        val priority: RenderPriority
    )

    private data class StatePublication(
        val sequence: Long,
        val state: PreviewState
    )

    private class PreviewPreparationThreadFactory : ThreadFactory {
        private val counter = AtomicInteger()

        override fun newThread(task: Runnable): Thread =
            Thread(task, "aetex-preview-prepare-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
    }
}
