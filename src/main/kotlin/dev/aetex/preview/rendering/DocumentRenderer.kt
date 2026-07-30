package dev.aetex.preview.rendering

import dev.aetex.preview.domain.DocumentMetadata
import dev.aetex.preview.domain.PageRenderKey
import dev.aetex.preview.domain.PreviewResult
import dev.aetex.preview.domain.RenderedPage
import java.nio.file.Path

interface DocumentRenderer : AutoCloseable {
    val metadata: DocumentMetadata
    fun render(key: PageRenderKey): PreviewResult<RenderedPage>
    override fun close()
}

internal fun interface DocumentRendererFactory {
    fun open(snapshotPath: Path): PreviewResult<DocumentRenderer>
}

data class RasterLimits(
    val maximumWidth: Int = 8_192,
    val maximumHeight: Int = 8_192,
    val maximumPixels: Long = 32_000_000,
    val maximumPages: Int = 100_000
) {
    init {
        require(maximumWidth > 0)
        require(maximumHeight > 0)
        require(maximumPixels > 0)
        require(maximumPages > 0)
    }
}
