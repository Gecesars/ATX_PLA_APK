package com.gecesars.atxplan.data.antenna

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class PreparedAntennaExportCacheTest {
    private lateinit var sandboxDirectory: File
    private lateinit var cacheDirectory: File
    private lateinit var now: AtomicLong

    @Before
    fun setUp() {
        sandboxDirectory = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toFile()
        cacheDirectory = File(sandboxDirectory, "prepared")
        now = AtomicLong(TEST_NOW)
    }

    @After
    fun tearDown() {
        check(sandboxDirectory.name.startsWith(TEST_DIRECTORY_PREFIX)) {
            "Refusing to remove an unexpected test directory."
        }
        val systemTemporaryDirectory = File(checkNotNull(System.getProperty("java.io.tmpdir")))
        check(sandboxDirectory.toPath().parent == systemTemporaryDirectory.toPath()) {
            "The prepared-export test directory is outside the system temporary directory."
        }
        check(sandboxDirectory.deleteRecursively()) {
            "The prepared-export test directory could not be removed."
        }
    }

    @Test
    fun `process recreation restores the complete verified entry and retry loads do not consume it`() {
        val payload = "ATX antenna export\n0,1.0\n".toByteArray()
        val warnings = listOf(
            "The source omitted optional phase samples.",
            "The desktop format rounds gain metadata.",
        )
        val stored = cache().store(
            patternId = "pattern-process-recreation",
            formatName = "ATX_DESKTOP_JSON",
            suggestedFileName = "process-recreation.atxpat.json",
            payload = payload,
            warnings = warnings,
        )

        val recreatedCache = cache()
        val firstLoad = requireNotNull(recreatedCache.load(stored.token))
        assertRecordEquals(stored, firstLoad)
        assertEquals("ATX_DESKTOP_JSON", firstLoad.formatName)
        assertEquals(warnings, firstLoad.warnings)
        assertArrayEquals(payload, firstLoad.payload)

        firstLoad.payload[0] = 0
        val retryLoad = requireNotNull(recreatedCache.load(stored.token))
        assertArrayEquals(payload, retryLoad.payload)
        assertTrue(publishedFile(cacheDirectory, stored.token).isFile)
    }

    @Test
    fun `store publishes one synced envelope with an unguessable token and exact metadata`() {
        val payload = ByteArray(2_049) { index -> (index * 31).toByte() }

        val first = cache().store(
            patternId = "pattern-1",
            formatName = "PRN",
            suggestedFileName = "pattern-1.prn",
            payload = payload,
            warnings = emptyList(),
        )
        now.incrementAndGet()
        val second = cache().store(
            patternId = "pattern-2",
            formatName = "PAT",
            suggestedFileName = "pattern-2.pat",
            payload = byteArrayOf(1, 2, 3),
            warnings = listOf("A bounded warning."),
        )

        assertTrue(TOKEN_PATTERN.matches(first.token))
        assertTrue(TOKEN_PATTERN.matches(second.token))
        assertNotEquals(first.token, second.token)
        assertEquals(sha256(payload), first.sha256)
        assertEquals(TEST_NOW, first.createdAtEpochMillis)
        assertEquals(TEST_NOW + PreparedAntennaExportCache.DEFAULT_TTL_MILLIS, first.expiresAtEpochMillis)
        assertEquals(2, cacheDirectory.listFiles().orEmpty().size)
        assertTrue(cacheDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `explicit removal deletes the token while cleanup preserves an unexpired valid entry`() {
        val prepared = storeDefault()

        assertEquals(0, cache().cleanup())
        requireNotNull(cache().load(prepared.token))
        assertTrue(cache().remove(prepared.token))
        assertNull(cache().load(prepared.token))
        assertFalse(publishedFileOrNull(cacheDirectory, prepared.token)?.exists() == true)
    }

    @Test
    fun `expiry cleanup removes the envelope and never returns its bytes`() {
        val prepared = storeDefault(payload = "expires".toByteArray())
        now.set(TEST_NOW + PreparedAntennaExportCache.DEFAULT_TTL_MILLIS)

        assertEquals(1, cache().cleanup())
        assertNull(cache().load(prepared.token))
        assertTrue(cacheDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `payload and warning bounds reject hostile stores without staging data`() {
        val oversizedPayload = ByteArray(AntennaPatternCodecLimits.MAX_INPUT_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            storeDefault(payload = oversizedPayload)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache().store(
                patternId = "pattern-warnings",
                formatName = "ATX_JSON",
                suggestedFileName = "warnings.atx-antenna.json",
                payload = byteArrayOf(1),
                warnings = List(101) { "warning-$it" },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache().store(
                patternId = "pattern-warning-size",
                formatName = "ATX_JSON",
                suggestedFileName = "warnings.atx-antenna.json",
                payload = byteArrayOf(1),
                warnings = listOf("w".repeat(501)),
            )
        }

        assertFalse(cacheDirectory.exists())
    }

    @Test
    fun `invalid format filename and pattern metadata reject before publication`() {
        assertThrows(IllegalArgumentException::class.java) {
            cache().store("pattern", "UNKNOWN", "pattern.dat", byteArrayOf(1), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache().store("pattern", "PRN", "../outside.prn", byteArrayOf(1), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            cache().store("pattern\u0000", "PRN", "pattern.prn", byteArrayOf(1), emptyList())
        }

        assertFalse(cacheDirectory.exists())
    }

    @Test
    fun `entry capacity rejects the new export and preserves the valid cached export`() {
        val bounded = cache(maxEntries = 1)
        val retained = bounded.store(
            "pattern-retained",
            "PRN",
            "retained.prn",
            "retained".toByteArray(),
            emptyList(),
        )

        assertThrows(IOException::class.java) {
            bounded.store(
                "pattern-rejected",
                "PAT",
                "rejected.pat",
                "rejected".toByteArray(),
                emptyList(),
            )
        }

        assertArrayEquals("retained".toByteArray(), requireNotNull(bounded.load(retained.token)).payload)
        assertEquals(1, cacheDirectory.listFiles().orEmpty().size)
    }

    @Test
    fun `total byte capacity rejects the new export without evicting valid data`() {
        val bounded = cache(maxTotalBytes = 4_096L)
        val retainedPayload = ByteArray(3_000) { 0x2a }
        val retained = bounded.store(
            "pattern-retained",
            "PRN",
            "retained.prn",
            retainedPayload,
            emptyList(),
        )

        assertThrows(IOException::class.java) {
            bounded.store(
                "pattern-rejected",
                "PRN",
                "rejected.prn",
                ByteArray(3_000) { 0x3b },
                emptyList(),
            )
        }

        assertArrayEquals(retainedPayload, requireNotNull(bounded.load(retained.token)).payload)
        assertEquals(1, cacheDirectory.listFiles().orEmpty().size)
    }

    @Test
    fun `invalid and traversal tokens cannot address cache or outside files`() {
        val prepared = storeDefault(payload = "private bytes".toByteArray())
        val outside = File(sandboxDirectory, "outside.prepared-antenna-export")
        outside.writeText("outside sentinel")

        listOf(
            "../${prepared.token}",
            "..\\${prepared.token}",
            prepared.token.uppercase(),
            prepared.token.dropLast(1),
            "a".repeat(4_096),
        ).forEach { invalidToken ->
            assertNull(cache().load(invalidToken))
            assertFalse(cache().remove(invalidToken))
        }

        assertEquals("outside sentinel", outside.readText())
        assertArrayEquals("private bytes".toByteArray(), requireNotNull(cache().load(prepared.token)).payload)
    }

    @Test
    fun `an envelope copied under another valid token fails token correlation`() {
        val prepared = storeDefault(payload = "correlated".toByteArray())
        val original = publishedFile(cacheDirectory, prepared.token)
        val otherToken = differentValidToken(prepared.token)
        val copied = File(cacheDirectory, original.name.replace(prepared.token, otherToken))
        original.copyTo(copied)

        assertNull(cache().load(otherToken))
        assertFalse(copied.exists())
        assertArrayEquals("correlated".toByteArray(), requireNotNull(cache().load(prepared.token)).payload)
    }

    @Test
    fun `symbolic envelope paths fail closed without following or deleting their targets`() {
        val sourceDirectory = File(sandboxDirectory, "source")
        val targetDirectory = File(sandboxDirectory, "target").apply {
            check(mkdirs())
        }
        val sourceCache = cache(directory = sourceDirectory)
        val prepared = sourceCache.store(
            "pattern-symlink",
            "PRN",
            "symlink.prn",
            "symlink target bytes".toByteArray(),
            emptyList(),
        )
        val sourceEnvelope = publishedFile(sourceDirectory, prepared.token)
        val linkedEnvelope = File(targetDirectory, sourceEnvelope.name)
        try {
            Files.createSymbolicLink(linkedEnvelope.toPath(), sourceEnvelope.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable in this JVM environment.", error)
        }

        assertNull(cache(directory = targetDirectory).load(prepared.token))
        assertTrue(sourceEnvelope.isFile)
        assertArrayEquals(
            "symlink target bytes".toByteArray(),
            requireNotNull(sourceCache.load(prepared.token)).payload,
        )
    }

    @Test
    fun `unknown on disk format and invalid timestamps fail closed`() {
        val unknownFormat = storeDefault(formatName = "ATX_JSON")
        val unknownFormatFile = publishedFile(cacheDirectory, unknownFormat.token)
        replaceSameLengthAscii(unknownFormatFile, "ATX_JSON", "BAD_JSON")
        assertNull(cache().load(unknownFormat.token))

        val invalidTimestamp = storeDefault(formatName = "PRN")
        val invalidTimestampFile = publishedFile(cacheDirectory, invalidTimestamp.token)
        RandomAccessFile(invalidTimestampFile, "rw").use { file ->
            val timestampOffset = timestampOffset(file)
            file.seek(timestampOffset + Long.SIZE_BYTES)
            file.writeLong(TEST_NOW)
            file.fd.sync()
        }
        assertNull(cache().load(invalidTimestamp.token))
    }

    @Test
    fun `oversize declared length truncation trailing data and hash changes return no bytes`() {
        val oversized = storeDefault(payload = ByteArray(321) { 0x11 })
        RandomAccessFile(publishedFile(cacheDirectory, oversized.token), "rw").use { file ->
            file.seek(payloadLengthOffset(file))
            file.writeInt(AntennaPatternCodecLimits.MAX_INPUT_BYTES + 1)
            file.fd.sync()
        }
        assertNull(cache().load(oversized.token))

        val truncated = storeDefault(payload = ByteArray(321) { 0x22 })
        RandomAccessFile(publishedFile(cacheDirectory, truncated.token), "rw").use { file ->
            file.setLength(file.length() - 1L)
            file.fd.sync()
        }
        assertNull(cache().load(truncated.token))

        val trailing = storeDefault(payload = ByteArray(321) { 0x33 })
        RandomAccessFile(publishedFile(cacheDirectory, trailing.token), "rw").use { file ->
            file.seek(file.length())
            file.writeByte(0x7f)
            file.fd.sync()
        }
        assertNull(cache().load(trailing.token))

        val hashChanged = storeDefault(payload = ByteArray(321) { 0x44 })
        RandomAccessFile(publishedFile(cacheDirectory, hashChanged.token), "rw").use { file ->
            file.seek(file.length() - 1L)
            file.writeByte(0x45)
            file.fd.sync()
        }
        assertNull(cache().load(hashChanged.token))
    }

    @Test
    fun `malformed metadata lengths fail before allocation and are cleaned`() {
        val prepared = storeDefault()
        val envelope = publishedFile(cacheDirectory, prepared.token)
        RandomAccessFile(envelope, "rw").use { file ->
            file.seek((Int.SIZE_BYTES * 2).toLong())
            file.writeInt(Int.MAX_VALUE)
            file.fd.sync()
        }

        assertNull(cache().load(prepared.token))
        assertFalse(envelope.exists())
    }

    private fun cache(
        directory: File = cacheDirectory,
        maxEntries: Int = PreparedAntennaExportCache.DEFAULT_MAX_ENTRIES,
        maxTotalBytes: Long = PreparedAntennaExportCache.DEFAULT_MAX_TOTAL_BYTES,
    ): PreparedAntennaExportCache = PreparedAntennaExportCache(
        directory = directory,
        clock = now::get,
        maxEntries = maxEntries,
        maxTotalBytes = maxTotalBytes,
    )

    private fun storeDefault(
        payload: ByteArray = "prepared antenna export".toByteArray(),
        formatName: String = "PRN",
    ): PreparedAntennaExport = cache().store(
        patternId = "pattern-default",
        formatName = formatName,
        suggestedFileName = "pattern-default.prn",
        payload = payload,
        warnings = listOf("The export uses a bounded test warning."),
    )

    private fun assertRecordEquals(
        expected: PreparedAntennaExport,
        actual: PreparedAntennaExport,
    ) {
        assertEquals(expected.token, actual.token)
        assertEquals(expected.patternId, actual.patternId)
        assertEquals(expected.formatName, actual.formatName)
        assertEquals(expected.suggestedFileName, actual.suggestedFileName)
        assertArrayEquals(expected.payload, actual.payload)
        assertEquals(expected.sha256, actual.sha256)
        assertEquals(expected.createdAtEpochMillis, actual.createdAtEpochMillis)
        assertEquals(expected.expiresAtEpochMillis, actual.expiresAtEpochMillis)
        assertEquals(expected.warnings, actual.warnings)
    }

    private fun publishedFile(directory: File, token: String): File =
        requireNotNull(publishedFileOrNull(directory, token))

    private fun publishedFileOrNull(directory: File, token: String): File? =
        directory.listFiles().orEmpty().singleOrNull { file ->
            file.name.startsWith(token) && !file.name.endsWith(".part")
        }

    private fun replaceSameLengthAscii(
        file: File,
        original: String,
        replacement: String,
    ) {
        val originalBytes = original.toByteArray(Charsets.US_ASCII)
        val replacementBytes = replacement.toByteArray(Charsets.US_ASCII)
        require(originalBytes.size == replacementBytes.size)
        val content = file.readBytes()
        val offset = content.indexOfSubArray(originalBytes)
        check(offset >= 0) { "The expected envelope metadata was not found." }
        replacementBytes.copyInto(content, destinationOffset = offset)
        file.writeBytes(content)
    }

    private fun timestampOffset(file: RandomAccessFile): Long {
        file.seek((Int.SIZE_BYTES * 2).toLong())
        repeat(5) { skipLengthPrefixedField(file) }
        return file.filePointer
    }

    private fun payloadLengthOffset(file: RandomAccessFile): Long {
        val timestamps = timestampOffset(file)
        return timestamps + Long.SIZE_BYTES * 2L
    }

    private fun skipLengthPrefixedField(file: RandomAccessFile) {
        val length = file.readInt()
        check(length >= 0)
        file.seek(file.filePointer + length)
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (index in 0..size - needle.size) {
            if (needle.indices.all { offset -> this[index + offset] == needle[offset] }) return index
        }
        return -1
    }

    private fun differentValidToken(token: String): String =
        (if (token.first() == '0') '1' else '0') + token.drop(1)

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TEST_DIRECTORY_PREFIX = "atx-prepared-export-cache-test-"
        const val TEST_NOW = 1_800_000_000_000L
        val TOKEN_PATTERN = Regex("[a-f0-9]{64}")
    }
}
