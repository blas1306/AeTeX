package dev.aetex.compilation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LatexDiagnosticExtractorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `extracts undefined control sequence in main file`() {
        val root = project("main.tex")
        val diagnostic = extract(root, BuildLogOrigin.STDERR, "main.tex:6: Undefined control sequence.\nl.6 \\badcommand\n").single()

        assertEquals(DiagnosticKind.TEX_ERROR, diagnostic.kind)
        assertEquals(root.resolve("main.tex").toRealPath(), diagnostic.sourcePath)
        assertEquals("main.tex", diagnostic.reportedPath)
        assertEquals(6, diagnostic.line)
        assertEquals("l.6 \\badcommand", diagnostic.contextLine)
    }

    @Test
    fun `extracts included chapter path and line`() {
        val root = project("main.tex", "chapters/cap1.tex")
        val diagnostic = extract(
            root,
            BuildLogOrigin.STDOUT,
            "chapters/cap1.tex:123: Undefined control sequence.\nl.123 Text \\noSuchCommand\n"
        ).single()

        assertEquals(root.resolve("chapters/cap1.tex").toRealPath(), diagnostic.sourcePath)
        assertEquals("chapters/cap1.tex", diagnostic.reportedPath)
        assertEquals(123, diagnostic.line)
    }

    @Test
    fun `parses generic file line error format`() {
        val root = project("file.tex")
        val diagnostic = extract(root, BuildLogOrigin.STDERR, "file.tex:42: Missing \$ inserted.\n").single()

        assertEquals(42, diagnostic.line)
        assertEquals("Missing \$ inserted.", diagnostic.message)
    }

    @Test
    fun `associates classic fatal error with l line and context`() {
        val root = project("main.tex")
        val diagnostic = extract(
            root,
            BuildLogOrigin.STDERR,
            "! Undefined control sequence.\n<argument> \\broken\nl.123 Text \\broken\n"
        ).single()

        assertEquals("Undefined control sequence.", diagnostic.message)
        assertEquals(123, diagnostic.line)
        assertEquals("l.123 Text \\broken", diagnostic.contextLine)
    }

    @Test
    fun `reads diagnostics emitted only to stdout and ignores latexmk epilogue`() {
        val root = project("main.tex")
        val diagnostics = extract(
            root,
            BuildLogOrigin.STDOUT,
            "main.tex:9: Undefined control sequence.\nLatexmk: Sometimes, the -f option can be used to get latexmk to try to force complete processing.\n"
        )

        assertEquals(1, diagnostics.size)
        assertEquals(9, diagnostics.single().line)
    }

    @Test
    fun `keeps warnings distinct from following fatal error`() {
        val root = project("main.tex")
        val diagnostics = extract(
            root,
            BuildLogOrigin.STDERR,
            "LaTeX Warning: Reference undefined.\nmain.tex:17: Emergency stop.\n"
        )

        assertEquals(listOf(DiagnosticSeverity.WARNING, DiagnosticSeverity.ERROR), diagnostics.map { it.severity })
        assertEquals("Emergency stop.", diagnostics.last().message)
    }

    @Test
    fun `supports spaces and non ASCII in validated project paths`() {
        val root = project("chapters/intro one ü.tex")
        val diagnostic = extract(
            root,
            BuildLogOrigin.STDERR,
            "chapters/intro one ü.tex:71: Undefined control sequence.\n"
        ).single()

        assertEquals("chapters/intro one ü.tex", diagnostic.reportedPath)
        assertEquals(root.resolve("chapters/intro one ü.tex").toRealPath(), diagnostic.sourcePath)
    }

    @Test
    fun `parses Windows style path without trusting it on another platform`() {
        val root = project("main.tex")
        val diagnostic = extract(
            root,
            BuildLogOrigin.STDERR,
            "C:\\Projects\\Book One\\chapter.tex:88: Undefined control sequence.\n"
        ).single()

        assertEquals("C:\\Projects\\Book One\\chapter.tex", diagnostic.reportedPath)
        assertEquals(88, diagnostic.line)
        if (!HostPlatform.current().isWindows) assertNull(diagnostic.sourcePath)
    }

    @Test
    fun `does not expose path outside project for navigation`() {
        val root = project("main.tex")
        val outside = temporaryDirectory.resolve("outside.tex")
        Files.writeString(outside, "bad")
        val diagnostic = extract(root, BuildLogOrigin.STDERR, "$outside:2: Undefined control sequence.\n").single()

        assertNull(diagnostic.sourcePath)
        assertTrue(diagnostic.reportedPath!!.endsWith("outside.tex"))
    }

    private fun project(vararg files: String): Path {
        val root = temporaryDirectory.resolve("project")
        Files.createDirectories(root)
        files.forEach { relative ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "% fixture")
        }
        return root
    }

    private fun extract(root: Path, origin: BuildLogOrigin, text: String): List<BuildDiagnostic> =
        BasicLatexDiagnosticExtractor().extract(
            SESSION,
            root,
            listOf(event(origin, text))
        )

    private fun event(origin: BuildLogOrigin, text: String) = BuildLogEvent(
        sessionId = SESSION,
        sequence = 1,
        timestamp = Instant.EPOCH,
        elapsed = Duration.ZERO,
        origin = origin,
        rawBytes = text.toByteArray(),
        decodedText = text,
        decodingStatus = DecodingStatus.COMPLETE
    )

    private companion object {
        val SESSION = BuildSessionId("diagnostics")
    }
}
