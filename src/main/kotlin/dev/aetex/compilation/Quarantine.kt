package dev.aetex.compilation

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.Properties

enum class QuarantineRecoveryState {
    PENDING,
    VERIFIED,
    REJECTED
}

enum class OutputLeasePhase {
    RESERVED,
    STARTING,
    STARTED
}

data class OutputLeaseRecord(
    val identity: OutputSpaceIdentity,
    val projectRoot: Path,
    val sessionId: BuildSessionId,
    val createdAt: Instant,
    val bootIdentity: String?,
    val phase: OutputLeasePhase = OutputLeasePhase.RESERVED,
    val coordinator: ProcessIdentity? = null,
    val descendants: List<ProcessIdentity> = emptyList(),
    val logPath: Path? = null
)

data class QuarantineRecord(
    val recordId: String,
    val outputSpaceIdentity: OutputSpaceIdentity,
    val outputPath: Path,
    val projectRoot: Path,
    val responsibleSession: BuildSessionId,
    val cause: BuildFailure,
    val createdAt: Instant,
    val coordinator: ProcessIdentity?,
    val descendants: List<ProcessIdentity>,
    val bootIdentity: String?,
    val responsibleResultId: String?,
    val logPath: Path?,
    val recoveryState: QuarantineRecoveryState,
    val recoveryDetail: String? = null
)

interface CoordinationStore {
    fun loadLeases(): List<OutputLeaseRecord>
    fun loadQuarantines(): List<QuarantineRecord>
    fun persistLease(record: OutputLeaseRecord)
    fun markLeaseStarting(sessionId: BuildSessionId)
    fun updateLeaseProcesses(
        sessionId: BuildSessionId,
        coordinator: ProcessIdentity,
        descendants: List<ProcessIdentity>
    )
    fun removeLease(
        sessionId: BuildSessionId,
        identity: OutputSpaceIdentity? = null
    )
    fun persistQuarantine(record: QuarantineRecord)
    fun removeQuarantine(recordId: String)
}

class OutputLeaseConflictException(
    val identity: OutputSpaceIdentity
) : IOException("The output space already has a durable lease.")

class FileCoordinationStore(
    private val root: Path
) : CoordinationStore {
    private val leaseDirectory = root.resolve("leases")
    private val quarantineDirectory = root.resolve("quarantine")
    private val leasePathsBySession = mutableMapOf<BuildSessionId, Path>()

    @Synchronized
    override fun loadLeases(): List<OutputLeaseRecord> =
        loadProperties(leaseDirectory).mapNotNull { (path, properties) ->
            runCatching { properties.toLease() }.getOrNull()?.also {
                leasePathsBySession[it.sessionId] = path
            }
        }

    override fun loadQuarantines(): List<QuarantineRecord> =
        loadProperties(quarantineDirectory).mapNotNull { (_, properties) ->
            runCatching { properties.toQuarantine() }.getOrNull()
        }

    @Synchronized
    override fun persistLease(record: OutputLeaseRecord) {
        requireValidIdentity(record.identity)
        require(record.projectRoot.isAbsolute) { "Lease project root must be absolute." }
        require(record.logPath == null || record.logPath.isAbsolute) {
            "Lease log path must be absolute."
        }
        ensureDirectory(leaseDirectory)
        val path = leasePath(record.identity)
        try {
            Files.createFile(path)
        } catch (_: FileAlreadyExistsException) {
            throw OutputLeaseConflictException(record.identity)
        }
        try {
            writeProperties(path, record.toProperties())
            leasePathsBySession[record.sessionId] = path
        } catch (error: IOException) {
            // The empty reservation intentionally remains as a conservative global guard.
            throw error
        }
    }

    @Synchronized
    override fun markLeaseStarting(sessionId: BuildSessionId) {
        updateLease(sessionId) { properties ->
            properties["phase"] = OutputLeasePhase.STARTING.name
        }
    }

    @Synchronized
    override fun updateLeaseProcesses(
        sessionId: BuildSessionId,
        coordinator: ProcessIdentity,
        descendants: List<ProcessIdentity>
    ) {
        updateLease(sessionId) { properties ->
            properties["phase"] = OutputLeasePhase.STARTED.name
            properties["coordinator"] = encodeIdentity(coordinator)
            properties["descendants"] = descendants.joinToString("|", transform = ::encodeIdentity)
        }
    }

    @Synchronized
    override fun removeLease(
        sessionId: BuildSessionId,
        identity: OutputSpaceIdentity?
    ) {
        val path = leasePathsBySession.remove(sessionId)
            ?: leaseRecordPath(sessionId)
            ?: identity?.let(::leasePath)?.takeIf(Files::exists)
        if (path != null) {
            Files.deleteIfExists(path)
            forceDirectory(leaseDirectory)
        }
    }

    @Synchronized
    override fun persistQuarantine(record: QuarantineRecord) {
        requireSafeRecordId(record.recordId)
        requireValidIdentity(record.outputSpaceIdentity)
        require(record.outputPath.isAbsolute && record.projectRoot.isAbsolute) {
            "Quarantine paths must be absolute."
        }
        require(record.logPath == null || record.logPath.isAbsolute) {
            "Quarantine log path must be absolute."
        }
        writeProperties(
            quarantineDirectory.resolve("${record.recordId}.properties"),
            record.toProperties()
        )
    }

    @Synchronized
    override fun removeQuarantine(recordId: String) {
        requireSafeRecordId(recordId)
        Files.deleteIfExists(quarantineDirectory.resolve("$recordId.properties"))
    }

    private fun loadProperties(directory: Path): List<Pair<Path, Properties>> {
        if (!Files.isDirectory(directory)) return emptyList()
        if (Files.isSymbolicLink(directory)) {
            throw IOException("Coordination storage may not be a symbolic link: $directory")
        }
        return Files.newDirectoryStream(directory, "*.properties").use { stream ->
            stream.mapNotNull { path ->
                try {
                    path to Files.newInputStream(path).use { input ->
                        Properties().apply { load(input) }
                    }
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.sortedBy { it.first.fileName.toString() }
        }
    }

    private fun writeProperties(path: Path, properties: Properties) {
        ensureDirectory(path.parent)
        val temporary = Files.createTempFile(path.parent, ".coordination-", ".tmp")
        try {
            val encoded = ByteArrayOutputStream().use { buffer ->
                properties.store(buffer, "AeTeX application-owned coordination state")
                buffer.toByteArray()
            }
            FileChannel.open(
                temporary,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { channel ->
                val bytes = ByteBuffer.wrap(encoded)
                while (bytes.hasRemaining()) {
                    channel.write(bytes)
                }
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: IOException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            forceDirectory(path.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun OutputLeaseRecord.toProperties() = Properties().apply {
        this["formatVersion"] = FORMAT_VERSION
        this["kind"] = "lease"
        putIdentity(identity)
        this["projectRoot"] = projectRoot.toString()
        this["sessionId"] = sessionId.value
        this["createdAt"] = createdAt.toString()
        this["phase"] = phase.name
        bootIdentity?.let { this["bootIdentity"] = it }
        coordinator?.let { this["coordinator"] = encodeIdentity(it) }
        this["descendants"] = descendants.joinToString("|", transform = ::encodeIdentity)
        logPath?.let { this["logPath"] = it.toString() }
    }

    private fun QuarantineRecord.toProperties() = Properties().apply {
        this["formatVersion"] = FORMAT_VERSION
        this["kind"] = "quarantine"
        this["recordId"] = recordId
        putIdentity(outputSpaceIdentity)
        this["outputPath"] = outputPath.toString()
        this["projectRoot"] = projectRoot.toString()
        this["sessionId"] = responsibleSession.value
        this["failureKind"] = cause.kind.name
        this["failureMessage"] = cause.message
        this["createdAt"] = createdAt.toString()
        coordinator?.let { this["coordinator"] = encodeIdentity(it) }
        this["descendants"] = descendants.joinToString("|", transform = ::encodeIdentity)
        bootIdentity?.let { this["bootIdentity"] = it }
        responsibleResultId?.let { this["resultId"] = it }
        logPath?.let { this["logPath"] = it.toString() }
        this["recoveryState"] = recoveryState.name
        recoveryDetail?.let { this["recoveryDetail"] = it }
    }

    private fun Properties.toLease() = OutputLeaseRecord(
        identity = readIdentity().also {
            requireFormat("lease")
        },
        projectRoot = Path.of(requireProperty("projectRoot")),
        sessionId = BuildSessionId(requireProperty("sessionId")),
        createdAt = Instant.parse(requireProperty("createdAt")),
        bootIdentity = getProperty("bootIdentity"),
        phase = OutputLeasePhase.valueOf(requireProperty("phase")),
        coordinator = getProperty("coordinator")?.let(::decodeIdentity),
        descendants = decodeIdentities(getProperty("descendants")),
        logPath = getProperty("logPath")?.let(Path::of)
    )

    private fun Properties.toQuarantine() = QuarantineRecord(
        recordId = requireProperty("recordId").also {
            requireSafeRecordId(it)
            requireFormat("quarantine")
        },
        outputSpaceIdentity = readIdentity(),
        outputPath = Path.of(requireProperty("outputPath")),
        projectRoot = Path.of(requireProperty("projectRoot")),
        responsibleSession = BuildSessionId(requireProperty("sessionId")),
        cause = BuildFailure(
            BuildFailureKind.valueOf(requireProperty("failureKind")),
            requireProperty("failureMessage")
        ),
        createdAt = Instant.parse(requireProperty("createdAt")),
        coordinator = getProperty("coordinator")?.let(::decodeIdentity),
        descendants = decodeIdentities(getProperty("descendants")),
        bootIdentity = getProperty("bootIdentity"),
        responsibleResultId = getProperty("resultId"),
        logPath = getProperty("logPath")?.let(Path::of),
        recoveryState = QuarantineRecoveryState.valueOf(requireProperty("recoveryState")),
        recoveryDetail = getProperty("recoveryDetail")
    )

    private fun Properties.readIdentity() = OutputSpaceIdentity(
        normalizedOutputPath = Path.of(requireProperty("output.normalized")),
        nearestExistingAncestor = Path.of(requireProperty("output.ancestor")),
        unresolvedRemainder = getProperty("output.remainder")?.let(Path::of),
        existingOutputIdentity = getProperty("output.existing")?.let(Path::of),
        comparisonKey = requireProperty("output.key"),
        nearestExistingAncestorFileKey = getProperty("output.ancestorFileKey"),
        existingOutputFileKey = getProperty("output.existingFileKey")
    ).also(::requireValidIdentity)

    private fun Properties.requireProperty(key: String): String =
        getProperty(key) ?: error("Missing coordination property $key")

    private fun Properties.requireFormat(expectedKind: String) {
        require(requireProperty("formatVersion") == FORMAT_VERSION) {
            "Unsupported coordination format."
        }
        require(requireProperty("kind") == expectedKind) {
            "Unexpected coordination record kind."
        }
    }

    private fun decodeIdentities(value: String?): List<ProcessIdentity> =
        value.orEmpty().split('|').filter(String::isNotEmpty).map(::decodeIdentity)

    private fun encodeIdentity(identity: ProcessIdentity): String =
        "${identity.pid},${identity.startInstant?.toString().orEmpty()}"

    private fun decodeIdentity(value: String): ProcessIdentity {
        val parts = value.split(',', limit = 2)
        return ProcessIdentity(parts[0].toLong(), parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.let(Instant::parse))
    }

    private fun Properties.putIdentity(identity: OutputSpaceIdentity) {
        this["output.normalized"] = identity.normalizedOutputPath.toString()
        this["output.ancestor"] = identity.nearestExistingAncestor.toString()
        identity.unresolvedRemainder?.let { this["output.remainder"] = it.toString() }
        identity.existingOutputIdentity?.let { this["output.existing"] = it.toString() }
        this["output.key"] = identity.comparisonKey
        identity.nearestExistingAncestorFileKey?.let {
            this["output.ancestorFileKey"] = it
        }
        identity.existingOutputFileKey?.let { this["output.existingFileKey"] = it }
    }

    private fun updateLease(sessionId: BuildSessionId, update: (Properties) -> Unit) {
        val path = leaseRecordPath(sessionId)
            ?: throw IOException("The durable output lease is unavailable.")
        val properties = Files.newInputStream(path).use { Properties().apply { load(it) } }
        try {
            properties.requireFormat("lease")
            update(properties)
            writeProperties(path, properties)
        } catch (error: RuntimeException) {
            throw IOException("The durable output lease is corrupt.", error)
        }
    }

    private fun leaseRecordPath(sessionId: BuildSessionId): Path? =
        loadProperties(leaseDirectory).firstOrNull { (_, properties) ->
            properties.getProperty("sessionId") == sessionId.value
        }?.first

    private fun leasePath(identity: OutputSpaceIdentity): Path =
        leaseDirectory.resolve("${coordinationIdentityKey(identity)}.properties")

    private fun ensureDirectory(directory: Path) {
        Files.createDirectories(directory)
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) {
            throw IOException("Coordination storage is not a real directory: $directory")
        }
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // File contents were forced; directory fsync is not available on every JVM platform.
        } catch (_: UnsupportedOperationException) {
            // Same portability constraint as above.
        }
    }

    private fun requireSafeRecordId(recordId: String) {
        require(SAFE_RECORD_ID.matches(recordId)) {
            "Coordination record identity is unsafe."
        }
    }

    private fun requireValidIdentity(identity: OutputSpaceIdentity) {
        require(
            identity.normalizedOutputPath.isAbsolute &&
                identity.nearestExistingAncestor.isAbsolute &&
                (identity.existingOutputIdentity == null ||
                    identity.existingOutputIdentity.isAbsolute) &&
                (identity.unresolvedRemainder == null ||
                    !identity.unresolvedRemainder.isAbsolute) &&
                identity.comparisonKey.isNotBlank()
        ) {
            "Output-space identity contains unsafe paths."
        }
    }

    private companion object {
        const val FORMAT_VERSION = "1"
        val SAFE_RECORD_ID = Regex("""[A-Za-z0-9_-]{1,128}""")
    }
}

fun interface BootIdentityProvider {
    fun current(): String?
}

class ConservativeBootIdentityProvider(
    private val platform: HostPlatform = HostPlatform.current()
) : BootIdentityProvider {
    override fun current(): String? {
        return when (platform) {
            HostPlatform.LINUX -> try {
                Files.readString(Path.of("/proc/sys/kernel/random/boot_id"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let { "linux:$it" }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            HostPlatform.WINDOWS -> systemProcessBootIdentity(4L, "windows")
            HostPlatform.MACOS -> systemProcessBootIdentity(1L, "macos")
        }
    }

    private fun systemProcessBootIdentity(pid: Long, prefix: String): String? = try {
        ProcessHandle.of(pid)
            .flatMap { it.info().startInstant() }
            .map { "$prefix:$it" }
            .orElse(null)
    } catch (_: SecurityException) {
        null
    }
}

sealed interface RecoveryResult {
    data class Recovered(val previous: QuarantineRecord) : RecoveryResult
    data class StillQuarantined(val record: QuarantineRecord) : RecoveryResult
    data object NotFound : RecoveryResult
}

enum class ProcessIdentityStatus {
    ABSENT,
    SAME_PROCESS,
    DIFFERENT_PROCESS,
    UNVERIFIABLE
}

fun interface ProcessIdentityInspector {
    fun inspect(identity: ProcessIdentity): ProcessIdentityStatus
}

class JvmProcessIdentityInspector : ProcessIdentityInspector {
    override fun inspect(identity: ProcessIdentity): ProcessIdentityStatus {
        val recordedStart = identity.startInstant ?: return ProcessIdentityStatus.UNVERIFIABLE
        val current = try {
            ProcessHandle.of(identity.pid).orElse(null)
        } catch (_: SecurityException) {
            return ProcessIdentityStatus.UNVERIFIABLE
        } ?: return ProcessIdentityStatus.ABSENT
        val currentStart = try {
            current.info().startInstant().orElse(null)
        } catch (_: SecurityException) {
            null
        } ?: return ProcessIdentityStatus.UNVERIFIABLE
        return if (currentStart == recordedStart) {
            ProcessIdentityStatus.SAME_PROCESS
        } else {
            ProcessIdentityStatus.DIFFERENT_PROCESS
        }
    }
}

class QuarantineRecovery(
    private val store: CoordinationStore,
    private val pathValidator: CompilationPathValidator,
    private val bootIdentityProvider: BootIdentityProvider,
    private val processIdentityInspector: ProcessIdentityInspector = JvmProcessIdentityInspector()
) {
    fun recheck(recordId: String): RecoveryResult {
        val record = store.loadQuarantines().firstOrNull { it.recordId == recordId }
            ?: return RecoveryResult.NotFound
        val processProof = processesTerminated(record)
        if (processProof != null) {
            val rejected = record.copy(
                recoveryState = QuarantineRecoveryState.REJECTED,
                recoveryDetail = processProof
            )
            store.persistQuarantine(rejected)
            return RecoveryResult.StillQuarantined(rejected)
        }
        val pathResult = pathValidator.validateRecovery(record)
        if (pathResult is PathValidationResult.Invalid) {
            val rejected = record.copy(
                recoveryState = QuarantineRecoveryState.REJECTED,
                recoveryDetail = pathResult.failure.message
            )
            store.persistQuarantine(rejected)
            return RecoveryResult.StillQuarantined(rejected)
        }
        try {
            store.removeLease(record.responsibleSession, record.outputSpaceIdentity)
            store.removeQuarantine(record.recordId)
        } catch (error: IOException) {
            val rejected = record.copy(
                recoveryState = QuarantineRecoveryState.REJECTED,
                recoveryDetail = "Verified recovery could not be persisted: ${error.message}"
            )
            store.persistQuarantine(rejected)
            return RecoveryResult.StillQuarantined(rejected)
        }
        return RecoveryResult.Recovered(record.copy(recoveryState = QuarantineRecoveryState.VERIFIED))
    }

    private fun processesTerminated(record: QuarantineRecord): String? {
        val currentBoot = bootIdentityProvider.current()
        if (
            currentBoot != null &&
            record.bootIdentity != null &&
            currentBoot != record.bootIdentity
        ) {
            return null
        }
        val identities = listOfNotNull(record.coordinator) + record.descendants
        if (identities.isEmpty()) {
            return "No verifiable process identity is available and a reboot cannot be proven."
        }
        identities.forEach { recorded ->
            when (processIdentityInspector.inspect(recorded)) {
                ProcessIdentityStatus.ABSENT,
                ProcessIdentityStatus.DIFFERENT_PROCESS -> Unit
                ProcessIdentityStatus.SAME_PROCESS ->
                    return "Recorded process ${recorded.pid} is still alive."
                ProcessIdentityStatus.UNVERIFIABLE ->
                    return "Recorded process ${recorded.pid} identity cannot be verified."
            }
        }
        return null
    }
}

internal fun quarantineRecordId(identity: OutputSpaceIdentity, sessionId: BuildSessionId): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${identity.comparisonKey}\u0000${sessionId.value}".toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

internal fun coordinationIdentityKey(identity: OutputSpaceIdentity): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.comparisonKey.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
}
