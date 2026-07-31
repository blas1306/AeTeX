package dev.aetex.compilation

import dev.aetex.preview.successfulBuildResult
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BuildResultSummaryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `summary distinguishes cancellation supersession timeout and internal failure`() {
        val base = successfulBuildResult(temporaryDirectory.resolve("project"))
        val cancelled = terminal(
            base,
            BuildState.CANCELLED,
            cancellation = cancellation(CancellationOrigin.USER)
        )
        val superseded = terminal(
            base,
            BuildState.CANCELLED,
            cancellation = cancellation(CancellationOrigin.LATEST_REQUEST_REPLACEMENT)
        )
        val timeout = terminal(
            base,
            BuildState.FAILED,
            failure = BuildFailure(
                BuildFailureKind.EXECUTION_DEADLINE,
                "The caller-owned execution deadline expired."
            ),
            cancellation = cancellation(CancellationOrigin.EXECUTION_DEADLINE)
        )
        val internal = terminal(
            base,
            BuildState.FAILED,
            failure = BuildFailure(
                BuildFailureKind.INTERNAL_ERROR,
                "Synthetic internal failure."
            )
        )

        assertTrue(cancelled.userSummary().startsWith("Build cancelled"))
        assertTrue(superseded.userSummary().startsWith("Build superseded"))
        assertTrue(timeout.userSummary().startsWith("Build timed out"))
        assertTrue(internal.userSummary().contains("Synthetic internal failure"))
        assertTrue(
            listOf(cancelled, superseded, timeout, internal)
                .all { it.userSummary().length <= 1_200 }
        )
    }

    @Test
    fun `successful summary uses the planned project-relative artifact`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))

        assertEquals("Build succeeded: build/main.pdf.", result.userSummary())
    }

    private fun cancellation(origin: CancellationOrigin) =
        BuildCancellation(origin, Instant.parse("2026-07-30T12:00:00Z"))

    private fun terminal(
        base: BuildResult,
        state: BuildState,
        failure: BuildFailure? = null,
        cancellation: BuildCancellation? = null
    ) = BuildResult(
        sessionId = base.sessionId,
        state = state,
        plan = base.plan,
        failure = failure,
        createdAt = base.createdAt,
        startedAt = base.startedAt,
        finishedAt = base.finishedAt,
        processEvidence = base.processEvidence,
        cancellation = cancellation,
        logs = base.logs,
        diagnostics = emptyList(),
        artifacts = base.artifacts,
        missingRequiredArtifacts = base.missingRequiredArtifacts,
        quarantine = null,
        trace = emptyMap()
    )
}
