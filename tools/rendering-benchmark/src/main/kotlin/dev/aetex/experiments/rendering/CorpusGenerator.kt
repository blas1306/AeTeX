package dev.aetex.experiments.rendering

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class CorpusDocument(
    val path: Path,
    val category: String,
    val expectedPages: Int,
)

class CorpusGenerator(
    private val root: Path,
) {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    fun generate(): List<CorpusDocument> {
        root.createDirectories()
        val documents = listOf(
            CorpusDocument(root.resolve("small.pdf"), "small", 2),
            CorpusDocument(root.resolve("medium.pdf"), "medium", 8),
            CorpusDocument(root.resolve("large.pdf"), "large-mixed", 20),
            CorpusDocument(root.resolve("image-heavy.pdf"), "many-images", 6),
            CorpusDocument(root.resolve("text-heavy.pdf"), "much-text", 12),
            CorpusDocument(root.resolve("many-pages.pdf"), "many-pages", 40),
            CorpusDocument(root.resolve("vector-graphics.pdf"), "vector-graphics", 8),
            CorpusDocument(root.resolve("tables.pdf"), "tables", 8),
        )

        writeDocument(documents[0]) { document, page -> addTextPage(document, page, 18) }
        writeDocument(documents[1]) { document, page -> addMixedPage(document, page) }
        writeDocument(documents[2]) { document, page -> addMixedPage(document, page, dense = true) }
        writeDocument(documents[3]) { document, page -> addImagePage(document, page) }
        writeDocument(documents[4]) { document, page -> addTextPage(document, page, 46) }
        writeDocument(documents[5]) { document, page -> addTextPage(document, page, 12) }
        writeDocument(documents[6]) { document, page -> addVectorPage(document, page) }
        writeDocument(documents[7]) { document, page -> addTablePage(document, page) }

        val invalid = root.resolve("invalid").createDirectories()
        Files.write(invalid.resolve("empty.pdf"), byteArrayOf())
        Files.writeString(
            invalid.resolve("corrupt.pdf"),
            "%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 99 0 R >>\nendobj\ntruncated",
        )
        writeManifest(documents)
        return documents
    }

    private fun writeDocument(
        descriptor: CorpusDocument,
        pageWriter: (PDDocument, Int) -> Unit,
    ) {
        val temporary = descriptor.path.resolveSibling("${descriptor.path.name}.generating")
        try {
            PDDocument().use { document ->
                document.documentInformation.apply {
                    title = "AeTeX synthetic ${descriptor.category} benchmark"
                    author = "AeTeX experimental benchmark generator"
                    creator = "AeTeX CorpusGenerator"
                    producer = "Apache PDFBox synthetic corpus"
                    val epoch = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = 0
                    }
                    creationDate = epoch
                    modificationDate = epoch
                }
                repeat(descriptor.expectedPages) { pageWriter(document, it) }
                val stableId = COSArray().apply {
                    add(COSString("AeTeX-${descriptor.path.name}-synthetic-id"))
                    add(COSString("AeTeX-${descriptor.path.name}-synthetic-id"))
                }
                document.document.trailer.setItem("ID", stableId)
                document.save(temporary.toFile())
            }
            try {
                Files.move(
                    temporary,
                    descriptor.path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, descriptor.path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun addTextPage(document: PDDocument, pageNumber: Int, lines: Int) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            heading(stream, "Synthetic text page ${pageNumber + 1}")
            stream.beginText()
            stream.setFont(regular, 9f)
            stream.newLineAtOffset(50f, 760f)
            repeat(lines) { line ->
                stream.showText(
                    "Line ${line + 1}: deterministic LaTeX-like prose, equations x^2 + y^2 = r^2, " +
                        "references [${pageNumber + 1}.${line + 1}], and repeated glyph coverage.",
                )
                stream.newLineAtOffset(0f, -14f)
            }
            stream.endText()
        }
    }

    private fun addMixedPage(document: PDDocument, pageNumber: Int, dense: Boolean = false) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            heading(stream, "Synthetic mixed page ${pageNumber + 1}")
            drawTable(stream, 45f, 690f, 5, if (dense) 12 else 7, 100f, 22f)
            stream.setStrokingColor(Color(30, 90, 180))
            repeat(if (dense) 30 else 12) { index ->
                val x = 60f + (index % 10) * 48f
                val y = 230f + (index / 10) * 70f
                stream.addRect(x, y, 34f, 34f)
                stream.stroke()
            }
            stream.beginText()
            stream.setFont(regular, 9f)
            stream.newLineAtOffset(50f, 190f)
            repeat(if (dense) 10 else 5) { line ->
                stream.showText("Mixed content line ${line + 1} on deterministic page ${pageNumber + 1}.")
                stream.newLineAtOffset(0f, -14f)
            }
            stream.endText()
        }
    }

    private fun addImagePage(document: PDDocument, pageNumber: Int) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val image = syntheticImage(pageNumber)
        val pdfImage = LosslessFactory.createFromImage(document, image)
        PDPageContentStream(document, page).use { stream ->
            heading(stream, "Synthetic image page ${pageNumber + 1}")
            stream.drawImage(pdfImage, 45f, 210f, 505f, 380f)
            stream.drawImage(pdfImage, 45f, 90f, 160f, 100f)
            stream.drawImage(pdfImage, 220f, 90f, 160f, 100f)
            stream.drawImage(pdfImage, 395f, 90f, 160f, 100f)
        }
    }

    private fun syntheticImage(seed: Int): BufferedImage {
        val width = 960
        val height = 720
        val random = Random(seed + 44_021)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val noise = random.nextInt(24)
                    val red = (x * 255 / width + noise).coerceAtMost(255)
                    val green = (y * 255 / height + noise).coerceAtMost(255)
                    val blue = ((x xor y) and 0xff)
                    setRGB(x, y, Color(red, green, blue).rgb)
                }
            }
        }
    }

    private fun addVectorPage(document: PDDocument, pageNumber: Int) {
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            heading(stream, "Synthetic vector page ${pageNumber + 1}")
            val centerX = 297f
            val centerY = 410f
            repeat(180) { index ->
                val angle = index * Math.PI / 90.0
                val radius = 40f + index * 1.7f
                val x = centerX + (cos(angle) * radius).toFloat()
                val y = centerY + (sin(angle) * radius).toFloat()
                stream.setStrokingColor(
                    Color.getHSBColor(index / 180f, 0.75f, 0.85f),
                )
                stream.moveTo(centerX, centerY)
                stream.lineTo(x, y)
                stream.stroke()
            }
            repeat(20) { index ->
                stream.setNonStrokingColor(Color(index * 11, 80, 220 - index * 8, 140))
                stream.addRect(55f + index * 22f, 100f + index * 7f, 100f, 80f)
                stream.fill()
            }
        }
    }

    private fun addTablePage(document: PDDocument, pageNumber: Int) {
        val page = PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            heading(stream, "Synthetic table page ${pageNumber + 1}", landscape = true)
            drawTable(stream, 35f, 520f, 10, 20, 76f, 23f, labels = true)
        }
    }

    private fun drawTable(
        stream: PDPageContentStream,
        left: Float,
        top: Float,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
        labels: Boolean = false,
    ) {
        stream.setStrokingColor(Color.DARK_GRAY)
        repeat(rows + 1) { row ->
            stream.moveTo(left, top - row * cellHeight)
            stream.lineTo(left + columns * cellWidth, top - row * cellHeight)
            stream.stroke()
        }
        repeat(columns + 1) { column ->
            stream.moveTo(left + column * cellWidth, top)
            stream.lineTo(left + column * cellWidth, top - rows * cellHeight)
            stream.stroke()
        }
        if (labels) {
            stream.beginText()
            stream.setFont(regular, 7f)
            repeat(rows) { row ->
                repeat(columns) { column ->
                    stream.setTextMatrix(
                        org.apache.pdfbox.util.Matrix.getTranslateInstance(
                            left + column * cellWidth + 4f,
                            top - row * cellHeight - 15f,
                        ),
                    )
                    stream.showText("R${row + 1} C${column + 1}")
                }
            }
            stream.endText()
        }
    }

    private fun heading(stream: PDPageContentStream, text: String, landscape: Boolean = false) {
        stream.beginText()
        stream.setFont(bold, 16f)
        stream.newLineAtOffset(45f, if (landscape) 555f else 800f)
        stream.showText(text)
        stream.endText()
    }

    private fun writeManifest(documents: List<CorpusDocument>) {
        val lines = buildList {
            add("file\tcategory\tpages\tbytes\tsha256")
            documents.forEach { document ->
                add(
                    listOf(
                        document.path.name,
                        document.category,
                        document.expectedPages,
                        document.path.fileSize(),
                        sha256(document.path),
                    ).joinToString("\t"),
                )
            }
        }
        Files.write(root.resolve("manifest.tsv"), lines)
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}

fun validateCorpusGeometry(documents: List<CorpusDocument>) {
    documents.forEach { descriptor ->
        Loader.loadPDF(descriptor.path.toFile()).use { document ->
            check(document.numberOfPages == descriptor.expectedPages) {
                "${descriptor.path.name}: expected ${descriptor.expectedPages} pages, got ${document.numberOfPages}"
            }
            document.pages.forEachIndexed { index, page ->
                val media = page.mediaBox
                val crop = page.cropBox
                check(page.rotation.mod(360) == 0) {
                    "${descriptor.path.name} page ${index + 1}: rotation ${page.rotation} is outside the audited zero-rotation corpus"
                }
                check(
                    media.lowerLeftX == crop.lowerLeftX &&
                        media.lowerLeftY == crop.lowerLeftY &&
                        media.width == crop.width &&
                        media.height == crop.height,
                ) {
                    "${descriptor.path.name} page ${index + 1}: CropBox differs from MediaBox"
                }
            }
        }
    }
}
