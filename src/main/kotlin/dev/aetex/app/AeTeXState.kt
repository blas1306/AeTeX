package dev.aetex.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aetex.compilation.BuildFailure
import dev.aetex.compilation.BuildFailureKind
import dev.aetex.compilation.BuildRequestResult
import dev.aetex.compilation.BuildSessionId
import dev.aetex.compilation.BuildSessionSnapshot
import dev.aetex.compilation.CancellationOrigin
import dev.aetex.compilation.CancellationRequestResult
import dev.aetex.compilation.CompilationManager
import dev.aetex.compilation.RuntimeStorageException
import dev.aetex.editor.DocumentError
import dev.aetex.editor.DocumentResult
import dev.aetex.editor.DocumentService
import dev.aetex.editor.EditableFileTypes
import dev.aetex.editor.OpenDocument
import dev.aetex.project.ProjectLoader
import dev.aetex.project.ProjectScanException
import dev.aetex.project.ProjectScanIssue
import dev.aetex.project.TeXProject
import dev.aetex.project.configuration.ProjectConfigurationDiagnostic
import dev.aetex.project.configuration.ProjectConfigurationDiagnosticSeverity
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

data class UiMessage(
    val text: String,
    val isError: Boolean
)

class AeTeXState(
    private val projectLoader: ProjectLoader = ProjectLoader(),
    private val documentServiceFactory: (Path) -> DocumentService = ::DocumentService,
    private val compilationManagerFactory: () -> CompilationManager = ::CompilationManager
) {
    var project: TeXProject? by mutableStateOf(null)
        private set

    private val mutableOpenDocuments = mutableStateListOf<OpenDocument>()
    val openDocuments: List<OpenDocument> =
        Collections.unmodifiableList(mutableOpenDocuments)

    var activeDocumentPath: Path? by mutableStateOf(null)
        private set

    var message: UiMessage? by mutableStateOf(null)
        private set

    var projectScanIssues: List<ProjectScanIssue> by mutableStateOf(emptyList())
        private set

    var configurationDiagnostics: List<ProjectConfigurationDiagnostic> by
        mutableStateOf(emptyList())
        private set

    private var documentService: DocumentService? = null
    private var compilationManager: CompilationManager? = null
    private val retiringCompilationManagers = CopyOnWriteArrayList<CompletableFuture<Void>>()

    val activeDocument: OpenDocument?
        get() = activeDocumentPath?.let { activePath ->
            openDocuments.firstOrNull { it.path == activePath }
        }

    val modifiedDocuments: List<OpenDocument>
        get() = openDocuments.filter(OpenDocument::isModified)

    fun openProject(
        rootDirectory: Path,
        discardModifiedDocuments: Boolean = false
    ): Boolean {
        if (modifiedDocuments.isNotEmpty() && !discardModifiedDocuments) {
            showError("Save or discard modified documents before replacing the project.")
            return false
        }

        return try {
            val loadResult = projectLoader.load(rootDirectory)
            val service = documentServiceFactory(loadResult.project.rootDirectory)

            compilationManager?.let(::retireCompilationManager)
            compilationManager = null
            project = loadResult.project
            projectScanIssues = loadResult.scanIssues
            configurationDiagnostics = loadResult.project.configurationDiagnostics
            documentService = service
            mutableOpenDocuments.clear()
            activeDocumentPath = null
            loadResult.scanIssues.forEach { issue ->
                LOGGER.log(
                    Level.WARNING,
                    "Project scan issue at ${issue.path}: ${issue.message}" +
                        issue.technicalDetails?.let { " ($it)" }.orEmpty()
                )
            }
            configurationDiagnostics.forEach { diagnostic ->
                LOGGER.log(
                    if (diagnostic.severity == ProjectConfigurationDiagnosticSeverity.ERROR) {
                        Level.WARNING
                    } else {
                        Level.INFO
                    },
                    buildString {
                        append("${diagnostic.code}: ${diagnostic.message}")
                        diagnostic.technicalDetails?.let { append(" ($it)") }
                    },
                    diagnostic.cause
                )
            }
            message = projectMessage(loadResult.scanIssues, configurationDiagnostics)
            true
        } catch (error: ProjectScanException) {
            logTechnicalFailure("Project scan failed", error)
            showError(error.userMessage)
            false
        } catch (error: Exception) {
            logTechnicalFailure("Project initialization failed", error)
            showError("The selected project could not be opened.")
            false
        }
    }

    fun openDocument(path: Path): Boolean {
        val normalizedPath = path.toAbsolutePath().normalize()
        openDocuments.firstOrNull { it.path == normalizedPath }?.let {
            activeDocumentPath = it.path
            return true
        }

        if (isProjectMetadataOrOutput(normalizedPath)) {
            showError("Project metadata and generated output are not editable documents.")
            return false
        }

        if (!EditableFileTypes.isEditable(normalizedPath)) {
            showError("This file type is not editable in AeTeX.")
            return false
        }

        val service = documentService
        if (service == null) {
            showError("Open a project before opening a document.")
            return false
        }

        return when (val result = service.open(normalizedPath)) {
            is DocumentResult.Success -> {
                mutableOpenDocuments += result.value
                activeDocumentPath = result.value.path
                message = null
                true
            }

            is DocumentResult.Failure -> {
                reportDocumentError(result.error)
                false
            }
        }
    }

    fun activateDocument(path: Path) {
        val normalizedPath = path.toAbsolutePath().normalize()
        if (openDocuments.any { it.path == normalizedPath }) {
            activeDocumentPath = normalizedPath
        }
    }

    fun updateDocument(path: Path, content: String) {
        val normalizedPath = path.toAbsolutePath().normalize()
        val index = openDocuments.indexOfFirst { it.path == normalizedPath }
        if (index >= 0 && openDocuments[index].content != content) {
            mutableOpenDocuments[index] = openDocuments[index].withContent(content)
        }
    }

    fun saveActiveDocument(): Boolean {
        val path = activeDocumentPath ?: run {
            showError("There is no active document to save.")
            return false
        }
        return saveDocument(path)
    }

    fun saveDocument(path: Path): Boolean {
        val normalizedPath = path.toAbsolutePath().normalize()
        val index = openDocuments.indexOfFirst { it.path == normalizedPath }
        if (index < 0) {
            showError("The document is no longer open.")
            return false
        }

        val service = documentService
        if (service == null) {
            showError("There is no open project.")
            return false
        }

        return when (val result = service.save(openDocuments[index])) {
            is DocumentResult.Success -> {
                mutableOpenDocuments[index] = result.value
                message = UiMessage(
                    text = "Saved ${result.value.path.fileName}.",
                    isError = false
                )
                true
            }

            is DocumentResult.Failure -> {
                mutableOpenDocuments[index] = openDocuments[index].copy(error = result.error)
                reportDocumentError(result.error)
                false
            }
        }
    }

    fun saveAllModifiedDocuments(): Boolean {
        val pathsToSave = modifiedDocuments.map(OpenDocument::path)
        pathsToSave.forEach { path ->
            if (!saveDocument(path)) {
                return false
            }
        }
        return true
    }

    fun requestBuild(): BuildRequestResult {
        val currentProject = project ?: return BuildRequestResult.Rejected(
            BuildFailure(
                BuildFailureKind.INVALID_CONFIGURATION,
                "Open a project before requesting compilation."
            )
        )
        val manager = compilationManager ?: try {
            compilationManagerFactory().also {
                compilationManager = it
            }
        } catch (error: RuntimeStorageException) {
            return BuildRequestResult.Rejected(error.failure)
        } catch (error: Exception) {
            return BuildRequestResult.Rejected(
                BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "The compilation manager could not be initialized.",
                    technicalCause = dev.aetex.compilation.TechnicalCause.from(error)
                )
            )
        }
        return manager.requestBuild(currentProject)
    }

    fun cancelBuild(
        sessionId: BuildSessionId,
        origin: CancellationOrigin = CancellationOrigin.USER
    ): CancellationRequestResult =
        compilationManager?.cancel(sessionId, origin)
            ?: CancellationRequestResult.UnknownSession

    fun observeBuild(sessionId: BuildSessionId): BuildSessionSnapshot? =
        compilationManager?.observeSession(sessionId)

    fun shutdown() {
        val manager = compilationManager
        compilationManager = null
        manager?.close()
        retiringCompilationManagers.toList().forEach {
            try {
                it.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Each retired manager already preserves a durable lease if cleanup is incomplete.
            }
        }
        retiringCompilationManagers.clear()
    }

    fun closeDocument(path: Path): Boolean {
        val normalizedPath = path.toAbsolutePath().normalize()
        val document = openDocuments.firstOrNull { it.path == normalizedPath }
            ?: return true
        if (document.isModified) {
            showError("Save or discard changes before closing ${document.path.fileName}.")
            return false
        }

        removeDocument(normalizedPath)
        return true
    }

    fun discardAndCloseDocument(path: Path) {
        val normalizedPath = path.toAbsolutePath().normalize()
        removeDocument(normalizedPath)
    }

    private fun removeDocument(normalizedPath: Path) {
        val index = openDocuments.indexOfFirst { it.path == normalizedPath }
        if (index < 0) {
            return
        }

        val wasActive = activeDocumentPath == normalizedPath
        mutableOpenDocuments.removeAt(index)
        if (wasActive) {
            activeDocumentPath = openDocuments.getOrNull(index)?.path
                ?: openDocuments.getOrNull(index - 1)?.path
        }
    }

    fun dismissMessage() {
        message = null
    }

    private fun reportDocumentError(error: DocumentError) {
        if (error.technicalDetails != null) {
            LOGGER.log(
                Level.WARNING,
                "${error.operation}: ${error.userMessage} (${error.technicalDetails})"
            )
        }
        showError(error.userMessage)
    }

    private fun showError(text: String) {
        message = UiMessage(text = text, isError = true)
    }

    private fun logTechnicalFailure(message: String, error: Exception) {
        LOGGER.log(Level.WARNING, message, error)
    }

    private fun projectMessage(
        scanIssues: List<ProjectScanIssue>,
        diagnostics: List<ProjectConfigurationDiagnostic>
    ): UiMessage? {
        diagnostics.firstOrNull {
            it.severity == ProjectConfigurationDiagnosticSeverity.ERROR
        }?.let { diagnostic ->
            return UiMessage(text = diagnostic.message, isError = true)
        }
        if (scanIssues.isNotEmpty()) {
            return UiMessage(
                text = "Project opened with ${scanIssues.size} unreadable " +
                    "or skipped ${if (scanIssues.size == 1) "entry" else "entries"}.",
                isError = true
            )
        }
        return diagnostics.firstOrNull()?.let { diagnostic ->
            UiMessage(text = diagnostic.message, isError = false)
        }
    }

    private fun isProjectMetadataOrOutput(path: Path): Boolean {
        val currentProject = project ?: return false
        val metadataDirectory = currentProject.rootDirectory.resolve(".aetex")
        if (path.startsWith(metadataDirectory)) {
            return true
        }
        val outputDirectory =
            currentProject.effectiveConfiguration.outputDirectory?.value ?: return false
        return path.startsWith(outputDirectory)
    }

    private fun retireCompilationManager(manager: CompilationManager) {
        val completion = CompletableFuture<Void>()
        retiringCompilationManagers += completion
        Thread.startVirtualThread {
            try {
                manager.close()
                completion.complete(null)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            } finally {
                retiringCompilationManagers -= completion
            }
        }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(AeTeXState::class.java.name)
    }
}
