package dev.aetex.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorCaretViewportCoordinatorTest {
    @Test
    fun `caret above viewport and typing reveals it`() {
        val fixture = fixture(lines(30), caret = 2)
        fixture.manualScroll()
        fixture.action(textRevision = 1, text = "x${fixture.text}", caret = 3)
        fixture.currentLayout = fixture.layout(textRevision = 1, text = "x${fixture.text}")

        assertEquals(4, fixture.reveal(scroll = 180, height = 60).targetScroll)
    }

    @Test
    fun `caret below viewport and typing reveals it`() {
        val text = lines(30)
        val caret = LogicalLineIndex.of(text).starts[20]
        val fixture = fixture(text, caret)
        fixture.manualScroll()
        fixture.action(textRevision = 1, text = text.replaceRange(caret, caret, "x"), caret = caret + 1)
        fixture.currentLayout = fixture.layout(
            textRevision = 1,
            text = text.replaceRange(caret, caret, "x")
        )

        assertEquals(170, fixture.reveal(scroll = 20, height = 60).targetScroll)
    }

    @Test
    fun `manual scroll without a selection change does not snap back`() {
        val fixture = fixture(lines(30), caret = 0)

        assertTrue(fixture.manualScroll())
        assertNull(fixture.resolve(scroll = 180, height = 60))
        assertFalse(fixture.manualScroll())
    }

    @Test
    fun `manual scroll followed by typing creates a fresh reveal`() {
        val fixture = fixture(lines(30), caret = 0)
        fixture.manualScroll()

        fixture.action(textRevision = 1, text = "x${fixture.text}", caret = 1)
        fixture.currentLayout = fixture.layout(textRevision = 1, text = "x${fixture.text}")

        assertTrue(fixture.reveal(scroll = 180, height = 60).requiresScroll)
    }

    @Test
    fun `arrow navigation crossing top edge scrolls upward`() {
        val text = lines(30)
        val fixture = fixture(text, LogicalLineIndex.of(text).starts[10])
        fixture.manualScroll()
        fixture.action(caret = LogicalLineIndex.of(text).starts[9])

        assertEquals(94, fixture.reveal(scroll = 100, height = 60).targetScroll)
    }

    @Test
    fun `arrow navigation crossing bottom edge scrolls downward`() {
        val text = lines(30)
        val fixture = fixture(text, LogicalLineIndex.of(text).starts[5])
        fixture.manualScroll()
        fixture.action(caret = LogicalLineIndex.of(text).starts[6])

        assertEquals(30, fixture.reveal(scroll = 0, height = 60).targetScroll)
    }

    @Test
    fun `home and end reveal their resulting visual rows`() {
        val text = "abcdefghij\nnext"
        val fixture = fixture(text, caret = 0, visualStarts = intArrayOf(0, 4, 8, 11))
        fixture.manualScroll()
        fixture.action(caret = 10)
        assertEquals(30, fixture.reveal(scroll = 0, height = 20).targetScroll)

        fixture.action(caret = 0)
        assertEquals(4, fixture.reveal(scroll = 20, height = 20).targetScroll)
    }

    @Test
    fun `document start and end navigation reveal boundaries`() {
        val text = lines(30)
        val fixture = fixture(text, caret = 0)
        fixture.manualScroll()
        fixture.action(caret = text.length)
        assertEquals(260, fixture.reveal(scroll = 0, height = 60).targetScroll)

        fixture.action(caret = 0)
        assertEquals(4, fixture.reveal(scroll = 240, height = 60).targetScroll)
    }

    @Test
    fun `page up and page down reveal current editor result without changing semantics`() {
        val text = lines(40)
        val starts = LogicalLineIndex.of(text).starts
        val fixture = fixture(text, caret = starts[10])
        fixture.manualScroll()
        fixture.action(caret = starts[4])
        assertEquals(44, fixture.reveal(scroll = 100, height = 60).targetScroll)

        fixture.action(caret = starts[16])
        assertEquals(130, fixture.reveal(scroll = 40, height = 60).targetScroll)
    }

    @Test
    fun `mouse and programmatic selection changes reveal the active caret`() {
        val text = lines(30)
        val starts = LogicalLineIndex.of(text).starts
        val fixture = fixture(text, caret = 0)
        fixture.manualScroll()

        fixture.action(caret = starts[12])
        assertEquals(100, fixture.reveal(scroll = 0, height = 50).targetScroll)
        fixture.action(caret = starts[2])
        assertEquals(24, fixture.reveal(scroll = 100, height = 50).targetScroll)
    }

    @Test
    fun `selection extension reveals selection end as the active endpoint`() {
        val text = lines(30)
        val starts = LogicalLineIndex.of(text).starts
        val fixture = fixture(text, caret = starts[2])
        fixture.manualScroll()

        fixture.action(selectionStart = starts[2], caret = starts[14])

        assertEquals(120, fixture.reveal(scroll = 0, height = 50).targetScroll)
    }

    @Test
    fun `visible caret consumes request without scrolling`() {
        val text = lines(20)
        val starts = LogicalLineIndex.of(text).starts
        val fixture = fixture(text, caret = starts[5])

        val reveal = fixture.reveal(scroll = 40, height = 60)

        assertFalse(reveal.requiresScroll)
        assertEquals(40, reveal.targetScroll)
        assertNull(fixture.resolve(scroll = 40, height = 60))
    }

    @Test
    fun `trailing newline caret at EOF uses final visual row`() {
        val fixture = fixture("a\n", caret = 2)

        assertEquals(20, fixture.reveal(scroll = 0, height = 20, maximumScroll = 20).targetScroll)
    }

    @Test
    fun `empty file caret is valid and clamped to viewport start`() {
        val fixture = fixture("", caret = 0)

        val reveal = fixture.reveal(scroll = 0, height = 20, maximumScroll = 0)
        assertEquals(0, reveal.targetScroll)
        assertFalse(reveal.requiresScroll)
    }

    @Test
    fun `CRLF offsets reveal their actual rows`() {
        val text = "one\r\ntwo\r\nthree"
        val fixture = fixture(text, caret = text.indexOf("three"))

        assertEquals(30, fixture.reveal(scroll = 0, height = 20).targetScroll)
    }

    @Test
    fun `Unicode surrogate pair offsets remain canonical UTF-16 offsets`() {
        val text = "α😀β\nnext"
        val insideSurrogatePair = text.indexOf("😀") + 1
        val fixture = fixture(text, caret = insideSurrogatePair)

        val reveal = fixture.reveal(scroll = 0, height = 40)
        assertEquals(0, reveal.targetScroll)
        assertFalse(reveal.requiresScroll)
    }

    @Test
    fun `soft wrapped long line reveals only actual caret visual row`() {
        val text = "abcdefghijkl"
        val fixture = fixture(text, caret = 10, visualStarts = intArrayOf(0, 4, 8))

        val reveal = fixture.reveal(scroll = 0, height = 20, maximumScroll = 20)

        assertEquals(20, reveal.targetScroll)
    }

    @Test
    fun `rapid selection changes coalesce to latest target`() {
        val text = lines(30)
        val starts = LogicalLineIndex.of(text).starts
        val fixture = fixture(text, caret = starts[2])
        val old = fixture.resolve(scroll = 0, height = 40)
        assertNotNull(old)

        fixture.action(caret = starts[20])
        assertFalse(fixture.coordinator.isCurrent(old))
        assertFalse(fixture.coordinator.complete(old))
        assertEquals(190, fixture.reveal(scroll = 0, height = 40).targetScroll)
    }

    @Test
    fun `stale text layout cannot scroll`() {
        val fixture = fixture(lines(20), caret = 0)
        fixture.action(textRevision = 1, text = "x${fixture.text}", caret = 1)

        assertNull(
            fixture.coordinator.resolve(
                fixture.layout(textRevision = 0, text = fixture.text),
                fixture.viewport(scroll = 100, height = 40)
            )
        )
    }

    @Test
    fun `pending reveal executes when exact matching layout arrives`() {
        val fixture = fixture(lines(20), caret = 0)
        fixture.action(textRevision = 1, text = "x${fixture.text}", caret = 1)
        assertNull(fixture.resolve(scroll = 100, height = 40))

        fixture.currentLayout = fixture.layout(textRevision = 1, text = "x${fixture.text}")
        assertEquals(4, fixture.reveal(scroll = 100, height = 40).targetScroll)
    }

    @Test
    fun `manual scroll after pending reveal invalidates it`() {
        val fixture = fixture(lines(20), caret = 0)
        fixture.action(textRevision = 1, text = "x${fixture.text}", caret = 1)

        assertTrue(fixture.manualScroll())
        fixture.currentLayout = fixture.layout(textRevision = 1, text = "x${fixture.text}")
        assertNull(fixture.resolve(scroll = 100, height = 40))
    }

    @Test
    fun `document replacement invalidates old command and uses replacement selection`() {
        val fixture = fixture(lines(20), caret = 0)
        val old = assertNotNull(fixture.resolve(scroll = 100, height = 40))
        val replacement = lines(30)
        val replacementCaret = LogicalLineIndex.of(replacement).starts[20]

        fixture.coordinator.activateDocument(2, 0, replacement, replacementCaret, replacementCaret)

        assertFalse(fixture.coordinator.isCurrent(old))
        assertNull(
            fixture.coordinator.resolve(
                fixture.currentLayout,
                fixture.viewport(scroll = 0, height = 40)
            )
        )
        fixture.currentLayout = fixture.layout(2, 0, replacement)
        assertEquals(190, fixture.reveal(scroll = 0, height = 40).targetScroll)
    }

    @Test
    fun `closing document cancels pending reveal and invalidates issued command`() {
        val fixture = fixture(lines(20), caret = 0)
        val issued = assertNotNull(fixture.resolve(scroll = 100, height = 40))

        fixture.coordinator.closeDocument()

        assertFalse(fixture.coordinator.isCurrent(issued))
        assertNull(fixture.resolve(scroll = 100, height = 40))
    }

    @Test
    fun `shared scroll offset keeps highlight gutter glyph and caret geometry aligned`() {
        val text = lines(30)
        val logicalLines = LogicalLineIndex.of(text)
        val caret = logicalLines.starts[20]
        val fixture = fixture(text, caret)
        val target = fixture.reveal(scroll = 0, height = 40).targetScroll
        val geometry = editorLineGeometry(text, logicalLines, fixture.lineLayout(), 20)

        assertEquals(22f, geometry.top + 12f - target)
        assertEquals(20, logicalLines.lineForOffset(caret, text.length))
    }

    @Test
    fun `visual reveal changes neither document generation nor build requests`() {
        val fixture = fixture(lines(30), caret = 0)
        var documentGeneration = 7
        var buildRequests = 0

        fixture.reveal(scroll = 100, height = 40)

        assertEquals(7, documentGeneration)
        assertEquals(0, buildRequests)
    }

    @Test
    fun `deletion and newline insertion request exact new text revisions`() {
        val fixture = fixture("abc", caret = 3)
        fixture.manualScroll()
        fixture.action(textRevision = 1, text = "ab", caret = 2)
        fixture.currentLayout = fixture.layout(textRevision = 1, text = "ab")
        assertNotNull(fixture.reveal(scroll = 0, height = 20))

        fixture.action(textRevision = 2, text = "a\nb", caret = 2)
        fixture.currentLayout = fixture.layout(textRevision = 2, text = "a\nb")
        assertNotNull(fixture.reveal(scroll = 0, height = 20))
    }

    @Test
    fun `identical canonical value republication does not request reveal`() {
        val fixture = fixture(lines(20), caret = 0)
        fixture.manualScroll()

        assertFalse(fixture.action(caret = 0))
        assertNull(fixture.resolve(scroll = 100, height = 40))
    }

    private fun fixture(
        text: String,
        caret: Int,
        visualStarts: IntArray = LogicalLineIndex.of(text).starts
    ): Fixture = Fixture(text, caret, visualStarts)

    private fun lines(count: Int): String = (1..count).joinToString("\n") { "line $it" }

    private class Fixture(
        val text: String,
        caret: Int,
        private val visualStarts: IntArray
    ) {
        val coordinator = EditorCaretViewportCoordinator()
        var currentLayout: FakeCaretLayout = layout(text = text)
        private var canonicalText = text
        private var canonicalTextRevision = 0L
        private var canonicalCaret = caret

        init {
            coordinator.activateDocument(1, 0, text, caret, caret)
        }

        fun action(
            textRevision: Long = canonicalTextRevision,
            text: String = canonicalText,
            caret: Int = canonicalCaret,
            selectionStart: Int = caret
        ): Boolean {
            val changed = coordinator.onEditorAction(1, textRevision, text, selectionStart, caret)
            if (changed) {
                canonicalText = text
                canonicalTextRevision = textRevision
                canonicalCaret = caret
            }
            return changed
        }

        fun manualScroll(): Boolean = coordinator.onManualViewportChange()

        fun resolve(
            scroll: Int,
            height: Int,
            maximumScroll: Int = 10_000
        ): EditorCaretReveal? = coordinator.resolve(
            currentLayout,
            viewport(scroll, height, maximumScroll)
        )

        fun reveal(
            scroll: Int,
            height: Int,
            maximumScroll: Int = 10_000
        ): EditorCaretReveal {
            val reveal = assertNotNull(resolve(scroll, height, maximumScroll))
            assertTrue(coordinator.complete(reveal))
            return reveal
        }

        fun viewport(scroll: Int, height: Int, maximumScroll: Int = 10_000) = EditorViewport(
            scroll = scroll,
            maximumScroll = maximumScroll,
            height = height,
            textTopPadding = 12f,
            revealMargin = 8f
        )

        fun layout(
            documentRevision: Long = 1,
            textRevision: Long = 0,
            text: String = this.text
        ) = FakeCaretLayout(
            documentRevision,
            textRevision,
            text,
            if (text == this.text) visualStarts else LogicalLineIndex.of(text).starts
        )

        fun lineLayout(): EditorTextLayout = FakeLineLayout(text, visualStarts)

    }

    private class FakeCaretLayout(
        override val documentRevision: Long,
        override val textRevision: Long,
        override val text: String,
        private val visualStarts: IntArray
    ) : EditorCaretLayout {
        override fun caretBounds(offset: Int): EditorCaretBounds {
            val line = visualLineForOffset(offset)
            return EditorCaretBounds(line * 10f, (line + 1) * 10f)
        }

        private fun visualLineForOffset(offset: Int): Int {
            val found = visualStarts.binarySearch(offset.coerceIn(0, text.length))
            return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
        }
    }

    private class FakeLineLayout(
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
    }
}
