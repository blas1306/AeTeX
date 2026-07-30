package dev.aetex.compilation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BuildProcessTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `captures separate stdout stderr exit and natural completion`() {
        val managed = FakeManagedProcess(
            stdoutBytes = "out".toByteArray(),
            stderrBytes = "err".toByteArray(),
            initiallyAlive = false,
            exitCode = 0
        )
        val outcome = execute(managed)

        assertNull(outcome.failure)
        assertEquals(0, outcome.evidence.exitCode)
        assertTrue(outcome.evidence.cleanupProven)
        val events = requireNotNull(lastLog).readEvents()
        assertTrue(events.any { it.origin == BuildLogOrigin.STDOUT && it.decodedText == "out" })
        assertTrue(events.any { it.origin == BuildLogOrigin.STDERR && it.decodedText == "err" })
    }

    @Test
    fun `reports process start failure without exit code`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val log = FileBuildLogFactory(temporaryDirectory.resolve("logs"))
            .create(BuildSessionId("start"), Instant.now())
        val executor = Executors.newCachedThreadPool()
        val outcome = BuildProcess(
            ProcessLauncher { throw IOException("no process") },
            executor
        ).execute(plan, log, CancellationSignal { null })
        log.close()
        executor.shutdownNow()

        assertEquals(BuildFailureKind.PROCESS_START_FAILURE, outcome.failure?.kind)
        assertFalse(outcome.evidence.started)
        assertNull(outcome.evidence.exitCode)
    }

    @Test
    fun `nonzero exit is typed and logs remain available`() {
        val outcome = execute(FakeManagedProcess(initiallyAlive = false, exitCode = 12))

        assertEquals(BuildFailureKind.NON_ZERO_EXIT, outcome.failure?.kind)
        assertEquals(12, outcome.evidence.exitCode)
        assertTrue(requireNotNull(lastLog).readEvents().isNotEmpty())
    }

    @Test
    fun `accepted cancellation escalates from graceful to forced`() {
        val managed = FakeManagedProcess(
            initiallyAlive = true,
            exitCode = 130,
            stopGracefully = false,
            stopForcibly = true
        )
        val cancellation = BuildCancellation(CancellationOrigin.USER, Instant.now())
        val outcome = execute(managed, cancellation)

        assertNull(outcome.failure)
        assertEquals(1, managed.gracefulCalls)
        assertEquals(1, managed.forcedCalls)
        assertEquals(CancellationResult.FORCED_TERMINATION, outcome.cancellation?.result)
        assertTrue(outcome.evidence.cleanupProven)
    }

    @Test
    fun `failed cancellation reports possibly live process and uncertain cleanup`() {
        val managed = FakeManagedProcess(
            initiallyAlive = true,
            exitCode = null,
            stopGracefully = false,
            stopForcibly = false
        )
        val outcome = execute(
            managed,
            BuildCancellation(CancellationOrigin.USER, Instant.now()),
            BuildProcessPolicy(Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(1))
        )

        assertEquals(BuildFailureKind.CANCELLATION_FAILURE, outcome.failure?.kind)
        assertEquals(CancellationResult.FAILED, outcome.cancellation?.result)
        assertFalse(outcome.evidence.cleanupProven)
    }

    @Test
    fun `late cancellation accepted before publication wins natural exit`() {
        val cancellation = BuildCancellation(CancellationOrigin.USER, Instant.now())
        val outcome = execute(
            FakeManagedProcess(initiallyAlive = false, exitCode = 0),
            cancellation
        )

        assertNotNull(outcome.cancellation)
        assertNull(outcome.failure)
    }

    @Test
    fun `surviving descendant prevents cleanup proof after parent terminates`() {
        val child = ProcessIdentity(4321, Instant.parse("2026-01-01T00:00:01Z"))
        val managed = FakeManagedProcess(
            initiallyAlive = true,
            exitCode = 130,
            stopGracefully = true,
            stopForcibly = true,
            survivingProcesses = listOf(child)
        )

        val outcome = execute(
            managed,
            BuildCancellation(CancellationOrigin.USER, Instant.now()),
            BuildProcessPolicy(Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(1))
        )

        assertEquals(BuildFailureKind.CANCELLATION_FAILURE, outcome.failure?.kind)
        assertFalse(outcome.evidence.cleanupProven)
        assertEquals(listOf(child), outcome.cancellation?.remainingProcesses)
    }

    @Test
    fun `caller deadline uses bounded cleanup and terminates as failed`() {
        val deadline = Instant.parse("2026-01-01T00:00:01Z")
        val calls = AtomicInteger()
        val clock = object : BuildClock {
            override fun instant(): Instant =
                if (calls.getAndIncrement() == 0) deadline.minusSeconds(1) else deadline
        }
        val managed = FakeManagedProcess(
            initiallyAlive = true,
            exitCode = 130,
            stopGracefully = true
        )

        val outcome = execute(
            managed = managed,
            deadline = deadline,
            clock = clock
        )

        assertEquals(BuildFailureKind.EXECUTION_DEADLINE, outcome.failure?.kind)
        assertEquals(CancellationOrigin.EXECUTION_DEADLINE, outcome.cancellation?.origin)
        assertEquals(1, managed.gracefulCalls)
        assertTrue(outcome.evidence.cleanupProven)
    }

    private var lastLog: BuildLogHandle? = null

    private fun execute(
        managed: FakeManagedProcess,
        cancellation: BuildCancellation? = null,
        policy: BuildProcessPolicy = BuildProcessPolicy(
            Duration.ofMillis(20),
            Duration.ofMillis(20),
            Duration.ofMillis(1)
        ),
        deadline: Instant? = null,
        clock: BuildClock = SystemBuildClock
    ): BuildProcessOutcome {
        val plan = createPlan(temporaryDirectory.resolve("project-${System.nanoTime()}"))
        val log = FileBuildLogFactory(temporaryDirectory.resolve("logs"))
            .create(BuildSessionId("session-${System.nanoTime()}"), Instant.now())
        val executor = Executors.newCachedThreadPool()
        val outcome = BuildProcess(ProcessLauncher { managed }, executor, policy, clock)
            .execute(
                plan,
                log,
                CancellationSignal { cancellation },
                executionDeadline = deadline
            )
        log.close()
        lastLog = log.snapshot()
        executor.shutdownNow()
        return outcome
    }

    private class FakeManagedProcess(
        stdoutBytes: ByteArray = byteArrayOf(),
        stderrBytes: ByteArray = byteArrayOf(),
        @Volatile var initiallyAlive: Boolean,
        private var exitCode: Int?,
        private val stopGracefully: Boolean = true,
        private val stopForcibly: Boolean = true,
        private val survivingProcesses: List<ProcessIdentity> = emptyList()
    ) : ManagedProcess {
        override val stdout: InputStream = ByteArrayInputStream(stdoutBytes)
        override val stderr: InputStream = ByteArrayInputStream(stderrBytes)
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val identity = ProcessIdentity(1234, Instant.parse("2026-01-01T00:00:00Z"))
        var gracefulCalls = 0
        var forcedCalls = 0

        override fun isAlive(): Boolean = initiallyAlive
        override fun waitFor(timeout: Duration): Boolean = !initiallyAlive
        override fun exitCodeOrNull(): Int? = if (initiallyAlive) null else exitCode
        override fun descendants(): List<ProcessIdentity> = emptyList()
        override fun remainingProcesses(): List<ProcessIdentity> = buildList {
            if (initiallyAlive) add(identity)
            addAll(survivingProcesses)
        }
        override fun destroyGracefully() {
            gracefulCalls += 1
            if (stopGracefully) initiallyAlive = false
        }
        override fun destroyForcibly() {
            forcedCalls += 1
            if (stopForcibly) initiallyAlive = false
        }
        override fun close() {
            stdout.close()
            stderr.close()
            stdin.close()
        }
    }
}
