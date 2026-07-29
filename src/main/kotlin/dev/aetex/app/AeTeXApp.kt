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
                exitApplication()
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
                        exitApplication()
                    } else {
                        confirmApplicationClose = false
                    }
                },
                onDiscard = ::exitApplication,
                onCancel = { confirmApplicationClose = false }
            )
        }
    }
}
