package dev.aetex.compilation

import java.nio.file.Path
import java.util.ArrayDeque

private const val MAX_DIAGNOSTIC_EXCERPT_CHARACTERS = 600
private const val MAX_DIAGNOSTIC_EXCERPT_LINES = 6
private const val MAX_USER_MESSAGE_CHARACTERS = 1_200

enum class BuildSummaryCategory {
    SUCCESS,
    CANCELLED,
    SUPERSEDED,
    TIMEOUT,
    ACTIONABLE_TEX_ERROR,
    PROCESS_START,
    NON_ZERO_EXIT,
    ARTIFACT,
    TOOL,
    CLEANUP,
    INTERNAL
}

data class BuildResultSummary(
    val text: String,
    val category: BuildSummaryCategory,
    val failureKind: BuildFailureKind? = null,
    val exitCode: Int? = null,
    /** Validated project-contained path suitable for a future navigation action. */
    val sourcePath: Path? = null,
    val reportedPath: String? = null,
    val line: Int? = null,
    val contextLine: String? = null
) {
    init {
        require(text.length <= MAX_USER_MESSAGE_CHARACTERS)
        require(line == null || line > 0)
    }
}

fun BuildResult.userSummary(): String = summary().text

fun BuildResult.summary(): BuildResultSummary {
    val main = displayPath(plan.invocation.mainDocument)
    val expected = displayPath(plan.primaryPdf)
    val executable = plan.invocation.coordinator.executable.fileName.toString()
    if (state == BuildState.SUCCEEDED) {
        return typed("Build succeeded: $expected.", BuildSummaryCategory.SUCCESS)
    }
    if (state == BuildState.CANCELLED) {
        return when (cancellation?.origin) {
            CancellationOrigin.LATEST_REQUEST_REPLACEMENT -> typed(
                "Build superseded by a newer request for $main.",
                BuildSummaryCategory.SUPERSEDED
            )
            CancellationOrigin.EXECUTION_DEADLINE -> typed(
                "Build timed out for $main.",
                BuildSummaryCategory.TIMEOUT
            )
            else -> typed("Build cancelled for $main.", BuildSummaryCategory.CANCELLED)
        }
    }

    val kind = failure?.kind
    return when (kind) {
        BuildFailureKind.PROCESS_START_FAILURE -> typed(
            "Build could not start $executable for $main${causeSuffix()}.",
            BuildSummaryCategory.PROCESS_START
        )
        BuildFailureKind.NON_ZERO_EXIT -> actionableTexDiagnostic()?.let { diagnostic ->
            val locationPath = diagnostic.reportedPath
                ?: diagnostic.sourcePath?.let(::displayPath)
                ?: main
            val location = diagnostic.line?.let { "$locationPath:$it" } ?: locationPath
            val message = diagnostic.message.trim().trimEnd('.').ifEmpty { "TeX reported an error" }
            val context = diagnostic.contextLine
                ?.takeIf { it.isNotBlank() && it != diagnostic.message }
                ?.let { " Context: ${it.trim().trimEnd('.')}." }
                .orEmpty()
            typed(
                "Build failed: $location: $message.$context",
                BuildSummaryCategory.ACTIONABLE_TEX_ERROR,
                diagnostic
            )
        } ?: typed(
            buildString {
                append("Build failed: $executable exited with code ")
                append(processEvidence.exitCode ?: "unknown")
                append(" for $main.")
                outputExcerpt()?.let { append(" $it") }
            },
            BuildSummaryCategory.NON_ZERO_EXIT
        )
        BuildFailureKind.EXECUTION_DEADLINE -> typed(
            "Build timed out for $main.",
            BuildSummaryCategory.TIMEOUT
        )
        BuildFailureKind.EXPECTED_ARTIFACT_MISSING -> typed(
            "Build finished without the expected artifact $expected for $main.",
            BuildSummaryCategory.ARTIFACT
        )
        BuildFailureKind.EXPECTED_ARTIFACT_INVALID -> typed(
            "Build produced an invalid artifact at $expected for $main.",
            BuildSummaryCategory.ARTIFACT
        )
        BuildFailureKind.TOOL_UNAVAILABLE,
        BuildFailureKind.TOOL_INVALID -> typed(
            "Build tool $executable is unavailable or invalid for $main.",
            BuildSummaryCategory.TOOL
        )
        BuildFailureKind.CANCELLATION_FAILURE,
        BuildFailureKind.POSSIBLY_ORPHANED_PROCESS -> typed(
            "Build cancellation or process cleanup failed for $main.",
            BuildSummaryCategory.CLEANUP
        )
        else -> typed(
            "Build failed for $main: ${failure?.message ?: "An internal compilation error occurred."}",
            BuildSummaryCategory.INTERNAL
        )
    }
}

private fun BuildResult.typed(
    text: String,
    category: BuildSummaryCategory,
    diagnostic: BuildDiagnostic? = null
): BuildResultSummary = BuildResultSummary(
    text = text.take(MAX_USER_MESSAGE_CHARACTERS),
    category = category,
    failureKind = failure?.kind,
    exitCode = processEvidence.exitCode,
    sourcePath = diagnostic?.sourcePath,
    reportedPath = diagnostic?.reportedPath,
    line = diagnostic?.line,
    contextLine = diagnostic?.contextLine
)

private fun BuildResult.actionableTexDiagnostic(): BuildDiagnostic? {
    val parsed = diagnostics.filter(::isActionableTexError).ifEmpty {
        val events = try {
            logs.readEvents()
        } catch (_: Exception) {
            emptyList()
        }
        try {
            BasicLatexDiagnosticExtractor()
                .extract(sessionId, plan.workingDirectory, events)
                .filter(::isActionableTexError)
        } catch (_: Exception) {
            emptyList()
        }
    }
    return parsed.minWithOrNull(
        compareBy<BuildDiagnostic>(
            { if (it.reportedPath != null && it.line != null) 0 else 1 },
            { it.relatedEventSequence ?: Long.MAX_VALUE }
        )
    )
}

private fun isActionableTexError(diagnostic: BuildDiagnostic): Boolean =
    diagnostic.kind == DiagnosticKind.TEX_ERROR &&
        diagnostic.severity == DiagnosticSeverity.ERROR

private fun BuildResult.causeSuffix(): String =
    failure?.technicalCause?.message
        ?.takeLast(MAX_DIAGNOSTIC_EXCERPT_CHARACTERS * 2)
        ?.normalizeDiagnosticText()
        ?.take(MAX_DIAGNOSTIC_EXCERPT_CHARACTERS)
        ?.takeIf(String::isNotBlank)
        ?.let { ": $it" }
        .orEmpty()

private fun BuildResult.outputExcerpt(): String? {
    val events = try {
        logs.readEvents()
    } catch (_: Exception) {
        return null
    }
    val stderr = boundedTail(events, BuildLogOrigin.STDERR)
    if (stderr.isNotEmpty()) return "stderr: $stderr"
    val stdout = boundedTail(events, BuildLogOrigin.STDOUT)
    return stdout.takeIf(String::isNotEmpty)?.let { "stdout: $it" }
}

private fun boundedTail(events: List<BuildLogEvent>, origin: BuildLogOrigin): String {
    val lines = ArrayDeque<String>(MAX_DIAGNOSTIC_EXCERPT_LINES)
    events.asSequence()
        .filter { it.origin == origin }
        .flatMap { it.decodedText.orEmpty().lineSequence() }
        .map { it.takeLast(MAX_DIAGNOSTIC_EXCERPT_CHARACTERS * 2).normalizeDiagnosticText() }
        .filter(String::isNotBlank)
        .forEach { line ->
            if (lines.size == MAX_DIAGNOSTIC_EXCERPT_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    return lines.joinToString(" | ").takeLast(MAX_DIAGNOSTIC_EXCERPT_CHARACTERS)
}

private fun BuildResult.displayPath(path: Path): String =
    if (path.startsWith(plan.workingDirectory)) {
        plan.workingDirectory.relativize(path).joinToString("/")
    } else {
        path.toString()
    }
