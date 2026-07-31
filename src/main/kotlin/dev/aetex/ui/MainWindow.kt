package dev.aetex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.aetex.app.AeTeXState
import dev.aetex.app.ProjectTransitionPreparation
import dev.aetex.project.CreateProjectRequest
import dev.aetex.project.ProjectInitializationPlan
import dev.aetex.project.ProjectInitializationPlanResult
import dev.aetex.project.chooseProjectDirectory
import dev.aetex.project.chooseProjectParentDirectory
import dev.aetex.ui.panels.EditorPanel
import dev.aetex.ui.panels.PreviewPanel
import dev.aetex.ui.panels.ProjectPanel
import java.nio.file.Path
import java.nio.file.InvalidPathException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PendingProjectAction {
    data class Open(val path: Path) : PendingProjectAction
    data class Create(val request: CreateProjectRequest) : PendingProjectAction
    data class Initialize(val plan: ProjectInitializationPlan) : PendingProjectAction
}

@Composable
fun MainWindow(state: AeTeXState) {
    val scope = rememberCoroutineScope()
    var pendingProjectAction by remember { mutableStateOf<PendingProjectAction?>(null) }
    var documentWaitingToClose by remember { mutableStateOf<Path?>(null) }
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var createProjectError by remember { mutableStateOf<String?>(null) }
    var initializationDialogOpen by remember { mutableStateOf(false) }
    var initializationPlan by remember { mutableStateOf<ProjectInitializationPlan?>(null) }
    var initializationError by remember { mutableStateOf<String?>(null) }
    var initializationPlanRequest by remember { mutableStateOf(0L) }
    var projectOperationInProgress by remember { mutableStateOf(false) }

    fun executeProjectAction(
        action: PendingProjectAction,
        discardModifiedDocuments: Boolean = false
    ) {
        val token = state.beginProjectOperation(discardModifiedDocuments) ?: return
        projectOperationInProgress = true
        scope.launch {
            val preparation = withContext(Dispatchers.IO) {
                when (action) {
                    is PendingProjectAction.Open -> state.prepareOpenProject(action.path)
                    is PendingProjectAction.Create ->
                        state.prepareProjectCreation(action.request)

                    is PendingProjectAction.Initialize ->
                        state.prepareProjectInitialization(action.plan)
                }
            }
            val isCurrent = state.isCurrentProjectOperation(token)
            val published = state.completeProjectOperation(token, preparation)
            if (!isCurrent) {
                return@launch
            }
            projectOperationInProgress = false
            if (preparation is ProjectTransitionPreparation.Failed) {
                when (action) {
                    is PendingProjectAction.Create ->
                        createProjectError = preparation.userMessage

                    is PendingProjectAction.Initialize ->
                        initializationError = preparation.userMessage

                    is PendingProjectAction.Open -> Unit
                }
            } else if (published) {
                when (action) {
                    is PendingProjectAction.Create -> showCreateProjectDialog = false
                    is PendingProjectAction.Initialize -> initializationDialogOpen = false
                    is PendingProjectAction.Open -> Unit
                }
            }
        }
    }

    fun requestProjectAction(action: PendingProjectAction) {
        if (state.modifiedDocuments.isEmpty()) {
            executeProjectAction(action)
        } else {
            pendingProjectAction = action
        }
    }

    fun beginInitialization() {
        val root = state.project?.rootDirectory ?: return
        val request = initializationPlanRequest + 1
        initializationPlanRequest = request
        initializationDialogOpen = true
        initializationPlan = null
        initializationError = null
        scope.launch {
            when (val result = withContext(Dispatchers.IO) {
                state.planProjectInitialization(root)
            }) {
                is ProjectInitializationPlanResult.Ready -> {
                    if (
                        initializationDialogOpen &&
                        initializationPlanRequest == request &&
                        state.project?.rootDirectory == root
                    ) {
                        initializationPlan = result.plan
                    }
                }

                is ProjectInitializationPlanResult.AlreadyConfigured ->
                    if (initializationPlanRequest == request) {
                        initializationError =
                            "This folder is already configured as an AeTeX project."
                    }

                is ProjectInitializationPlanResult.Conflict ->
                    if (initializationPlanRequest == request) {
                        initializationError =
                            result.error.message + " Path: ${result.error.path}."
                    }

                is ProjectInitializationPlanResult.Failed ->
                    if (initializationPlanRequest == request) {
                        initializationError =
                            result.error.message + " Path: ${result.error.path}."
                    }
            }
        }
    }

    fun requestDocumentClose(path: Path) {
        val document = state.openDocuments.firstOrNull { it.path == path }
        if (document?.isModified == true) {
            documentWaitingToClose = path
        } else {
            state.closeDocument(path)
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        event.key == Key.S &&
                        (event.isCtrlPressed || event.isMetaPressed)
                    ) {
                        state.saveActiveDocument()
                        true
                    } else {
                        false
                    }
                }
        ) {
            EditorToolbar(
                canSave = state.activeDocument != null,
                canBuild = state.project?.isBuildable == true,
                buildUnavailableReason = state.buildUnavailableReason,
                onSave = state::saveActiveDocument,
                onBuild = { state.requestBuild() }
            )

            state.message?.let { message ->
                MessageBanner(
                    text = message.text,
                    isError = message.isError,
                    onDismiss = state::dismissMessage
                )
            }

            Row(modifier = Modifier.weight(1f)) {
                ProjectPanel(
                    project = state.project,
                    activeDocumentPath = state.activeDocumentPath,
                    onCreateProject = {
                        createProjectError = null
                        showCreateProjectDialog = true
                    },
                    onOpenProject = {
                        chooseProjectDirectory()?.let {
                            requestProjectAction(PendingProjectAction.Open(it))
                        }
                    },
                    onInitializeProject = ::beginInitialization,
                    onFileSelected = state::openDocument
                )

                EditorPanel(
                    documents = state.openDocuments,
                    activeDocument = state.activeDocument,
                    onDocumentActivated = state::activateDocument,
                    onDocumentChanged = state::updateDocument,
                    onDocumentCloseRequested = ::requestDocumentClose,
                    modifier = Modifier.weight(1f)
                )

                PreviewPanel(
                    state = state.previewState,
                    onViewportChanged = state::updatePreviewViewport,
                    onRetryPage = state::retryPreviewPage
                )
            }
        }

        pendingProjectAction?.let { pendingAction ->
            UnsavedChangesDialog(
                title = "Unsaved changes",
                message = "Save all modified documents before replacing the current project?",
                onSave = {
                    if (state.saveAllModifiedDocuments()) {
                        pendingProjectAction = null
                        executeProjectAction(pendingAction)
                    } else {
                        pendingProjectAction = null
                    }
                },
                onDiscard = {
                    pendingProjectAction = null
                    executeProjectAction(pendingAction, discardModifiedDocuments = true)
                },
                onCancel = { pendingProjectAction = null }
            )
        }

        documentWaitingToClose?.let { pendingPath ->
            UnsavedChangesDialog(
                title = "Unsaved changes",
                message = "Save changes to ${pendingPath.fileName} before closing it?",
                onSave = {
                    if (state.saveDocument(pendingPath)) {
                        state.closeDocument(pendingPath)
                    }
                    documentWaitingToClose = null
                },
                onDiscard = {
                    state.discardAndCloseDocument(pendingPath)
                    documentWaitingToClose = null
                },
                onCancel = { documentWaitingToClose = null }
            )
        }

        if (showCreateProjectDialog) {
            NewProjectDialog(
                initialParentLocation = state.project?.rootDirectory?.parent?.toString()
                    ?: System.getProperty("user.home", ""),
                error = createProjectError,
                inProgress = projectOperationInProgress,
                onBrowse = { current ->
                    chooseProjectParentDirectory(current)?.toString()
                },
                onCreate = { name, parent ->
                    createProjectError = null
                    val parentPath = try {
                        Path.of(parent)
                    } catch (_: InvalidPathException) {
                        createProjectError = "Enter a valid parent location."
                        return@NewProjectDialog
                    }
                    requestProjectAction(
                        PendingProjectAction.Create(CreateProjectRequest(name, parentPath))
                    )
                },
                onCancel = {
                    if (!projectOperationInProgress) {
                        showCreateProjectDialog = false
                        createProjectError = null
                    }
                }
            )
        }

        if (initializationDialogOpen) {
            InitializeProjectDialog(
                plan = initializationPlan,
                error = initializationError,
                inProgress = projectOperationInProgress,
                onConfirm = {
                    initializationPlan?.let {
                        initializationError = null
                        requestProjectAction(PendingProjectAction.Initialize(it))
                    }
                },
                onCancel = {
                    if (!projectOperationInProgress) {
                        initializationDialogOpen = false
                        initializationPlanRequest += 1
                        initializationPlan = null
                        initializationError = null
                    }
                }
            )
        }
    }
}

@Composable
private fun EditorToolbar(
    canSave: Boolean,
    canBuild: Boolean,
    buildUnavailableReason: String?,
    onSave: () -> Unit,
    onBuild: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D30))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        buildUnavailableReason?.let {
            Text(
                text = it,
                color = Color(0xFFB7B7B7),
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = onBuild,
            enabled = canBuild
        ) {
            Text("Build")
        }
        Button(
            onClick = onSave,
            enabled = canSave
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun NewProjectDialog(
    initialParentLocation: String,
    error: String?,
    inProgress: Boolean,
    onBrowse: (Path?) -> String?,
    onCreate: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var parentLocation by remember(initialParentLocation) {
        mutableStateOf(initialParentLocation)
    }
    var validationError by remember { mutableStateOf<String?>(null) }
    val displayedError = error ?: validationError

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("New AeTeX Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Create a LaTeX project with src/main.tex and .aetex/project.toml.")
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text("Project name") },
                    enabled = !inProgress,
                    singleLine = true
                )
                OutlinedTextField(
                    value = parentLocation,
                    onValueChange = {
                        parentLocation = it
                        validationError = null
                    },
                    label = { Text("Parent location") },
                    enabled = !inProgress,
                    singleLine = true
                )
                TextButton(
                    onClick = {
                        val current = try {
                            parentLocation.takeIf(String::isNotBlank)?.let(Path::of)
                        } catch (_: InvalidPathException) {
                            null
                        }
                        onBrowse(current)?.let { parentLocation = it }
                    },
                    enabled = !inProgress
                ) {
                    Text("Browse...")
                }
                if (name.isNotBlank() && parentLocation.isNotBlank()) {
                    runCatching { Path.of(parentLocation).resolve(name) }
                        .getOrNull()
                        ?.let {
                            Text(
                                "Destination: $it",
                                color = Color(0xFF666666)
                            )
                        }
                }
                displayedError?.let {
                    Text(it, color = Color(0xFFB3261E))
                }
                if (inProgress) {
                    Text("Creating and validating project...")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        name.isBlank() ->
                            validationError = "Enter a project name."

                        parentLocation.isBlank() ->
                            validationError = "Choose a parent location."

                        else -> onCreate(name, parentLocation)
                    }
                },
                enabled = !inProgress
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !inProgress) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InitializeProjectDialog(
    plan: ProjectInitializationPlan?,
    error: String?,
    inProgress: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Initialize AeTeX Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    error != null -> Text(error, color = Color(0xFFB3261E))
                    plan == null -> Text("Inspecting this folder...")
                    else -> {
                        Text("AeTeX will create:")
                        plan.pathsToCreate.forEach { path ->
                            Text("• ${plan.rootDirectory.relativize(path)}")
                        }
                        if (plan.entryDocumentToCreate == null) {
                            Text(
                                "Existing main document reused: " +
                                    plan.rootDirectory.relativize(plan.mainDocument)
                            )
                        }
                        Text("No unrelated files will be modified or deleted.")
                    }
                }
                if (inProgress) {
                    Text("Initializing and validating project...")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = plan != null && error == null && !inProgress
            ) {
                Text("Initialize")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !inProgress) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MessageBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) Color(0xFF7A2929) else Color(0xFF245C36))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = Color.White)
        }
    }
}

@Composable
fun UnsavedChangesDialog(
    title: String,
    message: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Row {
                TextButton(onClick = onSave) {
                    Text("Save")
                }
                TextButton(onClick = onDiscard) {
                    Text("Discard")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
