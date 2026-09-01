package com.gecesars.atxplan.data.regulatory

import android.os.StatFs
import android.system.Os
import com.gecesars.atxplan.data.dataset.RegionalHttpRequest
import com.gecesars.atxplan.data.dataset.RegionalHttpRequestMethod
import com.gecesars.atxplan.data.dataset.RegionalHttpResponse
import com.gecesars.atxplan.data.dataset.RegionalHttpTransport
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RegulatoryArtifactPhase {
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    PROCESSING,
}

data class RegulatoryArtifactProgress(
    val phase: RegulatoryArtifactPhase,
    val label: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    init {
        require(label.isNotBlank() && completedBytes >= 0L && totalBytes >= 0L)
        require(totalBytes == 0L || completedBytes <= totalBytes)
    }
}

data class VerifiedRemoteArtifact(
    val file: File,
    val sourceUrl: String,
    val etag: String,
    val lastModified: String?,
    val byteCount: Long,
    val sha256: String,
)

/** Resumable, atomic, private-storage acquisition for one fixed official source artifact. */
internal class VerifiedRemoteArtifactStore(
    private val root: File,
    private val transport: RegionalHttpTransport,
    private val availableBytes: (File) -> Long = { directory -> StatFs(directory.absolutePath).availableBytes },
) {
    fun acquire(
        key: String,
        url: String,
        extension: String,
        maximumBytes: Long,
        progressLabel: String,
        onProgress: (RegulatoryArtifactProgress) -> Unit = {},
    ): VerifiedRemoteArtifact {
        require(SAFE_KEY.matches(key) && SAFE_EXTENSION.matches(extension)) {
            "The regulatory artifact cache identity is invalid."
        }
        require(url.startsWith("https://") && maximumBytes in 1L..MAXIMUM_ARTIFACT_BYTES) {
            "The regulatory artifact source policy is invalid."
        }
        val directory = File(root, key)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Private regulatory artifact storage could not be created.")
        }
        onProgress(RegulatoryArtifactProgress(RegulatoryArtifactPhase.CHECKING, progressLabel))
        val remote = probe(url, maximumBytes)
        val manifestFile = File(directory, MANIFEST_FILE)
        val installed = readManifest(manifestFile)?.takeIf { manifest ->
            manifest.schema == MANIFEST_SCHEMA && manifest.url == url && manifest.etag == remote.etag &&
                manifest.byteCount == remote.byteCount && SAFE_ARTIFACT_FILE.matches(manifest.fileName) &&
                SHA256.matches(manifest.sha256)
        }
        if (installed != null) {
            val file = File(directory, installed.fileName)
            onProgress(
                RegulatoryArtifactProgress(
                    RegulatoryArtifactPhase.VERIFYING,
                    progressLabel,
                    installed.byteCount,
                    installed.byteCount,
                ),
            )
            if (file.isFile && file.length() == installed.byteCount && sha256(file) == installed.sha256) {
                return installed.toArtifact(file)
            }
        }

        val partial = File(directory, PARTIAL_FILE)
        val partialManifestFile = File(directory, PARTIAL_MANIFEST_FILE)
        val partialManifest = readPartialManifest(partialManifestFile)
        val reusablePartial = partialManifest?.let { value ->
            value.schema == PARTIAL_MANIFEST_SCHEMA && value.url == url && value.etag == remote.etag &&
                value.byteCount == remote.byteCount && partial.isFile &&
                partial.length() in 0L until remote.byteCount
        } == true
        if (!reusablePartial) {
            partial.delete()
            partialManifestFile.delete()
            writePartialManifest(
                partialManifestFile,
                PartialArtifactManifest(
                    PARTIAL_MANIFEST_SCHEMA,
                    url,
                    remote.etag,
                    remote.lastModified,
                    remote.byteCount,
                ),
            )
        }
        val start = partial.length().takeIf { reusablePartial } ?: 0L
        val requiredFree = remote.byteCount - start + INSTALL_SAFETY_BYTES
        if (availableBytes(directory) < requiredFree) {
            throw IOException(
                "The $progressLabel download needs at least ${formatBytes(requiredFree)} of free private storage.",
            )
        }
        onProgress(
            RegulatoryArtifactProgress(
                RegulatoryArtifactPhase.DOWNLOADING,
                progressLabel,
                start,
                remote.byteCount,
            ),
        )
        downloadRemainder(partial, remote, start, progressLabel, onProgress)
        onProgress(
            RegulatoryArtifactProgress(
                RegulatoryArtifactPhase.VERIFYING,
                progressLabel,
                remote.byteCount,
                remote.byteCount,
            ),
        )
        if (partial.length() != remote.byteCount) {
            throw IOException("The $progressLabel artifact ended at an unexpected byte count.")
        }
        val digest = sha256(partial)
        val target = File(directory, "$digest.$extension")
        Os.rename(partial.absolutePath, target.absolutePath)
        partialManifestFile.delete()
        val manifest = InstalledArtifactManifest(
            schema = MANIFEST_SCHEMA,
            url = url,
            etag = remote.etag,
            lastModified = remote.lastModified,
            byteCount = remote.byteCount,
            sha256 = digest,
            fileName = target.name,
        )
        writeManifest(manifestFile, manifest)
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && SAFE_ARTIFACT_FILE.matches(file.name) && file.name != target.name
        }.forEach(File::delete)
        return manifest.toArtifact(target)
    }

    private fun probe(url: String, maximumBytes: Long): RemoteIdentity {
        transport.execute(
            RegionalHttpRequest(
                url = url,
                method = RegionalHttpRequestMethod.GET,
                rangeStart = 0L,
                rangeEndInclusive = 0L,
            ),
        ).use { response ->
            if (response.statusCode != 206) {
                throw IOException("The official source did not honor a bounded probe (${response.statusCode}).")
            }
            val range = parseContentRange(response, 0L, 0L)
            if (range.total !in 1L..maximumBytes) {
                throw IOException("The official source artifact exceeds its approved byte bound.")
            }
            val etag = response.etag?.takeIf { it.isNotBlank() && !it.startsWith("W/") }
                ?: throw IOException("The official source did not provide a strong ETag.")
            if (response.body.read() < 0 || response.body.read() >= 0) {
                throw IOException("The official source probe body did not contain exactly one byte.")
            }
            return RemoteIdentity(url, etag, response.lastModified, range.total)
        }
    }

    private fun downloadRemainder(
        partial: File,
        remote: RemoteIdentity,
        start: Long,
        progressLabel: String,
        onProgress: (RegulatoryArtifactProgress) -> Unit,
    ) {
        transport.execute(
            RegionalHttpRequest(
                url = remote.url,
                method = RegionalHttpRequestMethod.GET,
                rangeStart = start,
                rangeEndInclusive = remote.byteCount - 1L,
                ifRangeEtag = remote.etag,
            ),
        ).use { response ->
            if (response.statusCode == 200) {
                throw IOException("The official source changed during a resumed download. Retry the operation.")
            }
            val range = parseContentRange(response, start, remote.byteCount - 1L)
            if (range.total != remote.byteCount || response.etag != null && response.etag != remote.etag) {
                throw IOException("The official source identity changed during download.")
            }
            FileOutputStream(partial, start > 0L).use { output ->
                val buffer = ByteArray(128 * 1024)
                var completed = start
                var nextProgress = start + PROGRESS_INTERVAL_BYTES
                while (true) {
                    val read = response.body.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    completed += read
                    if (completed > remote.byteCount) {
                        throw IOException("The official source exceeded its declared byte count.")
                    }
                    output.write(buffer, 0, read)
                    if (completed >= nextProgress || completed == remote.byteCount) {
                        onProgress(
                            RegulatoryArtifactProgress(
                                RegulatoryArtifactPhase.DOWNLOADING,
                                progressLabel,
                                completed,
                                remote.byteCount,
                            ),
                        )
                        nextProgress = completed + PROGRESS_INTERVAL_BYTES
                    }
                }
                output.fd.sync()
                if (completed != remote.byteCount) {
                    throw IOException("The official source download ended before Content-Range.")
                }
            }
        }
    }

    private fun parseContentRange(
        response: RegionalHttpResponse,
        expectedStart: Long,
        expectedEnd: Long,
    ): ArtifactContentRange {
        val match = response.contentRange?.let(CONTENT_RANGE::matchEntire)
            ?: throw IOException("The official source response omitted a valid Content-Range header.")
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        if (start != expectedStart || end != expectedEnd || total == null || total <= end) {
            throw IOException("The official source Content-Range did not match the request.")
        }
        val count = end - start + 1L
        if (response.contentLength != null && response.contentLength != count) {
            throw IOException("The official source response length conflicts with Content-Range.")
        }
        return ArtifactContentRange(start, end, total)
    }

    private fun readManifest(file: File): InstalledArtifactManifest? = readBoundedJson(file) { text ->
        ARTIFACT_JSON.decodeFromString<InstalledArtifactManifest>(text)
    }

    private fun readPartialManifest(file: File): PartialArtifactManifest? = readBoundedJson(file) { text ->
        ARTIFACT_JSON.decodeFromString<PartialArtifactManifest>(text)
    }

    private fun <T> readBoundedJson(file: File, decode: (String) -> T): T? {
        if (!file.isFile || file.length() !in 1L..MAXIMUM_MANIFEST_BYTES) return null
        return runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeManifest(file: File, value: InstalledArtifactManifest) =
        writeJsonAtomically(file, ARTIFACT_JSON.encodeToString(value))

    private fun writePartialManifest(file: File, value: PartialArtifactManifest) =
        writeJsonAtomically(file, ARTIFACT_JSON.encodeToString(value))

    private fun writeJsonAtomically(file: File, text: String) {
        val staging = File.createTempFile(".artifact-", ".tmp", file.parentFile)
        try {
            FileOutputStream(staging).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(staging.absolutePath, file.absolutePath)
        } finally {
            staging.delete()
        }
    }
}

@Serializable
private data class InstalledArtifactManifest(
    val schema: Int,
    val url: String,
    val etag: String,
    val lastModified: String?,
    val byteCount: Long,
    val sha256: String,
    val fileName: String,
) {
    fun toArtifact(file: File) = VerifiedRemoteArtifact(
        file = file,
        sourceUrl = url,
        etag = etag,
        lastModified = lastModified,
        byteCount = byteCount,
        sha256 = sha256,
    )
}

@Serializable
private data class PartialArtifactManifest(
    val schema: Int,
    val url: String,
    val etag: String,
    val lastModified: String?,
    val byteCount: Long,
)

private data class RemoteIdentity(
    val url: String,
    val etag: String,
    val lastModified: String?,
    val byteCount: Long,
)

private data class ArtifactContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MANIFEST_SCHEMA = 1
private const val PARTIAL_MANIFEST_SCHEMA = 1
private const val MANIFEST_FILE = "artifact.json"
private const val PARTIAL_MANIFEST_FILE = "partial.json"
private const val PARTIAL_FILE = "artifact.partial"
private const val MAXIMUM_ARTIFACT_BYTES = 512L * 1024L * 1024L
private const val MAXIMUM_MANIFEST_BYTES = 64L * 1024L
private const val INSTALL_SAFETY_BYTES = 32L * 1024L * 1024L
private const val PROGRESS_INTERVAL_BYTES = 1L * 1024L * 1024L
private val SAFE_KEY = Regex("^[a-z0-9][a-z0-9._-]{0,95}$")
private val SAFE_EXTENSION = Regex("^[a-z0-9]{1,12}$")
private val SAFE_ARTIFACT_FILE = Regex("^[0-9a-f]{64}\\.[a-z0-9]{1,12}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
private val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")
private val ARTIFACT_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
}
