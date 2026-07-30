package dev.aetex.project.configuration

import java.nio.file.Path

const val CURRENT_PROJECT_CONFIGURATION_SCHEMA: Int = 1

enum class TeXEngine(val configurationValue: String) {
    PDF_LATEX("pdflatex"),
    XE_LATEX("xelatex"),
    LUA_LATEX("lualatex");

    companion object {
        fun fromConfigurationValue(value: String): TeXEngine? =
            entries.firstOrNull { it.configurationValue == value }
    }
}

enum class CompilationStrategy(val configurationValue: String) {
    LATEXMK("latexmk");

    companion object {
        fun fromConfigurationValue(value: String): CompilationStrategy? =
            entries.firstOrNull { it.configurationValue == value }
    }
}

data class ProjectConfiguration(
    val schema: Int,
    val main: Path?,
    val engine: TeXEngine?,
    val strategy: CompilationStrategy?,
    val output: Path?,
    val unknownFields: Map<String, Any> = emptyMap()
)

enum class ConfigurationValueSource {
    EXPLICIT,
    INFERRED,
    DEFAULT
}

data class EffectiveConfigurationValue<T>(
    val value: T,
    val source: ConfigurationValueSource
)

enum class PersistedConfigurationStatus {
    ABSENT,
    LOADED,
    INVALID,
    UNSUPPORTED_SCHEMA
}

enum class MainDocumentSelectionReason {
    SINGLE_CANDIDATE,
    ROOT_DIRECTIVE,
    MAIN_FILE_NAME,
    ROOT_LEVEL,
    PROJECT_DIRECTORY_NAME
}

sealed interface MainDocumentState {
    data class Confirmed(val path: Path) : MainDocumentState

    data class Provisional(
        val path: Path,
        val reason: MainDocumentSelectionReason
    ) : MainDocumentState

    data class SelectionRequired(val candidates: List<Path>) : MainDocumentState

    data object Unavailable : MainDocumentState

    data class InvalidExplicitMain(
        val configuredPath: Path?,
        val diagnostic: ProjectConfigurationDiagnostic
    ) : MainDocumentState
}

data class EffectiveProjectConfiguration(
    val persistedStatus: PersistedConfigurationStatus,
    val mainDocument: MainDocumentState,
    val engine: EffectiveConfigurationValue<TeXEngine>?,
    val strategy: EffectiveConfigurationValue<CompilationStrategy>?,
    val outputDirectory: EffectiveConfigurationValue<Path>?
) {
    val isProvisional: Boolean
        get() = persistedStatus == PersistedConfigurationStatus.ABSENT ||
            mainDocument !is MainDocumentState.Confirmed

    val isReady: Boolean
        get() = persistedStatus == PersistedConfigurationStatus.LOADED &&
            mainDocument is MainDocumentState.Confirmed &&
            engine != null &&
            strategy != null &&
            outputDirectory != null
}
