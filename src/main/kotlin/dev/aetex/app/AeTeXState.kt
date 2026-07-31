package dev.aetex.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import dev.aetex.compilation.BuildFailure
import dev.aetex.compilation.BuildFailureKind
import dev.aetex.compilation.BuildRequestResult
import dev.aetex.compilation.BuildSessionId
import dev.aetex.compilation.BuildSessionSnapshot
import dev.aetex.compilation.BuildState
import dev.aetex.compilation.CancellationOrigin
import dev.aetex.compilation.CancellationRequestResult
import dev.aetex.compilation.CompilationManager
import dev.aetex.compilation.RuntimeStorageException
import dev.aetex.compilation.userSummary
import dev.aetex.editor.DocumentError
import dev.aetex.editor.DocumentResult
import dev.aetex.editor.DocumentService
import dev.aetex.editor.EditableFileTypes
import dev.aetex.editor.OpenDocument
import dev.aetex.project.ProjectLoader
import dev.aetex.project.CreateProjectRequest
import dev.aetex.project.OpenedDirectoryKind
import dev.aetex.project.ProjectCreationResult
import dev.aetex.project.ProjectInitializationPlan
import dev.aetex.project.ProjectInitializationPlanResult
import dev.aetex.project.ProjectInitializationResult
import dev.aetex.project.ProjectLoadResult
import dev.aetex.project.ProjectProvisioningError
import dev.aetex.project.ProjectProvisioningService
import dev.aetex.project.ProjectScanException
import dev.aetex.project.ProjectScanIssue
import dev.aetex.project.TeXProject
import dev.aetex.project.configuration.ProjectConfigurationDiagnostic
import dev.aetex.project.configuration.ProjectConfigurationDiagnosticSeverity
import dev.aetex.preview.coordination.PreviewManager
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.domain.RenderScale
import dev.aetex.workspace.ResolvedWorkspaceLayout
import dev.aetex.workspace.WorkspaceLayout
import dev.aetex.workspace.WorkspacePanel
import dev.aetex.workspace.WorkspacePreferencesCoordinator
import dev.aetex.workspace.WorkspaceTool
import dev.aetex.workspace.transientWorkspacePreferencesCoordinator
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

@JvmInline
value class ProjectOperationToken internal constructor(val value: Long)

sealed interface ProjectTransitionPreparation {
    data class Ready(
        val loadResult: ProjectLoadResult,
        val initialDocument: Path? = null,
        val successMessage: String? = null
    ) : ProjectTransitionPreparation

    data class Failed(
        val userMessage: String,
        val cause: Throwable? = null
    ) : ProjectTransitionPreparation
}

class AeTeXState(
    private val projectLoader: ProjectLoader = ProjectLoader(),
    private val projectProvisioningService: ProjectProvisioningService =
        ProjectProvisioningService(projectLoader = projectLoader),
    private val documentServiceFactory: (Path) -> DocumentService = ::DocumentService,
    private val compilationManagerFactory: () -> CompilationManager = ::CompilationManager,
    private val previewManagerFactory: (CompilationManager, Path) -> PreviewManager =
        { manager, root -> PreviewManager(manager, root) },
    private val workspacePreferences: WorkspacePreferencesCoordinator =
        transientWorkspacePreferencesCoordinator()
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
    private val buildFeedbackLock = Any()
    private var buildFeedbackSubscription: AutoCloseable? = null
    private var buildFeedbackManager: CompilationManager? = null
    private var latestBuildFeedbackSequence = 0L
    private val previewStateLock = Any()
    private var previewManager: PreviewManager? = null
    private var previewSubscription: AutoCloseable? = null
    private val retiringCompilationManagers = CopyOnWriteArrayList<CompletableFuture<Void>>()
    private val retiringPreviewManagers = CopyOnWriteArrayList<CompletableFuture<Void>>()
    private val projectOperationLock = Any()
    private var nextProjectOperation = 0L
    private var currentProjectOperation = 0L

    var previewState: PreviewState by mutableStateOf(PreviewState.Empty)
        private set

    var workspaceLayout: WorkspaceLayout by mutableStateOf(
        workspacePreferences.initialLayout
    )
        private set

    val activeDocument: OpenDocument?
        get() = activeDocumentPath?.let { activePath ->
            openDocuments.firstOrNull { it.path == activePath }
        }

    val modifiedDocuments: List<OpenDocument>
        get() = openDocuments.filter(OpenDocument::isModified)

    val buildUnavailableReason: String?
        get() {
            val currentProject = project ?: return "Open or create an AeTeX project to build."
            return when (val kind = currentProject.directoryKind) {
                is OpenedDirectoryKind.Unconfigured ->
                    "Initialize this folder to create .aetex/project.toml before building."

                is OpenedDirectoryKind.InvalidProject ->
                    kind.diagnostics.firstOrNull()?.message
                        ?: "The project configuration is invalid."

                is OpenedDirectoryKind.Configured ->
                    if (currentProject.isBuildable) {
                        null
                    } else {
                        currentProject.configurationDiagnostics
                            .firstOrNull { it.severity == ProjectConfigurationDiagnosticSeverity.ERROR }
                            ?.message
                            ?: "Confirm a valid main document in .aetex/project.toml before building."
                    }
            }
        }

    fun openProject(
        rootDirectory: Path,
        discardModifiedDocuments: Boolean = false
    ): Boolean {
        val token = beginProjectOperation(discardModifiedDocuments) ?: return false
        return completeProjectOperation(token, prepareOpenProject(rootDirectory))
    }

    fun beginProjectOperation(
        discardModifiedDocuments: Boolean = false
    ): ProjectOperationToken? {
        if (modifiedDocuments.isNotEmpty() && !discardModifiedDocuments) {
            showError("Save or discard modified documents before replacing the project.")
            return null
        }
        return synchronized(projectOperationLock) {
            ProjectOperationToken(++nextProjectOperation).also {
                currentProjectOperation = it.value
            }
        }
    }

    fun prepareOpenProject(rootDirectory: Path): ProjectTransitionPreparation =
        try {
            val loadResult = projectLoader.load(rootDirectory)
            ProjectTransitionPreparation.Ready(
                loadResult = loadResult,
                initialDocument = loadResult.project.mainDocument
            )
        } catch (error: ProjectScanException) {
            ProjectTransitionPreparation.Failed(error.userMessage, error)
        } catch (error: Exception) {
            ProjectTransitionPreparation.Failed(
                "The selected project could not be opened.",
                error
            )
        }

    fun isCurrentProjectOperation(token: ProjectOperationToken): Boolean =
        synchronized(projectOperationLock) {
            currentProjectOperation == token.value
        }

    fun cancelProjectOperation(token: ProjectOperationToken): Boolean =
        synchronized(projectOperationLock) {
            if (currentProjectOperation != token.value) {
                false
            } else {
                currentProjectOperation = ++nextProjectOperation
                true
            }
        }

    fun prepareProjectCreation(
        request: CreateProjectRequest
    ): ProjectTransitionPreparation =
        when (val result = projectProvisioningService.create(request)) {
            is ProjectCreationResult.Created ->
                ProjectTransitionPreparation.Ready(
                    loadResult = result.loadResult,
                    initialDocument = result.entryDocument,
                    successMessage = "Created and opened ${result.loadResult.project.rootDirectory.fileName}."
                )

            is ProjectCreationResult.Failed -> result.error.asPreparationFailure()
        }

    fun planProjectInitialization(
        rootDirectory: Path
    ): ProjectInitializationPlanResult =
        projectProvisioningService.planInitialization(rootDirectory)

    fun prepareProjectInitialization(
        plan: ProjectInitializationPlan
    ): ProjectTransitionPreparation =
        when (val result = projectProvisioningService.initialize(plan)) {
            is ProjectInitializationResult.Initialized ->
                ProjectTransitionPreparation.Ready(
                    loadResult = result.loadResult,
                    initialDocument = result.mainDocument,
                    successMessage = "Initialized this folder as an AeTeX project."
                )

            is ProjectInitializationResult.AlreadyConfigured ->
                ProjectTransitionPreparation.Failed(
                    "This folder is already configured as an AeTeX project."
                )

            is ProjectInitializationResult.Conflict -> result.error.asPreparationFailure()
            is ProjectInitializationResult.Failed -> result.error.asPreparationFailure()
        }

    fun completeProjectOperation(
        token: ProjectOperationToken,
        preparation: ProjectTransitionPreparation
    ): Boolean {
        if (synchronized(projectOperationLock) { currentProjectOperation != token.value }) {
            return false
        }
        return when (preparation) {
            is ProjectTransitionPreparation.Failed -> {
                preparation.cause?.let {
                    logTechnicalFailure("Project operation failed", it)
                }
                showError(preparation.userMessage)
                false
            }

            is ProjectTransitionPreparation.Ready ->
                publishLoadedProject(preparation)
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
        buildUnavailableReason?.let { reason ->
            showError(reason)
            return BuildRequestResult.Rejected(
                BuildFailure(BuildFailureKind.INVALID_CONFIGURATION, reason)
            )
        }
        val manager = compilationManager ?: try {
            compilationManagerFactory().also {
                compilationManager = it
            }
        } catch (error: RuntimeStorageException) {
            showError(error.failure.message)
            return BuildRequestResult.Rejected(error.failure)
        } catch (error: Exception) {
            val failure = BuildFailure(
                BuildFailureKind.INTERNAL_ERROR,
                "The compilation manager could not be initialized.",
                technicalCause = dev.aetex.compilation.TechnicalCause.from(error)
            )
            showError(failure.message)
            return BuildRequestResult.Rejected(failure)
        }
        ensureBuildFeedback(manager, currentProject.rootDirectory)
        ensurePreviewManager(manager, currentProject.rootDirectory)
        return manager.requestBuild(currentProject).also { result ->
            when (result) {
                is BuildRequestResult.Accepted -> {
                    synchronized(buildFeedbackLock) {
                        latestBuildFeedbackSequence = maxOf(
                            latestBuildFeedbackSequence,
                            result.session.requestSequence
                        )
                        val current = manager.observeSession(result.session.id)
                        Snapshot.withMutableSnapshot {
                            message = current?.result?.let {
                                UiMessage(it.userSummary(), it.state == BuildState.FAILED)
                            } ?: UiMessage("Build queued.", isError = false)
                        }
                    }
                }

                is BuildRequestResult.PlanningFailed -> {
                    showError(result.failure.message)
                }

                is BuildRequestResult.Rejected -> {
                    showError(result.failure.message)
                }
            }
        }
    }

    fun cancelBuild(
        sessionId: BuildSessionId,
        origin: CancellationOrigin = CancellationOrigin.USER
    ): CancellationRequestResult =
        compilationManager?.cancel(sessionId, origin)
            ?: CancellationRequestResult.UnknownSession

    fun observeBuild(sessionId: BuildSessionId): BuildSessionSnapshot? =
        compilationManager?.observeSession(sessionId)

    fun updatePreviewViewport(
        visiblePages: Set<Int>,
        currentPageIndex: Int,
        scale: RenderScale,
        scrollDirection: Int = 0
    ) {
        previewManager?.updateViewport(
            visiblePages,
            currentPageIndex,
            scale,
            scrollDirection
        )
    }

    fun retryPreviewPage(pageIndex: Int) {
        previewManager?.retryPage(pageIndex)
    }

    fun resolvedWorkspaceLayout(availableWidthDp: Double): ResolvedWorkspaceLayout =
        workspaceLayout.resolve(availableWidthDp)

    fun dragWorkspaceDivider(
        panel: WorkspacePanel,
        horizontalDeltaDp: Double,
        availableWidthDp: Double
    ) {
        updateWorkspaceLayout(
            workspaceLayout.dragDivider(panel, horizontalDeltaDp, availableWidthDp)
        )
    }

    fun collapseWorkspacePanel(panel: WorkspacePanel) {
        updateWorkspaceLayout(workspaceLayout.collapse(panel))
    }

    fun restoreWorkspacePanel(panel: WorkspacePanel, availableWidthDp: Double) {
        updateWorkspaceLayout(workspaceLayout.restore(panel, availableWidthDp))
    }

    fun activateWorkspaceTool(tool: WorkspaceTool, availableWidthDp: Double) {
        updateWorkspaceLayout(workspaceLayout.toggleTool(tool, availableWidthDp))
    }

    fun flushWorkspaceLayout(): Boolean =
        workspacePreferences.flush(workspaceLayout)

    private fun updateWorkspaceLayout(layout: WorkspaceLayout) {
        val normalized = layout.normalized()
        if (normalized == workspaceLayout) return
        workspaceLayout = normalized
        workspacePreferences.requestSave(normalized)
    }

    fun shutdown() {
        workspacePreferences.close(workspaceLayout)
        val preview = synchronized(previewStateLock) {
            val current = previewManager
            previewManager = null
            previewSubscription?.close()
            previewSubscription = null
            current
        }
        preview?.close()
        val manager = compilationManager
        compilationManager = null
        closeBuildFeedback()
        manager?.close()
        retiringCompilationManagers.toList().forEach {
            try {
                it.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Each retired manager already preserves a durable lease if cleanup is incomplete.
            }
        }
        retiringCompilationManagers.clear()
        retiringPreviewManagers.toList().forEach {
            try {
                it.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Preview workers are daemonized and late publication is already suppressed.
            }
        }
        retiringPreviewManagers.clear()
        synchronized(previewStateLock) {
            previewState = PreviewState.Closed
        }
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

    private fun logTechnicalFailure(message: String, error: Throwable) {
        LOGGER.log(Level.WARNING, message, error)
    }

    private fun ProjectProvisioningError.asPreparationFailure() =
        ProjectTransitionPreparation.Failed(
            userMessage = buildString {
                append(message)
                path?.let {
                    append(" Path: ")
                    append(it)
                    append('.')
                }
                diagnostics.firstOrNull()?.let {
                    append(' ')
                    append(it.message)
                }
            },
            cause = cause
        )

    private fun publishLoadedProject(
        preparation: ProjectTransitionPreparation.Ready
    ): Boolean {
        val loadResult = preparation.loadResult
        val service = try {
            documentServiceFactory(loadResult.project.rootDirectory)
        } catch (error: Exception) {
            logTechnicalFailure("Document service initialization failed", error)
            showError("The selected project could not be opened.")
            return false
        }

        val oldPreview = synchronized(previewStateLock) {
            previewSubscription?.close()
            previewSubscription = null
            val previous = previewManager
            previewManager = null
            previewState = PreviewState.Empty
            previous
        }
        oldPreview?.let(::retirePreviewManager)
        closeBuildFeedback()
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
        preparation.initialDocument?.let(::openDocument)
        preparation.successMessage?.let {
            message = UiMessage(it, isError = false)
        }
        return true
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

    private fun ensureBuildFeedback(manager: CompilationManager, root: Path) {
        synchronized(buildFeedbackLock) {
            if (buildFeedbackManager === manager) return
            buildFeedbackSubscription?.close()
            buildFeedbackSubscription = null
            buildFeedbackManager = manager
            latestBuildFeedbackSequence = 0L
            buildFeedbackSubscription = manager.addSessionListener { snapshot ->
                acceptBuildFeedback(manager, root, snapshot)
            }
        }
    }

    private fun acceptBuildFeedback(
        manager: CompilationManager,
        root: Path,
        snapshot: BuildSessionSnapshot
    ) {
        synchronized(buildFeedbackLock) {
            if (
                buildFeedbackManager !== manager ||
                compilationManager !== manager ||
                project?.rootDirectory != root ||
                snapshot.requestSequence < latestBuildFeedbackSequence
            ) {
                return
            }
            latestBuildFeedbackSequence = snapshot.requestSequence
            if (!snapshot.state.isTerminal) return
            val result = snapshot.result ?: return
            if (
                result.sessionId != snapshot.id ||
                result.state != snapshot.state ||
                result.plan !== snapshot.plan
            ) {
                return
            }
            Snapshot.withMutableSnapshot {
                message = UiMessage(
                    text = result.userSummary(),
                    isError = result.state == BuildState.FAILED
                )
            }
        }
    }

    private fun closeBuildFeedback() {
        synchronized(buildFeedbackLock) {
            buildFeedbackSubscription?.close()
            buildFeedbackSubscription = null
            buildFeedbackManager = null
            latestBuildFeedbackSequence = 0L
        }
    }

    private fun ensurePreviewManager(manager: CompilationManager, root: Path) {
        synchronized(previewStateLock) {
            if (previewManager != null) return
        }
        var createdPreview: PreviewManager? = null
        try {
            val preview = previewManagerFactory(manager, root)
            createdPreview = preview
            synchronized(previewStateLock) {
                if (previewManager != null) {
                    preview.close()
                    return
                }
                previewManager = preview
            }
            val subscription = preview.addStateListener { state ->
                synchronized(previewStateLock) {
                    if (previewManager !== preview) return@addStateListener
                    Snapshot.withMutableSnapshot {
                        previewState = state
                    }
                }
            }
            synchronized(previewStateLock) {
                if (previewManager === preview) {
                    previewSubscription = subscription
                } else {
                    subscription.close()
                }
            }
        } catch (error: Exception) {
            val failedPreview = synchronized(previewStateLock) {
                val failed = createdPreview?.takeIf { previewManager === it }
                if (failed != null) {
                    previewManager = null
                    previewSubscription?.close()
                    previewSubscription = null
                }
                failed
            }
            failedPreview?.close()
            LOGGER.log(Level.WARNING, "PDF preview initialization failed.", error)
            synchronized(previewStateLock) {
                previewState = PreviewState.GenerationError(
                    PreviewError(
                        PreviewErrorKind.INTERNAL,
                        "PDF preview could not be initialized.",
                        technicalCause = error
                    )
                )
            }
        }
    }

    private fun retirePreviewManager(manager: PreviewManager) {
        val completion = CompletableFuture<Void>()
        retiringPreviewManagers += completion
        Thread.startVirtualThread {
            try {
                manager.close()
                completion.complete(null)
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            } finally {
                retiringPreviewManagers -= completion
            }
        }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(AeTeXState::class.java.name)
    }
}
