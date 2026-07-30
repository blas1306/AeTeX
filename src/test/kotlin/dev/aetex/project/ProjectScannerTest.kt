package dev.aetex.project

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ProjectScannerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val scanner = ProjectScanner()

    @Test
    fun `builds a hierarchical tree and sorts directories before files`() {
        temporaryDirectory.resolve("zeta.tex").writeText("zeta")
        temporaryDirectory.resolve("Alpha.tex").writeText("alpha")
        val zDirectory = temporaryDirectory.resolve("z-dir").createDirectory()
        val aDirectory = temporaryDirectory.resolve("a-dir").createDirectory()
        aDirectory.resolve("nested.tex").writeText("nested")

        val result = scanner.scan(temporaryDirectory)

        assertEquals(
            listOf("a-dir", "z-dir", "Alpha.tex", "zeta.tex"),
            result.project.entries.map(ProjectEntry::name)
        )
        val firstDirectory = assertIs<ProjectDirectory>(result.project.entries.first())
        assertEquals("nested.tex", firstDirectory.children.single().name)
        assertTrue(result.issues.isEmpty())
        assertTrue(zDirectory.toAbsolutePath().normalize().startsWith(result.project.rootDirectory))
    }

    @Test
    fun `ignores build and metadata directories`() {
        listOf(".aetex", ".git", ".gradle", "build", "out").forEach { name ->
            temporaryDirectory.resolve(name).createDirectory()
                .resolve("ignored.tex").writeText("ignored")
        }
        temporaryDirectory.resolve("src").createDirectory()
            .resolve("kept.tex").writeText("kept")

        val result = scanner.scan(temporaryDirectory)

        assertEquals(listOf("src"), result.project.entries.map(ProjectEntry::name))
    }

    @Test
    fun `does not recurse through symbolic directory links`() {
        val realDirectory = temporaryDirectory.resolve("real").createDirectory()
        realDirectory.resolve("inside.tex").writeText("content")
        val link = temporaryDirectory.resolve("linked")

        try {
            Files.createSymbolicLink(link, realDirectory)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: SecurityException) {
            return
        }

        val result = scanner.scan(temporaryDirectory)
        val linkedEntry = assertIs<ProjectDirectory>(
            result.project.entries.single { it.name == "linked" }
        )

        assertTrue(linkedEntry.isSymbolicLink)
        assertTrue(linkedEntry.children.isEmpty())
    }

    @Test
    fun `handles an empty project directory`() {
        val result = scanner.scan(temporaryDirectory)

        assertTrue(result.project.entries.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `rejects missing paths and paths that are not directories`() {
        val missing = temporaryDirectory.resolve("missing")
        val file = temporaryDirectory.resolve("document.tex").createFile()

        assertFailsWith<ProjectScanException> {
            scanner.scan(missing)
        }
        assertFailsWith<ProjectScanException> {
            scanner.scan(file)
        }
    }

    @Test
    fun `reports an inaccessible nested directory when permissions are enforced`() {
        val blocked = temporaryDirectory.resolve("blocked").createDirectory()
        val originalPermissions = try {
            Files.getPosixFilePermissions(blocked)
        } catch (_: UnsupportedOperationException) {
            return
        }

        try {
            Files.setPosixFilePermissions(blocked, emptySet<PosixFilePermission>())
            if (Files.isReadable(blocked)) {
                return
            }

            val result = scanner.scan(temporaryDirectory)
            assertTrue(result.issues.any { it.path.fileName.toString() == "blocked" })
        } finally {
            Files.setPosixFilePermissions(blocked, originalPermissions)
        }
    }
}
