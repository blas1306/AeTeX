package dev.aetex.preview.ui

import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.RenderScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewZoomTest {
    private val portrait = PageGeometry(widthPoints = 600f, heightPoints = 800f)

    @Test
    fun `fit width uses available content width after horizontal padding`() {
        val zoom = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(widthDp = 632.0, heightDp = 900.0)
        )

        assertEquals(1.0, zoom.logicalScale)
        assertEquals(100, zoom.displayedPercentage)
    }

    @Test
    fun `fit page uses the smaller available axis after viewport padding`() {
        val widthLimited = resolve(
            PreviewZoomMode.FitPage,
            PreviewViewport(widthDp = 632.0, heightDp = 1_632.0)
        )
        val heightLimited = resolve(
            PreviewZoomMode.FitPage,
            PreviewViewport(widthDp = 1_232.0, heightDp = 832.0)
        )

        assertEquals(1.0, widthLimited.logicalScale)
        assertEquals(1.0, heightLimited.logicalScale)
    }

    @Test
    fun `fixed scale remains unchanged when viewport changes`() {
        val mode = PreviewZoomMode.fixed(1.25)

        val narrow = resolve(mode, PreviewViewport(400.0, 500.0))
        val wide = resolve(mode, PreviewViewport(1_400.0, 1_000.0))

        assertEquals(1.25, narrow.logicalScale)
        assertEquals(narrow.logicalScale, wide.logicalScale)
    }

    @Test
    fun `fit width follows wider and narrower viewports`() {
        val narrow = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(332.0, 900.0)
        )
        val wide = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(932.0, 900.0)
        )

        assertEquals(0.5, narrow.logicalScale)
        assertEquals(1.5, wide.logicalScale)
        assertTrue(wide.logicalScale > narrow.logicalScale)
    }

    @Test
    fun `fit page respects both axes`() {
        val widthRatio = (900.0 - PreviewViewport.DEFAULT_HORIZONTAL_PADDING_DP) / 600.0
        val heightRatio = (700.0 - PreviewViewport.DEFAULT_VERTICAL_PADDING_DP) / 800.0
        val zoom = resolve(
            PreviewZoomMode.FitPage,
            PreviewViewport(900.0, 700.0)
        )

        assertTrue(heightRatio < widthRatio)
        assertEquals(heightRatio, zoom.logicalScale)
    }

    @Test
    fun `rotated landscape page uses displayed geometry`() {
        val rotated = PageGeometry(
            widthPoints = 600f,
            heightPoints = 800f,
            rotationDegrees = 90
        )
        val zoom = PreviewZoom.resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(832.0, 900.0),
            rotated
        )

        assertEquals(800f, rotated.displayedWidthPoints)
        assertEquals(600f, rotated.displayedHeightPoints)
        assertEquals(1.0, zoom?.logicalScale)
    }

    @Test
    fun `invalid viewport measurements do not resolve`() {
        listOf(
            PreviewViewport(Double.NaN, 800.0),
            PreviewViewport(Double.POSITIVE_INFINITY, 800.0),
            PreviewViewport(0.0, 800.0),
            PreviewViewport(20.0, 800.0),
            PreviewViewport(800.0, 20.0)
        ).forEach { viewport ->
            assertNull(
                PreviewZoom.resolve(
                    PreviewZoomMode.FitPage,
                    viewport,
                    portrait
                )
            )
        }
        assertNull(
            PreviewZoom.resolve(
                PreviewZoomMode.FitWidth,
                PreviewViewport(800.0, 800.0),
                portrait,
                displayDensity = Double.NaN
            )
        )
    }

    @Test
    fun `fixed zoom selection clamps to safe raster limits`() {
        assertEquals(
            RenderScale.MIN_MILLI_SCALE,
            PreviewZoomMode.fixed(-10.0).scale.milliScale
        )
        assertEquals(
            RenderScale.MAX_MILLI_SCALE,
            PreviewZoomMode.fixed(10.0).scale.milliScale
        )
    }

    @Test
    fun `plus and minus switch fit modes to fixed`() {
        val plus = PreviewZoom.stepFixed(
            PreviewZoomMode.FitWidth,
            effectiveDisplayScale = 1.0,
            steps = 1
        )
        val minus = PreviewZoom.stepFixed(
            PreviewZoomMode.FitPage,
            effectiveDisplayScale = 1.0,
            steps = -1
        )

        assertIs<PreviewZoomMode.Fixed>(plus)
        assertEquals(1.25f, plus.scale.value)
        assertEquals(0.75f, minus.scale.value)
    }

    @Test
    fun `selecting one hundred percent is fixed one`() {
        val selected = PreviewZoom.fixedPercentage(100)

        assertEquals(PreviewZoomMode.Fixed(RenderScale.DEFAULT), selected)
        assertEquals(1.0f, selected.scale.value)
    }

    @Test
    fun `fit modes retain identity while reporting effective percentage`() {
        val zoom = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(782.0, 900.0)
        )

        assertEquals(PreviewZoomMode.FitWidth, zoom.mode)
        assertEquals(125, zoom.displayedPercentage)
    }

    @Test
    fun `nearby fit scales normalize to one raster cache key`() {
        val first = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(158.0, 300.0),
            PageGeometry(100f, 100f)
        )
        val second = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(160.0, 300.0),
            PageGeometry(100f, 100f)
        )

        assertTrue(first.logicalScale != second.logicalScale)
        assertEquals(first.rasterScale, second.rasterScale)
    }

    @Test
    fun `density changes raster scale without changing display fit`() {
        val viewport = PreviewViewport(632.0, 900.0)
        val normal = resolve(PreviewZoomMode.FitWidth, viewport, density = 1.0)
        val highDpi = resolve(PreviewZoomMode.FitWidth, viewport, density = 2.0)

        assertEquals(normal.logicalScale, highDpi.logicalScale)
        assertEquals(1.0, normal.displayScale)
        assertEquals(2.0, highDpi.displayScale)
        assertEquals(150, normal.rasterScale.percentage)
        assertEquals(300, highDpi.rasterScale.percentage)
    }

    @Test
    fun `fit page responds to remaining height after toolbar layout`() {
        val tallerBody = resolve(
            PreviewZoomMode.FitPage,
            PreviewViewport(1_200.0, 832.0)
        )
        val shorterBody = resolve(
            PreviewZoomMode.FitPage,
            PreviewViewport(1_200.0, 632.0)
        )

        assertEquals(1.0, tallerBody.logicalScale)
        assertEquals(0.75, shorterBody.logicalScale)
    }

    @Test
    fun `very wide fit uses exact logical width while raster remains safely clamped`() {
        val zoom = resolve(
            PreviewZoomMode.FitWidth,
            PreviewViewport(4_832.0, 900.0)
        )

        assertEquals(8.0, zoom.logicalScale)
        assertEquals(8.0, zoom.displayScale)
        assertEquals(RenderScale.MAX_MILLI_SCALE, zoom.rasterScale.milliScale)
    }

    private fun resolve(
        mode: PreviewZoomMode,
        viewport: PreviewViewport,
        page: PageGeometry = portrait,
        density: Double = 1.0
    ): ResolvedPreviewZoom = requireNotNull(
        PreviewZoom.resolve(mode, viewport, page, density)
    )
}
