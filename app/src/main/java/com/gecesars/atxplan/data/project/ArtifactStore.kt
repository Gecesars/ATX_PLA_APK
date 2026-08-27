package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

enum class ArtifactAvailability {
    AVAILABLE,
    MISSING,
    CORRUPT,
}

interface ProjectArtifactRepository {
    suspend fun storeArtifact(
        role: ProjectArtifactRole,
        fileName: String,
        mediaType: String,
        input: InputStream,
        maximumBytes: Long,
        expectedSha256: String? = null,
    ): ProjectArtifactReference

    suspend fun artifactAvailability(reference: ProjectArtifactReference): ArtifactAvailability

    suspend fun copyArtifact(
        reference: ProjectArtifactReference,
        output: OutputStream,
        maximumBytes: Long,
    )
}

internal class FileContentAddressedArtifactStore(
    private val rootDirectory: File,
    private val idGenerator: () -> String = { "artifact-${UUID.randomUUID()}" },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun put(
        role: ProjectArtifactRole,
        fileName: String,
        mediaType: String,
        input: InputStream,
        maximumBytes: Long,
        expectedSha256: String? = null,
    ): ProjectArtifactReference {
        require(maximumBytes in 1..MAX_ARTIFACT_OPERATION_BYTES) {
            "The artifact operation limit must be between 1 byte and 512 MB."
        }
        require(expectedSha256 == null || STORAGE_SHA256_PATTERN.matches(expectedSha256)) {
            "The expected artifact hash must be a lowercase SHA-256 digest."
        }
        val stagingDirectory = File(rootDirectory, "staging")
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IOException("The artifact staging directory could not be created.")
        }
        val staging = File.createTempFile("artifact-", ".part", stagingDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            FileOutputStream(staging).use { output ->
                val buffer = ByteArray(ARTIFACT_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    byteCount += read
                    if (byteCount > maximumBytes) {
                        throw IOException("The artifact exceeds the approved operation limit.")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            val sha256 = digest.digest().toHex()
            if (expectedSha256 != null && expectedSha256 != sha256) {
                throw IOException("The artifact does not match the expected SHA-256 digest.")
            }
            val target = targetFile(sha256)
            val parent = target.parentFile
                ?: throw IOException("The artifact directory is invalid.")
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("The artifact directory could not be created.")
            }
            val targetExists = target.exists()
            if (targetExists && !verifyFile(target, sha256, byteCount)) {
                throw IOException("Existing immutable artifact content failed verification.")
            }
            val reference = ProjectArtifactReference(
                id = idGenerator(),
                role = role,
                fileName = fileName,
                mediaType = mediaType,
                sha256 = sha256,
                byteCount = byteCount,
                createdAtEpochMillis = clock(),
            )
            if (!targetExists && !staging.renameTo(target)) {
                if (!target.exists() || !verifyFile(target, sha256, byteCount)) {
                    throw IOException("The artifact could not be promoted atomically.")
                }
            }
            return reference
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun availability(reference: ProjectArtifactReference): ArtifactAvailability {
        val target = targetFile(reference.sha256)
        if (!target.isFile) return ArtifactAvailability.MISSING
        return if (verifyFile(target, reference.sha256, reference.byteCount)) {
            ArtifactAvailability.AVAILABLE
        } else {
            ArtifactAvailability.CORRUPT
        }
    }

    fun copy(
        reference: ProjectArtifactReference,
        output: OutputStream,
        maximumBytes: Long,
    ) {
        require(maximumBytes in 1..MAX_ARTIFACT_OPERATION_BYTES) {
            "The artifact operation limit must be between 1 byte and 512 MB."
        }
        if (reference.byteCount > maximumBytes) {
            throw IOException("The artifact exceeds the approved operation limit.")
        }
        val target = targetFile(reference.sha256)
        when (availability(reference)) {
            ArtifactAvailability.MISSING -> throw IOException("The referenced artifact is missing.")
            ArtifactAvailability.CORRUPT -> throw IOException("The referenced artifact is corrupt.")
            ArtifactAvailability.AVAILABLE -> Unit
        }
        FileInputStream(target).use { input -> input.copyTo(output, ARTIFACT_BUFFER_BYTES) }
    }

    private fun targetFile(sha256: String): File {
        require(STORAGE_SHA256_PATTERN.matches(sha256)) {
            "The artifact hash must be a lowercase SHA-256 digest."
        }
        return File(File(File(rootDirectory, "sha256"), sha256.take(2)), "$sha256.blob")
    }

    private fun verifyFile(file: File, expectedSha256: String, expectedBytes: Long): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(ARTIFACT_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == expectedSha256
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val ARTIFACT_BUFFER_BYTES = 64 * 1024
private const val MAX_ARTIFACT_OPERATION_BYTES = 512L * 1024L * 1024L
