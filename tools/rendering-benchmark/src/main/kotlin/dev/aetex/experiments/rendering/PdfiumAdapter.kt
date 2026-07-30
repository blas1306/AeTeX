package dev.aetex.experiments.rendering

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import java.nio.file.Path

class PdfiumRuntime(
    libraryPath: Path,
) : AutoCloseable {
    internal val api: PdfiumAdapter.PdfiumLibrary =
        Native.load(libraryPath.toAbsolutePath().toString(), PdfiumAdapter.PdfiumLibrary::class.java)
    private var initialized = false

    init {
        api.FPDF_InitLibrary()
        initialized = true
    }

    override fun close() {
        if (initialized) {
            api.FPDF_DestroyLibrary()
            initialized = false
        }
    }
}

class PdfiumAdapter(
    private val runtime: PdfiumRuntime,
) : StatefulRendererAdapter() {
    override val id: String = "pdfium"
    override val version: String = PDFIUM_VERSION

    private val api: PdfiumLibrary
        get() = runtime.api
    private var document: Pointer? = null
    private var pageCount: Int = 0

    override fun open(document: Path): DocumentInfo {
        beginOpen(document)
        try {
            val loaded = api.FPDF_LoadDocument(document.toAbsolutePath().toString(), null)
                ?: throw PdfiumException("FPDF_LoadDocument failed", lastError())
            this.document = loaded
            pageCount = api.FPDF_GetPageCount(loaded)
            if (pageCount < 0) {
                throw PdfiumException("FPDF_GetPageCount returned $pageCount", lastError())
            }
            return DocumentInfo(pageCount)
        } catch (failure: Throwable) {
            this.document?.let(api::FPDF_CloseDocument)
            this.document = null
            pageCount = 0
            failedOpen()
            throw failure
        }
    }

    override fun render(page: Int, scale: Double): RasterImage {
        requireOpen()
        require(scale > 0.0 && scale.isFinite()) { "scale must be finite and positive" }
        if (page !in 0 until pageCount) {
            throw IndexOutOfBoundsException("page $page outside 0..${pageCount - 1}")
        }

        val nativePage = api.FPDF_LoadPage(checkNotNull(document), page)
            ?: throw PdfiumException("FPDF_LoadPage($page) failed", lastError())
        try {
            // PDFBox's scale API truncates fractional pixel dimensions. Match
            // that policy so both engines rasterize the same pixel count.
            val width = (api.FPDF_GetPageWidthF(nativePage) * scale).toInt().coerceAtLeast(1)
            val height = (api.FPDF_GetPageHeightF(nativePage) * scale).toInt().coerceAtLeast(1)
            val bitmap = api.FPDFBitmap_Create(width, height, 1)
                ?: throw PdfiumException("FPDFBitmap_Create($width, $height) failed", lastError())
            try {
                api.FPDFBitmap_FillRect(bitmap, 0, 0, width, height, WHITE_BGRA)
                api.FPDF_RenderPageBitmap(
                    bitmap,
                    nativePage,
                    0,
                    0,
                    width,
                    height,
                    0,
                    FPDF_ANNOT,
                )
                return copyBitmap(bitmap, width, height)
            } finally {
                api.FPDFBitmap_Destroy(bitmap)
            }
        } finally {
            api.FPDF_ClosePage(nativePage)
        }
    }

    private fun copyBitmap(bitmap: Pointer, width: Int, height: Int): RasterImage {
        val stride = api.FPDFBitmap_GetStride(bitmap)
        check(stride >= width * 4) { "Unexpected PDFium stride $stride for width $width" }
        val buffer = api.FPDFBitmap_GetBuffer(bitmap)
            ?: throw PdfiumException("FPDFBitmap_GetBuffer failed", lastError())
        val bytes = buffer.getByteArray(0, stride * height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val offset = row + x * 4
                val blue = bytes[offset].toInt() and 0xff
                val green = bytes[offset + 1].toInt() and 0xff
                val red = bytes[offset + 2].toInt() and 0xff
                pixels[y * width + x] =
                    OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
            }
        }
        return RasterImage(width, height, pixels)
    }

    override fun close() {
        document?.let(api::FPDF_CloseDocument)
        document = null
        pageCount = 0
        endClose()
    }

    private fun lastError(): Long = api.FPDF_GetLastError().toLong()

    internal interface PdfiumLibrary : Library {
        fun FPDF_InitLibrary()
        fun FPDF_DestroyLibrary()
        fun FPDF_LoadDocument(filePath: String, password: String?): Pointer?
        fun FPDF_GetLastError(): NativeLong
        fun FPDF_GetPageCount(document: Pointer): Int
        fun FPDF_LoadPage(document: Pointer, pageIndex: Int): Pointer?
        fun FPDF_ClosePage(page: Pointer)
        fun FPDF_CloseDocument(document: Pointer)
        fun FPDF_GetPageWidthF(page: Pointer): Float
        fun FPDF_GetPageHeightF(page: Pointer): Float
        fun FPDFBitmap_Create(width: Int, height: Int, alpha: Int): Pointer?
        fun FPDFBitmap_Destroy(bitmap: Pointer)
        fun FPDFBitmap_FillRect(bitmap: Pointer, left: Int, top: Int, width: Int, height: Int, color: Int)
        fun FPDFBitmap_GetBuffer(bitmap: Pointer): Pointer?
        fun FPDFBitmap_GetStride(bitmap: Pointer): Int
        fun FPDF_RenderPageBitmap(
            bitmap: Pointer,
            page: Pointer,
            startX: Int,
            startY: Int,
            sizeX: Int,
            sizeY: Int,
            rotate: Int,
            flags: Int,
        )
    }

    companion object {
        const val PDFIUM_VERSION = "152.0.7961.0"
        private const val FPDF_ANNOT = 0x01
        private const val WHITE_BGRA = -1
        private const val OPAQUE_ALPHA = -0x1000000
    }
}

class PdfiumException(message: String, errorCode: Long) :
    IllegalStateException("$message (PDFium error $errorCode)")
