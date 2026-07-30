package dev.aetex.compilation

import dev.aetex.project.configuration.EffectiveProjectConfiguration
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.TeXEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BuildPlannerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `produces a fully resolved shell free latexmk plan`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val main = Files.writeString(root.resolve("nested main.tex"), "\\documentclass{article}")
        val tools = createToolDirectory(temporaryDirectory.resolve("external"), HostPlatform.WINDOWS, TeXEngine.XE_LATEX)
        val planner = planner(tools, HostPlatform.WINDOWS)

        val plan = assertIs<PlanningResult.Success>(
            planner.plan(root, readyConfiguration(root, main, root.resolve("generated files"), TeXEngine.XE_LATEX))
        ).plan

        assertEquals(root.toRealPath(), plan.workingDirectory)
        assertEquals(ToolKind.LATEXMK, plan.invocation.coordinator.kind)
        assertEquals(ToolKind.XELATEX, plan.invocation.engineTool.kind)
        assertTrue("-norc" in plan.arguments)
        assertTrue("-xelatex" in plan.arguments)
        assertTrue(plan.arguments.last() == main.toRealPath().toString())
        assertFalse(plan.arguments.any { it.contains("cmd /c") || it.contains("bash -c") })
        assertEquals(root.resolve("generated files").resolve("nested main.pdf"), plan.primaryPdf)
    }

    @Test
    fun `not ready configuration fails before discovery and creates no session`() {
        var discoveryCalls = 0
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val planner = BuildPlanner(
            toolDiscoverer = ToolDiscoverer { _, _, _, _ ->
                discoveryCalls += 1
                error("must not run")
            }
        )
        val result = planner.plan(
            root,
            EffectiveProjectConfiguration(
                PersistedConfigurationStatus.ABSENT,
                MainDocumentState.Unavailable,
                null,
                null,
                null
            )
        )

        assertEquals(0, discoveryCalls)
        assertEquals(BuildFailureKind.INVALID_CONFIGURATION, assertIs<PlanningResult.Failure>(result).failure.kind)
    }

    @Test
    fun `missing latexmk is a typed failure without engine fallback`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val main = Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        val tools = Files.createDirectory(temporaryDirectory.resolve("tools"))
        Files.writeString(tools.resolve("pdflatex.exe"), "x")
        val result = planner(tools, HostPlatform.WINDOWS).plan(
            root,
            readyConfiguration(root, main)
        )

        val failure = assertIs<PlanningResult.Failure>(result).failure
        assertEquals(BuildFailureKind.TOOL_UNAVAILABLE, failure.kind)
        assertEquals(ToolKind.LATEXMK, failure.requiredTool)
    }

    @Test
    fun `missing exact configured engine is a typed failure`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val main = Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        val tools = Files.createDirectory(temporaryDirectory.resolve("tools"))
        Files.writeString(tools.resolve("latexmk.exe"), "x")
        Files.writeString(tools.resolve("pdflatex.exe"), "x")
        val result = planner(tools, HostPlatform.WINDOWS).plan(
            root,
            readyConfiguration(root, main, engine = TeXEngine.LUA_LATEX)
        )

        val failure = assertIs<PlanningResult.Failure>(result).failure
        assertEquals(ToolKind.LUALATEX, failure.requiredTool)
    }

    @Test
    fun `invalid main and output fail planning`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val tools = createToolDirectory(temporaryDirectory.resolve("tools"), HostPlatform.WINDOWS)
        val planner = planner(tools, HostPlatform.WINDOWS)
        val missingMain = assertIs<PlanningResult.Failure>(
            planner.plan(root, readyConfiguration(root, root.resolve("missing.tex")))
        )
        assertEquals(BuildFailureKind.PLANNING_FAILURE, missingMain.failure.kind)

        val main = Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        val outputFile = Files.writeString(root.resolve("output"), "not a directory")
        val invalidOutput = assertIs<PlanningResult.Failure>(
            planner.plan(root, readyConfiguration(root, main, outputFile))
        )
        assertEquals(BuildFailureKind.INVALID_OUTPUT, invalidOutput.failure.kind)
    }

    @Test
    fun `sanitized path places exact engine then coordinator and removes unsafe entries`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val main = Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        val coordinator = Files.createDirectory(temporaryDirectory.resolve("coordinator"))
        Files.writeString(coordinator.resolve("latexmk.exe"), "x")
        val engine = Files.createDirectory(temporaryDirectory.resolve("engine"))
        Files.writeString(engine.resolve("pdflatex.exe"), "x")
        val unsafe = Files.createDirectory(root.resolve("tools"))
        val path = listOf(unsafe, coordinator, engine, coordinator).joinToString(";")
        val planner = BuildPlanner(
            environmentProvider = EnvironmentProvider {
                linkedMapOf("Path" to path, "SECRET_TOKEN" to "not-rendered")
            },
            platform = HostPlatform.WINDOWS
        )
        val plan = assertIs<PlanningResult.Success>(
            planner.plan(root, readyConfiguration(root, main))
        ).plan

        assertEquals(
            listOf(engine.toRealPath(), coordinator.toRealPath()),
            plan.environment.values.getValue("PATH").split(';').map(Path::of)
        )
        assertEquals("not-rendered", plan.environment.values["SECRET_TOKEN"])
        assertEquals(1, plan.environment.values.keys.count { it.equals("PATH", true) })
    }

    @Test
    fun `detects project latexmkrc as ignored evidence`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val main = Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        val rc = Files.writeString(root.resolve(".latexmkrc"), "system('bad')")
        val tools = createToolDirectory(temporaryDirectory.resolve("tools"), HostPlatform.WINDOWS)

        val plan = assertIs<PlanningResult.Success>(
            planner(tools, HostPlatform.WINDOWS).plan(root, readyConfiguration(root, main))
        ).plan

        assertEquals(listOf(rc), plan.invocation.ignoredInitializationFiles)
        assertEquals("-norc", plan.arguments.first())
    }

    @Test
    fun `windows rejects case duplicate environment keys before discovery`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("project"))
        val main = Files.writeString(root.resolve("main.tex"), "\\documentclass{article}")
        var discoveryCalls = 0
        val planner = BuildPlanner(
            toolDiscoverer = ToolDiscoverer { _, _, _, _ ->
                discoveryCalls += 1
                error("must not discover")
            },
            environmentProvider = EnvironmentProvider {
                linkedMapOf("PATH" to "one", "Path" to "two")
            },
            platform = HostPlatform.WINDOWS
        )

        val failure = assertIs<PlanningResult.Failure>(
            planner.plan(root, readyConfiguration(root, main))
        ).failure

        assertEquals(BuildFailureKind.PLANNING_FAILURE, failure.kind)
        assertEquals(0, discoveryCalls)
    }

    private fun planner(tools: Path, platform: HostPlatform) = BuildPlanner(
        environmentProvider = EnvironmentProvider { mapOf("PATH" to tools.toString()) },
        platform = platform
    )
}
