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
    fun scan(rootDirectory: Path): ProjectScanResult {
        val root = validateRoot(rootDirectory)
        val issues = mutableListOf<ProjectScanIssue>()
        val visitedDirectories = mutableSetOf<Path>()
        val entries = scanDirectory(root, visitedDirectories, issues)

        return ProjectScanResult(
            project = TeXProject(
                rootDirectory = root,
                entries = entries
            ),
            issues = issues
        )
    }

    private fun validateRoot(rootDirectory: Path): Path {
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
        issues: MutableList<ProjectScanIssue>
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
                    createEntry(child, visitedDirectories, issues)
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
        issues: MutableList<ProjectScanIssue>
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
            if (path.fileName.toString() in excludedDirectoryNames) {
                return null
            }

            return ProjectDirectory(
                path = path.toAbsolutePath().normalize(),
                children = if (isSymbolicLink) {
                    emptyList()
                } else {
                    scanDirectory(path, visitedDirectories, issues)
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
