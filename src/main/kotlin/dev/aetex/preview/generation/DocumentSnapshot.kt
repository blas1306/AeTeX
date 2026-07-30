package dev.aetex.preview.generation

import dev.aetex.compilation.ArtifactObservation
import dev.aetex.compilation.ArtifactRole
import dev.aetex.compilation.ArtifactStatus
import dev.aetex.compilation.BuildResult
import dev.aetex.compilation.BuildState
import dev.aetex.compilation.RuntimeDirectoryResolver
import dev.aetex.preview.domain.GenerationId
import dev.aetex.preview.domain.PreviewError
import dev.aetex.preview.domain.PreviewErrorKind
import dev.aetex.preview.domain.PreviewResult
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.DigestInputStream
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

internal data class SnapshotPolicy(
    val maximumPdfBytes: Long = 512L * 1024 * 1024
) {
    init {
        require(maximumPdfBytes > 0)
    }
}

internal class DocumentSnapshot(
    internal val path: Path,
    val size: Long,
    val sourceLastModified: Instant,
    val sha256: String,
    private val directory: Path
) : AutoCloseable {
    private val cleanupLock = Any()
    private val cleaned = AtomicBoolean(false)

    override fun close() {
        if (cleaned.get()) return
        synchronized(cleanupLock) {
            if (cleaned.get()) return
            var failure: Throwable? = null
            try {
                Files.deleteIfExists(path)
            } catch (error: IOException) {
                failure = error
            } catch (error: SecurityException) {
                failure = error
            }
            try {
                Files.deleteIfExists(directory)
            } catch (error: IOException) {
                if (failure == null) failure = error
            } catch (error: SecurityException) {
                if (failure == null) failure = error
            }
            if (!existsConservatively(path) &&
                !existsConservatively(directory)
            ) {
                cleaned.set(true)
            } else {
                LOGGER.log(
                    Level.WARNING,
                    "Preview snapshot cleanup was incomplete for $directory.",
                    failure
                )
            }
        }
    }

    private fun existsConservatively(candidate: Path): Boolean = try {
        Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
    } catch (_: SecurityException) {
        true
    }

    companion object {
        private val LOGGER = Logger.getLogger(DocumentSnapshot::class.java.name)
    }
}

internal interface SnapshotStore {
    fun capture(
        result: BuildResult,
        generationId: GenerationId
    ): PreviewResult<SnapshotCapture>
}

internal class SnapshotCapture internal constructor(
    internal val snapshot: DocumentSnapshot,
    val observation: ArtifactObservation
)

internal class FileSnapshotStore(
    private val root: Path = defaultPreviewRoot(),
    private val policy: SnapshotPolicy = SnapshotPolicy(),
    private val hooks: SnapshotCaptureHooks = SnapshotCaptureHooks()
) : SnapshotStore {
    private val validatedRoot: Path = prepareRoot(root)
    private val supportsPosixPermissions =
        Files.getFileStore(validatedRoot).supportsFileAttributeView("posix")

    init {
        if (CLEANED_ROOTS.add(validatedRoot)) {
            cleanupAbandoned()
        }
    }

    override fun capture(
        result: BuildResult,
        generationId: GenerationId
    ): PreviewResult<SnapshotCapture> {
        if (result.state != BuildState.SUCCEEDED) {
            return failure(
                PreviewErrorKind.INVALID_BUILD_RESULT,
                "Only a successful compilation can create a PDF preview.",
                generationId
            )
        }
        if (
            !result.processEvidence.cleanupProven ||
            !result.processEvidence.resourcesClosed ||
            !result.processEvidence.streamsReachedEof
        ) {
            return failure(
                PreviewErrorKind.INVALID_BUILD_RESULT,
                "The successful compilation has not proven process and stream cleanup.",
                generationId
            )
        }
        val observations = result.artifacts.filter {
            it.expected.role == ArtifactRole.PRIMARY_PDF &&
                it.expected.required &&
                it.expected.path == result.plan.primaryPdf
        }
        if (observations.size != 1) {
            return failure(
                PreviewErrorKind.INVALID_BUILD_RESULT,
                "The compilation result does not contain exactly one required primary PDF.",
                generationId
            )
        }
        val observation = observations.single()
        if (
            observation.status !in setOf(
                ArtifactStatus.CREATED,
                ArtifactStatus.MODIFIED,
                ArtifactStatus.REUSED_UNCHANGED
            ) ||
            observation.size == null ||
            observation.lastModified == null
        ) {
            return failure(
                PreviewErrorKind.INVALID_BUILD_RESULT,
                "The compilation primary PDF was not validated as previewable.",
                generationId
            )
        }
        if (observation.size < 1L || observation.size > policy.maximumPdfBytes) {
            return failure(
                PreviewErrorKind.SNAPSHOT_TOO_LARGE,
                "The generated PDF exceeds the configured preview snapshot limit.",
                generationId
            )
        }

        val source = observation.expected.path.toAbsolutePath().normalize()
        val projectRoot = result.plan.workingDirectory.toAbsolutePath().normalize()
        if (validatedRoot.startsWith(projectRoot)) {
            return failure(
                PreviewErrorKind.INVALID_SNAPSHOT,
                "Preview runtime storage must be outside the project.",
                generationId
            )
        }

        var generationDirectory: Path? = null
        var partial: Path? = null
        return try {
            val outputDirectory =
                result.plan.invocation.outputDirectory.toAbsolutePath().normalize()
            if (
                !source.startsWith(outputDirectory) ||
                Files.isSymbolicLink(outputDirectory) ||
                containsSymbolicLink(outputDirectory, source)
            ) {
                return failure(
                    PreviewErrorKind.INVALID_SNAPSHOT,
                    "The primary PDF path contains an unexpected symbolic link.",
                    generationId
                )
            }
            val outputReal = outputDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS)
            val before = readSafeAttributes(source)
            val sourceReal = source.toRealPath(LinkOption.NOFOLLOW_LINKS)
            if (
                Files.isSymbolicLink(source) ||
                !before.isRegularFile ||
                !Files.isReadable(source) ||
                !sourceReal.startsWith(outputReal) ||
                before.size() != observation.size ||
                before.lastModifiedTime().toInstant() != observation.lastModified
            ) {
                return failure(
                    PreviewErrorKind.INVALID_SNAPSHOT,
                    "The primary PDF changed or became unsafe before preview capture.",
                    generationId
                )
            }

            generationDirectory = createPrivateDirectory(
                validatedRoot.resolve("generation-${generationId.value}-${UUID.randomUUID()}")
            )
            partial = generationDirectory.resolve("document.pdf.part")
            val finalPath = generationDirectory.resolve("document.pdf")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val options: Set<OpenOption> =
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            FileChannel.open(source, options).use { channel ->
                val opened = readSafeAttributes(source)
                requireSameSource(before, opened)
                newPrivateOutputChannel(partial).use { outputChannel ->
                    Channels.newOutputStream(outputChannel).use { output ->
                        val buffer = ByteBuffer.allocate(DEFAULT_COPY_BUFFER)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException(
                                    "Preview snapshot capture was interrupted."
                                )
                            }
                            buffer.clear()
                            val read = channel.read(buffer)
                            if (read < 0) break
                            digest.update(buffer.array(), 0, read)
                            output.write(buffer.array(), 0, read)
                            copied += read
                            if (copied > policy.maximumPdfBytes) {
                                throw SnapshotLimitExceeded()
                            }
                        }
                    }
                }
                hooks.afterCopy(source, partial)
                val afterCopy = readSafeAttributes(source)
                requireSameSource(before, afterCopy)
                channel.position(0L)
                val verificationDigest = MessageDigest.getInstance("SHA-256")
                val verificationBuffer = ByteBuffer.allocate(DEFAULT_COPY_BUFFER)
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException(
                            "Preview snapshot verification was interrupted."
                        )
                    }
                    verificationBuffer.clear()
                    val read = channel.read(verificationBuffer)
                    if (read < 0) break
                    verificationDigest.update(verificationBuffer.array(), 0, read)
                }
                requireSameSource(before, readSafeAttributes(source))
                if (!MessageDigest.isEqual(digest.digest(), verificationDigest.digest())) {
                    throw SnapshotSourceChanged()
                }
            }
            if (copied != observation.size || Files.isSymbolicLink(source)) {
                throw SnapshotSourceChanged()
            }
            try {
                Files.move(partial, finalPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial, finalPath)
            }
            partial = null
            hardenFilePermissions(finalPath)
            val finalAttributes = readSafeAttributes(finalPath)
            if (
                !finalAttributes.isRegularFile ||
                finalAttributes.size() != copied ||
                Files.isSymbolicLink(finalPath)
            ) {
                throw InvalidSnapshotAfterCopy()
            }
            val finalDigest = sha256(finalPath)
            val contentDigest = HexFormat.of().formatHex(finalDigest)
            val sourceDigest = sha256(source)
            requireSameSource(before, readSafeAttributes(source))
            if (!MessageDigest.isEqual(finalDigest, sourceDigest)) {
                throw SnapshotSourceChanged()
            }
            PreviewResult.Success(
                SnapshotCapture(
                    snapshot = DocumentSnapshot(
                        path = finalPath,
                        size = copied,
                        sourceLastModified = observation.lastModified,
                        sha256 = contentDigest,
                        directory = generationDirectory
                    ),
                    observation = observation
                )
            )
        } catch (error: SnapshotLimitExceeded) {
            cleanupPartial(partial, generationDirectory)
            failure(
                PreviewErrorKind.SNAPSHOT_TOO_LARGE,
                "The generated PDF exceeds the configured preview snapshot limit.",
                generationId,
                error
            )
        } catch (error: SnapshotSourceChanged) {
            cleanupPartial(partial, generationDirectory)
            failure(
                PreviewErrorKind.INVALID_SNAPSHOT,
                "The primary PDF changed while the preview snapshot was being captured.",
                generationId,
                error
            )
        } catch (error: InvalidSnapshotAfterCopy) {
            cleanupPartial(partial, generationDirectory)
            failure(
                PreviewErrorKind.INVALID_SNAPSHOT,
                "The private PDF snapshot failed post-copy validation.",
                generationId,
                error
            )
        } catch (error: Throwable) {
            cleanupPartial(partial, generationDirectory)
            failure(
                PreviewErrorKind.SNAPSHOT_COPY_FAILED,
                "The generated PDF could not be copied into private preview storage.",
                generationId,
                error
            )
        }
    }

    private fun cleanupAbandoned() {
        try {
            Files.newDirectoryStream(validatedRoot, "generation-*").use { stream ->
                stream.forEach { directory ->
                    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(directory)
                    ) {
                        Files.deleteIfExists(directory.resolve("document.pdf.part"))
                        Files.deleteIfExists(directory.resolve("document.pdf"))
                        Files.deleteIfExists(directory)
                    }
                }
            }
        } catch (error: Throwable) {
            LOGGER.log(Level.WARNING, "Abandoned preview snapshot cleanup was incomplete.", error)
        }
    }

    private fun readSafeAttributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun requireSameSource(
        expected: BasicFileAttributes,
        actual: BasicFileAttributes
    ) {
        if (
            !actual.isRegularFile ||
            expected.fileKey()?.toString() != actual.fileKey()?.toString() ||
            expected.size() != actual.size() ||
            expected.lastModifiedTime() != actual.lastModifiedTime()
        ) {
            throw SnapshotSourceChanged()
        }
    }

    private fun containsSymbolicLink(outputDirectory: Path, source: Path): Boolean {
        var current = outputDirectory
        for (segment in outputDirectory.relativize(source)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun createPrivateDirectory(path: Path): Path =
        if (supportsPosixPermissions) {
            Files.createDirectory(
                path,
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")
                )
            )
        } else {
            Files.createDirectory(path)
        }

    private fun newPrivateOutputChannel(path: Path): FileChannel =
        if (supportsPosixPermissions) {
            FileChannel.open(
                path,
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")
                )
            )
        } else {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }

    private fun hardenFilePermissions(path: Path) {
        if (supportsPosixPermissions) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    private fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            DigestInputStream(input, digest).use { digestInput ->
                val buffer = ByteArray(DEFAULT_COPY_BUFFER)
                while (digestInput.read(buffer) >= 0) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException(
                            "Preview snapshot verification was interrupted."
                        )
                    }
                }
            }
        }
        return digest.digest()
    }

    private fun cleanupPartial(partial: Path?, directory: Path?) {
        try {
            partial?.let(Files::deleteIfExists)
            directory?.resolve("document.pdf")?.let(Files::deleteIfExists)
            directory?.let(Files::deleteIfExists)
        } catch (error: Throwable) {
            LOGGER.log(Level.WARNING, "Failed preview snapshot capture left cleanup work.", error)
        }
    }

    private fun failure(
        kind: PreviewErrorKind,
        message: String,
        generationId: GenerationId,
        cause: Throwable? = null
    ): PreviewResult.Failure {
        LOGGER.log(
            if (cause == null) Level.INFO else Level.WARNING,
            "$message (generation=${generationId.value})",
            cause
        )
        return PreviewResult.Failure(PreviewError(kind, message, generationId, technicalCause = cause))
    }

    private class SnapshotLimitExceeded : IOException()
    private class SnapshotSourceChanged : IOException()
    private class InvalidSnapshotAfterCopy : IOException()

    companion object {
        private const val DEFAULT_COPY_BUFFER = 64 * 1024
        private val CLEANED_ROOTS = ConcurrentHashMap.newKeySet<Path>()
        private val LOGGER = Logger.getLogger(FileSnapshotStore::class.java.name)

        private fun defaultPreviewRoot(): Path =
            RuntimeDirectoryResolver.resolve(System.getProperty("user.home")).resolve("preview")

        private fun prepareRoot(path: Path): Path {
            val normalized = path.toAbsolutePath().normalize()
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(normalized)
            }
            require(
                !Files.isSymbolicLink(normalized) &&
                    Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isReadable(normalized) &&
                    Files.isWritable(normalized)
            ) { "Preview runtime root must be a readable writable real directory." }
            return normalized.toRealPath()
        }
    }
}

internal fun interface SnapshotCaptureHooks {
    fun afterCopy(source: Path, partial: Path)

    companion object {
        operator fun invoke(): SnapshotCaptureHooks = SnapshotCaptureHooks { _, _ -> }
    }
}
