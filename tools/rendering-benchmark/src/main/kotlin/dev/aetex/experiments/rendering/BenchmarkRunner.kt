package dev.aetex.experiments.rendering

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.util.Collections
import java.util.Random
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.math.min

class BenchmarkRunner(
    private val rendererId: String,
    private val adapterFactory: () -> RendererAdapter,
    private val documents: List<CorpusDocument>,
    private val corpusRoot: Path,
    private val rendererOutput: Path,
    private val repetitions: Int,
    private val warmups: Int,
) {
    private val samples = mutableListOf<MetricSample>()
    private val robustness = mutableListOf<RobustnessObservation>()

    fun run() {
        rendererOutput.createDirectories()
        adapterFactory().use { adapter ->
            Files.writeString(rendererOutput.resolve("engine-version.txt"), "$rendererId\t${adapter.version}\n")
        }
        warmUp()
        val schedule = measurementSchedule(documents, repetitions)
        writeExecutionOrder(schedule)
        schedule.forEach { scheduled ->
            benchmarkDocument(scheduled.document, scheduled.repetition)
        }
        runRobustnessChecks()
        generateVisualSamples()
        writeTsv()
    }

    private fun warmUp() {
        repeat(warmups) { round ->
            shuffledDocuments(documents, EXECUTION_ORDER_SEED + round).forEach { descriptor ->
                adapterFactory().use { adapter ->
                    exerciseRenderingSequence(adapter, descriptor)
                }
            }
        }
    }

    private fun benchmarkDocument(descriptor: CorpusDocument, repetition: Int) {
        forceGcForMeasurement()
        val before = snapshotMemory()
        val documentName = descriptor.path.name
        val repetitionSamples = mutableListOf<MetricSample>()
        var peak = MemoryPeak(before.heapUsedBytes, before.rssBytes)
        var after = before
        var failure: Throwable? = null
        var totalMs = 0.0
        var closeMs = 0.0
        val adapter = adapterFactory()
        val sampler = MemorySampler()
        val totalStarted = System.nanoTime()
        try {
            val (info, openMs) = measuredMillis { adapter.open(descriptor.path) }
            repetitionSamples.add(documentName, repetition, "open", "ms", openMs)
            repetitionSamples.add(documentName, repetition, "page_count", "pages", info.pageCount.toDouble())

            val (_, firstMs) = measuredMillis { adapter.render(0, 1.0) }
            repetitionSamples.add(documentName, repetition, "first_page_100", "ms", firstMs)

            val successorCount = min(4, info.pageCount - 1)
            if (successorCount > 0) {
                val (_, successorsMs) = measuredMillis {
                    for (page in 1..successorCount) {
                        adapter.render(page, 1.0)
                    }
                }
                repetitionSamples.add(
                    documentName,
                    repetition,
                    "successive_page_100",
                    "ms/page",
                    successorsMs / successorCount,
                )
            }

            for (scale in SCALES) {
                val (_, zoomMs) = measuredMillis { adapter.render(0, scale) }
                repetitionSamples.add(
                    documentName,
                    repetition,
                    "zoom_${(scale * 100).toInt()}",
                    "ms",
                    zoomMs,
                )
            }

            val (_, fullMs) = measuredMillis {
                repeat(info.pageCount) { page ->
                    adapter.render(page, 1.0)
                }
            }
            repetitionSamples.add(documentName, repetition, "full_document_100", "ms", fullMs)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try {
                val (_, measuredCloseMs) = measuredMillis { adapter.close() }
                closeMs = measuredCloseMs
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
            totalMs = (System.nanoTime() - totalStarted) / 1_000_000.0
            sampler.close()
            peak = sampler.peak()
            forceGcForMeasurement()
            after = snapshotMemory()
        }

        if (failure != null) {
            robustness += RobustnessObservation(
                renderer = rendererId,
                scenario = "benchmark:$documentName:repetition:$repetition",
                outcome = "ERROR",
                elapsedMs = totalMs,
                exception = failure::class.qualifiedName.orEmpty(),
                diagnostic = failure.message.orEmpty(),
            )
            return
        }

        repetitionSamples.add(documentName, repetition, "close", "ms", closeMs)
        repetitionSamples.add(documentName, repetition, "total_document", "ms", totalMs)
        repetitionSamples.addMemory(documentName, repetition, "memory_before", before)
        repetitionSamples.add(
            documentName,
            repetition,
            "memory_peak_heap",
            "MiB",
            peak.heapUsedBytes.toMebibytes(),
        )
        peak.rssBytes?.let {
            repetitionSamples.add(documentName, repetition, "memory_peak_rss", "MiB", it.toMebibytes())
        }
        repetitionSamples.addMemory(documentName, repetition, "memory_after", after)
        samples += repetitionSamples
    }

    private fun MutableList<MetricSample>.addMemory(
        document: String,
        repetition: Int,
        prefix: String,
        snapshot: MemorySnapshot,
    ) {
        add(document, repetition, "${prefix}_heap", "MiB", snapshot.heapUsedBytes.toMebibytes())
        snapshot.rssBytes?.let {
            add(document, repetition, "${prefix}_rss", "MiB", it.toMebibytes())
        }
    }

    private fun exerciseRenderingSequence(
        adapter: RendererAdapter,
        descriptor: CorpusDocument,
    ) {
        val info = adapter.open(descriptor.path)
        adapter.render(0, 1.0)
        val successorCount = min(4, info.pageCount - 1)
        for (page in 1..successorCount) {
            adapter.render(page, 1.0)
        }
        SCALES.forEach { scale -> adapter.render(0, scale) }
        repeat(info.pageCount) { page -> adapter.render(page, 1.0) }
    }

    private fun writeExecutionOrder(schedule: List<ScheduledDocument>) {
        val lines = buildList {
            add("renderer\trepetition\tposition\tdocument\tseed")
            schedule.groupBy { it.repetition }.toSortedMap().forEach { (repetition, entries) ->
                entries.forEachIndexed { position, entry ->
                    add("$rendererId\t$repetition\t${position + 1}\t${entry.document.path.name}\t$EXECUTION_ORDER_SEED")
                }
            }
        }
        Files.write(rendererOutput.resolve("execution-order.tsv"), lines)
    }

    private fun runRobustnessChecks() {
        val valid = documents.first().path
        val invalid = corpusRoot.resolve("invalid")
        scenario("missing_document") { adapter ->
            adapter.open(corpusRoot.resolve("does-not-exist.pdf"))
        }
        scenario("empty_document") { adapter ->
            adapter.open(invalid.resolve("empty.pdf"))
        }
        scenario("corrupt_document") { adapter ->
            adapter.open(invalid.resolve("corrupt.pdf"))
        }
        scenario("page_out_of_range") { adapter ->
            val info = adapter.open(valid)
            adapter.render(info.pageCount, 1.0)
        }
        scenario("repeated_open") { adapter ->
            adapter.open(valid)
            adapter.open(valid)
        }
        scenario("repeated_close", expectedFailure = false) { adapter ->
            adapter.open(valid)
            adapter.close()
            adapter.close()
        }
    }

    private fun scenario(
        name: String,
        expectedFailure: Boolean = true,
        operation: (RendererAdapter) -> Unit,
    ) {
        val started = System.nanoTime()
        var adapter: RendererAdapter? = null
        try {
            adapter = adapterFactory()
            operation(adapter)
            robustness += RobustnessObservation(
                rendererId,
                name,
                if (expectedFailure) "UNEXPECTED_SUCCESS" else "SUCCESS",
                (System.nanoTime() - started) / 1_000_000.0,
                "",
                if (expectedFailure) "Scenario should have failed cleanly" else "Idempotent close accepted",
            )
        } catch (failure: Throwable) {
            robustness += RobustnessObservation(
                rendererId,
                name,
                if (expectedFailure) "EXPECTED_ERROR" else "ERROR",
                (System.nanoTime() - started) / 1_000_000.0,
                failure::class.qualifiedName.orEmpty(),
                failure.message.orEmpty(),
            )
        } finally {
            runCatching { adapter?.close() }
        }
    }

    private fun generateVisualSamples() {
        val imageRoot = rendererOutput.resolve("images").createDirectories()
        documents.forEach { descriptor ->
            adapterFactory().use { adapter ->
                val info = adapter.open(descriptor.path)
                for (scale in SCALES) {
                    val image = adapter.render(0, scale)
                    writePng(
                        image,
                        imageRoot.resolve(
                            "${descriptor.path.fileName.toString().removeSuffix(".pdf")}" +
                                "-page-001-${(scale * 100).toInt()}pct.png",
                        ),
                    )
                }
                if (info.pageCount > 1) {
                    writePng(
                        adapter.render(info.pageCount - 1, 1.0),
                        imageRoot.resolve(
                            "${descriptor.path.fileName.toString().removeSuffix(".pdf")}" +
                                "-page-${info.pageCount.toString().padStart(3, '0')}-100pct.png",
                        ),
                    )
                }
            }
        }
    }

    private fun writePng(image: RasterImage, target: Path) {
        check(Files.notExists(target)) { "Refusing to overwrite visual result: $target" }
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        check(ImageIO.write(buffered, "png", target.toFile())) { "No PNG writer available" }
    }

    private fun writeTsv() {
        val metricLines = buildList {
            add("renderer\tdocument\trepetition\tmetric\tunit\tvalue")
            samples.forEach { sample ->
                add(
                    listOf(
                        sample.renderer,
                        sample.document,
                        sample.repetition,
                        sample.metric,
                        sample.unit,
                        "%.6f".format(java.util.Locale.ROOT, sample.value),
                    ).joinToString("\t"),
                )
            }
        }
        Files.write(rendererOutput.resolve("measurements.tsv"), metricLines)

        val robustnessLines = buildList {
            add("renderer\tscenario\toutcome\telapsed_ms\texception\tdiagnostic")
            robustness.forEach { observation ->
                add(
                    listOf(
                        observation.renderer,
                        observation.scenario,
                        observation.outcome,
                        "%.6f".format(java.util.Locale.ROOT, observation.elapsedMs),
                        cleanTsv(observation.exception),
                        cleanTsv(observation.diagnostic),
                    ).joinToString("\t"),
                )
            }
        }
        Files.write(rendererOutput.resolve("robustness.tsv"), robustnessLines)
    }

    private fun MutableList<MetricSample>.add(
        document: String,
        repetition: Int,
        metric: String,
        unit: String,
        value: Double,
    ) {
        samples += MetricSample(rendererId, document, repetition, metric, unit, value)
    }

    private fun Long.toMebibytes(): Double = this / (1024.0 * 1024.0)

    companion object {
        val SCALES = listOf(1.0, 1.5, 2.0, 3.0)
        const val EXECUTION_ORDER_SEED = 20_260_730L
    }
}

internal data class ScheduledDocument(
    val repetition: Int,
    val document: CorpusDocument,
)

internal fun measurementSchedule(
    documents: List<CorpusDocument>,
    repetitions: Int,
): List<ScheduledDocument> =
    (1..repetitions).flatMap { repetition ->
        shuffledDocuments(documents, BenchmarkRunner.EXECUTION_ORDER_SEED + repetition)
            .map { ScheduledDocument(repetition, it) }
    }

private fun shuffledDocuments(
    documents: List<CorpusDocument>,
    seed: Long,
): List<CorpusDocument> =
    documents.toMutableList().also { Collections.shuffle(it, Random(seed)) }

private fun cleanTsv(value: String): String =
    value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
