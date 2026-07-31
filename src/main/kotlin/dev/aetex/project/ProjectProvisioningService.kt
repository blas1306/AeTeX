package dev.aetex.project

import dev.aetex.project.configuration.AtomicNewProjectFileWriter
import dev.aetex.project.configuration.CURRENT_PROJECT_CONFIGURATION_SCHEMA
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.NewProjectFileWriter
import dev.aetex.project.configuration.ProjectConfiguration
import dev.aetex.project.configuration.ProjectConfigurationDiagnostic
import dev.aetex.project.configuration.ProjectConfigurationLoadResult
import dev.aetex.project.configuration.ProjectConfigurationLoader
import dev.aetex.project.configuration.ProjectConfigurationWriter
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

data class CreateProjectRequest(
    val name: String,
    val parentDirectory: Path
)

enum class ProjectProvisioningErrorKind {
    INVALID_PROJECT_NAME,
    PARENT_NOT_FOUND,
    PARENT_NOT_DIRECTORY,
    DESTINATION_COLLISION,
    DESTINATION_NOT_EMPTY,
    PROJECT_FILE_CONFLICT,
    INVALID_EXISTING_CONFIGURATION,
    UNSUPPORTED_EXISTING_CONFIGURATION,
    ACCESS_DENIED,
    FILESYSTEM_FAILURE,
    VALIDATION_FAILURE,
    STALE_INITIALIZATION_PLAN
}

data class ProjectProvisioningError(
    val kind: ProjectProvisioningErrorKind,
    val message: String,
    val path: Path? = null,
    val diagnostics: List<ProjectConfigurationDiagnostic> = emptyList(),
    val cause: Throwable? = null
)

sealed interface ProjectCreationResult {
    data class Created(
        val loadResult: ProjectLoadResult,
        val entryDocument: Path
    ) : ProjectCreationResult

    data class Failed(val error: ProjectProvisioningError) : ProjectCreationResult
}

class ProjectInitializationPlan internal constructor(
    val rootDirectory: Path,
    val configurationPath: Path,
    val mainDocument: Path,
    val relativeMainDocument: Path,
    val entryDocumentToCreate: Path?,
    val directoriesToCreate: List<Path>
) {
    val pathsToCreate: List<Path>
        get() = buildList {
            addAll(directoriesToCreate)
            entryDocumentToCreate?.let(::add)
            add(configurationPath)
        }
}

sealed interface ProjectInitializationPlanResult {
    data class Ready(val plan: ProjectInitializationPlan) : ProjectInitializationPlanResult
    data class AlreadyConfigured(val project: TeXProject) : ProjectInitializationPlanResult
    data class Conflict(val error: ProjectProvisioningError) : ProjectInitializationPlanResult
    data class Failed(val error: ProjectProvisioningError) : ProjectInitializationPlanResult
}

sealed interface ProjectInitializationResult {
    data class Initialized(
        val loadResult: ProjectLoadResult,
        val mainDocument: Path
    ) : ProjectInitializationResult

    data class AlreadyConfigured(val project: TeXProject) : ProjectInitializationResult
    data class Conflict(val error: ProjectProvisioningError) : ProjectInitializationResult
    data class Failed(val error: ProjectProvisioningError) : ProjectInitializationResult
}

class ProjectProvisioningService(
    private val configurationLoader: ProjectConfigurationLoader = ProjectConfigurationLoader(),
    private val projectLoader: ProjectLoader = ProjectLoader(configurationLoader = configurationLoader),
    private val fileWriter: NewProjectFileWriter = AtomicNewProjectFileWriter(),
    private val configurationWriter: ProjectConfigurationWriter =
        ProjectConfigurationWriter(fileWriter = fileWriter)
) {
    fun create(request: CreateProjectRequest): ProjectCreationResult {
        validateProjectName(request.name)?.let {
            return ProjectCreationResult.Failed(it)
        }

        val parent = resolveParent(request.parentDirectory)
            ?: return ProjectCreationResult.Failed(parentError(request.parentDirectory))
        val destination = parent.resolve(request.name).normalize()
        if (destination.parent != parent) {
            return ProjectCreationResult.Failed(
                error(
                    ProjectProvisioningErrorKind.INVALID_PROJECT_NAME,
                    "The project name must identify one directory.",
                    destination
                )
            )
        }

        val journal = CreationJournal()
        return try {
            prepareDestination(destination, journal)?.let {
                return ProjectCreationResult.Failed(it)
            }
            val realRoot = destination.toRealPath()
            val sourceDirectory = realRoot.resolve(DEFAULT_SOURCE_DIRECTORY)
            createDirectoryIfMissing(sourceDirectory, journal)
            val entry = sourceDirectory.resolve(DEFAULT_ENTRY_FILE)
            writeNew(entry, DEFAULT_ENTRY_CONTENT, journal)
            createDirectoryIfMissing(realRoot.resolve(".aetex"), journal)
            configurationWriter.writeNew(realRoot, generatedConfiguration(realRoot.relativize(entry)))
            journal.recordFile(realRoot.resolve(ProjectConfigurationLoader.CONFIGURATION_RELATIVE_PATH))

            val loaded = projectLoader.load(realRoot)
            if (!loaded.project.isBuildable) {
                throw GeneratedProjectValidationException(loaded.project.configurationDiagnostics)
            }
            ProjectCreationResult.Created(loaded, entry.toRealPath())
        } catch (error: GeneratedProjectValidationException) {
            journal.rollback()
            ProjectCreationResult.Failed(
                ProjectProvisioningError(
                    kind = ProjectProvisioningErrorKind.VALIDATION_FAILURE,
                    message = "The generated project did not pass AeTeX validation.",
                    path = destination,
                    diagnostics = error.diagnostics,
                    cause = error
                )
            )
        } catch (error: FileAlreadyExistsException) {
            journal.rollback()
            ProjectCreationResult.Failed(fileConflict(error.file?.let(Path::of) ?: destination, error))
        } catch (error: SecurityException) {
            journal.rollback()
            ProjectCreationResult.Failed(accessDenied(destination, error))
        } catch (error: AccessDeniedException) {
            journal.rollback()
            ProjectCreationResult.Failed(accessDenied(destination, error))
        } catch (error: IOException) {
            journal.rollback()
            ProjectCreationResult.Failed(filesystemFailure(destination, error))
        } catch (error: ProjectScanException) {
            journal.rollback()
            ProjectCreationResult.Failed(
                error(
                    ProjectProvisioningErrorKind.VALIDATION_FAILURE,
                    error.userMessage,
                    destination,
                    cause = error
                )
            )
        }
    }

    fun planInitialization(rootDirectory: Path): ProjectInitializationPlanResult {
        val loaded = try {
            projectLoader.load(rootDirectory)
        } catch (error: ProjectScanException) {
            return ProjectInitializationPlanResult.Failed(
                error(
                    ProjectProvisioningErrorKind.FILESYSTEM_FAILURE,
                    error.userMessage,
                    rootDirectory,
                    cause = error
                )
            )
        } catch (error: SecurityException) {
            return ProjectInitializationPlanResult.Failed(accessDenied(rootDirectory, error))
        } catch (error: IOException) {
            return ProjectInitializationPlanResult.Failed(filesystemFailure(rootDirectory, error))
        }

        when (val kind = loaded.project.directoryKind) {
            is OpenedDirectoryKind.Configured ->
                return ProjectInitializationPlanResult.AlreadyConfigured(loaded.project)

            is OpenedDirectoryKind.InvalidProject ->
                return ProjectInitializationPlanResult.Conflict(
                    invalidConfiguration(kind)
                )

            is OpenedDirectoryKind.Unconfigured -> Unit
        }

        val root = loaded.project.rootDirectory
        val existingMain = (loaded.project.mainDocumentState as? MainDocumentState.Provisional)?.path
        val entryToCreate = if (existingMain == null) {
            root.resolve(DEFAULT_SOURCE_DIRECTORY).resolve(DEFAULT_ENTRY_FILE)
        } else {
            null
        }
        if (entryToCreate != null && Files.exists(entryToCreate, LinkOption.NOFOLLOW_LINKS)) {
            return ProjectInitializationPlanResult.Conflict(
                fileConflict(entryToCreate)
            )
        }
        val main = existingMain ?: entryToCreate!!
        val directories = buildList {
            val metadata = root.resolve(".aetex")
            directoryCreationRequirement(metadata)?.let {
                if (it is DirectoryRequirement.Create) add(metadata)
                if (it is DirectoryRequirement.Conflict) {
                    return ProjectInitializationPlanResult.Conflict(fileConflict(metadata))
                }
            }
            if (entryToCreate != null) {
                val source = entryToCreate.parent
                directoryCreationRequirement(source)?.let {
                    if (it is DirectoryRequirement.Create) add(source)
                    if (it is DirectoryRequirement.Conflict) {
                        return ProjectInitializationPlanResult.Conflict(fileConflict(source))
                    }
                }
            }
        }
        return ProjectInitializationPlanResult.Ready(
            ProjectInitializationPlan(
                rootDirectory = root,
                configurationPath =
                    root.resolve(ProjectConfigurationLoader.CONFIGURATION_RELATIVE_PATH),
                mainDocument = main,
                relativeMainDocument = root.relativize(main),
                entryDocumentToCreate = entryToCreate,
                directoriesToCreate = directories
            )
        )
    }

    fun initialize(plan: ProjectInitializationPlan): ProjectInitializationResult {
        val root = try {
            plan.rootDirectory.toRealPath()
        } catch (error: IOException) {
            return ProjectInitializationResult.Failed(filesystemFailure(plan.rootDirectory, error))
        } catch (error: SecurityException) {
            return ProjectInitializationResult.Failed(accessDenied(plan.rootDirectory, error))
        }
        if (root != plan.rootDirectory) {
            return ProjectInitializationResult.Conflict(
                error(
                    ProjectProvisioningErrorKind.STALE_INITIALIZATION_PLAN,
                    "The selected directory changed after initialization was confirmed.",
                    plan.rootDirectory
                )
            )
        }
        if (!isValidPlan(root, plan)) {
            return ProjectInitializationResult.Conflict(
                error(
                    ProjectProvisioningErrorKind.STALE_INITIALIZATION_PLAN,
                    "The initialization plan is not valid for the selected directory.",
                    root
                )
            )
        }

        when (val current = configurationLoader.load(root)) {
            is ProjectConfigurationLoadResult.Loaded -> {
                val project = projectLoader.load(root).project
                return when (val kind = project.directoryKind) {
                    is OpenedDirectoryKind.Configured ->
                        ProjectInitializationResult.AlreadyConfigured(project)

                    is OpenedDirectoryKind.InvalidProject ->
                        ProjectInitializationResult.Conflict(invalidConfiguration(kind))

                    is OpenedDirectoryKind.Unconfigured ->
                        ProjectInitializationResult.Conflict(
                            error(
                                ProjectProvisioningErrorKind.STALE_INITIALIZATION_PLAN,
                                "The project configuration changed during initialization.",
                                current.configurationPath
                            )
                        )
                }
            }

            is ProjectConfigurationLoadResult.Invalid ->
                return ProjectInitializationResult.Conflict(
                    invalidConfiguration(current.configurationPath, current.diagnostics, false)
                )

            is ProjectConfigurationLoadResult.UnsupportedSchema ->
                return ProjectInitializationResult.Conflict(
                    invalidConfiguration(current.configurationPath, current.diagnostics, true)
                )

            is ProjectConfigurationLoadResult.Absent -> Unit
        }

        val journal = CreationJournal()
        return try {
            if (
                plan.entryDocumentToCreate == null &&
                (!Files.isRegularFile(plan.mainDocument, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(plan.mainDocument))
            ) {
                throw StaleInitializationPlanException(
                    "The selected existing main document changed before initialization.",
                    plan.mainDocument
                )
            }
            plan.directoriesToCreate.forEach { createDirectoryIfMissing(it, journal) }
            plan.entryDocumentToCreate?.let {
                writeNew(it, DEFAULT_ENTRY_CONTENT, journal)
            }
            configurationWriter.writeNew(root, generatedConfiguration(plan.relativeMainDocument))
            journal.recordFile(plan.configurationPath)

            val loaded = projectLoader.load(root)
            if (!loaded.project.isBuildable) {
                throw GeneratedProjectValidationException(loaded.project.configurationDiagnostics)
            }
            ProjectInitializationResult.Initialized(loaded, plan.mainDocument.toRealPath())
        } catch (error: GeneratedProjectValidationException) {
            journal.rollback()
            ProjectInitializationResult.Failed(
                ProjectProvisioningError(
                    kind = ProjectProvisioningErrorKind.VALIDATION_FAILURE,
                    message = "The initialized project did not pass AeTeX validation.",
                    path = root,
                    diagnostics = error.diagnostics,
                    cause = error
                )
            )
        } catch (error: FileAlreadyExistsException) {
            journal.rollback()
            ProjectInitializationResult.Conflict(
                fileConflict(error.file?.let(Path::of) ?: plan.configurationPath, error)
            )
        } catch (error: StaleInitializationPlanException) {
            journal.rollback()
            ProjectInitializationResult.Conflict(
                error(
                    ProjectProvisioningErrorKind.STALE_INITIALIZATION_PLAN,
                    error.message.orEmpty(),
                    error.path,
                    cause = error
                )
            )
        } catch (error: SecurityException) {
            journal.rollback()
            ProjectInitializationResult.Failed(accessDenied(root, error))
        } catch (error: AccessDeniedException) {
            journal.rollback()
            ProjectInitializationResult.Failed(accessDenied(root, error))
        } catch (error: IOException) {
            journal.rollback()
            ProjectInitializationResult.Failed(filesystemFailure(root, error))
        } catch (error: ProjectScanException) {
            journal.rollback()
            ProjectInitializationResult.Failed(
                error(
                    ProjectProvisioningErrorKind.VALIDATION_FAILURE,
                    error.userMessage,
                    root,
                    cause = error
                )
            )
        }
    }

    private fun generatedConfiguration(main: Path) = ProjectConfiguration(
        schema = CURRENT_PROJECT_CONFIGURATION_SCHEMA,
        main = main,
        engine = null,
        strategy = null,
        output = null
    )

    private fun isValidPlan(root: Path, plan: ProjectInitializationPlan): Boolean {
        val configurationPath =
            root.resolve(ProjectConfigurationLoader.CONFIGURATION_RELATIVE_PATH)
        if (plan.configurationPath != configurationPath) {
            return false
        }
        val relativeMain = plan.relativeMainDocument
        if (relativeMain.isAbsolute || relativeMain.toString().isBlank()) {
            return false
        }
        val resolvedMain = root.resolve(relativeMain).normalize()
        if (!resolvedMain.startsWith(root) || resolvedMain != plan.mainDocument.normalize()) {
            return false
        }
        val canonicalEntry = root.resolve(DEFAULT_SOURCE_DIRECTORY).resolve(DEFAULT_ENTRY_FILE)
        if (
            plan.entryDocumentToCreate != null &&
            (plan.entryDocumentToCreate != canonicalEntry ||
                plan.mainDocument != canonicalEntry)
        ) {
            return false
        }
        val permittedDirectories = setOf(root.resolve(".aetex"), canonicalEntry.parent)
        return plan.directoriesToCreate.all { it in permittedDirectories }
    }

    private fun validateProjectName(name: String): ProjectProvisioningError? {
        if (
            name.isBlank() ||
            name != name.trim() ||
            name == "." ||
            name == ".." ||
            '/' in name ||
            '\\' in name ||
            name.any { it.isISOControl() }
        ) {
            return error(
                ProjectProvisioningErrorKind.INVALID_PROJECT_NAME,
                "Enter a project name without path separators, leading or trailing spaces, or control characters."
            )
        }
        return try {
            val path = Path.of(name)
            if (path.isAbsolute || path.nameCount != 1) {
                error(
                    ProjectProvisioningErrorKind.INVALID_PROJECT_NAME,
                    "The project name must identify one directory."
                )
            } else {
                null
            }
        } catch (error: InvalidPathException) {
            error(
                ProjectProvisioningErrorKind.INVALID_PROJECT_NAME,
                "The project name is not valid on this filesystem.",
                cause = error
            )
        }
    }

    private fun resolveParent(path: Path): Path? = try {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            null
        } else {
            path.toRealPath().takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun parentError(path: Path): ProjectProvisioningError =
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            error(
                ProjectProvisioningErrorKind.PARENT_NOT_DIRECTORY,
                "The selected project location is not an accessible directory.",
                path
            )
        } else {
            error(
                ProjectProvisioningErrorKind.PARENT_NOT_FOUND,
                "The selected project location does not exist.",
                path
            )
        }

    private fun prepareDestination(
        destination: Path,
        journal: CreationJournal
    ): ProjectProvisioningError? {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(destination)
            journal.recordDirectory(destination)
            return null
        }
        if (Files.isSymbolicLink(destination) ||
            !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
        ) {
            return error(
                ProjectProvisioningErrorKind.DESTINATION_COLLISION,
                "A file or symbolic link already exists at the project destination.",
                destination
            )
        }
        Files.newDirectoryStream(destination).use { stream ->
            if (stream.iterator().hasNext()) {
                return error(
                    ProjectProvisioningErrorKind.DESTINATION_NOT_EMPTY,
                    "The project destination already exists and is not empty.",
                    destination
                )
            }
        }
        return null
    }

    private fun createDirectoryIfMissing(path: Path, journal: CreationJournal) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) ||
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                throw FileAlreadyExistsException(path.toString())
            }
            return
        }
        Files.createDirectory(path)
        journal.recordDirectory(path)
    }

    private fun writeNew(path: Path, content: String, journal: CreationJournal) {
        fileWriter.writeNew(path, content)
        journal.recordFile(path)
    }

    private fun directoryCreationRequirement(path: Path): DirectoryRequirement? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return DirectoryRequirement.Create
        }
        return if (
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
        ) {
            null
        } else {
            DirectoryRequirement.Conflict
        }
    }

    private fun invalidConfiguration(kind: OpenedDirectoryKind.InvalidProject) =
        invalidConfiguration(kind.configurationPath, kind.diagnostics, kind.unsupportedSchema)

    private fun invalidConfiguration(
        path: Path,
        diagnostics: List<ProjectConfigurationDiagnostic>,
        unsupported: Boolean
    ) = ProjectProvisioningError(
        kind = if (unsupported) {
            ProjectProvisioningErrorKind.UNSUPPORTED_EXISTING_CONFIGURATION
        } else {
            ProjectProvisioningErrorKind.INVALID_EXISTING_CONFIGURATION
        },
        message = if (unsupported) {
            "The existing project configuration uses an unsupported schema and was not changed."
        } else {
            "The existing project configuration is invalid and was not changed."
        },
        path = path,
        diagnostics = diagnostics
    )

    private fun fileConflict(path: Path, cause: Throwable? = null) = error(
        ProjectProvisioningErrorKind.PROJECT_FILE_CONFLICT,
        "AeTeX cannot create the required project file because that path already exists.",
        path,
        cause = cause
    )

    private fun accessDenied(path: Path, cause: Throwable) = error(
        ProjectProvisioningErrorKind.ACCESS_DENIED,
        "AeTeX does not have permission to create the project files at this location.",
        path,
        cause = cause
    )

    private fun filesystemFailure(path: Path, cause: Throwable) = error(
        ProjectProvisioningErrorKind.FILESYSTEM_FAILURE,
        "The project files could not be created. Check the location and available storage.",
        path,
        cause = cause
    )

    private fun error(
        kind: ProjectProvisioningErrorKind,
        message: String,
        path: Path? = null,
        diagnostics: List<ProjectConfigurationDiagnostic> = emptyList(),
        cause: Throwable? = null
    ) = ProjectProvisioningError(kind, message, path, diagnostics, cause)

    private sealed interface DirectoryRequirement {
        data object Create : DirectoryRequirement
        data object Conflict : DirectoryRequirement
    }

    private class GeneratedProjectValidationException(
        val diagnostics: List<ProjectConfigurationDiagnostic>
    ) : Exception()

    private class StaleInitializationPlanException(
        message: String,
        val path: Path
    ) : Exception(message)

    private class CreationJournal {
        private val files = mutableListOf<CreatedFile>()
        private val directories = mutableListOf<Path>()

        fun recordFile(path: Path) {
            val fileKey = try {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS
                ).fileKey()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            files.add(CreatedFile(path, fileKey))
        }

        fun recordDirectory(path: Path) {
            directories.add(path)
        }

        fun rollback() {
            files.asReversed().forEach { artifact ->
                try {
                    val currentKey = Files.readAttributes(
                        artifact.path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS
                    ).fileKey()
                    if (artifact.fileKey == null || artifact.fileKey == currentKey) {
                        Files.deleteIfExists(artifact.path)
                    }
                } catch (_: java.nio.file.NoSuchFileException) {
                    // The operation's artifact is already gone.
                } catch (_: IOException) {
                    // Best effort; only files created by this operation are targeted.
                } catch (_: SecurityException) {
                    // Best effort; the original failure remains the actionable result.
                }
            }
            directories.asReversed().forEach {
                try {
                    Files.deleteIfExists(it)
                } catch (_: DirectoryNotEmptyException) {
                    // Never remove content created independently during the operation.
                } catch (_: IOException) {
                    // Best effort and restricted to directories created by this operation.
                } catch (_: SecurityException) {
                    // Best effort; the original failure remains the actionable result.
                }
            }
        }

        private data class CreatedFile(
            val path: Path,
            val fileKey: Any?
        )
    }

    companion object {
        val DEFAULT_SOURCE_DIRECTORY: Path = Path.of("src")
        const val DEFAULT_ENTRY_FILE: String = "main.tex"
        val DEFAULT_ENTRY_CONTENT: String =
            """
            \documentclass{article}

            \begin{document}
            Welcome to AeTeX.
            \end{document}
            """.trimIndent() + "\n"
    }
}
