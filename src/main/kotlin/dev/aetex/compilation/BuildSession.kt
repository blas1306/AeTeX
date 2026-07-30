package dev.aetex.compilation

import java.time.Instant
import java.util.concurrent.CompletableFuture

class InvalidBuildTransitionException(
    val from: BuildState,
    val to: BuildState
) : IllegalStateException("Build session transition $from -> $to is not permitted.")

class BuildSession internal constructor(
    val id: BuildSessionId,
    val plan: BuildPlan,
    val createdAt: Instant,
    private val clock: BuildClock,
    internal val log: BuildLog
) : CancellationSignal {
    private var state: BuildState = BuildState.QUEUED
    private val queuedAt: Instant = createdAt
    private var startedAt: Instant? = null
    private var cancellation: BuildCancellation? = null
    private var finishedAt: Instant? = null
    private var result: BuildResult? = null
    private var terminalPublicationStarted = false
    private val completion = CompletableFuture<BuildResult>()

    @Synchronized
    fun snapshot(): BuildSessionSnapshot = BuildSessionSnapshot(
        id = id,
        plan = plan,
        state = state,
        createdAt = createdAt,
        queuedAt = queuedAt,
        startedAt = startedAt,
        cancellation = cancellation,
        finishedAt = finishedAt,
        result = result
    )

    @Synchronized
    internal fun transition(to: BuildState) {
        if (to !in allowedTransitions.getValue(state)) {
            throw InvalidBuildTransitionException(state, to)
        }
        state = to
        when (to) {
            BuildState.RUNNING -> startedAt = clock.instant()
            BuildState.SUCCEEDED,
            BuildState.FAILED,
            BuildState.CANCELLED -> finishedAt = clock.instant()
            else -> Unit
        }
    }

    @Synchronized
    internal fun requestCancellation(origin: CancellationOrigin): Boolean {
        if (state.isTerminal || terminalPublicationStarted || cancellation != null) return false
        val requested = BuildCancellation(origin = origin, requestedAt = clock.instant())
        cancellation = requested
        if (state == BuildState.RUNNING) {
            transition(BuildState.CANCELLING)
        }
        return true
    }

    @Synchronized
    internal fun updateCancellation(value: BuildCancellation?) {
        if (value != null) cancellation = value
    }

    @Synchronized
    internal fun beginTerminalPublication(): BuildCancellation? {
        terminalPublicationStarted = true
        return cancellation
    }

    @Synchronized
    override fun current(): BuildCancellation? = cancellation

    @Synchronized
    internal fun complete(buildResult: BuildResult) {
        check(result == null) { "A build session can publish only one result." }
        check(buildResult.sessionId == id)
        check(buildResult.plan === plan) { "A session result must reference its exact plan." }
        if (state != buildResult.state) {
            transition(buildResult.state)
        }
        result = buildResult
        finishedAt = buildResult.finishedAt
        completion.complete(buildResult)
    }

    fun completion(): CompletableFuture<BuildResult> = completion

    companion object {
        private val allowedTransitions = mapOf(
            BuildState.QUEUED to setOf(BuildState.RUNNING, BuildState.CANCELLED),
            BuildState.RUNNING to setOf(
                BuildState.SUCCEEDED,
                BuildState.FAILED,
                BuildState.CANCELLING
            ),
            BuildState.CANCELLING to setOf(BuildState.CANCELLED, BuildState.FAILED),
            BuildState.SUCCEEDED to emptySet(),
            BuildState.FAILED to emptySet(),
            BuildState.CANCELLED to emptySet()
        )

        fun allowedTransitionsFrom(state: BuildState): Set<BuildState> =
            allowedTransitions.getValue(state)
    }
}
