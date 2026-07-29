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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.aetex.project.chooseProjectDirectory
import dev.aetex.ui.panels.EditorPanel
import dev.aetex.ui.panels.PreviewPanel
import dev.aetex.ui.panels.ProjectPanel
import java.nio.file.Path

@Composable
fun MainWindow(state: AeTeXState) {
    var projectWaitingToOpen by remember { mutableStateOf<Path?>(null) }
    var documentWaitingToClose by remember { mutableStateOf<Path?>(null) }

    fun openSelectedProject(path: Path) {
        if (state.modifiedDocuments.isEmpty()) {
            state.openProject(path)
        } else {
            projectWaitingToOpen = path
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
                onSave = state::saveActiveDocument
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
                    onOpenProject = {
                        chooseProjectDirectory()?.let(::openSelectedProject)
                    },
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

                PreviewPanel()
            }
        }

        projectWaitingToOpen?.let { pendingPath ->
            UnsavedChangesDialog(
                title = "Unsaved changes",
                message = "Save all modified documents before opening another project?",
                onSave = {
                    if (state.saveAllModifiedDocuments()) {
                        projectWaitingToOpen = null
                        state.openProject(pendingPath)
                    } else {
                        projectWaitingToOpen = null
                    }
                },
                onDiscard = {
                    projectWaitingToOpen = null
                    state.openProject(
                        rootDirectory = pendingPath,
                        discardModifiedDocuments = true
                    )
                },
                onCancel = { projectWaitingToOpen = null }
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
    }
}

@Composable
private fun EditorToolbar(
    canSave: Boolean,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D30))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onSave,
            enabled = canSave
        ) {
            Text("Save")
        }
    }
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
