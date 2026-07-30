package dev.aetex.compilation

import dev.aetex.project.configuration.CompilationStrategy
import dev.aetex.project.configuration.ConfigurationValueSource
import dev.aetex.project.configuration.TeXEngine
import java.nio.charset.Charset
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

enum class HostPlatform(
    val isWindows: Boolean,
    val pathSeparator: Char,
    val environmentKeysCaseInsensitive: Boolean
) {
    WINDOWS(true, ';', true),
    LINUX(false, ':', false),
    MACOS(false, ':', false);

    companion object {
        fun current(): HostPlatform {
            val name = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            return when {
                name.contains("win") -> WINDOWS
                name.contains("mac") -> MACOS
                else -> LINUX
            }
        }
    }
}

enum class ToolKind(val executableName: String) {
    LATEXMK("latexmk"),
    PDFLATEX("pdflatex"),
    XELATEX("xelatex"),
    LUALATEX("lualatex");

    companion object {
        fun forEngine(engine: TeXEngine): ToolKind = when (engine) {
            TeXEngine.PDF_LATEX -> PDFLATEX
            TeXEngine.XE_LATEX -> XELATEX
            TeXEngine.LUA_LATEX -> LUALATEX
        }
    }
}

enum class ToolDiscoverySource {
    PATH
}

enum class ToolCandidateRejection {
    EMPTY_ENTRY,
    RELATIVE_ENTRY,
    MISSING_DIRECTORY,
    INACCESSIBLE_DIRECTORY,
    NON_DIRECTORY_ENTRY,
    PROJECT_CONTAINED_DIRECTORY,
    DUPLICATE_DIRECTORY,
    MISSING_CANDIDATE,
    BROKEN_LINK,
    NON_REGULAR_FILE,
    PROJECT_CONTAINED_CANDIDATE,
    NOT_EXECUTABLE,
    INVALID_WINDOWS_EXTENSION,
    IO_ERROR
}

data class RejectedToolCandidate(
    val pathEntryIndex: Int,
    val path: Path?,
    val reason: ToolCandidateRejection,
    val technicalDetail: String? = null
)

data class ResolvedTool(
    val kind: ToolKind,
    val executable: Path,
    val source: ToolDiscoverySource,
    val pathEntryIndex: Int,
    val rejectedCandidates: List<RejectedToolCandidate>,
    val fileKey: String?
)

data class ConfigurationProvenance(
    val engine: ConfigurationValueSource,
    val strategy: ConfigurationValueSource,
    val output: ConfigurationValueSource
)

data class OutputSpaceIdentity(
    val normalizedOutputPath: Path,
    val nearestExistingAncestor: Path,
    val unresolvedRemainder: Path?,
    val existingOutputIdentity: Path?,
    val comparisonKey: String,
    val nearestExistingAncestorFileKey: String?,
    val existingOutputFileKey: String?
)

data class ResolvedInvocation(
    val coordinator: ResolvedTool,
    val engineTool: ResolvedTool,
    val engine: TeXEngine,
    val strategy: CompilationStrategy,
    val provenance: ConfigurationProvenance,
    val mainDocument: Path,
    val outputDirectory: Path,
    val outputSpaceIdentity: OutputSpaceIdentity,
    val ignoredInitializationFiles: List<Path>
)

class BuildEnvironment private constructor(
    values: Map<String, String>,
    val charsetName: String,
    val platform: HostPlatform
) {
    val values: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    init {
        Charset.forName(charsetName)
    }

    companion object {
        fun copied(
            values: Map<String, String>,
            charset: Charset,
            platform: HostPlatform
        ): BuildEnvironment = BuildEnvironment(
            values = Collections.unmodifiableMap(LinkedHashMap(values)),
            charsetName = charset.name(),
            platform = platform
        )
    }
}

data class ExpectedArtifact(
    val path: Path,
    val role: ArtifactRole,
    val required: Boolean
)

class BuildPlan private constructor(
    val invocation: ResolvedInvocation,
    arguments: List<String>,
    val workingDirectory: Path,
    val environment: BuildEnvironment,
    expectedFiles: List<ExpectedArtifact>
) {
    val arguments: List<String> = Collections.unmodifiableList(arguments.toList())
    val expectedFiles: List<ExpectedArtifact> =
        Collections.unmodifiableList(expectedFiles.toList())
    val fingerprint: String = fingerprint()

    val primaryPdf: Path
        get() = expectedFiles.single { it.role == ArtifactRole.PRIMARY_PDF }.path

    init {
        require(workingDirectory.isAbsolute) { "Working directory must be absolute." }
        require(invocation.coordinator.executable.isAbsolute)
        require(invocation.engineTool.executable.isAbsolute)
        require(invocation.mainDocument.isAbsolute)
        require(invocation.outputDirectory.isAbsolute)
        require(expectedFiles.count { it.role == ArtifactRole.PRIMARY_PDF && it.required } == 1)
        if (environment.platform.environmentKeysCaseInsensitive) {
            require(
                environment.values.keys
                    .groupBy { it.lowercase(Locale.ROOT) }
                    .values
                    .none { it.size > 1 }
            ) {
                "Case-insensitive environment keys must be unique."
            }
        }
    }

    internal fun canonicalSerialization(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val output = DataOutputStream(buffer)
        fun add(tag: String, value: String?) {
            fun writeUtf8(text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            writeUtf8(tag)
            if (value == null) {
                output.writeInt(-1)
            } else {
                writeUtf8(value)
            }
        }
        fun addTool(tag: String, tool: ResolvedTool) {
            add("$tag.executable", tool.executable.toString())
            add("$tag.kind", tool.kind.name)
            add("$tag.source", tool.source.name)
            add("$tag.pathEntryIndex", tool.pathEntryIndex.toString())
            add("$tag.fileKey", tool.fileKey)
            add("$tag.rejections.count", tool.rejectedCandidates.size.toString())
            tool.rejectedCandidates.forEachIndexed { index, rejection ->
                add("$tag.rejections[$index].pathEntryIndex", rejection.pathEntryIndex.toString())
                add("$tag.rejections[$index].path", rejection.path?.toString())
                add("$tag.rejections[$index].reason", rejection.reason.name)
                add("$tag.rejections[$index].detail", rejection.technicalDetail)
            }
        }

        add("format", "aetex-build-plan-v1")
        addTool("coordinator", invocation.coordinator)
        addTool("engineTool", invocation.engineTool)
        add("engine", invocation.engine.name)
        add("strategy", invocation.strategy.name)
        add("provenance.engine", invocation.provenance.engine.name)
        add("provenance.strategy", invocation.provenance.strategy.name)
        add("provenance.output", invocation.provenance.output.name)
        add("mainDocument", invocation.mainDocument.toString())
        add("outputDirectory", invocation.outputDirectory.toString())
        add("outputIdentity.comparisonKey", invocation.outputSpaceIdentity.comparisonKey)
        add("outputIdentity.normalizedPath", invocation.outputSpaceIdentity.normalizedOutputPath.toString())
        add("outputIdentity.ancestor", invocation.outputSpaceIdentity.nearestExistingAncestor.toString())
        add("outputIdentity.remainder", invocation.outputSpaceIdentity.unresolvedRemainder?.toString())
        add("outputIdentity.existing", invocation.outputSpaceIdentity.existingOutputIdentity?.toString())
        add(
            "outputIdentity.ancestorFileKey",
            invocation.outputSpaceIdentity.nearestExistingAncestorFileKey
        )
        add("outputIdentity.existingFileKey", invocation.outputSpaceIdentity.existingOutputFileKey)
        add("ignoredInitializationFiles.count", invocation.ignoredInitializationFiles.size.toString())
        invocation.ignoredInitializationFiles.forEachIndexed { index, path ->
            add("ignoredInitializationFiles[$index]", path.toString())
        }
        add("arguments.count", arguments.size.toString())
        arguments.forEachIndexed { index, argument -> add("arguments[$index]", argument) }
        add("workingDirectory", workingDirectory.toString())
        val keyComparator = if (environment.platform.environmentKeysCaseInsensitive) {
            compareBy<String>({ it.lowercase(Locale.ROOT) }, { it })
        } else {
            naturalOrder()
        }
        val environmentKeys = environment.values.keys.sortedWith(keyComparator)
        add("environment.count", environmentKeys.size.toString())
        environmentKeys.forEachIndexed { index, key ->
            add("environment[$index].key", key)
            add("environment[$index].value", environment.values.getValue(key))
        }
        add("environment.charset", environment.charsetName)
        add("environment.platform", environment.platform.name)
        add("expectedFiles.count", expectedFiles.size.toString())
        expectedFiles.forEachIndexed { index, artifact ->
            add("expectedFiles[$index].path", artifact.path.toString())
            add("expectedFiles[$index].role", artifact.role.name)
            add("expectedFiles[$index].required", artifact.required.toString())
        }
        output.flush()
        return buffer.toByteArray()
    }

    private fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalSerialization())
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    companion object {
        fun create(
            invocation: ResolvedInvocation,
            arguments: List<String>,
            workingDirectory: Path,
            environment: BuildEnvironment,
            expectedFiles: List<ExpectedArtifact>
        ): BuildPlan = BuildPlan(
            invocation = invocation.copy(
                coordinator = invocation.coordinator.copy(
                    executable = invocation.coordinator.executable.toAbsolutePath().normalize(),
                    rejectedCandidates = Collections.unmodifiableList(
                        invocation.coordinator.rejectedCandidates.toList()
                    )
                ),
                engineTool = invocation.engineTool.copy(
                    executable = invocation.engineTool.executable.toAbsolutePath().normalize(),
                    rejectedCandidates = Collections.unmodifiableList(
                        invocation.engineTool.rejectedCandidates.toList()
                    )
                ),
                mainDocument = invocation.mainDocument.toAbsolutePath().normalize(),
                outputDirectory = invocation.outputDirectory.toAbsolutePath().normalize(),
                outputSpaceIdentity = invocation.outputSpaceIdentity.copy(
                    normalizedOutputPath =
                        invocation.outputSpaceIdentity.normalizedOutputPath.toAbsolutePath().normalize(),
                    nearestExistingAncestor =
                        invocation.outputSpaceIdentity.nearestExistingAncestor.toAbsolutePath().normalize(),
                    unresolvedRemainder =
                        invocation.outputSpaceIdentity.unresolvedRemainder?.normalize(),
                    existingOutputIdentity =
                        invocation.outputSpaceIdentity.existingOutputIdentity?.toAbsolutePath()?.normalize()
                ),
                ignoredInitializationFiles = Collections.unmodifiableList(
                    invocation.ignoredInitializationFiles.map {
                        it.toAbsolutePath().normalize()
                    }
                )
            ),
            arguments = arguments.toList(),
            workingDirectory = workingDirectory.toAbsolutePath().normalize(),
            environment = BuildEnvironment.copied(
                environment.values,
                Charset.forName(environment.charsetName),
                environment.platform
            ),
            expectedFiles = expectedFiles.map {
                it.copy(path = it.path.toAbsolutePath().normalize())
            }
        )
    }
}
