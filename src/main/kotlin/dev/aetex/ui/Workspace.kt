package dev.aetex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aetex.app.AeTeXState
import dev.aetex.ui.panels.EditorPanel
import dev.aetex.ui.panels.PreviewPanel
import dev.aetex.ui.panels.ProjectPanel
import dev.aetex.ui.panels.rememberPreviewPanelViewState
import dev.aetex.workspace.WorkspaceLayout
import dev.aetex.workspace.WorkspacePanel
import dev.aetex.workspace.WorkspaceTool
import java.awt.Cursor
import java.nio.file.Path

@Composable
fun Workspace(
    state: AeTeXState,
    onCreateProject: () -> Unit,
    onOpenProject: () -> Unit,
    onInitializeProject: () -> Unit,
    onDocumentCloseRequested: (Path) -> Unit,
    modifier: Modifier = Modifier
) {
    val expandedDirectories = remember(state.project?.rootDirectory) {
        mutableStateMapOf<Path, Boolean>()
    }
    val previewViewState = rememberPreviewPanelViewState()

    BoxWithConstraints(modifier = modifier) {
        val availableWidthDp = maxWidth.value.toDouble()
        val resolved = state.resolvedWorkspaceLayout(availableWidthDp)
        val density = LocalDensity.current

        Row(modifier = Modifier.fillMaxSize()) {
            ProjectToolRail(
                widthDp = resolved.toolRailWidthDp,
                projectOpen = !state.workspaceLayout.projectPanelCollapsed,
                onProjectActivated = {
                    state.activateWorkspaceTool(
                        WorkspaceTool.PROJECT,
                        availableWidthDp
                    )
                }
            )

            if (!state.workspaceLayout.projectPanelCollapsed) {
                ProjectPanel(
                    project = state.project,
                    activeDocumentPath = state.activeDocumentPath,
                    onCreateProject = onCreateProject,
                    onOpenProject = onOpenProject,
                    onInitializeProject = onInitializeProject,
                    onFileSelected = state::openDocument,
                    expandedDirectories = expandedDirectories,
                    onDirectoryExpandedChanged = { path, expanded ->
                        expandedDirectories[path] = expanded
                    },
                    modifier = Modifier.width(resolved.projectPanelWidthDp.toFloat().dp)
                )
                WorkspaceDivider(
                    panel = WorkspacePanel.PROJECT,
                    widthDp = resolved.projectDividerWidthDp,
                    onDrag = { horizontalPixels ->
                        val deltaDp = with(density) {
                            horizontalPixels.toDp().value.toDouble()
                        }
                        state.dragWorkspaceDivider(
                            WorkspacePanel.PROJECT,
                            deltaDp,
                            availableWidthDp
                        )
                    },
                    onDragFinished = state::flushWorkspaceLayout
                )
            }

            key("workspace-editor") {
                EditorPanel(
                    documents = state.openDocuments,
                    activeDocument = state.activeDocument,
                    onDocumentActivated = state::activateDocument,
                    onDocumentChanged = state::updateDocument,
                    onDocumentCloseRequested = onDocumentCloseRequested,
                    modifier = Modifier.weight(1f)
                )
            }

            if (state.workspaceLayout.previewPanelCollapsed) {
                PreviewRestoreAffordance(
                    widthDp = resolved.previewRestoreAffordanceWidthDp,
                    onRestore = {
                        state.restoreWorkspacePanel(
                            WorkspacePanel.PREVIEW,
                            availableWidthDp
                        )
                    }
                )
            } else {
                WorkspaceDivider(
                    panel = WorkspacePanel.PREVIEW,
                    widthDp = resolved.previewDividerWidthDp,
                    onDrag = { horizontalPixels ->
                        val deltaDp = with(density) {
                            horizontalPixels.toDp().value.toDouble()
                        }
                        state.dragWorkspaceDivider(
                            WorkspacePanel.PREVIEW,
                            deltaDp,
                            availableWidthDp
                        )
                    },
                    onDragFinished = state::flushWorkspaceLayout
                )
                PreviewPanel(
                    state = state.previewState,
                    onViewportChanged = state::updatePreviewViewport,
                    onRetryPage = state::retryPreviewPage,
                    viewState = previewViewState,
                    onCollapse = {
                        state.collapseWorkspacePanel(WorkspacePanel.PREVIEW)
                    },
                    modifier = Modifier.width(resolved.previewPanelWidthDp.toFloat().dp)
                )
            }
        }
    }
}

@Composable
private fun WorkspaceDivider(
    panel: WorkspacePanel,
    widthDp: Double,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit
) {
    val keyboardStepPixels = with(LocalDensity.current) {
        WorkspaceLayout.KEYBOARD_RESIZE_STEP_DP.toFloat().dp.toPx()
    }
    IdeTooltip(
        text = when (panel) {
            WorkspacePanel.PROJECT -> "Resize Project panel"
            WorkspacePanel.PREVIEW -> "Resize PDF Preview panel"
        },
        modifier = Modifier
            .width(widthDp.toFloat().dp)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF252526))
                .pointerHoverIcon(
                    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
                )
                .pointerInput(panel) {
                    detectHorizontalDragGestures(
                        onDragCancel = onDragFinished,
                        onDragEnd = onDragFinished
                    ) { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onDrag(-keyboardStepPixels)
                                onDragFinished()
                                true
                            }

                            Key.DirectionRight -> {
                                onDrag(keyboardStepPixels)
                                onDragFinished()
                                true
                            }

                            else -> false
                        }
                    }
                }
                .semantics {
                    contentDescription = when (panel) {
                        WorkspacePanel.PROJECT -> "Resize Project panel"
                        WorkspacePanel.PREVIEW -> "Resize PDF Preview panel"
                    }
                }
                .focusable(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(WorkspaceLayout.DIVIDER_VISIBLE_WIDTH_DP.toFloat().dp)
                    .fillMaxHeight()
                    .background(Color(0xFF5B5B5B))
            )
        }
    }
}

@Composable
private fun ProjectToolRail(
    widthDp: Double,
    projectOpen: Boolean,
    onProjectActivated: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(widthDp.toFloat().dp)
            .fillMaxHeight()
            .background(Color(0xFF181A1D)),
        contentAlignment = Alignment.TopCenter
    ) {
        IdeTooltip(if (projectOpen) "Close Project" else "Open Project") {
            IconButton(
                onClick = onProjectActivated,
                modifier = Modifier
                    .width(widthDp.toFloat().dp)
                    .height(44.dp)
                    .background(
                        if (projectOpen) Color(0xFF34373C) else Color.Transparent
                    )
                    .semantics {
                        contentDescription =
                            if (projectOpen) "Close Project panel" else "Open Project panel"
                        selected = projectOpen
                    }
            ) {
                Text("P", color = if (projectOpen) Color.White else Color(0xFFBDBDBD))
            }
        }
    }
}

@Composable
private fun PreviewRestoreAffordance(
    widthDp: Double,
    onRestore: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(widthDp.toFloat().dp)
            .fillMaxHeight()
            .background(Color(0xFF202225)),
        contentAlignment = Alignment.TopCenter
    ) {
        IdeTooltip("Restore PDF Preview") {
            IconButton(
                onClick = onRestore,
                modifier = Modifier
                    .width(widthDp.toFloat().dp)
                    .height(44.dp)
                    .semantics {
                        contentDescription = "Restore PDF Preview panel"
                    }
            ) {
                Text("‹", color = Color(0xFFD8D8D8))
            }
        }
    }
}
