package dev.aetex.editor.highlighting

/**
 * A tolerant line-state lexer. Edits re-lex changed lines and only propagate
 * while their outgoing lexical state differs from the reusable suffix.
 */
class IncrementalLatexLexer {
    private var text = ""
    private var layout = LineLayout.of("")
    private var lines = listOf(lexLine("", 0, 0, LexerState()))
    private var snapshot = snapshot(relexed = 1, reused = 0)

    fun update(newText: String): LatexHighlightSnapshot {
        if (newText == text) {
            snapshot = snapshot.copy(
                updateStats = HighlightUpdateStats(lines.size, 0, lines.size)
            )
            return snapshot
        }

        val oldText = text
        val oldLayout = layout
        val oldLines = lines
        val newLayout = LineLayout.of(newText)
        val commonPrefix = commonPrefixLines(oldText, oldLayout, newText, newLayout)
        val commonSuffix = commonSuffixLines(
            oldText,
            oldLayout,
            newText,
            newLayout,
            commonPrefix
        )
        val rebuilt = arrayOfNulls<CachedLine>(newLayout.lineCount)
        repeat(commonPrefix) { rebuilt[it] = oldLines[it] }
        var state = rebuilt.getOrNull(commonPrefix - 1)?.outgoing ?: LexerState()
        val newSuffixStart = newLayout.lineCount - commonSuffix
        val oldSuffixStart = oldLayout.lineCount - commonSuffix
        var relexed = 0
        var reused = commonPrefix
        var lineIndex = commonPrefix

        while (lineIndex < newLayout.lineCount) {
            if (lineIndex >= newSuffixStart) {
                val oldIndex = oldSuffixStart + lineIndex - newSuffixStart
                val candidate = oldLines[oldIndex]
                if (candidate.incoming == state) {
                    var newCursor = lineIndex
                    var oldCursor = oldIndex
                    while (newCursor < newLayout.lineCount) {
                        rebuilt[newCursor] = oldLines[oldCursor]
                        newCursor++
                        oldCursor++
                        reused++
                    }
                    break
                }
            }
            val line = lexLine(
                newText,
                newLayout.starts[lineIndex],
                newLayout.ends[lineIndex],
                state
            )
            rebuilt[lineIndex] = line
            state = line.outgoing
            lineIndex++
            relexed++
        }

        text = newText
        layout = newLayout
        lines = rebuilt.map { requireNotNull(it) }
        snapshot = snapshot(relexed, reused)
        return snapshot
    }

    fun current(): LatexHighlightSnapshot = snapshot

    private fun snapshot(relexed: Int, reused: Int): LatexHighlightSnapshot {
        val tokens = ArrayList<LatexToken>(lines.sumOf { it.tokens.size })
        lines.forEachIndexed { lineIndex, line ->
            val lineStart = layout.starts[lineIndex]
            line.tokens.forEach { token ->
                tokens += LatexToken(
                    start = lineStart + token.start,
                    end = lineStart + token.end,
                    kind = token.kind
                )
            }
        }
        return LatexHighlightSnapshot(
            text,
            tokens,
            HighlightUpdateStats(lines.size, relexed, reused)
        )
    }

    private fun commonPrefixLines(
        oldText: String,
        oldLayout: LineLayout,
        newText: String,
        newLayout: LineLayout
    ): Int {
        val maximum = minOf(oldLayout.lineCount, newLayout.lineCount)
        var count = 0
        while (
            count < maximum &&
            lineEquals(oldText, oldLayout, count, newText, newLayout, count)
        ) {
            count++
        }
        return count
    }

    private fun commonSuffixLines(
        oldText: String,
        oldLayout: LineLayout,
        newText: String,
        newLayout: LineLayout,
        prefix: Int
    ): Int {
        val maximum = minOf(oldLayout.lineCount - prefix, newLayout.lineCount - prefix)
        var count = 0
        while (
            count < maximum &&
            lineEquals(
                oldText,
                oldLayout,
                oldLayout.lineCount - count - 1,
                newText,
                newLayout,
                newLayout.lineCount - count - 1
            )
        ) {
            count++
        }
        return count
    }

    private fun lineEquals(
        firstText: String,
        first: LineLayout,
        firstIndex: Int,
        secondText: String,
        second: LineLayout,
        secondIndex: Int
    ): Boolean {
        val firstLength = first.ends[firstIndex] - first.starts[firstIndex]
        val secondLength = second.ends[secondIndex] - second.starts[secondIndex]
        return firstLength == secondLength && firstText.regionMatches(
            first.starts[firstIndex],
            secondText,
            second.starts[secondIndex],
            firstLength
        )
    }

    private data class LineLayout(val starts: IntArray, val ends: IntArray) {
        val lineCount: Int
            get() = starts.size

        companion object {
            fun of(text: String): LineLayout {
                var lineCount = 1
                text.forEach { if (it == '\n') lineCount++ }
                val starts = IntArray(lineCount)
                val ends = IntArray(lineCount)
                var line = 0
                var start = 0
                text.forEachIndexed { index, character ->
                    if (character == '\n') {
                        starts[line] = start
                        ends[line] = if (index > start && text[index - 1] == '\r') index - 1 else index
                        line++
                        start = index + 1
                    }
                }
                starts[line] = start
                ends[line] = text.length
                return LineLayout(starts, ends)
            }
        }
    }

    private data class CachedLine(
        val incoming: LexerState,
        val outgoing: LexerState,
        val tokens: List<LineToken>
    )

    private data class LineToken(val start: Int, val end: Int, val kind: LatexTokenKind)

    private enum class ArgumentContext(val tokenKind: LatexTokenKind) {
        OPTIONAL(LatexTokenKind.OPTIONAL_ARGUMENT),
        MANDATORY(LatexTokenKind.MANDATORY_ARGUMENT),
        ENVIRONMENT(LatexTokenKind.ENVIRONMENT)
    }

    private enum class MathMode {
        NONE,
        SINGLE_DOLLAR,
        DOUBLE_DOLLAR,
        PARENTHESIS,
        BRACKET
    }

    private data class LexerState(
        val arguments: List<ArgumentContext> = emptyList(),
        val mathMode: MathMode = MathMode.NONE,
        val environmentArgumentPending: Boolean = false
    )

    private companion object {
        val KEYWORDS = setOf(
            "documentclass",
            "usepackage",
            "begin",
            "end",
            "include",
            "input",
            "newcommand",
            "renewcommand"
        )

        fun lexLine(source: String, start: Int, end: Int, incoming: LexerState): CachedLine {
            val tokens = ArrayList<LineToken>()
            var index = start
            var state = incoming

            fun add(from: Int, to: Int, kind: LatexTokenKind) {
                if (to > from) tokens += LineToken(from - start, to - start, kind)
            }

            while (index < end) {
                val character = source[index]
                if (character == '%') {
                    add(index, end, LatexTokenKind.COMMENT)
                    index = end
                    continue
                }
                if (character == '\\' && index + 1 < end) {
                    val next = source[index + 1]
                    if (next == '(' || next == ')' || next == '[' || next == ']') {
                        add(index, index + 2, LatexTokenKind.MATH_DELIMITER)
                        state = state.copy(mathMode = nextMathMode(state.mathMode, next))
                        index += 2
                        continue
                    }
                    if (next.isLetter() || next == '@') {
                        var commandEnd = index + 2
                        while (
                            commandEnd < end &&
                            (source[commandEnd].isLetter() || source[commandEnd] == '@')
                        ) {
                            commandEnd++
                        }
                        val command = source.substring(index + 1, commandEnd)
                        add(
                            index,
                            commandEnd,
                            if (command in KEYWORDS) LatexTokenKind.KEYWORD else LatexTokenKind.COMMAND
                        )
                        state = state.copy(
                            environmentArgumentPending = command == "begin" || command == "end"
                        )
                        index = commandEnd
                        continue
                    }
                    add(index, index + 2, LatexTokenKind.ESCAPE)
                    index += 2
                    continue
                }
                if (character == '\\') {
                    add(index, index + 1, LatexTokenKind.ESCAPE)
                    index++
                    continue
                }
                if (character == '$') {
                    val delimiterEnd = if (index + 1 < end && source[index + 1] == '$') {
                        index + 2
                    } else {
                        index + 1
                    }
                    val double = delimiterEnd - index == 2
                    add(index, delimiterEnd, LatexTokenKind.MATH_DELIMITER)
                    state = state.copy(
                        mathMode = nextDollarMode(state.mathMode, double),
                        environmentArgumentPending = false
                    )
                    index = delimiterEnd
                    continue
                }
                if (character == '{' || character == '[') {
                    val context = when {
                        character == '{' && state.environmentArgumentPending ->
                            ArgumentContext.ENVIRONMENT
                        character == '{' -> ArgumentContext.MANDATORY
                        else -> ArgumentContext.OPTIONAL
                    }
                    add(index, index + 1, LatexTokenKind.BRACE)
                    state = state.copy(
                        arguments = state.arguments + context,
                        environmentArgumentPending = false
                    )
                    index++
                    continue
                }
                if (character == '}' || character == ']') {
                    val expected = if (character == '}') {
                        setOf(ArgumentContext.MANDATORY, ArgumentContext.ENVIRONMENT)
                    } else {
                        setOf(ArgumentContext.OPTIONAL)
                    }
                    val matches = state.arguments.lastOrNull() in expected
                    add(
                        index,
                        index + 1,
                        if (matches) LatexTokenKind.BRACE else LatexTokenKind.ERROR
                    )
                    if (matches) state = state.copy(arguments = state.arguments.dropLast(1))
                    index++
                    continue
                }
                if (character == '"') {
                    var stringEnd = index + 1
                    while (stringEnd < end && source[stringEnd] != '"') stringEnd++
                    if (stringEnd < end) stringEnd++
                    add(index, stringEnd, LatexTokenKind.STRING)
                    state = state.copy(environmentArgumentPending = false)
                    index = stringEnd
                    continue
                }
                if (character.isDigit()) {
                    var numberEnd = index + 1
                    while (numberEnd < end && (source[numberEnd].isDigit() || source[numberEnd] == '.')) {
                        numberEnd++
                    }
                    add(index, numberEnd, LatexTokenKind.NUMBER)
                    if (state.environmentArgumentPending) {
                        state = state.copy(environmentArgumentPending = false)
                    }
                    index = numberEnd
                    continue
                }

                val contextualKind = state.arguments.lastOrNull()?.tokenKind
                    ?: LatexTokenKind.PLAIN_TEXT
                var plainEnd = index + 1
                while (plainEnd < end && !isTokenStart(source[plainEnd])) plainEnd++
                add(index, plainEnd, contextualKind)
                if ((index until plainEnd).any { !source[it].isWhitespace() }) {
                    state = state.copy(environmentArgumentPending = false)
                }
                index = plainEnd
            }
            return CachedLine(incoming, state, tokens)
        }

        fun isTokenStart(character: Char): Boolean =
            character == '%' || character == '\\' || character == '$' ||
                character == '{' || character == '}' || character == '[' ||
                character == ']' || character == '"' || character.isDigit()

        fun nextMathMode(current: MathMode, delimiter: Char): MathMode = when (delimiter) {
            '(' -> MathMode.PARENTHESIS
            ')' -> if (current == MathMode.PARENTHESIS) MathMode.NONE else current
            '[' -> MathMode.BRACKET
            ']' -> if (current == MathMode.BRACKET) MathMode.NONE else current
            else -> current
        }

        fun nextDollarMode(current: MathMode, double: Boolean): MathMode {
            val requested = if (double) MathMode.DOUBLE_DOLLAR else MathMode.SINGLE_DOLLAR
            return if (current == requested) MathMode.NONE else if (current == MathMode.NONE) requested else current
        }
    }
}
