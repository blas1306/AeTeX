package dev.aetex.compilation

import dev.aetex.project.configuration.CompilationStrategy
import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.EffectiveConfigurationValue
import dev.aetex.project.configuration.EffectiveProjectConfiguration
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.TeXEngine
import java.nio.file.Files
import java.nio.file.Path

internal fun readyConfiguration(
    root: Path,
    main: Path = root.resolve("main.tex"),
    output: Path = root.resolve("build"),
    engine: TeXEngine = TeXEngine.PDF_LATEX
) = EffectiveProjectConfiguration(
    persistedStatus = PersistedConfigurationStatus.LOADED,
    mainDocument = MainDocumentState.Confirmed(main),
    engine = EffectiveConfigurationValue(engine, ConfigurationValueSource.EXPLICIT),
    strategy = EffectiveConfigurationValue(
        CompilationStrategy.LATEXMK,
        ConfigurationValueSource.DEFAULT
    ),
    outputDirectory = EffectiveConfigurationValue(output, ConfigurationValueSource.EXPLICIT)
)

internal fun createToolDirectory(
    root: Path,
    platform: HostPlatform = HostPlatform.current(),
    engine: TeXEngine = TeXEngine.PDF_LATEX
): Path {
    val tools = Files.createDirectories(root.resolve("tool chain"))
    listOf(ToolKind.LATEXMK, ToolKind.forEngine(engine)).forEach { kind ->
        val name = if (platform.isWindows) "${kind.executableName}.exe" else kind.executableName
        val file = Files.writeString(tools.resolve(name), "test executable")
        file.toFile().setExecutable(true)
    }
    return tools
}

internal fun createPlan(
    root: Path,
    output: Path = root.resolve("build"),
    platform: HostPlatform = HostPlatform.current(),
    engine: TeXEngine = TeXEngine.PDF_LATEX,
    environmentMutator: (MutableMap<String, String>) -> Unit = {}
): BuildPlan {
    Files.createDirectories(root)
    val main = root.resolve("main.tex")
    if (!Files.exists(main)) Files.writeString(main, "\\documentclass{article}\n")
    val tools = createToolDirectory(root.parent.resolve("${root.fileName}-external"), platform, engine)
    val environment = linkedMapOf("PATH" to tools.toString(), "AETEX_TEST" to "value")
    environmentMutator(environment)
    val planner = BuildPlanner(
        environmentProvider = EnvironmentProvider { environment },
        platform = platform
    )
    return (planner.plan(root, readyConfiguration(root, main, output, engine)) as PlanningResult.Success).plan
}
