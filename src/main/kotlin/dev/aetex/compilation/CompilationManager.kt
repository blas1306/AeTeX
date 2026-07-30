package dev.aetex.compilation

import dev.aetex.project.TeXProject
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompilationManager(
    private val planner: BuildPlanner = BuildPlanner(),
    private val pathValidator: CompilationPathValidator = CompilationPathValidator(),
    private val launcher: ProcessLauncher = JvmProcessLauncher(),
    private val logFactory: BuildLogFactory = FileBuildLogFactory(defaultRuntimeRoot().resolve("logs")),
    private val coordinationStore: CoordinationStore =
        FileCoordinationStore(defaultRuntimeRoot().resolve("coordination")),
    private val diagnosticExtractor: DiagnosticExtractor = BasicLatexDiagnosticExtractor(),
    private val artifactValidator: ArtifactValidator = ArtifactValidator(),
    private val clock: BuildClock = SystemBuildClock,
    private val bootIdentityProvider: BootIdentityProvider = ConservativeBootIdentityProvider(),
    private val processIdentityInspector: ProcessIdentityInspector = JvmProcessIdentityInspector(),
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
    private val processPolicy: BuildProcessPolicy = BuildProcessPolicy()
) : AutoCloseable {
    private val lock = Any()
    private val sessions = LinkedHashMap<BuildSessionId, BuildSession>()
    private val slots = LinkedHashMap<String, OutputSlot>()
    private val listeners = CopyOnWriteArrayList<(BuildSessionSnapshot) -> Unit>()
    private var nextRequestSequence = 0L
    private val recovery = QuarantineRecovery(
        coordinationStore,
        pathValidator,
        bootIdentityProvider,
        processIdentityInspector
    )
    @Volatile
    private var closed = false

    init {
        restoreCoordinationState()
    }

    fun plan(project: TeXProject): PlanningResult = planner.plan(project)

    fun requestBuild(project: TeXProject): BuildRequestResult =
        when (val planning = planner.plan(project)) {
            is PlanningResult.Success -> requestBuild(planning.plan)
            is PlanningResult.Failure -> BuildRequestResult.PlanningFailed(planning.failure)
        }

    fun requestBuild(plan: BuildPlan): BuildRequestResult {
        val session: BuildSession
        synchronized(lock) {
            if (closed) {
                return BuildRequestResult.Rejected(
                    BuildFailure(BuildFailureKind.INTERNAL_ERROR, "The compilation manager is closed.")
                )
            }
            val now = clock.instant()
            val id = BuildSessionId.create()
            val log = try {
                logFactory.create(id, now)
            } catch (error: Exception) {
                return BuildRequestResult.Rejected(
                    BuildFailure(
                        BuildFailureKind.LOG_STORAGE_FAILURE,
                        "A session log could not be created.",
                        TechnicalCause.from(error)
                    )
                )
            }
            session = BuildSession(
                id,
                plan,
                now,
                clock,
                log,
                requestSequence = ++nextRequestSequence
            )
            sessions[id] = session
            val key = plan.invocation.outputSpaceIdentity.comparisonKey
            val slot = slots.getOrPut(key) { OutputSlot() }
            when {
                slot.active == null && slot.quarantine == null -> {
                    val attempt = tryAcquireLease(session)
                    if (attempt !is LeaseAttempt.Acquired) {
                        if (attempt is LeaseAttempt.Conflict) {
                            slot.quarantine = externalLeaseQuarantine(session)
                        } else {
                            slots.remove(key)
                        }
                        sessions.remove(id)
                        closeLogBestEffort(log)
                        return BuildRequestResult.Rejected(
                            when (attempt) {
                                is LeaseAttempt.Conflict -> BuildFailure(
                                    BuildFailureKind.OUTPUT_QUARANTINED,
                                    "The output space is leased by another AeTeX process."
                                )
                                is LeaseAttempt.StorageFailure -> attempt.failure
                                is LeaseAttempt.Acquired -> error("unreachable")
                            }
                        )
                    }
                    slot.active = session
                    dispatch(session, slot, attempt.lease)
                }

                else -> {
                    val active = slot.active
                    if (active != null && active.snapshot().state == BuildState.QUEUED) {
                        slot.active = null
                        removeLeaseBestEffort(active.id)
                        completeQueuedCancellation(
                            active,
                            CancellationOrigin.LATEST_REQUEST_REPLACEMENT
                        )
                        val attempt = tryAcquireLease(session)
                        if (attempt is LeaseAttempt.Acquired) {
                            slot.active = session
                            dispatch(session, slot, attempt.lease)
                        } else {
                            slot.queued = session
                            slot.quarantine = when (attempt) {
                                is LeaseAttempt.Conflict -> externalLeaseQuarantine(session)
                                is LeaseAttempt.StorageFailure ->
                                    localStorageQuarantine(session, attempt.failure)
                                is LeaseAttempt.Acquired -> error("unreachable")
                            }
                        }
                        publish(session)
                        return BuildRequestResult.Accepted(session.snapshot())
                    }
                    slot.queued?.let { replaced ->
                        completeQueuedCancellation(
                            replaced,
                            CancellationOrigin.LATEST_REQUEST_REPLACEMENT
                        )
                    }
                    slot.queued = session
                    if (
                        active != null &&
                        active.requestCancellation(CancellationOrigin.LATEST_REQUEST_REPLACEMENT)
                    ) {
                        publish(active)
                    }
                }
            }
        }
        publish(session)
        return BuildRequestResult.Accepted(session.snapshot())
    }

    fun cancel(
        sessionId: BuildSessionId,
        origin: CancellationOrigin = CancellationOrigin.USER
    ): CancellationRequestResult {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return CancellationRequestResult.UnknownSession
            val snapshot = session.snapshot()
            if (snapshot.state.isTerminal) {
                return CancellationRequestResult.AlreadyTerminal(snapshot)
            }
            val key = session.plan.invocation.outputSpaceIdentity.comparisonKey
            val slot = slots[key]
            if (snapshot.state == BuildState.QUEUED) {
                if (slot?.queued === session) slot.queued = null
                if (slot?.active === session) {
                    slot.active = null
                    removeLeaseBestEffort(session.id)
                }
                completeQueuedCancellation(session, origin)
                if (slot != null && slot.active == null && slot.queued == null && slot.quarantine == null) {
                    slots.remove(key)
                }
            } else {
                if (!session.requestCancellation(origin)) {
                    return CancellationRequestResult.AlreadyTerminal(session.snapshot())
                }
                publish(session)
            }
            return CancellationRequestResult.Accepted(session.snapshot())
        }
    }

    fun observeSession(sessionId: BuildSessionId): BuildSessionSnapshot? =
        synchronized(lock) { sessions[sessionId]?.snapshot() }

    fun getResult(sessionId: BuildSessionId): BuildResult? =
        synchronized(lock) { sessions[sessionId]?.snapshot()?.result }

    fun awaitResult(sessionId: BuildSessionId, timeout: Duration): BuildResult? {
        val session = synchronized(lock) { sessions[sessionId] } ?: return null
        return try {
            session.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    fun addSessionListener(listener: (BuildSessionSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun inspectQuarantine(): List<QuarantineRecord> =
        synchronized(lock) { slots.values.mapNotNull(OutputSlot::quarantine).distinctBy { it.recordId } }

    fun retryRecovery(recordId: String): RecoveryResult {
        val result = recovery.recheck(recordId)
        if (result is RecoveryResult.Recovered) {
            synchronized(lock) {
                val key = result.previous.outputSpaceIdentity.comparisonKey
                val slot = slots[key]
                if (slot?.quarantine?.recordId == recordId) {
                    slot.quarantine = null
                    val queued = slot.queued
                    if (queued != null && slot.active == null) {
                        val attempt = tryAcquireLease(queued)
                        if (attempt is LeaseAttempt.Acquired) {
                            slot.queued = null
                            slot.active = queued
                            dispatch(queued, slot, attempt.lease)
                        } else {
                            slot.quarantine = when (attempt) {
                                is LeaseAttempt.Conflict -> externalLeaseQuarantine(queued)
                                is LeaseAttempt.StorageFailure ->
                                    localStorageQuarantine(queued, attempt.failure)
                                is LeaseAttempt.Acquired -> error("unreachable")
                            }
                        }
                    } else if (slot.active == null && slot.queued == null) {
                        slots.remove(key)
                    }
                }
            }
        } else if (result is RecoveryResult.StillQuarantined) {
            synchronized(lock) {
                slots[result.record.outputSpaceIdentity.comparisonKey]?.quarantine = result.record
            }
        }
        return result
    }

    fun activity(identity: OutputSpaceIdentity): OutputActivity = synchronized(lock) {
        val slot = slots[identity.comparisonKey] ?: return@synchronized OutputActivity.Idle
        slot.quarantine?.let { return@synchronized OutputActivity.Quarantined(it) }
        (slot.active ?: slot.queued)?.let {
            return@synchronized OutputActivity.Session(it.snapshot())
        }
        OutputActivity.Idle
    }

    fun cancelAll(origin: CancellationOrigin) {
        synchronized(lock) {
            slots.values.forEach { slot ->
                slot.queued?.let {
                    completeQueuedCancellation(it, origin)
                    slot.queued = null
                }
                slot.active?.let { active ->
                    if (active.snapshot().state == BuildState.QUEUED) {
                        removeLeaseBestEffort(active.id)
                        completeQueuedCancellation(active, origin)
                        slot.active = null
                    } else {
                        active.requestCancellation(origin)
                    }
                }
            }
        }
    }

    private fun dispatch(
        session: BuildSession,
        slot: OutputSlot,
        lease: OutputLeaseRecord
    ) {
        executor.submit {
            runSession(session, slot, lease)
        }
    }

    private fun runSession(
        session: BuildSession,
        slot: OutputSlot,
        lease: OutputLeaseRecord
    ) {
        synchronized(lock) {
            if (session.snapshot().state != BuildState.QUEUED || slot.active !== session) {
                removeLeaseBestEffort(session.id)
                return
            }
            session.transition(BuildState.RUNNING)
            publish(session)
        }

        val prepared = when (val validation = pathValidator.prepareForExecution(session.plan)) {
            is PathValidationResult.Valid -> validation.value
            is PathValidationResult.Invalid -> {
                finishWithoutProcess(session, slot, validation.failure)
                return
            }
        }
        for (tool in listOf(
            session.plan.invocation.coordinator,
            session.plan.invocation.engineTool
        )) {
            val validation = pathValidator.validateExecutable(tool)
            if (validation is PathValidationResult.Invalid) {
                finishWithoutProcess(session, slot, validation.failure)
                return
            }
        }
        appendLog(session.log, BuildLogOrigin.LIFECYCLE, "Pre-start path validation completed.\n")
        session.plan.invocation.ignoredInitializationFiles.forEach {
            appendLog(session.log, BuildLogOrigin.LIFECYCLE, "Ignored latexmk initialization file: $it\n")
        }
        try {
            coordinationStore.markLeaseStarting(session.id)
        } catch (error: IOException) {
            finishWithoutProcess(
                session,
                slot,
                BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "The process-start phase could not be persisted.",
                    TechnicalCause.from(error)
                )
            )
            return
        }

        val process = BuildProcess(launcher, executor, processPolicy)
        val outcome = process.execute(
            plan = session.plan,
            log = session.log,
            cancellationSignal = session,
            onProcessStarted = { coordinator, descendants ->
                coordinationStore.updateLeaseProcesses(session.id, coordinator, descendants)
            }
        )
        session.updateCancellation(outcome.cancellation)
        val processSucceeded = outcome.failure == null &&
            outcome.cancellation == null &&
            outcome.evidence.exitCode == 0 &&
            outcome.evidence.cleanupProven
        val artifacts = artifactValidator.validate(
            session.plan,
            prepared.artifactsBeforeBuild,
            processSucceeded
        )
        artifacts.forEach {
            appendLog(
                session.log,
                BuildLogOrigin.LIFECYCLE,
                "Artifact ${it.expected.role}: ${it.status} at ${it.expected.path}\n"
            )
        }
        val attributedLogFailure = captureAttributedToolLogs(session, artifacts)
        val invalidRequired = artifacts.filter {
            it.expected.required &&
                (it.status == ArtifactStatus.MISSING || it.status == ArtifactStatus.INVALID)
        }
        val artifactFailure = invalidRequired.firstOrNull()?.let {
            BuildFailure(
                BuildFailureKind.EXPECTED_ARTIFACT_MISSING,
                "The required artifact is missing or invalid: ${it.expected.path}",
                relatedPath = it.expected.path
            )
        }
        var failure = outcome.failure ?: attributedLogFailure ?: artifactFailure ?: session.log.storageFailure
        var quarantine: QuarantineRecord? = null
        if (outcome.evidence.started && !outcome.evidence.cleanupProven) {
            quarantine = createQuarantine(
                session = session,
                lease = lease,
                failure = failure ?: BuildFailure(
                    BuildFailureKind.POSSIBLY_ORPHANED_PROCESS,
                    "Process cleanup is uncertain."
                ),
                evidence = outcome.evidence
            )
            failure = failure ?: quarantine.cause
        }

        var requestedCancellation = session.beginTerminalPublication()
        if (
            requestedCancellation != null &&
            outcome.cancellation == null &&
            outcome.evidence.cleanupProven
        ) {
            requestedCancellation = requestedCancellation.copy(
                result = CancellationResult.GRACEFUL_TERMINATION,
                streamsReachedEof = outcome.evidence.streamsReachedEof
            )
            session.updateCancellation(requestedCancellation)
        }
        if (
            requestedCancellation != null &&
            outcome.evidence.cleanupProven &&
            failure?.kind in setOf(
                BuildFailureKind.NON_ZERO_EXIT,
                BuildFailureKind.EXPECTED_ARTIFACT_MISSING
            )
        ) {
            failure = null
        }
        val logCloseFailure = closeLog(session.log)
        failure = failure ?: logCloseFailure
        if (quarantine == null) {
            try {
                coordinationStore.removeLease(session.id)
            } catch (error: IOException) {
                failure = BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "The durable output lease could not be released.",
                    TechnicalCause.from(error)
                )
                quarantine = createQuarantine(session, lease, failure, outcome.evidence)
            }
        }
        val diagnostics = extractDiagnostics(session, failure, artifacts)
        val state = when {
            quarantine != null || failure != null -> BuildState.FAILED
            requestedCancellation != null -> BuildState.CANCELLED
            else -> BuildState.SUCCEEDED
        }
        val result = buildResult(
            session = session,
            state = state,
            failure = failure,
            processEvidence = outcome.evidence,
            cancellation = outcome.cancellation ?: requestedCancellation,
            diagnostics = diagnostics,
            artifacts = artifacts,
            quarantine = quarantine
        )
        finish(session, slot, result, quarantine)
    }

    private fun finishWithoutProcess(
        session: BuildSession,
        slot: OutputSlot,
        failure: BuildFailure
    ) {
        val diagnostics = listOf(
            BuildDiagnostic(
                kind = DiagnosticKind.PROCESS_START,
                severity = DiagnosticSeverity.ERROR,
                message = failure.message,
                sessionId = session.id,
                origin = "AeTeX",
                sourcePath = failure.relatedPath,
                confidence = DiagnosticConfidence.EXACT,
                technicalDetail = failure.technicalCause?.message
            )
        )
        val artifacts = session.plan.expectedFiles.map {
            ArtifactObservation(it, ArtifactStatus.MISSING)
        }
        session.beginTerminalPublication()
        closeLog(session.log)
        var quarantine: QuarantineRecord? = null
        try {
            coordinationStore.removeLease(session.id)
        } catch (error: IOException) {
            quarantine = createQuarantine(
                session,
                OutputLeaseRecord(
                    identity = session.plan.invocation.outputSpaceIdentity,
                    projectRoot = session.plan.workingDirectory,
                    sessionId = session.id,
                    createdAt = session.createdAt,
                    bootIdentity = bootIdentityProvider.current(),
                    logPath = session.log.snapshot().path
                ),
                BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "The durable output lease could not be released.",
                    TechnicalCause.from(error)
                ),
                ProcessEvidence(started = false)
            )
        }
        val result = buildResult(
            session,
            BuildState.FAILED,
            failure,
            ProcessEvidence(started = false, cleanupProven = true, streamsReachedEof = true, resourcesClosed = true),
            session.current(),
            diagnostics,
            artifacts,
            quarantine
        )
        finish(session, slot, result, quarantine)
    }

    private fun finish(
        session: BuildSession,
        slot: OutputSlot,
        result: BuildResult,
        quarantine: QuarantineRecord?
    ) {
        synchronized(lock) {
            if (quarantine != null) slot.quarantine = quarantine
            session.complete(result)
            if (slot.active === session) slot.active = null
            publish(session)
            if (quarantine == null) {
                val queued = slot.queued
                if (queued != null) {
                    val attempt = tryAcquireLease(queued)
                    if (attempt is LeaseAttempt.Acquired) {
                        slot.queued = null
                        slot.active = queued
                        dispatch(queued, slot, attempt.lease)
                    } else {
                        slot.quarantine = when (attempt) {
                            is LeaseAttempt.Conflict -> externalLeaseQuarantine(queued)
                            is LeaseAttempt.StorageFailure ->
                                localStorageQuarantine(queued, attempt.failure)
                            is LeaseAttempt.Acquired -> error("unreachable")
                        }
                    }
                } else {
                    slots.remove(session.plan.invocation.outputSpaceIdentity.comparisonKey)
                }
            }
        }
    }

    private fun completeQueuedCancellation(
        session: BuildSession,
        origin: CancellationOrigin
    ) {
        session.requestCancellation(origin)
        session.updateCancellation(
            session.current()?.copy(
                result = CancellationResult.QUEUED_CANCELLED,
                streamsReachedEof = true
            )
        )
        session.transition(BuildState.CANCELLED)
        appendLog(session.log, BuildLogOrigin.LIFECYCLE, "Queued build cancelled: $origin.\n")
        val result = buildResult(
            session = session,
            state = BuildState.CANCELLED,
            failure = null,
            processEvidence = ProcessEvidence(
                started = false,
                streamsReachedEof = true,
                resourcesClosed = true,
                cleanupProven = true
            ),
            cancellation = session.current(),
            diagnostics = emptyList(),
            artifacts = emptyList(),
            quarantine = null
        )
        closeLogBestEffort(session.log)
        session.complete(result)
        publish(session)
    }

    private fun buildResult(
        session: BuildSession,
        state: BuildState,
        failure: BuildFailure?,
        processEvidence: ProcessEvidence,
        cancellation: BuildCancellation?,
        diagnostics: List<BuildDiagnostic>,
        artifacts: List<ArtifactObservation>,
        quarantine: QuarantineRecord?
    ): BuildResult {
        val now = clock.instant()
        val snapshot = session.snapshot()
        return BuildResult(
            sessionId = session.id,
            state = state,
            plan = session.plan,
            failure = failure,
            createdAt = session.createdAt,
            startedAt = snapshot.startedAt,
            finishedAt = now,
            processEvidence = processEvidence,
            cancellation = cancellation,
            logs = session.log.snapshot(),
            diagnostics = diagnostics.toList(),
            artifacts = artifacts.toList(),
            missingRequiredArtifacts = artifacts.filter {
                it.expected.required &&
                    (it.status == ArtifactStatus.MISSING || it.status == ArtifactStatus.INVALID)
            }.map(ArtifactObservation::expected),
            quarantine = quarantine?.let {
                QuarantineSnapshot(it.recordId, it.recoveryState)
            },
            trace = mapOf(
                "planFingerprint" to session.plan.fingerprint,
                "outputSpace" to session.plan.invocation.outputSpaceIdentity.comparisonKey,
                "coordinator" to session.plan.invocation.coordinator.executable.toString(),
                "engine" to session.plan.invocation.engineTool.executable.toString()
            )
        )
    }

    private fun extractDiagnostics(
        session: BuildSession,
        failure: BuildFailure?,
        artifacts: List<ArtifactObservation>
    ): List<BuildDiagnostic> = buildList {
        try {
            addAll(
                diagnosticExtractor.extract(
                    session.id,
                    session.plan.workingDirectory,
                    session.log.snapshot().readEvents()
                )
            )
        } catch (error: Exception) {
            add(
                BuildDiagnostic(
                    DiagnosticKind.PARSER,
                    DiagnosticSeverity.WARNING,
                    "Compilation diagnostics could not be completely extracted.",
                    session.id,
                    "AeTeX diagnostics",
                    confidence = DiagnosticConfidence.INCOMPLETE,
                    technicalDetail = error.message
                )
            )
        }
        failure?.let {
            add(
                BuildDiagnostic(
                    kind = when (it.kind) {
                        BuildFailureKind.LOG_STORAGE_FAILURE -> DiagnosticKind.LOG_STORAGE
                        BuildFailureKind.CANCELLATION_FAILURE,
                        BuildFailureKind.POSSIBLY_ORPHANED_PROCESS -> DiagnosticKind.CLEANUP
                        BuildFailureKind.EXPECTED_ARTIFACT_MISSING -> DiagnosticKind.ARTIFACT
                        else -> DiagnosticKind.PROCESS_EXIT
                    },
                    severity = DiagnosticSeverity.ERROR,
                    message = it.message,
                    sessionId = session.id,
                    origin = "AeTeX",
                    sourcePath = it.relatedPath,
                    confidence = DiagnosticConfidence.EXACT,
                    technicalDetail = it.technicalCause?.message
                )
            )
        }
        artifacts.filter {
            it.expected.required &&
                (it.status == ArtifactStatus.MISSING || it.status == ArtifactStatus.INVALID)
        }.forEach {
            add(
                BuildDiagnostic(
                    DiagnosticKind.ARTIFACT,
                    DiagnosticSeverity.ERROR,
                    "Required artifact ${it.expected.path} is ${it.status.name.lowercase()}.",
                    session.id,
                    "AeTeX artifact validation",
                    sourcePath = it.expected.path,
                    confidence = DiagnosticConfidence.EXACT,
                    technicalDetail = it.technicalDetail
                )
            )
        }
    }

    private fun createQuarantine(
        session: BuildSession,
        lease: OutputLeaseRecord,
        failure: BuildFailure,
        evidence: ProcessEvidence
    ): QuarantineRecord {
        val record = QuarantineRecord(
            recordId = quarantineRecordId(lease.identity, session.id),
            outputSpaceIdentity = lease.identity,
            outputPath = session.plan.invocation.outputDirectory,
            projectRoot = session.plan.workingDirectory,
            responsibleSession = session.id,
            cause = failure,
            createdAt = clock.instant(),
            coordinator = evidence.coordinator,
            descendants = evidence.descendants,
            bootIdentity = lease.bootIdentity,
            responsibleResultId = session.id.value,
            logPath = session.log.snapshot().path,
            recoveryState = QuarantineRecoveryState.PENDING
        )
        try {
            coordinationStore.persistQuarantine(record)
        } catch (_: IOException) {
            // The durable lease remains the conservative global guard.
        }
        return record
    }

    private fun restoreCoordinationState() {
        val quarantines = try {
            coordinationStore.loadQuarantines()
        } catch (_: Exception) {
            emptyList()
        }
        quarantines.forEach { record ->
            slots.getOrPut(record.outputSpaceIdentity.comparisonKey) { OutputSlot() }
                .quarantine = record
        }
        val quarantinedSessions = quarantines.map(QuarantineRecord::responsibleSession).toSet()
        val leases = try {
            coordinationStore.loadLeases()
        } catch (_: Exception) {
            emptyList()
        }
        leases.forEach { lease ->
            if (lease.sessionId in quarantinedSessions) return@forEach
            if (lease.phase == OutputLeasePhase.RESERVED) {
                try {
                    coordinationStore.removeLease(lease.sessionId)
                } catch (_: IOException) {
                    // The reservation remains a conservative guard but no process was started.
                }
                return@forEach
            }
            val failure = BuildFailure(
                BuildFailureKind.ABNORMAL_APPLICATION_TERMINATION,
                "An output lease survived an abnormal application termination."
            )
            val record = QuarantineRecord(
                recordId = quarantineRecordId(lease.identity, lease.sessionId),
                outputSpaceIdentity = lease.identity,
                outputPath = lease.identity.normalizedOutputPath,
                projectRoot = lease.projectRoot,
                responsibleSession = lease.sessionId,
                cause = failure,
                createdAt = clock.instant(),
                coordinator = lease.coordinator,
                descendants = lease.descendants,
                bootIdentity = lease.bootIdentity,
                responsibleResultId = null,
                logPath = lease.logPath,
                recoveryState = QuarantineRecoveryState.PENDING
            )
            try {
                coordinationStore.persistQuarantine(record)
            } catch (_: IOException) {
                // The unreleased lease remains the durable global guard.
            }
            slots.getOrPut(lease.identity.comparisonKey) { OutputSlot() }.quarantine = record
        }
    }

    private fun publish(session: BuildSession) {
        val snapshot = session.snapshot()
        listeners.forEach { listener ->
            try {
                listener(snapshot)
            } catch (_: RuntimeException) {
                // Observers cannot alter compilation lifecycle.
            }
        }
    }

    private fun appendLog(log: BuildLog, origin: BuildLogOrigin, text: String) {
        try {
            log.append(origin, decodedText = text, decodingStatus = DecodingStatus.COMPLETE)
        } catch (_: LogStorageException) {
            // BuildProcess classifies process-time failures; retained prefix remains available.
        }
    }

    private fun captureAttributedToolLogs(
        session: BuildSession,
        artifacts: List<ArtifactObservation>
    ): BuildFailure? {
        val charset = Charset.forName(session.plan.environment.charsetName)
        for (artifact in artifacts) {
            if (
                artifact.expected.role != ArtifactRole.TEX_LOG ||
                artifact.status !in setOf(ArtifactStatus.CREATED, ArtifactStatus.MODIFIED)
            ) {
                continue
            }
            val decoder = StreamingTextDecoder(charset)
            try {
                Files.newInputStream(artifact.expected.path).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val raw = buffer.copyOf(count)
                        val (text, status) = decoder.decode(raw)
                        session.log.append(BuildLogOrigin.TOOL_FILE, raw, text, status)
                    }
                    val (tail, status) = decoder.decode(byteArrayOf(), endOfInput = true)
                    if (tail.isNotEmpty()) {
                        session.log.append(
                            BuildLogOrigin.TOOL_FILE,
                            decodedText = tail,
                            decodingStatus = status
                        )
                    }
                }
            } catch (error: LogStorageException) {
                return BuildFailure(
                    BuildFailureKind.LOG_STORAGE_FAILURE,
                    "An attributed TeX log could not be retained completely.",
                    TechnicalCause.from(error),
                    artifact.expected.path
                )
            } catch (error: IOException) {
                return BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "An attributed TeX log could not be read.",
                    TechnicalCause.from(error),
                    artifact.expected.path
                )
            } catch (error: SecurityException) {
                return BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "Reading an attributed TeX log was denied.",
                    TechnicalCause.from(error),
                    artifact.expected.path
                )
            }
        }
        return null
    }

    private fun tryAcquireLease(session: BuildSession): LeaseAttempt {
        val lease = OutputLeaseRecord(
            identity = session.plan.invocation.outputSpaceIdentity,
            projectRoot = session.plan.workingDirectory,
            sessionId = session.id,
            createdAt = clock.instant(),
            bootIdentity = bootIdentityProvider.current(),
            logPath = session.log.snapshot().path
        )
        return try {
            coordinationStore.persistLease(lease)
            LeaseAttempt.Acquired(lease)
        } catch (_: OutputLeaseConflictException) {
            LeaseAttempt.Conflict
        } catch (error: IOException) {
            LeaseAttempt.StorageFailure(
                BuildFailure(
                    BuildFailureKind.INTERNAL_ERROR,
                    "The durable output lease could not be acquired.",
                    TechnicalCause.from(error)
                )
            )
        }
    }

    private fun externalLeaseQuarantine(session: BuildSession): QuarantineRecord {
        val existing = try {
            coordinationStore.loadLeases().firstOrNull {
                it.identity.comparisonKey ==
                    session.plan.invocation.outputSpaceIdentity.comparisonKey
            }
        } catch (_: IOException) {
            null
        }
        val record = QuarantineRecord(
            recordId = quarantineRecordId(
                session.plan.invocation.outputSpaceIdentity,
                existing?.sessionId ?: session.id
            ),
            outputSpaceIdentity = session.plan.invocation.outputSpaceIdentity,
            outputPath = session.plan.invocation.outputDirectory,
            projectRoot = session.plan.workingDirectory,
            responsibleSession = existing?.sessionId ?: session.id,
            cause = BuildFailure(
                BuildFailureKind.OUTPUT_QUARANTINED,
                "The output space has a durable lease owned by another AeTeX process."
            ),
            createdAt = existing?.createdAt ?: clock.instant(),
            coordinator = existing?.coordinator,
            descendants = existing?.descendants.orEmpty(),
            bootIdentity = existing?.bootIdentity ?: bootIdentityProvider.current(),
            responsibleResultId = null,
            logPath = existing?.logPath,
            recoveryState = QuarantineRecoveryState.PENDING,
            recoveryDetail = if (existing == null) {
                "The existing lease record is unavailable or corrupt."
            } else {
                null
            }
        )
        try {
            coordinationStore.persistQuarantine(record)
        } catch (_: IOException) {
            // The pre-existing lease remains the durable guard.
        }
        return record
    }

    private fun localStorageQuarantine(
        session: BuildSession,
        failure: BuildFailure
    ): QuarantineRecord = QuarantineRecord(
        recordId = quarantineRecordId(session.plan.invocation.outputSpaceIdentity, session.id),
        outputSpaceIdentity = session.plan.invocation.outputSpaceIdentity,
        outputPath = session.plan.invocation.outputDirectory,
        projectRoot = session.plan.workingDirectory,
        responsibleSession = session.id,
        cause = failure,
        createdAt = clock.instant(),
        coordinator = null,
        descendants = emptyList(),
        bootIdentity = bootIdentityProvider.current(),
        responsibleResultId = null,
        logPath = session.log.snapshot().path,
        recoveryState = QuarantineRecoveryState.PENDING,
        recoveryDetail = "Coordination storage could not prove output availability."
    )

    private fun removeLeaseBestEffort(sessionId: BuildSessionId) {
        try {
            coordinationStore.removeLease(sessionId)
        } catch (_: IOException) {
            // A retained reservation continues to block cross-process overlap.
        }
    }

    private fun closeLog(log: BuildLog): BuildFailure? {
        return try {
            log.close()
            log.storageFailure
        } catch (error: IOException) {
            log.storageFailure ?: BuildFailure(
                BuildFailureKind.LOG_STORAGE_FAILURE,
                "The build log could not be closed.",
                TechnicalCause.from(error)
            )
        }
    }

    private fun closeLogBestEffort(log: BuildLog) {
        try {
            log.close()
        } catch (_: IOException) {
            // The retained prefix remains inspectable.
        }
    }

    override fun close() {
        val active: List<BuildSession>
        synchronized(lock) {
            if (closed) return
            closed = true
            cancelAll(CancellationOrigin.APPLICATION_SHUTDOWN)
            active = slots.values.mapNotNull(OutputSlot::active)
        }
        active.forEach {
            try {
                it.completion().get(
                    processPolicy.gracefulWait.plus(processPolicy.forcedWait).plusSeconds(2).toMillis(),
                    TimeUnit.MILLISECONDS
                )
            } catch (_: Exception) {
                // The durable lease preserves the output as unsafe across restart.
            }
        }
        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            listeners.clear()
            sessions.clear()
            slots.clear()
        }
    }

    private class OutputSlot(
        var active: BuildSession? = null,
        var queued: BuildSession? = null,
        var quarantine: QuarantineRecord? = null
    )

    private sealed interface LeaseAttempt {
        data class Acquired(val lease: OutputLeaseRecord) : LeaseAttempt
        data object Conflict : LeaseAttempt
        data class StorageFailure(val failure: BuildFailure) : LeaseAttempt
    }

    companion object {
        private fun defaultRuntimeRoot(): Path =
            RuntimeDirectoryResolver.resolve(System.getProperty("user.home"))
    }
}
