package dev.aetex.preview.ui

import dev.aetex.preview.domain.RenderScale
import kotlin.math.max
import kotlin.math.min

data class VisiblePageMeasurement(
    val pageIndex: Int,
    val offset: Int,
    val size: Int
)

object PreviewLayoutLogic {
    private const val MAXIMUM_LAYOUT_EXTENT = 100_000f

    fun currentPage(
        visiblePages: List<VisiblePageMeasurement>,
        viewportStart: Int,
        viewportEnd: Int
    ): Int? = visiblePages
        .filter { it.size > 0 }
        .maxWithOrNull(
            compareBy<VisiblePageMeasurement>(
                {
                    val visibleStart = max(
                        it.offset.toLong(),
                        viewportStart.toLong()
                    )
                    val visibleEnd = min(
                        it.offset.toLong() + it.size.toLong(),
                        viewportEnd.toLong()
                    )
                    (visibleEnd - visibleStart).coerceAtLeast(0L).toDouble() / it.size
                },
                { -it.pageIndex }
            )
        )
        ?.pageIndex

    fun preservePage(oldPage: Int, newPageCount: Int): Int =
        if (newPageCount <= 0) 0 else oldPage.coerceIn(0, newPageCount - 1)

    fun zoom(current: RenderScale, steps: Int): RenderScale =
        RenderScale.normalized(current.value + steps * 0.25)

    fun safeDisplayExtent(points: Float, scale: RenderScale): Float {
        return safeDisplayExtent(points, scale.value.toDouble())
    }

    fun safeDisplayExtent(points: Float, scale: Double): Float {
        if (!points.isFinite() || points <= 0f) return 1f
        if (!scale.isFinite() || scale <= 0.0) return 1f
        val extent = points.toDouble() * scale
        return if (extent.isFinite()) {
            extent.coerceIn(1.0, MAXIMUM_LAYOUT_EXTENT.toDouble()).toFloat()
        } else {
            MAXIMUM_LAYOUT_EXTENT
        }
    }
}
