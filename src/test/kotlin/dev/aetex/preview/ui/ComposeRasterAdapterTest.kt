package dev.aetex.preview.ui

import dev.aetex.preview.domain.RasterImage
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeRasterAdapterTest {
    @Test
    fun `converts owned RGB pixels once at Compose boundary`() {
        val raster = RasterImage.owned(
            width = 1,
            height = 1,
            stride = 3,
            bytes = byteArrayOf(0x12, 0x34, 0x56)
        )
        val image = ComposePageImage.from(raster)
        val pixels = IntArray(1)

        image.imageBitmap.readPixels(pixels)

        assertEquals(0xff123456.toInt(), pixels.single())
        image.close()
        image.close()
    }
}
