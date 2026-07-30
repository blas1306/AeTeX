package dev.aetex.compilation

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class BuildLogOrigin {
    LIFECYCLE,
    STDOUT,
    STDERR,
    CLEANUP,
    TOOL_FILE
}

enum class DecodingStatus {
    NOT_APPLICABLE,
    COMPLETE,
    PARTIAL,
    REPLACED
}

class BuildLogEvent(
    val sessionId: BuildSessionId,
    val sequence: Long,
    val timestamp: Instant,
    val elapsed: Duration,
    val origin: BuildLogOrigin,
    rawBytes: ByteArray,
    val decodedText: String?,
    val decodingStatus: DecodingStatus
) {
    private val storedRawBytes = rawBytes.copyOf()

    val rawBytes: ByteArray
        get() = storedRawBytes.copyOf()
}

data class BuildLogHandle(
    val path: Path,
    val eventCount: Long,
    val bytesStored: Long
) {
    fun readEvents(): List<BuildLogEvent> = BuildLogCodec.read(path)
}

class LogStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface BuildLog : AutoCloseable {
    val sessionId: BuildSessionId
    val storageFailure: BuildFailure?
    fun append(
        origin: BuildLogOrigin,
        rawBytes: ByteArray = byteArrayOf(),
        decodedText: String? = null,
        decodingStatus: DecodingStatus = DecodingStatus.NOT_APPLICABLE
    ): BuildLogEvent

    fun snapshot(): BuildLogHandle
}

fun interface BuildLogFactory {
    fun create(sessionId: BuildSessionId, createdAt: Instant): BuildLog
}

class FileBuildLogFactory(
    private val root: Path,
    private val quotaBytes: Long = DEFAULT_LOG_QUOTA_BYTES,
    private val clock: BuildClock = SystemBuildClock
) : BuildLogFactory {
    override fun create(sessionId: BuildSessionId, createdAt: Instant): BuildLog {
        require(SESSION_FILE_NAME.matches(sessionId.value)) {
            "Build session identity is unsafe for log storage."
        }
        Files.createDirectories(root)
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw LogStorageException("Build-log storage is not a real directory.")
        }
        return FileBuildLog(
            sessionId = sessionId,
            path = root.resolve("${sessionId.value}.aetexlog"),
            quotaBytes = quotaBytes,
            createdAt = createdAt,
            clock = clock
        )
    }

    companion object {
        const val DEFAULT_LOG_QUOTA_BYTES: Long = 32L * 1024L * 1024L
        private val SESSION_FILE_NAME = Regex("""[A-Za-z0-9_-]{1,128}""")
    }
}

class FileBuildLog(
    override val sessionId: BuildSessionId,
    private val path: Path,
    private val quotaBytes: Long,
    private val createdAt: Instant,
    private val clock: BuildClock
) : BuildLog {
    private val sequence = AtomicLong()
    private val createdNanos = clock.nanoTime()
    private var bytesStored = 0L
    private var failed = false
    private var failure: BuildFailure? = null
    private var closed = false
    private val output = DataOutputStream(
        BufferedOutputStream(
            Files.newOutputStream(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
        )
    )

    override val storageFailure: BuildFailure?
        @Synchronized get() = failure

    @Synchronized
    override fun append(
        origin: BuildLogOrigin,
        rawBytes: ByteArray,
        decodedText: String?,
        decodingStatus: DecodingStatus
    ): BuildLogEvent {
        check(!closed) { "The build log is closed." }
        if (failed) throw LogStorageException("The build log backing store has already failed.")
        val now = clock.instant()
        val nextSequence = sequence.get() + 1
        val event = BuildLogEvent(
            sessionId = sessionId,
            sequence = nextSequence,
            timestamp = now,
            elapsed = Duration.ofNanos((clock.nanoTime() - createdNanos).coerceAtLeast(0L)),
            origin = origin,
            rawBytes = rawBytes,
            decodedText = decodedText,
            decodingStatus = decodingStatus
        )
        val encoded = BuildLogCodec.encode(event)
        val recordSize = Int.SIZE_BYTES.toLong() + encoded.size
        if (bytesStored + recordSize > quotaBytes) {
            failed = true
            val error = LogStorageException("The finite build-log quota was exceeded.")
            failure = BuildFailure(
                BuildFailureKind.LOG_STORAGE_FAILURE,
                "The finite build-log quota was exceeded.",
                TechnicalCause.from(error)
            )
            throw error
        }
        try {
            output.writeInt(encoded.size)
            output.write(encoded)
            output.flush()
            bytesStored += recordSize
            sequence.incrementAndGet()
        } catch (error: IOException) {
            failed = true
            failure = BuildFailure(
                BuildFailureKind.LOG_STORAGE_FAILURE,
                "The build log could not be stored.",
                TechnicalCause.from(error)
            )
            throw LogStorageException("The build log could not be stored.", error)
        }
        return event
    }

    @Synchronized
    override fun snapshot(): BuildLogHandle {
        if (!closed) {
            try {
                output.flush()
            } catch (_: IOException) {
                // The earlier retained prefix remains readable.
            }
        }
        return BuildLogHandle(path, sequence.get(), bytesStored)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            output.close()
        } catch (error: IOException) {
            if (!failed) {
                failed = true
                failure = BuildFailure(
                    BuildFailureKind.LOG_STORAGE_FAILURE,
                    "The build log could not be closed.",
                    TechnicalCause.from(error)
                )
                throw LogStorageException("The build log could not be closed.", error)
            }
        }
    }
}

internal object BuildLogCodec {
    fun encode(event: BuildLogEvent): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { data ->
            data.writeUTF(event.sessionId.value)
            data.writeLong(event.sequence)
            data.writeLong(event.timestamp.epochSecond)
            data.writeInt(event.timestamp.nano)
            data.writeLong(event.elapsed.toNanos())
            data.writeInt(event.origin.ordinal)
            data.writeInt(event.decodingStatus.ordinal)
            val text = event.decodedText?.toByteArray(Charsets.UTF_8)
            data.writeInt(text?.size ?: -1)
            if (text != null) data.write(text)
            val raw = event.rawBytes
            data.writeInt(raw.size)
            data.write(raw)
        }
        return buffer.toByteArray()
    }

    fun read(path: Path): List<BuildLogEvent> {
        if (!Files.exists(path)) return emptyList()
        val events = mutableListOf<BuildLogEvent>()
        DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { input ->
            while (true) {
                val size = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                val encoded = input.readNBytes(size)
                if (encoded.size != size) break
                DataInputStream(encoded.inputStream()).use { data ->
                    val session = BuildSessionId(data.readUTF())
                    val sequence = data.readLong()
                    val timestamp = Instant.ofEpochSecond(data.readLong(), data.readInt().toLong())
                    val elapsed = Duration.ofNanos(data.readLong())
                    val origin = BuildLogOrigin.entries[data.readInt()]
                    val status = DecodingStatus.entries[data.readInt()]
                    val textSize = data.readInt()
                    val text = if (textSize >= 0) {
                        data.readNBytes(textSize).toString(Charsets.UTF_8)
                    } else {
                        null
                    }
                    val raw = data.readNBytes(data.readInt())
                    events += BuildLogEvent(
                        session,
                        sequence,
                        timestamp,
                        elapsed,
                        origin,
                        raw,
                        text,
                        status
                    )
                }
            }
        }
        return events
    }
}

class StreamingTextDecoder(charset: Charset) {
    private val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = byteArrayOf()
    private var finished = false

    @Synchronized
    fun decode(bytes: ByteArray, endOfInput: Boolean = false): Pair<String, DecodingStatus> {
        check(!finished) { "The decoder has already reached end of input." }
        val combined = ByteArray(pending.size + bytes.size)
        pending.copyInto(combined)
        bytes.copyInto(combined, pending.size)
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate((combined.size * decoder.maxCharsPerByte()).toInt() + 8)
        decoder.decode(input, output, endOfInput)
        pending = ByteArray(input.remaining())
        input.get(pending)
        if (endOfInput) {
            decoder.flush(output)
            finished = true
            pending = byteArrayOf()
        }
        output.flip()
        val text = output.toString()
        val status = when {
            text.indexOf('\uFFFD') >= 0 -> DecodingStatus.REPLACED
            !endOfInput && pending.isNotEmpty() -> DecodingStatus.PARTIAL
            else -> DecodingStatus.COMPLETE
        }
        return text to status
    }
}

interface BuildClock {
    fun instant(): Instant
    fun nanoTime(): Long = System.nanoTime()
}

data object SystemBuildClock : BuildClock {
    override fun instant(): Instant = Instant.now()
    override fun nanoTime(): Long = System.nanoTime()
}
