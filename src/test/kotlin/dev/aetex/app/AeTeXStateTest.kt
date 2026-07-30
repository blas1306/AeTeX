package dev.aetex.app

import dev.aetex.editor.OpenDocument
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.ProjectConfigurationDiagnosticCode
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AeTeXStateTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `open document detects edits and becomes clean after saving`() {
        val file = temporaryDirectory.resolve("main.tex")
        file.writeText("original")
        val state = AeTeXState()

        assertTrue(state.openProject(temporaryDirectory))
        assertTrue(state.openDocument(file))
        state.updateDocument(file, "modified")

        assertTrue(state.activeDocument?.isModified == true)
        assertTrue(state.saveActiveDocument())
        assertFalse(state.activeDocument?.isModified ?: true)
        assertEquals("modified", file.readText())
    }

    @Test
    fun `keeps multiple documents separate and changes the active document`() {
        val first = temporaryDirectory.resolve("first.tex")
        val second = temporaryDirectory.resolve("second.bib")
        first.writeText("first")
        second.writeText("second")
        val state = AeTeXState()

        state.openProject(temporaryDirectory)
        state.openDocument(first)
        state.updateDocument(first, "first changed")
        state.openDocument(second)

        assertEquals(2, state.openDocuments.size)
        assertEquals(second.toRealPath(), state.activeDocumentPath)
        assertEquals(
            "first changed",
            state.openDocuments.single { it.path == first.toRealPath() }.content
        )

        state.activateDocument(first)
        assertEquals(first.toRealPath(), state.activeDocumentPath)
        assertTrue(state.activeDocument?.isModified == true)
    }

    @Test
    fun `closing the active document selects a neighboring tab`() {
        val first = temporaryDirectory.resolve("first.tex")
        val second = temporaryDirectory.resolve("second.tex")
        first.writeText("first")
        second.writeText("second")
        val state = AeTeXState()
        state.openProject(temporaryDirectory)
        state.openDocument(first)
        state.openDocument(second)

        state.closeDocument(second)

        assertEquals(first.toRealPath(), state.activeDocumentPath)
        assertEquals(listOf(first.toRealPath()), state.openDocuments.map(OpenDocument::path))
        state.closeDocument(first)
        assertNull(state.activeDocumentPath)
    }

    @Test
    fun `does not replace a project or close a modified document implicitly`() {
        val file = temporaryDirectory.resolve("main.tex")
        file.writeText("original")
        val otherProject = temporaryDirectory.resolve("other").createDirectory()
        val state = AeTeXState()
        state.openProject(temporaryDirectory)
        state.openDocument(file)
        state.updateDocument(file, "modified")

        assertFalse(state.closeDocument(file))
        assertFalse(state.openProject(otherProject))
        assertEquals(temporaryDirectory.toRealPath(), state.project?.rootDirectory)
        assertTrue(state.activeDocument?.isModified == true)

        state.discardAndCloseDocument(file)
        assertTrue(state.openDocuments.isEmpty())
    }

    @Test
    fun `keeps editing available when project configuration is corrupt`() {
        val file = temporaryDirectory.resolve("main.tex")
        file.writeText("original")
        temporaryDirectory.resolve(".aetex").createDirectory()
            .resolve("project.toml").writeText("schema = [")
        val state = AeTeXState()

        assertTrue(state.openProject(temporaryDirectory))
        assertEquals(
            PersistedConfigurationStatus.INVALID,
            state.project?.effectiveConfiguration?.persistedStatus
        )
        assertTrue(
            state.configurationDiagnostics.any {
                it.code == ProjectConfigurationDiagnosticCode.INVALID_TOML
            }
        )
        assertTrue(state.openDocument(file))
        state.updateDocument(file, "changed")
        assertTrue(state.saveActiveDocument())
        assertEquals("changed", file.readText())
    }

    @Test
    fun `does not open configured output as an editable document`() {
        val outputFile = temporaryDirectory.resolve("generated").createDirectory()
            .resolve("result.tex")
        outputFile.writeText("generated")
        temporaryDirectory.resolve(".aetex").createDirectory()
            .resolve("project.toml").writeText(
                "schema = 1\noutput = \"generated\"\n"
            )
        val state = AeTeXState()

        assertTrue(state.openProject(temporaryDirectory))
        assertFalse(state.openDocument(outputFile))
        assertTrue(state.openDocuments.isEmpty())
    }
}
