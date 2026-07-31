package dev.aetex.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorLineModelTest {
    private val manualDocument = """\documentclass{book}
\begin{document}

\begin{titlepage}
Title
\end{titlepage}

\cleardoublepage
\frontmatter
\tableofcontents
\mainmatter

\input{chapters/cap1}
\end{document}"""

    @Test
    fun `stale titlepage layout that points at mainmatter is rejected`() {
        val caret = manualDocument.indexOf("\\end{titlepage}") + "\\end{titlepage}".length
        val staleText = "header\n" + "x".repeat(caret - 8) + "\n\\mainmatter\n"

        val staleLine = LogicalLineIndex.of(staleText).lineForOffset(caret, staleText.length)

        assertEquals("\\mainmatter", staleText.lineSequence().elementAt(staleLine))
        assertFalse(acceptsEditorLayout(manualDocument, staleText))
        assertTrue(acceptsEditorLayout(manualDocument, manualDocument))
    }

    @Test
    fun `legacy unscrolled highlight for titlepage lands over visible mainmatter row`() {
        val lines = LogicalLineIndex.of(manualDocument)
        val layout = FakeLayout.atLogicalStarts(manualDocument)
        val titlepageLine = lines.lineForOffset(manualDocument.indexOf("\\end{titlepage}"), manualDocument.length)
        val mainmatterLine = lines.lineForOffset(manualDocument.indexOf("\\mainmatter"), manualDocument.length)
        val titlepage = editorLineGeometry(manualDocument, lines, layout, titlepageLine)
        val mainmatter = editorLineGeometry(manualDocument, lines, layout, mainmatterLine)
        val internalTextScroll = mainmatter.top - titlepage.top

        val legacyHighlightViewportY = titlepage.top
        val documentYBehindLegacyHighlight = legacyHighlightViewportY + internalTextScroll
        val visibleLineBehindLegacyHighlight = (documentYBehindLegacyHighlight / 10f).toInt()
        val sharedOwnerHighlightViewportY = titlepage.top - internalTextScroll

        assertEquals(mainmatterLine, visibleLineBehindLegacyHighlight)
        assertEquals(0f, sharedOwnerHighlightViewportY)
        assertEquals(titlepageLine, lines.lineForOffset(manualDocument.indexOf("\\end{titlepage}"), manualDocument.length))
    }

    @Test
    fun `selection follows multiline insertion and removal using canonical UTF-16 offset`() {
        val originalCaret = manualDocument.indexOf("\\mainmatter")
        val inserted = "first\nsecond\n$manualDocument"
        val insertedCaret = originalCaret + "first\nsecond\n".length

        assertEquals(10, LogicalLineIndex.of(manualDocument).lineForOffset(originalCaret, manualDocument.length))
        assertEquals(12, LogicalLineIndex.of(inserted).lineForOffset(insertedCaret, inserted.length))
        assertEquals(10, LogicalLineIndex.of(manualDocument).lineForOffset(originalCaret, manualDocument.length))
    }

    @Test
    fun `empty trailing newline CRLF Unicode and selection at EOF have stable logical lines`() {
        assertEquals(1, LogicalLineIndex.of("").lineCount)
        assertEquals(1, LogicalLineIndex.of("one\n").lineForOffset(4, 4))

        val crlf = "α😀\r\nβ\r\n"
        val lines = LogicalLineIndex.of(crlf)
        assertEquals(3, lines.lineCount)
        assertEquals(0, lines.lineForOffset(crlf.indexOf("😀") + 1, crlf.length))
        assertEquals(1, lines.lineForOffset(crlf.indexOf("β"), crlf.length))
        assertEquals(2, lines.lineForOffset(crlf.length, crlf.length))
    }

    @Test
    fun `soft wraps add visual rows but only logical starts receive numbers`() {
        val text = "abcdefghij\nnext"
        val lines = LogicalLineIndex.of(text)
        val layout = FakeLayout(text, intArrayOf(0, 4, 8, 11))

        val first = editorLineGeometry(text, lines, layout, 0)
        val second = editorLineGeometry(text, lines, layout, 1)

        assertEquals(0, first.firstVisualLine)
        assertEquals(2, first.lastVisualLine)
        assertEquals(0f, first.top)
        assertEquals(30f, first.bottom)
        assertEquals(3, second.firstVisualLine)
        assertEquals(listOf(1, 2), lines.starts.indices.map { it + 1 })
    }

    @Test
    fun `scroll changes viewport geometry without changing current logical line`() {
        val text = (1..100).joinToString("\n") { "line $it" }
        val lines = LogicalLineIndex.of(text)
        val caret = text.indexOf("line 70")
        val current = lines.lineForOffset(caret, text.length)
        val geometry = editorLineGeometry(text, lines, FakeLayout.atLogicalStarts(text), current)

        assertEquals(69, current)
        assertEquals(250f, geometry.top - 440f)
        assertEquals(69, lines.lineForOffset(caret, text.length))
    }

    @Test
    fun `editing near start of long file deterministically shifts gutter lines`() {
        val original = (1..1_000).joinToString("\n") { "line $it" }
        val edited = "new one\nnew two\n$original"

        assertEquals(1_000, LogicalLineIndex.of(original).lineCount)
        assertEquals(1_002, LogicalLineIndex.of(edited).lineCount)
        assertEquals(4, lineNumberDigitCount(LogicalLineIndex.of(edited).lineCount))
        assertEquals(2, lineNumberDigitCount(9))
    }

    @Test
    fun `selection focus scroll and other visual updates do not publish editor text changes`() {
        val canonical = "\\end{titlepage}\n\n\\mainmatter"

        assertFalse(shouldPublishEditorTextChange(canonical, canonical))
        assertTrue(shouldPublishEditorTextChange("\n$canonical", canonical))
    }

    private class FakeLayout(
        override val text: String,
        private val visualStarts: IntArray
    ) : EditorTextLayout {
        override fun visualLineForOffset(offset: Int): Int {
            val found = visualStarts.binarySearch(offset.coerceIn(0, text.length))
            return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        }

        override fun lineTop(line: Int): Float = line * 10f
        override fun lineBottom(line: Int): Float = (line + 1) * 10f
        override fun lineBaseline(line: Int): Float = line * 10f + 8f

        companion object {
            fun atLogicalStarts(text: String) = FakeLayout(text, LogicalLineIndex.of(text).starts)
        }
    }
}
