package dev.aetex.editor.highlighting

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.aetex.editor.theme.EditorTheme

class LatexVisualTransformation(
    private val lexer: IncrementalLatexLexer,
    private val theme: EditorTheme
) : VisualTransformation {
    private var cachedText: String? = null
    private var cachedResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        cachedResult?.takeIf { cachedText == text.text }?.let { return it }
        val highlighted = lexer.update(text.text)
        val annotated = AnnotatedString.Builder(text.text).apply {
            highlighted.tokens.forEach { token ->
                require(token.start in 0..text.length)
                require(token.end in token.start..text.length)
                addStyle(theme.styleFor(token.kind), token.start, token.end)
            }
        }.toAnnotatedString()
        check(annotated.text == text.text)
        check(annotated.length == text.length)
        return TransformedText(annotated, OffsetMapping.Identity).also {
            cachedText = text.text
            cachedResult = it
        }
    }
}
