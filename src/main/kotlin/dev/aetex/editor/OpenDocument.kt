package dev.aetex.editor

import java.nio.file.Path
import java.util.Locale

data class OpenDocument(
    val path: Path,
    val content: String,
    val savedContent: String,
    val error: DocumentError? = null
) {
    val isModified: Boolean
        get() = content != savedContent

    fun withContent(newContent: String): OpenDocument =
        copy(content = newContent, error = null)

    fun markedSaved(): OpenDocument =
        copy(savedContent = content, error = null)
}

enum class DocumentOperation {
    READ,
    WRITE,
    VALIDATION
}

data class DocumentError(
    val operation: DocumentOperation,
    val userMessage: String,
    val technicalDetails: String? = null
)

sealed interface DocumentResult<out T> {
    data class Success<T>(val value: T) : DocumentResult<T>
    data class Failure(val error: DocumentError) : DocumentResult<Nothing>
}

object EditableFileTypes {
    val extensions: Set<String> = setOf("tex", "bib", "sty", "cls", "txt")

    fun isEditable(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return extension.lowercase(Locale.ROOT) in extensions
    }
}
