package dev.aetex.ui.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.aetex.preview.domain.PagePreviewState
import dev.aetex.preview.domain.PreviewDocument
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.domain.RenderedPage
import dev.aetex.preview.ui.ComposePageImage
import dev.aetex.preview.ui.PreviewLayoutLogic
import dev.aetex.preview.ui.VisiblePageMeasurement
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

@Composable
fun PreviewPanel(
    state: PreviewState,
    onViewportChanged: (Set<Int>, Int, RenderScale, Int) -> Unit,
    onRetryPage: (Int) -> Unit
) {
    val presentation = state.presentation()
    var scale by remember { mutableStateOf(RenderScale.DEFAULT) }
    var currentPage by remember { mutableIntStateOf(0) }
    var navigationSequence by remember { mutableIntStateOf(0) }
    var navigationTarget by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .width(480.dp)
            .fillMaxHeight()
            .background(Color(0xFF252526))
    ) {
        PreviewToolbar(
            document = presentation.document,
            currentPage = currentPage,
            scale = scale,
            onScaleChanged = { scale = it },
            onNavigate = { page ->
                navigationTarget = page
                navigationSequence++
            }
        )
        presentation.notice?.let {
            Text(
                text = it,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF6B4F1D))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        val document = presentation.document
        if (document == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(presentation.emptyMessage, color = Color(0xFFCCCCCC))
            }
        } else {
            DocumentPages(
                document = document,
                scale = scale,
                navigationTarget = navigationTarget,
                navigationSequence = navigationSequence,
                onCurrentPageChanged = { currentPage = it },
                onViewportChanged = onViewportChanged,
                onRetryPage = onRetryPage
            )
        }
    }
}

@Composable
private fun PreviewToolbar(
    document: PreviewDocument?,
    currentPage: Int,
    scale: RenderScale,
    onScaleChanged: (RenderScale) -> Unit,
    onNavigate: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D30))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("PDF Preview", color = Color.White, modifier = Modifier.weight(1f))
        if (document != null) {
            TextButton(
                onClick = { onNavigate((currentPage - 1).coerceAtLeast(0)) },
                enabled = currentPage > 0
            ) {
                Text("‹")
            }
            Text(
                "${currentPage.coerceIn(document.metadata.pages.indices) + 1} / " +
                    document.metadata.pageCount,
                color = Color(0xFFDDDDDD)
            )
            TextButton(
                onClick = {
                    onNavigate(
                        (currentPage + 1).coerceAtMost(document.metadata.pageCount - 1)
                    )
                },
                enabled = currentPage < document.metadata.pageCount - 1
            ) {
                Text("›")
            }
            TextButton(onClick = { onScaleChanged(PreviewLayoutLogic.zoom(scale, -1)) }) {
                Text("−")
            }
            Text("${scale.percentage}%", color = Color.White)
            TextButton(onClick = { onScaleChanged(PreviewLayoutLogic.zoom(scale, 1)) }) {
                Text("+")
            }
        }
    }
}

@Composable
private fun DocumentPages(
    document: PreviewDocument,
    scale: RenderScale,
    navigationTarget: Int?,
    navigationSequence: Int,
    onCurrentPageChanged: (Int) -> Unit,
    onViewportChanged: (Set<Int>, Int, RenderScale, Int) -> Unit,
    onRetryPage: (Int) -> Unit
) {
    val verticalState = rememberLazyListState()
    val horizontalState = rememberScrollState()
    val displayDensity = LocalDensity.current.density
    val effectiveScale = RenderScale.normalized((scale.value * displayDensity).toDouble())
    val maximumWidth = remember(document.generationId, scale) {
        PreviewLayoutLogic.safeDisplayExtent(
            document.metadata.maximumDisplayedWidthPoints,
            scale
        )
    }
    val latestViewportCallback by rememberUpdatedState(onViewportChanged)
    val latestCurrentPageCallback by rememberUpdatedState(onCurrentPageChanged)
    var previousFirstVisible by remember(document.generationId) {
        mutableIntStateOf(document.currentPageIndex)
    }

    LaunchedEffect(document.generationId) {
        verticalState.scrollToItem(
            PreviewLayoutLogic.preservePage(
                document.currentPageIndex,
                document.metadata.pageCount
            )
        )
    }

    LaunchedEffect(navigationSequence) {
        navigationTarget?.let {
            verticalState.animateScrollToItem(
                it.coerceIn(document.metadata.pages.indices)
            )
        }
    }

    LaunchedEffect(document.generationId, scale, effectiveScale, verticalState) {
        snapshotFlow {
            val layout = verticalState.layoutInfo
            val measurements = layout.visibleItemsInfo.map {
                VisiblePageMeasurement(it.index, it.offset, it.size)
            }
            val visible = measurements.mapTo(linkedSetOf(), VisiblePageMeasurement::pageIndex)
            val current = PreviewLayoutLogic.currentPage(
                measurements,
                layout.viewportStartOffset,
                layout.viewportEndOffset
            ) ?: document.currentPageIndex
            Triple(visible, current, verticalState.firstVisibleItemIndex)
        }.collectLatest { (visible, current, firstVisible) ->
            val direction = firstVisible.compareTo(previousFirstVisible)
            previousFirstVisible = firstVisible
            latestCurrentPageCallback(current)
            latestViewportCallback(visible, current, effectiveScale, direction)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(horizontalState)
    ) {
        LazyColumn(
            state = verticalState,
            modifier = Modifier
                .width(maximumWidth.dp)
                .fillMaxHeight()
                .background(Color(0xFF3A3A3A)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(document.metadata.pageCount, key = { it }) { pageIndex ->
                val geometry = document.metadata.pages[pageIndex]
                val width =
                    PreviewLayoutLogic.safeDisplayExtent(geometry.displayedWidthPoints, scale)
                val height =
                    PreviewLayoutLogic.safeDisplayExtent(geometry.displayedHeightPoints, scale)
                PageItem(
                    pageIndex = pageIndex,
                    state = document.pageState(pageIndex),
                    width = width,
                    height = height,
                    onRetry = { onRetryPage(pageIndex) }
                )
            }
        }
    }
}

@Composable
private fun PageItem(
    pageIndex: Int,
    state: PagePreviewState,
    width: Float,
    height: Float,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(height.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val displayPage = when (state) {
            is PagePreviewState.Ready -> state.page
            is PagePreviewState.Queued -> state.previous
            is PagePreviewState.Rendering -> state.previous
            else -> null
        }
        displayPage?.let { RenderedPageImage(it) }
        when (state) {
            PagePreviewState.NotRequested -> PagePlaceholder("Page ${pageIndex + 1}")
            is PagePreviewState.Queued -> PagePlaceholder("Queued…")
            is PagePreviewState.Rendering -> PagePlaceholder("Rendering…")
            is PagePreviewState.Ready -> Unit
            is PagePreviewState.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(state.error.message, color = Color(0xFF7A2929))
                if (state.retryable) {
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun BoxScopePageImage(page: RenderedPage) {
    val conversion by produceState<Result<ComposePageImage>?>(
        initialValue = null,
        key1 = page
    ) {
        val ownership = AtomicReference<ComposePageImage?>()
        try {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    ComposePageImage.from(page.raster).also(ownership::set)
                }
            }
            awaitCancellation()
        } finally {
            ownership.getAndSet(null)?.close()
        }
    }
    val composeImage = conversion?.getOrNull()
    if (composeImage == null) {
        Text(
            if (conversion == null) "Preparing page image…"
            else "The rendered page could not be uploaded for display.",
            color = if (conversion == null) Color(0xFF666666) else Color(0xFF7A2929),
            modifier = Modifier.padding(8.dp)
        )
        return
    }
    Image(
        bitmap = composeImage.imageBitmap,
        contentDescription = "Rendered PDF page ${page.key.pageIndex + 1}",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds
    )
}

@Composable
private fun RenderedPageImage(page: RenderedPage) = BoxScopePageImage(page)

@Composable
private fun PagePlaceholder(text: String) {
    Text(
        text = text,
        color = Color(0xFF666666),
        modifier = Modifier
            .background(Color(0xAAFFFFFF))
            .padding(6.dp)
    )
}

private data class PreviewPresentation(
    val document: PreviewDocument?,
    val notice: String?,
    val emptyMessage: String
)

private fun PreviewState.presentation(): PreviewPresentation = when (this) {
    PreviewState.Empty -> PreviewPresentation(null, null, "Build the project to preview its PDF.")
    PreviewState.Closed -> PreviewPresentation(null, null, "PDF preview is closed.")
    is PreviewState.LoadingGeneration -> PreviewPresentation(
        previous,
        if (previous != null) "Loading the new PDF…" else null,
        "Loading generated PDF…"
    )

    is PreviewState.Ready -> PreviewPresentation(
        document,
        when {
            notice != null -> notice.message
            buildInProgress -> "Building… the displayed PDF may be replaced."
            stale -> "The displayed PDF is from the last successful build."
            else -> null
        },
        ""
    )

    is PreviewState.GenerationError -> PreviewPresentation(
        previous,
        error.message,
        error.message
    )
}
