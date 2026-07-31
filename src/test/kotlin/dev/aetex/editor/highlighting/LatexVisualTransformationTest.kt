package dev.aetex.editor.highlighting

import androidx.compose.ui.text.AnnotatedString
import dev.aetex.editor.theme.AeTeXEditorThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LatexVisualTransformationTest {
    @Test
    fun `highlighting preserves exact UTF-16 source newline sequence and identity offsets`() {
        val source = "α😀 \\section{Título}\r\n% комментарий\n"
        val transformed = LatexVisualTransformation(
            IncrementalLatexLexer(),
            AeTeXEditorThemes.Dark
        ).filter(AnnotatedString(source))

        assertEquals(source, transformed.text.text)
        assertEquals(source.length, transformed.text.length)
        assertEquals(source.filter { it == '\r' || it == '\n' }, transformed.text.text.filter { it == '\r' || it == '\n' })
        (0..source.length).forEach { offset ->
            assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, transformed.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test
    fun `all style spans remain valid after incremental multiline edits`() {
        val lexer = IncrementalLatexLexer()
        val transformation = LatexVisualTransformation(lexer, AeTeXEditorThemes.Dark)
        val revisions = listOf(
            "\\begin{document}\n\\end{document}",
            "prefix\r\n\\begin{document}\n😀 {open\ntext}\n\\end{document}\n",
            "\\end{titlepage}\n\n\\mainmatter"
        )

        revisions.forEach { source ->
            val transformed = transformation.filter(AnnotatedString(source))
            assertTrue(transformed.text.spanStyles.all { it.start in 0..source.length && it.end in it.start..source.length })
            assertEquals(source, lexer.current().text)
            assertTrue(lexer.current().tokens.all { it.start in 0..source.length && it.end in it.start..source.length })
        }
    }
}
