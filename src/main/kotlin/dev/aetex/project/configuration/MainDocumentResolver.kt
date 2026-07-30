package dev.aetex.project.configuration

import dev.aetex.project.ProjectDirectory
import dev.aetex.project.ProjectEntry
import dev.aetex.project.ProjectFile
import dev.aetex.project.ProjectScanner
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

data class MainDocumentResolution(
    val state: MainDocumentState,
    val diagnostics: List<ProjectConfigurationDiagnostic>
)

data class EngineResolution(
    val value: EffectiveConfigurationValue<TeXEngine>,
    val diagnostics: List<ProjectConfigurationDiagnostic>
)

class MainDocumentResolver {
    fun resolve(
        root: Path,
        entries: List<ProjectEntry>,
        explicitMain: Path?,
        outputDirectory: Path,
        configurationPath: Path?
    ): MainDocumentResolution {
        if (explicitMain != null) {
            return resolveExplicitMain(root, explicitMain, outputDirectory, configurationPath)
        }

        val diagnostics = mutableListOf<ProjectConfigurationDiagnostic>()
        val texFiles = flattenFiles(entries)
            .filter(::hasTexExtension)
            .sortedWith(pathComparator(root))
        val candidates = texFiles.mapNotNull { path ->
            validateCandidate(
                root = root,
                path = path,
                outputDirectory = outputDirectory,
                configurationPath = configurationPath,
                reportOrdinaryRejection = false
            ).path
        }

        if (candidates.isEmpty()) {
            diagnostics += warning(
                code = ProjectConfigurationDiagnosticCode.MAIN_UNAVAILABLE,
                message = "No valid LaTeX main-document candidate was found.",
                configurationPath = configurationPath
            )
            return MainDocumentResolution(MainDocumentState.Unavailable, diagnostics)
        }

        if (candidates.size == 1) {
            val state = MainDocumentState.Provisional(
                path = candidates.single(),
                reason = MainDocumentSelectionReason.SINGLE_CANDIDATE
            )
            diagnostics += provisionalDiagnostic(state.path, configurationPath)
            return MainDocumentResolution(state, diagnostics)
        }

        resolveRootDirectives(root, texFiles, candidates, configurationPath, diagnostics)?.let { path ->
            val state = MainDocumentState.Provisional(
                path = path,
                reason = MainDocumentSelectionReason.ROOT_DIRECTIVE
            )
            diagnostics += provisionalDiagnostic(path, configurationPath)
            return MainDocumentResolution(state, diagnostics)
        }

        uniqueCandidate(candidates) {
            it.fileName.toString().equals("main.tex", ignoreCase = true)
        }?.let { path ->
            val state = MainDocumentState.Provisional(
                path = path,
                reason = MainDocumentSelectionReason.MAIN_FILE_NAME
            )
            diagnostics += provisionalDiagnostic(path, configurationPath)
            return MainDocumentResolution(state, diagnostics)
        }

        uniqueCandidate(candidates) { it.parent == root }?.let { path ->
            val state = MainDocumentState.Provisional(
                path = path,
                reason = MainDocumentSelectionReason.ROOT_LEVEL
            )
            diagnostics += provisionalDiagnostic(path, configurationPath)
            return MainDocumentResolution(state, diagnostics)
        }

        val projectName = root.fileName?.toString()
        if (projectName != null) {
            uniqueCandidate(candidates) {
                it.fileName.toString()
                    .substringBeforeLast('.', missingDelimiterValue = it.fileName.toString())
                    .equals(projectName, ignoreCase = true)
            }?.let { path ->
                val state = MainDocumentState.Provisional(
                    path = path,
                    reason = MainDocumentSelectionReason.PROJECT_DIRECTORY_NAME
                )
                diagnostics += provisionalDiagnostic(path, configurationPath)
                return MainDocumentResolution(state, diagnostics)
            }
        }

        val sortedCandidates = candidates.sortedWith(pathComparator(root))
        diagnostics += warning(
            code = ProjectConfigurationDiagnosticCode.MAIN_SELECTION_REQUIRED,
            message = "Several LaTeX main-document candidates require user selection.",
            configurationPath = configurationPath
        )
        return MainDocumentResolution(
            state = MainDocumentState.SelectionRequired(sortedCandidates),
            diagnostics = diagnostics
        )
    }

    fun inferEngine(
        confirmedMain: Path,
        configurationPath: Path?
    ): EngineResolution {
        val content = try {
            Files.readString(confirmedMain, StandardCharsets.UTF_8)
        } catch (error: IOException) {
            return EngineResolution(
                value = defaultEngine(),
                diagnostics = listOf(
                    warning(
                        code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                        message = "The main document could not be read for engine inference.",
                        configurationPath = configurationPath,
                        relatedPath = confirmedMain,
                        error = error
                    )
                )
            )
        } catch (error: SecurityException) {
            return EngineResolution(
                value = defaultEngine(),
                diagnostics = listOf(
                    warning(
                        code = ProjectConfigurationDiagnosticCode.CONFIGURATION_IO_ERROR,
                        message = "Access was denied while inferring the TeX engine.",
                        configurationPath = configurationPath,
                        relatedPath = confirmedMain,
                        error = error
                    )
                )
            )
        }

        val directiveValues = buildList {
            for (line in content.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    continue
                }
                if (!trimmed.startsWith("%")) {
                    break
                }
                PROGRAM_DIRECTIVE.matchEntire(line)?.groupValues?.get(1)?.let(::add)
            }
        }
        if (directiveValues.isEmpty()) {
            return EngineResolution(defaultEngine(), emptyList())
        }

        val unsupported = directiveValues.filter {
            TeXEngine.fromConfigurationValue(it.lowercase(Locale.ROOT)) == null
        }
        if (unsupported.isNotEmpty()) {
            return EngineResolution(
                value = defaultEngine(),
                diagnostics = listOf(
                    warning(
                        code = ProjectConfigurationDiagnosticCode.UNSUPPORTED_ENGINE_DIRECTIVE,
                        message = "An unsupported TeX program directive was ignored.",
                        configurationPath = configurationPath,
                        relatedPath = confirmedMain
                    )
                )
            )
        }

        val engines = directiveValues
            .mapNotNull { TeXEngine.fromConfigurationValue(it.lowercase(Locale.ROOT)) }
            .toSet()
        if (engines.size != 1) {
            return EngineResolution(
                value = defaultEngine(),
                diagnostics = listOf(
                    warning(
                        code = ProjectConfigurationDiagnosticCode.CONFLICTING_ENGINE_DIRECTIVES,
                        message = "Conflicting TeX program directives were ignored.",
                        configurationPath = configurationPath,
                        relatedPath = confirmedMain
                    )
                )
            )
        }

        return EngineResolution(
            value = EffectiveConfigurationValue(
                value = engines.single(),
                source = ConfigurationValueSource.INFERRED
            ),
            diagnostics = emptyList()
        )
    }

    private fun resolveExplicitMain(
        root: Path,
        explicitMain: Path,
        outputDirectory: Path,
        configurationPath: Path?
    ): MainDocumentResolution {
        val validation = validateCandidate(
            root = root,
            path = root.resolve(explicitMain).normalize(),
            outputDirectory = outputDirectory,
            configurationPath = configurationPath,
            reportOrdinaryRejection = true
        )
        validation.path?.let { path ->
            return MainDocumentResolution(
                state = MainDocumentState.Confirmed(path),
                diagnostics = emptyList()
            )
        }

        val diagnostic = checkNotNull(validation.diagnostic)
        return MainDocumentResolution(
            state = MainDocumentState.InvalidExplicitMain(
                configuredPath = explicitMain,
                diagnostic = diagnostic
            ),
            diagnostics = listOf(diagnostic)
        )
    }

    private fun validateCandidate(
        root: Path,
        path: Path,
        outputDirectory: Path,
        configurationPath: Path?,
        reportOrdinaryRejection: Boolean
    ): CandidateValidation {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_OUTSIDE_PROJECT,
                "The configured main document escapes the project root.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (Files.isSymbolicLink(normalized)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_SYMBOLIC_LINK,
                "The main document cannot be a symbolic link.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_FOUND,
                "The configured main document does not exist.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_REGULAR_FILE,
                "The configured main document is not a regular file.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (!Files.isReadable(normalized)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_READABLE,
                "The configured main document is not readable.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (!hasTexExtension(normalized)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_TEX,
                "The configured main document must have a .tex extension.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (normalized.startsWith(outputDirectory)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_IN_OUTPUT_DIRECTORY,
                "The configured main document is inside the output directory.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }
        if (isUnderScannerExclusion(root, normalized)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_IN_EXCLUDED_DIRECTORY,
                "The configured main document is inside a scanner-excluded directory.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }

        val realPath = try {
            normalized.toRealPath()
        } catch (error: IOException) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_READABLE,
                "The main-document path could not be resolved.",
                normalized,
                configurationPath,
                reportOrdinaryRejection,
                error
            )
        } catch (error: SecurityException) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_READABLE,
                "Access to the main-document path was denied.",
                normalized,
                configurationPath,
                reportOrdinaryRejection,
                error
            )
        }
        if (!realPath.startsWith(root)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_OUTSIDE_PROJECT,
                "The main document resolves outside the project root.",
                normalized,
                configurationPath,
                reportOrdinaryRejection
            )
        }

        val content = try {
            Files.readString(realPath, StandardCharsets.UTF_8)
        } catch (error: IOException) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_READABLE,
                "The main document could not be read.",
                realPath,
                configurationPath,
                reportOrdinaryRejection,
                error
            )
        } catch (error: SecurityException) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_NOT_READABLE,
                "Access to the main document was denied.",
                realPath,
                configurationPath,
                reportOrdinaryRejection,
                error
            )
        }
        if (!containsActiveDocumentClass(content)) {
            return rejected(
                ProjectConfigurationDiagnosticCode.MAIN_MISSING_DOCUMENT_CLASS,
                "The main document does not contain an active \\documentclass command.",
                realPath,
                configurationPath,
                reportOrdinaryRejection
            )
        }

        return CandidateValidation(path = realPath)
    }

    private fun resolveRootDirectives(
        root: Path,
        texFiles: List<Path>,
        candidates: List<Path>,
        configurationPath: Path?,
        diagnostics: MutableList<ProjectConfigurationDiagnostic>
    ): Path? {
        val candidateSet = candidates.toSet()
        val destinations = mutableSetOf<Path>()

        texFiles.forEach { sourcePath ->
            val content = try {
                Files.readString(sourcePath, StandardCharsets.UTF_8)
            } catch (_: IOException) {
                return@forEach
            } catch (_: SecurityException) {
                return@forEach
            }

            content.lineSequence().forEach lineLoop@{ line ->
                val configuredTarget = ROOT_DIRECTIVE.matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?: return@lineLoop
                val destination = resolveDirectiveTarget(root, sourcePath, configuredTarget)
                if (destination != null && destination in candidateSet) {
                    destinations.add(destination)
                } else {
                    diagnostics += warning(
                        code = ProjectConfigurationDiagnosticCode.INVALID_ROOT_DIRECTIVE,
                        message = "An invalid TeX root directive was ignored.",
                        configurationPath = configurationPath,
                        relatedPath = sourcePath
                    )
                }
            }
        }

        if (destinations.size > 1) {
            diagnostics += warning(
                code = ProjectConfigurationDiagnosticCode.CONFLICTING_ROOT_DIRECTIVES,
                message = "Conflicting TeX root directives did not select a main document.",
                configurationPath = configurationPath
            )
        }
        return destinations.singleOrNull()
    }

    private fun resolveDirectiveTarget(
        root: Path,
        sourcePath: Path,
        configuredTarget: String
    ): Path? {
        if (
            configuredTarget.isBlank() ||
            configuredTarget.startsWith("~") ||
            WINDOWS_DRIVE_PREFIX.matches(configuredTarget)
        ) {
            return null
        }
        val target = try {
            Path.of(configuredTarget)
        } catch (_: InvalidPathException) {
            return null
        }
        if (target.isAbsolute) {
            return null
        }
        val resolved = sourcePath.parent.resolve(target).normalize()
        if (!resolved.startsWith(root) || Files.isSymbolicLink(resolved)) {
            return null
        }
        return try {
            if (Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
                resolved.toRealPath().takeIf { it.startsWith(root) }
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun containsActiveDocumentClass(content: String): Boolean {
        val sourceWithoutComments = content.lineSequence()
            .joinToString("\n", transform = ::removeTexComment)
        return DOCUMENT_CLASS.containsMatchIn(sourceWithoutComments)
    }

    private fun removeTexComment(line: String): String {
        line.forEachIndexed { index, character ->
            if (character != '%') {
                return@forEachIndexed
            }
            var precedingBackslashes = 0
            var cursor = index - 1
            while (cursor >= 0 && line[cursor] == '\\') {
                precedingBackslashes += 1
                cursor -= 1
            }
            if (precedingBackslashes % 2 == 0) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun isUnderScannerExclusion(root: Path, path: Path): Boolean {
        val relativeParent = root.relativize(path).parent ?: return false
        return relativeParent.any { segment ->
            segment.toString() in ProjectScanner.DEFAULT_EXCLUDED_DIRECTORIES
        }
    }

    private fun flattenFiles(entries: List<ProjectEntry>): List<Path> = buildList {
        entries.forEach { entry ->
            when (entry) {
                is ProjectDirectory -> addAll(flattenFiles(entry.children))
                is ProjectFile -> if (!entry.isSymbolicLink) add(entry.path)
            }
        }
    }

    private fun hasTexExtension(path: Path): Boolean =
        path.fileName?.toString()?.endsWith(".tex", ignoreCase = true) == true

    private fun uniqueCandidate(
        candidates: List<Path>,
        predicate: (Path) -> Boolean
    ): Path? = candidates.filter(predicate).singleOrNull()

    private fun pathComparator(root: Path): Comparator<Path> =
        compareBy<Path>(
            { root.relativize(it).toString().lowercase(Locale.ROOT) },
            { root.relativize(it).toString() }
        )

    private fun rejected(
        code: ProjectConfigurationDiagnosticCode,
        message: String,
        path: Path,
        configurationPath: Path?,
        report: Boolean,
        error: Throwable? = null
    ): CandidateValidation = CandidateValidation(
        diagnostic = if (report) {
            ProjectConfigurationDiagnostic(
                code = code,
                severity = ProjectConfigurationDiagnosticSeverity.ERROR,
                message = message,
                configurationPath = configurationPath,
                relatedPath = path,
                field = "main",
                technicalDetails = error?.message,
                cause = error
            )
        } else {
            null
        }
    )

    private fun provisionalDiagnostic(
        path: Path,
        configurationPath: Path?
    ): ProjectConfigurationDiagnostic = warning(
        code = ProjectConfigurationDiagnosticCode.MAIN_PROVISIONAL,
        message = "Detected ${path.fileName} as a provisional main document.",
        configurationPath = configurationPath,
        relatedPath = path
    )

    private fun warning(
        code: ProjectConfigurationDiagnosticCode,
        message: String,
        configurationPath: Path?,
        relatedPath: Path? = null,
        error: Throwable? = null
    ): ProjectConfigurationDiagnostic = ProjectConfigurationDiagnostic(
        code = code,
        severity = ProjectConfigurationDiagnosticSeverity.WARNING,
        message = message,
        configurationPath = configurationPath,
        relatedPath = relatedPath,
        technicalDetails = error?.message,
        cause = error
    )

    private fun defaultEngine(): EffectiveConfigurationValue<TeXEngine> =
        EffectiveConfigurationValue(
            value = TeXEngine.PDF_LATEX,
            source = ConfigurationValueSource.DEFAULT
        )

    private data class CandidateValidation(
        val path: Path? = null,
        val diagnostic: ProjectConfigurationDiagnostic? = null
    )

    companion object {
        private val DOCUMENT_CLASS =
            Regex("""(?<!\\)\\documentclass(?:\s*\[[^]]*])?\s*\{""")

        private val ROOT_DIRECTIVE =
            Regex("""^\s*%\s*!\s*TEX\s+root\s*=\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

        private val PROGRAM_DIRECTIVE =
            Regex("""^\s*%\s*!\s*TEX\s+program\s*=\s*(\S+)\s*$""", RegexOption.IGNORE_CASE)

        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:.*")
    }
}
