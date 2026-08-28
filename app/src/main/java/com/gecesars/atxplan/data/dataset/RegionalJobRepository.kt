package com.gecesars.atxplan.data.dataset

import android.content.Context
import android.util.AtomicFile
import com.gecesars.atxplan.domain.dataset.MAXIMUM_REGIONAL_JOBS
import com.gecesars.atxplan.domain.dataset.REGIONAL_JOB_SCHEMA_VERSION
import com.gecesars.atxplan.domain.dataset.RegionalJobRecordV1
import com.gecesars.atxplan.domain.dataset.RegionalJobState
import com.gecesars.atxplan.domain.dataset.RegionalPlanFingerprint
import com.gecesars.atxplan.domain.dataset.validateRegionalJobMutation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class RegionalJobStoreSnapshot(
    val jobs: List<RegionalJobRecordV1>,
    val unreadableJobIds: List<String>,
) {
    init {
        require(jobs.size + unreadableJobIds.size <= MAXIMUM_REGIONAL_JOBS) {
            "The regional job snapshot exceeds its record limit."
        }
        require(jobs.distinctBy(RegionalJobRecordV1::jobId).size == jobs.size) {
            "The regional job snapshot contains duplicate records."
        }
        require(unreadableJobIds.distinct().size == unreadableJobIds.size) {
            "The regional job snapshot contains duplicate unreadable IDs."
        }
        require(jobs.map(RegionalJobRecordV1::jobId).none { it in unreadableJobIds }) {
            "A regional job cannot be both readable and unreadable."
        }
    }
}

interface RegionalJobRepository {
    suspend fun loadSnapshot(): RegionalJobStoreSnapshot

    suspend fun get(jobId: String): RegionalJobRecordV1?

    /** Idempotent only for the same job ID and exact plan identity. */
    suspend fun create(record: RegionalJobRecordV1): RegionalJobRecordV1

    /** Compare-and-set update. The transformed record must advance exactly one revision. */
    suspend fun update(
        jobId: String,
        expectedRevision: Long,
        transform: (RegionalJobRecordV1) -> RegionalJobRecordV1,
    ): RegionalJobRecordV1
}

class RegionalJobStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class RegionalJobConflictException(message: String) : IllegalStateException(message)

internal interface RegionalJobRecordStorage {
    fun listIds(maximumRecords: Int): List<String>

    fun exists(jobId: String): Boolean

    fun read(jobId: String, maximumBytes: Int): ByteArray

    fun createAtomically(jobId: String, payload: ByteArray)

    fun replaceAtomically(jobId: String, payload: ByteArray)
}

internal class RegionalJobStorePersistence(
    private val storage: RegionalJobRecordStorage,
    private val operationMutex: Mutex = Mutex(),
) {
    suspend fun loadSnapshot(): RegionalJobStoreSnapshot = operationMutex.withLock {
        loadSnapshotLocked()
    }

    suspend fun get(jobId: String): RegionalJobRecordV1? = operationMutex.withLock {
        validateJobId(jobId)
        if (!storage.exists(jobId)) return@withLock null
        decodeRecord(jobId, readRecordBytes(jobId))
    }

    suspend fun create(record: RegionalJobRecordV1): RegionalJobRecordV1 = operationMutex.withLock {
        validateNewRecord(record)
        val ids = listIdsLocked()
        if (record.jobId in ids || storage.exists(record.jobId)) {
            val existing = try {
                decodeRecord(record.jobId, readRecordBytes(record.jobId))
            } catch (error: Exception) {
                throw RegionalJobConflictException(
                    "Regional job '${record.jobId}' already has an unreadable durable record.",
                )
            }
            if (
                existing.planFingerprintSha256 == record.planFingerprintSha256 &&
                existing.semanticFingerprintSha256 == record.semanticFingerprintSha256 &&
                existing.canonicalPlan == record.canonicalPlan
            ) {
                return@withLock existing
            }
            throw RegionalJobConflictException(
                "Regional job '${record.jobId}' already exists with a different plan identity.",
            )
        }
        if (ids.size >= MAXIMUM_REGIONAL_JOBS) {
            throw RegionalJobStorageException("The regional job store reached its 64-record limit.")
        }
        val existingRecords = ids.filter { existingId -> existingId != record.jobId }.map { existingId ->
            try {
                decodeRecord(existingId, readRecordBytes(existingId))
            } catch (error: Exception) {
                throw RegionalJobStorageException(
                    "A new regional job cannot claim storage while durable record '$existingId' is unreadable.",
                    error,
                )
            }
        }
        val requestedPaths = record.canonicalPlan.artifacts.mapTo(hashSetOf()) { it.logicalRelativePath }
        val duplicateActivePlan = existingRecords.firstOrNull { existing ->
            !existing.state.isTerminal &&
                (
                    existing.planFingerprintSha256 == record.planFingerprintSha256 ||
                        existing.canonicalPlan.artifacts.any { it.logicalRelativePath in requestedPaths }
                    )
        }
        if (duplicateActivePlan != null) {
            throw RegionalJobConflictException(
                "Regional job '${duplicateActivePlan.jobId}' already owns this active plan or logical artifact path.",
            )
        }
        val payload = encodeRecord(record)
        try {
            storage.createAtomically(record.jobId, payload)
            verifyCommittedRecord(record, payload)
        } catch (error: RegionalJobConflictException) {
            throw error
        } catch (error: Exception) {
            throw RegionalJobStorageException(
                "The regional job create outcome could not be confirmed; reload the durable store.",
                error,
            )
        }
        record
    }

    suspend fun update(
        jobId: String,
        expectedRevision: Long,
        transform: (RegionalJobRecordV1) -> RegionalJobRecordV1,
    ): RegionalJobRecordV1 = operationMutex.withLock {
        validateJobId(jobId)
        val current = if (storage.exists(jobId)) {
            decodeRecord(jobId, readRecordBytes(jobId))
        } else {
            throw RegionalJobConflictException("Regional job '$jobId' does not exist.")
        }
        if (current.revision != expectedRevision) {
            throw RegionalJobConflictException(
                "Regional job '$jobId' changed from revision $expectedRevision to ${current.revision}.",
            )
        }
        val updated = transform(current)
        try {
            validateRegionalJobMutation(current, updated)
        } catch (error: IllegalArgumentException) {
            throw RegionalJobConflictException(
                error.message ?: "The regional job update is invalid.",
            )
        }
        val payload = encodeRecord(updated)
        try {
            storage.replaceAtomically(jobId, payload)
            verifyCommittedRecord(updated, payload)
        } catch (error: Exception) {
            throw RegionalJobStorageException(
                "The regional job update outcome could not be confirmed; reload the durable record.",
                error,
            )
        }
        updated
    }

    private fun loadSnapshotLocked(): RegionalJobStoreSnapshot {
        val jobs = mutableListOf<RegionalJobRecordV1>()
        val unreadable = mutableListOf<String>()
        listIdsLocked().forEach { jobId ->
            try {
                jobs += decodeRecord(jobId, readRecordBytes(jobId))
            } catch (_: Exception) {
                unreadable += jobId
            }
        }
        return RegionalJobStoreSnapshot(
            jobs = jobs.sortedWith(compareBy(RegionalJobRecordV1::createdAtEpochMillis, RegionalJobRecordV1::jobId)),
            unreadableJobIds = unreadable.sorted(),
        )
    }

    private fun listIdsLocked(): List<String> {
        val ids = try {
            storage.listIds(MAXIMUM_REGIONAL_JOBS + 1)
        } catch (error: Exception) {
            throw RegionalJobStorageException("The regional job directory could not be listed.", error)
        }
        if (ids.size > MAXIMUM_REGIONAL_JOBS) {
            throw RegionalJobStorageException("The regional job store exceeds its 64-record limit.")
        }
        if (ids.distinct().size != ids.size || ids.any { !REGIONAL_JOB_ID_PATTERN.matches(it) }) {
            throw RegionalJobStorageException("The regional job directory contains invalid record identities.")
        }
        return ids.sorted()
    }

    private fun readRecordBytes(jobId: String): ByteArray = try {
        storage.read(jobId, MAXIMUM_REGIONAL_JOB_RECORD_BYTES)
    } catch (error: Exception) {
        throw RegionalJobStorageException("Regional job '$jobId' could not be read safely.", error)
    }

    private fun decodeRecord(jobId: String, payload: ByteArray): RegionalJobRecordV1 {
        if (payload.isEmpty() || payload.size > MAXIMUM_REGIONAL_JOB_RECORD_BYTES) {
            throw RegionalJobStorageException("Regional job '$jobId' exceeds its record limit.")
        }
        val envelope = try {
            REGIONAL_JOB_HEADER_JSON.decodeFromString<RegionalJobSchemaHeader>(decodeStrictUtf8(payload))
        } catch (error: Exception) {
            throw RegionalJobStorageException("Regional job '$jobId' is not valid UTF-8 JSON.", error)
        }
        if (envelope.schemaVersion != REGIONAL_JOB_SCHEMA_VERSION) {
            throw RegionalJobStorageException("Regional job '$jobId' uses an unsupported schema.")
        }
        val record = try {
            REGIONAL_JOB_JSON.decodeFromString<RegionalJobRecordV1>(decodeStrictUtf8(payload))
        } catch (error: Exception) {
            throw RegionalJobStorageException("Regional job '$jobId' is invalid. Its bytes were preserved.", error)
        }
        if (record.jobId != jobId) {
            throw RegionalJobStorageException("Regional job '$jobId' does not match its filename identity.")
        }
        return record
    }

    private fun encodeRecord(record: RegionalJobRecordV1): ByteArray {
        val payload = try {
            REGIONAL_JOB_JSON.encodeToString(RegionalJobRecordV1.serializer(), record)
                .toByteArray(StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw RegionalJobStorageException("The regional job could not be encoded.", error)
        }
        if (payload.isEmpty() || payload.size > MAXIMUM_REGIONAL_JOB_RECORD_BYTES) {
            throw RegionalJobStorageException("The regional job exceeds its 256 KiB record limit.")
        }
        return payload
    }

    private fun verifyCommittedRecord(expected: RegionalJobRecordV1, expectedPayload: ByteArray) {
        val committed = storage.read(expected.jobId, MAXIMUM_REGIONAL_JOB_RECORD_BYTES)
        if (!committed.contentEquals(expectedPayload) || decodeRecord(expected.jobId, committed) != expected) {
            throw IOException("The committed regional job failed readback verification.")
        }
    }

    private fun validateNewRecord(record: RegionalJobRecordV1) {
        if (
            record.revision != 0L ||
            record.state !in setOf(RegionalJobState.DRAFT, RegionalJobState.ENQUEUE_PENDING) ||
            record.currentArtifactIndex != 0 ||
            record.networkBytesTransferred != 0L ||
            record.artifactAttemptCounts.any { it != 0 } ||
            record.checkpointReferences.isNotEmpty() ||
            record.artifactOutcomes.isNotEmpty() ||
            record.createdAtEpochMillis != record.updatedAtEpochMillis ||
            record.cancelRequested ||
            record.terminalProblem != null
        ) {
            throw RegionalJobConflictException(
                "A newly created regional job must be an untouched revision-zero draft or enqueue intent.",
            )
        }
        if (!RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(record.canonicalPlan)) {
            throw RegionalJobConflictException(
                "A newly created regional job must match the installed fixed catalog.",
            )
        }
    }
}

/** Android private-storage adapter. This is a durable foundation; it does not schedule work. */
class FileRegionalJobRepository private constructor(
    private val persistence: RegionalJobStorePersistence,
    private val ioDispatcher: CoroutineDispatcher,
) : RegionalJobRepository {
    constructor(context: Context) : this(
        persistence = RegionalJobStorePersistence(
            storage = AndroidAtomicRegionalJobRecordStorage(
                File(context.applicationContext.noBackupFilesDir, REGIONAL_JOB_DIRECTORY),
            ),
            operationMutex = APPLICATION_REGIONAL_JOB_MUTEX,
        ),
        ioDispatcher = Dispatchers.IO,
    )

    internal constructor(
        rootDirectory: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        persistence = RegionalJobStorePersistence(
            storage = AndroidAtomicRegionalJobRecordStorage(rootDirectory),
            operationMutex = APPLICATION_REGIONAL_JOB_MUTEX,
        ),
        ioDispatcher = ioDispatcher,
    )

    override suspend fun loadSnapshot(): RegionalJobStoreSnapshot = withContext(ioDispatcher) {
        persistence.loadSnapshot()
    }

    override suspend fun get(jobId: String): RegionalJobRecordV1? = withContext(ioDispatcher) {
        persistence.get(jobId)
    }

    override suspend fun create(record: RegionalJobRecordV1): RegionalJobRecordV1 = withContext(ioDispatcher) {
        persistence.create(record)
    }

    override suspend fun update(
        jobId: String,
        expectedRevision: Long,
        transform: (RegionalJobRecordV1) -> RegionalJobRecordV1,
    ): RegionalJobRecordV1 = withContext(ioDispatcher) {
        persistence.update(jobId, expectedRevision, transform)
    }

    private companion object {
        val APPLICATION_REGIONAL_JOB_MUTEX = Mutex()
    }
}

private class AndroidAtomicRegionalJobRecordStorage(
    private val rootDirectory: File,
) : RegionalJobRecordStorage {
    override fun listIds(maximumRecords: Int): List<String> {
        ensureRoot()
        val files = rootDirectory.listFiles()
            ?: throw IOException("The private regional job directory could not be listed.")
        val ids = files.mapNotNull { file ->
            val name = file.name
            when {
                name.endsWith(REGIONAL_JOB_FILE_SUFFIX) ->
                    name.removeSuffix(REGIONAL_JOB_FILE_SUFFIX).takeIf(REGIONAL_JOB_ID_PATTERN::matches)
                name.endsWith("$REGIONAL_JOB_FILE_SUFFIX.bak") ->
                    name.removeSuffix("$REGIONAL_JOB_FILE_SUFFIX.bak").takeIf(REGIONAL_JOB_ID_PATTERN::matches)
                else -> null
            }
        }.distinct().sorted()
        return ids.take(maximumRecords)
    }

    override fun exists(jobId: String): Boolean {
        val target = target(jobId)
        return target.isFile || File("${target.path}.bak").isFile
    }

    override fun read(jobId: String, maximumBytes: Int): ByteArray =
        AtomicFile(target(jobId)).openRead().use { input -> input.readBounded(maximumBytes) }

    override fun createAtomically(jobId: String, payload: ByteArray) {
        if (exists(jobId)) throw RegionalJobConflictException("Regional job '$jobId' already exists.")
        writeAtomically(jobId, payload)
    }

    override fun replaceAtomically(jobId: String, payload: ByteArray) {
        if (!exists(jobId)) throw RegionalJobConflictException("Regional job '$jobId' does not exist.")
        writeAtomically(jobId, payload)
    }

    private fun writeAtomically(jobId: String, payload: ByteArray) {
        val atomicFile = AtomicFile(target(jobId))
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(payload)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Exception) {
            try {
                output?.let(atomicFile::failWrite)
            } catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
            }
            throw IOException("Atomic regional job write failed.", error)
        }
    }

    private fun target(jobId: String): File {
        validateJobId(jobId)
        ensureRoot()
        val target = File(rootDirectory, "$jobId$REGIONAL_JOB_FILE_SUFFIX").canonicalFile
        if (target.parentFile != rootDirectory.canonicalFile) {
            throw IOException("A regional job path escapes its private root.")
        }
        return target
    }

    private fun ensureRoot() {
        if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) {
            throw IOException("The private regional job directory could not be created.")
        }
        if (rootDirectory.canonicalFile != rootDirectory.absoluteFile) {
            throw IOException("The private regional job directory cannot be a symbolic path.")
        }
    }
}

@Serializable
private data class RegionalJobSchemaHeader(val schemaVersion: Int)

private fun validateJobId(jobId: String) {
    if (!REGIONAL_JOB_ID_PATTERN.matches(jobId)) {
        throw RegionalJobConflictException("The regional job ID is invalid.")
    }
}

private fun decodeStrictUtf8(payload: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(payload))
    .toString()

private fun java.io.InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, JOB_READ_BUFFER_BYTES))
    val buffer = ByteArray(JOB_READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) throw IOException("The regional job record exceeds its read limit.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private val REGIONAL_JOB_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = false
}

private val REGIONAL_JOB_HEADER_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

private val REGIONAL_JOB_ID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

private const val REGIONAL_JOB_DIRECTORY = "datasets/regional/jobs"
private const val REGIONAL_JOB_FILE_SUFFIX = ".json"
private const val MAXIMUM_REGIONAL_JOB_RECORD_BYTES = 256 * 1024
private const val JOB_READ_BUFFER_BYTES = 8 * 1024
