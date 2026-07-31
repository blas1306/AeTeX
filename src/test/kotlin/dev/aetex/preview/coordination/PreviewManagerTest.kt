package dev.aetex.preview.coordination

import dev.aetex.compilation.BuildState
import dev.aetex.preview.FakeRenderer
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.generation.GenerationFactory
import dev.aetex.preview.snapshotOf
import dev.aetex.preview.successfulBuildResult
import dev.aetex.preview.terminalBuildResult
import dev.aetex.preview.testGeneration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PreviewManagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val managers = mutableListOf<PreviewManager>()

    @AfterTest
    fun cleanup() {
        managers.reversed().forEach(PreviewManager::close)
    }

    @Test
    fun `first successful result prepares initial page and atomically becomes ready`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val result = successfulBuildResult(root)

        manager.acceptCompilationSnapshot(snapshotOf(result))
        val ready = awaitState(manager) { it is PreviewState.Ready } as PreviewState.Ready

        assertEquals(result.sessionId, ready.document.provenance.sessionId)
        assertIs<dev.aetex.preview.domain.PagePreviewState.Ready>(
            ready.document.pageState(0)
        )
        assertTrue(manager.cacheStats().entryCount in 1..2)
    }

    @Test
    fun `new successful build creates new generation and closes previous resources`() {
        val root = temporaryDirectory.resolve("project")
        val renderers = mutableListOf<FakeRenderer>()
        val manager = manager(root) { build ->
            val renderer = FakeRenderer(
                dev.aetex.preview.domain.DocumentMetadata(
                    listOf(dev.aetex.preview.domain.PageGeometry(100f, 100f)),
                    "fake",
                    "1"
                )
            )
            renderers += renderer
            PreviewResult.Success(
                testGeneration(
                    temporaryDirectory,
                    renderer = renderer,
                    sessionId = build.sessionId
                )
            )
        }
        val first = successfulBuildResult(root, "first")
        manager.acceptCompilationSnapshot(snapshotOf(first))
        val firstReady = awaitState(manager) {
            it is PreviewState.Ready && it.document.provenance.sessionId == first.sessionId
        } as PreviewState.Ready
        val second = successfulBuildResult(
            root,
            "second",
            first.createdAt.plusSeconds(2)
        )

        manager.acceptCompilationSnapshot(snapshotOf(second))
        val secondReady = awaitState(manager) {
            it is PreviewState.Ready && it.document.provenance.sessionId == second.sessionId
        } as PreviewState.Ready

        assertNotEquals(firstReady.document.generationId, secondReady.document.generationId)
        assertTrue(awaitCondition { renderers.first().closed.get() })
        assertTrue(manager.cacheStats().entryCount in 1..2)
    }

    @Test
    fun `failed build keeps last good preview explicitly stale`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val success = successfulBuildResult(root, "success")
        manager.acceptCompilationSnapshot(snapshotOf(success))
        val ready = awaitState(manager) { it is PreviewState.Ready } as PreviewState.Ready
        val failedBase = successfulBuildResult(
            root,
            "failed",
            success.createdAt.plusSeconds(2)
        )

        manager.acceptCompilationSnapshot(
            snapshotOf(terminalBuildResult(failedBase, BuildState.FAILED))
        )
        val stale = awaitState(manager) {
            it is PreviewState.Ready && it.stale
        } as PreviewState.Ready

        assertEquals(ready.document.generationId, stale.document.generationId)
        assertEquals(PreviewErrorKind.BUILD_FAILED, stale.notice?.kind)
    }

    @Test
    fun `cancelled build never creates generation`() {
        val root = temporaryDirectory.resolve("project")
        val creations = AtomicInteger()
        val manager = manager(root) {
            creations.incrementAndGet()
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val base = successfulBuildResult(root)

        manager.acceptCompilationSnapshot(
            snapshotOf(terminalBuildResult(base, BuildState.CANCELLED))
        )
        val error = awaitState(manager) {
            it is PreviewState.GenerationError
        } as PreviewState.GenerationError

        assertEquals(0, creations.get())
        assertEquals(PreviewErrorKind.BUILD_CANCELLED, error.error.kind)
    }

    @Test
    fun `older and duplicate terminal results are ignored`() {
        val root = temporaryDirectory.resolve("project")
        val creations = AtomicInteger()
        val manager = manager(root) {
            creations.incrementAndGet()
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val newer = successfulBuildResult(
            root,
            "newer",
            Instant.parse("2026-07-30T12:00:10Z")
        )
        manager.acceptCompilationSnapshot(snapshotOf(newer))
        awaitState(manager) { it is PreviewState.Ready }
        manager.acceptCompilationSnapshot(snapshotOf(newer))
        val older = successfulBuildResult(
            root,
            "older",
            Instant.parse("2026-07-30T12:00:00Z")
        )
        manager.acceptCompilationSnapshot(snapshotOf(older))

        assertEquals(1, creations.get())
        assertEquals(
            newer.sessionId,
            (manager.state as PreviewState.Ready).document.provenance.sessionId
        )
    }

    @Test
    fun `older cancelled replacement cannot make completed current preview stale`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val current = successfulBuildResult(
            root,
            "current",
            Instant.parse("2026-07-30T12:00:10Z")
        )
        manager.acceptCompilationSnapshot(snapshotOf(current, requestSequence = 2))
        val ready = awaitState(manager) { it is PreviewState.Ready } as PreviewState.Ready
        val replaced = terminalBuildResult(
            successfulBuildResult(
                root,
                "replaced",
                Instant.parse("2026-07-30T12:00:00Z")
            ),
            BuildState.CANCELLED
        )

        manager.acceptCompilationSnapshot(snapshotOf(replaced, requestSequence = 1))

        val afterCancellation = assertIs<PreviewState.Ready>(manager.state)
        assertEquals(ready.document.generationId, afterCancellation.document.generationId)
        assertFalse(afterCancellation.stale)
        assertNull(afterCancellation.notice)
    }

    @Test
    fun `generation preparation error preserves prior functional document`() {
        val root = temporaryDirectory.resolve("project")
        val creations = AtomicInteger()
        val manager = manager(root) {
            if (creations.getAndIncrement() == 0) {
                PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
            } else {
                PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.SNAPSHOT_COPY_FAILED,
                        "Synthetic snapshot failure."
                    )
                )
            }
        }
        val first = successfulBuildResult(root, "first")
        manager.acceptCompilationSnapshot(snapshotOf(first))
        val ready = awaitState(manager) { it is PreviewState.Ready } as PreviewState.Ready
        val second = successfulBuildResult(
            root,
            "second",
            first.createdAt.plusSeconds(2)
        )

        manager.acceptCompilationSnapshot(snapshotOf(second))
        val error = awaitState(manager) {
            it is PreviewState.GenerationError
        } as PreviewState.GenerationError

        assertEquals(PreviewErrorKind.SNAPSHOT_COPY_FAILED, error.error.kind)
        assertEquals(ready.document.generationId, error.previous?.generationId)
    }

    @Test
    fun `page updates cannot erase stale compilation status`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val success = successfulBuildResult(root, "success")
        manager.acceptCompilationSnapshot(snapshotOf(success))
        awaitState(manager) { it is PreviewState.Ready }
        val failedBase = successfulBuildResult(
            root,
            "failed",
            success.createdAt.plusSeconds(2)
        )
        manager.acceptCompilationSnapshot(
            snapshotOf(terminalBuildResult(failedBase, BuildState.FAILED))
        )
        awaitState(manager) { it is PreviewState.Ready && it.stale }

        manager.updateViewport(setOf(1), 1, dev.aetex.preview.domain.RenderScale.DEFAULT)
        val afterRender = awaitState(manager) {
            it is PreviewState.Ready &&
                it.stale &&
                it.document.pageState(1) is dev.aetex.preview.domain.PagePreviewState.Ready
        } as PreviewState.Ready

        assertTrue(afterRender.stale)
        assertEquals(PreviewErrorKind.BUILD_FAILED, afterRender.notice?.kind)
    }

    @Test
    fun `rapid A B C D sequence promotes only latest pending result`() {
        val root = temporaryDirectory.resolve("project")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger()
        val manager = manager(root) { result ->
            if (calls.getAndIncrement() == 0) {
                firstStarted.countDown()
                releaseFirst.await()
            }
            val generation = testGeneration(
                temporaryDirectory,
                GenerationId.create(),
                sessionId = result.sessionId
            )
            PreviewResult.Success(generation)
        }
        val start = Instant.parse("2026-07-30T12:00:00Z")
        val results = listOf("A", "B", "C", "D").mapIndexed { index, name ->
            successfulBuildResult(root, name, start.plusSeconds(index.toLong()))
        }
        manager.acceptCompilationSnapshot(snapshotOf(results[0]))
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        results.drop(1).forEach { manager.acceptCompilationSnapshot(snapshotOf(it)) }
        releaseFirst.countDown()

        val ready = awaitState(manager, timeoutSeconds = 4) {
            it is PreviewState.Ready &&
                it.document.provenance.sessionId == results.last().sessionId
        } as PreviewState.Ready

        assertEquals(results.last().sessionId, ready.document.provenance.sessionId)
        assertEquals(2, calls.get(), "Only A and the latest queued D should prepare.")
    }

    @Test
    fun `newer nonterminal request prevents older preparation from promoting`() {
        val root = temporaryDirectory.resolve("project")
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger()
        val secondRenderer = AtomicReference<FakeRenderer?>()
        val manager = manager(root) { result ->
            val call = calls.incrementAndGet()
            val renderer = FakeRenderer(
                dev.aetex.preview.domain.DocumentMetadata(
                    listOf(dev.aetex.preview.domain.PageGeometry(100f, 100f)),
                    "fake",
                    "1"
                )
            )
            if (call == 2) {
                secondRenderer.set(renderer)
                secondStarted.countDown()
                releaseSecond.await()
            }
            PreviewResult.Success(
                testGeneration(
                    temporaryDirectory,
                    renderer = renderer,
                    sessionId = result.sessionId
                )
            )
        }
        val first = successfulBuildResult(root, "A")
        manager.acceptCompilationSnapshot(snapshotOf(first, requestSequence = 1))
        val firstReady = awaitState(manager) { it is PreviewState.Ready } as PreviewState.Ready
        val second = successfulBuildResult(root, "B", first.createdAt.plusSeconds(1))
        manager.acceptCompilationSnapshot(snapshotOf(second, requestSequence = 2))
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        val third = successfulBuildResult(root, "C", first.createdAt.plusSeconds(2))
        val thirdRunning = snapshotOf(third, requestSequence = 3).copy(
            state = BuildState.RUNNING,
            result = null,
            finishedAt = null
        )

        manager.acceptCompilationSnapshot(thirdRunning)
        releaseSecond.countDown()
        val duringThird = awaitState(manager) {
            it is PreviewState.Ready && it.buildInProgress
        } as PreviewState.Ready

        assertEquals(firstReady.document.generationId, duringThird.document.generationId)
        assertTrue(
            awaitCondition {
                secondRenderer.get()?.closed?.get() == true
            }
        )

        manager.acceptCompilationSnapshot(snapshotOf(third, requestSequence = 3))
        val thirdReady = awaitState(manager) {
            it is PreviewState.Ready &&
                it.document.provenance.sessionId == third.sessionId
        } as PreviewState.Ready
        assertEquals(third.sessionId, thirdReady.document.provenance.sessionId)
    }

    @Test
    fun `visible report is bounded before scheduling and pinning`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(
                testGeneration(
                    temporaryDirectory,
                    renderer = FakeRenderer(
                        dev.aetex.preview.domain.DocumentMetadata(
                            List(300) { dev.aetex.preview.domain.PageGeometry(10f, 10f) },
                            "fake",
                            "1"
                        )
                    ),
                    sessionId = it.sessionId
                )
            )
        }
        manager.acceptCompilationSnapshot(snapshotOf(successfulBuildResult(root)))
        awaitState(manager) { it is PreviewState.Ready }

        manager.updateViewport((0 until 300).toSet(), 0, RenderScale.DEFAULT)

        assertTrue(manager.schedulerStats().queued <= 8)
    }

    @Test
    fun `rapid responsive scale changes supersede obsolete requests within bounds`() {
        val root = temporaryDirectory.resolve("responsive-project")
        val responsiveRenderStarted = CountDownLatch(1)
        val releaseResponsiveRender = CountDownLatch(1)
        val renderer = FakeRenderer(
            dev.aetex.preview.domain.DocumentMetadata(
                listOf(
                    dev.aetex.preview.domain.PageGeometry(200f, 300f),
                    dev.aetex.preview.domain.PageGeometry(300f, 200f)
                ),
                "fake",
                "1"
            ),
            beforeRender = { key ->
                if (key.scale != RenderScale.DEFAULT) {
                    responsiveRenderStarted.countDown()
                    releaseResponsiveRender.await()
                }
            }
        )
        val manager = manager(root) {
            PreviewResult.Success(
                testGeneration(
                    temporaryDirectory,
                    renderer = renderer,
                    sessionId = it.sessionId
                )
            )
        }
        manager.acceptCompilationSnapshot(snapshotOf(successfulBuildResult(root)))
        val initial = awaitState(manager) { it is PreviewState.Ready } as PreviewState.Ready
        val generation = initial.document.generationId

        manager.updateViewport(setOf(0), 0, RenderScale.normalized(0.75))
        assertTrue(responsiveRenderStarted.await(2, TimeUnit.SECONDS))
        listOf(1.0, 1.26, 1.34, 1.5, 1.0, 1.25, 1.0, 1.5).forEach {
            manager.updateViewport(setOf(0), 0, RenderScale.normalized(it))
        }

        val duringResize = manager.schedulerStats()
        assertTrue(duringResize.queued <= 8)
        assertTrue(duringResize.pendingConsumers <= 3)
        assertEquals(generation, (manager.state as PreviewState.Ready).document.generationId)

        releaseResponsiveRender.countDown()
        val finalScale = RenderScale.normalized(1.5)
        val final = awaitState(manager) {
            it is PreviewState.Ready &&
                it.document.scale == finalScale &&
                it.document.pageState(0) is
                dev.aetex.preview.domain.PagePreviewState.Ready
        } as PreviewState.Ready

        assertEquals(generation, final.document.generationId)
        assertEquals(finalScale, final.document.scale)
    }

    @Test
    fun `request sequence orders equal-clock terminal results`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val instant = Instant.parse("2026-07-30T12:00:00Z")
        val first = successfulBuildResult(root, "first", instant)
        val second = successfulBuildResult(root, "second", instant)

        manager.acceptCompilationSnapshot(snapshotOf(first, requestSequence = 1))
        awaitState(manager) { it is PreviewState.Ready }
        manager.acceptCompilationSnapshot(snapshotOf(second, requestSequence = 2))
        val ready = awaitState(manager) {
            it is PreviewState.Ready &&
                it.document.provenance.sessionId == second.sessionId
        } as PreviewState.Ready

        assertEquals(second.sessionId, ready.document.provenance.sessionId)
    }

    @Test
    fun `unexpected generation factory exception becomes typed preview failure`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            error("synthetic factory failure")
        }

        manager.acceptCompilationSnapshot(snapshotOf(successfulBuildResult(root)))
        val failure = awaitState(manager) {
            it is PreviewState.GenerationError
        } as PreviewState.GenerationError

        assertEquals(PreviewErrorKind.INTERNAL, failure.error.kind)
        assertEquals(0, manager.schedulerStats().inFlight)
    }

    @Test
    fun `concurrent close calls converge without residual work`() {
        val root = temporaryDirectory.resolve("project")
        val renderStarted = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val manager = manager(root) { result ->
            PreviewResult.Success(
                testGeneration(
                    temporaryDirectory,
                    renderer = dev.aetex.preview.blockingRenderer(
                        renderStarted,
                        releaseRender
                    ),
                    sessionId = result.sessionId
                )
            )
        }
        manager.acceptCompilationSnapshot(snapshotOf(successfulBuildResult(root)))
        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))
        val startClose = CountDownLatch(1)
        val closeThreads = List(2) {
            thread {
                startClose.await()
                manager.close()
            }
        }

        startClose.countDown()
        releaseRender.countDown()
        closeThreads.forEach { it.join(4_000) }

        assertTrue(closeThreads.none(Thread::isAlive))
        assertEquals(PreviewState.Closed, manager.state)
        assertEquals(0L, manager.cacheStats().reservedBytes)
        assertEquals(0, manager.cacheStats().entryCount)
        assertEquals(0, manager.cacheStats().pinnedKeyCount)
        assertEquals(0, manager.cacheStats().pinOwnerCount)
        assertEquals(0, manager.schedulerStats().queued)
        assertEquals(0, manager.schedulerStats().inFlight)
        assertEquals(0, manager.schedulerStats().pendingConsumers)
        assertTrue(manager.schedulerStats().workerPoolTerminated)
        assertTrue(
            Thread.getAllStackTraces().keys.none {
                it.isAlive && it.name.startsWith("aetex-preview-")
            }
        )
    }

    @Test
    fun `result from another project is ignored`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val other = successfulBuildResult(temporaryDirectory.resolve("other"), "other")

        manager.acceptCompilationSnapshot(snapshotOf(other))

        assertEquals(PreviewState.Empty, manager.state)
    }

    @Test
    fun `close is idempotent rejects late publication and clears resources`() {
        val root = temporaryDirectory.resolve("project")
        val manager = manager(root) {
            PreviewResult.Success(testGeneration(temporaryDirectory, sessionId = it.sessionId))
        }
        val result = successfulBuildResult(root)
        manager.acceptCompilationSnapshot(snapshotOf(result))
        awaitState(manager) { it is PreviewState.Ready }

        manager.close()
        manager.close()
        manager.acceptCompilationSnapshot(snapshotOf(result))

        assertEquals(PreviewState.Closed, manager.state)
        assertEquals(0, manager.cacheStats().entryCount)
        assertEquals(0, manager.schedulerStats().inFlight)
    }

    private fun manager(
        root: Path,
        factory: GenerationFactory
    ): PreviewManager {
        Files.createDirectories(root)
        return PreviewManager(
            compilationManager = null,
            projectRoot = root,
            generationFactory = factory,
            policy = PreviewPolicy(
                neighborRadius = 1,
                cache = dev.aetex.preview.cache.PageCachePolicy(2_000_000),
                scheduler = dev.aetex.preview.scheduling.RenderSchedulerPolicy(1, 8)
            )
        ).also(managers::add)
    }

    private fun awaitState(
        manager: PreviewManager,
        timeoutSeconds: Long = 2,
        predicate: (PreviewState) -> Boolean
    ): PreviewState {
        val found = AtomicReference<PreviewState?>()
        val latch = CountDownLatch(1)
        val subscription = manager.addStateListener {
            if (predicate(it)) {
                found.compareAndSet(null, it)
                latch.countDown()
            }
        }
        try {
            assertTrue(
                latch.await(timeoutSeconds, TimeUnit.SECONDS),
                "Timed out waiting for preview state; current=${manager.state}"
            )
            return checkNotNull(found.get())
        } finally {
            subscription.close()
        }
    }

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
