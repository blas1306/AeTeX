package dev.aetex.preview.ui

import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.RenderScale
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface PreviewZoomMode {
    data object FitWidth : PreviewZoomMode
    data object FitPage : PreviewZoomMode
    data class Fixed(val scale: RenderScale) : PreviewZoomMode

    companion object {
        val DEFAULT: PreviewZoomMode = FitWidth

        fun fixed(scale: Double): Fixed = Fixed(RenderScale.normalized(scale))
    }
}

/**
 * The measured preview body after its headers, toolbar, and notices have been
 * laid out. Padding values are totals across both edges.
 */
data class PreviewViewport(
    val widthDp: Double,
    val heightDp: Double,
    val horizontalPaddingDp: Double = DEFAULT_HORIZONTAL_PADDING_DP,
    val verticalPaddingDp: Double = DEFAULT_VERTICAL_PADDING_DP
) {
    val availableContentWidthDp: Double
        get() = widthDp - horizontalPaddingDp

    val availableContentHeightDp: Double
        get() = heightDp - verticalPaddingDp

    fun isValid(): Boolean =
        widthDp.isFinite() &&
            heightDp.isFinite() &&
            horizontalPaddingDp.isFinite() &&
            verticalPaddingDp.isFinite() &&
            widthDp > 0.0 &&
            heightDp > 0.0 &&
            horizontalPaddingDp >= 0.0 &&
            verticalPaddingDp >= 0.0 &&
            availableContentWidthDp > 0.0 &&
            availableContentHeightDp > 0.0

    companion object {
        const val DEFAULT_HORIZONTAL_PADDING_DP = 32.0
        const val DEFAULT_VERTICAL_PADDING_DP = 32.0
    }
}

data class ResolvedPreviewZoom(
    val mode: PreviewZoomMode,
    val logicalScale: Double,
    val displayScale: Double,
    val displayDensity: Double,
    val rasterScale: RenderScale
) {
    val displayedPercentage: Int
        get() = (logicalScale * 100.0).roundToInt()

    val effectiveOversamplingFactor: Double
        get() = rasterScale.value.toDouble() / displayScale
}

object PreviewZoom {
    private const val FIXED_ZOOM_STEP = 0.25

    fun resolve(
        mode: PreviewZoomMode,
        viewport: PreviewViewport,
        page: PageGeometry,
        displayDensity: Double = 1.0,
        qualityPolicy: PreviewRasterQualityPolicy = PreviewRasterQualityPolicy.DEFAULT
    ): ResolvedPreviewZoom? {
        if (!viewport.isValid() || !displayDensity.isFinite() || displayDensity <= 0.0) {
            return null
        }
        val pageWidth = page.displayedWidthPoints.toDouble()
        val pageHeight = page.displayedHeightPoints.toDouble()
        if (
            !pageWidth.isFinite() ||
            !pageHeight.isFinite() ||
            pageWidth <= 0.0 ||
            pageHeight <= 0.0
        ) {
            return null
        }
        val rawDisplayScale = when (mode) {
            PreviewZoomMode.FitWidth ->
                viewport.availableContentWidthDp / pageWidth

            PreviewZoomMode.FitPage -> min(
                viewport.availableContentWidthDp / pageWidth,
                viewport.availableContentHeightDp / pageHeight
            )

            is PreviewZoomMode.Fixed -> mode.scale.value.toDouble()
        }
        if (!rawDisplayScale.isFinite() || rawDisplayScale <= 0.0) return null
        val quality = qualityPolicy.resolve(rawDisplayScale, displayDensity) ?: return null
        return ResolvedPreviewZoom(
            mode = mode,
            logicalScale = rawDisplayScale,
            displayScale = quality.displayScale,
            displayDensity = displayDensity,
            rasterScale = quality.rasterScale
        )
    }

    fun stepFixed(
        mode: PreviewZoomMode,
        effectiveDisplayScale: Double?,
        steps: Int
    ): PreviewZoomMode.Fixed {
        val startingScale = when (mode) {
            is PreviewZoomMode.Fixed -> mode.scale.value.toDouble()
            else -> effectiveDisplayScale
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: RenderScale.DEFAULT.value.toDouble()
        }
        return PreviewZoomMode.fixed(startingScale + steps * FIXED_ZOOM_STEP)
    }

    fun fixedPercentage(percentage: Int): PreviewZoomMode.Fixed =
        PreviewZoomMode.fixed(percentage / 100.0)
}
