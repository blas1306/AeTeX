package dev.aetex.preview.rendering

import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.writePdf
import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PdfBoxDocumentRendererTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `opens PDF and exposes all page metadata`() {
        val pdf = temporaryDirectory.resolve("pages.pdf")
        writePdf(pdf, listOf(200f to 300f, 400f to 250f))

        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(pdf)
        ).value

        assertEquals(2, renderer.metadata.pageCount)
        assertEquals(200f, renderer.metadata.pages[0].widthPoints)
        assertEquals(400f, renderer.metadata.pages[1].widthPoints)
        renderer.close()
    }

    @Test
    fun `renders a valid page into engine-neutral RGB pixels`() {
        val pdf = temporaryDirectory.resolve("page.pdf")
        writePdf(pdf, listOf(20f to 30f))
        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(pdf)
        ).value

        val page = assertIs<PreviewResult.Success<dev.aetex.preview.domain.RenderedPage>>(
            renderer.render(PageRenderKey(GenerationId.create(), 0, RenderScale.DEFAULT))
        ).value

        assertEquals(20, page.raster.width)
        assertEquals(30, page.raster.height)
        assertEquals(20 * 30 * 3L, page.raster.retainedBytes)
        assertEquals(20 * 3, page.raster.stride)
        renderer.close()
    }

    @Test
    fun `scale changes raster dimensions deterministically`() {
        val pdf = temporaryDirectory.resolve("scale.pdf")
        writePdf(pdf, listOf(20f to 30f))
        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(pdf)
        ).value

        val page = assertIs<PreviewResult.Success<dev.aetex.preview.domain.RenderedPage>>(
            renderer.render(
                PageRenderKey(GenerationId.create(), 0, RenderScale.normalized(2.0))
            )
        ).value

        assertEquals(40, page.raster.width)
        assertEquals(60, page.raster.height)
        renderer.close()
    }

    @Test
    fun `rejects page outside metadata range`() {
        val pdf = temporaryDirectory.resolve("invalid-page.pdf")
        writePdf(pdf)
        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(pdf)
        ).value

        val failure = assertIs<PreviewResult.Failure>(
            renderer.render(PageRenderKey(GenerationId.create(), 3, RenderScale.DEFAULT))
        )

        assertEquals(PreviewErrorKind.INVALID_PAGE, failure.error.kind)
        renderer.close()
    }

    @Test
    fun `rejects corrupt PDF with typed open failure`() {
        val pdf = Files.writeString(temporaryDirectory.resolve("corrupt.pdf"), "%PDF truncated")

        val failure = assertIs<PreviewResult.Failure>(PdfBoxDocumentRenderer.open(pdf))

        assertEquals(PreviewErrorKind.CORRUPT_PDF, failure.error.kind)
    }

    @Test
    fun `rejects empty PDF without leaking its file handle`() {
        val pdf = temporaryDirectory.resolve("empty.pdf")
        writePdf(pdf, emptyList())

        val failure = assertIs<PreviewResult.Failure>(PdfBoxDocumentRenderer.open(pdf))

        assertEquals(PreviewErrorKind.CORRUPT_PDF, failure.error.kind)
        assertTrue(Files.deleteIfExists(pdf))
    }

    @Test
    fun `preserves rotated crop box geometry`() {
        val pdf = temporaryDirectory.resolve("rotated-crop.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(300f, 400f))
            page.cropBox = PDRectangle(10f, 20f, 120f, 80f)
            page.rotation = 90
            document.addPage(page)
            document.save(pdf.toFile())
        }

        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(pdf)
        ).value
        val geometry = renderer.metadata.pages.single()

        assertEquals(120f, geometry.widthPoints)
        assertEquals(80f, geometry.heightPoints)
        assertEquals(10f, geometry.cropLeftPoints)
        assertEquals(20f, geometry.cropBottomPoints)
        assertEquals(90, geometry.rotationDegrees)
        assertEquals(80f, geometry.displayedWidthPoints)
        assertEquals(120f, geometry.displayedHeightPoints)
        renderer.close()
    }

    @Test
    fun `rejects password protected PDF with typed failure`() {
        val pdf = temporaryDirectory.resolve("protected.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(
                StandardProtectionPolicy("owner-password", "user-password", AccessPermission())
            )
            document.save(pdf.toFile())
        }

        val failure = assertIs<PreviewResult.Failure>(PdfBoxDocumentRenderer.open(pdf))

        assertEquals(PreviewErrorKind.PROTECTED_PDF, failure.error.kind)
    }

    @Test
    fun `enforces raster pixel limits before allocation`() {
        val pdf = temporaryDirectory.resolve("large.pdf")
        writePdf(pdf, listOf(100f to 100f))
        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(
                pdf,
                RasterLimits(maximumWidth = 50, maximumHeight = 50, maximumPixels = 2_500)
            )
        ).value

        val failure = assertIs<PreviewResult.Failure>(
            renderer.render(PageRenderKey(GenerationId.create(), 0, RenderScale.DEFAULT))
        )

        assertEquals(PreviewErrorKind.RASTER_LIMIT, failure.error.kind)
        renderer.close()
    }

    @Test
    fun `rejects pathological page count before metadata allocation`() {
        val pdf = temporaryDirectory.resolve("many-pages.pdf")
        writePdf(pdf, List(3) { 10f to 10f })

        val failure = assertIs<PreviewResult.Failure>(
            PdfBoxDocumentRenderer.open(
                pdf,
                RasterLimits(maximumPages = 2)
            )
        )

        assertEquals(PreviewErrorKind.UNSUPPORTED_PDF, failure.error.kind)
        assertTrue(Files.deleteIfExists(pdf))
    }

    @Test
    fun `close is idempotent and later render is rejected`() {
        val pdf = temporaryDirectory.resolve("close.pdf")
        writePdf(pdf)
        val renderer = assertIs<PreviewResult.Success<DocumentRenderer>>(
            PdfBoxDocumentRenderer.open(pdf)
        ).value

        renderer.close()
        renderer.close()
        val failure = assertIs<PreviewResult.Failure>(
            renderer.render(PageRenderKey(GenerationId.create(), 0, RenderScale.DEFAULT))
        )

        assertEquals(PreviewErrorKind.MANAGER_CLOSED, failure.error.kind)
        assertTrue(Files.deleteIfExists(pdf))
    }
}
