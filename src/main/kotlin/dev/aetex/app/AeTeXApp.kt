package dev.aetex.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.aetex.ui.MainWindow
import dev.aetex.ui.UnsavedChangesDialog
import dev.aetex.workspace.defaultWorkspacePreferencesCoordinator
import java.awt.Dimension

fun startAeTeX() = application {
    val state = remember {
        AeTeXState(workspacePreferences = defaultWorkspacePreferencesCoordinator())
    }
    val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)
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
        title = title,
        state = windowState
    ) {
        val density = LocalDensity.current
        SideEffect {
            window.minimumSize = with(density) {
                Dimension(800.dp.roundToPx(), 500.dp.roundToPx())
            }
        }
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
