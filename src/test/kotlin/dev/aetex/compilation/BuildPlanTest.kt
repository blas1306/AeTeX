package dev.aetex.compilation

import dev.aetex.project.configuration.TeXEngine
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class BuildPlanTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `fingerprint is stable for equal resolved inputs`() {
        val root = temporaryDirectory.resolve("project")
        val first = createPlan(root)
        val second = createPlan(root)

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(64, first.fingerprint.length)
    }

    @Test
    fun `fingerprint changes when ordered arguments change`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val changed = BuildPlan.create(
            plan.invocation,
            plan.arguments.reversed(),
            plan.workingDirectory,
            plan.environment,
            plan.expectedFiles
        )

        assertNotEquals(plan.fingerprint, changed.fingerprint)
    }

    @Test
    fun `copies mutable arguments expected files and environment defensively`() {
        val original = createPlan(temporaryDirectory.resolve("project"))
        val arguments = original.arguments.toMutableList()
        val expected = original.expectedFiles.toMutableList()
        val environment = original.environment.values.toMutableMap()
        val copied = BuildPlan.create(
            original.invocation,
            arguments,
            original.workingDirectory,
            BuildEnvironment.copied(environment, Charsets.UTF_8, original.environment.platform),
            expected
        )
        arguments.clear()
        expected.clear()
        environment.clear()

        assertTrue(copied.arguments.isNotEmpty())
        assertTrue(copied.expectedFiles.isNotEmpty())
        assertTrue(copied.environment.values.isNotEmpty())
    }

    @Test
    fun `preserves exact engine strategy tools and provenance`() {
        val plan = createPlan(
            temporaryDirectory.resolve("project"),
            engine = TeXEngine.XE_LATEX
        )

        assertEquals(TeXEngine.XE_LATEX, plan.invocation.engine)
        assertEquals(ToolKind.XELATEX, plan.invocation.engineTool.kind)
        assertEquals(ToolKind.LATEXMK, plan.invocation.coordinator.kind)
        assertEquals("latexmk", plan.invocation.strategy.configurationValue)
        assertEquals("EXPLICIT", plan.invocation.provenance.engine.name)
    }

    @Test
    fun `normalizes paths and defines only the exact main pdf as primary`() {
        val root = temporaryDirectory.resolve("project")
        val plan = createPlan(root)

        assertTrue(plan.workingDirectory.isAbsolute)
        assertEquals(root.resolve("build").resolve("main.pdf").normalize(), plan.primaryPdf)
        assertEquals(1, plan.expectedFiles.count { it.role == ArtifactRole.PRIMARY_PDF })
    }

    @Test
    fun `environment fingerprint order follows deterministic key sorting`() {
        val root = temporaryDirectory.resolve("project")
        val first = createPlan(root) {
            it["Z"] = "last"
            it["A"] = "first"
        }
        val second = createPlan(root) {
            val path = it.getValue("PATH")
            val testValue = it.getValue("AETEX_TEST")
            it.clear()
            it["A"] = "first"
            it["Z"] = "last"
            it["AETEX_TEST"] = testValue
            it["PATH"] = path
        }

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `canonical serialization delimits ordered values without concatenation ambiguity`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))
        val first = BuildPlan.create(
            plan.invocation,
            listOf("ab", "c"),
            plan.workingDirectory,
            plan.environment,
            plan.expectedFiles
        )
        val second = BuildPlan.create(
            plan.invocation,
            listOf("a", "bc"),
            plan.workingDirectory,
            plan.environment,
            plan.expectedFiles
        )

        assertNotEquals(
            first.canonicalSerialization().toList(),
            second.canonicalSerialization().toList()
        )
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `plan string rendering does not expose environment secrets`() {
        val plan = createPlan(temporaryDirectory.resolve("project")) {
            it["AETEX_SECRET"] = "do-not-render"
        }

        assertFalse(plan.toString().contains("do-not-render"))
    }

    @Test
    fun `nested plan collections cannot be mutated through runtime casts`() {
        val plan = createPlan(temporaryDirectory.resolve("project"))

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.invocation.coordinator.rejectedCandidates as MutableList).add(
                RejectedToolCandidate(0, null, ToolCandidateRejection.EMPTY_ENTRY)
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.arguments as MutableList).clear()
        }
    }
}
