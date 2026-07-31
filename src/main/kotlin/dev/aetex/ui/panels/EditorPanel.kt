package dev.aetex.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import dev.aetex.editor.EditorTextLayout
import dev.aetex.editor.EditorCaretBounds
import dev.aetex.editor.EditorCaretLayout
import dev.aetex.editor.EditorCaretViewportCoordinator
import dev.aetex.editor.EditorViewport
import dev.aetex.editor.LogicalLineIndex
import dev.aetex.editor.acceptsEditorLayout
import dev.aetex.editor.editorLineGeometry
import dev.aetex.editor.highlighting.IncrementalLatexLexer
import dev.aetex.editor.highlighting.LatexVisualTransformation
import dev.aetex.editor.OpenDocument
import dev.aetex.editor.lineNumberDigitCount
import dev.aetex.editor.shouldPublishEditorTextChange
import dev.aetex.editor.theme.AeTeXEditorThemes
import dev.aetex.editor.theme.EditorTheme
import java.nio.file.Path

@Composable
fun EditorPanel(
    documents: List<OpenDocument>,
    activeDocument: OpenDocument?,
    onDocumentActivated: (Path) -> Unit,
    onDocumentChanged: (Path, String) -> Unit,
    onDocumentCloseRequested: (Path) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = AeTeXEditorThemes.Dark
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(theme.background)
    ) {
        DocumentTabs(
            documents = documents,
            activeDocument = activeDocument,
            onDocumentActivated = onDocumentActivated,
            onDocumentCloseRequested = onDocumentCloseRequested,
            theme = theme
        )

        if (activeDocument == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Select an editable file from the project.",
                    color = theme.lineNumbers
                )
            }
        } else {
            activeDocument.error?.let { error ->
                Text(
                    text = error.userMessage,
                    color = theme.caret,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.errors.copy(alpha = 0.45f))
                        .padding(8.dp)
                )
            }

            key(activeDocument.path) {
                EditorTextField(activeDocument, onDocumentChanged, theme)
            }
        }
    }
}

@Composable
private fun DocumentTabs(
    documents: List<OpenDocument>,
    activeDocument: OpenDocument?,
    onDocumentActivated: (Path) -> Unit,
    onDocumentCloseRequested: (Path) -> Unit,
    theme: EditorTheme
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.gutter)
            .horizontalScroll(rememberScrollState())
    ) {
        documents.forEach { document ->
            val active = activeDocument?.path == document.path
            Row(
                modifier = Modifier
                    .background(if (active) theme.background else theme.currentLine)
                    .clickable { onDocumentActivated(document.path) }
                    .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = document.path.fileName.toString() +
                        if (document.isModified) " *" else "",
                    color = if (active) theme.foreground else theme.lineNumbers,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onDocumentCloseRequested(document.path) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = theme.foreground)
                }
            }
        }
    }
}

@Composable
private fun EditorTextField(
    document: OpenDocument,
    onDocumentChanged: (Path, String) -> Unit,
    theme: EditorTheme
) {
    var value by remember(document.path) { mutableStateOf(TextFieldValue(document.content)) }
    var focused by remember(document.path) { mutableStateOf(false) }
    var layoutSnapshot by remember(document.path) { mutableStateOf<EditorLayoutSnapshot?>(null) }
    var documentRevision by remember(document.path) { mutableLongStateOf(0L) }
    var textRevision by remember(document.path) { mutableLongStateOf(0L) }
    var coordinatorRevision by remember(document.path) { mutableLongStateOf(0L) }
    var viewportHeight by remember(document.path) { mutableIntStateOf(0) }
    val verticalScroll = rememberScrollState()
    val caretViewportCoordinator = remember(document.path) {
        EditorCaretViewportCoordinator().apply {
            activateDocument(
                documentRevision = 0L,
                textRevision = 0L,
                text = document.content,
                selectionStart = value.selection.start,
                selectionEnd = value.selection.end
            )
        }
    }
    val manualScrollObserver = remember(caretViewportCoordinator) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (
                    source == NestedScrollSource.UserInput &&
                    available.y != 0f &&
                    caretViewportCoordinator.onManualViewportChange()
                ) {
                    coordinatorRevision++
                }
                return Offset.Zero
            }
        }
    }
    val lexer = remember(document.path) { IncrementalLatexLexer() }
    val transformation = remember(document.path, theme) {
        LatexVisualTransformation(lexer, theme)
    }
    val selectionColors = remember(theme, focused) {
        TextSelectionColors(
            handleColor = theme.caret,
            backgroundColor = if (focused) theme.selection else theme.unfocusedSelection
        )
    }

    LaunchedEffect(document.content) {
        if (value.text != document.content) {
            layoutSnapshot = null
            documentRevision++
            textRevision++
            value = value.copy(
                text = document.content,
                selection = TextRange(
                    value.selection.start.coerceIn(0, document.content.length),
                    value.selection.end.coerceIn(0, document.content.length)
                ),
                composition = null
            )
            caretViewportCoordinator.activateDocument(
                documentRevision = documentRevision,
                textRevision = textRevision,
                text = value.text,
                selectionStart = value.selection.start,
                selectionEnd = value.selection.end
            )
            coordinatorRevision++
        }
    }

    val logicalLines = remember(value.text) { LogicalLineIndex.of(value.text) }
    val currentLogicalLine = logicalLines.lineForOffset(
        value.selection.end,
        value.text.length
    )
    val layout = layoutSnapshot
        ?.takeIf {
            it.documentRevision == documentRevision &&
                it.textRevision == textRevision &&
                it.text == value.text
        }
        ?.layout
    val layoutAdapter = remember(layout) { layout?.let(::ComposeEditorTextLayout) }
    val geometries = remember(value.text, logicalLines, layoutAdapter) {
        layoutAdapter?.let { measured ->
            List(logicalLines.lineCount) { line ->
                editorLineGeometry(value.text, logicalLines, measured, line)
            }
        }.orEmpty()
    }
    val textStyle = remember(theme) {
        TextStyle(
            color = theme.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
    }
    val lineNumberStyle = remember(theme) {
        TextStyle(
            color = theme.lineNumbers,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digitCount = lineNumberDigitCount(logicalLines.lineCount)
    val digitWidth = remember(textMeasurer, lineNumberStyle) {
        textMeasurer.measure("0", style = lineNumberStyle).size.width.toFloat()
    }
    val gutterHorizontalPadding = with(density) { 10.dp.toPx() }
    val gutterWidthPx = digitWidth * digitCount + gutterHorizontalPadding * 2
    val gutterWidth = with(density) { gutterWidthPx.toDp() }
    val editorTopPadding = with(density) { 12.dp.toPx() }
    val caretRevealMargin = with(density) { 8.dp.toPx() }
    val measuredNumbers = remember(logicalLines.lineCount, lineNumberStyle, textMeasurer) {
        List(logicalLines.lineCount) { line ->
            textMeasurer.measure((line + 1).toString(), style = lineNumberStyle)
        }
    }
    // Capture immutable revision values in this callback. An onTextLayout from a
    // superseded composition must retain its old identity and be rejected below.
    val layoutDocumentRevision = documentRevision
    val layoutTextRevision = textRevision
    val layoutCanonicalText = value.text

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewportHeight = it.height }
                .nestedScroll(manualScrollObserver)
                .verticalScroll(verticalScroll)
        ) {
            BasicTextField(
                value = value,
                onValueChange = { updated ->
                    val textChanged = updated.text != value.text
                    val selectionChanged = updated.selection != value.selection
                    if (!textChanged && !selectionChanged && updated.composition == value.composition) {
                        return@BasicTextField
                    }
                    if (textChanged) {
                        layoutSnapshot = null
                        textRevision++
                    }
                    value = updated
                    if (textChanged || selectionChanged) {
                        if (
                            caretViewportCoordinator.onEditorAction(
                                documentRevision = documentRevision,
                                textRevision = textRevision,
                                text = updated.text,
                                selectionStart = updated.selection.start,
                                selectionEnd = updated.selection.end
                            )
                        ) {
                            coordinatorRevision++
                        }
                    }
                    if (shouldPublishEditorTextChange(updated.text, document.content)) {
                        onDocumentChanged(document.path, updated.text)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .drawBehind {
                        geometries.getOrNull(currentLogicalLine)?.let { current ->
                            drawRect(
                                color = if (focused) theme.currentLine else theme.unfocusedCurrentLine,
                                topLeft = Offset(0f, current.top + editorTopPadding),
                                size = Size(
                                    size.width,
                                    (current.bottom - current.top).coerceAtLeast(1f)
                                )
                            )
                        }
                        drawRect(
                            color = theme.gutter,
                            size = Size(gutterWidthPx, size.height)
                        )
                        geometries.forEachIndexed { line, geometry ->
                            val measured = measuredNumbers[line]
                            drawText(
                                textLayoutResult = measured,
                                color = if (line == currentLogicalLine) theme.foreground else theme.lineNumbers,
                                topLeft = Offset(
                                    gutterWidthPx - gutterHorizontalPadding - measured.size.width,
                                    geometry.baseline + editorTopPadding - measured.firstBaseline
                                )
                            )
                        }
                    }
                    .padding(
                        start = gutterWidth + 12.dp,
                        top = 12.dp,
                        end = 20.dp,
                        bottom = 12.dp
                    ),
                textStyle = textStyle,
                cursorBrush = SolidColor(theme.caret),
                visualTransformation = transformation,
                onTextLayout = { candidate ->
                    val laidOutText = candidate.layoutInput.text.text
                    if (acceptsEditorLayout(layoutCanonicalText, laidOutText)) {
                        layoutSnapshot = EditorLayoutSnapshot(
                            documentRevision = layoutDocumentRevision,
                            textRevision = layoutTextRevision,
                            text = laidOutText,
                            layout = candidate
                        )
                        coordinatorRevision++
                    }
                }
            )
        }
    }

    val matchingLayout = layoutSnapshot?.takeIf {
        it.documentRevision == documentRevision &&
            it.textRevision == textRevision &&
            it.text == value.text
    }
    LaunchedEffect(
        coordinatorRevision,
        matchingLayout,
        viewportHeight,
        verticalScroll.maxValue
    ) {
        val snapshot = matchingLayout ?: return@LaunchedEffect
        val reveal = caretViewportCoordinator.resolve(
            layout = ComposeEditorCaretLayout(snapshot),
            viewport = EditorViewport(
                scroll = verticalScroll.value,
                maximumScroll = verticalScroll.maxValue,
                height = viewportHeight,
                textTopPadding = editorTopPadding,
                revealMargin = caretRevealMargin
            )
        ) ?: return@LaunchedEffect
        if (!caretViewportCoordinator.isCurrent(reveal)) return@LaunchedEffect
        if (reveal.requiresScroll) verticalScroll.scrollTo(reveal.targetScroll)
        caretViewportCoordinator.complete(reveal)
    }
}

private data class EditorLayoutSnapshot(
    val documentRevision: Long,
    val textRevision: Long,
    val text: String,
    val layout: TextLayoutResult
)

private class ComposeEditorCaretLayout(
    snapshot: EditorLayoutSnapshot
) : EditorCaretLayout {
    private val delegate = snapshot.layout
    override val documentRevision: Long = snapshot.documentRevision
    override val textRevision: Long = snapshot.textRevision
    override val text: String = snapshot.text

    override fun caretBounds(offset: Int): EditorCaretBounds {
        val canonicalOffset = offset.coerceIn(0, text.length)
        runCatching { delegate.getCursorRect(canonicalOffset) }.getOrNull()?.let { cursor ->
            if (
                cursor.top.isFinite() &&
                cursor.bottom.isFinite() &&
                cursor.bottom > cursor.top
            ) {
                return EditorCaretBounds(cursor.top, cursor.bottom)
            }
        }
        val visualLine = delegate.getLineForOffset(canonicalOffset)
        return EditorCaretBounds(
            top = delegate.getLineTop(visualLine),
            bottom = delegate.getLineBottom(visualLine)
        )
    }
}

private class ComposeEditorTextLayout(
    private val delegate: TextLayoutResult
) : EditorTextLayout {
    override val text: String = delegate.layoutInput.text.text

    override fun visualLineForOffset(offset: Int): Int = delegate.getLineForOffset(offset)
    override fun lineTop(line: Int): Float = delegate.getLineTop(line)
    override fun lineBottom(line: Int): Float = delegate.getLineBottom(line)
    override fun lineBaseline(line: Int): Float = delegate.getLineBaseline(line)
}
