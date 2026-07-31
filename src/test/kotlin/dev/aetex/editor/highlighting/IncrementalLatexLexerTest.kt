package dev.aetex.editor.highlighting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalLatexLexerTest {
    @Test
    fun `recognizes ordinary and structural commands`() {
        val text = "\\section{Intro} \\documentclass{article}"
        val tokens = IncrementalLatexLexer().update(text).tokens

        assertToken(text, tokens, "\\section", LatexTokenKind.COMMAND)
        assertToken(text, tokens, "\\documentclass", LatexTokenKind.KEYWORD)
    }

    @Test
    fun `comment consumes the remainder of its line only`() {
        val text = "text % \\ignored\n\\section{Live}"
        val tokens = IncrementalLatexLexer().update(text).tokens

        assertToken(text, tokens, "% \\ignored", LatexTokenKind.COMMENT)
        assertToken(text, tokens, "\\section", LatexTokenKind.COMMAND)
    }

    @Test
    fun `nested mandatory braces remain balanced and styled`() {
        val text = "\\command{outer {inner} tail}"
        val tokens = IncrementalLatexLexer().update(text).tokens

        assertEquals(4, tokens.count { it.kind == LatexTokenKind.BRACE })
        assertToken(text, tokens, "outer ", LatexTokenKind.MANDATORY_ARGUMENT)
        assertToken(text, tokens, "inner", LatexTokenKind.MANDATORY_ARGUMENT)
    }

    @Test
    fun `begin and end environment names have their own token kind`() {
        val text = "\\begin{document}\nbody\n\\end{document}"
        val tokens = IncrementalLatexLexer().update(text).tokens

        assertEquals(
            2,
            tokens.count {
                it.kind == LatexTokenKind.ENVIRONMENT && text.substring(it.start, it.end) == "document"
            }
        )
    }

    @Test
    fun `optional and mandatory arguments are distinct`() {
        val text = "\\usepackage[quiet]{geometry}"
        val tokens = IncrementalLatexLexer().update(text).tokens

        assertToken(text, tokens, "quiet", LatexTokenKind.OPTIONAL_ARGUMENT)
        assertToken(text, tokens, "geometry", LatexTokenKind.MANDATORY_ARGUMENT)
    }

    @Test
    fun `unterminated argument carries stable state to following lines`() {
        val lexer = IncrementalLatexLexer()
        val first = "\\command[unfinished\nstill typing"
        val before = lexer.update(first)

        assertToken(first, before.tokens, "still typing", LatexTokenKind.OPTIONAL_ARGUMENT)

        val completed = "$first]"
        val after = lexer.update(completed)
        assertToken(completed, after.tokens, "still typing", LatexTokenKind.OPTIONAL_ARGUMENT)
        assertFalse(after.tokens.any { it.kind == LatexTokenKind.ERROR })
    }

    @Test
    fun `recognizes every supported math delimiter form`() {
        val text = "\$x\$ \$\$y\$\$ \\(z\\) \\[w\\]"
        val delimiters = IncrementalLatexLexer().update(text).tokens
            .filter { it.kind == LatexTokenKind.MATH_DELIMITER }
            .map { text.substring(it.start, it.end) }

        assertEquals(listOf("$", "$", "$$", "$$", "\\(", "\\)", "\\[", "\\]"), delimiters)
    }

    @Test
    fun `escaped comment and brace characters do not open constructs`() {
        val text = "escaped \\% and \\{ text % real comment"
        val tokens = IncrementalLatexLexer().update(text).tokens

        assertToken(text, tokens, "\\%", LatexTokenKind.ESCAPE)
        assertToken(text, tokens, "\\{", LatexTokenKind.ESCAPE)
        assertToken(text, tokens, "% real comment", LatexTokenKind.COMMENT)
    }

    @Test
    fun `unfinished escape remains a stable token while typing`() {
        val text = "text ending in \\"

        assertToken(
            text,
            IncrementalLatexLexer().update(text).tokens,
            "\\",
            LatexTokenKind.ESCAPE
        )
    }

    @Test
    fun `single line edit reuses unaffected lines`() {
        val lexer = IncrementalLatexLexer()
        lexer.update("first\n\\section{Before}\nlast")

        val edited = lexer.update("first\n\\section{After}\nlast")

        assertEquals(3, edited.updateStats.totalLineCount)
        assertEquals(1, edited.updateStats.relexedLineCount)
        assertEquals(2, edited.updateStats.reusedLineCount)
        assertToken(edited.text, edited.tokens, "\\section", LatexTokenKind.COMMAND)
    }

    @Test
    fun `state changing incremental edit propagates only until stable suffix`() {
        val lexer = IncrementalLatexLexer()
        lexer.update("top\nplain\nafter\ntail")

        val edited = lexer.update("top\n{open\nafter}\ntail")

        assertTrue(edited.updateStats.relexedLineCount in 2..3)
        assertTrue(edited.updateStats.reusedLineCount >= 2)
        assertToken(edited.text, edited.tokens, "after", LatexTokenKind.MANDATORY_ARGUMENT)
    }

    @Test
    fun `large document typing relexes only the edited line`() {
        val lexer = IncrementalLatexLexer()
        val original = (0 until 1_000).joinToString("\n") { "line $it" }
        lexer.update(original)
        val edited = original.replace("line 500", "line 500 edited")

        val result = lexer.update(edited)

        assertEquals(1_000, result.updateStats.totalLineCount)
        assertEquals(1, result.updateStats.relexedLineCount)
        assertEquals(999, result.updateStats.reusedLineCount)
    }

    @Test
    fun `numbers braces strings escapes and plain text all receive tokens`() {
        val text = "plain 12 {arg} \"value\" \\#"
        val kinds = IncrementalLatexLexer().update(text).tokens.mapTo(mutableSetOf()) { it.kind }

        assertTrue(LatexTokenKind.PLAIN_TEXT in kinds)
        assertTrue(LatexTokenKind.NUMBER in kinds)
        assertTrue(LatexTokenKind.BRACE in kinds)
        assertTrue(LatexTokenKind.MANDATORY_ARGUMENT in kinds)
        assertTrue(LatexTokenKind.STRING in kinds)
        assertTrue(LatexTokenKind.ESCAPE in kinds)
    }

    private fun assertToken(
        text: String,
        tokens: List<LatexToken>,
        expectedText: String,
        expectedKind: LatexTokenKind
    ) {
        assertTrue(
            tokens.any {
                it.kind == expectedKind && text.substring(it.start, it.end) == expectedText
            },
            "Expected $expectedKind token '$expectedText' in $tokens"
        )
    }
}
