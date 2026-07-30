package dev.aetex.project

import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.MainDocumentSelectionReason
import dev.aetex.project.configuration.MainDocumentState
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

class MainDocumentResolutionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val loader = ProjectLoader()

    @Test
    fun `confirms a valid explicit main document`() {
        val main = latexFile("src/main.tex")
        configuration("schema = 1\nmain = \"src/main.tex\"")

        val project = loader.load(temporaryDirectory).project

        assertEquals(main.toRealPath(), assertIs<MainDocumentState.Confirmed>(
            project.mainDocumentState
        ).path)
        assertEquals(main.toRealPath(), project.mainDocument)
        assertTrue(project.effectiveConfiguration.isReady)
    }

    @Test
    fun `does not fall back from an invalid explicit main`() {
        val fallback = latexFile("fallback.tex")
        configuration("schema = 1\nmain = \"missing.tex\"")

        val project = loader.load(temporaryDirectory).project
        val state = assertIs<MainDocumentState.InvalidExplicitMain>(
            project.mainDocumentState
        )

        assertEquals(Path.of("missing.tex"), state.configuredPath)
        assertEquals(ProjectConfigurationDiagnosticCode.MAIN_NOT_FOUND, state.diagnostic.code)
        assertNull(project.mainDocument)
        assertTrue(
            project.configurationDiagnostics.none { it.relatedPath == fallback.toRealPath() }
        )
    }

    @Test
    fun `selects a single candidate provisionally without configuration`() {
        val only = latexFile("article.tex")

        val state = assertIs<MainDocumentState.Provisional>(
            loader.load(temporaryDirectory).project.mainDocumentState
        )

        assertEquals(only.toRealPath(), state.path)
        assertEquals(MainDocumentSelectionReason.SINGLE_CANDIDATE, state.reason)
    }

    @Test
    fun `prefers converging root directives`() {
        val first = latexFile("first.tex")
        latexFile("second.tex")
        textFile("chapters/one.tex", "% !TEX root = ../first.tex\nContent")
        textFile("chapters/two.tex", "% !TEX root = ../first.tex\nContent")

        val state = assertIs<MainDocumentState.Provisional>(
            loader.load(temporaryDirectory).project.mainDocumentState
        )

        assertEquals(first.toRealPath(), state.path)
        assertEquals(MainDocumentSelectionReason.ROOT_DIRECTIVE, state.reason)
    }

    @Test
    fun `prefers a unique main tex name`() {
        val main = latexFile("nested/main.tex")
        latexFile("other/appendix.tex")

        val state = assertIs<MainDocumentState.Provisional>(
            loader.load(temporaryDirectory).project.mainDocumentState
        )

        assertEquals(main.toRealPath(), state.path)
        assertEquals(MainDocumentSelectionReason.MAIN_FILE_NAME, state.reason)
    }

    @Test
    fun `prefers a unique root-level candidate`() {
        val rootMain = latexFile("article.tex")
        latexFile("nested/appendix.tex")

        val state = assertIs<MainDocumentState.Provisional>(
            loader.load(temporaryDirectory).project.mainDocumentState
        )

        assertEquals(rootMain.toRealPath(), state.path)
        assertEquals(MainDocumentSelectionReason.ROOT_LEVEL, state.reason)
    }

    @Test
    fun `uses the project directory name only when earlier signals do not decide`() {
        val projectName = temporaryDirectory.fileName.toString()
        val named = latexFile("a/$projectName.tex")
        latexFile("b/other.tex")

        val state = assertIs<MainDocumentState.Provisional>(
            loader.load(temporaryDirectory).project.mainDocumentState
        )

        assertEquals(named.toRealPath(), state.path)
        assertEquals(MainDocumentSelectionReason.PROJECT_DIRECTORY_NAME, state.reason)
    }

    @Test
    fun `requires selection when heuristics remain tied`() {
        val first = latexFile("a/first.tex")
        val second = latexFile("b/second.tex")

        val state = assertIs<MainDocumentState.SelectionRequired>(
            loader.load(temporaryDirectory).project.mainDocumentState
        )

        assertEquals(setOf(first.toRealPath(), second.toRealPath()), state.candidates.toSet())
    }

    @Test
    fun `ignores commented documentclass commands`() {
        textFile("commented.tex", "% \\\\documentclass{article}\nText")

        val project = loader.load(temporaryDirectory).project

        assertIs<MainDocumentState.Unavailable>(project.mainDocumentState)
    }

    @Test
    fun `excludes output and aetex files from candidates and the project tree`() {
        latexFile("generated/main.tex")
        latexFile(".aetex/hidden.tex")
        val visible = latexFile("visible.tex")
        configuration("schema = 1\noutput = \"generated\"")

        val project = loader.load(temporaryDirectory).project
        val state = assertIs<MainDocumentState.Provisional>(project.mainDocumentState)

        assertEquals(visible.toRealPath(), state.path)
        assertTrue(project.entries.none { it.name == "generated" || it.name == ".aetex" })
    }

    @Test
    fun `rejects an explicit main inside output`() {
        latexFile("generated/main.tex")
        configuration(
            """
            schema = 1
            main = "generated/main.tex"
            output = "generated"
            """
        )

        val project = loader.load(temporaryDirectory).project
        val state = assertIs<MainDocumentState.InvalidExplicitMain>(
            project.mainDocumentState
        )

        assertEquals(
            ProjectConfigurationDiagnosticCode.MAIN_IN_OUTPUT_DIRECTORY,
            state.diagnostic.code
        )
    }

    @Test
    fun `infers engine only from a confirmed main top-of-file directive`() {
        textFile(
            "main.tex",
            "% !TEX program = lualatex\n\\documentclass{article}\n"
        )
        configuration("schema = 1\nmain = \"main.tex\"")

        val engine = loader.load(temporaryDirectory)
            .project
            .effectiveConfiguration
            .engine

        assertEquals(TeXEngine.LUA_LATEX, engine?.value)
        assertEquals(ConfigurationValueSource.INFERRED, engine?.source)
    }

    @Test
    fun `falls back for conflicting engine directives`() {
        textFile(
            "main.tex",
            """
            % !TEX program = xelatex
            % !TEX program = lualatex
            \documentclass{article}
            """
        )
        configuration("schema = 1\nmain = \"main.tex\"")

        val project = loader.load(temporaryDirectory).project

        assertEquals(TeXEngine.PDF_LATEX, project.effectiveConfiguration.engine?.value)
        assertEquals(
            ConfigurationValueSource.DEFAULT,
            project.effectiveConfiguration.engine?.source
        )
        assertTrue(
            project.configurationDiagnostics.any {
                it.code == ProjectConfigurationDiagnosticCode.CONFLICTING_ENGINE_DIRECTIVES
            }
        )
    }

    private fun latexFile(relativePath: String): Path =
        textFile(relativePath, "\\documentclass{article}\n")

    private fun textFile(relativePath: String, content: String): Path {
        val path = temporaryDirectory.resolve(relativePath)
        path.parent.createDirectories()
        path.writeText(content)
        return path
    }

    private fun configuration(content: String) {
        textFile(".aetex/project.toml", content.trimIndent() + "\n")
    }
}
