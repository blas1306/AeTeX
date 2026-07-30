package dev.aetex.compilation

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RuntimeStorageTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `resolves runtime below validated absolute home including spaces`() {
        val home = Files.createDirectory(temporaryDirectory.resolve("home with spaces"))

        val runtime = RuntimeDirectoryResolver.resolve(home.toString())

        assertEquals(home.resolve(".aetex").resolve("runtime"), runtime)
        assertTrue(Files.isDirectory(runtime))
    }

    @Test
    fun `rejects missing and relative homes with typed storage failure`() {
        assertEquals(
            BuildFailureKind.LOG_STORAGE_FAILURE,
            assertFailsWith<RuntimeStorageException> {
                RuntimeDirectoryResolver.resolve(null)
            }.failure.kind
        )
        assertFailsWith<RuntimeStorageException> {
            RuntimeDirectoryResolver.resolve("relative-home")
        }
    }

    @Test
    fun `rejects runtime path that already exists as a file`() {
        val home = Files.createDirectory(temporaryDirectory.resolve("home"))
        Files.createDirectory(home.resolve(".aetex"))
        Files.writeString(home.resolve(".aetex").resolve("runtime"), "not a directory")

        assertFailsWith<RuntimeStorageException> {
            RuntimeDirectoryResolver.resolve(home.toString())
        }
    }

    @Test
    fun `rejects a symbolic link runtime when platform supports links`() {
        val home = Files.createDirectory(temporaryDirectory.resolve("home"))
        val target = Files.createDirectory(temporaryDirectory.resolve("target"))
        Files.createDirectory(home.resolve(".aetex"))
        try {
            Files.createSymbolicLink(home.resolve(".aetex").resolve("runtime"), target)
        } catch (_: Exception) {
            return
        }

        assertFailsWith<RuntimeStorageException> {
            RuntimeDirectoryResolver.resolve(home.toString())
        }
    }

    @Test
    fun `concurrent runtime creation converges on one directory`() {
        val home = Files.createDirectory(temporaryDirectory.resolve("home"))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val futures = List(4) {
            pool.submit<Path> {
                start.await()
                RuntimeDirectoryResolver.resolve(home.toString())
            }
        }
        start.countDown()
        try {
            val results = futures.map { it.get() }
            assertEquals(1, results.toSet().size)
            assertTrue(Files.isDirectory(results.first()))
        } finally {
            pool.shutdownNow()
        }
    }
}
