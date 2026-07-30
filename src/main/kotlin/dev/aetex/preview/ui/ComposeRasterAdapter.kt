package dev.aetex.preview.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.aetex.preview.domain.RasterImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean

class ComposePageImage private constructor(
    val imageBitmap: ImageBitmap,
    private val bitmap: Bitmap,
    @Suppress("unused") private val ownedPixels: ByteArray
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            bitmap.close()
        }
    }

    companion object {
        fun from(raster: RasterImage): ComposePageImage {
            val pixels = raster.copyArgbBytesForUi()
            val bitmap = Bitmap()
            try {
                val info = ImageInfo(
                    raster.width,
                    raster.height,
                    ColorType.BGRA_8888,
                    ColorAlphaType.OPAQUE
                )
                check(
                    bitmap.installPixels(
                        info,
                        pixels,
                        Math.multiplyExact(raster.width, 4)
                    )
                ) {
                    "Skia rejected the rendered preview pixels."
                }
                bitmap.setImmutable()
                return ComposePageImage(bitmap.asComposeImageBitmap(), bitmap, pixels)
            } catch (error: Throwable) {
                bitmap.close()
                throw error
            }
        }
    }
}
