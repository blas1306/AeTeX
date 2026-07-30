package dev.aetex.project.configuration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectConfigurationLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val loader = ProjectConfigurationLoader()

    @Test
    fun `reports an absent configuration file`() {
        val result = assertIs<ProjectConfigurationLoadResult.Absent>(
            loader.load(temporaryDirectory)
        )

        assertEquals(
            temporaryDirectory.resolve(".aetex").resolve("project.toml"),
            result.configurationPath
        )
    }

    @Test
    fun `loads a minimal configuration`() {
        writeConfiguration(
            """
            schema = 1
            main = "main.tex"
            """
        )

        val result = assertIs<ProjectConfigurationLoadResult.Loaded>(
            loader.load(temporaryDirectory)
        )

        assertEquals(1, result.configuration.schema)
        assertEquals(Path.of("main.tex"), result.configuration.main)
        assertNull(result.configuration.engine)
        assertNull(result.configuration.strategy)
        assertNull(result.configuration.output)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `loads every supported field and enum value`() {
        writeConfiguration(
            """
            schema = 1
            main = "src/main.tex"
            engine = "xelatex"
            strategy = "latexmk"
            output = "generated"
            """
        )

        val configuration = assertIs<ProjectConfigurationLoadResult.Loaded>(
            loader.load(temporaryDirectory)
        ).configuration

        assertEquals(Path.of("src", "main.tex"), configuration.main)
        assertEquals(TeXEngine.XE_LATEX, configuration.engine)
        assertEquals(CompilationStrategy.LATEXMK, configuration.strategy)
        assertEquals(Path.of("generated"), configuration.output)
    }

    @Test
    fun `accepts all supported engines`() {
        val expected = mapOf(
            "pdflatex" to TeXEngine.PDF_LATEX,
            "xelatex" to TeXEngine.XE_LATEX,
            "lualatex" to TeXEngine.LUA_LATEX
        )

        expected.forEach { (configured, engine) ->
            writeConfiguration("schema = 1\nengine = \"$configured\"")
            val result = assertIs<ProjectConfigurationLoadResult.Loaded>(
                loader.load(temporaryDirectory)
            )
            assertEquals(engine, result.configuration.engine)
        }
    }

    @Test
    fun `rejects unknown engine and strategy values`() {
        writeConfiguration(
            """
            schema = 1
            engine = "tectonic"
            strategy = "custom"
            """
        )

        val result = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )

        assertEquals(
            setOf(
                ProjectConfigurationDiagnosticCode.INVALID_ENGINE,
                ProjectConfigurationDiagnosticCode.INVALID_STRATEGY
            ),
            result.diagnostics.map(ProjectConfigurationDiagnostic::code).toSet()
        )
    }

    @Test
    fun `rejects invalid TOML with a source position`() {
        writeConfiguration("schema = [")

        val result = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )
        val diagnostic = result.diagnostics.first()

        assertEquals(ProjectConfigurationDiagnosticCode.INVALID_TOML, diagnostic.code)
        assertTrue(diagnostic.line != null)
        assertTrue(diagnostic.column != null)
    }

    @Test
    fun `rejects invalid UTF-8`() {
        val configurationPath = temporaryDirectory.resolve(".aetex")
            .createDirectories()
            .resolve("project.toml")
        Files.write(configurationPath, byteArrayOf(0xC3.toByte(), 0x28))

        val result = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )

        assertEquals(
            ProjectConfigurationDiagnosticCode.INVALID_UTF8,
            result.diagnostics.single().code
        )
    }

    @Test
    fun `distinguishes missing invalid and unsupported schemas`() {
        writeConfiguration("main = \"main.tex\"")
        val missing = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )
        assertEquals(
            ProjectConfigurationDiagnosticCode.SCHEMA_MISSING,
            missing.diagnostics.single().code
        )

        writeConfiguration("schema = \"1\"")
        val invalid = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )
        assertEquals(
            ProjectConfigurationDiagnosticCode.SCHEMA_INVALID,
            invalid.diagnostics.single().code
        )

        writeConfiguration("schema = 2")
        val future = assertIs<ProjectConfigurationLoadResult.UnsupportedSchema>(
            loader.load(temporaryDirectory)
        )
        assertEquals(2, future.schema)
        assertEquals(
            ProjectConfigurationDiagnosticCode.SCHEMA_UNSUPPORTED,
            future.diagnostics.single().code
        )

        writeConfiguration("schema = 0")
        val old = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )
        assertEquals(
            ProjectConfigurationDiagnosticCode.SCHEMA_INVALID,
            old.diagnostics.single().code
        )
    }

    @Test
    fun `warns about and records unknown fields`() {
        writeConfiguration(
            """
            schema = 1
            future = "value"
            """
        )

        val result = assertIs<ProjectConfigurationLoadResult.Loaded>(
            loader.load(temporaryDirectory)
        )

        assertEquals(mapOf("future" to "value"), result.configuration.unknownFields)
        assertEquals(
            ProjectConfigurationDiagnosticCode.UNKNOWN_FIELD,
            result.diagnostics.single().code
        )
        assertEquals(
            ProjectConfigurationDiagnosticSeverity.WARNING,
            result.diagnostics.single().severity
        )
    }

    @Test
    fun `validates relative main paths`() {
        writeConfiguration("schema = 1\nmain = \"chapters/main.tex\"")
        val valid = assertIs<ProjectConfigurationLoadResult.Loaded>(
            loader.load(temporaryDirectory)
        )
        assertEquals(Path.of("chapters", "main.tex"), valid.configuration.main)

        val absolute = temporaryDirectory.resolve("main.tex")
            .toString()
            .replace('\\', '/')
        writeConfiguration("schema = 1\nmain = \"$absolute\"")
        assertInvalidPath("main")

        writeConfiguration("schema = 1\nmain = \"../main.tex\"")
        assertInvalidPath("main")
    }

    @Test
    fun `validates output paths and existing path type`() {
        writeConfiguration("schema = 1\noutput = \"generated\"")
        val valid = assertIs<ProjectConfigurationLoadResult.Loaded>(
            loader.load(temporaryDirectory)
        )
        assertEquals(Path.of("generated"), valid.configuration.output)

        val absolute = temporaryDirectory.resolve("generated")
            .toString()
            .replace('\\', '/')
        writeConfiguration("schema = 1\noutput = \"$absolute\"")
        assertInvalidPath("output")

        writeConfiguration("schema = 1\noutput = \"../generated\"")
        assertInvalidPath("output")

        temporaryDirectory.resolve("artifact").writeText("not a directory")
        writeConfiguration("schema = 1\noutput = \"artifact\"")
        val fileOutput = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )
        assertEquals(
            ProjectConfigurationDiagnosticCode.OUTPUT_NOT_DIRECTORY,
            fileOutput.diagnostics.single().code
        )
    }

    @Test
    fun `rejects output that contains project metadata`() {
        writeConfiguration("schema = 1\noutput = \".aetex\"")

        val result = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )

        assertEquals(
            ProjectConfigurationDiagnosticCode.OUTPUT_OVERLAPS_METADATA,
            result.diagnostics.single().code
        )
    }

    private fun assertInvalidPath(field: String) {
        val result = assertIs<ProjectConfigurationLoadResult.Invalid>(
            loader.load(temporaryDirectory)
        )
        assertTrue(
            result.diagnostics.any {
                it.code == ProjectConfigurationDiagnosticCode.INVALID_PATH &&
                    it.field == field
            }
        )
    }

    private fun writeConfiguration(content: String) {
        temporaryDirectory.resolve(".aetex").createDirectories()
            .resolve("project.toml")
            .writeText(content.trimIndent() + "\n")
    }
}
