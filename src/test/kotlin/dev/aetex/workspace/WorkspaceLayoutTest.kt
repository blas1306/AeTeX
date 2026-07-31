package dev.aetex.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceLayoutTest {
    @Test
    fun `default layout uses canonical preferred widths`() {
        val layout = WorkspaceLayout()
        val resolved = layout.resolve(1440.0)

        assertEquals(260.0, resolved.projectPanelWidthDp)
        assertEquals(360.0, resolved.previewPanelWidthDp)
        assertEquals(WorkspaceLayout.TOOL_RAIL_WIDTH_DP, resolved.toolRailWidthDp)
        assertEquals(760.0, resolved.editorWidthDp)
        assertEquals(1440.0, resolved.totalWidthDp)
    }

    @Test
    fun `invalid and out of bounds widths normalize to safe defaults`() {
        val normalized = WorkspaceLayout(
            projectPanelWidthDp = Double.NaN,
            previewPanelWidthDp = Double.POSITIVE_INFINITY,
            lastProjectPanelWidthDp = -1.0,
            lastPreviewPanelWidthDp = 50_000.0
        ).normalized()

        assertEquals(WorkspaceLayout.PROJECT_DEFAULT_WIDTH_DP, normalized.projectPanelWidthDp)
        assertEquals(WorkspaceLayout.PREVIEW_DEFAULT_WIDTH_DP, normalized.previewPanelWidthDp)
        assertEquals(
            WorkspaceLayout.PROJECT_DEFAULT_WIDTH_DP,
            normalized.lastProjectPanelWidthDp
        )
        assertEquals(
            WorkspaceLayout.PREVIEW_DEFAULT_WIDTH_DP,
            normalized.lastPreviewPanelWidthDp
        )
    }

    @Test
    fun `very narrow workspace preserves editor before compressing side panels`() {
        val resolved = WorkspaceLayout().resolve(500.0)

        assertTrue(resolved.editorWidthDp >= 0.0)
        assertEquals(500.0, resolved.totalWidthDp)
        assertTrue(resolved.projectPanelWidthDp >= 0.0)
        assertTrue(resolved.previewPanelWidthDp >= 0.0)
    }

    @Test
    fun `workspace narrower than editor minimum remains finite and non negative`() {
        val resolved = WorkspaceLayout().resolve(200.0)

        assertEquals(WorkspaceLayout.TOOL_RAIL_WIDTH_DP, resolved.toolRailWidthDp)
        assertEquals(156.0, resolved.editorWidthDp)
        assertEquals(200.0, resolved.totalWidthDp)
        assertTrue(resolved.projectPanelWidthDp >= 0.0)
        assertTrue(resolved.previewPanelWidthDp >= 0.0)
    }

    @Test
    fun `very wide workspace leaves preferred panels stable and gives editor remainder`() {
        val resolved = WorkspaceLayout().resolve(10_000.0)

        assertEquals(260.0, resolved.projectPanelWidthDp)
        assertEquals(360.0, resolved.previewPanelWidthDp)
        assertEquals(9_320.0, resolved.editorWidthDp)
    }

    @Test
    fun `left divider drag is bounded by project minimum and opposite panel`() {
        val expanded = WorkspaceLayout()
            .dragDivider(WorkspacePanel.PROJECT, 100.0, 1440.0)
        val minimum = expanded
            .dragDivider(WorkspacePanel.PROJECT, -10_000.0, 1440.0)
        val maximum = minimum
            .dragDivider(WorkspacePanel.PROJECT, 10_000.0, 1_000.0)

        assertEquals(360.0, expanded.projectPanelWidthDp)
        assertEquals(WorkspaceLayout.PROJECT_MIN_WIDTH_DP, minimum.projectPanelWidthDp)
        assertEquals(260.0, maximum.projectPanelWidthDp)
        assertTrue(maximum.resolve(1_000.0).editorWidthDp >= WorkspaceLayout.EDITOR_MIN_WIDTH_DP)
    }

    @Test
    fun `right divider drag has inverse horizontal direction and is bounded`() {
        val smaller = WorkspaceLayout()
            .dragDivider(WorkspacePanel.PREVIEW, 50.0, 1440.0)
        val larger = smaller
            .dragDivider(WorkspacePanel.PREVIEW, -100.0, 1440.0)
        val minimum = larger
            .dragDivider(WorkspacePanel.PREVIEW, 10_000.0, 1440.0)

        assertEquals(310.0, smaller.previewPanelWidthDp)
        assertEquals(410.0, larger.previewPanelWidthDp)
        assertEquals(WorkspaceLayout.PREVIEW_MIN_WIDTH_DP, minimum.previewPanelWidthDp)
    }

    @Test
    fun `both expanded panels honor minimums when workspace can contain them`() {
        val resolved = WorkspaceLayout().resolve(840.0)

        assertTrue(resolved.projectPanelWidthDp >= WorkspaceLayout.PROJECT_MIN_WIDTH_DP)
        assertTrue(resolved.previewPanelWidthDp >= WorkspaceLayout.PREVIEW_MIN_WIDTH_DP)
        assertTrue(resolved.editorWidthDp >= WorkspaceLayout.EDITOR_MIN_WIDTH_DP)
    }

    @Test
    fun `project tool rail remains visible when project is open or closed`() {
        val open = WorkspaceLayout().resolve(1440.0)
        val layout = WorkspaceLayout().collapse(WorkspacePanel.PROJECT)
        val resolved = layout.resolve(1440.0)

        assertTrue(layout.projectPanelCollapsed)
        assertEquals(WorkspaceLayout.TOOL_RAIL_WIDTH_DP, open.toolRailWidthDp)
        assertEquals(WorkspaceLayout.TOOL_RAIL_WIDTH_DP, resolved.toolRailWidthDp)
        assertEquals(0.0, resolved.projectPanelWidthDp)
        assertEquals(0.0, resolved.projectDividerWidthDp)
        assertEquals(1_028.0, resolved.editorWidthDp)
    }

    @Test
    fun `closed project consumes only persistent rail and closed preview only restore affordance`() {
        val layout = WorkspaceLayout()
            .collapse(WorkspacePanel.PROJECT)
            .collapse(WorkspacePanel.PREVIEW)
        val resolved = layout.resolve(800.0)

        assertTrue(layout.projectPanelCollapsed)
        assertTrue(layout.previewPanelCollapsed)
        assertEquals(WorkspaceLayout.TOOL_RAIL_WIDTH_DP, resolved.toolRailWidthDp)
        assertEquals(0.0, resolved.projectPanelWidthDp)
        assertEquals(0.0, resolved.projectDividerWidthDp)
        assertEquals(0.0, resolved.previewPanelWidthDp)
        assertEquals(0.0, resolved.previewDividerWidthDp)
        assertEquals(
            WorkspaceLayout.PREVIEW_RESTORE_AFFORDANCE_WIDTH_DP,
            resolved.previewRestoreAffordanceWidthDp
        )
        assertEquals(728.0, resolved.editorWidthDp)
    }

    @Test
    fun `restore preserves the last useful expanded width`() {
        val resized = WorkspaceLayout()
            .dragDivider(WorkspacePanel.PROJECT, 90.0, 1440.0)
        val collapsed = resized.collapse(WorkspacePanel.PROJECT)
        val restored = collapsed.restore(WorkspacePanel.PROJECT, 1440.0)

        assertEquals(0.0, collapsed.projectPanelWidthDp)
        assertEquals(350.0, collapsed.lastProjectPanelWidthDp)
        assertFalse(restored.projectPanelCollapsed)
        assertEquals(350.0, restored.projectPanelWidthDp)
    }

    @Test
    fun `restore after window shrink clamps without reducing editor minimum`() {
        val large = WorkspaceLayout(
            projectPanelWidthDp = 700.0,
            lastProjectPanelWidthDp = 700.0
        ).collapse(WorkspacePanel.PROJECT)
        val restored = large.restore(WorkspacePanel.PROJECT, 1_000.0)
        val resolved = restored.resolve(1_000.0)

        assertEquals(260.0, restored.projectPanelWidthDp)
        assertTrue(resolved.editorWidthDp >= WorkspaceLayout.EDITOR_MIN_WIDTH_DP)
        assertEquals(1_000.0, resolved.totalWidthDp)
    }

    @Test
    fun `non finite drag deltas cannot change state`() {
        val layout = WorkspaceLayout()

        assertEquals(
            layout,
            layout.dragDivider(WorkspacePanel.PROJECT, Double.NaN, 1440.0)
        )
        assertEquals(
            layout,
            layout.dragDivider(WorkspacePanel.PREVIEW, Double.NEGATIVE_INFINITY, 1440.0)
        )
    }

    @Test
    fun `project tool toggles closed and open while preserving previous width`() {
        val resized = WorkspaceLayout()
            .dragDivider(WorkspacePanel.PROJECT, 80.0, 1440.0)
        val closed = resized.toggleTool(WorkspaceTool.PROJECT, 1440.0)
        val reopened = closed.toggleTool(WorkspaceTool.PROJECT, 1440.0)

        assertTrue(closed.projectPanelCollapsed)
        assertEquals(340.0, closed.lastProjectPanelWidthDp)
        assertFalse(reopened.projectPanelCollapsed)
        assertEquals(340.0, reopened.projectPanelWidthDp)
    }

    @Test
    fun `preview close consumes no expanded width and restore clamps previous width`() {
        val wide = WorkspaceLayout(
            previewPanelWidthDp = 700.0,
            lastPreviewPanelWidthDp = 700.0
        )
        val closed = wide.collapse(WorkspacePanel.PREVIEW)
        val resolvedClosed = closed.resolve(1_000.0)
        val restored = closed.restore(WorkspacePanel.PREVIEW, 1_000.0)

        assertEquals(0.0, resolvedClosed.previewPanelWidthDp)
        assertEquals(0.0, resolvedClosed.previewDividerWidthDp)
        assertEquals(360.0, restored.previewPanelWidthDp)
        assertEquals(WorkspaceLayout.EDITOR_MIN_WIDTH_DP, restored.resolve(1_000.0).editorWidthDp)
    }

    @Test
    fun `divider drag and collapse state are independent`() {
        val closed = WorkspaceLayout()
            .collapse(WorkspacePanel.PREVIEW)
            .dragDivider(WorkspacePanel.PREVIEW, -500.0, 1440.0)

        assertTrue(closed.previewPanelCollapsed)
        assertEquals(WorkspaceLayout.PREVIEW_DEFAULT_WIDTH_DP, closed.lastPreviewPanelWidthDp)
        assertEquals(0.0, closed.previewPanelWidthDp)
    }

    @Test
    fun `editor minimum is preserved for every representable supported width`() {
        (WorkspaceLayout.TOOL_RAIL_WIDTH_DP.toInt() + 320..2_000).forEach { width ->
            val resolved = WorkspaceLayout().resolve(width.toDouble())
            assertTrue(
                resolved.editorWidthDp >= WorkspaceLayout.EDITOR_MIN_WIDTH_DP,
                "editor width at workspace width $width"
            )
            assertTrue(resolved.totalWidthDp <= width.toDouble() + 0.000_001)
        }
    }
}
