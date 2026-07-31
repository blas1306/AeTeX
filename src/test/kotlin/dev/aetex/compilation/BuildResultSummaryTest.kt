package dev.aetex.compilation

import dev.aetex.preview.successfulBuildResult
import java.nio.file.Files
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

    @Test
    fun `real TeX error replaces generic latexmk epilogue in typed summary`() {
        val result = failedWithOutput(
            stderr = "chapters/cap1.tex:37: Undefined control sequence.\n" +
                "l.37 Text \\doesNotExist\n" +
                "Latexmk: Sometimes, the -f option can be used to get latexmk to try to force complete processing.\n",
            projectFiles = listOf("main.tex", "chapters/cap1.tex")
        )

        val summary = result.summary()

        assertEquals(BuildSummaryCategory.ACTIONABLE_TEX_ERROR, summary.category)
        assertEquals("chapters/cap1.tex", summary.reportedPath)
        assertEquals(37, summary.line)
        assertEquals(result.plan.workingDirectory.resolve("chapters/cap1.tex").toRealPath(), summary.sourcePath)
        assertTrue(summary.text.contains("chapters/cap1.tex:37: Undefined control sequence"))
        assertTrue(summary.text.contains("l.37 Text \\doesNotExist"))
        assertTrue(!summary.text.contains("Sometimes, the -f option"))
    }

    @Test
    fun `stdout only fatal error outranks earlier warnings`() {
        val result = failedWithOutput(
            stdout = "LaTeX Warning: Citation undefined.\n! Emergency stop.\nl.123 \\bad\n",
            projectFiles = listOf("main.tex")
        )

        val summary = result.summary()

        assertEquals(BuildSummaryCategory.ACTIONABLE_TEX_ERROR, summary.category)
        assertEquals(123, summary.line)
        assertTrue(summary.text.contains("Emergency stop"))
        assertTrue(!summary.text.contains("Citation undefined"))
    }

    @Test
    fun `malformed output uses bounded stderr fallback`() {
        val result = failedWithOutput(
            stderr = (1..30).joinToString("\n") { "unrecognized output $it " + "x".repeat(80) },
            projectFiles = listOf("main.tex")
        )

        val summary = result.summary()

        assertEquals(BuildSummaryCategory.NON_ZERO_EXIT, summary.category)
        assertTrue(summary.text.contains("latexmk exited with code 12"))
        assertTrue(summary.text.contains("stderr:"))
        assertTrue(summary.text.length <= 1_200)
        assertTrue(!summary.text.contains("unrecognized output 1 "))
    }

    @Test
    fun `actionable diagnostic fields and excerpts are bounded`() {
        val result = failedWithOutput(
            stderr = "main.tex:4: ${"failure ".repeat(200)}\n" + "l.4 ${"context ".repeat(200)}\n",
            projectFiles = listOf("main.tex")
        )

        val summary = result.summary()

        assertEquals(BuildSummaryCategory.ACTIONABLE_TEX_ERROR, summary.category)
        assertTrue(summary.text.length <= 1_200)
        assertTrue(summary.contextLine!!.length <= 300)
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

    private fun failedWithOutput(
        stdout: String = "",
        stderr: String = "",
        projectFiles: List<String>
    ): BuildResult {
        val root = temporaryDirectory.resolve("project-${System.nanoTime()}")
        Files.createDirectories(root)
        projectFiles.forEach { relative ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "% fixture")
        }
        val base = successfulBuildResult(root, "summary-${System.nanoTime()}")
        val log = FileBuildLogFactory(temporaryDirectory.resolve("logs-${System.nanoTime()}"))
            .create(base.sessionId, base.createdAt)
        if (stdout.isNotEmpty()) {
            log.append(
                BuildLogOrigin.STDOUT,
                stdout.toByteArray(),
                stdout,
                DecodingStatus.COMPLETE
            )
        }
        if (stderr.isNotEmpty()) {
            log.append(
                BuildLogOrigin.STDERR,
                stderr.toByteArray(),
                stderr,
                DecodingStatus.COMPLETE
            )
        }
        val handle = log.snapshot()
        val diagnostics = BasicLatexDiagnosticExtractor().extract(
            base.sessionId,
            root,
            handle.readEvents()
        )
        log.close()
        return BuildResult(
            sessionId = base.sessionId,
            state = BuildState.FAILED,
            plan = base.plan,
            failure = BuildFailure(BuildFailureKind.NON_ZERO_EXIT, "latexmk exited unsuccessfully."),
            createdAt = base.createdAt,
            startedAt = base.startedAt,
            finishedAt = base.finishedAt,
            processEvidence = base.processEvidence.copy(exitCode = 12),
            cancellation = null,
            logs = handle,
            diagnostics = diagnostics,
            artifacts = base.artifacts,
            missingRequiredArtifacts = emptyList(),
            quarantine = null,
            trace = emptyMap()
        )
    }
}
