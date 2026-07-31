package dev.aetex.project.configuration

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ProjectConfigurationSerializer {
    fun serialize(configuration: ProjectConfiguration): String {
        require(configuration.schema == CURRENT_PROJECT_CONFIGURATION_SCHEMA) {
            "Only project configuration schema $CURRENT_PROJECT_CONFIGURATION_SCHEMA can be written."
        }
        require(configuration.unknownFields.isEmpty()) {
            "A configuration containing unknown fields cannot be rewritten without data loss."
        }

        return buildString {
            append("schema = ")
            append(configuration.schema)
            append('\n')
            configuration.main?.let {
                append("main = ")
                appendTomlString(portableRelativePath("main", it))
                append('\n')
            }
            configuration.engine?.let {
                append("engine = ")
                appendTomlString(it.configurationValue)
                append('\n')
            }
            configuration.strategy?.let {
                append("strategy = ")
                appendTomlString(it.configurationValue)
                append('\n')
            }
            configuration.output?.let {
                append("output = ")
                appendTomlString(portableRelativePath("output", it))
                append('\n')
            }
        }
    }

    private fun portableRelativePath(field: String, path: Path): String {
        require(!path.isAbsolute) { "The '$field' path must be relative." }
        val normalized = path.normalize()
        require(normalized.nameCount > 0 && normalized.toString().isNotBlank()) {
            "The '$field' path cannot be empty."
        }
        require(normalized.none { it.toString() == ".." }) {
            "The '$field' path cannot escape the project root."
        }
        return normalized.joinToString("/") { it.toString() }.also {
            require('\\' !in it) { "The '$field' path cannot contain a backslash." }
        }
    }

    private fun StringBuilder.appendTomlString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> {
                    require(character.code >= 0x20 && character.code != 0x7F) {
                        "Project configuration strings cannot contain control characters."
                    }
                    append(character)
                }
            }
        }
        append('"')
    }
}

fun interface NewProjectFileWriter {
    @Throws(IOException::class)
    fun writeNew(path: Path, content: String)
}

class AtomicNewProjectFileWriter : NewProjectFileWriter {
    override fun writeNew(path: Path, content: String) {
        val parent = checkNotNull(path.parent) { "A project file must have a parent directory." }
        val temporary = Files.createTempFile(parent, ".aetex-", ".partial")
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            // A same-directory move publishes only the complete temporary file.
            // REPLACE_EXISTING is deliberately absent: provisioning never overwrites.
            Files.move(temporary, path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

class ProjectConfigurationWriter(
    private val serializer: ProjectConfigurationSerializer = ProjectConfigurationSerializer(),
    private val fileWriter: NewProjectFileWriter = AtomicNewProjectFileWriter()
) {
    fun writeNew(projectRoot: Path, configuration: ProjectConfiguration): Path {
        val path = projectRoot.toAbsolutePath().normalize()
            .resolve(ProjectConfigurationLoader.CONFIGURATION_RELATIVE_PATH)
        fileWriter.writeNew(path, serializer.serialize(configuration))
        return path
    }
}
