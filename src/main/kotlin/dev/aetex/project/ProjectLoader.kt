package dev.aetex.project

import dev.aetex.project.configuration.CompilationStrategy
import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.EffectiveConfigurationValue
import dev.aetex.project.configuration.EffectiveProjectConfiguration
import dev.aetex.project.configuration.MainDocumentResolution
import dev.aetex.project.configuration.MainDocumentResolver
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.ProjectConfiguration
import dev.aetex.project.configuration.ProjectConfigurationDiagnostic
import dev.aetex.project.configuration.ProjectConfigurationDiagnosticCode
import dev.aetex.project.configuration.ProjectConfigurationDiagnosticSeverity
import dev.aetex.project.configuration.ProjectConfigurationLoadResult
import dev.aetex.project.configuration.ProjectConfigurationLoader
import dev.aetex.project.configuration.TeXEngine
import java.nio.file.Path
import javax.swing.JFileChooser

data class ProjectLoadResult(
    val project: TeXProject,
    val scanIssues: List<ProjectScanIssue>
)

class ProjectLoader(
    private val configurationLoader: ProjectConfigurationLoader = ProjectConfigurationLoader(),
    private val projectScanner: ProjectScanner = ProjectScanner(),
    private val mainDocumentResolver: MainDocumentResolver = MainDocumentResolver()
) {
    fun load(rootDirectory: Path): ProjectLoadResult {
        val root = projectScanner.resolveRoot(rootDirectory)
        return when (val configurationResult = configurationLoader.load(root)) {
            is ProjectConfigurationLoadResult.Absent ->
                loadInterpretableProject(
                    root = root,
                    configurationPath = configurationResult.configurationPath,
                    configuration = null,
                    persistedStatus = PersistedConfigurationStatus.ABSENT,
                    initialDiagnostics = listOf(
                        ProjectConfigurationDiagnostic(
                            code = ProjectConfigurationDiagnosticCode.CONFIGURATION_ABSENT,
                            severity = ProjectConfigurationDiagnosticSeverity.WARNING,
                            message = "This folder is not an AeTeX project. " +
                                "An AeTeX project requires .aetex/project.toml.",
                            configurationPath = configurationResult.configurationPath
                        )
                    )
                )

            is ProjectConfigurationLoadResult.Loaded ->
                loadInterpretableProject(
                    root = root,
                    configurationPath = configurationResult.configurationPath,
                    configuration = configurationResult.configuration,
                    persistedStatus = PersistedConfigurationStatus.LOADED,
                    initialDiagnostics = configurationResult.diagnostics
                )

            is ProjectConfigurationLoadResult.Invalid ->
                loadUninterpretableProject(
                    root = root,
                    persistedStatus = PersistedConfigurationStatus.INVALID,
                    diagnostics = configurationResult.diagnostics
                )

            is ProjectConfigurationLoadResult.UnsupportedSchema ->
                loadUninterpretableProject(
                    root = root,
                    persistedStatus = PersistedConfigurationStatus.UNSUPPORTED_SCHEMA,
                    diagnostics = configurationResult.diagnostics
                )
        }
    }

    private fun loadInterpretableProject(
        root: Path,
        configurationPath: Path,
        configuration: ProjectConfiguration?,
        persistedStatus: PersistedConfigurationStatus,
        initialDiagnostics: List<ProjectConfigurationDiagnostic>
    ): ProjectLoadResult {
        val outputRelative = configuration?.output ?: Path.of(DEFAULT_OUTPUT_DIRECTORY)
        val outputSource = if (configuration?.output != null) {
            ConfigurationValueSource.EXPLICIT
        } else {
            ConfigurationValueSource.DEFAULT
        }
        val outputDiagnostic = if (configuration?.output == null) {
            configurationLoader.validateOutput(root, configurationPath, outputRelative)
        } else {
            null
        }
        val outputDirectory = root.resolve(outputRelative).normalize()
        val scanResult = projectScanner.scanResolvedRoot(
            root = root,
            additionalExcludedDirectories = setOf(outputDirectory)
        )

        val mainResolution = if (outputDiagnostic == null) {
            mainDocumentResolver.resolve(
                root = root,
                entries = scanResult.project.entries,
                explicitMain = configuration?.main,
                outputDirectory = outputDirectory,
                configurationPath = configurationPath
            )
        } else {
            MainDocumentResolution(
                state = MainDocumentState.Unavailable,
                diagnostics = emptyList()
            )
        }

        val engineResolution = when {
            configuration?.engine != null -> EngineValueAndDiagnostics(
                value = EffectiveConfigurationValue(
                    value = configuration.engine,
                    source = ConfigurationValueSource.EXPLICIT
                )
            )

            mainResolution.state is MainDocumentState.Confirmed -> {
                val inferred = mainDocumentResolver.inferEngine(
                    confirmedMain = mainResolution.state.path,
                    configurationPath = configurationPath
                )
                EngineValueAndDiagnostics(inferred.value, inferred.diagnostics)
            }

            else -> EngineValueAndDiagnostics(
                value = EffectiveConfigurationValue(
                    value = TeXEngine.PDF_LATEX,
                    source = ConfigurationValueSource.DEFAULT
                )
            )
        }

        val diagnostics = buildList {
            addAll(initialDiagnostics)
            outputDiagnostic?.let(::add)
            addAll(mainResolution.diagnostics)
            addAll(engineResolution.diagnostics)
        }
        val effectiveConfiguration = EffectiveProjectConfiguration(
            persistedStatus = persistedStatus,
            mainDocument = mainResolution.state,
            engine = engineResolution.value,
            strategy = EffectiveConfigurationValue(
                value = configuration?.strategy ?: CompilationStrategy.LATEXMK,
                source = if (configuration?.strategy != null) {
                    ConfigurationValueSource.EXPLICIT
                } else {
                    ConfigurationValueSource.DEFAULT
                }
            ),
            outputDirectory = if (outputDiagnostic == null) {
                EffectiveConfigurationValue(
                    value = outputDirectory,
                    source = outputSource
                )
            } else {
                null
            }
        )

        return ProjectLoadResult(
            project = scanResult.project.copy(
                persistedConfiguration = configuration,
                effectiveConfiguration = effectiveConfiguration,
                configurationDiagnostics = diagnostics
            ),
            scanIssues = scanResult.issues
        )
    }

    private fun loadUninterpretableProject(
        root: Path,
        persistedStatus: PersistedConfigurationStatus,
        diagnostics: List<ProjectConfigurationDiagnostic>
    ): ProjectLoadResult {
        val scanResult = projectScanner.scanResolvedRoot(root)
        val invalidMainDiagnostic = diagnostics.firstOrNull { it.field == "main" }
        val mainState = if (invalidMainDiagnostic != null) {
            MainDocumentState.InvalidExplicitMain(
                configuredPath = null,
                diagnostic = invalidMainDiagnostic
            )
        } else {
            MainDocumentState.Unavailable
        }
        val effectiveConfiguration = EffectiveProjectConfiguration(
            persistedStatus = persistedStatus,
            mainDocument = mainState,
            engine = null,
            strategy = null,
            outputDirectory = null
        )

        return ProjectLoadResult(
            project = scanResult.project.copy(
                persistedConfiguration = null,
                effectiveConfiguration = effectiveConfiguration,
                configurationDiagnostics = diagnostics
            ),
            scanIssues = scanResult.issues
        )
    }

    private data class EngineValueAndDiagnostics(
        val value: EffectiveConfigurationValue<TeXEngine>,
        val diagnostics: List<ProjectConfigurationDiagnostic> = emptyList()
    )

    private companion object {
        const val DEFAULT_OUTPUT_DIRECTORY = "build"
    }
}

fun chooseProjectDirectory(): Path? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Open LaTeX project"
    }

    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath()
    } else {
        null
    }
}

fun chooseProjectParentDirectory(initialDirectory: Path? = null): Path? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose project location"
        initialDirectory?.toFile()?.takeIf { it.isDirectory }?.let {
            currentDirectory = it
        }
    }

    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.toPath()
    } else {
        null
    }
}
