package dev.aetex.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aetex.ui.MainWindow
import dev.aetex.ui.UnsavedChangesDialog

fun startAeTeX() = application {
    val state = remember { AeTeXState() }
    var confirmApplicationClose by remember { mutableStateOf(false) }
    var shutdownStarted by remember { mutableStateOf(false) }
    val closeApplication = {
        if (!shutdownStarted) {
            shutdownStarted = true
            Thread.startVirtualThread {
                try {
                    state.shutdown()
                } finally {
                    exitApplication()
                }
            }
        }
    }

    val title = buildString {
        append("AeTeX")
        state.activeDocument?.let { document ->
            append(" — ")
            append(document.path.fileName)
            if (document.isModified) {
                append(" *")
            }
        }
    }

    Window(
        onCloseRequest = {
            if (state.modifiedDocuments.isEmpty()) {
                closeApplication()
            } else {
                confirmApplicationClose = true
            }
        },
        title = title
    ) {
        MainWindow(state = state)

        if (confirmApplicationClose) {
            UnsavedChangesDialog(
                title = "Unsaved changes",
                message = "Save all modified documents before closing AeTeX?",
                onSave = {
                    if (state.saveAllModifiedDocuments()) {
                        closeApplication()
                    } else {
                        confirmApplicationClose = false
                    }
                },
                onDiscard = closeApplication,
                onCancel = { confirmApplicationClose = false }
            )
        }
    }
}
