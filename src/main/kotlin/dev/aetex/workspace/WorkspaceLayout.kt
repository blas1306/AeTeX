package dev.aetex.workspace

import kotlin.math.min

enum class WorkspacePanel {
    PROJECT,
    PREVIEW
}

enum class WorkspaceTool {
    PROJECT
}

data class WorkspaceLayout(
    val schemaVersion: Int = SCHEMA_VERSION,
    val projectPanelWidthDp: Double = PROJECT_DEFAULT_WIDTH_DP,
    val previewPanelWidthDp: Double = PREVIEW_DEFAULT_WIDTH_DP,
    val projectPanelCollapsed: Boolean = false,
    val previewPanelCollapsed: Boolean = false,
    val lastProjectPanelWidthDp: Double = PROJECT_DEFAULT_WIDTH_DP,
    val lastPreviewPanelWidthDp: Double = PREVIEW_DEFAULT_WIDTH_DP
) {
    fun normalized(): WorkspaceLayout {
        val projectLast = lastProjectPanelWidthDp.validWidthOr(
            PROJECT_DEFAULT_WIDTH_DP,
            PROJECT_MIN_WIDTH_DP
        )
        val previewLast = lastPreviewPanelWidthDp.validWidthOr(
            PREVIEW_DEFAULT_WIDTH_DP,
            PREVIEW_MIN_WIDTH_DP
        )
        return copy(
            schemaVersion = SCHEMA_VERSION,
            projectPanelWidthDp = if (projectPanelCollapsed) {
                0.0
            } else {
                projectPanelWidthDp.validWidthOr(
                    projectLast,
                    PROJECT_MIN_WIDTH_DP
                )
            },
            previewPanelWidthDp = if (previewPanelCollapsed) {
                0.0
            } else {
                previewPanelWidthDp.validWidthOr(
                    previewLast,
                    PREVIEW_MIN_WIDTH_DP
                )
            },
            lastProjectPanelWidthDp = projectLast,
            lastPreviewPanelWidthDp = previewLast
        )
    }

    fun resolve(availableWidthDp: Double): ResolvedWorkspaceLayout {
        val layout = normalized()
        val available = availableWidthDp.finiteNonNegative()
        val toolRail = min(TOOL_RAIL_WIDTH_DP, available)
        val afterToolRail = (available - toolRail).coerceAtLeast(0.0)
        val requestedProjectChrome =
            if (layout.projectPanelCollapsed) 0.0 else DIVIDER_HIT_TARGET_WIDTH_DP
        val requestedPreviewChrome =
            if (layout.previewPanelCollapsed) PREVIEW_RESTORE_AFFORDANCE_WIDTH_DP
            else DIVIDER_HIT_TARGET_WIDTH_DP
        val requestedChrome = requestedProjectChrome + requestedPreviewChrome

        val reservedEditor = min(EDITOR_MIN_WIDTH_DP, afterToolRail)
        val spaceAfterEditor = (afterToolRail - reservedEditor).coerceAtLeast(0.0)
        val chromeScale = if (requestedChrome == 0.0) {
            1.0
        } else {
            min(1.0, spaceAfterEditor / requestedChrome)
        }
        val projectChrome = requestedProjectChrome * chromeScale
        val previewChrome = requestedPreviewChrome * chromeScale
        val panelBudget =
            (spaceAfterEditor - projectChrome - previewChrome).coerceAtLeast(0.0)

        val requestedProjectPanel =
            if (layout.projectPanelCollapsed) 0.0 else layout.projectPanelWidthDp
        val requestedPreviewPanel =
            if (layout.previewPanelCollapsed) 0.0 else layout.previewPanelWidthDp
        val requestedPanels = requestedProjectPanel + requestedPreviewPanel
        val projectMinimum =
            if (layout.projectPanelCollapsed) 0.0 else PROJECT_MIN_WIDTH_DP
        val previewMinimum =
            if (layout.previewPanelCollapsed) 0.0 else PREVIEW_MIN_WIDTH_DP
        val requestedMinimums = projectMinimum + previewMinimum
        val (projectPanel, previewPanel) = when {
            requestedPanels <= panelBudget -> {
                requestedProjectPanel to requestedPreviewPanel
            }

            requestedMinimums <= panelBudget -> {
                val projectExtra = requestedProjectPanel - projectMinimum
                val previewExtra = requestedPreviewPanel - previewMinimum
                val requestedExtras = projectExtra + previewExtra
                val extraScale = if (requestedExtras == 0.0) {
                    0.0
                } else {
                    (panelBudget - requestedMinimums) / requestedExtras
                }
                (projectMinimum + projectExtra * extraScale) to
                    (previewMinimum + previewExtra * extraScale)
            }

            requestedMinimums > 0.0 -> {
                val minimumScale = panelBudget / requestedMinimums
                (projectMinimum * minimumScale) to (previewMinimum * minimumScale)
            }

            else -> 0.0 to 0.0
        }
        val editor = if (requestedPanels > panelBudget) {
            reservedEditor
        } else {
            (
                afterToolRail -
                    projectChrome -
                    previewChrome -
                    projectPanel -
                    previewPanel
                ).coerceAtLeast(reservedEditor)
        }

        return ResolvedWorkspaceLayout(
            availableWidthDp = available,
            toolRailWidthDp = toolRail,
            projectPanelWidthDp = projectPanel,
            projectDividerWidthDp =
                if (layout.projectPanelCollapsed) 0.0 else projectChrome,
            editorWidthDp = editor,
            previewDividerWidthDp =
                if (layout.previewPanelCollapsed) 0.0 else previewChrome,
            previewPanelWidthDp = previewPanel,
            previewRestoreAffordanceWidthDp =
                if (layout.previewPanelCollapsed) previewChrome else 0.0
        )
    }

    fun dragDivider(
        panel: WorkspacePanel,
        horizontalDeltaDp: Double,
        availableWidthDp: Double
    ): WorkspaceLayout {
        if (!horizontalDeltaDp.isFinite()) return normalized()
        val layout = normalized()
        return when (panel) {
            WorkspacePanel.PROJECT -> {
                if (layout.projectPanelCollapsed) return layout
                val maximum = maximumProjectWidth(layout, availableWidthDp)
                if (maximum < PROJECT_MIN_WIDTH_DP) return layout
                val width = (layout.projectPanelWidthDp + horizontalDeltaDp)
                    .coerceIn(PROJECT_MIN_WIDTH_DP, maximum)
                layout.copy(
                    projectPanelWidthDp = width,
                    lastProjectPanelWidthDp = width
                )
            }

            WorkspacePanel.PREVIEW -> {
                if (layout.previewPanelCollapsed) return layout
                val maximum = maximumPreviewWidth(layout, availableWidthDp)
                if (maximum < PREVIEW_MIN_WIDTH_DP) return layout
                val width = (layout.previewPanelWidthDp - horizontalDeltaDp)
                    .coerceIn(PREVIEW_MIN_WIDTH_DP, maximum)
                layout.copy(
                    previewPanelWidthDp = width,
                    lastPreviewPanelWidthDp = width
                )
            }
        }
    }

    fun collapse(panel: WorkspacePanel): WorkspaceLayout {
        val layout = normalized()
        return when (panel) {
            WorkspacePanel.PROJECT -> if (layout.projectPanelCollapsed) {
                layout
            } else {
                layout.copy(
                    projectPanelWidthDp = 0.0,
                    projectPanelCollapsed = true,
                    lastProjectPanelWidthDp = layout.projectPanelWidthDp
                )
            }

            WorkspacePanel.PREVIEW -> if (layout.previewPanelCollapsed) {
                layout
            } else {
                layout.copy(
                    previewPanelWidthDp = 0.0,
                    previewPanelCollapsed = true,
                    lastPreviewPanelWidthDp = layout.previewPanelWidthDp
                )
            }
        }
    }

    fun toggleTool(
        tool: WorkspaceTool,
        availableWidthDp: Double
    ): WorkspaceLayout = when (tool) {
        WorkspaceTool.PROJECT ->
            if (normalized().projectPanelCollapsed) {
                restore(WorkspacePanel.PROJECT, availableWidthDp)
            } else {
                collapse(WorkspacePanel.PROJECT)
            }
    }

    fun restore(
        panel: WorkspacePanel,
        availableWidthDp: Double
    ): WorkspaceLayout {
        val layout = normalized()
        return when (panel) {
            WorkspacePanel.PROJECT -> {
                if (!layout.projectPanelCollapsed) return layout
                val expanded = layout.copy(projectPanelCollapsed = false)
                val maximum = maximumProjectWidth(expanded, availableWidthDp)
                val width = if (maximum >= PROJECT_MIN_WIDTH_DP) {
                    layout.lastProjectPanelWidthDp.coerceAtMost(maximum)
                } else {
                    PROJECT_MIN_WIDTH_DP
                }
                expanded.copy(
                    projectPanelWidthDp = width,
                    lastProjectPanelWidthDp = width
                )
            }

            WorkspacePanel.PREVIEW -> {
                if (!layout.previewPanelCollapsed) return layout
                val expanded = layout.copy(previewPanelCollapsed = false)
                val maximum = maximumPreviewWidth(expanded, availableWidthDp)
                val width = if (maximum >= PREVIEW_MIN_WIDTH_DP) {
                    layout.lastPreviewPanelWidthDp.coerceAtMost(maximum)
                } else {
                    PREVIEW_MIN_WIDTH_DP
                }
                expanded.copy(
                    previewPanelWidthDp = width,
                    lastPreviewPanelWidthDp = width
                )
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROJECT_DEFAULT_WIDTH_DP = 260.0
        const val PREVIEW_DEFAULT_WIDTH_DP = 360.0
        const val PROJECT_MIN_WIDTH_DP = 180.0
        const val PREVIEW_MIN_WIDTH_DP = 260.0
        const val EDITOR_MIN_WIDTH_DP = 320.0
        const val DIVIDER_HIT_TARGET_WIDTH_DP = 8.0
        const val DIVIDER_VISIBLE_WIDTH_DP = 1.0
        const val KEYBOARD_RESIZE_STEP_DP = 12.0
        const val TOOL_RAIL_WIDTH_DP = 44.0
        const val PREVIEW_RESTORE_AFFORDANCE_WIDTH_DP = 28.0
        const val MAX_PERSISTED_PANEL_WIDTH_DP = 4096.0

        private fun maximumProjectWidth(
            layout: WorkspaceLayout,
            availableWidthDp: Double
        ): Double {
            val opposite = if (layout.previewPanelCollapsed) {
                PREVIEW_RESTORE_AFFORDANCE_WIDTH_DP
            } else {
                layout.previewPanelWidthDp + DIVIDER_HIT_TARGET_WIDTH_DP
            }
            return (
                availableWidthDp.finiteNonNegative() -
                    TOOL_RAIL_WIDTH_DP -
                    EDITOR_MIN_WIDTH_DP -
                    DIVIDER_HIT_TARGET_WIDTH_DP -
                    opposite
                ).coerceAtMost(MAX_PERSISTED_PANEL_WIDTH_DP)
        }

        private fun maximumPreviewWidth(
            layout: WorkspaceLayout,
            availableWidthDp: Double
        ): Double {
            val opposite = if (layout.projectPanelCollapsed) {
                0.0
            } else {
                layout.projectPanelWidthDp + DIVIDER_HIT_TARGET_WIDTH_DP
            }
            return (
                availableWidthDp.finiteNonNegative() -
                    TOOL_RAIL_WIDTH_DP -
                    EDITOR_MIN_WIDTH_DP -
                    DIVIDER_HIT_TARGET_WIDTH_DP -
                    opposite
                ).coerceAtMost(MAX_PERSISTED_PANEL_WIDTH_DP)
        }
    }
}

data class ResolvedWorkspaceLayout(
    val availableWidthDp: Double,
    val toolRailWidthDp: Double,
    val projectPanelWidthDp: Double,
    val projectDividerWidthDp: Double,
    val editorWidthDp: Double,
    val previewDividerWidthDp: Double,
    val previewPanelWidthDp: Double,
    val previewRestoreAffordanceWidthDp: Double
) {
    val totalWidthDp: Double
        get() =
            toolRailWidthDp +
                projectPanelWidthDp +
                projectDividerWidthDp +
                editorWidthDp +
                previewDividerWidthDp +
                previewPanelWidthDp +
                previewRestoreAffordanceWidthDp
}

private fun Double.validWidthOr(default: Double, minimum: Double): Double =
    if (isFinite() && this >= minimum && this <= WorkspaceLayout.MAX_PERSISTED_PANEL_WIDTH_DP) {
        this
    } else {
        default.coerceIn(minimum, WorkspaceLayout.MAX_PERSISTED_PANEL_WIDTH_DP)
    }

private fun Double.finiteNonNegative(): Double =
    if (isFinite() && this >= 0.0) this else 0.0
