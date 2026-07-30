package dev.aetex.project

import dev.aetex.project.configuration.CompilationStrategy
import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.ProjectConfigurationDiagnosticCode
import dev.aetex.project.configuration.TeXEngine
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectLoaderIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val loader = ProjectLoader()

    @Test
    fun `opens an unconfigured project with defaults and provisional state`() {
        val main = latexFile("main.tex")

        val project = loader.load(temporaryDirectory).project

        assertEquals(PersistedConfigurationStatus.ABSENT, project.effectiveConfiguration.persistedStatus)
        assertNull(project.persistedConfiguration)
        assertEquals(main.toRealPath(), project.mainDocument)
        assertEquals(TeXEngine.PDF_LATEX, project.effectiveConfiguration.engine?.value)
        assertEquals(
            CompilationStrategy.LATEXMK,
            project.effectiveConfiguration.strategy?.value
        )
        assertEquals(
            temporaryDirectory.toRealPath().resolve("build"),
            project.effectiveConfiguration.outputDirectory?.value
        )
        assertTrue(project.effectiveConfiguration.isProvisional)
    }

    @Test
    fun `opens a complete configured project with explicit sources`() {
        val main = latexFile("src/book.tex")
        configuration(
            """
            schema = 1
            main = "src/book.tex"
            engine = "xelatex"
            strategy = "latexmk"
            output = "artifacts"
            """
        )

        val project = loader.load(temporaryDirectory).project

        assertEquals(PersistedConfigurationStatus.LOADED, project.effectiveConfiguration.persistedStatus)
        assertEquals(main.toRealPath(), assertIs<MainDocumentState.Confirmed>(
            project.mainDocumentState
        ).path)
        assertEquals(TeXEngine.XE_LATEX, project.effectiveConfiguration.engine?.value)
        assertEquals(
            ConfigurationValueSource.EXPLICIT,
            project.effectiveConfiguration.engine?.source
        )
        assertEquals(
            temporaryDirectory.toRealPath().resolve("artifacts"),
            project.effectiveConfiguration.outputDirectory?.value
        )
        assertTrue(project.effectiveConfiguration.isReady)
    }

    @Test
    fun `opens a schema-only configuration with detected provisional main`() {
        val main = latexFile("paper.tex")
        configuration("schema = 1")

        val project = loader.load(temporaryDirectory).project

        assertEquals(PersistedConfigurationStatus.LOADED, project.effectiveConfiguration.persistedStatus)
        assertEquals(
            main.toRealPath(),
            assertIs<MainDocumentState.Provisional>(project.mainDocumentState).path
        )
        assertTrue(project.persistedConfiguration != null)
        assertTrue(project.effectiveConfiguration.isProvisional)
        assertTrue(!project.effectiveConfiguration.isReady)
    }

    @Test
    fun `keeps a corrupt configuration recoverable without interpreting defaults`() {
        latexFile("main.tex")
        configuration("schema = [")

        val project = loader.load(temporaryDirectory).project

        assertEquals(PersistedConfigurationStatus.INVALID, project.effectiveConfiguration.persistedStatus)
        assertIs<MainDocumentState.Unavailable>(project.mainDocumentState)
        assertNull(project.effectiveConfiguration.engine)
        assertNull(project.effectiveConfiguration.strategy)
        assertNull(project.effectiveConfiguration.outputDirectory)
        assertTrue(
            project.configurationDiagnostics.any {
                it.code == ProjectConfigurationDiagnosticCode.INVALID_TOML
            }
        )
    }

    @Test
    fun `does not interpret a future schema`() {
        latexFile("main.tex")
        configuration("schema = 2\nmain = \"main.tex\"")

        val project = loader.load(temporaryDirectory).project

        assertEquals(
            PersistedConfigurationStatus.UNSUPPORTED_SCHEMA,
            project.effectiveConfiguration.persistedStatus
        )
        assertNull(project.persistedConfiguration)
        assertNull(project.mainDocument)
        assertNull(project.effectiveConfiguration.outputDirectory)
    }

    @Test
    fun `scanner excludes a custom output subtree but keeps siblings`() {
        latexFile("generated/ignored.tex")
        latexFile("sources/kept.tex")
        configuration("schema = 1\noutput = \"generated\"")

        val project = loader.load(temporaryDirectory).project

        assertEquals(listOf("sources"), project.entries.map(ProjectEntry::name))
        val sources = assertIs<ProjectDirectory>(project.entries.single())
        assertEquals(listOf("kept.tex"), sources.children.map(ProjectEntry::name))
    }

    private fun latexFile(relativePath: String): Path {
        val path = temporaryDirectory.resolve(relativePath)
        path.parent.createDirectories()
        path.writeText("\\documentclass{article}\n")
        return path
    }

    private fun configuration(content: String) {
        val path = temporaryDirectory.resolve(".aetex/project.toml")
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }
}
