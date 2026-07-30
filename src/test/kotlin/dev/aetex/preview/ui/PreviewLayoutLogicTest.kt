package dev.aetex.preview.ui

import dev.aetex.preview.coordination.PreviewManager
import dev.aetex.preview.domain.RenderScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewLayoutLogicTest {
    @Test
    fun `selects page with greatest visible fraction`() {
        val current = PreviewLayoutLogic.currentPage(
            listOf(
                VisiblePageMeasurement(2, -80, 100),
                VisiblePageMeasurement(3, 20, 100)
            ),
            0,
            100
        )

        assertEquals(3, current)
    }

    @Test
    fun `current page calculation is stable at equal visibility`() {
        val current = PreviewLayoutLogic.currentPage(
            listOf(
                VisiblePageMeasurement(4, -50, 100),
                VisiblePageMeasurement(5, 50, 100)
            ),
            0,
            100
        )

        assertEquals(4, current)
    }

    @Test
    fun `neighbor selection clamps to document edges and does not recurse`() {
        assertEquals(
            setOf(1),
            PreviewManager.neighborPages(setOf(0), pageCount = 3, radius = 1)
        )
        assertEquals(
            setOf(0, 3),
            PreviewManager.neighborPages(setOf(1, 2), pageCount = 4, radius = 1)
        )
    }

    @Test
    fun `position preservation clamps page when new document is shorter`() {
        assertEquals(2, PreviewLayoutLogic.preservePage(8, 3))
        assertEquals(0, PreviewLayoutLogic.preservePage(8, 0))
    }

    @Test
    fun `zoom supports required buckets and clamps safety limits`() {
        val levels = listOf(1.0, 1.25, 1.5, 1.75, 2.0).map(RenderScale::normalized)

        assertEquals(listOf(100, 125, 150, 175, 200), levels.map(RenderScale::percentage))
        assertEquals(50, RenderScale.normalized(-10.0).percentage)
        assertEquals(400, RenderScale.normalized(20.0).percentage)
        assertTrue(levels.toSet().size == levels.size)
    }

    @Test
    fun `nearby continuous zoom inputs share stable normalized keys`() {
        assertEquals(RenderScale.normalized(1.26), RenderScale.normalized(1.34))
    }

    @Test
    fun `scale normalization rejects non-finite values and clamps extremes`() {
        assertFailsWith<IllegalArgumentException> { RenderScale.normalized(Double.NaN) }
        assertFailsWith<IllegalArgumentException> {
            RenderScale.normalized(Double.POSITIVE_INFINITY)
        }
        assertEquals(RenderScale.normalized(0.5), RenderScale.normalized(-10.0))
        assertEquals(RenderScale.normalized(4.0), RenderScale.normalized(Double.MAX_VALUE))
    }

    @Test
    fun `layout extent remains finite and bounded for hostile geometry`() {
        val extent = PreviewLayoutLogic.safeDisplayExtent(
            Float.MAX_VALUE,
            RenderScale.normalized(4.0)
        )

        assertTrue(extent.isFinite())
        assertTrue(extent in 1f..100_000f)
    }
}
