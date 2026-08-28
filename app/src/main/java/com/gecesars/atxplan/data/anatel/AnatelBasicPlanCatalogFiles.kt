package com.gecesars.atxplan.data.anatel

import android.util.AtomicFile
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveEntry
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanLimits
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanSourceDescriptor
import com.gecesars.atxplan.domain.anatel.AnatelDatasetLicense
import com.gecesars.atxplan.domain.anatel.AnatelLicenseReviewStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal class AnatelBasicPlanCatalogFileException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class StoredAnatelRawArchive(
    val file: File,
    val provenanceFile: File,
    val provenance: AnatelBasicPlanArchiveProvenance,
    val reused: Boolean,
)

internal data class AnatelCurrentPointer(
    val archiveSha256: String,
    val rawArchiveFileName: String,
    val rawProvenanceFileName: String,
    val indexFileName: String,
    val indexedAtEpochMillis: Long,
)

internal sealed interface AnatelCurrentPointerRead {
    data object Missing : AnatelCurrentPointerRead

    data object Invalid : AnatelCurrentPointerRead

    data class Present(val pointer: AnatelCurrentPointer) : AnatelCurrentPointerRead
}

internal class AnatelBasicPlanCatalogLayout(rootDirectory: File) {
    val root: File = rootDirectory.absoluteFile
    val rawDirectory = File(root, "raw")
    val indexDirectory = File(root, "indexes")
    val currentPointerFile = File(root, "current.json")

    init {
        ensureDirectory(root, "Anatel catalog root")
        ensureDirectory(rawDirectory, "Anatel raw archive directory")
        ensureDirectory(indexDirectory, "Anatel index directory")
    }

    fun rawArchiveFile(sha256: String): File = File(rawDirectory, rawArchiveName(sha256))

    fun rawProvenanceFile(sha256: String): File = File(rawDirectory, rawProvenanceName(sha256))

    fun indexFile(sha256: String): File = File(indexDirectory, indexName(sha256))

    fun stagingIndexFile(): File = File(
        indexDirectory,
        ".staging-${UUID.randomUUID()}.sqlite",
    )

    fun validatePointer(pointer: AnatelCurrentPointer): Boolean =
        SHA256.matches(pointer.archiveSha256) &&
            pointer.rawArchiveFileName == rawArchiveName(pointer.archiveSha256) &&
            pointer.rawProvenanceFileName == rawProvenanceName(pointer.archiveSha256) &&
            pointer.indexFileName == indexName(pointer.archiveSha256) &&
            pointer.indexedAtEpochMillis >= 0L

    companion object {
        const val INDEX_SCHEMA_VERSION = 1

        private val SHA256 = Regex("[0-9a-f]{64}")

        fun rawArchiveName(sha256: String): String {
            require(SHA256.matches(sha256)) { "An Anatel archive hash is invalid." }
            return "canais-$sha256.zip"
        }

        fun rawProvenanceName(sha256: String): String {
            require(SHA256.matches(sha256)) { "An Anatel archive hash is invalid." }
            return "canais-$sha256.provenance.json"
        }

        fun indexName(sha256: String): String {
            require(SHA256.matches(sha256)) { "An Anatel archive hash is invalid." }
            return "basic-plan-$sha256-v$INDEX_SCHEMA_VERSION.sqlite"
        }
    }
}

internal class ImmutableAnatelRawArchiveStore(
    private val layout: AnatelBasicPlanCatalogLayout,
    private val maximumArchiveCount: Int = MAX_RAW_ARCHIVES,
    private val maximumTotalBytes: Long = MAX_TOTAL_RAW_BYTES,
) {
    init {
        require(maximumArchiveCount in 1..MAX_RAW_ARCHIVES) {
            "The immutable Anatel raw archive count bound is invalid."
        }
        require(maximumTotalBytes in AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES..MAX_TOTAL_RAW_BYTES) {
            "The immutable Anatel raw storage byte bound is invalid."
        }
    }

    fun store(
        input: InputStream,
        declaredContentLength: Long?,
        provenanceFactory: (sha256: String, byteCount: Long) -> AnatelBasicPlanArchiveProvenance,
    ): StoredAnatelRawArchive {
        if (declaredContentLength != null &&
            declaredContentLength !in 1..AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES
        ) {
            throw AnatelBasicPlanCatalogFileException(
                "The Anatel HTTP Content-Length is outside the supported archive bound.",
            )
        }
        val staging = File(layout.rawDirectory, ".download-${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            FileOutputStream(staging).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (byteCount > AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES - count) {
                        throw AnatelBasicPlanCatalogFileException(
                            "The downloaded Anatel archive exceeds the supported byte bound.",
                        )
                    }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    byteCount += count
                }
                output.flush()
                output.fd.sync()
            }
            if (byteCount == 0L) {
                throw AnatelBasicPlanCatalogFileException("The downloaded Anatel archive is empty.")
            }
            if (declaredContentLength != null && declaredContentLength != byteCount) {
                throw AnatelBasicPlanCatalogFileException(
                    "The downloaded Anatel archive does not match its HTTP Content-Length.",
                )
            }
            val sha256 = digest.digest().toHex()
            val candidateProvenance = provenanceFactory(sha256, byteCount)
            val target = layout.rawArchiveFile(sha256)
            val provenanceTarget = layout.rawProvenanceFile(sha256)

            if (target.exists()) {
                verifyRawFile(target, sha256, byteCount)
                if (!staging.delete()) {
                    throw AnatelBasicPlanCatalogFileException(
                        "The duplicate Anatel download staging file could not be removed.",
                    )
                }
                val storedProvenance = if (provenanceTarget.exists()) {
                    readProvenance(provenanceTarget)
                } else {
                    writeProvenanceOnce(provenanceTarget, candidateProvenance)
                    candidateProvenance
                }
                validateStoredProvenance(storedProvenance, sha256, byteCount)
                return StoredAnatelRawArchive(
                    file = target,
                    provenanceFile = provenanceTarget,
                    provenance = storedProvenance,
                    reused = true,
                )
            }

            enforceCapacityFor(byteCount)
            if (!staging.renameTo(target)) {
                throw AnatelBasicPlanCatalogFileException(
                    "The verified Anatel archive could not be promoted into immutable storage.",
                )
            }
            writeProvenanceOnce(provenanceTarget, candidateProvenance)
            return StoredAnatelRawArchive(
                file = target,
                provenanceFile = provenanceTarget,
                provenance = candidateProvenance,
                reused = false,
            )
        } finally {
            if (staging.exists()) staging.delete()
            // A promoted verified archive is intentionally retained if later metadata writing fails.
        }
    }

    fun load(pointer: AnatelCurrentPointer): StoredAnatelRawArchive? {
        if (!layout.validatePointer(pointer)) return null
        val raw = File(layout.rawDirectory, pointer.rawArchiveFileName)
        val provenanceFile = File(layout.rawDirectory, pointer.rawProvenanceFileName)
        if (!raw.isFile || !provenanceFile.isFile) return null
        val provenance = try {
            readProvenance(provenanceFile)
        } catch (_: Exception) {
            return null
        }
        return try {
            if (raw.length() !in 1..AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES) return null
            validateStoredProvenance(
                provenance = provenance,
                expectedSha256 = pointer.archiveSha256,
                expectedByteCount = raw.length(),
            )
            verifyRawFile(
                file = raw,
                expectedSha256 = pointer.archiveSha256,
                expectedByteCount = provenance.archiveByteCount,
            )
            StoredAnatelRawArchive(raw, provenanceFile, provenance, reused = true)
        } catch (_: AnatelBasicPlanCatalogFileException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun enforceCapacityFor(newArchiveBytes: Long) {
        val archives = layout.rawDirectory.listFiles { file ->
            file.isFile && RAW_ARCHIVE_FILE.matches(file.name)
        }.orEmpty()
        if (archives.size >= maximumArchiveCount) {
            throw AnatelBasicPlanCatalogFileException(
                "The immutable Anatel raw archive retention count is full.",
            )
        }
        val usedBytes = archives.fold(0L) { total, file ->
            if (total > maximumTotalBytes - file.length()) Long.MAX_VALUE else total + file.length()
        }
        if (usedBytes == Long.MAX_VALUE || usedBytes > maximumTotalBytes - newArchiveBytes) {
            throw AnatelBasicPlanCatalogFileException(
                "The immutable Anatel raw archive storage byte limit would be exceeded.",
            )
        }
    }

    private fun verifyRawFile(
        file: File,
        expectedSha256: String,
        expectedByteCount: Long,
    ) {
        if (!file.isFile || file.length() != expectedByteCount) {
            throw AnatelBasicPlanCatalogFileException(
                "An existing immutable Anatel archive does not match the downloaded artifact.",
            )
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (count > AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES - read) {
                    throw AnatelBasicPlanCatalogFileException(
                        "An existing immutable Anatel archive exceeds the supported byte bound.",
                    )
                }
                digest.update(buffer, 0, read)
                count += read
            }
        }
        if (count != expectedByteCount || digest.digest().toHex() != expectedSha256) {
            throw AnatelBasicPlanCatalogFileException(
                "An existing immutable Anatel archive failed SHA-256 verification.",
            )
        }
    }

    private fun writeProvenanceOnce(
        target: File,
        provenance: AnatelBasicPlanArchiveProvenance,
    ) {
        if (target.exists()) {
            validateStoredProvenance(
                readProvenance(target),
                provenance.archiveSha256,
                provenance.archiveByteCount,
            )
            return
        }
        val payload = CATALOG_JSON.encodeToString(
            StoredArchiveProvenanceDto.serializer(),
            StoredArchiveProvenanceDto.fromDomain(provenance),
        ).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_METADATA_BYTES) {
            throw AnatelBasicPlanCatalogFileException(
                "The Anatel archive provenance exceeds the metadata byte bound.",
            )
        }
        writeAtomicFile(AtomicFile(target), payload, "Anatel archive provenance")
    }

    private fun readProvenance(target: File): AnatelBasicPlanArchiveProvenance {
        val bytes = try {
            AtomicFile(target).openRead().use { input -> input.readBounded(MAX_METADATA_BYTES) }
        } catch (error: Exception) {
            throw AnatelBasicPlanCatalogFileException(
                "The immutable Anatel archive provenance could not be read.",
                error,
            )
        }
        return try {
            CATALOG_JSON.decodeFromString(
                StoredArchiveProvenanceDto.serializer(),
                bytes.toString(Charsets.UTF_8),
            ).toDomain()
        } catch (error: Exception) {
            throw AnatelBasicPlanCatalogFileException(
                "The immutable Anatel archive provenance is invalid.",
                error,
            )
        }
    }

    private fun validateStoredProvenance(
        provenance: AnatelBasicPlanArchiveProvenance,
        expectedSha256: String,
        expectedByteCount: Long,
    ) {
        require(
            provenance.archiveSha256 == expectedSha256 &&
                provenance.archiveByteCount == expectedByteCount,
        ) { "The immutable Anatel archive provenance does not match its raw artifact." }
    }
}

internal class AtomicAnatelCurrentPointerStore(
    private val layout: AnatelBasicPlanCatalogLayout,
) {
    private val atomicFile = AtomicFile(layout.currentPointerFile)

    fun read(): AnatelCurrentPointerRead {
        val payload = try {
            atomicFile.openRead().use { input -> input.readBounded(MAX_POINTER_BYTES) }
        } catch (_: FileNotFoundException) {
            return AnatelCurrentPointerRead.Missing
        } catch (_: Exception) {
            return AnatelCurrentPointerRead.Invalid
        }
        val dto = try {
            CATALOG_JSON.decodeFromString(CurrentPointerDto.serializer(), payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return AnatelCurrentPointerRead.Invalid
        }
        val pointer = dto.toDomain() ?: return AnatelCurrentPointerRead.Invalid
        return if (layout.validatePointer(pointer)) {
            AnatelCurrentPointerRead.Present(pointer)
        } else {
            AnatelCurrentPointerRead.Invalid
        }
    }

    fun write(pointer: AnatelCurrentPointer) {
        require(layout.validatePointer(pointer)) { "The Anatel current pointer is invalid." }
        val payload = CATALOG_JSON.encodeToString(
            CurrentPointerDto.serializer(),
            CurrentPointerDto.fromDomain(pointer),
        ).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_POINTER_BYTES) {
            throw AnatelBasicPlanCatalogFileException(
                "The Anatel current pointer exceeds the metadata byte bound.",
            )
        }
        writeAtomicFile(atomicFile, payload, "Anatel current pointer")
    }
}

@Serializable
private data class CurrentPointerDto(
    val schemaVersion: Int,
    val archiveSha256: String,
    val rawArchiveFileName: String,
    val rawProvenanceFileName: String,
    val indexFileName: String,
    val indexedAtEpochMillis: Long,
) {
    fun toDomain(): AnatelCurrentPointer? {
        if (schemaVersion != CURRENT_POINTER_SCHEMA_VERSION) return null
        return AnatelCurrentPointer(
            archiveSha256 = archiveSha256,
            rawArchiveFileName = rawArchiveFileName,
            rawProvenanceFileName = rawProvenanceFileName,
            indexFileName = indexFileName,
            indexedAtEpochMillis = indexedAtEpochMillis,
        )
    }

    companion object {
        fun fromDomain(pointer: AnatelCurrentPointer) = CurrentPointerDto(
            schemaVersion = CURRENT_POINTER_SCHEMA_VERSION,
            archiveSha256 = pointer.archiveSha256,
            rawArchiveFileName = pointer.rawArchiveFileName,
            rawProvenanceFileName = pointer.rawProvenanceFileName,
            indexFileName = pointer.indexFileName,
            indexedAtEpochMillis = pointer.indexedAtEpochMillis,
        )
    }
}

@Serializable
private data class StoredArchiveProvenanceDto(
    val schemaVersion: Int,
    val source: SourceDescriptorDto,
    val acquiredAtEpochMillis: Long,
    val archiveSha256: String,
    val archiveByteCount: Long,
    val effectiveArchiveUrl: String,
    val etag: String?,
    val lastModified: String?,
) {
    fun toDomain(): AnatelBasicPlanArchiveProvenance {
        require(schemaVersion == RAW_PROVENANCE_SCHEMA_VERSION) {
            "The Anatel raw provenance schema is unsupported."
        }
        return AnatelBasicPlanArchiveProvenance(
            source = source.toDomain(),
            acquiredAtEpochMillis = acquiredAtEpochMillis,
            archiveSha256 = archiveSha256,
            archiveByteCount = archiveByteCount,
            effectiveArchiveUrl = effectiveArchiveUrl,
            etag = etag,
            lastModified = lastModified,
        )
    }

    companion object {
        fun fromDomain(value: AnatelBasicPlanArchiveProvenance) = StoredArchiveProvenanceDto(
            schemaVersion = RAW_PROVENANCE_SCHEMA_VERSION,
            source = SourceDescriptorDto.fromDomain(value.source),
            acquiredAtEpochMillis = value.acquiredAtEpochMillis,
            archiveSha256 = value.archiveSha256,
            archiveByteCount = value.archiveByteCount,
            effectiveArchiveUrl = value.effectiveArchiveUrl,
            etag = value.etag,
            lastModified = value.lastModified,
        )
    }
}

@Serializable
private data class SourceDescriptorDto(
    val datasetId: String,
    val title: String,
    val provider: String,
    val landingPageUrl: String,
    val archiveUrl: String,
    val allowedHosts: List<String>,
    val archiveEntries: List<ArchiveEntryDto>,
    val license: LicenseDto,
) {
    fun toDomain() = AnatelBasicPlanSourceDescriptor(
        datasetId = datasetId,
        title = title,
        provider = provider,
        landingPageUrl = landingPageUrl,
        archiveUrl = archiveUrl,
        allowedHosts = allowedHosts.toSet(),
        archiveEntries = archiveEntries.map(ArchiveEntryDto::toDomain),
        license = license.toDomain(),
    )

    companion object {
        fun fromDomain(value: AnatelBasicPlanSourceDescriptor) = SourceDescriptorDto(
            datasetId = value.datasetId,
            title = value.title,
            provider = value.provider,
            landingPageUrl = value.landingPageUrl,
            archiveUrl = value.archiveUrl,
            allowedHosts = value.allowedHosts.sorted(),
            archiveEntries = value.archiveEntries.map(ArchiveEntryDto::fromDomain),
            license = LicenseDto.fromDomain(value.license),
        )
    }
}

@Serializable
private data class ArchiveEntryDto(
    val name: String,
    val origin: String,
) {
    fun toDomain() = AnatelBasicPlanArchiveEntry(name, AnatelBasicPlanOrigin.valueOf(origin))

    companion object {
        fun fromDomain(value: AnatelBasicPlanArchiveEntry) = ArchiveEntryDto(
            name = value.name,
            origin = value.origin.name,
        )
    }
}

@Serializable
private data class LicenseDto(
    val identifier: String,
    val title: String,
    val termsUrl: String,
    val attribution: String,
    val reviewStatus: String,
) {
    fun toDomain() = AnatelDatasetLicense(
        identifier = identifier,
        title = title,
        termsUrl = termsUrl,
        attribution = attribution,
        reviewStatus = AnatelLicenseReviewStatus.valueOf(reviewStatus),
    )

    companion object {
        fun fromDomain(value: AnatelDatasetLicense) = LicenseDto(
            identifier = value.identifier,
            title = value.title,
            termsUrl = value.termsUrl,
            attribution = value.attribution,
            reviewStatus = value.reviewStatus.name,
        )
    }
}

private fun writeAtomicFile(
    atomicFile: AtomicFile,
    payload: ByteArray,
    label: String,
) {
    var output: FileOutputStream? = null
    try {
        output = atomicFile.startWrite()
        output.write(payload)
        output.flush()
        output.fd.sync()
        atomicFile.finishWrite(output)
    } catch (error: Exception) {
        output?.let(atomicFile::failWrite)
        throw AnatelBasicPlanCatalogFileException("The $label could not be written atomically.", error)
    }
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maximumBytes - count) {
            throw AnatelBasicPlanCatalogFileException("An Anatel metadata file exceeds its byte bound.")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

private fun ensureDirectory(
    directory: File,
    label: String,
) {
    if (directory.exists() && !directory.isDirectory) {
        throw AnatelBasicPlanCatalogFileException("The $label is not a directory.")
    }
    if (!directory.exists() && !directory.mkdirs()) {
        throw AnatelBasicPlanCatalogFileException("The $label could not be created.")
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
}

private val CATALOG_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private val RAW_ARCHIVE_FILE = Regex("canais-[0-9a-f]{64}\\.zip")
private const val RAW_PROVENANCE_SCHEMA_VERSION = 1
private const val CURRENT_POINTER_SCHEMA_VERSION = 1
private const val MAX_METADATA_BYTES = 1 * 1024 * 1024
private const val MAX_POINTER_BYTES = 64 * 1024
private const val MAX_RAW_ARCHIVES = 8
private const val MAX_TOTAL_RAW_BYTES = 512L * 1024L * 1024L
