package dev.aetex.preview.ui

import dev.aetex.preview.domain.RenderScale

/**
 * A bounded sampling policy for PDF text. Logical scale is measured in dp per
 * PDF point, display scale in physical pixels per PDF point, and raster scale
 * in bitmap pixels per PDF point as passed to PDFBox.
 */
data class PreviewRasterQualityPolicy(
    val oversamplingFactor: Double = DEFAULT_OVERSAMPLING_FACTOR
) {
    init {
        require(oversamplingFactor.isFinite())
        require(oversamplingFactor in 1.0..MAXIMUM_OVERSAMPLING_FACTOR)
    }

    fun resolve(logicalScale: Double, displayDensity: Double): RasterQualityResolution? {
        if (
            !logicalScale.isFinite() ||
            !displayDensity.isFinite() ||
            logicalScale <= 0.0 ||
            displayDensity <= 0.0
        ) {
            return null
        }
        val displayScale = logicalScale * displayDensity
        if (!displayScale.isFinite() || displayScale <= 0.0) return null
        val requestedRasterScale = displayScale * oversamplingFactor
        if (!requestedRasterScale.isFinite()) return null
        val rasterScale = RenderScale.normalizedRasterRequest(requestedRasterScale)
        return RasterQualityResolution(
            logicalScale = logicalScale,
            displayDensity = displayDensity,
            displayScale = displayScale,
            requestedOversamplingFactor = oversamplingFactor,
            rasterScale = rasterScale
        )
    }

    companion object {
        const val DEFAULT_OVERSAMPLING_FACTOR = 1.5
        const val MAXIMUM_OVERSAMPLING_FACTOR = 2.0
        val DEFAULT = PreviewRasterQualityPolicy()
    }
}

data class RasterQualityResolution(
    val logicalScale: Double,
    val displayDensity: Double,
    val displayScale: Double,
    val requestedOversamplingFactor: Double,
    val rasterScale: RenderScale
) {
    val effectiveOversamplingFactor: Double
        get() = rasterScale.value.toDouble() / displayScale

    val composeDisplayRatio: Double
        get() = displayScale / rasterScale.value.toDouble()
}
