package dev.aetex.compilation

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class RuntimeStorageException(
    val failure: BuildFailure
) : IOException(failure.message, failure.technicalCause?.let {
    IOException("${it.type}: ${it.message.orEmpty()}")
})

object RuntimeDirectoryResolver {
    fun resolve(userHome: String?): Path {
        if (userHome.isNullOrBlank()) {
            throw failure(null, "The user home directory is unavailable.")
        }
        val home = try {
            Path.of(userHome).normalize()
        } catch (error: RuntimeException) {
            throw failure(null, "The user home directory is invalid.", error)
        }
        if (!home.isAbsolute) {
            throw failure(home, "The user home directory must be absolute.")
        }
        val realHome = validateDirectory(home, "The user home directory is not accessible.")
        var current = realHome
        listOf(".aetex", "runtime").forEach { segment ->
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current)
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent creator won; validate the resulting entry below.
                } catch (error: IOException) {
                    throw failure(current, "The AeTeX runtime directory could not be created.", error)
                } catch (error: SecurityException) {
                    throw failure(current, "Creation of the AeTeX runtime directory was denied.", error)
                }
            }
            if (
                Files.isSymbolicLink(current) ||
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isReadable(current) ||
                !Files.isWritable(current)
            ) {
                throw failure(current, "The AeTeX runtime path is not a writable real directory.")
            }
        }
        return current.toAbsolutePath().normalize()
    }

    private fun validateDirectory(path: Path, message: String): Path {
        return try {
            if (
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.isReadable(path)
            ) {
                throw failure(path, message)
            }
            path.toRealPath()
        } catch (error: RuntimeStorageException) {
            throw error
        } catch (error: IOException) {
            throw failure(path, message, error)
        } catch (error: SecurityException) {
            throw failure(path, message, error)
        }
    }

    private fun failure(
        path: Path?,
        message: String,
        error: Throwable? = null
    ) = RuntimeStorageException(
        BuildFailure(
            kind = BuildFailureKind.LOG_STORAGE_FAILURE,
            message = message,
            technicalCause = error?.let(TechnicalCause::from),
            relatedPath = path
        )
    )
}
