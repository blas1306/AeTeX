package dev.aetex.compilation

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
        val diagnostics = mutableListOf<BuildDiagnostic>()
        val lines = mutableMapOf(
            BuildLogOrigin.STDOUT to StringBuilder(),
            BuildLogOrigin.STDERR to StringBuilder(),
            BuildLogOrigin.TOOL_FILE to StringBuilder()
        )
        events.filter {
            it.origin == BuildLogOrigin.STDOUT ||
                it.origin == BuildLogOrigin.STDERR ||
                it.origin == BuildLogOrigin.TOOL_FILE
        }
            .forEach { event ->
                val buffer = lines.getValue(event.origin)
                buffer.append(event.decodedText.orEmpty())
                emitCompleteLines(buffer).forEach { line ->
                    parseLine(line, sessionId, projectRoot, event)?.let(diagnostics::add)
                }
            }
        lines.forEach { (origin, buffer) ->
            if (buffer.isNotEmpty()) {
                val synthetic = events.lastOrNull { it.origin == origin }
                if (synthetic != null) {
                    parseLine(buffer.toString(), sessionId, projectRoot, synthetic)?.let(diagnostics::add)
                }
            }
        }
        return diagnostics
    }

    private fun emitCompleteLines(buffer: StringBuilder): List<String> {
        val result = mutableListOf<String>()
        while (true) {
            val index = buffer.indexOf("\n")
            if (index < 0) break
            result += buffer.substring(0, index).trimEnd('\r')
            buffer.delete(0, index + 1)
        }
        return result
    }

    private fun parseLine(
        line: String,
        sessionId: BuildSessionId,
        projectRoot: Path,
        event: BuildLogEvent
    ): BuildDiagnostic? {
        val fileLine = FILE_LINE.matchEntire(line)
        if (fileLine != null) {
            val candidate = try {
                val path = Path.of(fileLine.groupValues[1])
                (if (path.isAbsolute) path else projectRoot.resolve(path)).normalize()
                    .takeIf { it.startsWith(projectRoot) }
            } catch (_: RuntimeException) {
                null
            }
            return BuildDiagnostic(
                kind = DiagnosticKind.TEX_ERROR,
                severity = DiagnosticSeverity.ERROR,
                message = fileLine.groupValues[3].trim(),
                sessionId = sessionId,
                origin = event.origin.name,
                sourcePath = candidate,
                line = fileLine.groupValues[2].toIntOrNull(),
                relatedEventSequence = event.sequence,
                confidence = if (candidate != null) DiagnosticConfidence.EXACT else DiagnosticConfidence.INCOMPLETE
            )
        }
        if (line.startsWith("!")) {
            return BuildDiagnostic(
                kind = DiagnosticKind.TEX_ERROR,
                severity = DiagnosticSeverity.ERROR,
                message = line.removePrefix("!").trim().ifEmpty { "TeX reported an error." },
                sessionId = sessionId,
                origin = event.origin.name,
                relatedEventSequence = event.sequence
            )
        }
        if (line.contains("LaTeX Warning:", ignoreCase = true)) {
            return BuildDiagnostic(
                kind = DiagnosticKind.TEX_WARNING,
                severity = DiagnosticSeverity.WARNING,
                message = line.trim(),
                sessionId = sessionId,
                origin = event.origin.name,
                relatedEventSequence = event.sequence
            )
        }
        return null
    }

    private companion object {
        val FILE_LINE = Regex("""^(.+):(\d+):\s*(.+)$""")
    }
}
