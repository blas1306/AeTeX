package dev.aetex.preview.rendering

import dev.aetex.preview.domain.DocumentMetadata
import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RasterImage
import dev.aetex.preview.domain.RenderedPage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

internal class PdfBoxDocumentRenderer private constructor(
    private val document: PDDocument,
    override val metadata: DocumentMetadata,
    private val limits: RasterLimits
) : DocumentRenderer {
    private val closed = AtomicBoolean(false)
    private val accessLock = Any()
    private val renderer = PDFRenderer(document)

    override fun render(key: PageRenderKey): PreviewResult<RenderedPage> =
        synchronized(accessLock) {
            if (closed.get()) {
                return@synchronized PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.MANAGER_CLOSED,
                        "The PDF renderer is closed.",
                        key.generationId,
                        key.pageIndex,
                        key.scale
                    )
                )
            }
            if (key.pageIndex !in metadata.pages.indices) {
                return@synchronized PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.INVALID_PAGE,
                        "Page ${key.pageIndex + 1} is outside this document.",
                        key.generationId,
                        key.pageIndex,
                        key.scale
                    )
                )
            }
            val geometry = metadata.pages[key.pageIndex]
            val width = ceil(geometry.displayedWidthPoints * key.scale.value).toInt()
            val height = ceil(geometry.displayedHeightPoints * key.scale.value).toInt()
            if (
                width <= 0 ||
                height <= 0 ||
                width > limits.maximumWidth ||
                height > limits.maximumHeight ||
                width.toLong() * height > limits.maximumPixels
            ) {
                return@synchronized PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.RASTER_LIMIT,
                        "The requested page raster exceeds the configured safety limit.",
                        key.generationId,
                        key.pageIndex,
                        key.scale
                    )
                )
            }
            try {
                val image = renderer.renderImage(key.pageIndex, key.scale.value, ImageType.RGB)
                val raster = image.toOwnedRgbRaster()
                PreviewResult.Success(
                    RenderedPage(
                        key = key,
                        raster = raster,
                        geometry = geometry,
                        rendererId = metadata.rendererId,
                        rendererVersion = metadata.rendererVersion
                    )
                )
            } catch (error: OutOfMemoryError) {
                PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.MEMORY_LIMIT,
                        "The page could not be rasterized within available memory.",
                        key.generationId,
                        key.pageIndex,
                        key.scale,
                        error
                    )
                )
            } catch (error: Throwable) {
                PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.RENDER_FAILED,
                        "Page ${key.pageIndex + 1} could not be rendered.",
                        key.generationId,
                        key.pageIndex,
                        key.scale,
                        error
                    )
                )
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(accessLock) {
            document.close()
        }
    }

    companion object : DocumentRendererFactory {
        const val RENDERER_ID = "pdfbox"

        override fun open(snapshotPath: Path): PreviewResult<DocumentRenderer> =
            open(snapshotPath, RasterLimits())

        fun open(
            snapshotPath: Path,
            limits: RasterLimits
        ): PreviewResult<DocumentRenderer> {
            var document: PDDocument? = null
            return try {
                document = Loader.loadPDF(snapshotPath.toFile())
                if (document.isEncrypted) {
                    closeAfterOpenFailure(document)
                    document = null
                    PreviewResult.Failure(
                        PreviewError(
                            PreviewErrorKind.PROTECTED_PDF,
                            "Password-protected PDFs cannot be previewed."
                        )
                    )
                } else if (document.numberOfPages <= 0) {
                    closeAfterOpenFailure(document)
                    document = null
                    PreviewResult.Failure(
                        PreviewError(
                            PreviewErrorKind.CORRUPT_PDF,
                            "The PDF contains no displayable pages."
                        )
                    )
                } else if (document.numberOfPages > limits.maximumPages) {
                    closeAfterOpenFailure(document)
                    document = null
                    PreviewResult.Failure(
                        PreviewError(
                            PreviewErrorKind.UNSUPPORTED_PDF,
                            "The PDF page count exceeds the configured preview safety limit."
                        )
                    )
                } else {
                    val version =
                        PDDocument::class.java.`package`.implementationVersion ?: "3.x"
                    val pages = (0 until document.numberOfPages).map { index ->
                        val page = document.getPage(index)
                        val box = page.cropBox
                        PageGeometry(
                            widthPoints = box.width,
                            heightPoints = box.height,
                            cropLeftPoints = box.lowerLeftX,
                            cropBottomPoints = box.lowerLeftY,
                            rotationDegrees = normalizedRotation(page.rotation)
                        )
                    }
                    PreviewResult.Success(
                        PdfBoxDocumentRenderer(
                            document = document,
                            metadata = DocumentMetadata(pages, RENDERER_ID, version),
                            limits = limits
                        )
                    )
                }
            } catch (error: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                closeAfterOpenFailure(document)
                PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.PROTECTED_PDF,
                        "Password-protected PDFs cannot be previewed.",
                        technicalCause = error
                    )
                )
            } catch (error: IOException) {
                closeAfterOpenFailure(document)
                PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.CORRUPT_PDF,
                        "The generated PDF is corrupt or unsupported.",
                        technicalCause = error
                    )
                )
            } catch (error: Throwable) {
                closeAfterOpenFailure(document)
                PreviewResult.Failure(
                    PreviewError(
                        PreviewErrorKind.UNSUPPORTED_PDF,
                        "The generated PDF could not be opened.",
                        technicalCause = error
                    )
                )
            }
        }

        private fun normalizedRotation(rotation: Int): Int {
            val normalized = ((rotation % 360) + 360) % 360
            return if (normalized in setOf(0, 90, 180, 270)) normalized else 0
        }

        private fun closeAfterOpenFailure(document: PDDocument?) {
            try {
                document?.close()
            } catch (_: IOException) {
                // The original open error remains authoritative.
            }
        }

        private fun BufferedImage.toOwnedRgbRaster(): RasterImage {
            try {
                val stride = Math.multiplyExact(width, 3)
                val bytes = ByteArray(Math.multiplyExact(stride, height))
                val row = IntArray(width)
                var target = 0
                for (y in 0 until height) {
                    getRGB(0, y, width, 1, row, 0, width)
                    for (pixel in row) {
                        bytes[target++] = ((pixel ushr 16) and 0xff).toByte()
                        bytes[target++] = ((pixel ushr 8) and 0xff).toByte()
                        bytes[target++] = (pixel and 0xff).toByte()
                    }
                }
                return RasterImage.owned(width, height, stride, bytes)
            } finally {
                flush()
            }
        }
    }
}
