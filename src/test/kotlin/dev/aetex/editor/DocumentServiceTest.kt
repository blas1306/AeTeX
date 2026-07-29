package dev.aetex.editor

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DocumentServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `opens a UTF-8 file and preserves its content`() {
        val file = temporaryDirectory.resolve("ámbito.tex")
        val content = "\\section{Introducción}\nLínea con ñ.\n"
        Files.writeString(file, content, StandardCharsets.UTF_8)
        val service = DocumentService(temporaryDirectory)

        val result = assertIs<DocumentResult.Success<OpenDocument>>(service.open(file))

        assertEquals(content, result.value.content)
        assertEquals(content, result.value.savedContent)
        assertFalse(result.value.isModified)
    }

    @Test
    fun `saves changes safely and returns a clean document state`() {
        val file = temporaryDirectory.resolve("main.tex")
        file.writeText("before\n")
        val service = DocumentService(temporaryDirectory)
        val opened = assertIs<DocumentResult.Success<OpenDocument>>(service.open(file)).value
        val changed = opened.withContent("after\nsecond line\n")

        val saved = assertIs<DocumentResult.Success<OpenDocument>>(service.save(changed)).value

        assertEquals("after\nsecond line\n", file.readText())
        assertEquals(saved.content, saved.savedContent)
        assertFalse(saved.isModified)
    }

    @Test
    fun `rejects directories and paths outside the project`() {
        val nestedDirectory = temporaryDirectory.resolve("folder").createDirectory()
        val outsideDirectory = Files.createTempDirectory("aetex-outside-")
        val outsideFile = outsideDirectory.resolve("outside.tex").createFile()
        val service = DocumentService(temporaryDirectory)

        val directoryResult = assertIs<DocumentResult.Failure>(
            service.open(nestedDirectory)
        )
        val outsideResult = assertIs<DocumentResult.Failure>(
            service.open(outsideFile)
        )

        assertEquals(DocumentOperation.VALIDATION, directoryResult.error.operation)
        assertEquals(DocumentOperation.VALIDATION, outsideResult.error.operation)
        assertTrue(outsideResult.error.userMessage.contains("outside"))
    }

    @Test
    fun `reports read and write failures when a file disappears`() {
        val file = temporaryDirectory.resolve("deleted.tex")
        file.writeText("content")
        val service = DocumentService(temporaryDirectory)
        val opened = assertIs<DocumentResult.Success<OpenDocument>>(service.open(file)).value
        Files.delete(file)

        val readResult = assertIs<DocumentResult.Failure>(service.open(file))
        val writeResult = assertIs<DocumentResult.Failure>(
            service.save(opened.withContent("changed"))
        )

        assertEquals(DocumentOperation.READ, readResult.error.operation)
        assertEquals(DocumentOperation.WRITE, writeResult.error.operation)
        assertTrue(readResult.error.userMessage.contains("no longer exists"))
        assertTrue(writeResult.error.userMessage.contains("no longer exists"))
    }

    @Test
    fun `recognizes supported extensions case insensitively`() {
        assertTrue(EditableFileTypes.isEditable(temporaryDirectory.resolve("REFERENCES.BIB")))
        assertTrue(EditableFileTypes.isEditable(temporaryDirectory.resolve("main.TeX")))
        assertFalse(EditableFileTypes.isEditable(temporaryDirectory.resolve("image.pdf")))
    }
}
