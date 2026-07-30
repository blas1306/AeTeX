package dev.aetex.experiments.rendering

import java.nio.file.Path

/**
 * Experimental engine-neutral boundary. No PDFBox, PDFium, AWT or Compose type
 * is allowed through this interface.
 */
interface RendererAdapter : AutoCloseable {
    val id: String
    val version: String

    fun open(document: Path): DocumentInfo
    fun render(page: Int, scale: Double): RasterImage
    override fun close()
}

data class DocumentInfo(
    val pageCount: Int,
)

data class RasterImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
    }
}

abstract class StatefulRendererAdapter : RendererAdapter {
    protected var openDocument: Path? = null
        private set

    protected fun beginOpen(document: Path) {
        check(openDocument == null) { "$id already has an open document" }
        openDocument = document
    }

    protected fun failedOpen() {
        openDocument = null
    }

    protected fun requireOpen(): Path =
        checkNotNull(openDocument) { "$id has no open document" }

    protected fun endClose() {
        openDocument = null
    }
}
