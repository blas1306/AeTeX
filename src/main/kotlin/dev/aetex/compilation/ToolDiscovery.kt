package dev.aetex.compilation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

data class ValidatedPathEnvironment(
    val pathKey: String,
    val directories: List<Path>,
    val rejectedEntries: List<RejectedToolCandidate>
)

sealed interface ToolDiscoveryResult {
    data class Found(
        val tool: ResolvedTool,
        val pathEnvironment: ValidatedPathEnvironment
    ) : ToolDiscoveryResult

    data class Unavailable(
        val kind: ToolKind,
        val rejectedCandidates: List<RejectedToolCandidate>
    ) : ToolDiscoveryResult
}

fun interface ToolDiscoverer {
    fun discover(
        kind: ToolKind,
        environment: Map<String, String>,
        projectRoot: Path,
        platform: HostPlatform
    ): ToolDiscoveryResult
}

fun interface ExecutablePermissionChecker {
    fun isExecutable(path: Path, platform: HostPlatform): Boolean
}

fun interface PathEnvironmentSplitter {
    fun split(value: String, separator: Char): List<String>
}

class PathToolDiscoverer(
    private val executablePermissionChecker: ExecutablePermissionChecker =
        ExecutablePermissionChecker { path, configuredPlatform ->
            configuredPlatform == HostPlatform.current() && Files.isExecutable(path)
        },
    private val pathEnvironmentSplitter: PathEnvironmentSplitter =
        PathEnvironmentSplitter(::splitPathValue)
) : ToolDiscoverer {
    override fun discover(
        kind: ToolKind,
        environment: Map<String, String>,
        projectRoot: Path,
        platform: HostPlatform
    ): ToolDiscoveryResult {
        val pathEntry = environment.entries.firstOrNull {
            if (platform.environmentKeysCaseInsensitive) {
                it.key.equals("PATH", ignoreCase = true)
            } else {
                it.key == "PATH"
            }
        }
        val rawPath = pathEntry?.value.orEmpty()
        val rejections = mutableListOf<RejectedToolCandidate>()
        val directories = mutableListOf<Path>()
        val identities = mutableSetOf<String>()
        val parts = pathEnvironmentSplitter.split(rawPath, platform.pathSeparator)
        parts.forEachIndexed { index, raw ->
            if (raw.isEmpty()) {
                rejections += rejected(index, null, ToolCandidateRejection.EMPTY_ENTRY)
                return@forEachIndexed
            }
            val candidate = try {
                Path.of(raw)
            } catch (error: RuntimeException) {
                rejections += rejected(index, null, ToolCandidateRejection.IO_ERROR, error)
                return@forEachIndexed
            }
            if (!candidate.isAbsolute) {
                rejections += rejected(index, candidate, ToolCandidateRejection.RELATIVE_ENTRY)
                return@forEachIndexed
            }
            val normalized = candidate.normalize()
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                rejections += rejected(index, normalized, ToolCandidateRejection.MISSING_DIRECTORY)
                return@forEachIndexed
            }
            val real = try {
                normalized.toRealPath()
            } catch (error: IOException) {
                rejections += rejected(index, normalized, ToolCandidateRejection.INACCESSIBLE_DIRECTORY, error)
                return@forEachIndexed
            } catch (error: SecurityException) {
                rejections += rejected(index, normalized, ToolCandidateRejection.INACCESSIBLE_DIRECTORY, error)
                return@forEachIndexed
            }
            if (!Files.isDirectory(real)) {
                rejections += rejected(index, real, ToolCandidateRejection.NON_DIRECTORY_ENTRY)
                return@forEachIndexed
            }
            if (real.startsWith(projectRoot)) {
                rejections += rejected(index, real, ToolCandidateRejection.PROJECT_CONTAINED_DIRECTORY)
                return@forEachIndexed
            }
            val key = comparisonKey(real, platform)
            if (!identities.add(key)) {
                rejections += rejected(index, real, ToolCandidateRejection.DUPLICATE_DIRECTORY)
                return@forEachIndexed
            }
            directories.add(real)
        }

        val executableName = if (platform.isWindows) {
            "${kind.executableName}.exe"
        } else {
            kind.executableName
        }
        directories.forEachIndexed { validatedIndex, directory ->
            val originalIndex = parts.indexOfFirst { raw ->
                try {
                    Path.of(raw).isAbsolute &&
                        Path.of(raw).normalize().toRealPath() == directory
                } catch (_: Exception) {
                    false
                }
            }.takeIf { it >= 0 } ?: validatedIndex
            val candidate = directory.resolve(executableName)
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                rejections += rejected(originalIndex, candidate, ToolCandidateRejection.MISSING_CANDIDATE)
                return@forEachIndexed
            }
            val real = try {
                candidate.toRealPath()
            } catch (error: IOException) {
                rejections += rejected(originalIndex, candidate, ToolCandidateRejection.BROKEN_LINK, error)
                return@forEachIndexed
            } catch (error: SecurityException) {
                rejections += rejected(originalIndex, candidate, ToolCandidateRejection.BROKEN_LINK, error)
                return@forEachIndexed
            }
            if (!Files.isRegularFile(real)) {
                rejections += rejected(originalIndex, real, ToolCandidateRejection.NON_REGULAR_FILE)
                return@forEachIndexed
            }
            if (real.startsWith(projectRoot)) {
                rejections += rejected(originalIndex, real, ToolCandidateRejection.PROJECT_CONTAINED_CANDIDATE)
                return@forEachIndexed
            }
            if (!platform.isWindows && !executablePermissionChecker.isExecutable(real, platform)) {
                rejections += rejected(originalIndex, real, ToolCandidateRejection.NOT_EXECUTABLE)
                return@forEachIndexed
            }
            if (
                platform.isWindows &&
                !real.fileName.toString().equals(executableName, ignoreCase = true)
            ) {
                rejections += rejected(originalIndex, real, ToolCandidateRejection.INVALID_WINDOWS_EXTENSION)
                return@forEachIndexed
            }
            val fileKey = readFileKey(real)
            if (fileKey == null) {
                rejections += rejected(
                    originalIndex,
                    real,
                    ToolCandidateRejection.IO_ERROR
                )
                return@forEachIndexed
            }
            return ToolDiscoveryResult.Found(
                tool = ResolvedTool(
                    kind = kind,
                    executable = real,
                    source = ToolDiscoverySource.PATH,
                    pathEntryIndex = originalIndex,
                    rejectedCandidates = rejections.toList(),
                    fileKey = fileKey
                ),
                pathEnvironment = ValidatedPathEnvironment(
                    pathKey = pathEntry?.key ?: "PATH",
                    directories = directories.toList(),
                    rejectedEntries = rejections.toList()
                )
            )
        }
        return ToolDiscoveryResult.Unavailable(kind, rejections.toList())
    }

    private fun comparisonKey(path: Path, platform: HostPlatform): String {
        val value = path.toString()
        return if (platform == HostPlatform.WINDOWS) value.lowercase(Locale.ROOT) else value
    }

    private fun readFileKey(path: Path): String? = try {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        attributes.fileKey()?.toString()
            ?: "created:${attributes.creationTime().toInstant()}:size:${attributes.size()}"
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun rejected(
        index: Int,
        path: Path?,
        reason: ToolCandidateRejection,
        error: Throwable? = null
    ) = RejectedToolCandidate(index, path, reason, error?.message)

    companion object {
        fun splitPathValue(value: String, separator: Char): List<String> {
            if (value.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            var start = 0
            value.forEachIndexed { index, char ->
                if (char == separator) {
                    result += value.substring(start, index)
                    start = index + 1
                }
            }
            result += value.substring(start)
            return result
        }
    }
}
