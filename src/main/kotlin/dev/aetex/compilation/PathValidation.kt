package dev.aetex.compilation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.Locale

data class PlannedPaths(
    val projectRoot: Path,
    val mainDocument: Path,
    val outputDirectory: Path,
    val outputIdentity: OutputSpaceIdentity
)

data class ArtifactBeforeBuild(
    val exists: Boolean,
    val fileKey: String?,
    val size: Long?,
    val lastModified: Instant?
)

data class PreparedOutput(
    val finalOutputIdentity: Path,
    val artifactsBeforeBuild: Map<Path, ArtifactBeforeBuild>
)

sealed interface PathValidationResult<out T> {
    data class Valid<T>(val value: T) : PathValidationResult<T>
    data class Invalid(val failure: BuildFailure) : PathValidationResult<Nothing>
}

class CompilationPathValidator(
    private val platform: HostPlatform = HostPlatform.current()
) {
    fun validateForPlanning(
        projectRoot: Path,
        mainDocument: Path,
        outputDirectory: Path
    ): PathValidationResult<PlannedPaths> {
        val root = tryRealDirectory(projectRoot, "The project root is not a readable directory.")
            ?: return invalidPath(projectRoot, "The project root is not a readable directory.")
        if (Files.isSymbolicLink(root)) {
            return invalidPath(root, "The resolved project root may not be a symbolic link.")
        }

        val normalizedMain = mainDocument.toAbsolutePath().normalize()
        if (!normalizedMain.startsWith(root) || Files.isSymbolicLink(normalizedMain)) {
            return invalidPath(normalizedMain, "The main document is unsafe or outside the project.")
        }
        val realMain = try {
            if (
                !Files.isRegularFile(normalizedMain, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isReadable(normalizedMain)
            ) {
                return invalidPath(normalizedMain, "The main document is not a readable regular file.")
            }
            normalizedMain.toRealPath()
        } catch (error: IOException) {
            return invalidPath(normalizedMain, "The main document could not be resolved.", error)
        } catch (error: SecurityException) {
            return invalidPath(normalizedMain, "Access to the main document was denied.", error)
        }
        if (!realMain.startsWith(root)) {
            return invalidPath(realMain, "The main document resolves outside the project.")
        }

        val normalizedOutput = outputDirectory.toAbsolutePath().normalize()
        if (
            normalizedOutput == root ||
            !normalizedOutput.startsWith(root) ||
            normalizedMain.startsWith(normalizedOutput) ||
            root.resolve(".aetex").startsWith(normalizedOutput)
        ) {
            return PathValidationResult.Invalid(
                BuildFailure(
                    kind = BuildFailureKind.INVALID_OUTPUT,
                    message = "The output directory is not a confined generated subtree.",
                    relatedPath = normalizedOutput
                )
            )
        }

        val identity = outputIdentity(root, normalizedOutput)
            ?: return PathValidationResult.Invalid(
                BuildFailure(
                    kind = BuildFailureKind.INVALID_OUTPUT,
                    message = "The output directory has an unsafe type, symlink, or identity.",
                    relatedPath = normalizedOutput
                )
            )
        return PathValidationResult.Valid(
            PlannedPaths(root, realMain, normalizedOutput, identity)
        )
    }

    fun prepareForExecution(plan: BuildPlan): PathValidationResult<PreparedOutput> {
        val planned = validateForPlanning(
            plan.workingDirectory,
            plan.invocation.mainDocument,
            plan.invocation.outputDirectory
        )
        if (planned is PathValidationResult.Invalid) {
            return PathValidationResult.Invalid(
                planned.failure.copy(
                    kind = BuildFailureKind.UNSAFE_PATH_CHANGE,
                    message = "A planned path changed before process start: ${planned.failure.message}"
                )
            )
        }
        planned as PathValidationResult.Valid
        val currentIdentity = planned.value.outputIdentity
        val expectedIdentity = plan.invocation.outputSpaceIdentity
        if (
            currentIdentity.nearestExistingAncestor != expectedIdentity.nearestExistingAncestor ||
            currentIdentity.nearestExistingAncestorFileKey !=
                expectedIdentity.nearestExistingAncestorFileKey ||
            (
                expectedIdentity.existingOutputIdentity != null &&
                    (
                        currentIdentity.existingOutputIdentity !=
                            expectedIdentity.existingOutputIdentity ||
                            currentIdentity.existingOutputFileKey !=
                            expectedIdentity.existingOutputFileKey
                        )
                )
        ) {
            return PathValidationResult.Invalid(
                BuildFailure(
                    kind = BuildFailureKind.UNSAFE_PATH_CHANGE,
                    message = "The output-space identity changed before process start.",
                    relatedPath = planned.value.outputDirectory
                )
            )
        }

        val created = createOutputSegments(
            root = planned.value.projectRoot,
            output = planned.value.outputDirectory,
            plannedAncestor = expectedIdentity.nearestExistingAncestor
        )
        if (created is PathValidationResult.Invalid) {
            return created
        }
        val finalIdentity = try {
            planned.value.outputDirectory.toRealPath()
        } catch (error: IOException) {
            return invalidOutput(planned.value.outputDirectory, "The output directory could not be resolved.", error)
        } catch (error: SecurityException) {
            return invalidOutput(planned.value.outputDirectory, "Access to the output directory was denied.", error)
        }
        if (!finalIdentity.startsWith(planned.value.projectRoot)) {
            return invalidOutput(finalIdentity, "The output directory resolves outside the project.")
        }

        val before = LinkedHashMap<Path, ArtifactBeforeBuild>()
        plan.expectedFiles.forEach { artifact ->
            before[artifact.path] = snapshotArtifact(artifact.path)
        }
        return PathValidationResult.Valid(PreparedOutput(finalIdentity, before))
    }

    fun validateExecutable(tool: ResolvedTool): PathValidationResult<Unit> {
        val path = tool.executable.toAbsolutePath().normalize()
        val real = try {
            if (
                Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isReadable(path)
            ) {
                return invalidTool(path, "A planned executable is no longer a readable regular file.")
            }
            path.toRealPath()
        } catch (error: IOException) {
            return invalidTool(path, "A planned executable could not be resolved.", error)
        } catch (error: SecurityException) {
            return invalidTool(path, "Access to a planned executable was denied.", error)
        }
        if (real != path) {
            return invalidTool(path, "A planned executable changed filesystem identity.")
        }
        if (!platform.isWindows && !Files.isExecutable(real)) {
            return invalidTool(real, "A planned executable lost executable permission.")
        }
        val currentFileKey = fileKey(real)
            ?: return invalidTool(real, "A planned executable identity could not be read.")
        if (tool.fileKey == null || currentFileKey != tool.fileKey) {
            return invalidTool(real, "A planned executable was replaced after discovery.")
        }
        return PathValidationResult.Valid(Unit)
    }

    fun validateRecovery(record: QuarantineRecord): PathValidationResult<OutputSpaceIdentity> {
        val root = tryRealDirectory(record.projectRoot, "The quarantined project root is invalid.")
            ?: return invalidPath(record.projectRoot, "The quarantined project root is invalid.")
        val identity = outputIdentity(root, record.outputPath)
            ?: return invalidOutput(record.outputPath, "The quarantined output path is unsafe.")
        return PathValidationResult.Valid(identity)
    }

    private fun createOutputSegments(
        root: Path,
        output: Path,
        plannedAncestor: Path
    ): PathValidationResult<Unit> {
        val ancestor = nearestExisting(output) ?: return invalidOutput(
            output,
            "No existing ancestor of the output directory can be resolved."
        )
        val realAncestor = try {
            ancestor.toRealPath()
        } catch (error: IOException) {
            return invalidOutput(ancestor, "The output ancestor could not be resolved.", error)
        }
        if (realAncestor != plannedAncestor || !realAncestor.startsWith(root)) {
            return invalidOutput(ancestor, "The output ancestor identity changed.")
        }

        var current = ancestor
        for (segment in ancestor.relativize(output)) {
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    return invalidOutput(current, "An output path segment is a symlink or not a directory.")
                }
            } else {
                try {
                    Files.createDirectory(current)
                } catch (error: IOException) {
                    return invalidOutput(current, "An output directory segment could not be created.", error)
                } catch (error: SecurityException) {
                    return invalidOutput(current, "Creation of an output directory was denied.", error)
                }
            }
            val real = try {
                current.toRealPath()
            } catch (error: IOException) {
                return invalidOutput(current, "A created output directory could not be resolved.", error)
            }
            if (!real.startsWith(root)) {
                return invalidOutput(real, "A created output directory escaped the project.")
            }
        }
        return PathValidationResult.Valid(Unit)
    }

    private fun outputIdentity(root: Path, output: Path): OutputSpaceIdentity? {
        val existing = nearestExisting(output) ?: return null
        if (hasSymlinkBetween(root, existing)) {
            return null
        }
        val realAncestor = try {
            if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) return null
            existing.toRealPath()
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        if (!realAncestor.startsWith(root)) return null

        val outputExists = Files.exists(output, LinkOption.NOFOLLOW_LINKS)
        val ancestorFileKey = fileKey(realAncestor) ?: return null
        val realOutput = if (outputExists) {
            if (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                return null
            }
            try {
                output.toRealPath().takeIf { it.startsWith(root) } ?: return null
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
        } else {
            null
        }
        val outputFileKey = realOutput?.let(::fileKey)
        if (realOutput != null && outputFileKey == null) return null
        val remainder = if (existing == output) null else existing.relativize(output)
        val identityPath = realOutput ?: remainder?.let(realAncestor::resolve) ?: realAncestor
        val key = normalizedComparisonKey(identityPath)
        return OutputSpaceIdentity(
            normalizedOutputPath = output,
            nearestExistingAncestor = realAncestor,
            unresolvedRemainder = remainder,
            existingOutputIdentity = realOutput,
            comparisonKey = key,
            nearestExistingAncestorFileKey = ancestorFileKey,
            existingOutputFileKey = outputFileKey
        )
    }

    private fun nearestExisting(path: Path): Path? {
        var current: Path? = path
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.parent
        }
        return current
    }

    private fun hasSymlinkBetween(root: Path, path: Path): Boolean {
        if (!path.startsWith(root)) return true
        var current = root
        for (segment in root.relativize(path)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun tryRealDirectory(path: Path, @Suppress("UNUSED_PARAMETER") message: String): Path? {
        val normalized = path.toAbsolutePath().normalize()
        return try {
            if (
                !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isReadable(normalized)
            ) {
                null
            } else {
                normalized.toRealPath()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun snapshotArtifact(path: Path): ArtifactBeforeBuild {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ArtifactBeforeBuild(false, null, null, null)
        }
        return try {
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
            ArtifactBeforeBuild(
                exists = true,
                fileKey = attributes.fileKey()?.toString(),
                size = attributes.size(),
                lastModified = attributes.lastModifiedTime().toInstant()
            )
        } catch (_: IOException) {
            ArtifactBeforeBuild(true, null, null, null)
        } catch (_: SecurityException) {
            ArtifactBeforeBuild(true, null, null, null)
        }
    }

    private fun fileKey(path: Path): String? = try {
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

    private fun normalizedComparisonKey(path: Path): String {
        val value = path.toAbsolutePath().normalize().toString()
        return if (platform == HostPlatform.WINDOWS) value.lowercase(Locale.ROOT) else value
    }

    private fun invalidPath(path: Path, message: String, error: Throwable? = null) =
        PathValidationResult.Invalid(
            BuildFailure(
                kind = BuildFailureKind.PLANNING_FAILURE,
                message = message,
                technicalCause = error?.let(TechnicalCause::from),
                relatedPath = path
            )
        )

    private fun invalidOutput(path: Path, message: String, error: Throwable? = null) =
        PathValidationResult.Invalid(
            BuildFailure(
                kind = BuildFailureKind.INVALID_OUTPUT,
                message = message,
                technicalCause = error?.let(TechnicalCause::from),
                relatedPath = path
            )
        )

    private fun invalidTool(path: Path, message: String, error: Throwable? = null) =
        PathValidationResult.Invalid(
            BuildFailure(
                kind = BuildFailureKind.TOOL_INVALID,
                message = message,
                technicalCause = error?.let(TechnicalCause::from),
                relatedPath = path
            )
        )
}
