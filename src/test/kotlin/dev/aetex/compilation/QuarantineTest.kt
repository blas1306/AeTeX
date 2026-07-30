package dev.aetex.compilation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class QuarantineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `persists and loads complete quarantine outside project`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val storeRoot = temporaryDirectory.resolve("application-state")
        val store = FileCoordinationStore(storeRoot)
        val record = record(plan, ProcessIdentity(999_999, Instant.now()))

        store.persistQuarantine(record)
        val loaded = FileCoordinationStore(storeRoot).loadQuarantines().single()

        assertEquals(record.recordId, loaded.recordId)
        assertEquals(record.outputSpaceIdentity, loaded.outputSpaceIdentity)
        assertEquals(record.responsibleSession, loaded.responsibleSession)
        assertEquals(record.cause.kind, loaded.cause.kind)
        assertTrue(!storeRoot.startsWith(plan.workingDirectory))
    }

    @Test
    fun `stale durable lease becomes quarantine after simulated restart`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val store = FileCoordinationStore(temporaryDirectory.resolve("coordination"))
        store.persistLease(
            OutputLeaseRecord(
                identity = plan.invocation.outputSpaceIdentity,
                projectRoot = plan.workingDirectory,
                sessionId = BuildSessionId("abnormal"),
                createdAt = Instant.now(),
                bootIdentity = "boot",
                phase = OutputLeasePhase.STARTING,
                logPath = temporaryDirectory.resolve("log")
            )
        )

        CompilationManager(
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("logs")),
            coordinationStore = store,
            bootIdentityProvider = BootIdentityProvider { "boot" }
        ).use { manager ->
            val quarantine = manager.inspectQuarantine().single()
            assertEquals(BuildFailureKind.ABNORMAL_APPLICATION_TERMINATION, quarantine.cause.kind)
            assertEquals(plan.invocation.outputSpaceIdentity.comparisonKey, quarantine.outputSpaceIdentity.comparisonKey)
        }
        assertEquals(1, store.loadLeases().size)
        assertEquals(1, store.loadQuarantines().size)
    }

    @Test
    fun `recovery succeeds only after process and path proof`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        Files.createDirectories(plan.invocation.outputDirectory)
        val store = FileCoordinationStore(temporaryDirectory.resolve("coordination"))
        val record = record(plan, ProcessIdentity(Long.MAX_VALUE - 10, Instant.now()))
        store.persistQuarantine(record)
        val recovery = QuarantineRecovery(
            store,
            CompilationPathValidator(),
            BootIdentityProvider { "same-boot" }
        )

        val result = recovery.recheck(record.recordId)

        assertIs<RecoveryResult.Recovered>(result)
        assertTrue(store.loadQuarantines().isEmpty())
    }

    @Test
    fun `recovery remains quarantined when identity is ambiguous`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        Files.createDirectories(plan.invocation.outputDirectory)
        val store = FileCoordinationStore(temporaryDirectory.resolve("coordination"))
        val record = record(plan, coordinator = null)
        store.persistQuarantine(record)
        val recovery = QuarantineRecovery(
            store,
            CompilationPathValidator(),
            BootIdentityProvider { "same-boot" }
        )

        val result = assertIs<RecoveryResult.StillQuarantined>(
            recovery.recheck(record.recordId)
        )

        assertEquals(QuarantineRecoveryState.REJECTED, result.record.recoveryState)
        assertTrue(store.loadQuarantines().isNotEmpty())
    }

    @Test
    fun `changed boot proves old processes gone but still revalidates paths`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val store = FileCoordinationStore(temporaryDirectory.resolve("coordination"))
        val record = record(plan, coordinator = null).copy(bootIdentity = "old-boot")
        store.persistQuarantine(record)
        val recovery = QuarantineRecovery(
            store,
            CompilationPathValidator(),
            BootIdentityProvider { "new-boot" }
        )

        assertIs<RecoveryResult.Recovered>(recovery.recheck(record.recordId))
    }

    @Test
    fun `normalized aliases use one global output identity`() {
        val root = temporaryDirectory.resolve("project")
        val first = createPlan(root, root.resolve("generated"))
        val second = createPlan(root, root.resolve("child").resolve("..").resolve("generated"))

        assertEquals(
            first.invocation.outputSpaceIdentity.comparisonKey,
            second.invocation.outputSpaceIdentity.comparisonKey
        )
    }

    @Test
    fun `filesystem lease acquisition is atomic across store instances`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val root = temporaryDirectory.resolve("coordination")
        val firstStore = FileCoordinationStore(root)
        val secondStore = FileCoordinationStore(root)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val attempts = listOf(
            BuildSessionId("first") to firstStore,
            BuildSessionId("second") to secondStore
        ).map { (sessionId, store) ->
            pool.submit<Boolean> {
                start.await()
                try {
                    store.persistLease(
                        OutputLeaseRecord(
                            plan.invocation.outputSpaceIdentity,
                            plan.workingDirectory,
                            sessionId,
                            Instant.now(),
                            "boot"
                        )
                    )
                    true
                } catch (_: OutputLeaseConflictException) {
                    false
                }
            }
        }
        start.countDown()

        try {
            assertEquals(1, attempts.count { it.get() })
            val winner = firstStore.loadLeases().single()
            firstStore.removeLease(winner.sessionId)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `recovery distinguishes pid reuse and preserves quarantine on uncertainty`() {
        val statuses = listOf(
            ProcessIdentityStatus.SAME_PROCESS to false,
            ProcessIdentityStatus.DIFFERENT_PROCESS to true,
            ProcessIdentityStatus.ABSENT to true,
            ProcessIdentityStatus.UNVERIFIABLE to false
        )
        statuses.forEachIndexed { index, (status, shouldRecover) ->
            val plan = createPlan(temporaryDirectory.resolve("project-$index"))
            Files.createDirectories(plan.invocation.outputDirectory)
            val store = FileCoordinationStore(temporaryDirectory.resolve("coord-$index"))
            val current = record(
                plan,
                ProcessIdentity(900_000L + index, Instant.parse("2026-01-01T00:00:00Z"))
            )
            store.persistLease(
                OutputLeaseRecord(
                    plan.invocation.outputSpaceIdentity,
                    plan.workingDirectory,
                    current.responsibleSession,
                    Instant.now(),
                    "same-boot",
                    OutputLeasePhase.STARTED,
                    current.coordinator
                )
            )
            store.persistQuarantine(current)
            val recovery = QuarantineRecovery(
                store,
                CompilationPathValidator(),
                BootIdentityProvider { "same-boot" },
                ProcessIdentityInspector { status }
            )

            val result = recovery.recheck(current.recordId)

            assertEquals(shouldRecover, result is RecoveryResult.Recovered, "status=$status")
            if (!shouldRecover) {
                assertIs<RecoveryResult.StillQuarantined>(result)
            }
        }
    }

    @Test
    fun `missing start token remains quarantined even when pid is absent`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        Files.createDirectories(plan.invocation.outputDirectory)
        val store = FileCoordinationStore(temporaryDirectory.resolve("coordination"))
        val current = record(plan, ProcessIdentity(Long.MAX_VALUE - 20, null))
        store.persistQuarantine(current)
        val recovery = QuarantineRecovery(
            store,
            CompilationPathValidator(),
            BootIdentityProvider { "same-boot" }
        )

        assertIs<RecoveryResult.StillQuarantined>(recovery.recheck(current.recordId))
    }

    @Test
    fun `corrupt global lease blocks a new manager instead of disappearing`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val root = temporaryDirectory.resolve("coordination")
        val store = FileCoordinationStore(root)
        store.persistLease(
            OutputLeaseRecord(
                plan.invocation.outputSpaceIdentity,
                plan.workingDirectory,
                BuildSessionId("corrupt"),
                Instant.now(),
                "boot",
                OutputLeasePhase.STARTING
            )
        )
        val leaseFile = Files.list(root.resolve("leases")).use { it.findFirst().orElseThrow() }
        Files.writeString(leaseFile, "truncated")

        CompilationManager(
            logFactory = FileBuildLogFactory(temporaryDirectory.resolve("logs")),
            coordinationStore = store,
            bootIdentityProvider = BootIdentityProvider { "boot" }
        ).use { manager ->
            assertIs<BuildRequestResult.Rejected>(manager.requestBuild(plan))
        }
        val record = store.loadQuarantines().single()
        assertIs<RecoveryResult.Recovered>(
            QuarantineRecovery(
                store,
                CompilationPathValidator(),
                BootIdentityProvider { "new-boot" }
            ).recheck(record.recordId)
        )
        assertTrue(Files.notExists(leaseFile))
    }

    private fun record(plan: BuildPlan, coordinator: ProcessIdentity?) = QuarantineRecord(
        recordId = "record-${System.nanoTime()}",
        outputSpaceIdentity = plan.invocation.outputSpaceIdentity,
        outputPath = plan.invocation.outputDirectory,
        projectRoot = plan.workingDirectory,
        responsibleSession = BuildSessionId("session"),
        cause = BuildFailure(
            BuildFailureKind.POSSIBLY_ORPHANED_PROCESS,
            "uncertain cleanup"
        ),
        createdAt = Instant.now(),
        coordinator = coordinator,
        descendants = emptyList(),
        bootIdentity = "same-boot",
        responsibleResultId = "session",
        logPath = temporaryDirectory.resolve("log"),
        recoveryState = QuarantineRecoveryState.PENDING
    )
}
