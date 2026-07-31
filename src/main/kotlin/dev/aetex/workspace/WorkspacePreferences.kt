package dev.aetex.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import org.tomlj.Toml
import org.tomlj.TomlParseResult

interface WorkspacePreferencesStore {
    fun load(): WorkspaceLayout
    fun save(layout: WorkspaceLayout): Boolean
}

class FileWorkspacePreferencesStore(
    val settingsFile: Path = defaultWorkspaceSettingsFile(),
    private val moveIntoPlace: (Path, Path) -> Unit = ::replaceFile
) : WorkspacePreferencesStore {
    override fun load(): WorkspaceLayout {
        if (!Files.exists(settingsFile)) return WorkspaceLayout()
        return try {
            val size = Files.size(settingsFile)
            if (size < 0L || size > MAX_SETTINGS_BYTES) {
                warn("Workspace preferences were ignored because the file is too large.")
                return WorkspaceLayout()
            }
            val parsed = Toml.parse(Files.readString(settingsFile, StandardCharsets.UTF_8))
            if (parsed.hasErrors()) {
                warn("Workspace preferences were ignored because the file is malformed.")
                return WorkspaceLayout()
            }
            parsed.toWorkspaceLayout()
        } catch (error: Exception) {
            warn("Workspace preferences could not be read; defaults will be used.", error)
            WorkspaceLayout()
        }
    }

    override fun save(layout: WorkspaceLayout): Boolean {
        val normalized = layout.normalized()
        val target = settingsFile.toAbsolutePath().normalize()
        val parent = target.parent
        var temporaryFile: Path? = null
        return try {
            Files.createDirectories(parent)
            temporaryFile = Files.createTempFile(parent, ".workspace-", ".tmp")
            Files.writeString(
                temporaryFile,
                normalized.asToml(),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            moveIntoPlace(temporaryFile, target)
            temporaryFile = null
            true
        } catch (error: Exception) {
            warn("Workspace preferences could not be saved.", error)
            false
        } finally {
            temporaryFile?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: Exception) {
                    // A failed best-effort cleanup must not affect application shutdown.
                }
            }
        }
    }

    private fun TomlParseResult.toWorkspaceLayout(): WorkspaceLayout {
        val schema = runCatching { getLong("schema") }.getOrNull()
        if (schema != WorkspaceLayout.SCHEMA_VERSION.toLong()) {
            warn("Workspace preferences use an unsupported schema; defaults will be used.")
            return WorkspaceLayout()
        }
        return WorkspaceLayout(
            projectPanelWidthDp = finiteDouble(
                "project_panel_width_dp",
                WorkspaceLayout.PROJECT_DEFAULT_WIDTH_DP
            ),
            previewPanelWidthDp = finiteDouble(
                "preview_panel_width_dp",
                WorkspaceLayout.PREVIEW_DEFAULT_WIDTH_DP
            ),
            projectPanelCollapsed = booleanOrDefault("project_panel_collapsed", false),
            previewPanelCollapsed = booleanOrDefault("preview_panel_collapsed", false),
            lastProjectPanelWidthDp = finiteDouble(
                "last_project_panel_width_dp",
                WorkspaceLayout.PROJECT_DEFAULT_WIDTH_DP
            ),
            lastPreviewPanelWidthDp = finiteDouble(
                "last_preview_panel_width_dp",
                WorkspaceLayout.PREVIEW_DEFAULT_WIDTH_DP
            )
        ).normalized()
    }

    private fun TomlParseResult.finiteDouble(key: String, default: Double): Double =
        runCatching { getDouble(key) }.getOrNull()?.takeIf(Double::isFinite) ?: default

    private fun TomlParseResult.booleanOrDefault(key: String, default: Boolean): Boolean =
        runCatching { getBoolean(key) }.getOrNull() ?: default

    private fun WorkspaceLayout.asToml(): String = buildString {
        appendLine("schema = $schemaVersion")
        appendLine("project_panel_width_dp = ${projectPanelWidthDp.tomlNumber()}")
        appendLine("preview_panel_width_dp = ${previewPanelWidthDp.tomlNumber()}")
        appendLine("project_panel_collapsed = $projectPanelCollapsed")
        appendLine("preview_panel_collapsed = $previewPanelCollapsed")
        appendLine("last_project_panel_width_dp = ${lastProjectPanelWidthDp.tomlNumber()}")
        appendLine("last_preview_panel_width_dp = ${lastPreviewPanelWidthDp.tomlNumber()}")
    }

    private fun Double.tomlNumber(): String = String.format(Locale.ROOT, "%.3f", this)

    private fun warn(message: String, error: Exception? = null) {
        if (error == null) {
            LOGGER.warning(message)
        } else {
            val detail = error.message
                ?.replace(Regex("\\s+"), " ")
                ?.take(MAX_DIAGNOSTIC_DETAIL_CHARS)
                .orEmpty()
            LOGGER.warning(
                "$message ${error::class.simpleName}" +
                    if (detail.isEmpty()) "" else ": $detail"
            )
        }
    }

    companion object {
        const val MAX_SETTINGS_BYTES = 64L * 1024L
        private const val MAX_DIAGNOSTIC_DETAIL_CHARS = 240
        private val LOGGER = Logger.getLogger(FileWorkspacePreferencesStore::class.java.name)
    }
}

fun interface PendingWorkspaceWrite {
    fun cancel()
}

interface WorkspaceWriteScheduler : AutoCloseable {
    fun schedule(delayMillis: Long, task: () -> Unit): PendingWorkspaceWrite
}

class ExecutorWorkspaceWriteScheduler(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "aetex-workspace-preferences").apply { isDaemon = true }
        }
) : WorkspaceWriteScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): PendingWorkspaceWrite {
        val future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
        return PendingWorkspaceWrite { future.cancel(false) }
    }

    override fun close() {
        executor.shutdown()
    }
}

class WorkspacePreferencesCoordinator(
    private val store: WorkspacePreferencesStore,
    private val scheduler: WorkspaceWriteScheduler = ExecutorWorkspaceWriteScheduler(),
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS
) : AutoCloseable {
    private val lock = Any()
    private var revision = 0L
    private var latest = store.load().normalized()
    private var pending: PendingWorkspaceWrite? = null
    private var closed = false

    val initialLayout: WorkspaceLayout
        get() = synchronized(lock) { latest }

    fun requestSave(layout: WorkspaceLayout) {
        synchronized(lock) {
            if (closed) return
            latest = layout.normalized()
            val expectedRevision = ++revision
            pending?.cancel()
            pending = scheduler.schedule(debounceMillis) {
                persistIfCurrent(expectedRevision)
            }
        }
    }

    fun flush(layout: WorkspaceLayout = initialLayout): Boolean =
        synchronized(lock) {
            if (closed) return false
            latest = layout.normalized()
            ++revision
            pending?.cancel()
            pending = null
            store.save(latest)
        }

    fun close(finalLayout: WorkspaceLayout): Boolean {
        val result = synchronized(lock) {
            if (closed) return true
            latest = finalLayout.normalized()
            closed = true
            ++revision
            pending?.cancel()
            pending = null
            store.save(latest)
        }
        scheduler.close()
        return result
    }

    override fun close() {
        close(initialLayout)
    }

    private fun persistIfCurrent(expectedRevision: Long) {
        synchronized(lock) {
            if (closed || expectedRevision != revision) return
            pending = null
            store.save(latest)
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 350L
    }
}

fun defaultWorkspacePreferencesCoordinator(): WorkspacePreferencesCoordinator =
    WorkspacePreferencesCoordinator(FileWorkspacePreferencesStore())

fun transientWorkspacePreferencesCoordinator(
    initialLayout: WorkspaceLayout = WorkspaceLayout()
): WorkspacePreferencesCoordinator = WorkspacePreferencesCoordinator(
    store = object : WorkspacePreferencesStore {
        override fun load() = initialLayout
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

fun defaultWorkspaceSettingsFile(
    environment: Map<String, String> = System.getenv(),
    properties: Map<String, String> = System.getProperties()
        .stringPropertyNames()
        .associateWith(System::getProperty)
): Path {
    val userHome = properties["user.home"].orEmpty()
    val osName = properties["os.name"].orEmpty().lowercase(Locale.ROOT)
    return when {
        osName.contains("win") -> {
            val base = environment["APPDATA"].takeUnless { it.isNullOrBlank() }
                ?: Path.of(userHome, "AppData", "Roaming").toString()
            Path.of(base, "AeTeX", SETTINGS_FILE_NAME)
        }

        osName.contains("mac") -> Path.of(
            userHome,
            "Library",
            "Application Support",
            "AeTeX",
            SETTINGS_FILE_NAME
        )

        else -> {
            val base = environment["XDG_CONFIG_HOME"].takeUnless { it.isNullOrBlank() }
                ?: Path.of(userHome, ".config").toString()
            Path.of(base, "aetex", SETTINGS_FILE_NAME)
        }
    }
}

private fun replaceFile(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private const val SETTINGS_FILE_NAME = "workspace.toml"
