package dev.aetex.compilation

import dev.aetex.project.TeXProject
import dev.aetex.project.configuration.EffectiveProjectConfiguration
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CompilationManagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `normal zero exit with exact pdf succeeds`() {
        val plan = planWithExistingPdf("normal")
        val process = ControlledProcess(alive = false, exitCode = 0)
        manager(QueueLauncher(process)).use { manager ->
            val accepted = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan))
            val result = manager.awaitResult(accepted.session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.SUCCEEDED, result?.state)
            assertEquals(ArtifactStatus.REUSED_UNCHANGED, result?.artifacts?.first {
                it.expected.role == ArtifactRole.PRIMARY_PDF
            }?.status)
        }
    }

    @Test
    fun `zero exit without exact pdf fails even when another pdf exists`() {
        val root = temporaryDirectory.resolve("missing")
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        Files.writeString(root.resolve("build").resolve("other.pdf"), "wrong")
        val plan = createPlan(root)
        manager(QueueLauncher(ControlledProcess(false, 0))).use { manager ->
            val accepted = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan))
            val result = manager.awaitResult(accepted.session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(BuildFailureKind.EXPECTED_ARTIFACT_MISSING, result?.failure?.kind)
            assertEquals(listOf(plan.primaryPdf), result?.missingRequiredArtifacts?.map(ExpectedArtifact::path))
        }
    }

    @Test
    fun `B and C are replaced by D and A receives one cancellation`() {
        val plan = planWithExistingPdf("replacement")
        val active = ControlledProcess(alive = true, exitCode = 0, stopGracefully = false, stopForcibly = false)
        val replacement = ControlledProcess(alive = false, exitCode = 0)
            val launcher = QueueLauncher(active, replacement)
        manager(
            launcher,
            BuildProcessPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(2))
        ).use { manager ->
            val a = accepted(manager.requestBuild(plan))
            launcher.awaitStarts(1)
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            val b = accepted(manager.requestBuild(plan))
            val c = accepted(manager.requestBuild(plan))
            val d = accepted(manager.requestBuild(plan))

            assertEquals(BuildState.CANCELLED, manager.observeSession(b.id)?.state)
            assertEquals(BuildState.CANCELLED, manager.observeSession(c.id)?.state)
            assertEquals(BuildState.QUEUED, manager.observeSession(d.id)?.state)
            active.awaitGracefulRequest()
            assertEquals(1, active.gracefulCalls.get())
            assertEquals(1, launcher.startCount.get())

            active.complete(130)
            val aResult = manager.awaitResult(a.id, Duration.ofSeconds(3))
            val dResult = manager.awaitResult(d.id, Duration.ofSeconds(3))
            assertEquals(BuildState.CANCELLED, aResult?.state)
            assertEquals(BuildState.SUCCEEDED, dResult?.state)
            assertEquals(2, launcher.startCount.get())
        }
    }

    @Test
    fun `D never starts until A really terminates and lease remains while cancelling`() {
        val plan = planWithExistingPdf("lease")
        val active = ControlledProcess(true, 0, stopGracefully = false, stopForcibly = false)
        val launcher = QueueLauncher(active, ControlledProcess(false, 0))
        manager(
            launcher,
            BuildProcessPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(2))
        ).use { manager ->
            val a = accepted(manager.requestBuild(plan))
            launcher.awaitStarts(1)
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            val d = accepted(manager.requestBuild(plan))
            assertEquals(BuildState.CANCELLING, manager.observeSession(a.id)?.state)

            assertEquals(1, launcher.startCount.get())
            assertEquals(BuildState.QUEUED, manager.observeSession(d.id)?.state)
            assertIs<OutputActivity.Session>(manager.activity(plan.invocation.outputSpaceIdentity))

            active.complete(130)
            assertNotNull(manager.awaitResult(d.id, Duration.ofSeconds(3)))
            assertEquals(2, launcher.startCount.get())
        }
    }

    @Test
    fun `planning failure for B does not cancel running A`() {
        val plan = planWithExistingPdf("planning")
        val active = ControlledProcess(true, 0)
        val launcher = QueueLauncher(active)
        manager(launcher).use { manager ->
            val a = accepted(manager.requestBuild(plan))
            launcher.awaitStarts(1)
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            val invalidProject = TeXProject(
                rootDirectory = plan.workingDirectory,
                entries = emptyList(),
                effectiveConfiguration = EffectiveProjectConfiguration(
                    PersistedConfigurationStatus.INVALID,
                    MainDocumentState.Unavailable,
                    null,
                    null,
                    null
                )
            )

            assertIs<BuildRequestResult.PlanningFailed>(manager.requestBuild(invalidProject))
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            assertEquals(0, active.gracefulCalls.get())
            active.complete(0)
            assertEquals(BuildState.SUCCEEDED, manager.awaitResult(a.id, Duration.ofSeconds(3))?.state)
        }
    }

    @Test
    fun `queued cancellation never starts a process`() {
        val plan = planWithExistingPdf("queued")
        val active = ControlledProcess(true, 0, stopGracefully = false)
        val launcher = QueueLauncher(active, ControlledProcess(false, 0))
        manager(launcher).use { manager ->
            val a = accepted(manager.requestBuild(plan))
            launcher.awaitStarts(1)
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            val queued = accepted(manager.requestBuild(plan))

            val cancelled = assertIs<CancellationRequestResult.Accepted>(
                manager.cancel(queued.id, CancellationOrigin.USER)
            )
            assertEquals(BuildState.CANCELLED, cancelled.session.state)
            assertEquals(CancellationResult.QUEUED_CANCELLED, cancelled.session.cancellation?.result)
            assertEquals(1, launcher.startCount.get())
            active.complete(130)
        }
    }

    @Test
    fun `different output spaces may execute concurrently`() {
        val first = planWithExistingPdf("parallel-one")
        val second = planWithExistingPdf("parallel-two")
        val one = ControlledProcess(true, 0)
        val two = ControlledProcess(true, 0)
        val launcher = QueueLauncher(one, two)
        manager(launcher).use { manager ->
            val a = accepted(manager.requestBuild(first))
            val b = accepted(manager.requestBuild(second))

            launcher.awaitStarts(2)
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            assertEquals(BuildState.RUNNING, manager.observeSession(b.id)?.state)
            one.complete(0)
            two.complete(0)
            assertNotNull(manager.awaitResult(a.id, Duration.ofSeconds(3)))
            assertNotNull(manager.awaitResult(b.id, Duration.ofSeconds(3)))
        }
    }

    @Test
    fun `uncertain cancellation fails creates durable quarantine and blocks replacement`() {
        val plan = planWithExistingPdf("quarantine")
        val orphan = ControlledProcess(
            alive = true,
            exitCode = null,
            stopGracefully = false,
            stopForcibly = false
        )
        val launcher = QueueLauncher(orphan, ControlledProcess(false, 0))
        val manager = manager(
            launcher,
            BuildProcessPolicy(Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(1))
        )
        val a = accepted(manager.requestBuild(plan))
        launcher.awaitStarts(1)
        assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
        val d = accepted(manager.requestBuild(plan))

        val result = manager.awaitResult(a.id, Duration.ofSeconds(3))
        assertEquals(BuildState.FAILED, result?.state)
        assertEquals(BuildFailureKind.CANCELLATION_FAILURE, result?.failure?.kind)
        assertNotNull(result?.quarantine)
        assertEquals(BuildState.QUEUED, manager.observeSession(d.id)?.state)
        assertEquals(1, launcher.startCount.get())
        assertIs<OutputActivity.Quarantined>(manager.activity(plan.invocation.outputSpaceIdentity))
        assertTrue(manager.inspectQuarantine().isNotEmpty())
        orphan.complete(130)
        manager.close()
    }

    @Test
    fun `terminal cancellation request is a no-op`() {
        val plan = planWithExistingPdf("terminal")
        manager(QueueLauncher(ControlledProcess(false, 0))).use { manager ->
            val session = accepted(manager.requestBuild(plan))
            assertNotNull(manager.awaitResult(session.id, Duration.ofSeconds(3)))

            assertIs<CancellationRequestResult.AlreadyTerminal>(
                manager.cancel(session.id)
            )
            assertNull(manager.observeSession(session.id)?.cancellation)
        }
    }

    @Test
    fun `cancellation accepted after natural exit but before publication wins`() {
        val plan = planWithExistingPdf("late-cancel")
        val manager = manager(QueueLauncher(ControlledProcess(false, 0)))
        var cancelled = false
        manager.addSessionListener { snapshot ->
            if (!cancelled && snapshot.state == BuildState.RUNNING) {
                cancelled = true
                manager.cancel(snapshot.id, CancellationOrigin.USER)
            }
        }
        manager.use {
            val session = accepted(manager.requestBuild(plan))
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.CANCELLED, result?.state)
            assertEquals(CancellationResult.GRACEFUL_TERMINATION, result?.cancellation?.result)
        }
    }

    @Test
    fun `log quota failure preserves prefix and prevents success`() {
        val plan = planWithExistingPdf("log-quota")
        val manager = CompilationManager(
            launcher = QueueLauncher(ControlledProcess(false, 0)),
            logFactory = FileBuildLogFactory(
                temporaryDirectory.resolve("tiny-logs"),
                quotaBytes = 96
            ),
            coordinationStore = FileCoordinationStore(temporaryDirectory.resolve("tiny-coord")),
            bootIdentityProvider = BootIdentityProvider { "boot" }
        )
        manager.use {
            val session = accepted(manager.requestBuild(plan))
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(BuildFailureKind.LOG_STORAGE_FAILURE, result?.failure?.kind)
            assertNotNull(result?.logs)
        }
    }

    @Test
    fun `cancelling an accepted session before dispatch never starts its process`() {
        val plan = planWithExistingPdf("pre-dispatch")
        val executor = Executors.newSingleThreadExecutor()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.submit {
            blockerStarted.countDown()
            releaseBlocker.await()
        }
        blockerStarted.await()
        val launcher = QueueLauncher()
        val manager = CompilationManager(
            launcher = launcher,
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("pre-dispatch-logs")),
            coordinationStore = FileCoordinationStore(temporaryDirectory.resolve("pre-dispatch-coord")),
            bootIdentityProvider = BootIdentityProvider { "boot" },
            executor = executor
        )
        try {
            val session = accepted(manager.requestBuild(plan))
            assertEquals(BuildState.QUEUED, manager.observeSession(session.id)?.state)

            val cancelled = assertIs<CancellationRequestResult.Accepted>(
                manager.cancel(session.id, CancellationOrigin.APPLICATION_SHUTDOWN)
            )

            assertEquals(BuildState.CANCELLED, cancelled.session.state)
            releaseBlocker.countDown()
            assertEquals(BuildState.CANCELLED, manager.awaitResult(session.id, Duration.ofSeconds(3))?.state)
            assertEquals(0, launcher.startCount.get())
        } finally {
            releaseBlocker.countDown()
            manager.close()
        }
    }

    @Test
    fun `planning failure for C preserves already queued B`() {
        val plan = planWithExistingPdf("planning-queued")
        val active = ControlledProcess(true, 0, stopGracefully = false, stopForcibly = false)
        val launcher = QueueLauncher(active, ControlledProcess(false, 0))
        manager(
            launcher,
            BuildProcessPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(2))
        ).use { manager ->
            val a = accepted(manager.requestBuild(plan))
            launcher.awaitStarts(1)
            assertEquals(BuildState.RUNNING, manager.observeSession(a.id)?.state)
            val b = accepted(manager.requestBuild(plan))
            val invalidProject = TeXProject(
                rootDirectory = plan.workingDirectory,
                entries = emptyList(),
                effectiveConfiguration = EffectiveProjectConfiguration(
                    PersistedConfigurationStatus.INVALID,
                    MainDocumentState.Unavailable,
                    null,
                    null,
                    null
                )
            )

            assertIs<BuildRequestResult.PlanningFailed>(manager.requestBuild(invalidProject))
            assertEquals(BuildState.QUEUED, manager.observeSession(b.id)?.state)
            active.complete(130)
            assertEquals(BuildState.SUCCEEDED, manager.awaitResult(b.id, Duration.ofSeconds(3))?.state)
        }
    }

    @Test
    fun `listener failure cannot interrupt terminal publication`() {
        val plan = planWithExistingPdf("listener")
        manager(QueueLauncher(ControlledProcess(false, 0))).use { manager ->
            manager.addSessionListener { error("observer failure") }
            val session = accepted(manager.requestBuild(plan))

            assertEquals(BuildState.SUCCEEDED, manager.awaitResult(session.id, Duration.ofSeconds(3))?.state)
        }
    }

    @Test
    fun `changed TeX log is retained as attributed raw evidence and diagnostics`() {
        val plan = createPlan(temporaryDirectory.resolve("tool-log"))
        val launcher = ProcessLauncher {
            Files.writeString(plan.primaryPdf, "pdf")
            Files.writeString(
                plan.invocation.outputDirectory.resolve("main.log"),
                "! Undefined control sequence\n"
            )
            ControlledProcess(false, 0)
        }
        manager(launcher).use { manager ->
            val session = accepted(manager.requestBuild(plan))
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.SUCCEEDED, result?.state)
            assertTrue(result?.logs?.readEvents().orEmpty().any {
                it.origin == BuildLogOrigin.TOOL_FILE &&
                    it.rawBytes.isNotEmpty()
            })
            assertTrue(result?.diagnostics.orEmpty().any {
                it.kind == DiagnosticKind.TEX_ERROR &&
                    it.origin == BuildLogOrigin.TOOL_FILE.name
            })
        }
    }

    @Test
    fun `process identity persistence failure triggers cleanup and a failed result`() {
        val plan = planWithExistingPdf("process-evidence-failure")
        val delegate = FileCoordinationStore(temporaryDirectory.resolve("process-evidence-coord"))
        val failingStore = object : CoordinationStore by delegate {
            override fun updateLeaseProcesses(
                sessionId: BuildSessionId,
                coordinator: ProcessIdentity,
                descendants: List<ProcessIdentity>
            ) {
                throw IOException("disk failure")
            }
        }
        val process = ControlledProcess(true, 0)
        CompilationManager(
            launcher = QueueLauncher(process),
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("process-evidence-logs")),
            coordinationStore = failingStore,
            bootIdentityProvider = BootIdentityProvider { "boot" }
        ).use { manager ->
            val session = accepted(manager.requestBuild(plan))
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(BuildFailureKind.INTERNAL_ERROR, result?.failure?.kind)
            assertEquals(1, process.gracefulCalls.get())
            assertTrue(delegate.loadLeases().isEmpty())
        }
    }

    @Test
    fun `lease storage failure rejects request with typed internal failure`() {
        val plan = planWithExistingPdf("lease-storage-failure")
        val delegate = FileCoordinationStore(temporaryDirectory.resolve("lease-storage-coord"))
        val failingStore = object : CoordinationStore by delegate {
            override fun persistLease(record: OutputLeaseRecord) {
                throw IOException("disk failure")
            }
        }
        CompilationManager(
            launcher = QueueLauncher(ControlledProcess(false, 0)),
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("lease-storage-logs")),
            coordinationStore = failingStore,
            bootIdentityProvider = BootIdentityProvider { "boot" }
        ).use { manager ->
            val rejected = assertIs<BuildRequestResult.Rejected>(manager.requestBuild(plan))

            assertEquals(BuildFailureKind.INTERNAL_ERROR, rejected.failure.kind)
        }
    }

    @Test
    fun `shutdown is idempotent and rejects later requests`() {
        val plan = planWithExistingPdf("shutdown-idempotent")
        val manager = manager(QueueLauncher(ControlledProcess(false, 0)))

        manager.close()
        manager.close()

        assertIs<BuildRequestResult.Rejected>(manager.requestBuild(plan))
    }

    @Test
    fun `shutdown of unkillable process leaves durable lease and quarantine`() {
        val plan = planWithExistingPdf("shutdown-quarantine")
        val store = FileCoordinationStore(temporaryDirectory.resolve("shutdown-coord"))
        val process = ControlledProcess(
            alive = true,
            exitCode = null,
            stopGracefully = false,
            stopForcibly = false
        )
        val launcher = QueueLauncher(process)
        val manager = CompilationManager(
            launcher = launcher,
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("shutdown-logs")),
            coordinationStore = store,
            bootIdentityProvider = BootIdentityProvider { "boot" },
            processPolicy = BuildProcessPolicy(
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(1)
            )
        )
        manager.requestBuild(plan)
        launcher.awaitStarts(1)

        manager.close()

        assertEquals(1, store.loadLeases().size)
        assertEquals(1, store.loadQuarantines().size)
        process.complete(130)
        val record = store.loadQuarantines().single()
        assertIs<RecoveryResult.Recovered>(
            QuarantineRecovery(
                store,
                CompilationPathValidator(),
                BootIdentityProvider { "boot" },
                ProcessIdentityInspector { ProcessIdentityStatus.ABSENT }
            ).recheck(record.recordId)
        )
    }

    private fun planWithExistingPdf(name: String): BuildPlan {
        val root = temporaryDirectory.resolve(name)
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        Files.writeString(root.resolve("build").resolve("main.pdf"), "pdf")
        return createPlan(root)
    }

    private fun manager(
        launcher: ProcessLauncher,
        policy: BuildProcessPolicy = BuildProcessPolicy(
            Duration.ofMillis(30),
            Duration.ofMillis(30),
            Duration.ofMillis(2)
        )
    ) = CompilationManager(
        launcher = launcher,
        logFactory = FileBuildLogFactory(temporaryDirectory.resolve("logs-${System.nanoTime()}")),
        coordinationStore = FileCoordinationStore(temporaryDirectory.resolve("coord-${System.nanoTime()}")),
        bootIdentityProvider = BootIdentityProvider { "boot" },
        processPolicy = policy
    )

    private fun accepted(result: BuildRequestResult): BuildSessionSnapshot =
        assertIs<BuildRequestResult.Accepted>(result).session

    private class QueueLauncher(vararg processes: ControlledProcess) : ProcessLauncher {
        private val queue = ConcurrentLinkedQueue(processes.toList())
        private val startEvents = LinkedBlockingQueue<Boolean>()
        val startCount = AtomicInteger()
        val started = CopyOnWriteArrayList<ControlledProcess>()

        override fun start(plan: BuildPlan): ManagedProcess {
            val process = queue.poll() ?: error("No controlled process available")
            startCount.incrementAndGet()
            startEvents.offer(true)
            started += process
            return process
        }

        fun awaitStarts(count: Int) {
            repeat(count) {
                assertNotNull(startEvents.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }

    private class ControlledProcess(
        @Volatile private var alive: Boolean,
        @Volatile private var exitCode: Int?,
        private val stopGracefully: Boolean = true,
        private val stopForcibly: Boolean = true
    ) : ManagedProcess {
        override val stdout: InputStream = ByteArrayInputStream("stdout fragment".toByteArray())
        override val stderr: InputStream = ByteArrayInputStream("stderr fragment".toByteArray())
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val identity = ProcessIdentity(nextPid.incrementAndGet().toLong(), Instant.now())
        val gracefulCalls = AtomicInteger()
        val forcedCalls = AtomicInteger()
        private val gracefulRequested = CountDownLatch(1)

        override fun isAlive(): Boolean = alive
        override fun waitFor(timeout: Duration): Boolean = !alive
        override fun exitCodeOrNull(): Int? = if (alive) null else exitCode
        override fun descendants(): List<ProcessIdentity> = emptyList()
        override fun destroyGracefully() {
            gracefulCalls.incrementAndGet()
            gracefulRequested.countDown()
            if (stopGracefully) alive = false
        }
        override fun destroyForcibly() {
            forcedCalls.incrementAndGet()
            if (stopForcibly) alive = false
        }
        override fun close() {
            stdout.close()
            stderr.close()
            stdin.close()
        }

        fun complete(code: Int) {
            exitCode = code
            alive = false
        }

        fun awaitGracefulRequest() {
            assertTrue(gracefulRequested.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }

        private companion object {
            val nextPid = AtomicInteger(500_000)
        }
    }
}
