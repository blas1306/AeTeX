package dev.aetex.project.configuration

import java.nio.file.Path

enum class ProjectConfigurationDiagnosticSeverity {
    WARNING,
    ERROR
}

enum class ProjectConfigurationDiagnosticCode {
    CONFIGURATION_ABSENT,
    CONFIGURATION_IO_ERROR,
    CONFIGURATION_PATH_INVALID,
    INVALID_UTF8,
    INVALID_TOML,
    SCHEMA_MISSING,
    SCHEMA_INVALID,
    SCHEMA_UNSUPPORTED,
    UNKNOWN_FIELD,
    INVALID_FIELD_TYPE,
    INVALID_ENGINE,
    INVALID_STRATEGY,
    INVALID_PATH,
    OUTPUT_NOT_DIRECTORY,
    OUTPUT_OUTSIDE_PROJECT,
    OUTPUT_OVERLAPS_METADATA,
    MAIN_NOT_FOUND,
    MAIN_NOT_REGULAR_FILE,
    MAIN_NOT_READABLE,
    MAIN_NOT_TEX,
    MAIN_SYMBOLIC_LINK,
    MAIN_OUTSIDE_PROJECT,
    MAIN_IN_EXCLUDED_DIRECTORY,
    MAIN_IN_OUTPUT_DIRECTORY,
    MAIN_MISSING_DOCUMENT_CLASS,
    INVALID_ROOT_DIRECTIVE,
    CONFLICTING_ROOT_DIRECTIVES,
    CONFLICTING_ENGINE_DIRECTIVES,
    UNSUPPORTED_ENGINE_DIRECTIVE,
    MAIN_PROVISIONAL,
    MAIN_SELECTION_REQUIRED,
    MAIN_UNAVAILABLE
}

data class ProjectConfigurationDiagnostic(
    val code: ProjectConfigurationDiagnosticCode,
    val severity: ProjectConfigurationDiagnosticSeverity,
    val message: String,
    val configurationPath: Path? = null,
    val relatedPath: Path? = null,
    val field: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val technicalDetails: String? = null,
    val cause: Throwable? = null
)
