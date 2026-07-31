package dev.aetex.editor

/** UTF-16 logical-line indexing shared by editor selection and gutter rendering. */
class LogicalLineIndex private constructor(
    val starts: IntArray,
    private val contentEnds: IntArray
) {
    val lineCount: Int
        get() = starts.size

    fun lineForOffset(offset: Int, textLength: Int): Int {
        val canonical = offset.coerceIn(0, textLength)
        val found = starts.binarySearch(canonical)
        if (found >= 0) return found
        return (-found - 2).coerceAtLeast(0)
    }

    fun contentEnd(line: Int): Int = contentEnds[line]

    companion object {
        fun of(text: String): LogicalLineIndex {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            starts += 0
            text.forEachIndexed { index, character ->
                if (character == '\n') {
                    ends += if (index > 0 && text[index - 1] == '\r') index - 1 else index
                    starts += index + 1
                }
            }
            ends += text.length
            return LogicalLineIndex(starts.toIntArray(), ends.toIntArray())
        }
    }
}

data class EditorLineGeometry(
    val logicalLine: Int,
    val firstVisualLine: Int,
    val lastVisualLine: Int,
    val baseline: Float,
    val top: Float,
    val bottom: Float
)

interface EditorTextLayout {
    val text: String
    fun visualLineForOffset(offset: Int): Int
    fun lineTop(line: Int): Float
    fun lineBottom(line: Int): Float
    fun lineBaseline(line: Int): Float
}

fun editorLineGeometry(
    text: String,
    lines: LogicalLineIndex,
    layout: EditorTextLayout,
    logicalLine: Int
): EditorLineGeometry {
    require(layout.text == text) { "Editor geometry must use the canonical text revision." }
    require(logicalLine in 0 until lines.lineCount)
    val start = lines.starts[logicalLine]
    val end = lines.contentEnd(logicalLine)
    val firstVisual = layout.visualLineForOffset(start)
    val lastVisual = layout.visualLineForOffset(if (end > start) end - 1 else start)
    return EditorLineGeometry(
        logicalLine = logicalLine,
        firstVisualLine = firstVisual,
        lastVisualLine = lastVisual,
        baseline = layout.lineBaseline(firstVisual),
        top = layout.lineTop(firstVisual),
        bottom = layout.lineBottom(lastVisual)
    )
}

fun acceptsEditorLayout(canonicalText: String, laidOutText: String): Boolean =
    canonicalText == laidOutText

fun lineNumberDigitCount(lineCount: Int): Int =
    lineCount.coerceAtLeast(1).toString().length.coerceAtLeast(2)

fun shouldPublishEditorTextChange(updatedText: String, canonicalText: String): Boolean =
    updatedText != canonicalText
