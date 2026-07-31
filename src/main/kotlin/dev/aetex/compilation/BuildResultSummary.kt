package dev.aetex.compilation

import java.nio.file.Path
import java.util.ArrayDeque

private const val MAX_DIAGNOSTIC_EXCERPT_CHARACTERS = 600
private const val MAX_DIAGNOSTIC_EXCERPT_LINES = 6
private const val MAX_USER_MESSAGE_CHARACTERS = 1_200

fun BuildResult.userSummary(): String {
    val main = displayPath(plan.invocation.mainDocument)
    val expected = displayPath(plan.primaryPdf)
    val executable = plan.invocation.coordinator.executable.fileName.toString()
    val summary = when (state) {
        BuildState.SUCCEEDED -> "Build succeeded: $expected."
        BuildState.CANCELLED -> when (cancellation?.origin) {
            CancellationOrigin.LATEST_REQUEST_REPLACEMENT ->
                "Build superseded by a newer request for $main."

            CancellationOrigin.EXECUTION_DEADLINE ->
                "Build timed out for $main."

            else -> "Build cancelled for $main."
        }

        BuildState.FAILED -> when (failure?.kind) {
            BuildFailureKind.PROCESS_START_FAILURE ->
                "Build could not start $executable for $main${causeSuffix()}."

            BuildFailureKind.NON_ZERO_EXIT -> buildString {
                append("Build failed: ")
                append(executable)
                append(" exited with code ")
                append(processEvidence.exitCode ?: "unknown")
                append(" for ")
                append(main)
                append('.')
                outputExcerpt()?.let {
                    append(' ')
                    append(it)
                }
            }

            BuildFailureKind.EXECUTION_DEADLINE ->
                "Build timed out for $main."

            BuildFailureKind.EXPECTED_ARTIFACT_MISSING ->
                "Build finished without the expected artifact $expected for $main."

            BuildFailureKind.EXPECTED_ARTIFACT_INVALID ->
                "Build produced an invalid artifact at $expected for $main."

            BuildFailureKind.TOOL_UNAVAILABLE,
            BuildFailureKind.TOOL_INVALID ->
                "Build tool $executable is unavailable or invalid for $main."

            BuildFailureKind.CANCELLATION_FAILURE,
            BuildFailureKind.POSSIBLY_ORPHANED_PROCESS ->
                "Build cancellation or process cleanup failed for $main."

            else -> buildString {
                append("Build failed for ")
                append(main)
                append(": ")
                append(failure?.message ?: "An internal compilation error occurred.")
            }
        }

        else -> "Build status changed for $main."
    }
    return summary.take(MAX_USER_MESSAGE_CHARACTERS)
}

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

private fun boundedTail(
    events: List<BuildLogEvent>,
    origin: BuildLogOrigin
): String {
    val lines = ArrayDeque<String>(MAX_DIAGNOSTIC_EXCERPT_LINES)
    events.asSequence()
        .filter { it.origin == origin }
        .flatMap { it.decodedText.orEmpty().lineSequence() }
        .map {
            it.takeLast(MAX_DIAGNOSTIC_EXCERPT_CHARACTERS * 2)
                .normalizeDiagnosticText()
        }
        .filter(String::isNotBlank)
        .forEach { line ->
            if (lines.size == MAX_DIAGNOSTIC_EXCERPT_LINES) {
                lines.removeFirst()
            }
            lines.addLast(line)
        }
    return lines.joinToString(" | ").takeLast(MAX_DIAGNOSTIC_EXCERPT_CHARACTERS)
}

private fun String.normalizeDiagnosticText(): String =
    buildString(length) {
        this@normalizeDiagnosticText.forEach { character ->
            append(
                if (character.isISOControl() && !character.isWhitespace()) {
                    '\uFFFD'
                } else {
                    character
                }
            )
        }
    }.replace(Regex("""\s+"""), " ").trim()

private fun BuildResult.displayPath(path: Path): String =
    if (path.startsWith(plan.workingDirectory)) {
        plan.workingDirectory.relativize(path).joinToString("/")
    } else {
        path.toString()
    }
