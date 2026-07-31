package dev.aetex.compilation

import java.nio.file.Files
import java.nio.file.Path

fun interface DiagnosticExtractor {
    fun extract(
        sessionId: BuildSessionId,
        projectRoot: Path,
        events: List<BuildLogEvent>
    ): List<BuildDiagnostic>
}

class BasicLatexDiagnosticExtractor : DiagnosticExtractor {
    override fun extract(
        sessionId: BuildSessionId,
        projectRoot: Path,
        events: List<BuildLogEvent>
    ): List<BuildDiagnostic> {
        return diagnosticLines(events).flatMap { lines ->
            parseLines(lines, sessionId, projectRoot)
        }.sortedBy { it.relatedEventSequence }
    }

    private fun diagnosticLines(events: List<BuildLogEvent>): List<List<LogLine>> =
        listOf(BuildLogOrigin.STDOUT, BuildLogOrigin.STDERR, BuildLogOrigin.TOOL_FILE).map { origin ->
            val result = mutableListOf<LogLine>()
            val buffer = StringBuilder()
            var sequence = 0L
            events.filter { it.origin == origin }.forEach { event ->
                if (buffer.isEmpty()) sequence = event.sequence
                buffer.append(event.decodedText.orEmpty())
                while (true) {
                    val newline = buffer.indexOf("\n")
                    if (newline < 0) break
                    result += LogLine(buffer.substring(0, newline).trimEnd('\r'), origin, sequence)
                    buffer.delete(0, newline + 1)
                    sequence = event.sequence
                }
            }
            if (buffer.isNotEmpty()) result += LogLine(buffer.toString(), origin, sequence)
            result
        }

    private fun parseLines(
        lines: List<LogLine>,
        sessionId: BuildSessionId,
        projectRoot: Path
    ): List<BuildDiagnostic> = buildList {
        lines.forEachIndexed { index, logLine ->
            val line = logLine.text
            val fileLine = FILE_LINE.matchEntire(line)
            if (fileLine != null) {
                val message = bounded(fileLine.groupValues[3])
                val warning = isWarning(message)
                val rawPath = bounded(fileLine.groupValues[1].trim().trim('"'), MAX_PATH)
                val trusted = trustedProjectPath(rawPath, projectRoot)
                add(
                    BuildDiagnostic(
                        kind = if (warning) DiagnosticKind.TEX_WARNING else DiagnosticKind.TEX_ERROR,
                        severity = if (warning) DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR,
                        message = message,
                        sessionId = sessionId,
                        origin = logLine.origin.name,
                        sourcePath = trusted,
                        line = fileLine.groupValues[2].toIntOrNull(),
                        relatedEventSequence = logLine.sequence,
                        confidence = if (trusted != null) DiagnosticConfidence.EXACT else DiagnosticConfidence.INCOMPLETE,
                        reportedPath = trusted?.let { displayProjectPath(it, projectRoot) } ?: rawPath,
                        contextLine = usefulContext(lines, index)
                    )
                )
                return@forEachIndexed
            }
            if (line.startsWith("!")) {
                val nearby = lines.asSequence().drop(index + 1).take(CONTEXT_LOOKAHEAD)
                val source = nearby.mapNotNull { CLASSIC_LINE.matchEntire(it.text) }.firstOrNull()
                add(
                    BuildDiagnostic(
                        kind = DiagnosticKind.TEX_ERROR,
                        severity = DiagnosticSeverity.ERROR,
                        message = bounded(line.removePrefix("!")).ifEmpty { "TeX reported an error." },
                        sessionId = sessionId,
                        origin = logLine.origin.name,
                        line = source?.groupValues?.get(1)?.toIntOrNull(),
                        relatedEventSequence = logLine.sequence,
                        confidence = DiagnosticConfidence.CONSERVATIVE,
                        contextLine = source?.value?.let(::bounded) ?: usefulContext(lines, index)
                    )
                )
                return@forEachIndexed
            }
            if (isWarning(line)) {
                add(
                    BuildDiagnostic(
                        kind = DiagnosticKind.TEX_WARNING,
                        severity = DiagnosticSeverity.WARNING,
                        message = bounded(line),
                        sessionId = sessionId,
                        origin = logLine.origin.name,
                        relatedEventSequence = logLine.sequence
                    )
                )
            }
        }
    }

    private companion object {
        const val MAX_MESSAGE = 400
        const val MAX_CONTEXT = 300
        const val MAX_PATH = 400
        const val CONTEXT_LOOKAHEAD = 5
        val FILE_LINE = Regex("""^(.+):(\d+):\s*(.+)$""")
        val CLASSIC_LINE = Regex("""^\s*l\.(\d+)\s*(.*)$""")
        val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:[\\/].*""")

        fun bounded(value: String, limit: Int = MAX_MESSAGE): String =
            value.normalizeDiagnosticText().take(limit)

        fun isWarning(value: String): Boolean =
            value.contains("warning", ignoreCase = true) ||
                value.trimStart().startsWith("Overfull ") ||
                value.trimStart().startsWith("Underfull ")

        fun usefulContext(lines: List<LogLine>, index: Int): String? =
            lines.asSequence()
                .drop(index + 1)
                .take(CONTEXT_LOOKAHEAD)
                .map { bounded(it.text, MAX_CONTEXT) }
                .firstOrNull {
                    it.isNotBlank() &&
                        !it.startsWith("Latexmk:") &&
                        !it.startsWith("! ")
                }

        fun trustedProjectPath(reported: String, projectRoot: Path): Path? {
            if (WINDOWS_ABSOLUTE.matches(reported) && !HostPlatform.current().isWindows) return null
            return try {
                val root = projectRoot.toAbsolutePath().normalize()
                val parsed = Path.of(reported)
                val candidate = (if (parsed.isAbsolute) parsed else root.resolve(parsed)).normalize()
                if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return null
                val realRoot = root.toRealPath()
                candidate.toRealPath().takeIf { it.startsWith(realRoot) }
            } catch (_: Exception) {
                null
            }
        }

        fun displayProjectPath(path: Path, projectRoot: Path): String {
            val root = projectRoot.toAbsolutePath().normalize()
            return if (path.startsWith(root)) {
                root.relativize(path).joinToString("/")
            } else {
                path.toString()
            }
        }
    }

    private data class LogLine(
        val text: String,
        val origin: BuildLogOrigin,
        val sequence: Long
    )
}

internal fun String.normalizeDiagnosticText(): String =
    buildString(length) {
        this@normalizeDiagnosticText.forEach { character ->
            append(
                if (character.isISOControl() && !character.isWhitespace()) '\uFFFD' else character
            )
        }
    }.replace(Regex("""\s+"""), " ").trim()
