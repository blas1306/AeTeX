package dev.aetex.experiments.rendering

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MeasurementTest {
    @Test
    fun `statistics computes population values`() {
        val result = statistics(listOf(1.0, 2.0, 3.0))

        assertEquals(3, result.count)
        assertEquals(2.0, result.mean)
        assertEquals(1.0, result.minimum)
        assertEquals(3.0, result.maximum)
        assertEquals(0.816496580927726, result.standardDeviation, 1e-12)
    }

    @Test
    fun `statistics stays stable for a large baseline`() {
        val result = statistics(listOf(1_000_000_000_001.0, 1_000_000_000_002.0, 1_000_000_000_003.0))

        assertEquals(1_000_000_000_002.0, result.mean)
        assertEquals(0.816496580927726, result.standardDeviation, 1e-12)
    }

    @Test
    fun `execution schedule is reproducible and covers every document once per repetition`() {
        val documents = (1..8).map { index ->
            CorpusDocument(Path.of("document-$index.pdf"), "test", 1)
        }

        val first = measurementSchedule(documents, 3)
        val second = measurementSchedule(documents, 3)

        assertEquals(first, second)
        (1..3).forEach { repetition ->
            assertEquals(
                documents.toSet(),
                first.filter { it.repetition == repetition }.map { it.document }.toSet(),
            )
        }
        assertNotEquals(
            first.filter { it.repetition == 1 }.map { it.document },
            first.filter { it.repetition == 2 }.map { it.document },
        )
    }
}
