package dev.aetex.editor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.aetex.editor.highlighting.LatexTokenKind
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditorThemeTest {
    private val theme = AeTeXEditorThemes.Dark

    @Test
    fun `every lexical token has an explicit visible style`() {
        LatexTokenKind.entries.forEach { kind ->
            val style = theme.styleFor(kind)
            assertNotEquals(Color.Unspecified, style.color, "Missing color for $kind")
            assertTrue(style.color.alpha > 0f, "Transparent color for $kind")
        }
    }

    @Test
    fun `caret has strong contrast against editor and current line`() {
        assertTrue(contrast(theme.caret, theme.background) >= 7.0)
        assertTrue(contrast(theme.caret, theme.currentLine) >= 7.0)
    }

    @Test
    fun `focused and unfocused selection remain distinguishable`() {
        assertNotEquals(theme.selection, theme.background)
        assertNotEquals(theme.unfocusedSelection, theme.background)
        assertTrue(theme.selection.alpha > theme.unfocusedSelection.alpha)
    }

    @Test
    fun `current line colors are subtle but distinct`() {
        assertNotEquals(theme.currentLine, theme.background)
        assertNotEquals(theme.unfocusedCurrentLine, theme.background)
        assertTrue(contrast(theme.currentLine, theme.background) < 1.5)
    }

    private fun contrast(first: Color, second: Color): Double {
        val lighter = maxOf(first.luminance(), second.luminance()).toDouble()
        val darker = minOf(first.luminance(), second.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }
}
