package dev.aetex.preview.ui

import dev.aetex.preview.domain.RenderScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewRasterQualityTest {
    private val policy = PreviewRasterQualityPolicy.DEFAULT

    @Test
    fun `normal density uses bounded quality oversampling`() {
        val result = requireNotNull(policy.resolve(logicalScale = 1.0, displayDensity = 1.0))

        assertEquals(1.0, result.logicalScale)
        assertEquals(1.0, result.displayScale)
        assertEquals(1.5f, result.rasterScale.value)
        assertEquals(1.5, result.effectiveOversamplingFactor)
    }

    @Test
    fun `fractional desktop density participates in physical display scale`() {
        val result = requireNotNull(policy.resolve(logicalScale = 0.8, displayDensity = 1.25))

        assertEquals(1.0, result.displayScale)
        assertEquals(1.5f, result.rasterScale.value)
    }

    @Test
    fun `retina density gets oversampled raster without changing logical zoom`() {
        val result = requireNotNull(policy.resolve(logicalScale = 1.0, displayDensity = 2.0))

        assertEquals(1.0, result.logicalScale)
        assertEquals(2.0, result.displayScale)
        assertEquals(3.0f, result.rasterScale.value)
        assertTrue(result.composeDisplayRatio < 1.0)
    }

    @Test
    fun `raster normalization rounds quality target upward into cache bucket`() {
        val result = requireNotNull(policy.resolve(logicalScale = 0.67, displayDensity = 1.0))

        assertEquals(1.25f, result.rasterScale.value)
        assertTrue(result.rasterScale.value >= 0.67f * 1.5f)
        assertEquals(
            result.rasterScale,
            requireNotNull(policy.resolve(0.70, 1.0)).rasterScale
        )
    }

    @Test
    fun `quality policy preserves global raster bound`() {
        val result = requireNotNull(policy.resolve(logicalScale = 200.0, displayDensity = 4.0))

        assertEquals(RenderScale.MAX_MILLI_SCALE, result.rasterScale.milliScale)
        assertTrue(result.rasterScale.value <= 4.0f)
    }

    @Test
    fun `invalid logical and density inputs are rejected deterministically`() {
        assertNull(policy.resolve(0.0, 1.0))
        assertNull(policy.resolve(1.0, 0.0))
        assertNull(policy.resolve(Double.NaN, 1.0))
        assertNull(policy.resolve(1.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `oversampling factor itself is explicitly bounded`() {
        val maximum = PreviewRasterQualityPolicy(
            PreviewRasterQualityPolicy.MAXIMUM_OVERSAMPLING_FACTOR
        )
        val result = requireNotNull(maximum.resolve(1.0, 1.0))

        assertEquals(2.0f, result.rasterScale.value)
    }
}
