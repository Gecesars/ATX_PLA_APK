package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class ArtifactStoreTest {
    private lateinit var sandboxDirectory: File
    private lateinit var rootDirectory: File

    @Before
    fun setUp() {
        sandboxDirectory = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toFile()
        rootDirectory = File(sandboxDirectory, "store")
    }

    @After
    fun tearDown() {
        check(sandboxDirectory.name.startsWith(TEST_DIRECTORY_PREFIX)) {
            "Refusing to remove an unexpected test directory."
        }
        val systemTemporaryDirectory = File(checkNotNull(System.getProperty("java.io.tmpdir")))
        check(sandboxDirectory.toPath().parent == systemTemporaryDirectory.toPath()) {
            "The artifact test directory is outside the system temporary directory."
        }
        check(sandboxDirectory.deleteRecursively()) {
            "The artifact test directory could not be removed."
        }
    }

    @Test
    fun `put verifies metadata content address and copies the exact payload`() {
        val payload = ByteArray(ARTIFACT_BUFFER_BOUNDARY_BYTES + 37) { index ->
            (index * 31).toByte()
        }
        val expectedSha256 = sha256(payload)
        val store = store()

        val reference = store.put(
            role = ProjectArtifactRole.GIS_LAYER,
            fileName = "regional-clutter.tif",
            mediaType = "image/tiff",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
            expectedSha256 = expectedSha256,
        )

        assertEquals("artifact-test-1", reference.id)
        assertEquals(ProjectArtifactRole.GIS_LAYER, reference.role)
        assertEquals("regional-clutter.tif", reference.fileName)
        assertEquals("image/tiff", reference.mediaType)
        assertEquals(expectedSha256, reference.sha256)
        assertEquals(payload.size.toLong(), reference.byteCount)
        assertEquals(TEST_TIMESTAMP, reference.createdAtEpochMillis)
        assertEquals(ArtifactAvailability.AVAILABLE, store.availability(reference))

        val target = targetFile(reference.sha256)
        assertTrue(target.isFile)
        assertArrayEquals(payload, target.readBytes())
        assertTrue(target.canonicalPath.startsWith(rootDirectory.canonicalPath + File.separator))
        assertTrue(stagingFiles().isEmpty())

        val copied = ByteArrayOutputStream()
        store.copy(reference, copied, maximumBytes = payload.size.toLong())
        assertArrayEquals(payload, copied.toByteArray())
    }

    @Test
    fun `known SHA-256 vector is accepted and represented in lowercase`() {
        val payload = "abc".toByteArray(Charsets.UTF_8)
        val store = store()

        val reference = store.put(
            role = ProjectArtifactRole.IMPORT_SOURCE,
            fileName = "source.bin",
            mediaType = "application/octet-stream",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
            expectedSha256 = ABC_SHA256,
        )

        assertEquals(ABC_SHA256, reference.sha256)
        assertEquals(ArtifactAvailability.AVAILABLE, store.availability(reference))
        assertArrayEquals(payload, targetFile(ABC_SHA256).readBytes())
    }

    @Test
    fun `identical content is deduplicated while retaining independent references`() {
        val payload = "immutable shared coverage result".toByteArray(Charsets.UTF_8)
        val issuedIds = AtomicInteger()
        val store = store(idGenerator = { "artifact-${issuedIds.incrementAndGet()}" })

        val first = store.put(
            role = ProjectArtifactRole.COVERAGE_RESULT,
            fileName = "coverage-a.bin",
            mediaType = "application/octet-stream",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )
        val second = store.put(
            role = ProjectArtifactRole.STUDY_REPORT,
            fileName = "coverage-report.bin",
            mediaType = "application/vnd.atx.report",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )

        assertEquals("artifact-1", first.id)
        assertEquals("artifact-2", second.id)
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.byteCount, second.byteCount)
        assertEquals(1, blobFiles().size)
        assertArrayEquals(payload, blobFiles().single().readBytes())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `wrong expected hash rejects content without publishing a blob`() {
        val payload = "untrusted import".toByteArray(Charsets.UTF_8)
        val store = store()

        val error = assertThrows(IOException::class.java) {
            store.put(
                role = ProjectArtifactRole.IMPORT_SOURCE,
                fileName = "untrusted.rp3",
                mediaType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                maximumBytes = payload.size.toLong(),
                expectedSha256 = ABC_SHA256,
            )
        }

        assertTrue(error.message.orEmpty().contains("expected SHA-256"))
        assertTrue(blobFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `malformed expected hashes are rejected before storage is created`() {
        val invalidHashes = listOf(
            ABC_SHA256.uppercase(),
            ABC_SHA256.dropLast(1),
            "../$ABC_SHA256",
            "g".repeat(64),
        )

        invalidHashes.forEach { invalidHash ->
            assertThrows(IllegalArgumentException::class.java) {
                store().put(
                    role = ProjectArtifactRole.OTHER,
                    fileName = "artifact.bin",
                    mediaType = "application/octet-stream",
                    input = ByteArrayInputStream(byteArrayOf(1)),
                    maximumBytes = 1L,
                    expectedSha256 = invalidHash,
                )
            }
        }

        assertFalse(rootDirectory.exists())
    }

    @Test
    fun `put rejects content above its approved limit and removes staging data`() {
        val payload = ByteArray(65) { it.toByte() }
        val store = store()

        val error = assertThrows(IOException::class.java) {
            store.put(
                role = ProjectArtifactRole.DATASET_REFERENCE,
                fileName = "bounded.bin",
                mediaType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                maximumBytes = 64L,
            )
        }

        assertTrue(error.message.orEmpty().contains("approved operation limit"))
        assertTrue(blobFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `operation limits reject zero and values above the global ceiling`() {
        val store = store()
        val payload = byteArrayOf(1)

        listOf(0L, MAXIMUM_OPERATION_BYTES + 1L).forEach { invalidLimit ->
            assertThrows(IllegalArgumentException::class.java) {
                store.put(
                    role = ProjectArtifactRole.OTHER,
                    fileName = "artifact.bin",
                    mediaType = "application/octet-stream",
                    input = ByteArrayInputStream(payload),
                    maximumBytes = invalidLimit,
                )
            }
        }

        assertFalse(rootDirectory.exists())
    }

    @Test
    fun `invalid artifact metadata never promotes an orphan blob`() {
        val payload = "metadata validation payload".toByteArray(Charsets.UTF_8)

        assertThrows(IllegalArgumentException::class.java) {
            store().put(
                role = ProjectArtifactRole.OTHER,
                fileName = "",
                mediaType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                maximumBytes = payload.size.toLong(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().put(
                role = ProjectArtifactRole.OTHER,
                fileName = "artifact.bin",
                mediaType = "",
                input = ByteArrayInputStream(payload),
                maximumBytes = payload.size.toLong(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store(idGenerator = { "" }).put(
                role = ProjectArtifactRole.OTHER,
                fileName = "artifact.bin",
                mediaType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                maximumBytes = payload.size.toLong(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store(clock = { -1L }).put(
                role = ProjectArtifactRole.OTHER,
                fileName = "artifact.bin",
                mediaType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                maximumBytes = payload.size.toLong(),
            )
        }

        assertTrue(blobFiles().isEmpty())
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `copy rejects an insufficient limit without writing output`() {
        val payload = "bounded copy".toByteArray(Charsets.UTF_8)
        val store = store()
        val reference = store.put(
            role = ProjectArtifactRole.STUDY_REPORT,
            fileName = "report.json",
            mediaType = "application/json",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )
        val output = ByteArrayOutputStream().apply { write(OUTPUT_SENTINEL) }

        val error = assertThrows(IOException::class.java) {
            store.copy(reference, output, maximumBytes = payload.size.toLong() - 1L)
        }

        assertTrue(error.message.orEmpty().contains("approved operation limit"))
        assertArrayEquals(byteArrayOf(OUTPUT_SENTINEL.toByte()), output.toByteArray())
        assertEquals(ArtifactAvailability.AVAILABLE, store.availability(reference))
    }

    @Test
    fun `missing content is reported and cannot be copied`() {
        val payload = "temporary artifact".toByteArray(Charsets.UTF_8)
        val store = store()
        val reference = store.put(
            role = ProjectArtifactRole.OTHER,
            fileName = "temporary.bin",
            mediaType = "application/octet-stream",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )
        assertTrue(targetFile(reference.sha256).delete())

        assertEquals(ArtifactAvailability.MISSING, store.availability(reference))
        val output = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) {
            store.copy(reference, output, maximumBytes = payload.size.toLong())
        }
        assertTrue(error.message.orEmpty().contains("missing"))
        assertEquals(0, output.size())
    }

    @Test
    fun `same-size content corruption is detected before copy writes output`() {
        val payload = "trusted artifact bytes".toByteArray(Charsets.UTF_8)
        val store = store()
        val reference = store.put(
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            fileName = "pattern.prn",
            mediaType = "text/plain",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )
        val corruptPayload = payload.copyOf().also { bytes -> bytes[0] = (bytes[0] + 1).toByte() }
        targetFile(reference.sha256).writeBytes(corruptPayload)

        assertEquals(ArtifactAvailability.CORRUPT, store.availability(reference))
        val output = ByteArrayOutputStream().apply { write(OUTPUT_SENTINEL) }
        val error = assertThrows(IOException::class.java) {
            store.copy(reference, output, maximumBytes = payload.size.toLong())
        }
        assertTrue(error.message.orEmpty().contains("corrupt"))
        assertArrayEquals(byteArrayOf(OUTPUT_SENTINEL.toByte()), output.toByteArray())
    }

    @Test
    fun `truncated content is detected by its recorded size`() {
        val payload = "artifact with recorded length".toByteArray(Charsets.UTF_8)
        val store = store()
        val reference = store.put(
            role = ProjectArtifactRole.REGULATORY_RESULT,
            fileName = "screening.json",
            mediaType = "application/json",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )
        targetFile(reference.sha256).writeBytes(payload.dropLast(1).toByteArray())

        assertEquals(ArtifactAvailability.CORRUPT, store.availability(reference))
    }

    @Test
    fun `deduplication rejects a corrupt existing immutable blob`() {
        val payload = "immutable payload".toByteArray(Charsets.UTF_8)
        val issuedIds = AtomicInteger()
        val store = store(idGenerator = { "artifact-${issuedIds.incrementAndGet()}" })
        val first = store.put(
            role = ProjectArtifactRole.OTHER,
            fileName = "first.bin",
            mediaType = "application/octet-stream",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )
        targetFile(first.sha256).writeBytes(payload.reversedArray())

        val error = assertThrows(IOException::class.java) {
            store.put(
                role = ProjectArtifactRole.OTHER,
                fileName = "second.bin",
                mediaType = "application/octet-stream",
                input = ByteArrayInputStream(payload),
                maximumBytes = payload.size.toLong(),
            )
        }

        assertTrue(error.message.orEmpty().contains("immutable artifact content"))
        assertEquals(1, issuedIds.get())
        assertEquals(1, blobFiles().size)
        assertEquals(ArtifactAvailability.CORRUPT, store.availability(first))
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `untrusted display filename cannot influence the content-addressed storage path`() {
        val payload = "safe content address".toByteArray(Charsets.UTF_8)
        val untrustedFileName = "../../outside/..\\escaped-result.bin"
        val store = store()

        val reference = store.put(
            role = ProjectArtifactRole.IMPORT_SOURCE,
            fileName = untrustedFileName,
            mediaType = "application/octet-stream",
            input = ByteArrayInputStream(payload),
            maximumBytes = payload.size.toLong(),
        )

        assertEquals(untrustedFileName, reference.fileName)
        assertEquals(targetFile(reference.sha256).canonicalFile, blobFiles().single().canonicalFile)
        assertFalse(File(sandboxDirectory, "outside").exists())
        assertFalse(File(sandboxDirectory, "escaped-result.bin").exists())
        assertEquals(listOf(rootDirectory.canonicalFile), sandboxDirectory.listFiles().orEmpty().map(File::getCanonicalFile))
    }

    private fun store(
        idGenerator: () -> String = { "artifact-test-1" },
        clock: () -> Long = { TEST_TIMESTAMP },
    ) = FileContentAddressedArtifactStore(
        rootDirectory = rootDirectory,
        idGenerator = idGenerator,
        clock = clock,
    )

    private fun targetFile(sha256: String): File =
        File(File(File(rootDirectory, "sha256"), sha256.take(2)), "$sha256.blob")

    private fun blobFiles(): List<File> =
        if (!rootDirectory.exists()) {
            emptyList()
        } else {
            rootDirectory.walkTopDown().filter { file -> file.isFile && file.extension == "blob" }.toList()
        }

    private fun stagingFiles(): List<File> =
        File(rootDirectory, "staging").listFiles().orEmpty().toList()

    private fun sha256(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val TEST_DIRECTORY_PREFIX = "atx-artifact-store-test-"
        const val TEST_TIMESTAMP = 1_725_000_000_000L
        const val ARTIFACT_BUFFER_BOUNDARY_BYTES = 64 * 1024
        const val MAXIMUM_OPERATION_BYTES = 512L * 1024L * 1024L
        const val OUTPUT_SENTINEL = 0x5A
        const val ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
