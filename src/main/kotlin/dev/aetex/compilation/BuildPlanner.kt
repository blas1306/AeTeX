package dev.aetex.compilation

import dev.aetex.project.TeXProject
import dev.aetex.project.configuration.CompilationStrategy
import dev.aetex.project.configuration.EffectiveProjectConfiguration
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.TeXEngine
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.Locale

fun interface EnvironmentProvider {
    fun snapshot(): Map<String, String>
}

class BuildPlanner(
    private val toolDiscoverer: ToolDiscoverer = PathToolDiscoverer(),
    private val pathValidator: CompilationPathValidator = CompilationPathValidator(),
    private val environmentProvider: EnvironmentProvider =
        EnvironmentProvider { LinkedHashMap(System.getenv()) },
    private val platform: HostPlatform = HostPlatform.current(),
    private val windowsCharset: Charset = Charset.defaultCharset()
) {
    fun plan(project: TeXProject): PlanningResult =
        plan(project.rootDirectory, project.effectiveConfiguration)

    fun plan(
        projectRoot: Path,
        configuration: EffectiveProjectConfiguration
    ): PlanningResult {
        if (!configuration.isReady) {
            return PlanningResult.Failure(
                BuildFailure(
                    kind = BuildFailureKind.INVALID_CONFIGURATION,
                    message = "The effective project configuration is not ready for compilation."
                )
            )
        }
        val main = (configuration.mainDocument as? MainDocumentState.Confirmed)?.path
            ?: return PlanningResult.Failure(
                BuildFailure(
                    BuildFailureKind.INVALID_CONFIGURATION,
                    "Compilation requires a confirmed main document."
                )
            )
        val engineValue = configuration.engine
            ?: return missingEffectiveValue("engine")
        val strategyValue = configuration.strategy
            ?: return missingEffectiveValue("strategy")
        val outputValue = configuration.outputDirectory
            ?: return missingEffectiveValue("output")
        if (strategyValue.value != CompilationStrategy.LATEXMK) {
            return PlanningResult.Failure(
                BuildFailure(
                    BuildFailureKind.PLANNING_FAILURE,
                    "The configured compilation strategy is not supported."
                )
            )
        }

        val paths = when (
            val validation = pathValidator.validateForPlanning(projectRoot, main, outputValue.value)
        ) {
            is PathValidationResult.Valid -> validation.value
            is PathValidationResult.Invalid -> return PlanningResult.Failure(validation.failure)
        }
        val inheritedEnvironment = try {
            LinkedHashMap(environmentProvider.snapshot())
        } catch (error: Exception) {
            return PlanningResult.Failure(
                BuildFailure(
                    BuildFailureKind.PLANNING_FAILURE,
                    "The process environment could not be snapshotted.",
                    TechnicalCause.from(error)
                )
            )
        }
        if (platform.environmentKeysCaseInsensitive) {
            val duplicateKey = inheritedEnvironment.keys
                .groupBy { it.lowercase(Locale.ROOT) }
                .values
                .firstOrNull { it.size > 1 }
            if (duplicateKey != null) {
                return PlanningResult.Failure(
                    BuildFailure(
                        BuildFailureKind.PLANNING_FAILURE,
                        "The inherited environment contains ambiguous case-duplicate keys."
                    )
                )
            }
        }
        val coordinatorResult = try {
            toolDiscoverer.discover(
                ToolKind.LATEXMK,
                inheritedEnvironment,
                paths.projectRoot,
                platform
            )
        } catch (error: Exception) {
            return discoveryFailure(ToolKind.LATEXMK, error)
        }
        val coordinator = when (coordinatorResult) {
            is ToolDiscoveryResult.Found -> coordinatorResult
            is ToolDiscoveryResult.Unavailable ->
                return unavailable(ToolKind.LATEXMK, coordinatorResult.rejectedCandidates)
        }
        val engineKind = ToolKind.forEngine(engineValue.value)
        val engineResult = try {
            toolDiscoverer.discover(
                engineKind,
                inheritedEnvironment,
                paths.projectRoot,
                platform
            )
        } catch (error: Exception) {
            return discoveryFailure(engineKind, error)
        }
        val engine = when (engineResult) {
            is ToolDiscoveryResult.Found -> engineResult
            is ToolDiscoveryResult.Unavailable ->
                return unavailable(engineKind, engineResult.rejectedCandidates)
        }

        val sanitized = sanitizeEnvironment(
            inherited = inheritedEnvironment,
            engineDirectory = engine.tool.executable.parent,
            coordinatorDirectory = coordinator.tool.executable.parent,
            validatedDirectories = mergeValidatedDirectories(
                coordinator.pathEnvironment.directories,
                engine.pathEnvironment.directories
            )
        )
        val charset = if (platform.isWindows) windowsCharset else Charsets.UTF_8
        val basename = main.fileName.toString().removeSuffixIgnoreCase(".tex")
        val expected = listOf(
            ExpectedArtifact(paths.outputDirectory.resolve("$basename.pdf"), ArtifactRole.PRIMARY_PDF, true),
            ExpectedArtifact(paths.outputDirectory.resolve("$basename.log"), ArtifactRole.TEX_LOG, false),
            ExpectedArtifact(paths.outputDirectory.resolve("$basename.synctex.gz"), ArtifactRole.SYNCTEX, false),
            ExpectedArtifact(paths.outputDirectory.resolve("$basename.aux"), ArtifactRole.AUXILIARY, false),
            ExpectedArtifact(paths.outputDirectory.resolve("$basename.fdb_latexmk"), ArtifactRole.AUXILIARY, false),
            ExpectedArtifact(paths.outputDirectory.resolve("$basename.fls"), ArtifactRole.AUXILIARY, false)
        )
        val ignoredRc = listOf(
            paths.projectRoot.resolve("latexmkrc"),
            paths.projectRoot.resolve(".latexmkrc")
        ).filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        val invocation = ResolvedInvocation(
            coordinator = coordinator.tool,
            engineTool = engine.tool,
            engine = engineValue.value,
            strategy = strategyValue.value,
            provenance = ConfigurationProvenance(
                engine = engineValue.source,
                strategy = strategyValue.source,
                output = outputValue.source
            ),
            mainDocument = paths.mainDocument,
            outputDirectory = paths.outputDirectory,
            outputSpaceIdentity = paths.outputIdentity,
            ignoredInitializationFiles = ignoredRc
        )
        val arguments = latexmkArguments(
            engine = engineValue.value,
            output = paths.outputDirectory,
            main = paths.mainDocument
        )
        return PlanningResult.Success(
            BuildPlan.create(
                invocation = invocation,
                arguments = arguments,
                workingDirectory = paths.projectRoot,
                environment = BuildEnvironment.copied(sanitized, charset, platform),
                expectedFiles = expected
            )
        )
    }

    private fun unavailable(
        kind: ToolKind,
        rejections: List<RejectedToolCandidate>
    ) = PlanningResult.Failure(
        BuildFailure(
            kind = BuildFailureKind.TOOL_UNAVAILABLE,
            message = "Required tool ${kind.executableName} was not found in the validated PATH.",
            requiredTool = kind,
            toolRejections = rejections.toList()
        )
    )

    private fun missingEffectiveValue(name: String) = PlanningResult.Failure(
        BuildFailure(
            BuildFailureKind.INVALID_CONFIGURATION,
            "The effective $name value is unavailable."
        )
    )

    private fun discoveryFailure(kind: ToolKind, error: Exception) = PlanningResult.Failure(
        BuildFailure(
            BuildFailureKind.TOOL_UNAVAILABLE,
            "Discovery of required tool ${kind.executableName} failed.",
            TechnicalCause.from(error),
            requiredTool = kind
        )
    )

    private fun sanitizeEnvironment(
        inherited: Map<String, String>,
        engineDirectory: Path,
        coordinatorDirectory: Path,
        validatedDirectories: List<Path>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        inherited.forEach { (key, value) ->
            if (!(platform.environmentKeysCaseInsensitive && key.equals("PATH", true)) && key != "PATH") {
                result[key] = value
            }
        }
        val seen = mutableSetOf<String>()
        val ordered = buildList {
            listOf(engineDirectory, coordinatorDirectory).plus(validatedDirectories).forEach { path ->
                val key = if (platform.isWindows) {
                    path.toString().lowercase(Locale.ROOT)
                } else {
                    path.toString()
                }
                if (seen.add(key)) add(path)
            }
        }
        result["PATH"] = ordered.joinToString(platform.pathSeparator.toString())
        return result
    }

    private fun mergeValidatedDirectories(
        first: List<Path>,
        second: List<Path>
    ): List<Path> {
        val seen = mutableSetOf<String>()
        return buildList {
            (first + second).forEach { path ->
                val key = if (platform.isWindows) {
                    path.toString().lowercase(Locale.ROOT)
                } else {
                    path.toString()
                }
                if (seen.add(key)) add(path)
            }
        }
    }

    private fun latexmkArguments(
        engine: TeXEngine,
        output: Path,
        main: Path
    ): List<String> = listOf(
        "-norc",
        when (engine) {
            TeXEngine.PDF_LATEX -> "-pdf"
            TeXEngine.XE_LATEX -> "-xelatex"
            TeXEngine.LUA_LATEX -> "-lualatex"
        },
        "-interaction=nonstopmode",
        "-halt-on-error",
        "-file-line-error",
        "-outdir=${output}",
        "--",
        main.toString()
    )

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
