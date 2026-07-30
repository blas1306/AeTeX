package dev.aetex.compilation

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BuildStateTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `declares every normative transition`() {
        assertEquals(setOf(BuildState.RUNNING, BuildState.CANCELLED), BuildSession.allowedTransitionsFrom(BuildState.QUEUED))
        assertEquals(
            setOf(BuildState.SUCCEEDED, BuildState.FAILED, BuildState.CANCELLING),
            BuildSession.allowedTransitionsFrom(BuildState.RUNNING)
        )
        assertEquals(
            setOf(BuildState.CANCELLED, BuildState.FAILED),
            BuildSession.allowedTransitionsFrom(BuildState.CANCELLING)
        )
    }

    @Test
    fun `rejects every transition out of terminal states`() {
        BuildState.entries.filter(BuildState::isTerminal).forEach { terminal ->
            assertTrue(BuildSession.allowedTransitionsFrom(terminal).isEmpty())
        }
    }

    @Test
    fun `rejects a prohibited transition explicitly`() {
        val session = session()
        assertFailsWith<InvalidBuildTransitionException> {
            session.transition(BuildState.SUCCEEDED)
        }
    }

    @Test
    fun `cancellation changes running through cancelling`() {
        val session = session()
        session.transition(BuildState.RUNNING)
        assertTrue(session.requestCancellation(CancellationOrigin.USER))
        assertEquals(BuildState.CANCELLING, session.snapshot().state)
        assertFalse(session.requestCancellation(CancellationOrigin.USER))
    }

    @Test
    fun `idle is not a build session state`() {
        assertFalse(BuildState.entries.any { it.name == "IDLE" })
        assertEquals("Idle", OutputActivity.Idle.toString())
    }

    private fun session(): BuildSession {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val clock = object : BuildClock {
            override fun instant(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        }
        return BuildSession(
            BuildSessionId("session"),
            plan,
            clock.instant(),
            clock,
            FileBuildLogFactory(temporaryDirectory.resolve("logs")).create(
                BuildSessionId("session"),
                clock.instant()
            )
        )
    }
}
