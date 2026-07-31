package dev.aetex.editor

import dev.aetex.editor.highlighting.IncrementalLatexLexer
import dev.aetex.editor.highlighting.LatexTokenKind
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorHighlightingIntegrationTest {
    @Test
    fun `highlighting follows immutable open document edits without changing file identity`() {
        val path = Path.of("project/main.tex")
        val original = OpenDocument(
            path = path,
            content = "\\section{Before}\n",
            savedContent = "\\section{Before}\n"
        )
        val lexer = IncrementalLatexLexer()
        lexer.update(original.content)

        val edited = original.withContent("\\section{After}\n% note\n")
        val highlighted = lexer.update(edited.content)

        assertEquals(path, edited.path)
        assertTrue(edited.isModified)
        assertTrue(highlighted.tokens.any { it.kind == LatexTokenKind.COMMAND })
        assertTrue(highlighted.tokens.any { it.kind == LatexTokenKind.COMMENT })
        assertTrue(highlighted.updateStats.relexedLineCount < highlighted.updateStats.totalLineCount)
    }
}
