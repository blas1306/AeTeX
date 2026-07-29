package dev.aetex.editor

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView

class DocumentService(projectRoot: Path) {
    val projectRoot: Path = projectRoot.toRealPath()

    fun open(path: Path): DocumentResult<OpenDocument> {
        val validatedPath = when (val validation = validateRegularProjectFile(path, DocumentOperation.READ)) {
            is DocumentResult.Success -> validation.value
            is DocumentResult.Failure -> return validation
        }

        return try {
            val content = Files.readString(validatedPath, StandardCharsets.UTF_8)
            DocumentResult.Success(
                OpenDocument(
                    path = validatedPath,
                    content = content,
                    savedContent = content
                )
            )
        } catch (error: IOException) {
            failure(
                operation = DocumentOperation.READ,
                userMessage = "The file could not be read.",
                error = error
            )
        } catch (error: SecurityException) {
            failure(
                operation = DocumentOperation.READ,
                userMessage = "Access to the file was denied.",
                error = error
            )
        }
    }

    fun save(document: OpenDocument): DocumentResult<OpenDocument> {
        val validatedPath = when (
            val validation = validateRegularProjectFile(document.path, DocumentOperation.WRITE)
        ) {
            is DocumentResult.Success -> validation.value
            is DocumentResult.Failure -> return validation
        }

        if (!Files.isWritable(validatedPath)) {
            return failure(
                operation = DocumentOperation.WRITE,
                userMessage = "The file is not writable."
            )
        }

        val parent = validatedPath.parent
            ?: return failure(
                operation = DocumentOperation.WRITE,
                userMessage = "The file has no writable parent directory."
            )

        var temporaryFile: Path? = null
        return try {
            temporaryFile = Files.createTempFile(
                parent,
                ".${validatedPath.fileName}.",
                ".aetex.tmp"
            )
            Files.writeString(
                temporaryFile,
                document.content,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            copyPosixPermissionsIfSupported(validatedPath, temporaryFile)
            replaceFile(temporaryFile, validatedPath)
            temporaryFile = null
            DocumentResult.Success(document.markedSaved())
        } catch (error: IOException) {
            failure(
                operation = DocumentOperation.WRITE,
                userMessage = "The file could not be saved.",
                error = error
            )
        } catch (error: SecurityException) {
            failure(
                operation = DocumentOperation.WRITE,
                userMessage = "Access was denied while saving the file.",
                error = error
            )
        } finally {
            temporaryFile?.let { temp ->
                try {
                    Files.deleteIfExists(temp)
                } catch (_: IOException) {
                    // The original save error is more useful than a cleanup failure.
                }
            }
        }
    }

    private fun validateRegularProjectFile(
        path: Path,
        operation: DocumentOperation
    ): DocumentResult<Path> {
        val normalized = if (path.isAbsolute) {
            path.normalize()
        } else {
            projectRoot.resolve(path).normalize()
        }

        if (!normalized.startsWith(projectRoot)) {
            return failure(
                operation = DocumentOperation.VALIDATION,
                userMessage = "The selected path is outside the open project."
            )
        }
        if (Files.isSymbolicLink(normalized)) {
            return failure(
                operation = DocumentOperation.VALIDATION,
                userMessage = "Symbolic links cannot be opened as editable documents."
            )
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return failure(
                operation = operation,
                userMessage = "The file no longer exists."
            )
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return failure(
                operation = DocumentOperation.VALIDATION,
                userMessage = "The selected path is not a regular file."
            )
        }

        val realPath = try {
            normalized.toRealPath()
        } catch (error: IOException) {
            return failure(
                operation = operation,
                userMessage = "The file path could not be resolved.",
                error = error
            )
        } catch (error: SecurityException) {
            return failure(
                operation = operation,
                userMessage = "Access to the file path was denied.",
                error = error
            )
        }

        if (!realPath.startsWith(projectRoot)) {
            return failure(
                operation = DocumentOperation.VALIDATION,
                userMessage = "The selected path resolves outside the open project."
            )
        }
        if (operation == DocumentOperation.READ && !Files.isReadable(realPath)) {
            return failure(
                operation = operation,
                userMessage = "The file is not readable."
            )
        }

        return DocumentResult.Success(realPath)
    }

    private fun replaceFile(temporaryFile: Path, target: Path) {
        try {
            Files.move(
                temporaryFile,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile,
                target,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun copyPosixPermissionsIfSupported(source: Path, target: Path) {
        try {
            if (Files.getFileAttributeView(source, PosixFileAttributeView::class.java) == null) {
                return
            }
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX file systems do not expose these permissions.
        }
    }

    private fun <T> failure(
        operation: DocumentOperation,
        userMessage: String,
        error: Exception? = null
    ): DocumentResult<T> = DocumentResult.Failure(
        DocumentError(
            operation = operation,
            userMessage = userMessage,
            technicalDetails = error?.message
        )
    )
}
