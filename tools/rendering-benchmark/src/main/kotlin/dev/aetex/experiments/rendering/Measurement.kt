package dev.aetex.experiments.rendering

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

data class MetricSample(
    val renderer: String,
    val document: String,
    val repetition: Int,
    val metric: String,
    val unit: String,
    val value: Double,
)

data class RobustnessObservation(
    val renderer: String,
    val scenario: String,
    val outcome: String,
    val elapsedMs: Double,
    val exception: String,
    val diagnostic: String,
)

data class MemorySnapshot(
    val heapUsedBytes: Long,
    val rssBytes: Long?,
)

data class MemoryPeak(
    val heapUsedBytes: Long,
    val rssBytes: Long?,
)

class MemorySampler(
    private val intervalMillis: Long = 5,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private var heapPeak = 0L
    private var rssPeak: Long? = null
    private val worker = thread(
        start = true,
        isDaemon = true,
        name = "benchmark-memory-sampler",
    ) {
        while (running.get()) {
            val snapshot = snapshotMemory()
            synchronized(this) {
                heapPeak = maxOf(heapPeak, snapshot.heapUsedBytes)
                rssPeak = listOfNotNull(rssPeak, snapshot.rssBytes).maxOrNull()
            }
            try {
                Thread.sleep(intervalMillis)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    @Synchronized
    fun peak(): MemoryPeak = MemoryPeak(heapPeak, rssPeak)

    override fun close() {
        running.set(false)
        worker.interrupt()
        worker.join()
        val final = snapshotMemory()
        synchronized(this) {
            heapPeak = maxOf(heapPeak, final.heapUsedBytes)
            rssPeak = listOfNotNull(rssPeak, final.rssBytes).maxOrNull()
        }
    }
}

data class Statistics(
    val count: Int,
    val mean: Double,
    val minimum: Double,
    val maximum: Double,
    val standardDeviation: Double,
)

fun statistics(values: List<Double>): Statistics {
    require(values.isNotEmpty())
    var count = 0
    var mean = 0.0
    var sumSquaredDifferences = 0.0
    var minimum = Double.POSITIVE_INFINITY
    var maximum = Double.NEGATIVE_INFINITY
    values.forEach { value ->
        require(value.isFinite()) { "statistics require finite measurements" }
        count++
        val delta = value - mean
        mean += delta / count
        val deltaAfterMeanUpdate = value - mean
        sumSquaredDifferences += delta * deltaAfterMeanUpdate
        minimum = minOf(minimum, value)
        maximum = maxOf(maximum, value)
    }
    return Statistics(
        count = count,
        mean = mean,
        minimum = minimum,
        maximum = maximum,
        standardDeviation = sqrt((sumSquaredDifferences / count).coerceAtLeast(0.0)),
    )
}

fun snapshotMemory(): MemorySnapshot {
    val runtime = Runtime.getRuntime()
    return MemorySnapshot(
        heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        rssBytes = currentRssBytes(),
    )
}

private fun currentRssBytes(): Long? {
    val status = Path.of("/proc/self/status")
    if (Files.isRegularFile(status)) {
        Files.newBufferedReader(status).useLines { lines ->
            val line = lines.firstOrNull { it.startsWith("VmRSS:") }
            return line?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
        }
    }
    // The standard JVM exposes committed virtual memory, not resident memory.
    // Reporting it as RSS would be methodologically incorrect. Until a
    // validated native reader exists for the host OS, leave RSS unavailable.
    return null
}

fun rssMeasurementSource(): String =
    if (Files.isRegularFile(Path.of("/proc/self/status"))) {
        "Linux /proc/self/status VmRSS (current process resident set)"
    } else {
        "unavailable: this platform has no validated RSS reader; heap remains separately reported"
    }

inline fun <T> measuredMillis(block: () -> T): Pair<T, Double> {
    val started = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - started) / 1_000_000.0
}

fun forceGcForMeasurement() {
    repeat(2) {
        System.gc()
        Thread.sleep(30)
    }
}
