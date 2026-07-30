package dev.aetex.compilation

import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BuildLogTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `stores stdout and stderr chunks with sequence timestamps and raw bytes`() {
        val created = Instant.now()
        val log = FileBuildLogFactory(temporaryDirectory).create(BuildSessionId("one"), created)
        log.append(BuildLogOrigin.STDOUT, byteArrayOf(1, 2), "out", DecodingStatus.COMPLETE)
        log.append(BuildLogOrigin.STDERR, byteArrayOf(3), "err", DecodingStatus.COMPLETE)
        log.close()

        val events = log.snapshot().readEvents()
        assertEquals(listOf(1L, 2L), events.map(BuildLogEvent::sequence))
        assertEquals(listOf(BuildLogOrigin.STDOUT, BuildLogOrigin.STDERR), events.map(BuildLogEvent::origin))
        assertContentEquals(byteArrayOf(1, 2), events.first().rawBytes)
        assertTrue(events.all { !it.timestamp.isBefore(created) })
    }

    @Test
    fun `raw byte arrays are defensively copied`() {
        val log = FileBuildLogFactory(temporaryDirectory).create(BuildSessionId("one"), Instant.now())
        val input = byteArrayOf(7)
        val event = log.append(BuildLogOrigin.STDOUT, input, "x", DecodingStatus.COMPLETE)
        input[0] = 9
        val exposed = event.rawBytes
        exposed[0] = 8

        assertContentEquals(byteArrayOf(7), event.rawBytes)
        log.close()
    }

    @Test
    fun `streaming decoder preserves a multibyte character split across chunks`() {
        val bytes = "á".toByteArray(Charsets.UTF_8)
        val decoder = StreamingTextDecoder(Charsets.UTF_8)
        val first = decoder.decode(byteArrayOf(bytes[0]))
        val second = decoder.decode(byteArrayOf(bytes[1]))
        val end = decoder.decode(byteArrayOf(), endOfInput = true)

        assertEquals("", first.first)
        assertEquals("á", second.first)
        assertEquals("", end.first)
    }

    @Test
    fun `streaming decoder marks malformed bytes and preserves replacement text`() {
        val decoder = StreamingTextDecoder(Charsets.UTF_8)
        val decoded = decoder.decode(byteArrayOf(0xC3.toByte(), 0x28), endOfInput = true)

        assertEquals(DecodingStatus.REPLACED, decoded.second)
        assertTrue(decoded.first.contains('\uFFFD'))
    }

    @Test
    fun `finite quota rejects growth and keeps readable prefix`() {
        val log = FileBuildLog(
            BuildSessionId("quota"),
            temporaryDirectory.resolve("quota.aetexlog"),
            quotaBytes = 180,
            createdAt = Instant.now(),
            clock = SystemBuildClock
        )
        log.append(BuildLogOrigin.STDOUT, byteArrayOf(1), "prefix", DecodingStatus.COMPLETE)
        assertFailsWith<LogStorageException> {
            log.append(BuildLogOrigin.STDOUT, ByteArray(1024), "too large", DecodingStatus.COMPLETE)
        }
        log.close()

        assertEquals("prefix", log.snapshot().readEvents().single().decodedText)
    }

    @Test
    fun `diagnostics consume line view without changing raw events or final fragment`() {
        val log = FileBuildLogFactory(temporaryDirectory).create(BuildSessionId("diag"), Instant.now())
        log.append(BuildLogOrigin.STDOUT, decodedText = "! Undefined control sequence", decodingStatus = DecodingStatus.PARTIAL)
        val before = log.snapshot().readEvents()
        val diagnostics = BasicLatexDiagnosticExtractor().extract(
            BuildSessionId("diag"),
            temporaryDirectory,
            before
        )
        val after = log.snapshot().readEvents()

        assertEquals(1, diagnostics.size)
        assertEquals(DiagnosticKind.TEX_ERROR, diagnostics.single().kind)
        assertEquals(before.map(BuildLogEvent::decodedText), after.map(BuildLogEvent::decodedText))
        log.close()
    }

    @Test
    fun `quota counts the complete framed record at the exact boundary`() {
        val probePath = temporaryDirectory.resolve("probe.aetexlog")
        val probe = FileBuildLog(
            BuildSessionId("boundary"),
            probePath,
            Long.MAX_VALUE,
            Instant.now(),
            SystemBuildClock
        )
        probe.append(BuildLogOrigin.STDOUT, byteArrayOf(1, 2, 3), "x", DecodingStatus.COMPLETE)
        probe.close()
        val exactSize = Files.size(probePath)

        val exact = FileBuildLog(
            BuildSessionId("boundary"),
            temporaryDirectory.resolve("exact.aetexlog"),
            exactSize,
            Instant.now(),
            SystemBuildClock
        )
        exact.append(BuildLogOrigin.STDOUT, byteArrayOf(1, 2, 3), "x", DecodingStatus.COMPLETE)
        exact.close()

        assertEquals(exactSize, exact.snapshot().bytesStored)
    }

    @Test
    fun `decoder marks a complete chunk complete and only split input partial`() {
        val decoder = StreamingTextDecoder(Charsets.UTF_8)

        assertEquals(DecodingStatus.COMPLETE, decoder.decode("a".toByteArray()).second)
        assertEquals(
            DecodingStatus.PARTIAL,
            decoder.decode(byteArrayOf(0xC3.toByte())).second
        )
    }
}
