package dev.aetex.experiments.rendering

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.nio.file.Path

class PdfBoxAdapter : StatefulRendererAdapter() {
    override val id: String = "pdfbox"
    override val version: String =
        PDDocument::class.java.`package`.implementationVersion ?: "3.0.5"

    private var document: PDDocument? = null
    private var renderer: PDFRenderer? = null

    override fun open(document: Path): DocumentInfo {
        beginOpen(document)
        var loaded: PDDocument? = null
        try {
            val opened = Loader.loadPDF(document.toFile())
            loaded = opened
            this.document = opened
            renderer = PDFRenderer(opened)
            return DocumentInfo(opened.numberOfPages)
        } catch (failure: Throwable) {
            loaded?.close()
            this.document = null
            renderer = null
            failedOpen()
            throw failure
        }
    }

    override fun render(page: Int, scale: Double): RasterImage {
        requireOpen()
        require(scale > 0.0 && scale.isFinite()) { "scale must be finite and positive" }
        val loaded = checkNotNull(document)
        if (page !in 0 until loaded.numberOfPages) {
            throw IndexOutOfBoundsException("page $page outside 0..${loaded.numberOfPages - 1}")
        }
        val image = checkNotNull(renderer).renderImage(page, scale.toFloat(), ImageType.RGB)
        return RasterImage(
            width = image.width,
            height = image.height,
            argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
        )
    }

    override fun close() {
        document?.close()
        document = null
        renderer = null
        endClose()
    }
}
