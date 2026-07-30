package dev.aetex.compilation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class ToolDiscoveryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val discovery = PathToolDiscoverer(
        ExecutablePermissionChecker { _, _ -> true },
        PathEnvironmentSplitter { value, separator ->
            if (separator == ':' && HostPlatform.current().isWindows) {
                value.split('|')
            } else {
                PathToolDiscoverer.splitPathValue(value, separator)
            }
        }
    )

    @Test
    fun `finds first exact tool and retains rejected evidence`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val missing = Files.createDirectory(temporaryDirectory.resolve("missing"))
        val valid = toolDirectory("valid", HostPlatform.WINDOWS)
        val result = assertIs<ToolDiscoveryResult.Found>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("Path" to "$missing;$valid"),
                project,
                HostPlatform.WINDOWS
            )
        )

        assertEquals(valid.resolve("latexmk.exe").toRealPath(), result.tool.executable)
        assertEquals(1, result.tool.pathEntryIndex)
        assertTrue(result.tool.rejectedCandidates.any {
            it.reason == ToolCandidateRejection.MISSING_CANDIDATE
        })
    }

    @Test
    fun `uses platform separator and preserves path order`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val first = toolDirectory("first", HostPlatform.LINUX)
        val second = toolDirectory("second", HostPlatform.LINUX)
        val result = assertIs<ToolDiscoveryResult.Found>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf(
                    "PATH" to if (HostPlatform.current().isWindows) {
                        "$first|$second"
                    } else {
                        "$first:$second"
                    }
                ),
                project,
                HostPlatform.LINUX
            )
        )

        assertEquals(first.resolve("latexmk").toRealPath(), result.tool.executable)
    }

    @Test
    fun `path splitting preserves empty entries for each normative separator`() {
        assertEquals(listOf("a", "", "b"), PathToolDiscoverer.splitPathValue("a::b", ':'))
        assertEquals(listOf("a", "", "b"), PathToolDiscoverer.splitPathValue("a;;b", ';'))
    }

    @Test
    fun `rejects empty relative duplicate file and project path entries`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val duplicate = Files.createDirectory(temporaryDirectory.resolve("tools"))
        val ordinaryFile = Files.writeString(temporaryDirectory.resolve("ordinary"), "x")
        val value = listOf("", ".", duplicate, duplicate, ordinaryFile, project).joinToString(";")
        val result = assertIs<ToolDiscoveryResult.Unavailable>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to value),
                project,
                HostPlatform.WINDOWS
            )
        )

        val reasons = result.rejectedCandidates.map(RejectedToolCandidate::reason).toSet()
        assertTrue(ToolCandidateRejection.EMPTY_ENTRY in reasons)
        assertTrue(ToolCandidateRejection.RELATIVE_ENTRY in reasons)
        assertTrue(ToolCandidateRejection.DUPLICATE_DIRECTORY in reasons)
        assertTrue(ToolCandidateRejection.NON_DIRECTORY_ENTRY in reasons)
        assertTrue(ToolCandidateRejection.PROJECT_CONTAINED_DIRECTORY in reasons)
    }

    @Test
    fun `windows accepts only exact exe and never bat cmd com or extensionless`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val tools = Files.createDirectory(temporaryDirectory.resolve("tools"))
        listOf("latexmk", "latexmk.bat", "latexmk.cmd", "latexmk.com").forEach {
            Files.writeString(tools.resolve(it), "x")
        }

        assertIs<ToolDiscoveryResult.Unavailable>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to tools.toString()),
                project,
                HostPlatform.WINDOWS
            )
        )
    }

    @Test
    fun `rejects a directory with the executable name`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val tools = Files.createDirectory(temporaryDirectory.resolve("tools"))
        Files.createDirectory(tools.resolve("latexmk.exe"))

        val result = assertIs<ToolDiscoveryResult.Unavailable>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to tools.toString()),
                project,
                HostPlatform.WINDOWS
            )
        )
        assertTrue(result.rejectedCandidates.any {
            it.reason == ToolCandidateRejection.NON_REGULAR_FILE
        })
    }

    @Test
    fun `missing path never falls back to another tool kind`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val tools = toolDirectory("tools", HostPlatform.WINDOWS, "pdflatex")

        val result = assertIs<ToolDiscoveryResult.Unavailable>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to tools.toString()),
                project,
                HostPlatform.WINDOWS
            )
        )
        assertEquals(ToolKind.LATEXMK, result.kind)
    }

    @Test
    fun `accepts an external symlink whose complete target is valid`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val realTools = toolDirectory("real", HostPlatform.LINUX)
        val linked = temporaryDirectory.resolve("linked")
        try {
            Files.createSymbolicLink(linked, realTools)
        } catch (_: Exception) {
            return
        }

        val result = assertIs<ToolDiscoveryResult.Found>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to linked.toString()),
                project,
                HostPlatform.LINUX
            )
        )
        assertEquals(realTools.resolve("latexmk").toRealPath(), result.tool.executable)
    }

    @Test
    fun `paths containing spaces remain a single path entry`() {
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val tools = toolDirectory("tools with spaces", HostPlatform.WINDOWS)

        val result = assertIs<ToolDiscoveryResult.Found>(
            discovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to tools.toString()),
                project,
                HostPlatform.WINDOWS
            )
        )
        assertEquals(tools.resolve("latexmk.exe").toRealPath(), result.tool.executable)
    }

    @Test
    fun `windows discovery never consults host executable permission`() {
        var permissionChecks = 0
        val windowsDiscovery = PathToolDiscoverer(
            ExecutablePermissionChecker { _, _ ->
                permissionChecks += 1
                false
            }
        )
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val tools = toolDirectory("tools", HostPlatform.WINDOWS)

        assertIs<ToolDiscoveryResult.Found>(
            windowsDiscovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to tools.toString()),
                project,
                HostPlatform.WINDOWS
            )
        )
        assertEquals(0, permissionChecks)
    }

    @Test
    fun `linux and macos discovery use the configured permission provider`() {
        val checkedPlatforms = mutableListOf<HostPlatform>()
        val unixDiscovery = PathToolDiscoverer(
            ExecutablePermissionChecker { _, platform ->
                checkedPlatforms += platform
                false
            },
            PathEnvironmentSplitter { value, _ -> listOf(value) }
        )
        val project = Files.createDirectory(temporaryDirectory.resolve("project"))
        val linuxTools = toolDirectory("linux-tools", HostPlatform.LINUX)
        val macTools = toolDirectory("mac-tools", HostPlatform.MACOS)

        assertIs<ToolDiscoveryResult.Unavailable>(
            unixDiscovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to linuxTools.toString()),
                project,
                HostPlatform.LINUX
            )
        )
        assertIs<ToolDiscoveryResult.Unavailable>(
            unixDiscovery.discover(
                ToolKind.LATEXMK,
                mapOf("PATH" to macTools.toString()),
                project,
                HostPlatform.MACOS
            )
        )
        assertEquals(listOf(HostPlatform.LINUX, HostPlatform.MACOS), checkedPlatforms)
        assertFalse(checkedPlatforms.any(HostPlatform::isWindows))
    }

    private fun toolDirectory(
        name: String,
        platform: HostPlatform,
        tool: String = "latexmk"
    ): Path {
        val directory = Files.createDirectory(temporaryDirectory.resolve(name))
        val fileName = if (platform.isWindows) "$tool.exe" else tool
        Files.writeString(directory.resolve(fileName), "x").toFile().setExecutable(true)
        return directory
    }
}
