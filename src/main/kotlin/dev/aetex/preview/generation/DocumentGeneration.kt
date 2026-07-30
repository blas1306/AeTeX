package dev.aetex.preview.generation

import dev.aetex.compilation.BuildResult
import dev.aetex.preview.domain.BuildProvenance
import dev.aetex.preview.domain.DocumentMetadata
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RenderedPage
import dev.aetex.preview.rendering.DocumentRenderer
import dev.aetex.preview.rendering.DocumentRendererFactory
import dev.aetex.preview.rendering.PdfBoxDocumentRenderer
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

enum class GenerationLifecycle {
    PENDING,
    ACTIVE,
    RETIRING,
    CLOSED
}

fun interface PreviewClock {
    fun instant(): Instant
}

object SystemPreviewClock : PreviewClock {
    override fun instant(): Instant = Instant.now()
}

class DocumentGeneration internal constructor(
    val id: GenerationId,
    val provenance: BuildProvenance,
    val metadata: DocumentMetadata,
    val createdAt: Instant,
    val createdAtMonotonicNanos: Long,
    private val snapshot: DocumentSnapshot,
    private val renderer: DocumentRenderer
) : AutoCloseable {
    private val lifecycle = AtomicReference(GenerationLifecycle.PENDING)
    private val leaseLock = Any()
    private var renderLeases = 0

    val state: GenerationLifecycle
        get() = lifecycle.get()

    fun activate(): Boolean =
        lifecycle.compareAndSet(GenerationLifecycle.PENDING, GenerationLifecycle.ACTIVE)

    fun render(key: PageRenderKey): PreviewResult<RenderedPage> {
        if (key.generationId != id || !acquireRenderLease()) {
            return PreviewResult.Failure(
                PreviewError(
                    PreviewErrorKind.GENERATION_OBSOLETE,
                    "The document generation is no longer eligible for rendering.",
                    id,
                    key.pageIndex,
                    key.scale
                )
            )
        }
        return try {
            renderer.render(key)
        } finally {
            releaseRenderLease()
        }
    }

    fun retire() {
        while (true) {
            when (val current = lifecycle.get()) {
                GenerationLifecycle.PENDING,
                GenerationLifecycle.ACTIVE -> {
                    if (lifecycle.compareAndSet(current, GenerationLifecycle.RETIRING)) {
                        closeIfDrained()
                        return
                    }
                }

                GenerationLifecycle.RETIRING,
                GenerationLifecycle.CLOSED -> return
            }
        }
    }

    override fun close() = retire()

    private fun acquireRenderLease(): Boolean = synchronized(leaseLock) {
        if (lifecycle.get() !in setOf(GenerationLifecycle.PENDING, GenerationLifecycle.ACTIVE)) {
            false
        } else {
            renderLeases++
            true
        }
    }

    private fun releaseRenderLease() {
        synchronized(leaseLock) {
            check(renderLeases > 0)
            renderLeases--
        }
        closeIfDrained()
    }

    private fun closeIfDrained() {
        val shouldClose = synchronized(leaseLock) {
            renderLeases == 0 &&
                lifecycle.compareAndSet(GenerationLifecycle.RETIRING, GenerationLifecycle.CLOSED)
        }
        if (!shouldClose) return
        try {
            renderer.close()
        } catch (error: Throwable) {
            LOGGER.log(Level.WARNING, "Renderer cleanup failed for generation ${id.value}.", error)
        } finally {
            snapshot.close()
            LOGGER.fine("Closed preview generation ${id.value}.")
        }
    }

    companion object {
        private val LOGGER = Logger.getLogger(DocumentGeneration::class.java.name)
    }
}

internal fun interface GenerationFactory {
    fun create(result: BuildResult): PreviewResult<DocumentGeneration>
}

internal class DocumentGenerationFactory(
    private val snapshotStore: SnapshotStore,
    private val rendererFactory: DocumentRendererFactory,
    private val clock: PreviewClock = SystemPreviewClock,
    private val nanoTime: () -> Long = System::nanoTime
) : GenerationFactory {
    override fun create(result: BuildResult): PreviewResult<DocumentGeneration> {
        val id = GenerationId.create()
        val captured = when (val capture = snapshotStore.capture(result, id)) {
            is PreviewResult.Success -> capture.value
            is PreviewResult.Failure -> return capture
        }
        val renderer = when (val opened = rendererFactory.open(captured.snapshot.path)) {
            is PreviewResult.Success -> opened.value
            is PreviewResult.Failure -> {
                captured.snapshot.close()
                return PreviewResult.Failure(opened.error.copy(generationId = id))
            }
        }
        val generation = try {
            val observation = captured.observation
            DocumentGeneration(
                id = id,
                provenance = BuildProvenance(
                    projectRoot = result.plan.workingDirectory,
                    sessionId = result.sessionId,
                    planFingerprint = result.plan.fingerprint,
                    artifactStatus = observation.status,
                    artifactSize = checkNotNull(observation.size),
                    artifactLastModified = checkNotNull(observation.lastModified),
                    contentSha256 = captured.snapshot.sha256
                ),
                metadata = renderer.metadata,
                createdAt = clock.instant(),
                createdAtMonotonicNanos = nanoTime(),
                snapshot = captured.snapshot,
                renderer = renderer
            )
        } catch (error: Throwable) {
            try {
                renderer.close()
            } catch (_: Throwable) {
                // The construction failure remains authoritative.
            } finally {
                captured.snapshot.close()
            }
            return PreviewResult.Failure(
                PreviewError(
                    PreviewErrorKind.INTERNAL,
                    "The PDF preview generation could not be initialized.",
                    id,
                    technicalCause = error
                )
            )
        }
        LOGGER.fine("Created pending preview generation ${id.value}.")
        return PreviewResult.Success(generation)
    }

    companion object {
        private val LOGGER = Logger.getLogger(DocumentGenerationFactory::class.java.name)
    }
}

internal object DefaultGenerationFactory : GenerationFactory {
    private val delegate: DocumentGenerationFactory by lazy {
        DocumentGenerationFactory(FileSnapshotStore(), PdfBoxDocumentRenderer)
    }

    override fun create(result: BuildResult): PreviewResult<DocumentGeneration> =
        delegate.create(result)
}
