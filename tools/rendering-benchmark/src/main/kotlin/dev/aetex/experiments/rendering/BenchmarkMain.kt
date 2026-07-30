package dev.aetex.experiments.rendering

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import kotlin.io.path.absolute
import kotlin.io.path.createDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

private const val DEFAULT_REPETITIONS = 3
private const val DEFAULT_WARMUPS = 1

fun main(arguments: Array<String>) {
    val args = Arguments(arguments.toList())
    val projectRoot = Path.of("").toAbsolutePath().normalize()
    val corpusRoot = projectRoot.resolve("benchmark-documents/generated")

    if (args.has("--generate-corpus-only")) {
        val documents = CorpusGenerator(corpusRoot).generate()
        println("Generated ${documents.size} synthetic PDFs under $corpusRoot")
        return
    }

    if (args.has("--worker")) {
        runWorker(args, corpusRoot)
        return
    }

    val nativeLibrary = args.path("--native-library")
    val pdfiumProvenance = args.path("--pdfium-provenance")
    check(nativeLibrary.isRegularFile()) { "PDFium native library does not exist: $nativeLibrary" }
    check(pdfiumProvenance.isRegularFile()) { "PDFium provenance does not exist: $pdfiumProvenance" }
    val documents = CorpusGenerator(corpusRoot).generate()
    validateCorpusGeometry(documents)
    val runId = "run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .format(ZonedDateTime.now(ZoneOffset.UTC))
    val runDirectory = projectRoot.resolve("results").createDirectories().resolve(runId).createDirectory()
    Files.copy(pdfiumProvenance, runDirectory.resolve("pdfium-provenance.properties"))
    val repetitions = args.int("--repetitions", DEFAULT_REPETITIONS)
    val warmups = args.int("--warmups", DEFAULT_WARMUPS)

    listOf("pdfbox", "pdfium").forEach { renderer ->
        runWorkerProcess(
            renderer = renderer,
            runDirectory = runDirectory,
            corpusRoot = corpusRoot,
            nativeLibrary = nativeLibrary,
            repetitions = repetitions,
            warmups = warmups,
        )
    }

    val report = ReportWriter(runDirectory, documents, repetitions, warmups).write()
    println("Benchmark complete: $report")
}

private fun runWorker(args: Arguments, corpusRoot: Path) {
    val renderer = args.value("--renderer")
    val output = args.path("--run-dir").resolve(renderer).createDirectory()
    val nativeLibrary = args.path("--native-library")
    val documents = readCorpus(corpusRoot)
    fun runWith(factory: () -> RendererAdapter) {
        BenchmarkRunner(
            rendererId = renderer,
            adapterFactory = factory,
            documents = documents,
            corpusRoot = corpusRoot,
            rendererOutput = output,
            repetitions = args.int("--repetitions", DEFAULT_REPETITIONS),
            warmups = args.int("--warmups", DEFAULT_WARMUPS),
        ).run()
    }
    when (renderer) {
        "pdfbox" -> runWith { PdfBoxAdapter() }
        "pdfium" -> PdfiumRuntime(nativeLibrary).use { runtime ->
            runWith { PdfiumAdapter(runtime) }
        }
        else -> error("Unknown renderer: $renderer")
    }
}

private fun runWorkerProcess(
    renderer: String,
    runDirectory: Path,
    corpusRoot: Path,
    nativeLibrary: Path,
    repetitions: Int,
    warmups: Int,
) {
    val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
    val command = listOf(
        javaExecutable.toString(),
        "-Djava.awt.headless=true",
        "-cp",
        System.getProperty("java.class.path"),
        "dev.aetex.experiments.rendering.BenchmarkMainKt",
        "--worker",
        "--renderer",
        renderer,
        "--run-dir",
        runDirectory.toString(),
        "--corpus-root",
        corpusRoot.toString(),
        "--native-library",
        nativeLibrary.toString(),
        "--repetitions",
        repetitions.toString(),
        "--warmups",
        warmups.toString(),
    )
    val log = runDirectory.resolve("$renderer-worker.log")
    val process = ProcessBuilder(command)
        .directory(Path.of("").toAbsolutePath().toFile())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start()
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "Renderer worker '$renderer' failed with exit $exitCode. See $log"
    }
}

private fun readCorpus(root: Path): List<CorpusDocument> {
    val categories = Files.readAllLines(root.resolve("manifest.tsv"))
        .drop(1)
        .associate { line ->
            val fields = line.split('\t')
            fields[0] to (fields[1] to fields[2].toInt())
        }
    return categories.map { (file, details) ->
        CorpusDocument(root.resolve(file), details.first, details.second)
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("windows")

private class Arguments(
    private val values: List<String>,
) {
    fun has(name: String): Boolean = name in values

    fun value(name: String): String {
        val index = values.indexOf(name)
        require(index >= 0 && index + 1 < values.size) { "Missing argument $name" }
        return values[index + 1]
    }

    fun path(name: String): Path = Path.of(value(name)).absolute().normalize()

    fun int(name: String, default: Int): Int =
        if (has(name)) value(name).toInt().also { require(it > 0) } else default
}
