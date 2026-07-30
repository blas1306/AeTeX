package dev.aetex.compilation

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

interface ManagedProcess : AutoCloseable {
    val stdout: InputStream
    val stderr: InputStream
    val stdin: OutputStream
    val identity: ProcessIdentity
    fun isAlive(): Boolean
    fun waitFor(timeout: Duration): Boolean
    fun exitCodeOrNull(): Int?
    fun descendants(): List<ProcessIdentity>
    fun remainingProcesses(): List<ProcessIdentity> =
        if (isAlive()) listOf(identity) else emptyList()
    fun destroyGracefully()
    fun destroyForcibly()
}

fun interface ProcessLauncher {
    @Throws(IOException::class)
    fun start(plan: BuildPlan): ManagedProcess
}

class JvmProcessLauncher : ProcessLauncher {
    override fun start(plan: BuildPlan): ManagedProcess {
        val command = buildList {
            add(plan.invocation.coordinator.executable.toString())
            addAll(plan.arguments)
        }
        val builder = ProcessBuilder(command)
            .directory(plan.workingDirectory.toFile())
            .redirectErrorStream(false)
        builder.environment().apply {
            clear()
            putAll(plan.environment.values)
        }
        return JvmManagedProcess(builder.start())
    }
}

private class JvmManagedProcess(
    private val process: Process
) : ManagedProcess {
    private val knownHandles = ConcurrentHashMap<Long, ProcessHandle>()

    override val stdout: InputStream = process.inputStream
    override val stderr: InputStream = process.errorStream
    override val stdin: OutputStream = process.outputStream
    override val identity: ProcessIdentity = process.toHandle().toIdentity()

    override fun isAlive(): Boolean = process.isAlive || trackedHandles().any(ProcessHandle::isAlive)

    override fun waitFor(timeout: Duration): Boolean =
        if (process.isAlive) {
            process.waitFor(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } else {
            Thread.sleep(timeout.toMillis().coerceAtLeast(1))
            !isAlive()
        }

    override fun exitCodeOrNull(): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    override fun descendants(): List<ProcessIdentity> =
        trackedHandles().map { it.toIdentity() }

    override fun remainingProcesses(): List<ProcessIdentity> = buildList {
        if (process.isAlive) add(identity)
        addAll(trackedHandles().filter(ProcessHandle::isAlive).map { it.toIdentity() })
    }.distinctBy { it.pid to it.startInstant }

    override fun destroyGracefully() {
        trackedHandles().sortedByDescending { it.pid() }.forEach { it.destroy() }
        process.destroy()
    }

    override fun destroyForcibly() {
        trackedHandles().sortedByDescending { it.pid() }.forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private fun trackedHandles(): List<ProcessHandle> {
        process.descendants().use { stream ->
            stream.forEach { knownHandles[it.pid()] = it }
        }
        return knownHandles.values.toList()
    }

    override fun close() {
        stdin.close()
        stdout.close()
        stderr.close()
    }

    private fun ProcessHandle.toIdentity(): ProcessIdentity =
        ProcessIdentity(pid(), info().startInstant().orElse(null))
}

fun interface CancellationSignal {
    fun current(): BuildCancellation?
}

data class BuildProcessPolicy(
    val gracefulWait: Duration = Duration.ofSeconds(2),
    val forcedWait: Duration = Duration.ofSeconds(2),
    val pollInterval: Duration = Duration.ofMillis(20)
)

data class BuildProcessOutcome(
    val evidence: ProcessEvidence,
    val cancellation: BuildCancellation?,
    val failure: BuildFailure?
)

class BuildProcess(
    private val launcher: ProcessLauncher,
    private val ioExecutor: ExecutorService,
    private val policy: BuildProcessPolicy = BuildProcessPolicy(),
    private val clock: BuildClock = SystemBuildClock
) {
    fun execute(
        plan: BuildPlan,
        log: BuildLog,
        cancellationSignal: CancellationSignal,
        executionDeadline: Instant? = null,
        onProcessStarted: (ProcessIdentity, List<ProcessIdentity>) -> Unit = { _, _ -> }
    ): BuildProcessOutcome {
        if (executionDeadline != null && !clock.instant().isBefore(executionDeadline)) {
            appendBestEffort(
                log,
                BuildLogOrigin.LIFECYCLE,
                "Execution deadline expired before process start.\n"
            )
            val cancellation = BuildCancellation(
                CancellationOrigin.EXECUTION_DEADLINE,
                clock.instant(),
                streamsReachedEof = true
            )
            return BuildProcessOutcome(
                evidence = ProcessEvidence(
                    started = false,
                    streamsReachedEof = true,
                    resourcesClosed = true,
                    cleanupProven = true
                ),
                cancellation = cancellation,
                failure = BuildFailure(
                    BuildFailureKind.EXECUTION_DEADLINE,
                    "The caller-owned execution deadline expired before process start."
                )
            )
        }
        val process = try {
            launcher.start(plan)
        } catch (error: IOException) {
            appendBestEffort(log, BuildLogOrigin.LIFECYCLE, "Process start failed: ${error.message}\n")
            return BuildProcessOutcome(
                evidence = ProcessEvidence(started = false),
                cancellation = cancellationSignal.current(),
                failure = BuildFailure(
                    BuildFailureKind.PROCESS_START_FAILURE,
                    "The compilation process could not be started.",
                    TechnicalCause.from(error)
                )
            )
        } catch (error: SecurityException) {
            appendBestEffort(log, BuildLogOrigin.LIFECYCLE, "Process start denied: ${error.message}\n")
            return BuildProcessOutcome(
                evidence = ProcessEvidence(started = false),
                cancellation = cancellationSignal.current(),
                failure = BuildFailure(
                    BuildFailureKind.PROCESS_START_FAILURE,
                    "Starting the compilation process was denied.",
                    TechnicalCause.from(error)
                )
            )
        }

        val logFailure = AtomicReference<BuildFailure?>()
        val coordinationFailure = AtomicReference<BuildFailure?>()
        val knownAtStart = process.descendants()
        appendBestEffort(
            log,
            BuildLogOrigin.LIFECYCLE,
            "Started ${plan.invocation.coordinator.executable} with ${plan.arguments.size} arguments.\n",
            logFailure
        )
        try {
            process.stdin.close()
        } catch (error: IOException) {
            appendBestEffort(log, BuildLogOrigin.CLEANUP, "Could not close process stdin: ${error.message}\n")
        }

        val stdout = capture(
            process.stdout,
            BuildLogOrigin.STDOUT,
            Charset.forName(plan.environment.charsetName),
            log,
            process,
            logFailure
        )
        val stderr = capture(
            process.stderr,
            BuildLogOrigin.STDERR,
            Charset.forName(plan.environment.charsetName),
            log,
            process,
            logFailure
        )
        try {
            onProcessStarted(process.identity, knownAtStart)
        } catch (error: Exception) {
            coordinationFailure.set(
                BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "Process identity could not be persisted after startup.",
                    TechnicalCause.from(error)
                )
            )
        }

        var acceptedCancellation: BuildCancellation? = null
        var deadlineExpired = false
        while (process.isAlive()) {
            val cancellation = cancellationSignal.current()
            if (cancellation != null) {
                acceptedCancellation = cancellation
                break
            }
            if (executionDeadline != null && !clock.instant().isBefore(executionDeadline)) {
                acceptedCancellation = BuildCancellation(
                    CancellationOrigin.EXECUTION_DEADLINE,
                    clock.instant()
                )
                deadlineExpired = true
                break
            }
            if (logFailure.get() != null) {
                break
            }
            if (coordinationFailure.get() != null) {
                break
            }
            process.waitFor(policy.pollInterval)
        }
        acceptedCancellation = acceptedCancellation ?: cancellationSignal.current()

        var graceful = false
        var forced = false
        if (
            acceptedCancellation != null ||
            logFailure.get() != null ||
            coordinationFailure.get() != null
        ) {
            if (process.isAlive()) {
                graceful = true
                appendBestEffort(log, BuildLogOrigin.CLEANUP, "Graceful process-tree termination requested.\n")
                process.destroyGracefully()
                waitUntilStopped(process, policy.gracefulWait)
            }
            if (process.isAlive()) {
                forced = true
                appendBestEffort(log, BuildLogOrigin.CLEANUP, "Forced process-tree termination requested.\n")
                process.destroyForcibly()
                waitUntilStopped(process, policy.forcedWait)
            }
        }

        if (process.isAlive()) {
            process.destroyForcibly()
        }
        val streamsEof = awaitStreams(stdout, stderr, policy.forcedWait)
        val remaining = process.remainingProcesses()
        val exitCode = process.exitCodeOrNull()
        val resourcesClosed = try {
            process.close()
            true
        } catch (error: IOException) {
            appendBestEffort(log, BuildLogOrigin.CLEANUP, "Process resources could not be closed: ${error.message}\n")
            false
        }
        val cleanupProven = remaining.isEmpty() && streamsEof && resourcesClosed
        val evidence = ProcessEvidence(
            started = true,
            coordinator = process.identity,
            descendants = (knownAtStart + process.descendants() + remaining)
                .filterNot { it.pid == process.identity.pid && it.startInstant == process.identity.startInstant }
                .distinctBy { it.pid to it.startInstant },
            exitCode = exitCode,
            streamsReachedEof = streamsEof,
            resourcesClosed = resourcesClosed,
            cleanupProven = cleanupProven
        )

        val cancellation = acceptedCancellation?.copy(
            result = when {
                !cleanupProven -> CancellationResult.FAILED
                forced -> CancellationResult.FORCED_TERMINATION
                graceful -> CancellationResult.GRACEFUL_TERMINATION
                else -> CancellationResult.GRACEFUL_TERMINATION
            },
            gracefulRequested = graceful,
            forcedRequested = forced,
            remainingProcesses = remaining,
            streamsReachedEof = streamsEof
        )
        val failure = when {
            logFailure.get() != null -> logFailure.get()
            coordinationFailure.get() != null -> coordinationFailure.get()
            !cleanupProven -> BuildFailure(
                if (cancellation != null) {
                    BuildFailureKind.CANCELLATION_FAILURE
                } else {
                    BuildFailureKind.POSSIBLY_ORPHANED_PROCESS
                },
                "Process termination and stream cleanup could not be proven."
            )
            deadlineExpired -> BuildFailure(
                BuildFailureKind.EXECUTION_DEADLINE,
                "The caller-owned execution deadline expired."
            )
            cancellation != null -> null
            exitCode == null -> BuildFailure(
                BuildFailureKind.INTERNAL_ERROR,
                "The process exit code was unavailable after termination."
            )
            exitCode != 0 -> BuildFailure(
                BuildFailureKind.NON_ZERO_EXIT,
                "The compilation process exited with code $exitCode."
            )
            else -> null
        }
        appendBestEffort(
            log,
            BuildLogOrigin.LIFECYCLE,
            "Process finished with exit code ${exitCode ?: "unavailable"}; cleanup proven=$cleanupProven.\n"
        )
        return BuildProcessOutcome(evidence, cancellation, failure)
    }

    private fun capture(
        stream: InputStream,
        origin: BuildLogOrigin,
        charset: Charset,
        log: BuildLog,
        process: ManagedProcess,
        logFailure: AtomicReference<BuildFailure?>
    ): Future<Boolean> = ioExecutor.submit<Boolean> {
        val decoder = StreamingTextDecoder(charset)
        val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
        try {
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val raw = buffer.copyOf(count)
                val (text, status) = decoder.decode(raw)
                log.append(origin, raw, text, status)
            }
            val (tail, status) = decoder.decode(byteArrayOf(), endOfInput = true)
            if (tail.isNotEmpty()) {
                log.append(origin, byteArrayOf(), tail, status)
            }
            true
        } catch (error: LogStorageException) {
            logFailure.compareAndSet(
                null,
                BuildFailure(
                    BuildFailureKind.LOG_STORAGE_FAILURE,
                    "Complete process output could not be stored.",
                    TechnicalCause.from(error)
                )
            )
            process.destroyGracefully()
            false
        } catch (error: IOException) {
            appendBestEffort(log, BuildLogOrigin.CLEANUP, "Stream capture failed: ${error.message}\n")
            false
        }
    }

    private fun awaitStreams(first: Future<Boolean>, second: Future<Boolean>, timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        return awaitOne(first, deadline) && awaitOne(second, deadline)
    }

    private fun awaitOne(future: Future<Boolean>, deadline: Long): Boolean {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return false
        return try {
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun waitUntilStopped(process: ManagedProcess, wait: Duration) {
        val deadline = System.nanoTime() + wait.toNanos()
        while (process.isAlive() && System.nanoTime() < deadline) {
            process.waitFor(policy.pollInterval)
        }
    }

    private fun appendBestEffort(
        log: BuildLog,
        origin: BuildLogOrigin,
        text: String,
        failure: AtomicReference<BuildFailure?>? = null
    ) {
        try {
            log.append(origin, decodedText = text, decodingStatus = DecodingStatus.COMPLETE)
        } catch (error: LogStorageException) {
            failure?.compareAndSet(
                null,
                BuildFailure(
                    BuildFailureKind.LOG_STORAGE_FAILURE,
                    "Build lifecycle evidence could not be stored.",
                    TechnicalCause.from(error)
                )
            )
        }
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 8192
    }
}
