package dev.aetex.compilation

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID

@JvmInline
value class BuildSessionId(val value: String) {
    companion object {
        fun create(): BuildSessionId = BuildSessionId(UUID.randomUUID().toString())
    }
}

enum class BuildState {
    QUEUED,
    RUNNING,
    CANCELLING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

enum class BuildFailureKind {
    PLANNING_FAILURE,
    INVALID_CONFIGURATION,
    TOOL_UNAVAILABLE,
    TOOL_INVALID,
    PROCESS_START_FAILURE,
    NON_ZERO_EXIT,
    EXPECTED_ARTIFACT_MISSING,
    EXPECTED_ARTIFACT_INVALID,
    CANCELLATION_FAILURE,
    POSSIBLY_ORPHANED_PROCESS,
    LOG_STORAGE_FAILURE,
    INVALID_OUTPUT,
    OUTPUT_QUARANTINED,
    UNSAFE_PATH_CHANGE,
    UNSUPPORTED_DANGEROUS_CAPABILITY,
    ABNORMAL_APPLICATION_TERMINATION,
    EXECUTION_DEADLINE,
    INTERNAL_ERROR
}

data class TechnicalCause(
    val type: String,
    val message: String?
) {
    companion object {
        fun from(error: Throwable): TechnicalCause =
            TechnicalCause(error::class.qualifiedName ?: error::class.simpleName.orEmpty(), error.message)
    }
}

data class BuildFailure(
    val kind: BuildFailureKind,
    val message: String,
    val technicalCause: TechnicalCause? = null,
    val relatedPath: Path? = null,
    val requiredTool: ToolKind? = null,
    val toolRejections: List<RejectedToolCandidate> = emptyList()
)

sealed interface PlanningResult {
    data class Success(val plan: BuildPlan) : PlanningResult
    data class Failure(val failure: BuildFailure) : PlanningResult
}

enum class CancellationOrigin {
    USER,
    LATEST_REQUEST_REPLACEMENT,
    PROJECT_CLOSE,
    APPLICATION_SHUTDOWN,
    EXECUTION_DEADLINE
}

enum class CancellationResult {
    QUEUED_CANCELLED,
    GRACEFUL_TERMINATION,
    FORCED_TERMINATION,
    FAILED
}

data class BuildCancellation(
    val origin: CancellationOrigin,
    val requestedAt: Instant,
    val result: CancellationResult? = null,
    val gracefulRequested: Boolean = false,
    val forcedRequested: Boolean = false,
    val remainingProcesses: List<ProcessIdentity> = emptyList(),
    val streamsReachedEof: Boolean? = null
)

enum class ArtifactRole {
    PRIMARY_PDF,
    TEX_LOG,
    SYNCTEX,
    AUXILIARY
}

enum class ArtifactStatus {
    CREATED,
    MODIFIED,
    REUSED_UNCHANGED,
    MISSING,
    INVALID
}

data class ArtifactObservation(
    val expected: ExpectedArtifact,
    val status: ArtifactStatus,
    val size: Long? = null,
    val lastModified: Instant? = null,
    val technicalDetail: String? = null
)

enum class DiagnosticKind {
    PLANNING,
    TOOL_DISCOVERY,
    PROCESS_START,
    TEX_ERROR,
    TEX_WARNING,
    PROCESS_EXIT,
    CANCELLATION,
    CLEANUP,
    ARTIFACT,
    LOG_STORAGE,
    PARSER,
    SECURITY,
    INTERNAL
}

enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

enum class DiagnosticConfidence {
    EXACT,
    CONSERVATIVE,
    INCOMPLETE
}

data class BuildDiagnostic(
    val kind: DiagnosticKind,
    val severity: DiagnosticSeverity,
    val message: String,
    val sessionId: BuildSessionId? = null,
    val origin: String,
    val sourcePath: Path? = null,
    val line: Int? = null,
    val column: Int? = null,
    val relatedEventSequence: Long? = null,
    val confidence: DiagnosticConfidence = DiagnosticConfidence.CONSERVATIVE,
    val technicalDetail: String? = null
)

data class ProcessIdentity(
    val pid: Long,
    val startInstant: Instant?
)

data class ProcessEvidence(
    val started: Boolean,
    val coordinator: ProcessIdentity? = null,
    val descendants: List<ProcessIdentity> = emptyList(),
    val exitCode: Int? = null,
    val streamsReachedEof: Boolean = false,
    val resourcesClosed: Boolean = false,
    val cleanupProven: Boolean = false
)

data class QuarantineSnapshot(
    val recordId: String,
    val recoveryState: QuarantineRecoveryState
)

class BuildResult(
    val sessionId: BuildSessionId,
    val state: BuildState,
    val plan: BuildPlan,
    val failure: BuildFailure?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant,
    val processEvidence: ProcessEvidence,
    val cancellation: BuildCancellation?,
    val logs: BuildLogHandle,
    diagnostics: List<BuildDiagnostic>,
    artifacts: List<ArtifactObservation>,
    missingRequiredArtifacts: List<ExpectedArtifact>,
    val quarantine: QuarantineSnapshot?,
    trace: Map<String, String>
) {
    val diagnostics: List<BuildDiagnostic> =
        Collections.unmodifiableList(diagnostics.toList())
    val artifacts: List<ArtifactObservation> =
        Collections.unmodifiableList(artifacts.toList())
    val missingRequiredArtifacts: List<ExpectedArtifact> =
        Collections.unmodifiableList(missingRequiredArtifacts.toList())
    val trace: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(trace))

    init {
        require(state.isTerminal) { "BuildResult must have a terminal state." }
        require((state == BuildState.FAILED) == (failure != null)) {
            "A failure cause is required exactly for Failed results."
        }
    }

    val duration: Duration?
        get() = startedAt?.let { Duration.between(it, finishedAt) }
}

data class BuildSessionSnapshot(
    val id: BuildSessionId,
    val plan: BuildPlan,
    val state: BuildState,
    val createdAt: Instant,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val cancellation: BuildCancellation?,
    val finishedAt: Instant?,
    val result: BuildResult?,
    /**
     * Monotonic identity assigned by the owning CompilationManager.
     *
     * Timestamps are evidence, not ordering identities: clocks may return the
     * same instant for consecutive requests. Zero is reserved for snapshots
     * produced by compatibility fixtures outside a CompilationManager.
     */
    val requestSequence: Long = 0L
) {
    init {
        require(requestSequence >= 0L)
    }
}

sealed interface BuildRequestResult {
    data class Accepted(val session: BuildSessionSnapshot) : BuildRequestResult
    data class PlanningFailed(val failure: BuildFailure) : BuildRequestResult
    data class Rejected(val failure: BuildFailure) : BuildRequestResult
}

sealed interface CancellationRequestResult {
    data class Accepted(val session: BuildSessionSnapshot) : CancellationRequestResult
    data class AlreadyTerminal(val session: BuildSessionSnapshot) : CancellationRequestResult
    data object UnknownSession : CancellationRequestResult
}

sealed interface OutputActivity {
    data object Idle : OutputActivity
    data class Session(val session: BuildSessionSnapshot) : OutputActivity
    data class Quarantined(val record: QuarantineRecord) : OutputActivity
}
