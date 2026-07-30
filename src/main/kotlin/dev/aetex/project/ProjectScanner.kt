package dev.aetex.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

data class ProjectScanIssue(
    val path: Path,
    val message: String,
    val technicalDetails: String? = null
)

data class ProjectScanResult(
    val project: TeXProject,
    val issues: List<ProjectScanIssue>
)

class ProjectScanException(
    val userMessage: String,
    cause: Throwable? = null
) : Exception(userMessage, cause)

class ProjectScanner(
    private val excludedDirectoryNames: Set<String> = DEFAULT_EXCLUDED_DIRECTORIES
) {
    fun scan(
        rootDirectory: Path,
        additionalExcludedDirectories: Set<Path> = emptySet()
    ): ProjectScanResult {
        val root = resolveRoot(rootDirectory)
        return scanResolvedRoot(root, additionalExcludedDirectories)
    }

    internal fun scanResolvedRoot(
        root: Path,
        additionalExcludedDirectories: Set<Path> = emptySet()
    ): ProjectScanResult {
        val issues = mutableListOf<ProjectScanIssue>()
        val visitedDirectories = mutableSetOf<Path>()
        val normalizedExclusions = additionalExcludedDirectories
            .map { path ->
                if (path.isAbsolute) path.normalize() else root.resolve(path).normalize()
            }
            .toSet()
        val entries = scanDirectory(
            directory = root,
            visitedDirectories = visitedDirectories,
            issues = issues,
            excludedDirectories = normalizedExclusions
        )

        return ProjectScanResult(
            project = TeXProject(
                rootDirectory = root,
                entries = entries
            ),
            issues = issues
        )
    }

    fun resolveRoot(rootDirectory: Path): Path {
        val normalized = rootDirectory.toAbsolutePath().normalize()
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw ProjectScanException("The selected project folder does not exist.")
        }

        val realRoot = try {
            normalized.toRealPath()
        } catch (error: IOException) {
            throw ProjectScanException("The selected project folder cannot be accessed.", error)
        } catch (error: SecurityException) {
            throw ProjectScanException("Access to the selected project folder was denied.", error)
        }

        if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw ProjectScanException("The selected path is not a directory.")
        }
        if (!Files.isReadable(realRoot)) {
            throw ProjectScanException("The selected project folder is not readable.")
        }

        return realRoot
    }

    private fun scanDirectory(
        directory: Path,
        visitedDirectories: MutableSet<Path>,
        issues: MutableList<ProjectScanIssue>,
        excludedDirectories: Set<Path>
    ): List<ProjectEntry> {
        val identity = try {
            directory.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            issues += ProjectScanIssue(
                path = directory,
                message = "Could not resolve this directory.",
                technicalDetails = error.message
            )
            return emptyList()
        }

        if (!visitedDirectories.add(identity)) {
            issues += ProjectScanIssue(
                path = directory,
                message = "A directory cycle was skipped."
            )
            return emptyList()
        }

        val entries = try {
            Files.newDirectoryStream(directory).use { stream ->
                stream.mapNotNull { child ->
                    createEntry(child, visitedDirectories, issues, excludedDirectories)
                }
            }
        } catch (error: IOException) {
            issues += ProjectScanIssue(
                path = directory,
                message = "This directory could not be read.",
                technicalDetails = error.message
            )
            emptyList()
        } catch (error: SecurityException) {
            issues += ProjectScanIssue(
                path = directory,
                message = "Access to this directory was denied.",
                technicalDetails = error.message
            )
            emptyList()
        }

        return entries.sortedWith(ENTRY_COMPARATOR)
    }

    private fun createEntry(
        path: Path,
        visitedDirectories: MutableSet<Path>,
        issues: MutableList<ProjectScanIssue>,
        excludedDirectories: Set<Path>
    ): ProjectEntry? {
        val attributes = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        } catch (error: IOException) {
            issues += ProjectScanIssue(
                path = path,
                message = "This project entry could not be inspected.",
                technicalDetails = error.message
            )
            return null
        } catch (error: SecurityException) {
            issues += ProjectScanIssue(
                path = path,
                message = "Access to this project entry was denied.",
                technicalDetails = error.message
            )
            return null
        }

        val isSymbolicLink = attributes.isSymbolicLink
        val isDirectory = attributes.isDirectory ||
            (isSymbolicLink && Files.isDirectory(path))

        if (isDirectory) {
            val normalizedPath = path.toAbsolutePath().normalize()
            if (
                path.fileName.toString() in excludedDirectoryNames ||
                normalizedPath in excludedDirectories
            ) {
                return null
            }

            return ProjectDirectory(
                path = normalizedPath,
                children = if (isSymbolicLink) {
                    emptyList()
                } else {
                    scanDirectory(path, visitedDirectories, issues, excludedDirectories)
                },
                isSymbolicLink = isSymbolicLink
            )
        }

        return ProjectFile(
            path = path.toAbsolutePath().normalize(),
            isSymbolicLink = isSymbolicLink
        )
    }

    companion object {
        val DEFAULT_EXCLUDED_DIRECTORIES: Set<String> = setOf(
            ".aetex",
            ".git",
            ".gradle",
            "build",
            "out"
        )

        private val ENTRY_COMPARATOR =
            compareBy<ProjectEntry>(
                { if (it is ProjectDirectory) 0 else 1 },
                { it.name.lowercase(Locale.ROOT) },
                { it.name }
            )
    }
}
