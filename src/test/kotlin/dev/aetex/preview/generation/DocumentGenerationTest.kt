package dev.aetex.preview.generation

import dev.aetex.preview.FakeRenderer
import dev.aetex.preview.domain.DocumentMetadata
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RenderScale
import dev.aetex.preview.successfulBuildResult
import dev.aetex.preview.testGeneration
import dev.aetex.preview.writePdf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DocumentGenerationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `captures a private byte-identical snapshot outside the project`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val store = FileSnapshotStore(temporaryDirectory.resolve("preview"))

        val captured = assertIs<PreviewResult.Success<SnapshotCapture>>(
            store.capture(result, GenerationId.create())
        ).value

        assertNotEquals(result.plan.primaryPdf, captured.snapshot.path)
        assertFalse(captured.snapshot.path.startsWith(result.plan.workingDirectory))
        assertTrue(Files.mismatch(result.plan.primaryPdf, captured.snapshot.path) == -1L)
        captured.snapshot.close()
    }

    @Test
    fun `snapshot remains unchanged when the compilation output is replaced`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val store = FileSnapshotStore(temporaryDirectory.resolve("preview"))
        val captured = assertIs<PreviewResult.Success<SnapshotCapture>>(
            store.capture(result, GenerationId.create())
        ).value
        val snapshotBytes = Files.readAllBytes(captured.snapshot.path)
        val replacement = temporaryDirectory.resolve("replacement.pdf")
        writePdf(replacement, listOf(500f to 500f))
        Files.move(replacement, result.plan.primaryPdf, StandardCopyOption.REPLACE_EXISTING)

        assertTrue(snapshotBytes.contentEquals(Files.readAllBytes(captured.snapshot.path)))
        captured.snapshot.close()
    }

    @Test
    fun `snapshot cleanup and close are idempotent`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val captured = assertIs<PreviewResult.Success<SnapshotCapture>>(
            FileSnapshotStore(temporaryDirectory.resolve("preview"))
                .capture(result, GenerationId.create())
        ).value
        val directory = captured.snapshot.path.parent

        captured.snapshot.close()
        captured.snapshot.close()

        assertFalse(Files.exists(directory))
    }

    @Test
    fun `rejects a source whose validated metadata changed`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        Files.writeString(result.plan.primaryPdf, "changed")

        val failure = assertIs<PreviewResult.Failure>(
            FileSnapshotStore(temporaryDirectory.resolve("preview"))
                .capture(result, GenerationId.create())
        )

        assertEquals(PreviewErrorKind.INVALID_SNAPSHOT, failure.error.kind)
        assertTrue(Files.list(temporaryDirectory.resolve("preview")).use { it.count() } == 0L)
    }

    @Test
    fun `rejects symbolic link substituted for primary PDF`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val source = result.plan.primaryPdf
        val target = source.resolveSibling("real.pdf")
        Files.move(source, target)
        Files.createSymbolicLink(source, target.fileName)

        val failure = assertIs<PreviewResult.Failure>(
            FileSnapshotStore(temporaryDirectory.resolve("preview"))
                .capture(result, GenerationId.create())
        )

        assertEquals(PreviewErrorKind.INVALID_SNAPSHOT, failure.error.kind)
        assertEquals(0L, Files.list(temporaryDirectory.resolve("preview")).use { it.count() })
    }

    @Test
    fun `rejects a PDF larger than the configured snapshot limit without partial files`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val failure = assertIs<PreviewResult.Failure>(
            FileSnapshotStore(
                temporaryDirectory.resolve("preview"),
                SnapshotPolicy(maximumPdfBytes = 1)
            ).capture(result, GenerationId.create())
        )

        assertEquals(PreviewErrorKind.SNAPSHOT_TOO_LARGE, failure.error.kind)
        assertEquals(0L, Files.list(temporaryDirectory.resolve("preview")).use { it.count() })
    }

    @Test
    fun `detects same-size same-timestamp source mutation during capture`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val source = result.plan.primaryPdf
        val original = Files.readAllBytes(source)
        val replacement = original.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }
        val observedTime = checkNotNull(
            result.artifacts.single {
                it.expected.role == dev.aetex.compilation.ArtifactRole.PRIMARY_PDF
            }.lastModified
        )
        val store = FileSnapshotStore(
            temporaryDirectory.resolve("preview"),
            hooks = SnapshotCaptureHooks { _, _ ->
                Files.write(source, replacement)
                Files.setLastModifiedTime(source, FileTime.from(observedTime))
            }
        )

        val failure = assertIs<PreviewResult.Failure>(
            store.capture(result, GenerationId.create())
        )

        assertEquals(PreviewErrorKind.INVALID_SNAPSHOT, failure.error.kind)
        assertEquals(0L, Files.list(temporaryDirectory.resolve("preview")).use { it.count() })
    }

    @Test
    fun `snapshot digest matches validated private bytes and permissions are private when supported`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val captured = assertIs<PreviewResult.Success<SnapshotCapture>>(
            FileSnapshotStore(temporaryDirectory.resolve("preview"))
                .capture(result, GenerationId.create())
        ).value

        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(captured.snapshot.path))
        assertEquals(
            java.util.HexFormat.of().formatHex(expectedDigest),
            captured.snapshot.sha256
        )
        if (Files.getFileStore(captured.snapshot.path).supportsFileAttributeView("posix")) {
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(captured.snapshot.path)
            )
        }
        captured.snapshot.close()
    }

    @Test
    fun `distinct successful builds always receive distinct generation identity`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val store = FileSnapshotStore(temporaryDirectory.resolve("preview"))
        val factory = DocumentGenerationFactory(
            store,
            rendererFactory = { _ ->
                PreviewResult.Success(
                    FakeRenderer(
                        DocumentMetadata(listOf(PageGeometry(100f, 100f)), "fake", "1")
                    )
                )
            }
        )

        val first = assertIs<PreviewResult.Success<DocumentGeneration>>(factory.create(result)).value
        val second = assertIs<PreviewResult.Success<DocumentGeneration>>(factory.create(result)).value

        assertNotEquals(first.id, second.id)
        assertEquals(first.provenance.contentSha256, second.provenance.contentSha256)
        first.close()
        second.close()
    }

    @Test
    fun `generation defers resource close until an active render lease drains`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renderer = FakeRenderer(
            DocumentMetadata(listOf(PageGeometry(100f, 100f)), "fake", "1"),
            beforeRender = {
                started.countDown()
                release.await()
            }
        )
        val generation = testGeneration(temporaryDirectory, renderer = renderer)
        assertTrue(generation.activate())
        val outcome = AtomicReference<PreviewResult<*>?>()
        val renderThread = thread {
            outcome.set(
                generation.render(PageRenderKey(generation.id, 0, RenderScale.DEFAULT))
            )
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        generation.retire()
        assertEquals(GenerationLifecycle.RETIRING, generation.state)
        assertFalse(renderer.closed.get())
        release.countDown()
        renderThread.join()
        generation.retire()

        assertIs<PreviewResult.Success<*>>(outcome.get())
        assertEquals(GenerationLifecycle.CLOSED, generation.state)
        assertTrue(renderer.closed.get())
    }

    @Test
    fun `closed generation rejects later render attempts with typed error`() {
        val generation = testGeneration(temporaryDirectory)
        generation.close()

        val failure = assertIs<PreviewResult.Failure>(
            generation.render(PageRenderKey(generation.id, 0, RenderScale.DEFAULT))
        )

        assertEquals(PreviewErrorKind.GENERATION_OBSOLETE, failure.error.kind)
    }

    @Test
    fun `factory construction failure closes renderer and snapshot`() {
        val result = successfulBuildResult(temporaryDirectory.resolve("project"))
        val snapshotDirectory = Files.createDirectory(temporaryDirectory.resolve("captured"))
        val snapshotPath = Files.write(snapshotDirectory.resolve("document.pdf"), byteArrayOf(1, 2, 3))
        val snapshot = DocumentSnapshot(
            snapshotPath,
            3,
            Instant.EPOCH,
            "digest",
            snapshotDirectory
        )
        val renderer = FakeRenderer(
            DocumentMetadata(listOf(PageGeometry(10f, 10f)), "fake", "1")
        )
        val factory = DocumentGenerationFactory(
            snapshotStore = object : SnapshotStore {
                override fun capture(
                    result: dev.aetex.compilation.BuildResult,
                    generationId: GenerationId
                ): PreviewResult<SnapshotCapture> = PreviewResult.Success(
                    SnapshotCapture(
                        snapshot,
                        result.artifacts.single {
                            it.expected.role ==
                                dev.aetex.compilation.ArtifactRole.PRIMARY_PDF
                        }
                    )
                )
            },
            rendererFactory = { PreviewResult.Success(renderer) },
            clock = PreviewClock { error("synthetic clock failure") }
        )

        val failure = assertIs<PreviewResult.Failure>(factory.create(result))

        assertEquals(PreviewErrorKind.INTERNAL, failure.error.kind)
        assertTrue(renderer.closed.get())
        assertFalse(Files.exists(snapshotDirectory))
    }
}
