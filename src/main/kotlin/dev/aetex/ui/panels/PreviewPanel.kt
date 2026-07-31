package dev.aetex.ui.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aetex.preview.domain.PagePreviewState
import dev.aetex.preview.domain.PreviewDocument
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.domain.RenderedPage
import dev.aetex.preview.ui.ComposePageImage
import dev.aetex.preview.ui.PreviewLayoutLogic
import dev.aetex.preview.ui.PreviewViewport
import dev.aetex.preview.ui.PreviewZoom
import dev.aetex.preview.ui.PreviewZoomMode
import dev.aetex.preview.ui.ResolvedPreviewZoom
import dev.aetex.preview.ui.VisiblePageMeasurement
import dev.aetex.ui.IdeTooltip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class PreviewPanelViewState {
    var zoomMode by mutableStateOf<PreviewZoomMode>(PreviewZoomMode.DEFAULT)
    var effectiveDisplayScale by mutableStateOf<Double?>(null)
    var currentPage by mutableIntStateOf(0)
    var navigationSequence by mutableIntStateOf(0)
    var navigationTarget by mutableStateOf<Int?>(null)

    fun navigateTo(pageIndex: Int) {
        navigationTarget = pageIndex
        navigationSequence++
    }
}

@Composable
fun rememberPreviewPanelViewState(): PreviewPanelViewState =
    remember { PreviewPanelViewState() }

@Composable
fun PreviewPanel(
    state: PreviewState,
    onViewportChanged: (Set<Int>, Int, RenderScale, Int) -> Unit,
    onRetryPage: (Int) -> Unit,
    viewState: PreviewPanelViewState,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presentation = state.presentation()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFF252526))
    ) {
        PreviewHeader(onCollapse = onCollapse)
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
            PreviewToolbar(
                document = document,
                currentPage = viewState.currentPage,
                zoomMode = viewState.zoomMode,
                effectiveDisplayScale = viewState.effectiveDisplayScale,
                onZoomModeChanged = { viewState.zoomMode = it },
                onNavigate = viewState::navigateTo
            )
            DocumentPages(
                document = document,
                zoomMode = viewState.zoomMode,
                currentPage = viewState.currentPage,
                navigationTarget = viewState.navigationTarget,
                navigationSequence = viewState.navigationSequence,
                onCurrentPageChanged = { viewState.currentPage = it },
                onEffectiveZoomChanged = {
                    viewState.effectiveDisplayScale = it.logicalScale
                },
                onViewportChanged = onViewportChanged,
                onRetryPage = onRetryPage
            )
        }
    }
}

@Composable
private fun PreviewHeader(onCollapse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D30))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("PDF Preview", color = Color.White, modifier = Modifier.weight(1f))
        IdeTooltip("Close PDF Preview") {
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.semantics {
                    contentDescription = "Close PDF Preview panel"
                }
            ) {
                Text("›", color = Color(0xFFD8D8D8))
            }
        }
    }
}

@Composable
private fun PreviewToolbar(
    document: PreviewDocument,
    currentPage: Int,
    zoomMode: PreviewZoomMode,
    effectiveDisplayScale: Double?,
    onZoomModeChanged: (PreviewZoomMode) -> Unit,
    onNavigate: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D30))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
        IdeTooltip("Zoom out") {
            TextButton(
                onClick = {
                    onZoomModeChanged(
                        PreviewZoom.stepFixed(zoomMode, effectiveDisplayScale, -1)
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Zoom out"
                }
            ) {
                Text("−")
            }
        }
        ZoomSelector(
            mode = zoomMode,
            effectiveDisplayScale = effectiveDisplayScale,
            onModeChanged = onZoomModeChanged
        )
        IdeTooltip("Zoom in") {
            TextButton(
                onClick = {
                    onZoomModeChanged(
                        PreviewZoom.stepFixed(zoomMode, effectiveDisplayScale, 1)
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Zoom in"
                }
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun ZoomSelector(
    mode: PreviewZoomMode,
    effectiveDisplayScale: Double?,
    onModeChanged: (PreviewZoomMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val percentage = when (mode) {
        is PreviewZoomMode.Fixed -> mode.scale.percentage
        else -> effectiveDisplayScale
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 100.0).roundToInt() }
            ?: RenderScale.DEFAULT.percentage
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics {
                contentDescription = "PDF zoom mode and scale"
            }
        ) {
            Text(
                when (mode) {
                    PreviewZoomMode.FitWidth -> "Fit Width · $percentage%"
                    PreviewZoomMode.FitPage -> "Fit Page · $percentage%"
                    is PreviewZoomMode.Fixed -> "$percentage%"
                }
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(
                "Fit Width" to PreviewZoomMode.FitWidth,
                "Fit Page" to PreviewZoomMode.FitPage
            ).forEach { (label, selection) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeChanged(selection)
                        expanded = false
                    }
                )
            }
            listOf(50, 75, 100, 125, 150, 200).forEach { fixedPercentage ->
                DropdownMenuItem(
                    text = { Text("$fixedPercentage%") },
                    onClick = {
                        onModeChanged(PreviewZoom.fixedPercentage(fixedPercentage))
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DocumentPages(
    document: PreviewDocument,
    zoomMode: PreviewZoomMode,
    currentPage: Int,
    navigationTarget: Int?,
    navigationSequence: Int,
    onCurrentPageChanged: (Int) -> Unit,
    onEffectiveZoomChanged: (ResolvedPreviewZoom) -> Unit,
    onViewportChanged: (Set<Int>, Int, RenderScale, Int) -> Unit,
    onRetryPage: (Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val displayDensity = LocalDensity.current.density.toDouble()
        val pageIndex = currentPage.coerceIn(document.metadata.pages.indices)
        val viewport = PreviewViewport(
            widthDp = maxWidth.value.toDouble(),
            heightDp = maxHeight.value.toDouble()
        )
        val resolvedZoom = remember(
            document.generationId,
            zoomMode,
            pageIndex,
            viewport,
            displayDensity
        ) {
            PreviewZoom.resolve(
                mode = zoomMode,
                viewport = viewport,
                page = document.metadata.pages[pageIndex],
                displayDensity = displayDensity
            )
        }
        val logicalScale = resolvedZoom?.logicalScale
            ?: (zoomMode as? PreviewZoomMode.Fixed)?.scale?.value?.toDouble()
            ?: RenderScale.DEFAULT.value.toDouble()
        val rasterScale = resolvedZoom?.rasterScale ?: RenderScale.DEFAULT
        val maximumWidth = remember(document.generationId, logicalScale, viewport.widthDp) {
            val safeViewportWidth = viewport.widthDp
                .takeIf { it.isFinite() && it > 0.0 }
                ?.coerceAtMost(100_000.0)
                ?.toFloat()
                ?: 1f
            maxOf(
                safeViewportWidth,
                PreviewLayoutLogic.safeDisplayExtent(
                    document.metadata.maximumDisplayedWidthPoints,
                    logicalScale
                ) + PreviewViewport.DEFAULT_HORIZONTAL_PADDING_DP.toFloat()
            )
        }
        LaunchedEffect(resolvedZoom) {
            resolvedZoom?.let(onEffectiveZoomChanged)
        }
        DocumentPageList(
            document = document,
            logicalScale = logicalScale,
            rasterScale = rasterScale,
            maximumWidth = maximumWidth,
            recenterHorizontally = zoomMode !is PreviewZoomMode.Fixed,
            navigationTarget = navigationTarget,
            navigationSequence = navigationSequence,
            onCurrentPageChanged = onCurrentPageChanged,
            onViewportChanged = onViewportChanged,
            onRetryPage = onRetryPage
        )
    }
}

@Composable
private fun DocumentPageList(
    document: PreviewDocument,
    logicalScale: Double,
    rasterScale: RenderScale,
    maximumWidth: Float,
    recenterHorizontally: Boolean,
    navigationTarget: Int?,
    navigationSequence: Int,
    onCurrentPageChanged: (Int) -> Unit,
    onViewportChanged: (Set<Int>, Int, RenderScale, Int) -> Unit,
    onRetryPage: (Int) -> Unit
) {
    val verticalState = rememberLazyListState()
    val horizontalState = rememberScrollState()
    val latestViewportCallback by rememberUpdatedState(onViewportChanged)
    val latestCurrentPageCallback by rememberUpdatedState(onCurrentPageChanged)
    var previousFirstVisible by remember(document.generationId) {
        mutableIntStateOf(document.currentPageIndex)
    }

    LaunchedEffect(document.generationId, maximumWidth, recenterHorizontally) {
        if (recenterHorizontally) {
            snapshotFlow { horizontalState.maxValue }
                .collectLatest { maximumScroll ->
                    horizontalState.scrollTo(maximumScroll / 2)
                }
        }
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

    LaunchedEffect(document.generationId, rasterScale, verticalState) {
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
            latestViewportCallback(visible, current, rasterScale, direction)
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
                    PreviewLayoutLogic.safeDisplayExtent(
                        geometry.displayedWidthPoints,
                        logicalScale
                    )
                val height =
                    PreviewLayoutLogic.safeDisplayExtent(
                        geometry.displayedHeightPoints,
                        logicalScale
                    )
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
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High
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
