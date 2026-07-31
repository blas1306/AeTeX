package dev.aetex.project

import dev.aetex.project.configuration.AtomicNewProjectFileWriter
import dev.aetex.project.configuration.NewProjectFileWriter
import dev.aetex.project.configuration.ProjectConfigurationLoadResult
import dev.aetex.project.configuration.ProjectConfigurationLoader
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectProvisioningServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `realistic service integration preserves project format and unrelated files`() {
        val integrationRoot = Files.createDirectory(temporaryDirectory.resolve("integration"))
        val service = ProjectProvisioningService()

        val created = assertIs<ProjectCreationResult.Created>(
            service.create(CreateProjectRequest("created", integrationRoot))
        )
        val independentlyLoadedCreated =
            ProjectLoader().load(created.loadResult.project.rootDirectory).project
        assertTrue(independentlyLoadedCreated.isBuildable)

        val existing = Files.createDirectory(integrationRoot.resolve("existing"))
        val sentinel = existing.resolve("sentinel.bin")
        val sentinelBytes = byteArrayOf(9, 0, -7, 42)
        sentinel.writeBytes(sentinelBytes)
        val plan = assertIs<ProjectInitializationPlanResult.Ready>(
            service.planInitialization(existing)
        ).plan
        assertIs<ProjectInitializationResult.Initialized>(service.initialize(plan))
        assertContentEquals(sentinelBytes, sentinel.readBytes())
        assertTrue(ProjectLoader().load(existing).project.isBuildable)

        val malformedRoot = Files.createDirectory(integrationRoot.resolve("malformed"))
        val malformedConfiguration = malformedRoot.resolve(".aetex/project.toml")
        malformedConfiguration.parent.createDirectories()
        val malformedBytes = "schema = [\n".toByteArray()
        malformedConfiguration.writeBytes(malformedBytes)
        assertIs<ProjectInitializationPlanResult.Conflict>(
            service.planInitialization(malformedRoot)
        )
        assertContentEquals(malformedBytes, malformedConfiguration.readBytes())

        assertNoPartialFiles(integrationRoot)
    }

    @Test
    fun `creates canonical complete layout that immediately validates`() {
        val result = assertIs<ProjectCreationResult.Created>(
            ProjectProvisioningService().create(
                CreateProjectRequest("paper", temporaryDirectory)
            )
        )
        val root = temporaryDirectory.resolve("paper")

        assertEquals(root.toRealPath(), result.loadResult.project.rootDirectory)
        assertTrue(result.loadResult.project.isBuildable)
        assertEquals(root.resolve("src/main.tex").toRealPath(), result.entryDocument)
        assertEquals(
            ProjectProvisioningService.DEFAULT_ENTRY_CONTENT,
            result.entryDocument.readText()
        )
        assertIs<ProjectConfigurationLoadResult.Loaded>(
            ProjectConfigurationLoader().load(root)
        )
        assertNoPartialFiles(root)
    }

    @Test
    fun `permits an existing empty destination and rejects every collision`() {
        val empty = Files.createDirectory(temporaryDirectory.resolve("empty"))
        assertIs<ProjectCreationResult.Created>(
            ProjectProvisioningService().create(
                CreateProjectRequest(empty.fileName.toString(), temporaryDirectory)
            )
        )

        val nonEmpty = Files.createDirectory(temporaryDirectory.resolve("non-empty"))
        val sentinel = nonEmpty.resolve("sentinel.bin")
        sentinel.writeBytes(byteArrayOf(1, 2, 3))
        val rejected = assertIs<ProjectCreationResult.Failed>(
            ProjectProvisioningService().create(
                CreateProjectRequest(nonEmpty.fileName.toString(), temporaryDirectory)
            )
        )
        assertEquals(ProjectProvisioningErrorKind.DESTINATION_NOT_EMPTY, rejected.error.kind)
        assertContentEquals(byteArrayOf(1, 2, 3), sentinel.readBytes())

        val file = temporaryDirectory.resolve("file-collision")
        file.writeText("mine")
        val fileRejected = assertIs<ProjectCreationResult.Failed>(
            ProjectProvisioningService().create(
                CreateProjectRequest(file.fileName.toString(), temporaryDirectory)
            )
        )
        assertEquals(
            ProjectProvisioningErrorKind.DESTINATION_COLLISION,
            fileRejected.error.kind
        )
        assertEquals("mine", file.readText())
    }

    @Test
    fun `rejects invalid project names without filesystem changes`() {
        listOf("", " ", ".", "..", "nested/name", "trailing ").forEach { name ->
            val result = assertIs<ProjectCreationResult.Failed>(
                ProjectProvisioningService().create(
                    CreateProjectRequest(name, temporaryDirectory)
                )
            )
            assertEquals(ProjectProvisioningErrorKind.INVALID_PROJECT_NAME, result.error.kind)
        }
        assertTrue(Files.list(temporaryDirectory).use { it.findAny().isEmpty })
    }

    @Test
    fun `creation rollback removes only artifacts created by the operation`() {
        val writer = FailingWriter(failAtCall = 2)
        val result = assertIs<ProjectCreationResult.Failed>(
            ProjectProvisioningService(fileWriter = writer).create(
                CreateProjectRequest("rolled-back", temporaryDirectory)
            )
        )

        assertEquals(ProjectProvisioningErrorKind.FILESYSTEM_FAILURE, result.error.kind)
        assertFalse(Files.exists(temporaryDirectory.resolve("rolled-back")))
    }

    @Test
    fun `permission failure is typed and rolls back`() {
        val writer = NewProjectFileWriter { path, _ ->
            throw AccessDeniedException(path.toString())
        }
        val result = assertIs<ProjectCreationResult.Failed>(
            ProjectProvisioningService(fileWriter = writer).create(
                CreateProjectRequest("denied", temporaryDirectory)
            )
        )

        assertEquals(ProjectProvisioningErrorKind.ACCESS_DENIED, result.error.kind)
        assertFalse(Files.exists(temporaryDirectory.resolve("denied")))
    }

    @Test
    fun `initializes directory with sentinel unchanged and becomes buildable`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("existing"))
        val sentinel = root.resolve("notes.bin")
        val original = byteArrayOf(0, 1, 2, 127, -1)
        sentinel.writeBytes(original)
        val service = ProjectProvisioningService()
        val plan = assertIs<ProjectInitializationPlanResult.Ready>(
            service.planInitialization(root)
        ).plan

        assertTrue(plan.pathsToCreate.contains(root.resolve(".aetex/project.toml")))
        val result = assertIs<ProjectInitializationResult.Initialized>(
            service.initialize(plan)
        )

        assertContentEquals(original, sentinel.readBytes())
        assertTrue(result.loadResult.project.isBuildable)
        assertEquals(root.resolve("src/main.tex").toRealPath(), result.mainDocument)
        assertNoPartialFiles(root)
    }

    @Test
    fun `initializes an empty directory`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("empty-directory"))
        val service = ProjectProvisioningService()
        val plan = assertIs<ProjectInitializationPlanResult.Ready>(
            service.planInitialization(root)
        ).plan

        val result = assertIs<ProjectInitializationResult.Initialized>(
            service.initialize(plan)
        )

        assertTrue(result.loadResult.project.isBuildable)
        assertTrue(Files.isRegularFile(root.resolve(".aetex/project.toml")))
        assertTrue(Files.isRegularFile(root.resolve("src/main.tex")))
    }

    @Test
    fun `initialization reuses one compatible main without altering it`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("existing-main"))
        val main = root.resolve("article.tex")
        val original = "\\documentclass{book}\n\\begin{document}Mine\\end{document}\n"
        main.writeText(original)
        val service = ProjectProvisioningService()
        val plan = assertIs<ProjectInitializationPlanResult.Ready>(
            service.planInitialization(root)
        ).plan

        assertEquals(main.toRealPath(), plan.mainDocument)
        assertEquals(null, plan.entryDocumentToCreate)
        val result = assertIs<ProjectInitializationResult.Initialized>(
            service.initialize(plan)
        )

        assertEquals(original, main.readText())
        assertFalse(Files.exists(root.resolve("src")))
        assertTrue(result.loadResult.project.isBuildable)
    }

    @Test
    fun `already configured project is idempotent`() {
        val created = assertIs<ProjectCreationResult.Created>(
            ProjectProvisioningService().create(
                CreateProjectRequest("configured", temporaryDirectory)
            )
        )
        val configuration = created.loadResult.project.rootDirectory
            .resolve(".aetex/project.toml")
        val before = configuration.readBytes()

        val result = assertIs<ProjectInitializationPlanResult.AlreadyConfigured>(
            ProjectProvisioningService().planInitialization(
                created.loadResult.project.rootDirectory
            )
        )

        assertTrue(result.project.isBuildable)
        assertContentEquals(before, configuration.readBytes())
    }

    @Test
    fun `malformed configuration is a conflict and is never overwritten`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("invalid"))
        val configuration = root.resolve(".aetex/project.toml")
        configuration.parent.createDirectories()
        val malformed = "schema = [\n".toByteArray()
        configuration.writeBytes(malformed)

        val result = assertIs<ProjectInitializationPlanResult.Conflict>(
            ProjectProvisioningService().planInitialization(root)
        )

        assertEquals(
            ProjectProvisioningErrorKind.INVALID_EXISTING_CONFIGURATION,
            result.error.kind
        )
        assertContentEquals(malformed, configuration.readBytes())
    }

    @Test
    fun `semantically invalid configuration is a conflict and is never overwritten`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("invalid-main"))
        val configuration = root.resolve(".aetex/project.toml")
        configuration.parent.createDirectories()
        val invalid = "schema = 1\nmain = \"missing.tex\"\n".toByteArray()
        configuration.writeBytes(invalid)

        val result = assertIs<ProjectInitializationPlanResult.Conflict>(
            ProjectProvisioningService().planInitialization(root)
        )

        assertEquals(
            ProjectProvisioningErrorKind.INVALID_EXISTING_CONFIGURATION,
            result.error.kind
        )
        assertContentEquals(invalid, configuration.readBytes())
    }

    @Test
    fun `failed initialization keeps preexisting files and leaves no partial project`() {
        val root = Files.createDirectory(temporaryDirectory.resolve("failure"))
        val sentinel = root.resolve("sentinel.txt")
        sentinel.writeText("keep exactly")
        val service = ProjectProvisioningService(
            fileWriter = FailingWriter(failAtCall = 2)
        )
        val plan = assertIs<ProjectInitializationPlanResult.Ready>(
            service.planInitialization(root)
        ).plan

        assertIs<ProjectInitializationResult.Failed>(service.initialize(plan))

        assertEquals("keep exactly", sentinel.readText())
        assertFalse(Files.exists(root.resolve(".aetex/project.toml")))
        assertFalse(Files.exists(root.resolve("src/main.tex")))
        assertNoPartialFiles(root)
    }

    private fun assertNoPartialFiles(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            assertTrue(
                paths.noneMatch {
                    it.fileName?.toString()?.let { name ->
                        name.startsWith(".aetex-") && name.endsWith(".partial")
                    } == true
                }
            )
        }
    }

    private class FailingWriter(
        private val failAtCall: Int
    ) : NewProjectFileWriter {
        private val delegate = AtomicNewProjectFileWriter()
        private var calls = 0

        override fun writeNew(path: Path, content: String) {
            calls += 1
            if (calls == failAtCall) {
                throw IOException("Injected write failure")
            }
            delegate.writeNew(path, content)
        }
    }
}
