package com.gecesars.atxplan.data.antenna

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/** A fully verified export recovered from the app-private prepared-export cache. */
internal data class PreparedAntennaExport(
    val token: String,
    val patternId: String,
    val formatName: String,
    val suggestedFileName: String,
    val payload: ByteArray,
    val sha256: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val warnings: List<String>,
)

/**
 * Short-lived, bounded storage for prepared antenna exports.
 *
 * Each token is one immutable binary envelope. The envelope is written to a same-directory
 * staging file, synced, read back, and renamed into place. Reads do not consume an entry: callers
 * remove it only after an explicit dismissal or a verified successful export.
 */
internal class PreparedAntennaExportCache(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    init {
        require(ttlMillis in 1..DEFAULT_TTL_MILLIS) {
            "The prepared-export lifetime must be between 1 millisecond and 1 hour."
        }
        require(maxEntries in 1..DEFAULT_MAX_ENTRIES) {
            "The prepared-export entry limit must be between 1 and $DEFAULT_MAX_ENTRIES."
        }
        require(maxTotalBytes in 1..DEFAULT_MAX_TOTAL_BYTES) {
            "The prepared-export cache limit must be between 1 byte and 64 MiB."
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun store(
        patternId: String,
        formatName: String,
        suggestedFileName: String,
        payload: ByteArray,
        warnings: List<String>,
    ): PreparedAntennaExport {
        validatePatternId(patternId)
        validateFormatName(formatName)
        validateSuggestedFileName(suggestedFileName)
        validatePayload(payload)
        validateWarnings(warnings)

        val now = currentTime()
        if (now > Long.MAX_VALUE - ttlMillis) {
            throw IOException("The prepared-export expiry timestamp overflowed.")
        }
        val expiresAt = now + ttlMillis
        val root = requirePrivateDirectory()
        val snapshot = cleanupAndSnapshot(root, now)
        val token = createUniqueToken(root)
        val record = PreparedAntennaExport(
            token = token,
            patternId = patternId,
            formatName = formatName,
            suggestedFileName = suggestedFileName,
            payload = payload.copyOf(),
            sha256 = payload.sha256(),
            createdAtEpochMillis = now,
            expiresAtEpochMillis = expiresAt,
            warnings = warnings.toList(),
        )
        val envelopeBytes = envelopeByteCount(record)
        if (snapshot.entryCount >= maxEntries || envelopeBytes > maxTotalBytes - snapshot.totalBytes) {
            throw IOException("The prepared-export cache is full; no valid entry was removed.")
        }

        val staging = createStagingFile(root, token)
        val target = publishedFile(root, token, requireOrdinaryFile = false)
        var publishedByThisStore = false
        try {
            writeEnvelope(staging, record)
            if (staging.length() != envelopeBytes) {
                throw IOException("The staged prepared-export envelope has an unexpected length.")
            }
            val staged = readEnvelope(staging, token, now)
                ?: throw IOException("The staged prepared-export envelope failed verification.")
            if (!staged.sameContentAs(record)) {
                throw IOException("The staged prepared-export envelope changed during verification.")
            }
            if (target.exists()) {
                throw IOException("A prepared-export token collision prevented publication.")
            }
            if (!staging.renameTo(target)) {
                throw IOException("The prepared antenna export could not be published atomically.")
            }
            publishedByThisStore = true

            val published = readEnvelope(target, token, now)
                ?: throw IOException("The published prepared-export envelope failed verification.")
            if (!published.sameContentAs(record)) {
                throw IOException("The published prepared-export envelope changed during verification.")
            }
            val finalSnapshot = snapshot(root, now, deleteInvalid = false)
            if (
                finalSnapshot.entryCount > maxEntries ||
                finalSnapshot.totalBytes > maxTotalBytes
            ) {
                target.delete()
                throw IOException("Concurrent cache activity exceeded the prepared-export bounds.")
            }
            return published.detachedCopy()
        } catch (error: Exception) {
            if (publishedByThisStore) target.delete()
            if (error is IOException) throw error
            throw IOException("The prepared antenna export could not be cached.", error)
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    /** Returns verified bytes without consuming the entry, or null for every unsafe/missing case. */
    @Synchronized
    fun load(token: String): PreparedAntennaExport? {
        if (!TOKEN_PATTERN.matches(token)) return null
        val now = runCatching(::currentTime).getOrNull() ?: return null
        val root = runCatching(::requirePrivateDirectory).getOrNull() ?: return null
        val target = runCatching {
            publishedFile(root, token, requireOrdinaryFile = true)
        }.getOrNull() ?: return null
        val record = readEnvelope(target, token, now)
        if (record == null) {
            target.delete()
            return null
        }
        return record.detachedCopy()
    }

    /** Removes one validly addressed entry after dismissal or a verified successful export. */
    @Synchronized
    fun remove(token: String): Boolean {
        if (!TOKEN_PATTERN.matches(token)) return false
        val root = runCatching(::requirePrivateDirectory).getOrNull() ?: return false
        val target = File(root, "$token$PUBLISHED_SUFFIX").absoluteFile
        if (target.parentFile != root) return false
        return !target.exists() || target.delete()
    }

    /** Removes expired/corrupt cache-owned envelopes and abandoned staging files. */
    @Synchronized
    fun cleanup(): Int {
        val now = runCatching(::currentTime).getOrNull() ?: return 0
        val root = runCatching(::requirePrivateDirectory).getOrNull() ?: return 0
        return cleanupAndSnapshot(root, now).removedCount
    }

    private fun cleanupAndSnapshot(root: File, now: Long): CacheSnapshot =
        snapshot(root, now, deleteInvalid = true)

    private fun snapshot(
        root: File,
        now: Long,
        deleteInvalid: Boolean,
    ): CacheSnapshot {
        var removedCount = 0
        var totalBytes = 0L
        var entryCount = 0
        val files = root.listFiles()
            ?: throw IOException("The private prepared-export directory could not be listed.")
        for (file in files) {
            val token = PUBLISHED_FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)
            if (token != null) {
                val record = readEnvelope(file, token, now)
                if (record == null) {
                    if (deleteInvalid && file.delete()) removedCount += 1
                } else {
                    val length = file.length()
                    if (totalBytes > Long.MAX_VALUE - length) {
                        throw IOException("The prepared-export cache size overflowed.")
                    }
                    totalBytes += length
                    entryCount += 1
                }
                continue
            }
            if (
                deleteInvalid &&
                (file.name.endsWith(PUBLISHED_SUFFIX) || isAbandonedStagingFile(file, now)) &&
                file.delete()
            ) {
                removedCount += 1
            }
        }
        return CacheSnapshot(entryCount, totalBytes, removedCount)
    }

    private fun isAbandonedStagingFile(file: File, now: Long): Boolean {
        if (!STAGING_FILE_PATTERN.matches(file.name)) return false
        val modifiedAt = file.lastModified()
        return modifiedAt <= 0L || modifiedAt <= now - ttlMillis
    }

    private fun createUniqueToken(root: File): String {
        repeat(MAXIMUM_TOKEN_ATTEMPTS) {
            val token = randomToken()
            if (!File(root, "$token$PUBLISHED_SUFFIX").exists()) return token
        }
        throw IOException("A unique prepared-export token could not be generated.")
    }

    private fun createStagingFile(root: File, token: String): File {
        repeat(MAXIMUM_TOKEN_ATTEMPTS) {
            val nonce = randomToken().take(STAGING_NONCE_CHARACTERS)
            val staging = File(root, "$STAGING_PREFIX$token-$nonce$STAGING_SUFFIX").absoluteFile
            if (staging.parentFile != root) {
                throw IOException("A prepared-export staging path escaped its private directory.")
            }
            if (staging.createNewFile()) {
                if (staging.canonicalFile != staging) {
                    staging.delete()
                    throw IOException("A prepared-export staging file cannot be a symbolic path.")
                }
                return staging
            }
        }
        throw IOException("A prepared-export staging file could not be created.")
    }

    private fun publishedFile(
        root: File,
        token: String,
        requireOrdinaryFile: Boolean,
    ): File {
        if (!TOKEN_PATTERN.matches(token)) {
            throw IOException("The prepared-export token is invalid.")
        }
        val target = File(root, "$token$PUBLISHED_SUFFIX").absoluteFile
        if (target.parentFile != root || target.canonicalFile != target) {
            throw IOException("A prepared-export path escaped its private directory or is symbolic.")
        }
        if (requireOrdinaryFile && !target.isFile) {
            throw IOException("The prepared-export envelope is unavailable.")
        }
        return target
    }

    private fun requirePrivateDirectory(): File {
        val absolute = directory.absoluteFile
        if (!absolute.isDirectory && !absolute.mkdirs()) {
            throw IOException("The private prepared-export directory could not be created.")
        }
        val canonical = absolute.canonicalFile
        if (canonical != absolute || !canonical.isDirectory) {
            throw IOException("The private prepared-export directory cannot be a symbolic path.")
        }
        return canonical
    }

    private fun writeEnvelope(file: File, record: PreparedAntennaExport) {
        val fileOutput = FileOutputStream(file, false)
        try {
            DataOutputStream(BufferedOutputStream(fileOutput)).use { output ->
                output.writeInt(ENVELOPE_MAGIC)
                output.writeInt(ENVELOPE_VERSION)
                output.writeBoundedString(record.token)
                output.writeBoundedString(record.patternId)
                output.writeBoundedString(record.formatName)
                output.writeBoundedString(record.suggestedFileName)
                output.writeBoundedString(record.sha256)
                output.writeLong(record.createdAtEpochMillis)
                output.writeLong(record.expiresAtEpochMillis)
                output.writeInt(record.payload.size)
                output.writeInt(record.warnings.size)
                record.warnings.forEach(output::writeBoundedString)
                output.write(record.payload)
                output.flush()
                fileOutput.fd.sync()
            }
        } catch (error: Exception) {
            runCatching(fileOutput::close)
            if (error is IOException) throw error
            throw IOException("The prepared-export staging file could not be synced.", error)
        }
    }

    private fun readEnvelope(
        file: File,
        expectedToken: String,
        now: Long,
    ): PreparedAntennaExport? {
        return try {
            if (!TOKEN_PATTERN.matches(expectedToken)) return null
            val root = requirePrivateDirectory()
            if (
                file.absoluteFile.parentFile != root ||
                file.canonicalFile != file.absoluteFile ||
                !file.isFile ||
                file.length() !in MINIMUM_ENVELOPE_BYTES..MAXIMUM_ENVELOPE_BYTES
            ) {
                return null
            }

            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != ENVELOPE_MAGIC || input.readInt() != ENVELOPE_VERSION) return null
                val budget = PreparedExportMetadataBudget()
                val token = input.readBoundedString(TOKEN_CHARACTERS, budget)
                val patternId = input.readBoundedString(MAXIMUM_PATTERN_ID_BYTES, budget)
                val formatName = input.readBoundedString(MAXIMUM_FORMAT_NAME_BYTES, budget)
                val suggestedFileName = input.readBoundedString(MAXIMUM_FILE_NAME_BYTES, budget)
                val sha256 = input.readBoundedString(SHA256_CHARACTERS, budget)
                val createdAt = input.readLong()
                val expiresAt = input.readLong()
                val payloadLength = input.readInt()
                val warningCount = input.readInt()

                if (token != expectedToken || !TOKEN_PATTERN.matches(token)) return null
                if (!isValidPatternId(patternId)) return null
                if (formatName !in SUPPORTED_FORMAT_NAMES) return null
                if (!isValidSuggestedFileName(suggestedFileName)) return null
                if (!SHA256_PATTERN.matches(sha256)) return null
                if (createdAt < 0L || createdAt > now) return null
                if (expiresAt <= createdAt || expiresAt - createdAt != ttlMillis) return null
                if (expiresAt <= now) return null
                if (payloadLength !in 1..AntennaPatternCodecLimits.MAX_INPUT_BYTES) return null
                if (warningCount !in 0..MAXIMUM_WARNING_COUNT) return null

                val warnings = ArrayList<String>(warningCount)
                repeat(warningCount) {
                    val warning = input.readBoundedString(MAXIMUM_WARNING_BYTES, budget)
                    if (!isValidWarning(warning)) return null
                    warnings += warning
                }
                val warningBytes = warnings.sumOf { it.utf8Size().toLong() }
                if (warningBytes > MAXIMUM_TOTAL_WARNING_BYTES) return null
                val expectedLength = envelopeByteCount(
                    token = token,
                    patternId = patternId,
                    formatName = formatName,
                    suggestedFileName = suggestedFileName,
                    sha256 = sha256,
                    warningBytes = warningBytes,
                    warningCount = warnings.size,
                    payloadLength = payloadLength,
                )
                if (file.length() != expectedLength) return null

                val payload = ByteArray(payloadLength)
                input.readFully(payload)
                if (input.read() != -1) return null
                if (payload.sha256() != sha256) return null
                PreparedAntennaExport(
                    token = token,
                    patternId = patternId,
                    formatName = formatName,
                    suggestedFileName = suggestedFileName,
                    payload = payload,
                    sha256 = sha256,
                    createdAtEpochMillis = createdAt,
                    expiresAtEpochMillis = expiresAt,
                    warnings = warnings,
                )
            }
        } catch (_: EOFException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun envelopeByteCount(record: PreparedAntennaExport): Long = envelopeByteCount(
        token = record.token,
        patternId = record.patternId,
        formatName = record.formatName,
        suggestedFileName = record.suggestedFileName,
        sha256 = record.sha256,
        warningBytes = record.warnings.sumOf { it.utf8Size().toLong() },
        warningCount = record.warnings.size,
        payloadLength = record.payload.size,
    )

    private fun envelopeByteCount(
        token: String,
        patternId: String,
        formatName: String,
        suggestedFileName: String,
        sha256: String,
        warningBytes: Long,
        warningCount: Int,
        payloadLength: Int,
    ): Long = FIXED_ENVELOPE_BYTES +
        token.utf8Size() +
        patternId.utf8Size() +
        formatName.utf8Size() +
        suggestedFileName.utf8Size() +
        sha256.utf8Size() +
        warningBytes +
        warningCount.toLong() * STRING_LENGTH_BYTES +
        payloadLength

    private fun currentTime(): Long = clock().also { now ->
        if (now < 0L) throw IOException("The prepared-export clock returned an invalid timestamp.")
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.toLowerHex()
    }

    private fun validatePatternId(value: String) {
        require(isValidPatternId(value)) {
            "The antenna pattern ID is blank, oversized, or contains control characters."
        }
    }

    private fun validateFormatName(value: String) {
        require(value in SUPPORTED_FORMAT_NAMES) { "The antenna export format is not supported." }
    }

    private fun validateSuggestedFileName(value: String) {
        require(isValidSuggestedFileName(value)) {
            "The suggested antenna export file name is invalid or oversized."
        }
    }

    private fun validatePayload(value: ByteArray) {
        require(value.size in 1..AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
            "A prepared antenna export must contain between 1 byte and 16 MiB."
        }
    }

    private fun validateWarnings(values: List<String>) {
        require(values.size <= MAXIMUM_WARNING_COUNT) {
            "The prepared antenna export contains too many warnings."
        }
        require(values.all(::isValidWarning)) {
            "A prepared antenna export warning is blank, oversized, or contains control characters."
        }
        require(values.sumOf { it.utf8Size().toLong() } <= MAXIMUM_TOTAL_WARNING_BYTES) {
            "The prepared antenna export warning metadata is oversized."
        }
    }

    private fun isValidPatternId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAXIMUM_PATTERN_ID_CHARACTERS &&
            value.utf8Size() <= MAXIMUM_PATTERN_ID_BYTES &&
            value.none(Char::isISOControl)

    private fun isValidSuggestedFileName(value: String): Boolean =
        value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            value.length <= MAXIMUM_FILE_NAME_CHARACTERS &&
            value.utf8Size() <= MAXIMUM_FILE_NAME_BYTES &&
            value.none(Char::isISOControl) &&
            '/' !in value &&
            '\\' !in value

    private fun isValidWarning(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAXIMUM_WARNING_CHARACTERS &&
            value.utf8Size() <= MAXIMUM_WARNING_BYTES &&
            value.none(Char::isISOControl)

    private data class CacheSnapshot(
        val entryCount: Int,
        val totalBytes: Long,
        val removedCount: Int,
    )

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 60L * 60L * 1_000L
        const val DEFAULT_MAX_ENTRIES: Int = 8
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 64L * 1024L * 1024L

        private const val ENVELOPE_MAGIC: Int = 0x41545845 // ATXE
        private const val ENVELOPE_VERSION: Int = 1
        private const val TOKEN_BYTES: Int = 32
        private const val TOKEN_CHARACTERS: Int = TOKEN_BYTES * 2
        private const val SHA256_CHARACTERS: Int = 64
        private const val MAXIMUM_TOKEN_ATTEMPTS: Int = 16
        private const val MAXIMUM_PATTERN_ID_CHARACTERS: Int = 256
        private const val MAXIMUM_PATTERN_ID_BYTES: Int = 1_024
        private const val MAXIMUM_FORMAT_NAME_BYTES: Int = 64
        private const val MAXIMUM_FILE_NAME_CHARACTERS: Int = 255
        private const val MAXIMUM_FILE_NAME_BYTES: Int = 1_024
        private const val MAXIMUM_WARNING_COUNT: Int = 100
        private const val MAXIMUM_WARNING_CHARACTERS: Int = 500
        private const val MAXIMUM_WARNING_BYTES: Int = 2_000
        private const val MAXIMUM_TOTAL_WARNING_BYTES: Long = 64L * 1_024L
        internal const val MAXIMUM_METADATA_BYTES: Int = 128 * 1_024
        private const val STRING_LENGTH_BYTES: Long = Int.SIZE_BYTES.toLong()
        private const val FIXED_ENVELOPE_BYTES: Long =
            Int.SIZE_BYTES * 4L + Long.SIZE_BYTES * 2L + STRING_LENGTH_BYTES * 5L
        private const val MINIMUM_ENVELOPE_BYTES: Long = FIXED_ENVELOPE_BYTES +
            TOKEN_CHARACTERS + SHA256_CHARACTERS + 1L + 1L + 1L
        private const val MAXIMUM_ENVELOPE_BYTES: Long =
            AntennaPatternCodecLimits.MAX_INPUT_BYTES.toLong() + MAXIMUM_METADATA_BYTES
        private const val PUBLISHED_SUFFIX: String = ".prepared-antenna-export"
        private const val STAGING_PREFIX: String = ".prepared-antenna-export-stage-"
        private const val STAGING_SUFFIX: String = ".part"
        private const val STAGING_NONCE_CHARACTERS: Int = 24
        private val TOKEN_PATTERN = Regex("[a-f0-9]{$TOKEN_CHARACTERS}")
        private val SHA256_PATTERN = Regex("[a-f0-9]{$SHA256_CHARACTERS}")
        private val PUBLISHED_FILE_PATTERN = Regex("([a-f0-9]{$TOKEN_CHARACTERS})${Regex.escape(PUBLISHED_SUFFIX)}")
        private val STAGING_FILE_PATTERN = Regex(
            "${Regex.escape(STAGING_PREFIX)}[a-f0-9]{$TOKEN_CHARACTERS}-" +
                "[a-f0-9]{$STAGING_NONCE_CHARACTERS}${Regex.escape(STAGING_SUFFIX)}",
        )
        private val SUPPORTED_FORMAT_NAMES = setOf(
            "ATX_JSON",
            "ATX_DESKTOP_JSON",
            "PRN",
            "PAT",
            "HRP",
            "VRP",
            "VSOFT_HRP",
            "VSOFT_VRP",
        )
    }
}

private fun DataOutputStream.writeBoundedString(value: String) {
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
}

private fun DataInputStream.readBoundedString(
    maximumBytes: Int,
    budget: PreparedExportMetadataBudget,
): String {
    val byteCount = readInt()
    if (byteCount !in 0..maximumBytes) {
        throw IOException("A prepared-export metadata field exceeds its byte bound.")
    }
    budget.add(byteCount)
    val encoded = ByteArray(byteCount)
    readFully(encoded)
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(encoded))
        .toString()
}

private class PreparedExportMetadataBudget {
    private var bytes: Int = 0

    fun add(value: Int) {
        if (
            value < 0 ||
            bytes > PreparedAntennaExportCache.MAXIMUM_METADATA_BYTES - value - Int.SIZE_BYTES
        ) {
            throw IOException("Prepared-export metadata exceeds its byte bound.")
        }
        bytes += value + Int.SIZE_BYTES
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .toLowerHex()

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(LOWER_HEX_DIGITS[value ushr 4])
        append(LOWER_HEX_DIGITS[value and 0x0f])
    }
}

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun PreparedAntennaExport.sameContentAs(other: PreparedAntennaExport): Boolean =
    token == other.token &&
        patternId == other.patternId &&
        formatName == other.formatName &&
        suggestedFileName == other.suggestedFileName &&
        payload.contentEquals(other.payload) &&
        sha256 == other.sha256 &&
        createdAtEpochMillis == other.createdAtEpochMillis &&
        expiresAtEpochMillis == other.expiresAtEpochMillis &&
        warnings == other.warnings

private fun PreparedAntennaExport.detachedCopy(): PreparedAntennaExport = copy(
    payload = payload.copyOf(),
    warnings = warnings.toList(),
)

private const val LOWER_HEX_DIGITS = "0123456789abcdef"
