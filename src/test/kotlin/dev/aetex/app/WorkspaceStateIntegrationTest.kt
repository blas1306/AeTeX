package dev.aetex.app

import dev.aetex.compilation.BuildPlanner
import dev.aetex.compilation.CompilationManager
import dev.aetex.compilation.EnvironmentProvider
import dev.aetex.compilation.FileBuildLogFactory
import dev.aetex.compilation.FileCoordinationStore
import dev.aetex.preview.coordination.PreviewManager
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.snapshotOf
import dev.aetex.preview.successfulBuildResult
import dev.aetex.preview.testGeneration
import dev.aetex.preview.ui.PreviewViewport
import dev.aetex.preview.ui.PreviewRasterQualityPolicy
import dev.aetex.preview.ui.PreviewZoom
import dev.aetex.preview.ui.PreviewZoomMode
import dev.aetex.workspace.PendingWorkspaceWrite
import dev.aetex.workspace.WorkspaceLayout
import dev.aetex.workspace.WorkspacePanel
import dev.aetex.workspace.WorkspaceTool
import dev.aetex.workspace.WorkspacePreferencesCoordinator
import dev.aetex.workspace.WorkspacePreferencesStore
import dev.aetex.workspace.WorkspaceWriteScheduler
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WorkspaceStateIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `workspace changes replace neither project document nor build manager`() {
        val root = configuredProject("project")
        var managerCreations = 0
        val state = AeTeXState(
            compilationManagerFactory = {
                managerCreations++
                CompilationManager(
                    logFactory = FileBuildLogFactory(temporaryDirectory.resolve("logs")),
                    coordinationStore =
                        FileCoordinationStore(temporaryDirectory.resolve("coordination"))
                )
            },
            workspacePreferences = preferences()
        )
        assertTrue(state.openProject(root))
        val project = state.project
        val activeDocument = state.activeDocument

        state.dragWorkspaceDivider(WorkspacePanel.PROJECT, 70.0, 1440.0)
        state.dragWorkspaceDivider(WorkspacePanel.PREVIEW, -40.0, 1440.0)
        state.activateWorkspaceTool(WorkspaceTool.PROJECT, 1440.0)
        state.activateWorkspaceTool(WorkspaceTool.PROJECT, 1440.0)
        state.collapseWorkspacePanel(WorkspacePanel.PREVIEW)
        state.restoreWorkspacePanel(WorkspacePanel.PREVIEW, 1440.0)
        val quality = requireNotNull(PreviewRasterQualityPolicy.DEFAULT.resolve(1.0, 2.0))
        state.updatePreviewViewport(setOf(0), 0, quality.rasterScale)

        assertSame(project, state.project)
        assertSame(activeDocument, state.activeDocument)
        assertEquals(root.resolve("main.tex").toRealPath(), state.activeDocumentPath)
        assertEquals(0, managerCreations, "layout changes must not request a build")
        assertEquals(PreviewState.Empty, state.previewState)
        state.shutdown()
    }

    @Test
    fun `project rail interaction preserves editor selection and document identity`() {
        val root = configuredProject("rail-project")
        val state = AeTeXState(workspacePreferences = preferences())
        assertTrue(state.openProject(root))
        val document = state.activeDocument
        val path = state.activeDocumentPath

        state.activateWorkspaceTool(WorkspaceTool.PROJECT, 1440.0)
        state.activateWorkspaceTool(WorkspaceTool.PROJECT, 1440.0)

        assertSame(document, state.activeDocument)
        assertEquals(path, state.activeDocumentPath)
        assertEquals(root.resolve("main.tex").toRealPath(), path)
        state.shutdown()
    }

    @Test
    fun `collapse and resize preserve ready preview generation and manager`() {
        val root = configuredProject("preview-project")
        val compilation = CompilationManager(
            planner = BuildPlanner(environmentProvider = EnvironmentProvider { emptyMap() }),
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("preview-runtime/logs")),
            coordinationStore =
                FileCoordinationStore(temporaryDirectory.resolve("preview-runtime/coordination"))
        )
        val capturedPreview = AtomicReference<PreviewManager>()
        val state = AeTeXState(
            compilationManagerFactory = { compilation },
            previewManagerFactory = { manager, projectRoot ->
                PreviewManager(
                    compilationManager = manager,
                    projectRoot = projectRoot,
                    generationFactory = { result ->
                        PreviewResult.Success(
                            testGeneration(
                                temporaryDirectory,
                                sessionId = result.sessionId
                            )
                        )
                    }
                ).also(capturedPreview::set)
            },
            workspacePreferences = preferences()
        )
        assertTrue(state.openProject(root))
        state.requestBuild()
        val preview = capturedPreview.get()
        val readyLatch = CountDownLatch(1)
        val subscription = preview.addStateListener {
            if (it is PreviewState.Ready) readyLatch.countDown()
        }
        preview.acceptCompilationSnapshot(snapshotOf(successfulBuildResult(root, "workspace")))
        assertTrue(readyLatch.await(2, TimeUnit.SECONDS))
        val readyBefore = assertIs<PreviewState.Ready>(state.previewState)
        val generation = readyBefore.document.generationId

        val narrow = requireNotNull(
            PreviewZoom.resolve(
                PreviewZoomMode.FitWidth,
                PreviewViewport(332.0, 700.0),
                PageGeometry(200f, 300f)
            )
        )
        val wide = requireNotNull(
            PreviewZoom.resolve(
                PreviewZoomMode.FitWidth,
                PreviewViewport(632.0, 700.0),
                PageGeometry(200f, 300f)
            )
        )
        state.updatePreviewViewport(setOf(0), 0, narrow.rasterScale)
        state.dragWorkspaceDivider(WorkspacePanel.PREVIEW, -120.0, 1440.0)
        state.updatePreviewViewport(setOf(0), 0, wide.rasterScale)
        state.collapseWorkspacePanel(WorkspacePanel.PREVIEW)

        assertFalse(preview.state is PreviewState.Closed)
        assertEquals(generation, assertIs<PreviewState.Ready>(state.previewState).document.generationId)

        state.restoreWorkspacePanel(WorkspacePanel.PREVIEW, 1440.0)

        assertEquals(generation, assertIs<PreviewState.Ready>(state.previewState).document.generationId)
        assertSame(preview, capturedPreview.get())
        subscription.close()
        state.shutdown()
    }

    @Test
    fun `project replacement still retires preview manager after layout changes`() {
        val first = configuredProject("first")
        val second = configuredProject("second")
        val compilation = CompilationManager(
            planner = BuildPlanner(environmentProvider = EnvironmentProvider { emptyMap() }),
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("replace-runtime/logs")),
            coordinationStore =
                FileCoordinationStore(temporaryDirectory.resolve("replace-runtime/coordination"))
        )
        val capturedPreview = AtomicReference<PreviewManager>()
        val state = AeTeXState(
            compilationManagerFactory = { compilation },
            previewManagerFactory = { manager, root ->
                PreviewManager(manager, root).also(capturedPreview::set)
            },
            workspacePreferences = preferences()
        )
        assertTrue(state.openProject(first))
        state.requestBuild()
        val preview = capturedPreview.get()
        val closed = CountDownLatch(1)
        val subscription = preview.addStateListener {
            if (it == PreviewState.Closed) closed.countDown()
        }
        state.collapseWorkspacePanel(WorkspacePanel.PREVIEW)

        assertTrue(state.openProject(second))

        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(second.toRealPath(), state.project?.rootDirectory)
        subscription.close()
        state.shutdown()
    }

    private fun configuredProject(name: String): Path {
        val root = temporaryDirectory.resolve(name).createDirectory()
        root.resolve("main.tex").writeText("\\documentclass{article}\n")
        root.resolve(".aetex").createDirectory()
            .resolve("project.toml")
            .writeText("schema = 1\nmain = \"main.tex\"\n")
        return root
    }

    private fun preferences() = WorkspacePreferencesCoordinator(
        store = object : WorkspacePreferencesStore {
            override fun load() = WorkspaceLayout()
            override fun save(layout: WorkspaceLayout) = true
        },
        scheduler = object : WorkspaceWriteScheduler {
            override fun schedule(
                delayMillis: Long,
                task: () -> Unit
            ) = PendingWorkspaceWrite {}

            override fun close() = Unit
        }
    )
}
