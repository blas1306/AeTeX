package dev.aetex.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WorkspacePreferencesTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `preferences round trip in deterministic UTF 8 TOML`() {
        val file = temporaryDirectory.resolve("config/aetex/workspace.toml")
        val store = FileWorkspacePreferencesStore(file)
        val expected = WorkspaceLayout(
            projectPanelWidthDp = 311.25,
            previewPanelWidthDp = 477.5,
            previewPanelCollapsed = true,
            lastProjectPanelWidthDp = 311.25,
            lastPreviewPanelWidthDp = 477.5
        ).normalized()

        assertTrue(store.save(expected))

        assertEquals(expected, store.load())
        assertTrue(file.readText().startsWith("schema = 1\n"))
        assertFalse(file.readText().contains(temporaryDirectory.toString()))
    }

    @Test
    fun `missing settings file returns defaults`() {
        val store = FileWorkspacePreferencesStore(temporaryDirectory.resolve("missing.toml"))

        assertEquals(WorkspaceLayout(), store.load())
    }

    @Test
    fun `malformed settings file returns defaults`() {
        val file = temporaryDirectory.resolve("workspace.toml")
        file.writeText("schema = [")

        assertEquals(WorkspaceLayout(), FileWorkspacePreferencesStore(file).load())
    }

    @Test
    fun `unsupported schema returns defaults`() {
        val file = temporaryDirectory.resolve("workspace.toml")
        file.writeText("schema = 99\nproject_panel_width_dp = 999.0\n")

        assertEquals(WorkspaceLayout(), FileWorkspacePreferencesStore(file).load())
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val file = temporaryDirectory.resolve("workspace.toml")
        file.writeText(
            """
            schema = 1
            project_panel_width_dp = 300.0
            future_layout_mode = "floating"
            """.trimIndent()
        )

        assertEquals(
            300.0,
            FileWorkspacePreferencesStore(file).load().projectPanelWidthDp
        )
    }

    @Test
    fun `schema one collapsed rail settings normalize into tool rail layout`() {
        val file = temporaryDirectory.resolve("workspace.toml")
        file.writeText(
            """
            schema = 1
            project_panel_width_dp = 0.0
            preview_panel_width_dp = 0.0
            project_panel_collapsed = true
            preview_panel_collapsed = true
            last_project_panel_width_dp = 315.0
            last_preview_panel_width_dp = 475.0
            """.trimIndent()
        )

        val loaded = FileWorkspacePreferencesStore(file).load()
        val resolved = loaded.resolve(1_440.0)

        assertTrue(loaded.projectPanelCollapsed)
        assertTrue(loaded.previewPanelCollapsed)
        assertEquals(315.0, loaded.lastProjectPanelWidthDp)
        assertEquals(475.0, loaded.lastPreviewPanelWidthDp)
        assertEquals(WorkspaceLayout.TOOL_RAIL_WIDTH_DP, resolved.toolRailWidthDp)
        assertEquals(0.0, resolved.projectPanelWidthDp)
        assertEquals(0.0, resolved.previewPanelWidthDp)
    }

    @Test
    fun `invalid persisted widths fall back independently`() {
        val file = temporaryDirectory.resolve("workspace.toml")
        file.writeText(
            """
            schema = 1
            project_panel_width_dp = -20.0
            preview_panel_width_dp = 99999.0
            last_project_panel_width_dp = -1.0
            last_preview_panel_width_dp = 99999.0
            """.trimIndent()
        )

        assertEquals(WorkspaceLayout(), FileWorkspacePreferencesStore(file).load())
    }

    @Test
    fun `oversized settings file is rejected without parsing`() {
        val file = temporaryDirectory.resolve("workspace.toml")
        Files.write(file, ByteArray((FileWorkspacePreferencesStore.MAX_SETTINGS_BYTES + 1).toInt()))

        assertEquals(WorkspaceLayout(), FileWorkspacePreferencesStore(file).load())
    }

    @Test
    fun `atomic move failure cleans temporary file`() {
        val directory = temporaryDirectory.resolve("config").createDirectory()
        val file = directory.resolve("workspace.toml")
        val store = FileWorkspacePreferencesStore(file) { _, _ ->
            throw IllegalStateException("synthetic move failure")
        }

        assertFalse(store.save(WorkspaceLayout()))
        assertTrue(
            directory.listDirectoryEntries().none {
                it.fileName.toString().startsWith(".workspace-")
            }
        )
    }

    @Test
    fun `write failure degrades to false without throwing`() {
        val parentFile = temporaryDirectory.resolve("not-a-directory")
        parentFile.writeText("block")
        val store = FileWorkspacePreferencesStore(parentFile.resolve("workspace.toml"))

        assertFalse(store.save(WorkspaceLayout()))
    }

    @Test
    fun `debounced saves coalesce to newest layout`() {
        val store = RecordingStore()
        val scheduler = ManualScheduler()
        val coordinator = WorkspacePreferencesCoordinator(store, scheduler, 350)
        val first = WorkspaceLayout(projectPanelWidthDp = 300.0)
        val second = WorkspaceLayout(projectPanelWidthDp = 330.0)

        coordinator.requestSave(first)
        coordinator.requestSave(second)
        scheduler.run(0)
        scheduler.run(1)

        assertEquals(listOf(second), store.saved)
    }

    @Test
    fun `late persistence callback cannot overwrite a newer workspace state`() {
        val store = RecordingStore()
        val scheduler = ManualScheduler()
        val coordinator = WorkspacePreferencesCoordinator(store, scheduler)
        val older = WorkspaceLayout(previewPanelWidthDp = 400.0)
        val newer = WorkspaceLayout(previewPanelWidthDp = 440.0)

        coordinator.requestSave(older)
        coordinator.requestSave(newer)
        scheduler.run(1)
        scheduler.run(0)

        assertEquals(listOf(newer), store.saved)
    }

    @Test
    fun `shutdown flushes newest state and abandons pending callback safely`() {
        val store = RecordingStore()
        val scheduler = ManualScheduler()
        val coordinator = WorkspacePreferencesCoordinator(store, scheduler)
        val final = WorkspaceLayout(projectPanelCollapsed = true)

        coordinator.requestSave(final)
        assertTrue(coordinator.close(final))
        scheduler.run(0)

        assertEquals(listOf(final.normalized()), store.saved)
        assertTrue(scheduler.closed)
    }

    @Test
    fun `platform preference locations remain outside project data`() {
        val linux = defaultWorkspaceSettingsFile(
            environment = mapOf("XDG_CONFIG_HOME" to "/config"),
            properties = mapOf("user.home" to "/home/user", "os.name" to "Linux")
        )
        val windows = defaultWorkspaceSettingsFile(
            environment = mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming"),
            properties = mapOf("user.home" to "C:\\Users\\u", "os.name" to "Windows 11")
        )
        val mac = defaultWorkspaceSettingsFile(
            environment = emptyMap(),
            properties = mapOf("user.home" to "/Users/u", "os.name" to "Mac OS X")
        )

        assertEquals(Path.of("/config/aetex/workspace.toml"), linux)
        assertTrue(windows.toString().endsWith("AeTeX/workspace.toml"))
        assertEquals(
            Path.of("/Users/u/Library/Application Support/AeTeX/workspace.toml"),
            mac
        )
    }

    private class RecordingStore : WorkspacePreferencesStore {
        val saved = mutableListOf<WorkspaceLayout>()

        override fun load() = WorkspaceLayout()

        override fun save(layout: WorkspaceLayout): Boolean {
            saved += layout
            return true
        }
    }

    private class ManualScheduler : WorkspaceWriteScheduler {
        private data class Scheduled(
            val task: () -> Unit,
            var cancelled: Boolean = false
        )

        private val tasks = mutableListOf<Scheduled>()
        var closed = false
            private set

        override fun schedule(delayMillis: Long, task: () -> Unit): PendingWorkspaceWrite {
            val scheduled = Scheduled(task)
            tasks += scheduled
            return PendingWorkspaceWrite { scheduled.cancelled = true }
        }

        fun run(index: Int) {
            // Intentionally invoke cancelled work to model a callback already in flight.
            tasks[index].task()
        }

        override fun close() {
            closed = true
        }
    }
}
