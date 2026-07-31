package dev.aetex.editor.highlighting

enum class LatexTokenKind {
    KEYWORD,
    COMMAND,
    COMMENT,
    ENVIRONMENT,
    OPTIONAL_ARGUMENT,
    MANDATORY_ARGUMENT,
    STRING,
    MATH_DELIMITER,
    NUMBER,
    BRACE,
    ESCAPE,
    PLAIN_TEXT,
    ERROR
}

data class LatexToken(
    val start: Int,
    val end: Int,
    val kind: LatexTokenKind
) {
    init {
        require(start >= 0)
        require(end > start)
    }
}

data class HighlightUpdateStats(
    val totalLineCount: Int,
    val relexedLineCount: Int,
    val reusedLineCount: Int
)

data class LatexHighlightSnapshot(
    val text: String,
    val tokens: List<LatexToken>,
    val updateStats: HighlightUpdateStats
)
