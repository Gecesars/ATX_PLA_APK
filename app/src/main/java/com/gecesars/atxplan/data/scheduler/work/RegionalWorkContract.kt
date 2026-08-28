package com.gecesars.atxplan.data.scheduler.work

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * The complete, scheduler-neutral identity carried by one WorkManager invocation.
 *
 * Scheduler kind and physical identity are intentionally absent. The worker derives those values
 * from its trusted implementation and [androidx.work.WorkerParameters], never from input data.
 */
data class RegionalWorkInputV1(
    val jobId: String,
    val planFingerprintSha256: String,
    val schedulerGeneration: Int,
) {
    init {
        require(REGIONAL_WORK_JOB_ID_PATTERN.matches(jobId)) {
            "A regional WorkManager input contains an invalid job ID."
        }
        require(REGIONAL_WORK_SHA256_PATTERN.matches(planFingerprintSha256)) {
            "A regional WorkManager input requires a lowercase plan SHA-256."
        }
        require(schedulerGeneration in 0..MAXIMUM_REGIONAL_WORK_GENERATION) {
            "A regional WorkManager input contains an invalid scheduler generation."
        }
    }
}

/** Identity recoverable from strict WorkManager tags without trusting arbitrary input data. */
data class RegionalWorkTagIdentityV1(
    val jobId: String,
    val planFingerprintSha256: String,
    val schedulerGeneration: Int,
) {
    init {
        require(REGIONAL_WORK_JOB_ID_PATTERN.matches(jobId)) {
            "A regional WorkManager tag contains an invalid job ID."
        }
        require(REGIONAL_WORK_SHA256_PATTERN.matches(planFingerprintSha256)) {
            "A regional WorkManager tag requires a lowercase plan SHA-256."
        }
        require(schedulerGeneration in 0..MAXIMUM_REGIONAL_WORK_GENERATION) {
            "A regional WorkManager tag contains an invalid scheduler generation."
        }
    }
}

/** Strict identity parsed from one physical WorkManager entry. */
data class RegionalWorkInfoIdentityV1(
    val workRequestId: UUID,
    val jobId: String,
    val planFingerprintSha256: String,
    val schedulerGeneration: Int,
) {
    init {
        val input = RegionalWorkInputV1(
            jobId = jobId,
            planFingerprintSha256 = planFingerprintSha256,
            schedulerGeneration = schedulerGeneration,
        )
        require(workRequestId == RegionalWorkContractV1.deterministicWorkId(input)) {
            "A regional WorkManager entry contains an invalid physical identity."
        }
    }
}

/**
 * Versioned WorkManager wire contract.
 *
 * The version is part of every key and namespace. Input decoding fails closed unless the map has
 * exactly the three expected keys with their exact JVM value types.
 */
object RegionalWorkContractV1 {
    const val GLOBAL_TAG: String = "atx.regional.work.v1"

    internal const val JOB_ID_KEY: String = "atx.regional.work.v1.jobId"
    internal const val PLAN_FINGERPRINT_KEY: String =
        "atx.regional.work.v1.planFingerprintSha256"
    internal const val SCHEDULER_GENERATION_KEY: String =
        "atx.regional.work.v1.schedulerGeneration"

    private const val IDENTITY_SCHEMA: String = "atx-regional-work-request-v1"
    private const val UNIQUE_WORK_PREFIX: String = "atx.regional.work.v1.unique."
    private const val JOB_TAG_PREFIX: String = "atx.regional.work.v1.job."
    private const val PLAN_FINGERPRINT_TAG_PREFIX: String = "atx.regional.work.v1.plan."
    private const val GENERATION_TAG_PREFIX: String = "atx.regional.work.v1.generation."
    private val EXACT_INPUT_KEYS = setOf(
        JOB_ID_KEY,
        PLAN_FINGERPRINT_KEY,
        SCHEDULER_GENERATION_KEY,
    )

    fun encodeInput(input: RegionalWorkInputV1): Data = Data.Builder()
        .putString(JOB_ID_KEY, input.jobId)
        .putString(PLAN_FINGERPRINT_KEY, input.planFingerprintSha256)
        .putInt(SCHEDULER_GENERATION_KEY, input.schedulerGeneration)
        .build()

    fun decodeInput(data: Data): RegionalWorkInputV1? {
        val values = data.keyValueMap
        if (values.keys != EXACT_INPUT_KEYS) return null
        val jobId = values[JOB_ID_KEY] as? String ?: return null
        val fingerprint = values[PLAN_FINGERPRINT_KEY] as? String ?: return null
        val generation = values[SCHEDULER_GENERATION_KEY] as? Int ?: return null
        return try {
            RegionalWorkInputV1(
                jobId = jobId,
                planFingerprintSha256 = fingerprint,
                schedulerGeneration = generation,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Stable custom UUIDv8: the first 128 SHA-256 bits with RFC variant/version bits applied. */
    fun deterministicWorkId(input: RegionalWorkInputV1): UUID {
        val canonicalIdentity = buildString {
            append(IDENTITY_SCHEMA)
            append('\u0000')
            append(input.jobId)
            append('\u0000')
            append(input.planFingerprintSha256)
            append('\u0000')
            append(input.schedulerGeneration)
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalIdentity.toByteArray(Charsets.UTF_8))
            .copyOfRange(0, UUID_BYTES)
        bytes[UUID_VERSION_BYTE_INDEX] = (
            (bytes[UUID_VERSION_BYTE_INDEX].toInt() and UUID_VERSION_CLEAR_MASK) or
                UUID_VERSION_8_BITS
            ).toByte()
        bytes[UUID_VARIANT_BYTE_INDEX] = (
            (bytes[UUID_VARIANT_BYTE_INDEX].toInt() and UUID_VARIANT_CLEAR_MASK) or
                RFC_4122_UUID_VARIANT_BITS
            ).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun uniqueWorkName(input: RegionalWorkInputV1): String =
        "$UNIQUE_WORK_PREFIX${input.jobId}.g${input.schedulerGeneration}"

    fun jobTag(jobId: String): String {
        require(REGIONAL_WORK_JOB_ID_PATTERN.matches(jobId)) {
            "A regional WorkManager job tag requires a valid job ID."
        }
        return "$JOB_TAG_PREFIX$jobId"
    }

    fun generationTag(jobId: String, schedulerGeneration: Int): String {
        require(REGIONAL_WORK_JOB_ID_PATTERN.matches(jobId)) {
            "A regional WorkManager generation tag requires a valid job ID."
        }
        require(schedulerGeneration in 0..MAXIMUM_REGIONAL_WORK_GENERATION) {
            "A regional WorkManager generation tag contains an invalid scheduler generation."
        }
        return "$GENERATION_TAG_PREFIX$jobId.g$schedulerGeneration"
    }

    fun planFingerprintTag(planFingerprintSha256: String): String {
        require(REGIONAL_WORK_SHA256_PATTERN.matches(planFingerprintSha256)) {
            "A regional WorkManager plan tag requires a lowercase plan SHA-256."
        }
        return "$PLAN_FINGERPRINT_TAG_PREFIX$planFingerprintSha256"
    }

    /** The four application-owned tags added to every regional request. */
    fun customTags(input: RegionalWorkInputV1): Set<String> = setOf(
        GLOBAL_TAG,
        jobTag(input.jobId),
        planFingerprintTag(input.planFingerprintSha256),
        generationTag(input.jobId, input.schedulerGeneration),
    )

    /** Exact WorkInfo tags, including WorkManager's automatic worker-class tag. */
    fun expectedWorkInfoTags(identity: RegionalWorkTagIdentityV1): Set<String> = setOf(
        GLOBAL_TAG,
        jobTag(identity.jobId),
        planFingerprintTag(identity.planFingerprintSha256),
        generationTag(identity.jobId, identity.schedulerGeneration),
        RegionalJobWorker::class.java.name,
    )

    fun decodeTags(tags: Set<String>): RegionalWorkTagIdentityV1? {
        if (tags.size != EXPECTED_WORK_INFO_TAG_COUNT || GLOBAL_TAG !in tags) return null
        val jobTags = tags.filter { it.startsWith(JOB_TAG_PREFIX) }
        val fingerprintTags = tags.filter { it.startsWith(PLAN_FINGERPRINT_TAG_PREFIX) }
        val generationTags = tags.filter { it.startsWith(GENERATION_TAG_PREFIX) }
        if (jobTags.size != 1 || fingerprintTags.size != 1 || generationTags.size != 1) return null

        val jobId = jobTags.single().removePrefix(JOB_TAG_PREFIX)
        val fingerprint = fingerprintTags.single().removePrefix(PLAN_FINGERPRINT_TAG_PREFIX)
        val generationTag = generationTags.single()
        val generationSeparator = generationTag.lastIndexOf(".g")
        if (generationSeparator <= GENERATION_TAG_PREFIX.lastIndex) return null
        val generationJobId = generationTag
            .substring(GENERATION_TAG_PREFIX.length, generationSeparator)
        val generation = generationTag
            .substring(generationSeparator + GENERATION_SUFFIX_LENGTH)
            .toIntOrNull() ?: return null
        if (generationJobId != jobId) return null

        val identity = try {
            RegionalWorkTagIdentityV1(
                jobId = jobId,
                planFingerprintSha256 = fingerprint,
                schedulerGeneration = generation,
            )
        } catch (_: IllegalArgumentException) {
            return null
        }
        return identity.takeIf { tags == expectedWorkInfoTags(it) }
    }

    fun decodeWorkInfo(workInfo: WorkInfo): RegionalWorkInfoIdentityV1? =
        decodeWorkInfoIdentity(workInfo.id, workInfo.tags)

    /** Separated from [WorkInfo] construction so strict parsing remains a local JVM-test seam. */
    fun decodeWorkInfoIdentity(
        workRequestId: UUID,
        tags: Set<String>,
    ): RegionalWorkInfoIdentityV1? {
        val identity = decodeTags(tags) ?: return null
        return try {
            RegionalWorkInfoIdentityV1(
                workRequestId = workRequestId,
                jobId = identity.jobId,
                planFingerprintSha256 = identity.planFingerprintSha256,
                schedulerGeneration = identity.schedulerGeneration,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Constructs the exact one-shot physical request. Retry ownership remains outside WorkManager. */
object RegionalWorkRequestFactoryV1 {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    fun create(input: RegionalWorkInputV1): OneTimeWorkRequest {
        val builder = OneTimeWorkRequest.Builder(RegionalJobWorker::class.java)
            .setId(RegionalWorkContractV1.deterministicWorkId(input))
            .setInputData(RegionalWorkContractV1.encodeInput(input))
            .setConstraints(constraints)
        RegionalWorkContractV1.customTags(input).forEach { tag -> builder.addTag(tag) }
        return builder.build()
    }
}

private const val MAXIMUM_REGIONAL_WORK_GENERATION = 1_000
private const val EXPECTED_WORK_INFO_TAG_COUNT = 5
private const val GENERATION_SUFFIX_LENGTH = 2
private const val UUID_BYTES = 16
private const val UUID_VERSION_BYTE_INDEX = 6
private const val UUID_VARIANT_BYTE_INDEX = 8
private const val UUID_VERSION_CLEAR_MASK = 0x0f
private const val UUID_VERSION_8_BITS = 0x80
private const val UUID_VARIANT_CLEAR_MASK = 0x3f
private const val RFC_4122_UUID_VARIANT_BITS = 0x80

private val REGIONAL_WORK_JOB_ID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val REGIONAL_WORK_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
