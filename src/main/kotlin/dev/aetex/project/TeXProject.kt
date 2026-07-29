package dev.aetex.project

import java.nio.file.Path

data class TeXProject(
    val rootDirectory: Path,
    val entries: List<ProjectEntry>,
    val mainDocument: Path? = null
)

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
