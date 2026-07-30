package dev.aetex.compilation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

class ArtifactValidator {
    fun validate(
        plan: BuildPlan,
        before: Map<java.nio.file.Path, ArtifactBeforeBuild>,
        processSucceeded: Boolean
    ): List<ArtifactObservation> = plan.expectedFiles.map { expected ->
        observe(plan, expected, before[expected.path], processSucceeded)
    }

    private fun observe(
        plan: BuildPlan,
        expected: ExpectedArtifact,
        previous: ArtifactBeforeBuild?,
        processSucceeded: Boolean
    ): ArtifactObservation {
        val path = expected.path
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ArtifactObservation(expected, ArtifactStatus.MISSING)
        }
        if (
            Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isReadable(path)
        ) {
            return ArtifactObservation(expected, ArtifactStatus.INVALID, technicalDetail = "Not a readable regular file.")
        }
        val real = try {
            path.toRealPath()
        } catch (error: IOException) {
            return ArtifactObservation(expected, ArtifactStatus.INVALID, technicalDetail = error.message)
        } catch (error: SecurityException) {
            return ArtifactObservation(expected, ArtifactStatus.INVALID, technicalDetail = error.message)
        }
        if (!real.startsWith(plan.invocation.outputDirectory)) {
            return ArtifactObservation(expected, ArtifactStatus.INVALID, technicalDetail = "Artifact escaped output.")
        }
        return try {
            val attributes = Files.readAttributes(
                real,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
            val status = when {
                previous?.exists != true -> ArtifactStatus.CREATED
                previous.fileKey != attributes.fileKey()?.toString() ||
                    previous.size != attributes.size() ||
                    previous.lastModified != attributes.lastModifiedTime().toInstant() ->
                    ArtifactStatus.MODIFIED
                processSucceeded -> ArtifactStatus.REUSED_UNCHANGED
                else -> ArtifactStatus.REUSED_UNCHANGED
            }
            ArtifactObservation(
                expected = expected,
                status = status,
                size = attributes.size(),
                lastModified = attributes.lastModifiedTime().toInstant()
            )
        } catch (error: IOException) {
            ArtifactObservation(expected, ArtifactStatus.INVALID, technicalDetail = error.message)
        } catch (error: SecurityException) {
            ArtifactObservation(expected, ArtifactStatus.INVALID, technicalDetail = error.message)
        }
    }
}
