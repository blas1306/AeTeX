package dev.aetex.compilation

import dev.aetex.app.AeTeXState
import dev.aetex.preview.coordination.PreviewManager
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewState
import dev.aetex.preview.writePdf
import dev.aetex.project.CreateProjectRequest
import dev.aetex.project.ProjectCreationResult
import dev.aetex.project.ProjectInitializationPlanResult
import dev.aetex.project.ProjectInitializationResult
import dev.aetex.project.ProjectLoader
import dev.aetex.project.ProjectProvisioningService
import dev.aetex.project.TeXProject
import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.MainDocumentState
import dev.aetex.project.configuration.PersistedConfigurationStatus
import dev.aetex.project.configuration.TeXEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectory
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir

class BuildPipelineIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `configured project builds from canonical root and reaches preview`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val fixture = createFixture("project with spaces")
        val loaded = ProjectLoader().load(fixture.projectRoot)
        assertEquals(1, loaded.project.persistedConfiguration?.schema)
        assertEquals(Path.of("src/main.tex"), loaded.project.persistedConfiguration?.main)
        assertEquals(null, loaded.project.persistedConfiguration?.engine)
        assertEquals(null, loaded.project.persistedConfiguration?.strategy)
        assertEquals(null, loaded.project.persistedConfiguration?.output)
        assertEquals(
            PersistedConfigurationStatus.LOADED,
            loaded.project.effectiveConfiguration.persistedStatus
        )
        assertEquals(
            fixture.projectRoot.resolve("src/main.tex").toRealPath(),
            assertIs<MainDocumentState.Confirmed>(
                loaded.project.effectiveConfiguration.mainDocument
            ).path
        )
        assertEquals(TeXEngine.PDF_LATEX, loaded.project.effectiveConfiguration.engine?.value)
        assertEquals(
            ConfigurationValueSource.DEFAULT,
            loaded.project.effectiveConfiguration.engine?.source
        )
        assertEquals(
            fixture.projectRoot.resolve("build"),
            loaded.project.effectiveConfiguration.outputDirectory?.value
        )
        val previewState = AtomicReference<PreviewState>(PreviewState.Empty)
        val previewReady = CountDownLatch(1)

        manager(fixture).use { manager ->
            PreviewManager(manager, fixture.projectRoot).use { preview ->
                preview.addStateListener { state ->
                    previewState.set(state)
                    if (state is PreviewState.Ready && !state.stale) {
                        previewReady.countDown()
                    }
                }.use {
                    val accepted = assertIs<BuildRequestResult.Accepted>(
                        manager.requestBuild(loaded.project)
                    )
                    val result = manager.awaitResult(accepted.session.id, Duration.ofSeconds(5))

                    assertEquals(BuildState.SUCCEEDED, result?.state, result?.capturedOutput())
                    assertEquals(fixture.projectRoot, result?.plan?.workingDirectory)
                    assertEquals(
                        fixture.projectRoot.resolve("src/main.tex").toRealPath(),
                        result?.plan?.invocation?.mainDocument
                    )
                    assertEquals(
                        fixture.projectRoot.resolve("build/main.pdf"),
                        result?.plan?.primaryPdf
                    )
                    assertFalse("--" in result!!.plan.arguments)
                    assertEquals(fixture.projectRoot.toString(), fixture.record.resolve("cwd.txt").readText().trim())
                    assertEquals(result.plan.arguments, fixture.record.resolve("arguments.txt").readLines())
                    assertEquals(
                        listOf(
                            "AETEX_PIPELINE_MARKER=pipeline-visible",
                            "PATH=${result.plan.environment.values.getValue("PATH")}"
                        ),
                        fixture.record.resolve("environment.txt").readLines()
                    )
                    assertEquals(
                        result.plan.primaryPdf.toString(),
                        fixture.record.resolve("output.txt").readText().trim()
                    )
                    assertEquals(0, result.processEvidence.exitCode)
                    assertEquals("fake stdout\n", result.captured(BuildLogOrigin.STDOUT))
                    assertEquals("fake stderr\n", result.captured(BuildLogOrigin.STDERR))
                    assertTrue(Files.isRegularFile(result.plan.primaryPdf))
                    assertTrue(previewReady.await(5, TimeUnit.SECONDS))
                    assertIs<PreviewState.Ready>(previewState.get())
                }
            }
        }
    }

    @Test
    fun `missing executable at launch produces a typed diagnostic`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val fixture = createFixture("launch failure")
        val project = ProjectLoader().load(fixture.projectRoot).project
        val launcher = ProcessLauncher {
            throw IOException("No such executable after planning")
        }

        manager(fixture, launcher = launcher).use { manager ->
            val result = requestAndAwait(manager, project)

            assertEquals(BuildState.FAILED, result.state)
            assertEquals(BuildFailureKind.PROCESS_START_FAILURE, result.failure?.kind)
            assertFalse(result.processEvidence.started)
            assertTrue(result.userSummary().contains("could not start latexmk"))
            assertTrue(result.userSummary().contains("No such executable"))
            assertTrue(result.diagnostics.any { it.kind == DiagnosticKind.PROCESS_START })
        }
    }

    @Test
    fun `non-zero exit exposes bounded stderr and exit code`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val fixture = createFixture("non zero")
        val project = ProjectLoader().load(fixture.projectRoot).project
        val stalePdf = fixture.projectRoot.resolve("build/main.pdf")
        Files.createDirectories(stalePdf.parent)
        Files.copy(fixture.pdfTemplate, stalePdf)

        manager(fixture, outputMode = "missing", exitCode = 7).use { manager ->
            val result = requestAndAwait(manager, project)

            assertEquals(BuildState.FAILED, result.state)
            assertEquals(BuildFailureKind.NON_ZERO_EXIT, result.failure?.kind)
            assertEquals(7, result.processEvidence.exitCode)
            assertEquals("fake stdout\n", result.captured(BuildLogOrigin.STDOUT))
            assertEquals("fake stderr\n", result.captured(BuildLogOrigin.STDERR))
            assertEquals(
                ArtifactStatus.REUSED_UNCHANGED,
                result.artifacts.single { it.expected.role == ArtifactRole.PRIMARY_PDF }.status
            )
            assertTrue(result.userSummary().contains("exited with code 7"))
            assertTrue(result.userSummary().contains("stderr: fake stderr"))
            assertTrue(result.userSummary().length <= 1_200)
        }
    }

    @Test
    fun `zero exit without expected artifact is a distinct artifact failure`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val fixture = createFixture("missing artifact")
        val project = ProjectLoader().load(fixture.projectRoot).project

        manager(fixture, outputMode = "missing").use { manager ->
            val result = requestAndAwait(manager, project)

            assertEquals(BuildState.FAILED, result.state)
            assertEquals(BuildFailureKind.EXPECTED_ARTIFACT_MISSING, result.failure?.kind)
            assertEquals(0, result.processEvidence.exitCode)
            assertEquals(listOf(result.plan.primaryPdf), result.missingRequiredArtifacts.map(ExpectedArtifact::path))
            assertTrue(result.userSummary().contains("expected artifact build/main.pdf"))
        }
    }

    @Test
    fun `zero exit with non-PDF artifact is a distinct invalid artifact failure`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val fixture = createFixture("invalid artifact")
        val project = ProjectLoader().load(fixture.projectRoot).project

        manager(fixture, outputMode = "invalid").use { manager ->
            val result = requestAndAwait(manager, project)

            assertEquals(BuildState.FAILED, result.state)
            assertEquals(BuildFailureKind.EXPECTED_ARTIFACT_INVALID, result.failure?.kind)
            assertEquals(
                ArtifactStatus.INVALID,
                result.artifacts.single { it.expected.role == ArtifactRole.PRIMARY_PDF }.status
            )
            assertTrue(result.userSummary().contains("invalid artifact at build/main.pdf"))
        }
    }

    @Test
    fun `UI feedback exposes terminal diagnostic while preview keeps stale-state wording`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val fixture = createFixture("application feedback")
        val process = ReleasableProcess(exitCode = 9)
        val started = CountDownLatch(1)
        val manager = manager(
            fixture,
            launcher = ProcessLauncher {
                started.countDown()
                process
            }
        )
        val state = AeTeXState(compilationManagerFactory = { manager })
        assertTrue(state.openProject(fixture.projectRoot))
        val accepted = assertIs<BuildRequestResult.Accepted>(state.requestBuild())
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val terminalPublished = CountDownLatch(1)
        manager.addSessionListener {
            if (it.id == accepted.session.id && it.state.isTerminal) {
                terminalPublished.countDown()
            }
        }.use {
            process.complete()
            assertTrue(terminalPublished.await(5, TimeUnit.SECONDS))
        }

        assertTrue(state.message?.isError == true)
        assertTrue(state.message?.text?.contains("latexmk exited with code 9") == true)
        assertTrue(state.message?.text?.contains("stderr: controlled stderr") == true)
        val preview = assertIs<PreviewState.GenerationError>(state.previewState)
        assertEquals(PreviewErrorKind.BUILD_FAILED, preview.error.kind)
        assertEquals(
            "The latest compilation failed; the previous preview is stale.",
            preview.error.message
        )
        state.shutdown()
    }

    @Test
    fun `newly provisioned project builds through canonical loader and preview`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val created = assertIs<ProjectCreationResult.Created>(
            ProjectProvisioningService().create(
                CreateProjectRequest("new pipeline project", temporaryDirectory)
            )
        )
        val canonical = ProjectLoader().load(created.loadResult.project.rootDirectory).project
        val fixture = createFixtureFor(canonical.rootDirectory, "new project record")

        val result = assertSuccessfulPreviewBuild(canonical, fixture)

        assertEquals(canonical.rootDirectory.resolve("build/main.pdf"), result.plan.primaryPdf)
    }

    @Test
    fun `initialized directory builds through canonical loader and preview`() {
        assumeFalse(HostPlatform.current().isWindows, "The deterministic fake executable uses /bin/sh.")
        val root = temporaryDirectory.resolve("initialized pipeline project").createDirectory()
        val provisioning = ProjectProvisioningService()
        val plan = assertIs<ProjectInitializationPlanResult.Ready>(
            provisioning.planInitialization(root)
        ).plan
        val initialized = assertIs<ProjectInitializationResult.Initialized>(
            provisioning.initialize(plan)
        )
        val canonical = ProjectLoader().load(initialized.loadResult.project.rootDirectory).project
        val fixture = createFixtureFor(canonical.rootDirectory, "initialized record")

        val result = assertSuccessfulPreviewBuild(canonical, fixture)

        assertEquals(canonical.rootDirectory.resolve("build/main.pdf"), result.plan.primaryPdf)
    }

    private fun createFixture(projectName: String): PipelineFixture {
        val projectRoot = temporaryDirectory.resolve(projectName).createDirectory().toRealPath()
        projectRoot.resolve("src").createDirectory()
            .resolve("main.tex")
            .writeText("\\documentclass{article}\n\\begin{document}Test\\end{document}\n")
        projectRoot.resolve(".aetex").createDirectory()
            .resolve("project.toml")
            .writeText("schema = 1\nmain = \"src/main.tex\"\n")
        return createFixtureFor(projectRoot, "$projectName record")
    }

    private fun createFixtureFor(projectRoot: Path, fixtureName: String): PipelineFixture {
        val fixtureRoot = temporaryDirectory.resolve(fixtureName).createDirectory()
        val toolDirectory = fixtureRoot.resolve("fake tools").createDirectory()
        val record = fixtureRoot.resolve("process record").createDirectory()
        val pdfTemplate = fixtureRoot.resolve("template.pdf")
        writePdf(pdfTemplate)
        val latexmk = toolDirectory.resolve("latexmk")
        latexmk.writeText(FAKE_LATEXMK)
        assertTrue(latexmk.toFile().setExecutable(true))
        val engine = toolDirectory.resolve("pdflatex")
        engine.writeText("#!/bin/sh\nexit 0\n")
        assertTrue(engine.toFile().setExecutable(true))
        return PipelineFixture(projectRoot, toolDirectory, record, pdfTemplate)
    }

    private fun manager(
        fixture: PipelineFixture,
        launcher: ProcessLauncher = JvmProcessLauncher(),
        outputMode: String = "valid",
        exitCode: Int = 0
    ): CompilationManager {
        val environment = linkedMapOf(
            "PATH" to fixture.toolDirectory.toString(),
            "AETEX_TEST_RECORD" to fixture.record.toString(),
            "AETEX_TEST_PDF_TEMPLATE" to fixture.pdfTemplate.toString(),
            "AETEX_PIPELINE_MARKER" to "pipeline-visible",
            "AETEX_FAKE_OUTPUT_MODE" to outputMode,
            "AETEX_FAKE_EXIT_CODE" to exitCode.toString()
        )
        return CompilationManager(
            planner = BuildPlanner(environmentProvider = EnvironmentProvider { environment }),
            launcher = launcher,
            logFactory = FileBuildLogFactory(fixture.record.resolve("logs")),
            coordinationStore = FileCoordinationStore(fixture.record.resolve("coordination"))
        )
    }

    private fun requestAndAwait(
        manager: CompilationManager,
        project: TeXProject
    ): BuildResult {
        val accepted = assertIs<BuildRequestResult.Accepted>(manager.requestBuild(project))
        return assertNotNull(manager.awaitResult(accepted.session.id, Duration.ofSeconds(5)))
    }

    private fun assertSuccessfulPreviewBuild(
        project: TeXProject,
        fixture: PipelineFixture
    ): BuildResult {
        val ready = CountDownLatch(1)
        val state = AtomicReference<PreviewState>(PreviewState.Empty)
        manager(fixture).use { manager ->
            PreviewManager(manager, project.rootDirectory).use { preview ->
                preview.addStateListener {
                    state.set(it)
                    if (it is PreviewState.Ready && !it.stale) ready.countDown()
                }.use {
                    val result = requestAndAwait(manager, project)
                    assertEquals(BuildState.SUCCEEDED, result.state, result.capturedOutput())
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    val previewReady = assertIs<PreviewState.Ready>(state.get())
                    assertEquals(result.sessionId, previewReady.document.provenance.sessionId)
                    assertEquals(result.plan.primaryPdf, result.artifacts.single {
                        it.expected.role == ArtifactRole.PRIMARY_PDF
                    }.expected.path)
                    return result
                }
            }
        }
    }

    private fun BuildResult.captured(origin: BuildLogOrigin): String =
        logs.readEvents()
            .filter { it.origin == origin }
            .joinToString(separator = "") { it.decodedText.orEmpty() }

    private fun BuildResult.capturedOutput(): String =
        "failure=$failure, exit=${processEvidence.exitCode}, " +
            "stdout=${captured(BuildLogOrigin.STDOUT)}, stderr=${captured(BuildLogOrigin.STDERR)}"

    private data class PipelineFixture(
        val projectRoot: Path,
        val toolDirectory: Path,
        val record: Path,
        val pdfTemplate: Path
    )

    private class ReleasableProcess(
        private val exitCode: Int
    ) : ManagedProcess {
        private val completed = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        override val stdout: InputStream = ByteArrayInputStream("controlled stdout\n".toByteArray())
        override val stderr: InputStream = ByteArrayInputStream("controlled stderr\n".toByteArray())
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val identity = ProcessIdentity(41_001L, null)

        override fun isAlive(): Boolean = alive.get()

        override fun waitFor(timeout: Duration): Boolean =
            completed.await(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)

        override fun exitCodeOrNull(): Int? = exitCode.takeUnless { alive.get() }

        override fun descendants(): List<ProcessIdentity> = emptyList()

        override fun destroyGracefully() {
            complete()
        }

        override fun destroyForcibly() {
            complete()
        }

        fun complete() {
            alive.set(false)
            completed.countDown()
        }

        override fun close() {
            stdin.close()
            stdout.close()
            stderr.close()
        }
    }

    private companion object {
        val FAKE_LATEXMK = """
            |#!/bin/sh
            |record="${'$'}{AETEX_TEST_RECORD:?}"
            |pwd -P > "${'$'}record/cwd.txt"
            |: > "${'$'}record/arguments.txt"
            |outdir=
            |main=
            |bad_option=false
            |for argument do
            |    printf '%s\n' "${'$'}argument" >> "${'$'}record/arguments.txt"
            |    case "${'$'}argument" in
            |        -outdir=*) outdir=${'$'}{argument#-outdir=} ;;
            |        --) bad_option=true ;;
            |        *.tex) main="${'$'}argument" ;;
            |    esac
            |done
            |printf 'AETEX_PIPELINE_MARKER=%s\nPATH=%s\n' \
            |    "${'$'}{AETEX_PIPELINE_MARKER-}" "${'$'}{PATH-}" > "${'$'}record/environment.txt"
            |printf 'fake stdout\n'
            |printf 'fake stderr\n' >&2
            |if [ "${'$'}bad_option" = true ]; then
            |    printf 'Latexmk: - unknown option\n' >&2
            |    exit 10
            |fi
            |/usr/bin/mkdir -p "${'$'}outdir"
            |filename=${'$'}{main##*/}
            |stem=${'$'}{filename%.tex}
            |output="${'$'}outdir/${'$'}stem.pdf"
            |case "${'$'}{AETEX_FAKE_OUTPUT_MODE-valid}" in
            |    valid) /usr/bin/cp "${'$'}AETEX_TEST_PDF_TEMPLATE" "${'$'}output" ;;
            |    invalid) printf 'not a PDF\n' > "${'$'}output" ;;
            |    missing) ;;
            |esac
            |if [ -f "${'$'}output" ]; then
            |    printf '%s\n' "${'$'}output" > "${'$'}record/output.txt"
            |fi
            |exit "${'$'}{AETEX_FAKE_EXIT_CODE-0}"
        """.trimMargin()
    }
}
