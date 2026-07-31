package dev.aetex.preview.domain

import dev.aetex.compilation.ArtifactStatus
import dev.aetex.compilation.BuildSessionId
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.math.roundToInt

@JvmInline
value class GenerationId(val value: String) {
    companion object {
        fun create(): GenerationId = GenerationId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class PreviewConsumerId(val value: String) {
    companion object {
        val PRIMARY_VIEW = PreviewConsumerId("primary-preview")
    }
}

@JvmInline
value class RequestToken(val value: Long)

/**
 * An effective raster scale bucket. One unit is 1/1000 of PDFBox's 72-DPI
 * scale, avoiding floating-point cache keys and near-duplicate zoom renders.
 */
@JvmInline
value class RenderScale private constructor(val milliScale: Int) : Comparable<RenderScale> {
    val value: Float
        get() = milliScale / 1000f

    val percentage: Int
        get() = (milliScale / 10f).roundToInt()

    override fun compareTo(other: RenderScale): Int = milliScale.compareTo(other.milliScale)

    companion object {
        const val MIN_MILLI_SCALE = 500
        const val MAX_MILLI_SCALE = 4000
        const val BUCKET_MILLI_SCALE = 250

        val DEFAULT: RenderScale = RenderScale(1000)

        fun normalized(scale: Double): RenderScale {
            require(scale.isFinite()) { "Render scale must be finite." }
            val clamped = (scale * 1000.0)
                .roundToInt()
                .coerceIn(MIN_MILLI_SCALE, MAX_MILLI_SCALE)
            val bucket = (
                (clamped - MIN_MILLI_SCALE + BUCKET_MILLI_SCALE / 2) /
                    BUCKET_MILLI_SCALE
                ) * BUCKET_MILLI_SCALE + MIN_MILLI_SCALE
            return RenderScale(bucket.coerceIn(MIN_MILLI_SCALE, MAX_MILLI_SCALE))
        }

        /**
         * Normalizes a raster request upward so bucket selection never turns a
         * quality target into an undersized bitmap. The global render-scale
         * bounds still apply, preserving the finite cache-key space.
         */
        fun normalizedRasterRequest(scale: Double): RenderScale {
            require(scale.isFinite()) { "Render scale must be finite." }
            val requested = (scale * 1000.0)
                .coerceIn(MIN_MILLI_SCALE.toDouble(), MAX_MILLI_SCALE.toDouble())
            val bucket = kotlin.math.ceil(
                (requested - MIN_MILLI_SCALE) / BUCKET_MILLI_SCALE
            ).toInt() * BUCKET_MILLI_SCALE + MIN_MILLI_SCALE
            return RenderScale(bucket.coerceIn(MIN_MILLI_SCALE, MAX_MILLI_SCALE))
        }
    }
}

data class PageRenderKey(
    val generationId: GenerationId,
    val pageIndex: Int,
    val scale: RenderScale
) {
    init {
        require(pageIndex >= 0) { "Page index must be non-negative." }
    }
}

enum class RenderPriority(val rank: Int) {
    INITIAL(0),
    CURRENT_VISIBLE(1),
    OTHER_VISIBLE(2),
    VISIBLE_REFINEMENT(3),
    DIRECT_NAVIGATION(4),
    NEIGHBOR_IN_DIRECTION(5),
    NEIGHBOR(6),
    LOW(7)
}

data class RenderRequest(
    val key: PageRenderKey,
    val consumerId: PreviewConsumerId,
    val token: RequestToken,
    val priority: RenderPriority
)

enum class PixelFormat {
    OPAQUE_RGB888
}

/**
 * Immutable-by-ownership RGB raster. Callers can only obtain a copy; the
 * renderer and cache never publish their mutable backing storage.
 */
class RasterImage private constructor(
    val width: Int,
    val height: Int,
    val stride: Int,
    private val bytes: ByteArray
) {
    val format: PixelFormat = PixelFormat.OPAQUE_RGB888
    val retainedBytes: Long = bytes.size.toLong()

    init {
        require(width > 0 && height > 0)
        require(stride.toLong() >= width.toLong() * 3L)
        require(bytes.size.toLong() == stride.toLong() * height)
    }

    fun copyRgbBytes(): ByteArray = bytes.copyOf()

    internal fun copyArgbBytesForUi(): ByteArray {
        val output = ByteArray(
            Math.multiplyExact(Math.multiplyExact(width, height), 4)
        )
        var source = 0
        var target = 0
        repeat(height) {
            repeat(width) {
                val red = bytes[source++]
                val green = bytes[source++]
                val blue = bytes[source++]
                output[target++] = blue
                output[target++] = green
                output[target++] = red
                output[target++] = 0xff.toByte()
            }
            source += stride - Math.multiplyExact(width, 3)
        }
        return output
    }

    companion object {
        internal fun owned(width: Int, height: Int, stride: Int, bytes: ByteArray): RasterImage =
            RasterImage(width, height, stride, bytes)
    }
}

data class PageGeometry(
    val widthPoints: Float,
    val heightPoints: Float,
    val cropLeftPoints: Float = 0f,
    val cropBottomPoints: Float = 0f,
    val rotationDegrees: Int = 0
) {
    init {
        require(widthPoints.isFinite() && heightPoints.isFinite())
        require(cropLeftPoints.isFinite() && cropBottomPoints.isFinite())
        require(widthPoints > 0f && heightPoints > 0f)
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }

    val displayedWidthPoints: Float
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) heightPoints else widthPoints

    val displayedHeightPoints: Float
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) widthPoints else heightPoints
}

class DocumentMetadata(
    pages: List<PageGeometry>,
    val rendererId: String,
    val rendererVersion: String
) {
    val pages: List<PageGeometry> =
        Collections.unmodifiableList(pages.toList())

    val pageCount: Int
        get() = pages.size

    val maximumDisplayedWidthPoints: Float =
        this.pages.maxOfOrNull(PageGeometry::displayedWidthPoints) ?: 0f

    init {
        require(this.pages.isNotEmpty()) { "A preview document must contain at least one page." }
        require(rendererId.isNotBlank())
        require(rendererVersion.isNotBlank())
    }

    override fun equals(other: Any?): Boolean =
        other is DocumentMetadata &&
            pages == other.pages &&
            rendererId == other.rendererId &&
            rendererVersion == other.rendererVersion

    override fun hashCode(): Int =
        31 * (31 * pages.hashCode() + rendererId.hashCode()) + rendererVersion.hashCode()

    override fun toString(): String =
        "DocumentMetadata(pages=$pages, rendererId=$rendererId, rendererVersion=$rendererVersion)"
}

data class RenderedPage(
    val key: PageRenderKey,
    val raster: RasterImage,
    val geometry: PageGeometry,
    val rendererId: String,
    val rendererVersion: String
) {
    val estimatedCacheBytes: Long =
        raster.retainedBytes + raster.width.toLong() * raster.height * 4L + CACHE_ENTRY_OVERHEAD

    companion object {
        private const val CACHE_ENTRY_OVERHEAD = 256L
    }
}

enum class PreviewErrorKind {
    INVALID_BUILD_RESULT,
    INVALID_SNAPSHOT,
    SNAPSHOT_TOO_LARGE,
    SNAPSHOT_COPY_FAILED,
    CORRUPT_PDF,
    PROTECTED_PDF,
    UNSUPPORTED_PDF,
    INVALID_PAGE,
    RASTER_LIMIT,
    RENDER_FAILED,
    MEMORY_LIMIT,
    GENERATION_OBSOLETE,
    MANAGER_CLOSED,
    SCHEDULER_OVERLOADED,
    BUILD_FAILED,
    BUILD_CANCELLED,
    INTERNAL
}

data class PreviewError(
    val kind: PreviewErrorKind,
    val message: String,
    val generationId: GenerationId? = null,
    val pageIndex: Int? = null,
    val scale: RenderScale? = null,
    val technicalCause: Throwable? = null
)

sealed interface PreviewResult<out T> {
    data class Success<T>(val value: T) : PreviewResult<T>
    data class Failure(val error: PreviewError) : PreviewResult<Nothing>
}

enum class PageStatus {
    NOT_REQUESTED,
    QUEUED,
    RENDERING,
    READY,
    FAILED
}

sealed interface PagePreviewState {
    val status: PageStatus

    data object NotRequested : PagePreviewState {
        override val status = PageStatus.NOT_REQUESTED
    }

    data class Queued(
        val scale: RenderScale,
        val previous: RenderedPage? = null
    ) : PagePreviewState {
        override val status = PageStatus.QUEUED
    }

    data class Rendering(
        val scale: RenderScale,
        val previous: RenderedPage? = null
    ) : PagePreviewState {
        override val status = PageStatus.RENDERING
    }

    data class Ready(val page: RenderedPage) : PagePreviewState {
        override val status = PageStatus.READY
    }

    data class Failed(
        val error: PreviewError,
        val retryable: Boolean = true
    ) : PagePreviewState {
        override val status = PageStatus.FAILED
    }
}

data class BuildProvenance(
    val projectRoot: Path,
    val sessionId: BuildSessionId,
    val planFingerprint: String,
    val artifactStatus: ArtifactStatus,
    val artifactSize: Long,
    val artifactLastModified: Instant,
    val contentSha256: String
)

class PreviewDocument(
    val generationId: GenerationId,
    val provenance: BuildProvenance,
    val metadata: DocumentMetadata,
    pages: Map<Int, PagePreviewState>,
    val currentPageIndex: Int,
    val scale: RenderScale
) {
    val pages: Map<Int, PagePreviewState> =
        Collections.unmodifiableMap(LinkedHashMap(pages))

    init {
        require(currentPageIndex in metadata.pages.indices)
        require(this.pages.keys.all { it in metadata.pages.indices })
    }

    fun pageState(index: Int): PagePreviewState =
        pages[index] ?: PagePreviewState.NotRequested
}

sealed interface PreviewState {
    data object Empty : PreviewState

    data class LoadingGeneration(
        val previous: PreviewDocument? = null,
        val buildInProgress: Boolean = false
    ) : PreviewState

    data class Ready(
        val document: PreviewDocument,
        val stale: Boolean = false,
        val notice: PreviewError? = null,
        val buildInProgress: Boolean = false
    ) : PreviewState

    data class GenerationError(
        val error: PreviewError,
        val previous: PreviewDocument? = null
    ) : PreviewState

    data object Closed : PreviewState
}
