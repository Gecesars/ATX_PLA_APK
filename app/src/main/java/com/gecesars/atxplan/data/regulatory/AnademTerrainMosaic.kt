package com.gecesars.atxplan.data.regulatory

import android.system.Os
import android.os.StatFs
import com.gecesars.atxplan.data.dataset.AllowlistedHttpsRegionalHttpTransport
import com.gecesars.atxplan.data.dataset.Float32GeoTiffTerrainSource
import com.gecesars.atxplan.data.dataset.GeoTiffRandomAccessSource
import com.gecesars.atxplan.data.dataset.RegionalHttpRequest
import com.gecesars.atxplan.data.dataset.RegionalHttpRequestMethod
import com.gecesars.atxplan.data.dataset.RegionalHttpResponse
import com.gecesars.atxplan.data.dataset.RegionalHttpTransport
import com.gecesars.atxplan.domain.contour.RegulatoryTerrainArtifactProvenance
import com.gecesars.atxplan.domain.contour.RegulatoryTerrainIntegrityScope
import com.gecesars.atxplan.domain.contour.RegulatoryTerrainRangeCacheProvenance
import com.gecesars.atxplan.domain.contour.RegulatoryTerrainProvenance
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-demand ANADEM v1.0 mosaic backed by validated HTTP byte ranges.
 *
 * Source files are 0.6-2.1 GiB tiled GeoTIFFs. Downloading a complete MGRS tile on a phone is not
 * proportionate to a regional study, so only TIFF metadata and compressed 512 x 512 blocks that
 * are actually sampled are retained. Every cached response is tied to one strong ETag and exact
 * source length, and every local block is content-addressed by SHA-256.
 */
class AnademTerrainMosaic private constructor(
    private val sources: Map<String, AnademTerrainSource>,
) : AutoCloseable {
    fun elevationMeters(latitude: Double, longitude: Double): Double? {
        val key = anademTileKey(latitude, longitude) ?: return null
        return sources[key]?.elevationMeters(latitude, longitude)
    }

    fun provenance(): RegulatoryTerrainProvenance {
        val evidence = sources.values.sortedBy(AnademTerrainSource::tileKey)
        if (evidence.isEmpty()) throw IOException("No ANADEM terrain source covers the study bounds.")
        val primary = evidence.first().identityEvidence()
        val rangeEvidence = evidence.map(AnademTerrainSource::cacheEvidence)
        return RegulatoryTerrainProvenance(
            datasetId = ANADEM_DATASET_ID,
            datasetTitle = "ANADEM v1.0 South America Digital Terrain Model",
            dataType = "DIGITAL_TERRAIN_MODEL",
            relativePath = primary.relativePath,
            sha256 = primary.sha256,
            acquiredAt = null,
            sourceUrl = primary.artifactUrl,
            licenseTitle = "Freely available ANADEM v1.0 data; MIT-licensed source repository",
            attribution = "ANADEM v1.0 — UFRGS/IPH and Agência Nacional de Águas e Saneamento Básico (ANA)",
            nominalResolutionM = 30.0,
            sampleMethod = "nearest source pixel from exact HTTPS byte ranges; source identity uses URL, strong ETag, and byte count; each cached range is SHA-256 verified",
            integrityScope = RegulatoryTerrainIntegrityScope.SOURCE_IDENTITY_AND_RANGE_SHA256,
            integrityEvidenceDescription = "Each artifact SHA-256 identifies dataset ID, source URL, strong ETag, and source byte count. Each cached byte-range payload is content-addressed by SHA-256, and the bounded range manifest is hashed separately.",
            rangeCacheEvidence = rangeEvidence.map { item ->
                RegulatoryTerrainRangeCacheProvenance(
                    sourceId = item.tileKey,
                    sourceIdentitySha256 = item.sourceIdentitySha256,
                    rangeManifestSha256 = item.rangeManifestSha256,
                    cachedRangeCount = item.rangeCount,
                    cachedByteCount = item.cachedBytes,
                )
            },
            additionalArtifacts = evidence.drop(1).map(AnademTerrainSource::identityEvidence),
        )
    }

    fun cacheEvidence(): AnademRangeCacheEvidence {
        val snapshots = sources.values.sortedBy(AnademTerrainSource::tileKey)
            .map(AnademTerrainSource::cacheEvidence)
        return AnademRangeCacheEvidence(
            sourceCount = snapshots.size,
            cachedRangeCount = snapshots.sumOf(AnademSourceCacheEvidence::rangeCount),
            cachedByteCount = snapshots.sumOf(AnademSourceCacheEvidence::cachedBytes),
            sourceIdentitySha256 = sha256(
                snapshots.joinToString("\u0000") { item ->
                    "${item.tileKey}|${item.sourceIdentitySha256}|${item.rangeManifestSha256}"
                }.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    override fun close() {
        sources.values.toList().asReversed().forEach { source -> runCatching(source::close) }
    }

    companion object {
        fun open(
            bounds: RegionalBounds,
            cacheRoot: File,
            transport: RegionalHttpTransport = AllowlistedHttpsRegionalHttpTransport(
                allowedHosts = setOf(ANADEM_HOST),
                readTimeoutMillis = 120_000,
            ),
        ): AnademTerrainMosaic {
            val root = File(cacheRoot, "anadem-v1-range-cache")
            if (!root.isDirectory && !root.mkdirs()) {
                throw IOException("Private ANADEM range-cache storage could not be created.")
            }
            val opened = linkedMapOf<String, AnademTerrainSource>()
            try {
                anademTileKeys(bounds).forEach { key ->
                    try {
                        opened[key] = AnademTerrainSource(key, File(root, key.lowercase()), transport)
                    } catch (error: AnademSourceUnavailableException) {
                        // ANADEM publishes only land-intersecting MGRS tiles. Missing ocean-only
                        // combinations remain NoData instead of aborting a neighboring land tile.
                    }
                }
                if (opened.isEmpty()) throw IOException("ANADEM has no published tile for the study bounds.")
                return AnademTerrainMosaic(opened)
            } catch (error: Exception) {
                opened.values.toList().asReversed().forEach { source -> runCatching(source::close) }
                throw error
            }
        }
    }
}

data class AnademRangeCacheEvidence(
    val sourceCount: Int,
    val cachedRangeCount: Int,
    val cachedByteCount: Long,
    val sourceIdentitySha256: String,
)

private class AnademTerrainSource(
    val tileKey: String,
    cacheDirectory: File,
    transport: RegionalHttpTransport,
) : AutoCloseable {
    private val byteSource = AnademHttpRangeByteSource(
        tileKey = tileKey,
        url = "$ANADEM_TILE_BASE_URL/anadem_v1_$tileKey.tif",
        cacheDirectory = cacheDirectory,
        transport = transport,
    )
    private val terrain = Float32GeoTiffTerrainSource(
        source = byteSource,
        maximumSourceBytes = MAXIMUM_ANADEM_SOURCE_BYTES,
        maximumCachedTiles = 8,
    )

    fun elevationMeters(latitude: Double, longitude: Double): Double? =
        terrain.elevationMeters(latitude, longitude)

    fun identityEvidence(): RegulatoryTerrainArtifactProvenance =
        RegulatoryTerrainArtifactProvenance(
            relativePath = "regulatory/anadem-v1/${tileKey.lowercase()}/ranges.json",
            sha256 = byteSource.sourceIdentitySha256,
            acquiredAt = null,
            artifactUrl = byteSource.url,
        )

    fun cacheEvidence(): AnademSourceCacheEvidence = byteSource.cacheEvidence()

    override fun close() = terrain.close()
}

private class AnademHttpRangeByteSource(
    private val tileKey: String,
    val url: String,
    private val cacheDirectory: File,
    private val transport: RegionalHttpTransport,
) : GeoTiffRandomAccessSource {
    private var manifest: AnademRangeManifest
    private val verifiedCacheFiles = hashSetOf<String>()
    private var closed = false

    override val length: Long
        get() = manifest.sourceBytes

    val sourceIdentitySha256: String
        get() = sha256(
            "$ANADEM_DATASET_ID\u0000$url\u0000${manifest.etag}\u0000${manifest.sourceBytes}"
                .toByteArray(Charsets.UTF_8),
        )

    init {
        require(ANADEM_TILE_KEY_PATTERN.matches(tileKey)) { "The ANADEM tile key is invalid." }
        if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
            throw IOException("Private ANADEM tile-cache storage could not be created.")
        }
        val prior = readManifest()
        manifest = initializeSource(prior)
    }

    @Synchronized
    override fun readFully(offset: Long, destination: ByteArray) {
        check(!closed) { "The ANADEM terrain source is closed." }
        requireRange(offset, destination.size.toLong(), length)
        if (destination.isEmpty()) return
        val end = offset + destination.size - 1L
        var record = manifest.ranges.firstOrNull { it.start <= offset && it.endInclusive >= end }
        if (record != null && !verifyCachedRecord(record)) {
            manifest = manifest.copy(ranges = manifest.ranges - record)
            writeManifest(manifest)
            record = null
        }
        if (record != null) {
            manifest = manifest.copy(ranges = manifest.ranges.filterNot { it == record } + record)
        }
        val resolved = record ?: downloadRange(
            start = if (destination.size <= SMALL_READ_THRESHOLD_BYTES) {
                offset.floorToPage(METADATA_PAGE_BYTES)
            } else {
                offset
            },
            endInclusive = if (destination.size <= SMALL_READ_THRESHOLD_BYTES) {
                minOf(length - 1L, offset.floorToPage(METADATA_PAGE_BYTES) + METADATA_PAGE_BYTES - 1L)
            } else {
                end
            },
        )
        val file = File(cacheDirectory, resolved.fileName)
        FileInputStream(file).use { input ->
            val skip = offset - resolved.start
            input.channel.position(skip)
            var cursor = 0
            while (cursor < destination.size) {
                val read = input.read(destination, cursor, destination.size - cursor)
                if (read < 0) throw IOException("A cached ANADEM range ended unexpectedly.")
                if (read > 0) cursor += read
            }
        }
    }

    fun cacheEvidence(): AnademSourceCacheEvidence {
        val canonical = RANGE_JSON.encodeToString(
            manifest.copy(ranges = manifest.ranges.sortedBy(AnademCachedRange::start)),
        ).toByteArray(Charsets.UTF_8)
        return AnademSourceCacheEvidence(
            tileKey = tileKey,
            sourceIdentitySha256 = sourceIdentitySha256,
            rangeManifestSha256 = sha256(canonical),
            rangeCount = manifest.ranges.size,
            cachedBytes = manifest.ranges.sumOf { it.endInclusive - it.start + 1L },
        )
    }

    override fun close() {
        runCatching { writeManifest(manifest) }
        closed = true
        verifiedCacheFiles.clear()
    }

    private fun initializeSource(prior: AnademRangeManifest?): AnademRangeManifest {
        val response = executeRange(0L, METADATA_PAGE_BYTES - 1L, prior?.etag)
        response.use {
            if (it.statusCode == 404) throw AnademSourceUnavailableException(tileKey)
            if (prior != null && it.statusCode == 200) {
                clearKnownCache(prior)
                return initializeSource(null)
            }
            val range = validatePartialResponse(it, 0L, METADATA_PAGE_BYTES - 1L)
            val etag = it.etag?.takeIf { value -> value.isNotBlank() && !value.startsWith("W/") }
                ?: throw IOException("The ANADEM source did not provide a strong ETag.")
            if (prior != null && (prior.url != url || prior.etag != etag || prior.sourceBytes != range.total)) {
                clearKnownCache(prior)
            }
            val base = prior?.takeIf { old ->
                old.schema == RANGE_MANIFEST_SCHEMA && old.url == url && old.etag == etag &&
                    old.sourceBytes == range.total
            } ?: AnademRangeManifest(
                schema = RANGE_MANIFEST_SCHEMA,
                tileKey = tileKey,
                url = url,
                etag = etag,
                sourceBytes = range.total,
                lastModified = it.lastModified,
                ranges = emptyList(),
            )
            val payload = readExactBody(it, range.count)
            return commitRange(base, range.start, range.endInclusive, payload)
        }
    }

    private fun downloadRange(start: Long, endInclusive: Long): AnademCachedRange {
        val response = executeRange(start, endInclusive, manifest.etag)
        response.use {
            if (it.statusCode == 200) {
                throw IOException("The ANADEM source changed while cached blocks were in use. Retry the study.")
            }
            val range = validatePartialResponse(it, start, endInclusive)
            if (it.etag != null && it.etag != manifest.etag) {
                throw IOException("The ANADEM ETag changed during a range session. Retry the study.")
            }
            commitRange(manifest, range.start, range.endInclusive, readExactBody(it, range.count))
            return manifest.ranges.first { cached ->
                cached.start == range.start && cached.endInclusive == range.endInclusive
            }
        }
    }

    private fun executeRange(start: Long, endInclusive: Long, ifRange: String?): RegionalHttpResponse =
        transport.execute(
            RegionalHttpRequest(
                url = url,
                method = RegionalHttpRequestMethod.GET,
                rangeStart = start,
                rangeEndInclusive = endInclusive,
                ifRangeEtag = ifRange,
                accept = "image/tiff, application/octet-stream;q=0.9",
            ),
        )

    private fun validatePartialResponse(
        response: RegionalHttpResponse,
        requestedStart: Long,
        requestedEnd: Long,
    ): ParsedContentRange {
        if (response.statusCode != 206) {
            throw IOException("The ANADEM server did not honor a bounded byte-range request (${response.statusCode}).")
        }
        val parsed = response.contentRange?.let(CONTENT_RANGE_PATTERN::matchEntire)
            ?: throw IOException("The ANADEM response omitted a valid Content-Range header.")
        val start = parsed.groupValues[1].toLongOrNull()
        val end = parsed.groupValues[2].toLongOrNull()
        val total = parsed.groupValues[3].toLongOrNull()
        if (
            start != requestedStart || end != requestedEnd || total == null ||
            total !in MINIMUM_ANADEM_SOURCE_BYTES..MAXIMUM_ANADEM_SOURCE_BYTES || end < start
        ) {
            throw IOException("The ANADEM Content-Range does not match the requested bounded range.")
        }
        val count = end - start + 1L
        if (response.contentLength != null && response.contentLength != count) {
            throw IOException("The ANADEM response length conflicts with Content-Range.")
        }
        return ParsedContentRange(start, end, total, count)
    }

    private fun readExactBody(response: RegionalHttpResponse, expectedBytes: Long): ByteArray {
        if (expectedBytes !in 1L..MAXIMUM_SINGLE_RANGE_BYTES.toLong()) {
            throw IOException("The requested ANADEM byte range exceeds the approved bound.")
        }
        val output = ByteArrayOutputStream(expectedBytes.toInt())
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = response.body.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > expectedBytes) throw IOException("The ANADEM response exceeded Content-Range.")
            output.write(buffer, 0, read)
        }
        if (total != expectedBytes) throw IOException("The ANADEM response ended before Content-Range.")
        return output.toByteArray()
    }

    private fun commitRange(
        base: AnademRangeManifest,
        start: Long,
        endInclusive: Long,
        payload: ByteArray,
    ): AnademRangeManifest {
        val digest = sha256(payload)
        val fileName = "$digest.range"
        val target = File(cacheDirectory, fileName)
        if (!target.isFile || target.length() != payload.size.toLong() || sha256(target) != digest) {
            if (StatFs(cacheDirectory.absolutePath).availableBytes < payload.size + CACHE_WRITE_SAFETY_BYTES) {
                throw IOException("Private storage is insufficient for the next ANADEM range block.")
            }
            val staging = File.createTempFile(".range-", ".tmp", cacheDirectory)
            try {
                FileOutputStream(staging).use { output ->
                    output.write(payload)
                    output.fd.sync()
                }
                Os.rename(staging.absolutePath, target.absolutePath)
            } finally {
                staging.delete()
            }
        }
        verifiedCacheFiles += fileName
        val record = AnademCachedRange(start, endInclusive, fileName, digest)
        val retained = (base.ranges.filterNot { old ->
            old.start == start && old.endInclusive == endInclusive
        } + record).toMutableList()
        var retainedBytes = retained.sumOf { item -> item.endInclusive - item.start + 1L }
        val evicted = mutableListOf<AnademCachedRange>()
        while (
            retained.size > MAXIMUM_CACHED_RANGE_RECORDS ||
            retainedBytes > MAXIMUM_CACHED_RANGE_BYTES
        ) {
            val removed = retained.removeAt(0)
            retainedBytes -= removed.endInclusive - removed.start + 1L
            evicted += removed
        }
        val updated = base.copy(ranges = retained)
        manifest = updated
        writeManifest(updated)
        val retainedFiles = retained.mapTo(hashSetOf(), AnademCachedRange::fileName)
        evicted.map(AnademCachedRange::fileName).distinct().forEach { evictedName ->
            if (evictedName !in retainedFiles && SAFE_RANGE_FILE_PATTERN.matches(evictedName)) {
                File(cacheDirectory, evictedName).delete()
                verifiedCacheFiles.remove(evictedName)
            }
        }
        return updated
    }

    private fun verifyCachedRecord(record: AnademCachedRange): Boolean {
        if (!SHA256_PATTERN.matches(record.sha256) || record.fileName != "${record.sha256}.range") return false
        if (record.fileName in verifiedCacheFiles) return true
        val file = File(cacheDirectory, record.fileName)
        val expected = record.endInclusive - record.start + 1L
        val valid = file.isFile && file.length() == expected && sha256(file) == record.sha256
        if (valid) verifiedCacheFiles += record.fileName
        return valid
    }

    private fun readManifest(): AnademRangeManifest? {
        val file = File(cacheDirectory, RANGE_MANIFEST_FILE)
        if (!file.isFile || file.length() !in 1L..MAXIMUM_MANIFEST_BYTES) return null
        return runCatching {
            RANGE_JSON.decodeFromString<AnademRangeManifest>(file.readText(Charsets.UTF_8))
                .takeIf { decoded -> decoded.isValid() }
        }.getOrNull()
    }

    private fun writeManifest(value: AnademRangeManifest) {
        val target = File(cacheDirectory, RANGE_MANIFEST_FILE)
        val staging = File.createTempFile(".ranges-", ".tmp", cacheDirectory)
        try {
            FileOutputStream(staging).use { output ->
                output.write(RANGE_JSON.encodeToString(value).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(staging.absolutePath, target.absolutePath)
        } finally {
            staging.delete()
        }
    }

    private fun clearKnownCache(prior: AnademRangeManifest) {
        prior.ranges.map(AnademCachedRange::fileName).distinct().forEach { name ->
            if (SAFE_RANGE_FILE_PATTERN.matches(name)) File(cacheDirectory, name).delete()
        }
        File(cacheDirectory, RANGE_MANIFEST_FILE).delete()
        verifiedCacheFiles.clear()
    }

    private fun AnademRangeManifest.isValid(): Boolean =
        schema == RANGE_MANIFEST_SCHEMA && tileKey == this@AnademHttpRangeByteSource.tileKey &&
            url == this@AnademHttpRangeByteSource.url && etag.isNotBlank() && !etag.startsWith("W/") &&
            sourceBytes in MINIMUM_ANADEM_SOURCE_BYTES..MAXIMUM_ANADEM_SOURCE_BYTES &&
            ranges.size <= MAXIMUM_CACHED_RANGE_RECORDS && ranges.all { range ->
                range.start >= 0L && range.endInclusive >= range.start &&
                    range.endInclusive < sourceBytes && SAFE_RANGE_FILE_PATTERN.matches(range.fileName) &&
                    SHA256_PATTERN.matches(range.sha256)
            }
}

data class AnademSourceCacheEvidence(
    val tileKey: String,
    val sourceIdentitySha256: String,
    val rangeManifestSha256: String,
    val rangeCount: Int,
    val cachedBytes: Long,
)

@Serializable
private data class AnademRangeManifest(
    val schema: Int,
    val tileKey: String,
    val url: String,
    val etag: String,
    val sourceBytes: Long,
    val lastModified: String?,
    val ranges: List<AnademCachedRange>,
)

@Serializable
private data class AnademCachedRange(
    val start: Long,
    val endInclusive: Long,
    val fileName: String,
    val sha256: String,
)

private data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
    val count: Long,
)

private class AnademSourceUnavailableException(tileKey: String) :
    IOException("ANADEM tile $tileKey is not published.")

internal fun anademTileKey(latitude: Double, longitude: Double): String? {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -80.0..84.0 || longitude !in -180.0..180.0) {
        return null
    }
    val zone = if (longitude == 180.0) 60 else ((longitude + 180.0) / 6.0).toInt() + 1
    val bandIndex = ((latitude + 80.0) / 8.0).toInt().coerceAtMost(MGRS_BANDS.lastIndex)
    return String.format(Locale.ROOT, "%02d%c", zone, MGRS_BANDS[bandIndex])
}

internal fun anademTileKeys(bounds: RegionalBounds): List<String> {
    val keys = linkedSetOf<String>()
    val longitudeStep = 1.0
    val latitudeStep = 1.0
    var latitude = bounds.south
    while (latitude < bounds.north) {
        var longitude = bounds.west
        while (longitude < bounds.east) {
            anademTileKey(latitude, longitude)?.let(keys::add)
            longitude = minOf(bounds.east, longitude + longitudeStep)
        }
        latitude = minOf(bounds.north, latitude + latitudeStep)
    }
    anademTileKey(bounds.north - 1e-9, bounds.east - 1e-9)?.let(keys::add)
    return keys.sorted()
}

private fun Long.floorToPage(pageBytes: Long): Long = this / pageBytes * pageBytes

private fun requireRange(offset: Long, count: Long, length: Long) {
    if (offset < 0L || count < 0L || offset > length || count > length - offset) {
        throw IOException("An ANADEM byte range is outside the source file.")
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
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

private const val ANADEM_DATASET_ID = "anadem-v1.0-south-america"
private const val ANADEM_HOST = "metadados.snirh.gov.br"
private const val ANADEM_TILE_BASE_URL = "https://metadados.snirh.gov.br/files/anadem_v1_tiles"
private const val RANGE_MANIFEST_SCHEMA = 1
private const val RANGE_MANIFEST_FILE = "ranges.json"
private const val METADATA_PAGE_BYTES = 64L * 1024L
private const val SMALL_READ_THRESHOLD_BYTES = 64 * 1024
private const val MAXIMUM_SINGLE_RANGE_BYTES = 16 * 1024 * 1024
private const val MINIMUM_ANADEM_SOURCE_BYTES = 8L
private const val MAXIMUM_ANADEM_SOURCE_BYTES = 3L * 1024L * 1024L * 1024L
private const val MAXIMUM_CACHED_RANGE_RECORDS = 16_384
private const val MAXIMUM_CACHED_RANGE_BYTES = 512L * 1024L * 1024L
private const val CACHE_WRITE_SAFETY_BYTES = 32L * 1024L * 1024L
private const val MAXIMUM_MANIFEST_BYTES = 4L * 1024L * 1024L
private const val MGRS_BANDS = "CDEFGHJKLMNPQRSTUVWX"
private val ANADEM_TILE_KEY_PATTERN = Regex("^[0-9]{2}[C-HJ-NP-X]$")
private val CONTENT_RANGE_PATTERN = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val SAFE_RANGE_FILE_PATTERN = Regex("^[0-9a-f]{64}\\.range$")
private val RANGE_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
}
