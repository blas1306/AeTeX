package dev.aetex.experiments.rendering

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.io.path.name

class ReportWriter(
    private val runDirectory: Path,
    private val documents: List<CorpusDocument>,
    private val repetitions: Int,
    private val warmups: Int,
) {
    fun write(): Path {
        validateExecutionOrders()
        validateVisualDimensions()
        val samples = RENDERERS.flatMap { readMeasurements(runDirectory.resolve(it).resolve("measurements.tsv")) }
        val robustness = RENDERERS.flatMap { readRobustness(runDirectory.resolve(it).resolve("robustness.tsv")) }
        val report = runDirectory.resolve("benchmark-report.md")
        Files.writeString(report, buildReport(samples, robustness))
        return report
    }

    private fun buildReport(
        samples: List<MetricSample>,
        robustness: List<RobustnessObservation>,
    ): String = buildString {
        appendLine("# AeTeX experimental PDF rendering benchmark")
        appendLine()
        appendLine("> This is experimental evidence for Architecture 004. It is not product code and does not select a renderer.")
        appendLine()
        appendLine("Methodology revision: `2` (lifecycle-aligned audited methodology).")
        appendLine()
        appendLine("Generated: `${Instant.now()}`")
        appendLine()
        appendLine("## Environment")
        appendLine()
        environmentRows().forEach { (name, value) -> appendLine("- $name: `$value`") }
        appendLine()
        appendLine("Workers: one fresh JVM per renderer, executed sequentially in the recorded order `pdfbox`, then `pdfium`. " +
            "JVM startup, corpus generation, renderer-global initialization and PNG encoding are outside timed regions.")
        appendLine()
        appendLine("Warm-up: $warmups complete round(s) over every corpus document using the full open/render/close sequence, discarded. " +
            "Every warm-up document is really reopened through a fresh adapter. Measured repetitions: $repetitions.")
        appendLine()
        appendLine("Every measured document repetition creates and closes a fresh document adapter. Renderer-global caches may remain in their post-warm-up steady state " +
            "inside one worker, but no renderer state or heap is shared across engines.")
        appendLine("Before and after each repetition the harness requests two GCs with 30 ms settling intervals. This reduces retained Java noise but is not a guarantee that the JVM collects.")
        appendLine()
        appendLine("Document order is independently shuffled for each repetition with fixed seed `${BenchmarkRunner.EXECUTION_ORDER_SEED}`; " +
            "both renderers receive the identical schedule recorded in `execution-order.tsv`.")
        appendLine()
        appendLine("Within a repetition the fixed sequence is open, first page at 100%, up to four successive pages, page 1 at 100/150/200/300%, " +
            "all pages at 100%, then close. This intentionally measures resource reuse within one realistic open-document session. " +
            "`full_document_100` is therefore a warm-session traversal, not a cold standalone full-document render.")
        appendLine()
        appendLine("## Engines")
        appendLine()
        appendLine("| Renderer | Version | Binding |")
        appendLine("| --- | --- | --- |")
        RENDERERS.forEach { renderer ->
            val version = Files.readString(runDirectory.resolve(renderer).resolve("engine-version.txt"))
                .trim().split('\t').getOrElse(1) { "unknown" }
            val binding = if (renderer == "pdfium") "JNA 5.17.0 + pinned native binary" else "Direct JVM API"
            appendLine("| $renderer | $version | $binding |")
        }
        appendLine()
        appendLine("## PDFium binary provenance")
        appendLine()
        val provenance = readProperties(runDirectory.resolve("pdfium-provenance.properties"))
        appendLine("- Distribution: `${provenance.getProperty("distribution")}`")
        appendLine("- Version/tag: `${provenance.getProperty("version")}` / `${provenance.getProperty("tag")}`")
        appendLine("- Asset: `${provenance.getProperty("asset")}`")
        appendLine("- Source: `${provenance.getProperty("source")}`")
        appendLine("- Verified archive SHA-256: `${provenance.getProperty("archiveSha256")}`")
        appendLine("- Loaded library SHA-256: `${provenance.getProperty("librarySha256")}`")
        appendLine()
        appendLine("The distribution is a pinned third-party precompiled build, not an official Google JVM artifact. " +
            "The cached archive is verified and freshly extracted before every benchmark invocation.")
        appendLine()
        appendLine("## Documents")
        appendLine()
        appendLine("| File | Category | Pages | Bytes | SHA-256 |")
        appendLine("| --- | --- | ---: | ---: | --- |")
        val manifest = parseManifest(documents.first().path.parent.resolve("manifest.tsv"))
        documents.forEach { descriptor ->
            val row = manifest.getValue(descriptor.path.name)
            appendLine("| ${descriptor.path.name} | ${descriptor.category} | ${descriptor.expectedPages} | ${row.bytes} | `${row.sha256}` |")
        }
        appendLine()
        appendLine("All files are generated synthetic inputs. Invalid empty and truncated fixtures are excluded from performance measurements.")
        appendLine("The corpus validator requires zero page rotation and identical MediaBox/CropBox on every page before workers start.")
        appendLine()
        appendLine("## Timing metrics")
        appendLine()
        appendLine("All elapsed measurements use monotonic `System.nanoTime()`; wall-clock APIs are used only to name/report a run. " +
            "Values are milliseconds except `successive_page_100`, which is milliseconds per page.")
        appendLine()
        appendLine("- `open`: document load plus page-count retrieval.")
        appendLine("- `first_page_100`: first raster request, including renderer-lazy parsing/resource work triggered by that request.")
        appendLine("- `successive_page_100`: mean per-page time for pages 2 through at most 5 in the same session.")
        appendLine("- `zoom_*`: page 1 rerendered after its initial 100% render; these are warm-page zoom renders.")
        appendLine("- `full_document_100`: all pages traversed after the preceding interactions in the same session.")
        appendLine("- `close`: document close only; renderer-global shutdown is outside measurement.")
        appendLine("- `total_document`: wall time from immediately before `open` through completion of `close` for the complete fixed sequence. " +
            "Adapter construction and memory-sampler startup/shutdown are excluded.")
        appendLine("Every render timing includes producing and copying the complete engine-neutral `IntArray` raster. " +
            "It therefore compares the implemented JVM adapters (including JNA/native pixel transfer), not isolated native/pure-renderer core time.")
        appendLine()
        timingMetrics().forEach { metric ->
            appendMetricTable(samples, metric)
        }
        appendLine("## Memory metrics")
        appendLine()
        appendLine("Heap is JVM used heap. RSS source for this run: `${rssMeasurementSource()}`. " +
            "Peak is the maximum observed current RSS/heap while the sequence runs, sampled with a target interval of 5 ms; scheduler delays mean the interval is not guaranteed. " +
            "Values are MiB. RSS includes JVM, JNA and native renderer memory and is never substituted with heap or committed virtual memory.")
        appendLine()
        memoryMetrics().forEach { metric ->
            appendMetricTable(samples, metric)
        }
        appendLine("## Robustness")
        appendLine()
        appendLine("| Renderer | Scenario | Outcome | Time ms | Exception | Diagnostic |")
        appendLine("| --- | --- | --- | ---: | --- | --- |")
        robustness.forEach { observation ->
            appendLine(
                "| ${observation.renderer} | ${escape(observation.scenario)} | ${observation.outcome} | " +
                    "${format(observation.elapsedMs)} | `${escape(observation.exception)}` | ${escape(observation.diagnostic)} |",
            )
        }
        appendLine()
        appendLine("## Visual inspection")
        appendLine()
        appendLine("PNG samples are under `pdfbox/images/` and `pdfium/images/`. For every document they include page 1 at 100%, 150%, 200% and 300%, plus the last page at 100%. " +
            "PNG encoding happens after timing and never contributes to render measurements.")
        appendLine()
        appendLine("## Observations")
        appendLine()
        val normalErrors = robustness.filter { it.scenario.startsWith("benchmark:") }
        val unexpected = robustness.filter { it.outcome == "ERROR" || it.outcome == "UNEXPECTED_SUCCESS" }
        appendLine("- Performance rows contain mean, minimum, maximum and population standard deviation over $repetitions runs.")
        appendLine("- Population standard deviation is `sqrt(sum((x - mean)^2) / n)` over the retained measured runs; warm-ups are absent.")
        appendLine("- Execution schedules and all corresponding visual-sample dimensions were automatically checked equal between renderers.")
        appendLine("- Normal benchmark failures recorded: ${normalErrors.size}.")
        appendLine("- Robustness outcomes needing investigation: ${unexpected.size}.")
        appendLine("- Evidence validity: ${if (normalErrors.isEmpty()) "complete for the recorded run" else "INCOMPLETE; failed repetitions are excluded and this run must not support a decision"}.")
        appendLine("- Results are evidence from this machine and corpus only; the report intentionally makes no winner recommendation.")
        appendLine()
        appendLine("## Errors")
        appendLine()
        if (normalErrors.isEmpty() && unexpected.isEmpty()) {
            appendLine("No unexpected benchmark or robustness errors were recorded.")
        } else {
            (normalErrors + unexpected).distinct().forEach {
                appendLine("- `${it.renderer}/${it.scenario}`: ${it.outcome}: ${escape(it.diagnostic)}")
            }
        }
        appendLine()
        appendLine("## Summary")
        appendLine()
        appendLine("Both engines were measured with the same interface, corpus, scales, operation order and repetition policy. " +
            "Use the timing, memory, robustness and side-by-side PNG evidence together; do not infer product suitability from one aggregate number.")
        appendLine()
        appendLine("## Limitations")
        appendLine()
        appendLine("- Synthetic PDFs do not cover every font, transparency, color-space, malformed-input or optional-codec case.")
        appendLine("- Output is normalized to an opaque white RGB raster stored as ARGB with alpha 255, at 72-DPI scale factors and identical truncated pixel dimensions. " +
            "The corpus has zero rotation and CropBox equal to MediaBox. Color-management pipelines can still differ by engine/platform.")
        appendLine("- PDFium uses normal bitmap rendering with annotations and without LCD subpixel text. The corpus contains no forms or interactive annotations, " +
            "so form-widget parity is not established.")
        appendLine("- OS file caches cannot be cleared portably without privileged operations. Corpus generation and full-corpus warm-up make measured reads warm for both workers, " +
            "but fixed sequential renderer order cannot eliminate thermal or background-load drift.")
        appendLine("- JVM GC and system load introduce noise; increase repetitions and repeat on every supported OS/CPU.")
        appendLine("- The memory sampler itself adds a small equal harness load and can miss peaks shorter than its effective interval.")
        appendLine("- PDFBox uses `BufferedImage.getRGB` for the neutral raster copy; PDFium copies BGRA bytes through JNA and normalizes them in Kotlin. " +
            "This bridge cost is intentionally visible because Architecture 004 needs JVM-consumable pixels, but it must not be interpreted as PDFium core raster time.")
        appendLine("- Accurate sampled RSS is currently implemented only where `/proc/self/status` is available (validated on Linux). " +
            "macOS and Windows reports leave RSS unavailable instead of mislabeling virtual memory; use a validated external/native RSS measurement there.")
        appendLine("- Installed fonts and PDFBox's user font cache can affect substitution, first-run corpus generation and visual output. " +
            "The synthetic Standard 14 fonts are not a substitute for a representative embedded-font LaTeX corpus.")
        appendLine("- Visual quality is intentionally manual and PNG output is not a pixel-perfect correctness oracle.")
    }

    private fun StringBuilder.appendMetricTable(samples: List<MetricSample>, metric: String) {
        appendLine("### `$metric`")
        appendLine()
        appendLine("| Document | Renderer | n | Mean | Min | Max | Std dev |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
        documents.forEach { document ->
            RENDERERS.forEach { renderer ->
                val values = samples.filter {
                    it.document == document.path.name && it.renderer == renderer && it.metric == metric
                }.map { it.value }
                if (values.isNotEmpty()) {
                    val stats = statistics(values)
                    appendLine(
                        "| ${document.path.name} | $renderer | ${stats.count} | ${format(stats.mean)} | " +
                            "${format(stats.minimum)} | ${format(stats.maximum)} | ${format(stats.standardDeviation)} |",
                    )
                }
            }
        }
        appendLine()
    }

    private fun environmentRows(): List<Pair<String, String>> {
        val runtime = Runtime.getRuntime()
        val os = ManagementFactory.getOperatingSystemMXBean()
        val extended = os as? OperatingSystemMXBean
        return listOf(
            "OS" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            "Architecture" to System.getProperty("os.arch"),
            "Java runtime" to "${System.getProperty("java.runtime.name")} ${System.getProperty("java.runtime.version")}",
            "JVM" to "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}",
            "Available processors" to runtime.availableProcessors().toString(),
            "JVM max heap MiB" to format(runtime.maxMemory() / MIB),
            "Physical memory total MiB" to extended?.totalMemorySize?.let { format(it / MIB) }.orEmpty().ifBlank { "unavailable" },
            "Physical memory free MiB" to extended?.freeMemorySize?.let { format(it / MIB) }.orEmpty().ifBlank { "unavailable" },
        )
    }

    private fun readMeasurements(path: Path): List<MetricSample> =
        Files.readAllLines(path).drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            MetricSample(
                renderer = fields[0],
                document = fields[1],
                repetition = fields[2].toInt(),
                metric = fields[3],
                unit = fields[4],
                value = fields[5].toDouble(),
            )
        }

    private fun readRobustness(path: Path): List<RobustnessObservation> =
        Files.readAllLines(path).drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            RobustnessObservation(
                renderer = fields[0],
                scenario = fields[1],
                outcome = fields[2],
                elapsedMs = fields[3].toDouble(),
                exception = fields.getOrElse(4) { "" },
                diagnostic = fields.getOrElse(5) { "" },
            )
        }

    private fun parseManifest(path: Path): Map<String, ManifestRow> =
        Files.readAllLines(path).drop(1).associate { line ->
            val fields = line.split('\t')
            fields[0] to ManifestRow(fields[3].toLong(), fields[4])
        }

    private fun timingMetrics() = listOf(
        "open",
        "first_page_100",
        "successive_page_100",
        "zoom_100",
        "zoom_150",
        "zoom_200",
        "zoom_300",
        "full_document_100",
        "close",
        "total_document",
    )

    private fun memoryMetrics() = listOf(
        "memory_before_heap",
        "memory_peak_heap",
        "memory_after_heap",
        "memory_before_rss",
        "memory_peak_rss",
        "memory_after_rss",
    )

    private fun format(value: Double): String = "%.3f".format(Locale.ROOT, value)

    private fun readProperties(path: Path): Properties =
        Properties().apply {
            Files.newBufferedReader(path).use(::load)
        }

    private fun validateExecutionOrders() {
        fun normalized(renderer: String): List<String> =
            Files.readAllLines(runDirectory.resolve(renderer).resolve("execution-order.tsv"))
                .drop(1)
                .map { it.substringAfter('\t') }

        check(normalized("pdfbox") == normalized("pdfium")) {
            "Renderer execution schedules differ"
        }
    }

    private fun validateVisualDimensions() {
        val pdfBoxImages = Files.list(runDirectory.resolve("pdfbox/images")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".png") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
        val pdfiumImages = Files.list(runDirectory.resolve("pdfium/images")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".png") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
        check(pdfBoxImages == pdfiumImages) { "Renderer visual sample sets differ" }
        pdfBoxImages.forEach { fileName ->
            val pdfBoxSize = imageDimensions(runDirectory.resolve("pdfbox/images").resolve(fileName))
            val pdfiumSize = imageDimensions(runDirectory.resolve("pdfium/images").resolve(fileName))
            check(pdfBoxSize == pdfiumSize) {
                "Visual dimensions differ for $fileName: PDFBox=$pdfBoxSize PDFium=$pdfiumSize"
            }
        }
    }

    private fun imageDimensions(path: Path): Pair<Int, Int> =
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            check(readers.hasNext()) { "No image reader for $path" }
            val reader = readers.next()
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }

    private fun escape(value: String): String = value.replace("|", "\\|")

    private data class ManifestRow(val bytes: Long, val sha256: String)

    companion object {
        private val RENDERERS = listOf("pdfbox", "pdfium")
        private const val MIB = 1024.0 * 1024.0
    }
}
