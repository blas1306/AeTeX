package dev.aetex.preview

import dev.aetex.compilation.ArtifactObservation
import dev.aetex.compilation.ArtifactRole
import dev.aetex.compilation.ArtifactStatus
import dev.aetex.compilation.BuildLogHandle
import dev.aetex.compilation.BuildFailure
import dev.aetex.compilation.BuildFailureKind
import dev.aetex.compilation.BuildResult
import dev.aetex.compilation.BuildSessionId
import dev.aetex.compilation.BuildSessionSnapshot
import dev.aetex.compilation.BuildState
import dev.aetex.compilation.ProcessEvidence
import dev.aetex.compilation.createPlan
import dev.aetex.preview.domain.BuildProvenance
import dev.aetex.preview.domain.DocumentMetadata
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PageGeometry
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RasterImage
import dev.aetex.preview.domain.RenderedPage
import dev.aetex.preview.generation.DocumentGeneration
import dev.aetex.preview.generation.DocumentSnapshot
import dev.aetex.preview.rendering.DocumentRenderer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun writePdf(
    path: Path,
    pageSizes: List<Pair<Float, Float>> = listOf(200f to 300f)
) {
    Files.createDirectories(path.parent)
    PDDocument().use { document ->
        pageSizes.forEach { (width, height) ->
            document.addPage(PDPage(PDRectangle(width, height)))
        }
        document.save(path.toFile())
    }
}

internal fun successfulBuildResult(
    projectRoot: Path,
    sessionValue: String = "session",
    createdAt: Instant = Instant.parse("2026-07-30T12:00:00Z")
): BuildResult {
    val plan = createPlan(projectRoot)
    if (!Files.exists(plan.primaryPdf)) writePdf(plan.primaryPdf)
    val attributes = Files.readAttributes(
        plan.primaryPdf,
        java.nio.file.attribute.BasicFileAttributes::class.java
    )
    val artifacts = plan.expectedFiles.map { expected ->
        if (expected.role == ArtifactRole.PRIMARY_PDF) {
            ArtifactObservation(
                expected,
                ArtifactStatus.CREATED,
                attributes.size(),
                attributes.lastModifiedTime().toInstant()
            )
        } else {
            ArtifactObservation(expected, ArtifactStatus.MISSING)
        }
    }
    val sessionId = BuildSessionId(sessionValue)
    val log = projectRoot.parent.resolve("$sessionValue.aetexlog")
    if (!Files.exists(log)) Files.createFile(log)
    return BuildResult(
        sessionId = sessionId,
        state = BuildState.SUCCEEDED,
        plan = plan,
        failure = null,
        createdAt = createdAt,
        startedAt = createdAt,
        finishedAt = createdAt.plusSeconds(1),
        processEvidence = ProcessEvidence(
            started = true,
            exitCode = 0,
            streamsReachedEof = true,
            resourcesClosed = true,
            cleanupProven = true
        ),
        cancellation = null,
        logs = BuildLogHandle(log, 0, 0),
        diagnostics = emptyList(),
        artifacts = artifacts,
        missingRequiredArtifacts = emptyList(),
        quarantine = null,
        trace = emptyMap()
    )
}

internal fun snapshotOf(
    result: BuildResult,
    requestSequence: Long = 0L
): BuildSessionSnapshot =
    BuildSessionSnapshot(
        result.sessionId,
        result.plan,
        result.state,
        result.createdAt,
        result.createdAt,
        result.startedAt,
        result.cancellation,
        result.finishedAt,
        result,
        requestSequence
    )

internal fun terminalBuildResult(
    successful: BuildResult,
    state: BuildState,
    createdAt: Instant = successful.createdAt
): BuildResult {
    require(state == BuildState.FAILED || state == BuildState.CANCELLED)
    return BuildResult(
        sessionId = successful.sessionId,
        state = state,
        plan = successful.plan,
        failure = if (state == BuildState.FAILED) {
            BuildFailure(BuildFailureKind.NON_ZERO_EXIT, "Synthetic build failure.")
        } else {
            null
        },
        createdAt = createdAt,
        startedAt = createdAt,
        finishedAt = createdAt.plusSeconds(1),
        processEvidence = successful.processEvidence.copy(exitCode = 1),
        cancellation = null,
        logs = successful.logs,
        diagnostics = emptyList(),
        artifacts = successful.artifacts,
        missingRequiredArtifacts = emptyList(),
        quarantine = null,
        trace = emptyMap()
    )
}

internal class FakeRenderer(
    override val metadata: DocumentMetadata,
    private val beforeRender: ((PageRenderKey) -> Unit)? = null,
    private val failure: dev.aetex.preview.domain.PreviewError? = null
) : DocumentRenderer {
    val renderCount = AtomicInteger()
    val activeRenders = AtomicInteger()
    val maximumActiveRenders = AtomicInteger()
    val closed = AtomicBoolean()

    override fun render(key: PageRenderKey): PreviewResult<RenderedPage> {
        beforeRender?.invoke(key)
        renderCount.incrementAndGet()
        val active = activeRenders.incrementAndGet()
        maximumActiveRenders.accumulateAndGet(active, ::maxOf)
        return try {
            failure?.let { PreviewResult.Failure(it) } ?: PreviewResult.Success(
                RenderedPage(
                    key,
                    RasterImage.owned(
                        2,
                        2,
                        6,
                        byteArrayOf(
                            1, 2, 3, 4, 5, 6,
                            7, 8, 9, 10, 11, 12
                        )
                    ),
                    metadata.pages[key.pageIndex],
                    metadata.rendererId,
                    metadata.rendererVersion
                )
            )
        } finally {
            activeRenders.decrementAndGet()
        }
    }

    override fun close() {
        closed.set(true)
    }
}

internal fun testGeneration(
    root: Path,
    id: GenerationId = GenerationId.create(),
    renderer: FakeRenderer = FakeRenderer(
        DocumentMetadata(
            listOf(PageGeometry(200f, 300f), PageGeometry(300f, 200f)),
            "fake",
            "1"
        )
    ),
    sessionId: BuildSessionId = BuildSessionId("session-${id.value}")
): DocumentGeneration {
    val directory = Files.createDirectory(root.resolve("snapshot-${id.value}"))
    val snapshotPath = Files.writeString(directory.resolve("document.pdf"), "private")
    val instant = Instant.parse("2026-07-30T12:00:00Z")
    return DocumentGeneration(
        id,
        BuildProvenance(
            root,
            sessionId,
            "fingerprint",
            ArtifactStatus.CREATED,
            Files.size(snapshotPath),
            instant,
            "digest-${id.value}"
        ),
        renderer.metadata,
        instant,
        1L,
        DocumentSnapshot(snapshotPath, Files.size(snapshotPath), instant, "digest", directory),
        renderer
    )
}

internal fun blockingRenderer(
    started: CountDownLatch,
    release: CountDownLatch
): FakeRenderer = FakeRenderer(
    metadata = DocumentMetadata(listOf(PageGeometry(100f, 100f)), "fake", "1"),
    beforeRender = {
        started.countDown()
        release.await()
    }
)
