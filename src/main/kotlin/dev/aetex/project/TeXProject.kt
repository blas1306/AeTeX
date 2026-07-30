package dev.aetex.project

import dev.aetex.project.configuration.CompilationStrategy
import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.EffectiveConfigurationValue
import dev.aetex.project.configuration.EffectiveProjectConfiguration
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.ProjectConfiguration
import dev.aetex.project.configuration.ProjectConfigurationDiagnostic
import dev.aetex.project.configuration.TeXEngine
import java.nio.file.Path

data class TeXProject(
    val rootDirectory: Path,
    val entries: List<ProjectEntry>,
    val persistedConfiguration: ProjectConfiguration? = null,
    val effectiveConfiguration: EffectiveProjectConfiguration =
        defaultUnconfiguredEffectiveConfiguration(rootDirectory),
    val configurationDiagnostics: List<ProjectConfigurationDiagnostic> = emptyList()
) {
    val mainDocumentState: MainDocumentState
        get() = effectiveConfiguration.mainDocument

    val mainDocument: Path?
        get() = when (val state = mainDocumentState) {
            is MainDocumentState.Confirmed -> state.path
            is MainDocumentState.Provisional -> state.path
            is MainDocumentState.InvalidExplicitMain,
            is MainDocumentState.SelectionRequired,
            MainDocumentState.Unavailable -> null
        }
}

sealed interface ProjectEntry {
    val path: Path
    val name: String
    val isSymbolicLink: Boolean
}

data class ProjectDirectory(
    override val path: Path,
    val children: List<ProjectEntry>,
    override val isSymbolicLink: Boolean = false
) : ProjectEntry {
    override val name: String = path.fileName?.toString() ?: path.toString()
}

data class ProjectFile(
    override val path: Path,
    override val isSymbolicLink: Boolean = false
) : ProjectEntry {
    override val name: String = path.fileName?.toString() ?: path.toString()
}

private fun defaultUnconfiguredEffectiveConfiguration(root: Path) =
    EffectiveProjectConfiguration(
        persistedStatus = PersistedConfigurationStatus.ABSENT,
        mainDocument = MainDocumentState.Unavailable,
        engine = EffectiveConfigurationValue(
            value = TeXEngine.PDF_LATEX,
            source = ConfigurationValueSource.DEFAULT
        ),
        strategy = EffectiveConfigurationValue(
            value = CompilationStrategy.LATEXMK,
            source = ConfigurationValueSource.DEFAULT
        ),
        outputDirectory = EffectiveConfigurationValue(
            value = root.toAbsolutePath().normalize().resolve("build"),
            source = ConfigurationValueSource.DEFAULT
        )
    )
