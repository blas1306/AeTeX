package dev.aetex.compilation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.jupiter.api.io.TempDir

class PathAndArtifactTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `main content may be safely replaced at the same planned path`() {
        val root = temporaryDirectory.resolve("project")
        Files.createDirectories(root.resolve("build"))
        Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        Files.writeString(root.resolve("build").resolve("main.pdf"), "pdf")
        val plan = createPlan(root)
        Files.writeString(plan.invocation.mainDocument, "\\documentclass{book}\nchanged")
        manager().use { manager ->
            val session = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan)).session

            assertEquals(BuildState.SUCCEEDED, manager.awaitResult(session.id, Duration.ofSeconds(3))?.state)
        }
    }

    @Test
    fun `disappeared main fails before process start`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        Files.delete(plan.invocation.mainDocument)
        val launcher = CountingLauncher()
        manager(launcher).use { manager ->
            val session = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan)).session
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(BuildFailureKind.UNSAFE_PATH_CHANGE, result?.failure?.kind)
            assertEquals(0, launcher.starts)
        }
    }

    @Test
    fun `output converted to file fails before process start`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        Files.writeString(plan.invocation.outputDirectory, "file")
        val launcher = CountingLauncher()
        manager(launcher).use { manager ->
            val session = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan)).session
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(0, launcher.starts)
        }
    }

    @Test
    fun `output is created one confined segment at a time`() {
        val output = temporaryDirectory.resolve("project").resolve("a").resolve("b").resolve("output")
        val plan = createPlan(temporaryDirectory.resolve("project"), output)
        manager(CountingLauncher(createPdf = true)).use { manager ->
            val session = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan)).session

            assertEquals(
                BuildState.SUCCEEDED,
                manager.awaitResult(session.id, Duration.ofSeconds(3))?.state
            )
            assertNotNull(output.toRealPath())
        }
    }

    @Test
    fun `replaced coordinator executable fails before process start`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        Files.delete(plan.invocation.coordinator.executable)
        Files.writeString(plan.invocation.coordinator.executable, "replacement with different identity")
        val launcher = CountingLauncher()

        manager(launcher).use { manager ->
            val session = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan)).session
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(BuildFailureKind.TOOL_INVALID, result?.failure?.kind)
            assertEquals(0, launcher.starts)
        }
    }

    @Test
    fun `recreated existing output directory fails identity revalidation`() {
        val root = temporaryDirectory.resolve("project")
        val output = Files.createDirectories(root.resolve("build"))
        val plan = createPlan(root, output)
        Files.delete(output)
        Files.createDirectory(output)
        Files.setAttribute(
            output,
            "basic:creationTime",
            FileTime.from(Instant.parse("2000-01-01T00:00:00Z"))
        )
        val launcher = CountingLauncher()

        manager(launcher).use { manager ->
            val session = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(plan)).session
            val result = manager.awaitResult(session.id, Duration.ofSeconds(3))

            assertEquals(BuildState.FAILED, result?.state)
            assertEquals(BuildFailureKind.UNSAFE_PATH_CHANGE, result?.failure?.kind)
            assertEquals(0, launcher.starts)
        }
    }

    private fun manager(launcher: ProcessLauncher = CountingLauncher()) = CompilationManager(
        launcher = launcher,
        logFactory = FileBuildLogFactory(temporaryDirectory.resolve("logs-${System.nanoTime()}")),
        coordinationStore = FileCoordinationStore(temporaryDirectory.resolve("coord-${System.nanoTime()}")),
        bootIdentityProvider = BootIdentityProvider { "boot" }
    )

    private class CountingLauncher(
        private val createPdf: Boolean = false
    ) : ProcessLauncher {
        var starts = 0
        override fun start(plan: BuildPlan): ManagedProcess {
            starts += 1
            if (createPdf) Files.writeString(plan.primaryPdf, "pdf")
            return object : ManagedProcess {
                override val stdout = ByteArrayInputStream(byteArrayOf())
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val stdin = ByteArrayOutputStream()
                override val identity = ProcessIdentity(777_777, Instant.now())
                override fun isAlive() = false
                override fun waitFor(timeout: Duration) = true
                override fun exitCodeOrNull() = 0
                override fun descendants() = emptyList<ProcessIdentity>()
                override fun destroyGracefully() = Unit
                override fun destroyForcibly() = Unit
                override fun close() = Unit
            }
        }
    }
}
