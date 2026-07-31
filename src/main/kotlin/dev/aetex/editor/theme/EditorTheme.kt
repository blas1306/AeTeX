package dev.aetex.editor.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import dev.aetex.editor.highlighting.LatexTokenKind

/** The single palette contract for editor content and chrome. */
data class EditorTheme(
    val background: Color,
    val foreground: Color,
    val caret: Color,
    val selection: Color,
    val unfocusedSelection: Color,
    val currentLine: Color,
    val unfocusedCurrentLine: Color,
    val lineNumbers: Color,
    val gutter: Color,
    val matchingBraces: Color,
    val keywords: Color,
    val commands: Color,
    val environments: Color,
    val comments: Color,
    val arguments: Color,
    val strings: Color,
    val mathDelimiters: Color,
    val numbers: Color,
    val braces: Color,
    val escapes: Color,
    val errors: Color
) {
    fun colorFor(kind: LatexTokenKind): Color = when (kind) {
        LatexTokenKind.KEYWORD -> keywords
        LatexTokenKind.COMMAND -> commands
        LatexTokenKind.COMMENT -> comments
        LatexTokenKind.ENVIRONMENT -> environments
        LatexTokenKind.OPTIONAL_ARGUMENT,
        LatexTokenKind.MANDATORY_ARGUMENT -> arguments
        LatexTokenKind.STRING -> strings
        LatexTokenKind.MATH_DELIMITER -> mathDelimiters
        LatexTokenKind.NUMBER -> numbers
        LatexTokenKind.BRACE -> braces
        LatexTokenKind.ESCAPE -> escapes
        LatexTokenKind.PLAIN_TEXT -> foreground
        LatexTokenKind.ERROR -> errors
    }

    fun styleFor(kind: LatexTokenKind): SpanStyle = SpanStyle(color = colorFor(kind))
}

object AeTeXEditorThemes {
    val Dark = EditorTheme(
        background = Color(0xFF1E1F22),
        foreground = Color(0xFFD4D4D4),
        caret = Color(0xFFFFFFFF),
        selection = Color(0x99516F92),
        unfocusedSelection = Color(0x66516F92),
        currentLine = Color(0xFF26282D),
        unfocusedCurrentLine = Color(0xFF222328),
        lineNumbers = Color(0xFF73767C),
        gutter = Color(0xFF1B1C1F),
        matchingBraces = Color(0xFF9FAFCA),
        keywords = Color(0xFFC586C0),
        commands = Color(0xFF7FAACB),
        environments = Color(0xFFB7A07A),
        comments = Color(0xFF6F8068),
        arguments = Color(0xFFCEB98B),
        strings = Color(0xFFB7C991),
        mathDelimiters = Color(0xFFC89B6E),
        numbers = Color(0xFFAAC5A1),
        braces = Color(0xFFAEB2B8),
        escapes = Color(0xFF9BAEC8),
        errors = Color(0xFFE06C75)
    )
}
