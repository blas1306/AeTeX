package dev.aetex.project.configuration

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlVersion

sealed interface ProjectConfigurationLoadResult {
    val configurationPath: Path
    val diagnostics: List<ProjectConfigurationDiagnostic>

    data class Absent(
        override val configurationPath: Path
    ) : ProjectConfigurationLoadResult {
        override val diagnostics: List<ProjectConfigurationDiagnostic> = emptyList()
    }

    data class Loaded(
        override val configurationPath: Path,
        val configuration: ProjectConfiguration,
        override val diagnostics: List<ProjectConfigurationDiagnostic>
    ) : ProjectConfigurationLoadResult

    data class Invalid(
        override val configurationPath: Path,
        override val diagnostics: List<ProjectConfigurationDiagnostic>
    ) : ProjectConfigurationLoadResult

    data class UnsupportedSchema(
        override val configurationPath: Path,
        val schema: Long,
        override val diagnostics: List<ProjectConfigurationDiagnostic>
    ) : ProjectConfigurationLoadResult
}

class ProjectConfigurationLoader {
    fun load(projectRoot: Path): ProjectConfigurationLoadResult {
        val root = projectRoot.toAbsolutePath().normalize()
        val configurationPath = root.resolve(CONFIGURATION_RELATIVE_PATH)

        if (!Files.exists(configurationPath, LinkOption.NOFOLLOW_LINKS)) {
            return ProjectConfigurationLoadResult.Absent(configurationPath)
        }

        validateConfigurationFile(root, configurationPath)?.let { diagnostic ->
            return ProjectConfigurationLoadResult.Invalid(
                configurationPath = configurationPath,
                diagnostics = listOf(diagnostic)
            )
        }

        val source = try {
            Files.readString(configurationPath, StandardCharsets.UTF_8)
        } catch (error: CharacterCodingException) {
            return invalid(
                configurationPath,
                code = ProjectConfigurationDiagnosticCode.INVALID_UTF8,
                message = "The project configuration is not valid UTF-8.",
                error = error
            )
        } catch (error: IOException) {
            return invalid(
                configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                message = "The project configuration could not be read.",
                error = error
            )
        } catch (error: SecurityException) {
            return invalid(
                configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                message = "Access to the project configuration was denied.",
                error = error
            )
        }

        val parsed = Toml.parse(source, TomlVersion.V1_0_0)
        if (parsed.hasErrors()) {
            return ProjectConfigurationLoadResult.Invalid(
                configurationPath = configurationPath,
                diagnostics = parsed.errors().map { error ->
                    ProjectConfigurationDiagnostic(
                        code = ProjectConfigurationDiagnosticCode.INVALID_TOML,
                        severity = ProjectConfigurationDiagnosticSeverity.ERROR,
                        message = "The project configuration contains invalid TOML.",
                        configurationPath = configurationPath,
                        line = error.position().line(),
                        column = error.position().column(),
                        technicalDetails = error.message
                    )
                }
            )
        }

        val schemaValue = parsed.get("schema")
            ?: return ProjectConfigurationLoadResult.Invalid(
                configurationPath = configurationPath,
                diagnostics = listOf(
                    diagnostic(
                        configurationPath = configurationPath,
                        code = ProjectConfigurationDiagnosticCode.SCHEMA_MISSING,
                        message = "The project configuration must declare an integer schema."
                    )
                )
            )

        if (schemaValue !is Long || schemaValue <= 0) {
            return ProjectConfigurationLoadResult.Invalid(
                configurationPath = configurationPath,
                diagnostics = listOf(
                    diagnostic(
                        configurationPath = configurationPath,
                        code = ProjectConfigurationDiagnosticCode.SCHEMA_INVALID,
                        message = "The project configuration schema must be a positive integer.",
                        field = "schema"
                    )
                )
            )
        }

        if (schemaValue != CURRENT_PROJECT_CONFIGURATION_SCHEMA.toLong()) {
            val diagnostic = diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.SCHEMA_UNSUPPORTED,
                message = "Project configuration schema $schemaValue is not supported.",
                field = "schema"
            )
            return ProjectConfigurationLoadResult.UnsupportedSchema(
                configurationPath = configurationPath,
                schema = schemaValue,
                diagnostics = listOf(diagnostic)
            )
        }

        return loadSchemaOne(root, configurationPath, parsed)
    }

    private fun validateConfigurationFile(
        root: Path,
        configurationPath: Path
    ): ProjectConfigurationDiagnostic? {
        if (Files.isSymbolicLink(configurationPath)) {
            return diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_PATH_INVALID,
                message = "The project configuration cannot be a symbolic link.",
                relatedPath = configurationPath
            )
        }
        if (!Files.isRegularFile(configurationPath, LinkOption.NOFOLLOW_LINKS)) {
            return diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_PATH_INVALID,
                message = "The project configuration path is not a regular file.",
                relatedPath = configurationPath
            )
        }

        return try {
            if (!configurationPath.toRealPath().startsWith(root)) {
                diagnostic(
                    configurationPath = configurationPath,
                    code = ProjectConfigurationDiagnosticCode.CONFIGURATION_PATH_INVALID,
                    message = "The project configuration resolves outside the project root.",
                    relatedPath = configurationPath
                )
            } else {
                null
            }
        } catch (error: IOException) {
            diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                message = "The project configuration path could not be resolved.",
                relatedPath = configurationPath,
                error = error
            )
        } catch (error: SecurityException) {
            diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                message = "Access to the project configuration path was denied.",
                relatedPath = configurationPath,
                error = error
            )
        }
    }

    private fun loadSchemaOne(
        root: Path,
        configurationPath: Path,
        parsed: TomlParseResult
    ): ProjectConfigurationLoadResult {
        val diagnostics = mutableListOf<ProjectConfigurationDiagnostic>()
        val unknownFieldNames = parsed.keySet()
            .filterNot(KNOWN_FIELDS::contains)
            .toSortedSet()
        val unknownFields = unknownFieldNames.associateWith { field ->
            checkNotNull(parsed.get(field))
        }

        unknownFieldNames.forEach { field ->
            diagnostics += ProjectConfigurationDiagnostic(
                code = ProjectConfigurationDiagnosticCode.UNKNOWN_FIELD,
                severity = ProjectConfigurationDiagnosticSeverity.WARNING,
                message = "Unknown project configuration field '$field' was ignored.",
                configurationPath = configurationPath,
                field = field
            )
        }

        val main = readRelativePath(
            parsed = parsed,
            field = "main",
            root = root,
            configurationPath = configurationPath,
            diagnostics = diagnostics
        )
        val output = readRelativePath(
            parsed = parsed,
            field = "output",
            root = root,
            configurationPath = configurationPath,
            diagnostics = diagnostics
        )
        val engine = readEngine(parsed, configurationPath, diagnostics)
        val strategy = readStrategy(parsed, configurationPath, diagnostics)

        if (output != null) {
            validateOutput(root, configurationPath, output)?.let(diagnostics::add)
        }

        if (diagnostics.any { it.severity == ProjectConfigurationDiagnosticSeverity.ERROR }) {
            return ProjectConfigurationLoadResult.Invalid(
                configurationPath = configurationPath,
                diagnostics = diagnostics
            )
        }

        return ProjectConfigurationLoadResult.Loaded(
            configurationPath = configurationPath,
            configuration = ProjectConfiguration(
                schema = CURRENT_PROJECT_CONFIGURATION_SCHEMA,
                main = main,
                engine = engine,
                strategy = strategy,
                output = output,
                unknownFields = unknownFields
            ),
            diagnostics = diagnostics
        )
    }

    private fun readEngine(
        parsed: TomlParseResult,
        configurationPath: Path,
        diagnostics: MutableList<ProjectConfigurationDiagnostic>
    ): TeXEngine? {
        val value = readOptionalString(parsed, "engine", configurationPath, diagnostics)
            ?: return null
        return TeXEngine.fromConfigurationValue(value).also { engine ->
            if (engine == null) {
                diagnostics += diagnostic(
                    configurationPath = configurationPath,
                    code = ProjectConfigurationDiagnosticCode.INVALID_ENGINE,
                    message = "Unsupported engine '$value'. Expected pdflatex, xelatex, or lualatex.",
                    field = "engine"
                )
            }
        }
    }

    private fun readStrategy(
        parsed: TomlParseResult,
        configurationPath: Path,
        diagnostics: MutableList<ProjectConfigurationDiagnostic>
    ): CompilationStrategy? {
        val value = readOptionalString(parsed, "strategy", configurationPath, diagnostics)
            ?: return null
        return CompilationStrategy.fromConfigurationValue(value).also { strategy ->
            if (strategy == null) {
                diagnostics += diagnostic(
                    configurationPath = configurationPath,
                    code = ProjectConfigurationDiagnosticCode.INVALID_STRATEGY,
                    message = "Unsupported compilation strategy '$value'. Expected latexmk.",
                    field = "strategy"
                )
            }
        }
    }

    private fun readRelativePath(
        parsed: TomlParseResult,
        field: String,
        root: Path,
        configurationPath: Path,
        diagnostics: MutableList<ProjectConfigurationDiagnostic>
    ): Path? {
        val value = readOptionalString(parsed, field, configurationPath, diagnostics)
            ?: return null

        if (value.isBlank()) {
            diagnostics += invalidPath(configurationPath, field, "The '$field' path cannot be empty.")
            return null
        }
        if ('\\' in value) {
            diagnostics += invalidPath(
                configurationPath,
                field,
                "The '$field' path must use '/' as its separator."
            )
            return null
        }
        if (value.startsWith("~") || WINDOWS_DRIVE_PREFIX.matches(value)) {
            diagnostics += invalidPath(
                configurationPath,
                field,
                "The '$field' path must be relative to the project root."
            )
            return null
        }

        val relative = try {
            Path.of(value)
        } catch (error: InvalidPathException) {
            diagnostics += invalidPath(
                configurationPath,
                field,
                "The '$field' path is not a valid filesystem path.",
                error
            )
            return null
        }

        if (relative.isAbsolute) {
            diagnostics += invalidPath(
                configurationPath,
                field,
                "The '$field' path must be relative to the project root."
            )
            return null
        }

        val normalized = relative.normalize()
        val resolved = root.resolve(normalized).normalize()
        if (!resolved.startsWith(root)) {
            diagnostics += invalidPath(
                configurationPath,
                field,
                "The '$field' path escapes the project root."
            )
            return null
        }

        return normalized
    }

    private fun readOptionalString(
        parsed: TomlParseResult,
        field: String,
        configurationPath: Path,
        diagnostics: MutableList<ProjectConfigurationDiagnostic>
    ): String? {
        val value = parsed.get(field) ?: return null
        if (value !is String) {
            diagnostics += diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.INVALID_FIELD_TYPE,
                message = "The '$field' field must be a string.",
                field = field
            )
            return null
        }
        return value
    }

    internal fun validateOutput(
        root: Path,
        configurationPath: Path,
        output: Path
    ): ProjectConfigurationDiagnostic? {
        val resolved = root.resolve(output).normalize()
        if (resolved == root) {
            return diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.INVALID_PATH,
                message = "The output directory must be a proper descendant of the project root.",
                field = "output",
                relatedPath = resolved
            )
        }
        if (configurationPath.startsWith(resolved)) {
            return diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.OUTPUT_OVERLAPS_METADATA,
                message = "The output directory cannot contain .aetex/project.toml.",
                field = "output",
                relatedPath = resolved
            )
        }

        return try {
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(resolved)) {
                    diagnostic(
                        configurationPath = configurationPath,
                        code = ProjectConfigurationDiagnosticCode.OUTPUT_NOT_DIRECTORY,
                        message = "The configured output path exists but is not a directory.",
                        field = "output",
                        relatedPath = resolved
                    )
                } else if (!resolved.toRealPath().startsWith(root)) {
                    diagnostic(
                        configurationPath = configurationPath,
                        code = ProjectConfigurationDiagnosticCode.OUTPUT_OUTSIDE_PROJECT,
                        message = "The configured output directory resolves outside the project.",
                        field = "output",
                        relatedPath = resolved
                    )
                } else {
                    null
                }
            } else {
                validateNonexistentOutputAncestor(root, configurationPath, resolved)
            }
        } catch (error: IOException) {
            diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                message = "The configured output directory could not be validated.",
                field = "output",
                relatedPath = resolved,
                error = error
            )
        } catch (error: SecurityException) {
            diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                message = "Access was denied while validating the output directory.",
                field = "output",
                relatedPath = resolved,
                error = error
            )
        }
    }

    private fun validateNonexistentOutputAncestor(
        root: Path,
        configurationPath: Path,
        output: Path
    ): ProjectConfigurationDiagnostic? {
        var ancestor = output.parent
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        val existingAncestor = ancestor ?: root
        if (!Files.isDirectory(existingAncestor)) {
            return diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.OUTPUT_NOT_DIRECTORY,
                message = "An ancestor of the configured output path is not a directory.",
                field = "output",
                relatedPath = existingAncestor
            )
        }
        if (!existingAncestor.toRealPath().startsWith(root)) {
            return diagnostic(
                configurationPath = configurationPath,
                code = ProjectConfigurationDiagnosticCode.OUTPUT_OUTSIDE_PROJECT,
                message = "The configured output path resolves outside the project.",
                field = "output",
                relatedPath = output
            )
        }
        return null
    }

    private fun invalidPath(
        configurationPath: Path,
        field: String,
        message: String,
        error: Exception? = null
    ): ProjectConfigurationDiagnostic = diagnostic(
        configurationPath = configurationPath,
        code = ProjectConfigurationDiagnosticCode.INVALID_PATH,
        message = message,
        field = field,
        error = error
    )

    private fun invalid(
        configurationPath: Path,
        code: ProjectConfigurationDiagnosticCode,
        message: String,
        error: Exception
    ): ProjectConfigurationLoadResult.Invalid = ProjectConfigurationLoadResult.Invalid(
        configurationPath = configurationPath,
        diagnostics = listOf(
            diagnostic(
                configurationPath = configurationPath,
                code = code,
                message = message,
                error = error
            )
        )
    )

    private fun diagnostic(
        configurationPath: Path,
        code: ProjectConfigurationDiagnosticCode,
        message: String,
        field: String? = null,
        relatedPath: Path? = null,
        error: Throwable? = null
    ): ProjectConfigurationDiagnostic = ProjectConfigurationDiagnostic(
        code = code,
        severity = ProjectConfigurationDiagnosticSeverity.ERROR,
        message = message,
        configurationPath = configurationPath,
        relatedPath = relatedPath,
        field = field,
        technicalDetails = error?.message,
        cause = error
    )

    companion object {
        val CONFIGURATION_RELATIVE_PATH: Path = Path.of(".aetex", "project.toml")

        private val KNOWN_FIELDS: Set<String> =
            setOf("schema", "main", "engine", "strategy", "output")

        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:.*")
    }
}
