package com.gecesars.atxplan.domain.dataset

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Passive, integer-coordinate plan contract used for durable jobs and cross-language fixtures.
 *
 * It deliberately does not serialize [RegionalDownloadPlan], whose artifact constructors validate
 * against the currently installed catalog. Historical job records must remain readable when that
 * catalog changes, so compatibility is checked explicitly by [RegionalPlanFingerprint].
 */
@Serializable
data class RegionalCanonicalBoundsV1(
    val westE6: Long,
    val southE6: Long,
    val eastE6: Long,
    val northE6: Long,
) {
    init {
        require(westE6 in -180L * MICRODEGREES_PER_DEGREE..180L * MICRODEGREES_PER_DEGREE) {
            "Canonical west longitude is outside the WGS84 range."
        }
        require(eastE6 in -180L * MICRODEGREES_PER_DEGREE..180L * MICRODEGREES_PER_DEGREE) {
            "Canonical east longitude is outside the WGS84 range."
        }
        require(southE6 in -90L * MICRODEGREES_PER_DEGREE..90L * MICRODEGREES_PER_DEGREE) {
            "Canonical south latitude is outside the WGS84 range."
        }
        require(northE6 in -90L * MICRODEGREES_PER_DEGREE..90L * MICRODEGREES_PER_DEGREE) {
            "Canonical north latitude is outside the WGS84 range."
        }
        require(westE6 < eastE6 && southE6 < northE6) {
            "Canonical regional bounds must have positive width and height."
        }
    }

    fun toRegionalBounds(): RegionalBounds = RegionalBounds(
        west = westE6.toDouble() / MICRODEGREES_PER_DEGREE,
        south = southE6.toDouble() / MICRODEGREES_PER_DEGREE,
        east = eastE6.toDouble() / MICRODEGREES_PER_DEGREE,
        north = northE6.toDouble() / MICRODEGREES_PER_DEGREE,
    )

    companion object {
        fun from(bounds: RegionalBounds): RegionalCanonicalBoundsV1 = RegionalCanonicalBoundsV1(
            westE6 = bounds.west.toCanonicalMicrodegrees(),
            southE6 = bounds.south.toCanonicalMicrodegrees(),
            eastE6 = bounds.east.toCanonicalMicrodegrees(),
            northE6 = bounds.north.toCanonicalMicrodegrees(),
        )
    }
}

@Serializable
data class RegionalCanonicalArtifactV1(
    val selection: RegionalDatasetSelection,
    val datasetId: String,
    val licenseId: String,
    val datasetFamily: String,
    val datasetRelease: String,
    val catalogRevision: Int,
    val dataType: RegionalDataType,
    val fileFormat: RegionalFileFormat,
    val queryVersion: String,
    val normalizerVersion: String,
    val routeId: String,
    val routePolicyVersion: Int,
    val snapshotPolicy: RegionalSnapshotPolicy,
    val maximumCacheAgeMillis: Long?,
    val coverageBounds: RegionalCanonicalBoundsV1,
    val tileSouthDegrees: Int?,
    val tileWestDegrees: Int?,
    val logicalRelativePath: String,
    val requestedUrl: String,
    val httpMethod: RegionalHttpMethod,
    val requestBody: String?,
    val requestBodySha256: String?,
    val contentType: String?,
    val cachePolicy: RegionalArtifactCachePolicy,
    val estimatedBytes: Long,
    val maximumArtifactBytes: Long,
    val optionalWhenNotPublished: Boolean,
) {
    init {
        require(
            JOB_STABLE_ID_PATTERN.matches(datasetId) && JOB_STABLE_ID_PATTERN.matches(licenseId),
        ) { "A canonical artifact has an invalid dataset or license ID." }
        require(
            JOB_STABLE_ID_PATTERN.matches(datasetFamily) &&
                JOB_STABLE_ID_PATTERN.matches(datasetRelease) &&
                JOB_STABLE_ID_PATTERN.matches(queryVersion) &&
                JOB_STABLE_ID_PATTERN.matches(normalizerVersion) &&
                JOB_STABLE_ID_PATTERN.matches(routeId),
        ) { "A canonical artifact has an invalid contract identifier." }
        require(catalogRevision in 1..MAXIMUM_JOB_CATALOG_REVISION) {
            "A canonical artifact catalog revision is outside the supported range."
        }
        require(routePolicyVersion in 1..MAXIMUM_JOB_CATALOG_REVISION) {
            "A canonical artifact route policy is outside the supported range."
        }
        require(isSafeJobHttpsUrl(requestedUrl)) { "A canonical artifact requires a safe HTTPS URL." }
        require(isSafeJobRelativePath(logicalRelativePath)) {
            "A canonical artifact requires a safe logical relative path."
        }
        require(
            estimatedBytes > 0L &&
                maximumArtifactBytes in estimatedBytes..DEFAULT_MAXIMUM_BATCH_BYTES,
        ) {
            "A canonical artifact has inconsistent byte limits."
        }
        require(tileSouthDegrees == null == (tileWestDegrees == null)) {
            "A canonical artifact must declare both tile coordinates or neither."
        }
        require(
            tileSouthDegrees == null ||
                tileSouthDegrees in -90..89 && tileWestDegrees in -180..179,
        ) { "A canonical artifact tile origin is outside the WGS84 grid." }
        when (httpMethod) {
            RegionalHttpMethod.GET -> require(
                requestBody == null && requestBodySha256 == null && contentType == null,
            ) { "A canonical GET artifact cannot contain a request body." }

            RegionalHttpMethod.POST -> require(
                requestBody != null &&
                    requestBody.length in 1..MAXIMUM_CANONICAL_REQUEST_BODY_CHARACTERS &&
                    requestBody.none(Char::isISOControl) &&
                    requestBodySha256 != null &&
                    JOB_SHA256_PATTERN.matches(requestBodySha256) &&
                    requestBody.sha256Hex() == requestBodySha256 &&
                    contentType != null &&
                    contentType.length in 1..MAXIMUM_CANONICAL_CONTENT_TYPE_CHARACTERS &&
                    contentType.none(Char::isISOControl),
            ) { "A canonical POST artifact has an invalid body or content type." }
        }
        when (snapshotPolicy) {
            RegionalSnapshotPolicy.IMMUTABLE_RELEASE -> require(
                maximumCacheAgeMillis == null &&
                    cachePolicy == RegionalArtifactCachePolicy.IMMUTABLE_RELEASE,
            ) { "An immutable canonical artifact cannot have a live cache policy or age." }

            RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE -> require(
                maximumCacheAgeMillis != null &&
                    maximumCacheAgeMillis in 1L..MAXIMUM_CANONICAL_CACHE_AGE_MILLIS &&
                    cachePolicy in setOf(
                        RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE,
                        RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH,
                    ),
            ) { "A live canonical artifact requires a bounded live cache policy and age." }
        }
    }
}

@Serializable
data class RegionalCanonicalPlanV1(
    val contractSchemaVersion: Int = REGIONAL_CANONICAL_PLAN_SCHEMA_VERSION,
    val resourceProfileId: String = ANDROID_REGIONAL_RESOURCE_PROFILE_ID,
    val catalogRevision: Int,
    val requestBounds: RegionalCanonicalBoundsV1,
    val requestReason: String,
    val liveSnapshotRefresh: Boolean,
    val selections: List<RegionalDatasetSelection>,
    val artifacts: List<RegionalCanonicalArtifactV1>,
    val licenseSnapshots: List<RegionalDatasetLicense>,
    val estimatedBytes: Long,
    val maximumBatchBytes: Long,
) {
    init {
        require(contractSchemaVersion == REGIONAL_CANONICAL_PLAN_SCHEMA_VERSION) {
            "The canonical regional-plan schema is unsupported."
        }
        require(resourceProfileId == ANDROID_REGIONAL_RESOURCE_PROFILE_ID) {
            "The canonical regional resource profile is unsupported."
        }
        require(catalogRevision in 1..MAXIMUM_JOB_CATALOG_REVISION) {
            "The canonical plan catalog revision is outside the supported range."
        }
        require(
            requestReason == normalizeRegionalJobReason(requestReason) &&
                requestReason.length in 1..MAX_REASON_LENGTH &&
                requestReason.none(Char::isISOControl),
        ) { "The canonical regional reason is invalid or not normalized." }
        require(
            selections.isNotEmpty() &&
                selections.size == selections.distinct().size &&
                selections == selections.sortedBy(RegionalDatasetSelection::name),
        ) { "Canonical regional selections must be unique and ordered by stable ID." }
        require(artifacts.size in 1..MAX_ARTIFACTS_PER_PLAN) {
            "A canonical regional plan has an invalid artifact count."
        }
        require(
            artifacts == artifacts.sortedWith(CANONICAL_ARTIFACT_COMPARATOR) &&
                artifacts.distinct().size == artifacts.size &&
                artifacts.distinctBy(RegionalCanonicalArtifactV1::logicalRelativePath).size == artifacts.size,
        ) { "Canonical regional artifacts must be deterministic and have unique logical paths." }
        require(
            artifacts.map(RegionalCanonicalArtifactV1::selection)
                .distinct()
                .sortedBy(RegionalDatasetSelection::name) == selections &&
                artifacts.all { artifact -> artifact.catalogRevision == catalogRevision },
        ) { "Canonical regional artifacts do not exactly match the plan selections or catalog." }
        require(
            licenseSnapshots.isNotEmpty() &&
                licenseSnapshots.size == licenseSnapshots.distinctBy(RegionalDatasetLicense::id).size &&
                licenseSnapshots == licenseSnapshots.sortedBy(RegionalDatasetLicense::id) &&
                licenseSnapshots.map(RegionalDatasetLicense::id) ==
                artifacts.map(RegionalCanonicalArtifactV1::licenseId).distinct().sorted(),
        ) { "Canonical regional license snapshots must exactly match the artifact licenses." }
        require(
            artifacts.map(RegionalCanonicalArtifactV1::toSemanticFingerprintArtifact)
                .distinct()
                .size == artifacts.size,
        ) { "Canonical regional artifacts contain a duplicate semantic identity." }
        val liveArtifacts = artifacts.filter {
            it.snapshotPolicy == RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE
        }
        require(
            if (liveSnapshotRefresh) {
                liveArtifacts.isNotEmpty() && liveArtifacts.all {
                    it.cachePolicy == RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH
                }
            } else {
                liveArtifacts.none {
                    it.cachePolicy == RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH
                }
            },
        ) { "The canonical live-refresh choice does not match its artifact cache policies." }
        require(estimatedBytes > 0L && estimatedBytes == artifacts.checkedEstimatedByteSum()) {
            "The canonical regional estimate is inconsistent."
        }
        require(maximumBatchBytes in 1L..DEFAULT_MAXIMUM_BATCH_BYTES && estimatedBytes <= maximumBatchBytes) {
            "The canonical regional plan exceeds its batch limit."
        }
    }

    fun toRequest(): RegionalDatasetRequest = RegionalDatasetRequest(
        bounds = requestBounds.toRegionalBounds(),
        selections = selections.toSet(),
        reason = requestReason,
        liveSnapshotRefresh = liveSnapshotRefresh,
    )
}

@Serializable
private data class RegionalSemanticArtifactFingerprintV1(
    val coverageBounds: RegionalCanonicalBoundsV1,
    val dataKindId: String,
    val datasetFamily: String,
    val datasetRelease: String,
    val fileFormatId: String,
    val normalizerVersion: String,
    val queryVersion: String,
    val snapshotPolicyId: String,
    val tileSouthDegrees: Int?,
    val tileWestDegrees: Int?,
)

@Serializable
private data class RegionalSemanticPlanFingerprintV1(
    val artifacts: List<RegionalSemanticArtifactFingerprintV1>,
    val requestBounds: RegionalCanonicalBoundsV1,
    val schemaId: String = REGIONAL_SEMANTIC_FINGERPRINT_SCHEMA_ID,
)

@Serializable
private data class RegionalExecutionArtifactFingerprintV1(
    val cachePolicy: RegionalArtifactCachePolicy,
    val catalogRevision: Int,
    val contentType: String?,
    val datasetId: String,
    val estimatedBytes: Long,
    val httpMethod: RegionalHttpMethod,
    val licenseId: String,
    val logicalRelativePath: String,
    val maximumArtifactBytes: Long,
    val maximumCacheAgeMillis: Long?,
    val optionalWhenNotPublished: Boolean,
    val requestBodySha256: String?,
    val requestedUrl: String,
    val routeId: String,
    val routePolicyVersion: Int,
)

@Serializable
private data class RegionalExecutionPlanFingerprintV1(
    val artifacts: List<RegionalExecutionArtifactFingerprintV1>,
    val catalogRevision: Int,
    val estimatedBytes: Long,
    val licenseSnapshots: List<RegionalDatasetLicense>,
    val maximumArtifacts: Int = MAX_ARTIFACTS_PER_PLAN,
    val maximumBatchBytes: Long,
    val resourceProfileId: String,
    val schemaId: String = REGIONAL_EXECUTION_FINGERPRINT_SCHEMA_ID,
    val semanticFingerprintSha256: String,
)

private fun RegionalCanonicalArtifactV1.toSemanticFingerprintArtifact(): RegionalSemanticArtifactFingerprintV1 =
    RegionalSemanticArtifactFingerprintV1(
        coverageBounds = coverageBounds,
        dataKindId = dataType.semanticId(),
        datasetFamily = datasetFamily,
        datasetRelease = semanticReleaseAlias(datasetFamily, datasetRelease),
        fileFormatId = fileFormat.semanticId(),
        normalizerVersion = normalizerVersion,
        queryVersion = queryVersion,
        snapshotPolicyId = snapshotPolicy.semanticId(),
        tileSouthDegrees = tileSouthDegrees,
        tileWestDegrees = tileWestDegrees,
    )

private fun RegionalCanonicalPlanV1.toSemanticFingerprintPlan(): RegionalSemanticPlanFingerprintV1 =
    RegionalSemanticPlanFingerprintV1(
        artifacts = artifacts.map(RegionalCanonicalArtifactV1::toSemanticFingerprintArtifact)
            .sortedWith(SEMANTIC_ARTIFACT_COMPARATOR),
        requestBounds = requestBounds,
    )

private fun RegionalCanonicalPlanV1.toExecutionFingerprintPlan(): RegionalExecutionPlanFingerprintV1 =
    RegionalExecutionPlanFingerprintV1(
        artifacts = artifacts.map { artifact ->
            RegionalExecutionArtifactFingerprintV1(
                cachePolicy = artifact.cachePolicy,
                catalogRevision = artifact.catalogRevision,
                contentType = artifact.contentType,
                datasetId = artifact.datasetId,
                estimatedBytes = artifact.estimatedBytes,
                httpMethod = artifact.httpMethod,
                licenseId = artifact.licenseId,
                logicalRelativePath = artifact.logicalRelativePath,
                maximumArtifactBytes = artifact.maximumArtifactBytes,
                maximumCacheAgeMillis = artifact.maximumCacheAgeMillis,
                optionalWhenNotPublished = artifact.optionalWhenNotPublished,
                requestBodySha256 = artifact.requestBodySha256,
                requestedUrl = artifact.requestedUrl,
                routeId = artifact.routeId,
                routePolicyVersion = artifact.routePolicyVersion,
            )
        },
        catalogRevision = catalogRevision,
        estimatedBytes = estimatedBytes,
        licenseSnapshots = licenseSnapshots,
        maximumBatchBytes = maximumBatchBytes,
        resourceProfileId = resourceProfileId,
        semanticFingerprintSha256 = RegionalPlanFingerprint.semantic(this),
    )

private fun RegionalDataType.semanticId(): String = when (this) {
    RegionalDataType.DIGITAL_SURFACE_MODEL -> "surface-elevation-dsm"
    RegionalDataType.LAND_COVER -> "categorical-land-cover"
    RegionalDataType.BUILDING_FOOTPRINTS -> "building-footprints"
}

private fun RegionalFileFormat.semanticId(): String = when (this) {
    RegionalFileFormat.COG_GEOTIFF -> "cog-geotiff"
    RegionalFileFormat.OVERPASS_JSON -> "overpass-json"
}

private fun RegionalSnapshotPolicy.semanticId(): String = when (this) {
    RegionalSnapshotPolicy.IMMUTABLE_RELEASE -> "immutable-release"
    RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE -> "live-snapshot"
}

private fun semanticReleaseAlias(datasetFamily: String, datasetRelease: String): String = when {
    datasetFamily == "openstreetmap-buildings" && datasetRelease == "live" -> "live-snapshot"
    else -> datasetRelease
}

/** Canonical semantic and exact Android execution identities for one reviewed plan. */
object RegionalPlanFingerprint {
    fun canonicalize(plan: RegionalDownloadPlan): RegionalCanonicalPlanV1 {
        val catalogRevisions = plan.artifacts.map { it.source.catalogRevision }.distinct()
        require(catalogRevisions.size == 1) { "A regional plan must use one catalog revision." }
        val artifacts = plan.artifacts.map { artifact ->
            RegionalCanonicalArtifactV1(
                selection = artifact.source.selection,
                datasetId = artifact.source.datasetId,
                licenseId = artifact.source.license.id,
                datasetFamily = artifact.source.datasetFamily,
                datasetRelease = artifact.source.datasetRelease,
                catalogRevision = artifact.source.catalogRevision,
                dataType = artifact.source.dataType,
                fileFormat = artifact.source.fileFormat,
                queryVersion = artifact.source.queryVersion,
                normalizerVersion = artifact.source.normalizerVersion,
                routeId = artifact.source.routeId,
                routePolicyVersion = artifact.source.routePolicyVersion,
                snapshotPolicy = artifact.source.snapshotPolicy,
                maximumCacheAgeMillis = artifact.source.maximumCacheAgeMillis,
                coverageBounds = RegionalCanonicalBoundsV1.from(artifact.coverageBounds),
                tileSouthDegrees = artifact.south,
                tileWestDegrees = artifact.west,
                logicalRelativePath = artifact.relativePath,
                requestedUrl = artifact.url,
                httpMethod = artifact.httpMethod,
                requestBody = artifact.requestBody,
                requestBodySha256 = artifact.requestBody?.sha256Hex(),
                contentType = artifact.contentType,
                cachePolicy = artifact.cachePolicy,
                estimatedBytes = artifact.estimatedBytes,
                maximumArtifactBytes = artifact.source.maximumArtifactBytes,
                optionalWhenNotPublished = artifact.source.optionalWhenNotPublished,
            )
        }.sortedWith(CANONICAL_ARTIFACT_COMPARATOR)
        return RegionalCanonicalPlanV1(
            catalogRevision = catalogRevisions.single(),
            requestBounds = RegionalCanonicalBoundsV1.from(plan.request.bounds),
            requestReason = normalizeRegionalJobReason(plan.request.reason),
            liveSnapshotRefresh = plan.request.liveSnapshotRefresh,
            selections = plan.request.selections.sortedBy(RegionalDatasetSelection::name),
            artifacts = artifacts,
            licenseSnapshots = plan.licenses.sortedBy(RegionalDatasetLicense::id),
            estimatedBytes = plan.estimatedBytes,
            maximumBatchBytes = plan.maximumBatchBytes,
        )
    }

    fun canonicalJson(plan: RegionalCanonicalPlanV1): String {
        val element = CANONICAL_PLAN_JSON.encodeToJsonElement(RegionalCanonicalPlanV1.serializer(), plan)
        return canonicalJsonElement(element)
    }

    fun semanticCanonicalJson(plan: RegionalCanonicalPlanV1): String = canonicalJsonElement(
        CANONICAL_PLAN_JSON.encodeToJsonElement(
            RegionalSemanticPlanFingerprintV1.serializer(),
            plan.toSemanticFingerprintPlan(),
        ),
    )

    fun executionCanonicalJson(plan: RegionalCanonicalPlanV1): String = canonicalJsonElement(
        CANONICAL_PLAN_JSON.encodeToJsonElement(
            RegionalExecutionPlanFingerprintV1.serializer(),
            plan.toExecutionFingerprintPlan(),
        ),
    )

    fun semantic(plan: RegionalDownloadPlan): String = semantic(canonicalize(plan))

    fun semantic(plan: RegionalCanonicalPlanV1): String = semanticCanonicalJson(plan).sha256Hex()

    fun calculate(plan: RegionalDownloadPlan): String = calculate(canonicalize(plan))

    fun calculate(plan: RegionalCanonicalPlanV1): String = executionCanonicalJson(plan).sha256Hex()

    fun isCompatibleWithCurrentCatalog(plan: RegionalCanonicalPlanV1): Boolean = try {
        val rebuilt = RegionalDatasetPlanner(plan.maximumBatchBytes).plan(plan.toRequest())
        canonicalize(rebuilt) == plan
    } catch (_: Exception) {
        false
    }
}

@Serializable
data class RegionalAcceptedLicenseSnapshotV1(
    val license: RegionalDatasetLicense,
    val acceptedAtEpochMillis: Long,
) {
    init {
        require(acceptedAtEpochMillis >= 0L) { "A regional license acceptance time cannot be negative." }
    }
}

@Serializable
enum class RegionalJobSchedulerKind {
    UNASSIGNED,
    USER_INITIATED_DATA_TRANSFER,
    WORK_MANAGER_FOREGROUND,
}

@Serializable
enum class RegionalJobState {
    DRAFT,
    ENQUEUE_PENDING,
    QUEUED,
    RUNNING_DOWNLOAD,
    RUNNING_VERIFY,
    RUNNING_PROCESS,
    PAUSED_CONSTRAINT,
    SUCCEEDED,
    FAILED,
    CANCELED,
    ORPHANED,
    ;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, CANCELED, ORPHANED)
}

@Serializable
enum class RegionalJobCheckpointKind {
    TRANSFER_PARTIAL,
    TRANSFER_COMPLETE,
    VERIFIED_RAW,
    PROCESSED_OUTPUT,
}

@Serializable
data class RegionalJobCheckpointReferenceV1(
    val artifactIndex: Int,
    val kind: RegionalJobCheckpointKind,
    val relativePath: String,
    val bytes: Long,
    val sha256: String? = null,
) {
    init {
        require(artifactIndex in 0 until MAX_ARTIFACTS_PER_PLAN) {
            "A regional checkpoint artifact index is invalid."
        }
        require(isSafeJobRelativePath(relativePath)) { "A regional checkpoint path is unsafe." }
        require(bytes > 0L) { "A regional checkpoint must reference positive bytes." }
        require(sha256 == null || JOB_SHA256_PATTERN.matches(sha256)) {
            "A regional checkpoint SHA-256 is invalid."
        }
        if (kind == RegionalJobCheckpointKind.TRANSFER_PARTIAL) {
            require(sha256 == null) { "A partial transfer cannot claim a final content hash." }
        } else {
            require(sha256 != null) { "A completed regional checkpoint requires SHA-256." }
        }
    }
}

@Serializable
enum class RegionalJobArtifactOutcomeKind {
    READY,
    EXISTING,
    OPTIONAL_NOT_FOUND,
}

/** Bounded evidence that one artifact result was committed to the regional inventory. */
@Serializable
data class RegionalJobArtifactOutcomeV1(
    val artifactIndex: Int,
    val kind: RegionalJobArtifactOutcomeKind,
    val inventoryEntrySha256: String,
) {
    init {
        require(artifactIndex in 0 until MAX_ARTIFACTS_PER_PLAN) {
            "A regional artifact outcome index is invalid."
        }
        require(JOB_SHA256_PATTERN.matches(inventoryEntrySha256)) {
            "A regional artifact outcome requires a lowercase inventory-entry SHA-256."
        }
    }
}

@Serializable
data class RegionalJobProblemV1(
    val code: String,
    val message: String,
    val retryableByUser: Boolean,
) {
    init {
        require(JOB_STABLE_ID_PATTERN.matches(code)) { "A regional job problem code is invalid." }
        require(
            message.length in 1..MAXIMUM_JOB_PROBLEM_CHARACTERS &&
                message.none(Char::isISOControl),
        ) { "A regional job problem message is invalid." }
    }
}

@Serializable
data class RegionalJobRecordV1(
    val schemaVersion: Int = REGIONAL_JOB_SCHEMA_VERSION,
    val jobId: String,
    val revision: Long,
    val semanticFingerprintSha256: String,
    val planFingerprintSha256: String,
    val catalogRevision: Int,
    val canonicalPlan: RegionalCanonicalPlanV1,
    val acceptedLicenseSnapshots: List<RegionalAcceptedLicenseSnapshotV1>,
    val schedulerKind: RegionalJobSchedulerKind,
    val schedulerGeneration: Int,
    val schedulerIdentity: String?,
    val state: RegionalJobState,
    val currentArtifactIndex: Int,
    val networkBytesTransferred: Long,
    val artifactAttemptCounts: List<Int>,
    val checkpointReferences: List<RegionalJobCheckpointReferenceV1>,
    val artifactOutcomes: List<RegionalJobArtifactOutcomeV1>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val cancelRequested: Boolean,
    val terminalProblem: RegionalJobProblemV1?,
) {
    init {
        require(schemaVersion == REGIONAL_JOB_SCHEMA_VERSION) { "The regional job schema is unsupported." }
        require(JOB_ID_PATTERN.matches(jobId)) { "A regional job ID is invalid." }
        require(revision >= 0L) { "A regional job revision cannot be negative." }
        require(
            JOB_SHA256_PATTERN.matches(semanticFingerprintSha256) &&
                semanticFingerprintSha256 == RegionalPlanFingerprint.semantic(canonicalPlan),
        ) { "The regional job semantic fingerprint is invalid." }
        require(
            JOB_SHA256_PATTERN.matches(planFingerprintSha256) &&
                planFingerprintSha256 == RegionalPlanFingerprint.calculate(canonicalPlan),
        ) { "The regional job plan fingerprint is invalid." }
        require(catalogRevision == canonicalPlan.catalogRevision) {
            "The regional job catalog revision does not match its plan."
        }
        require(
            acceptedLicenseSnapshots.map(RegionalAcceptedLicenseSnapshotV1::license) ==
                canonicalPlan.licenseSnapshots &&
                acceptedLicenseSnapshots.all { it.acceptedAtEpochMillis <= createdAtEpochMillis },
        ) { "The regional job does not contain the exact reviewed license snapshots." }
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Regional job timestamps are inconsistent."
        }
        require(schedulerGeneration in 0..MAXIMUM_SCHEDULER_GENERATION) {
            "The regional scheduler generation is outside its bounded range."
        }
        require(currentArtifactIndex in 0..canonicalPlan.artifacts.size) {
            "The regional job artifact index is outside its plan."
        }
        val maximumTransferBytes = canonicalPlan.artifacts.checkedMaximumTransferByteSum()
        require(networkBytesTransferred in 0L..maximumTransferBytes) {
            "Regional job cumulative network bytes exceed its bounded retry plan."
        }
        require(
            artifactAttemptCounts.size == canonicalPlan.artifacts.size &&
                artifactAttemptCounts.zip(canonicalPlan.artifacts).all { (attempts, artifact) ->
                    attempts in 0..artifact.maximumTransferAttempts()
                },
        ) { "Regional job attempt counters are invalid." }
        require(
            checkpointReferences.size <= MAXIMUM_JOB_CHECKPOINTS &&
                checkpointReferences.all { it.artifactIndex < canonicalPlan.artifacts.size } &&
                checkpointReferences.all { it.artifactIndex <= currentArtifactIndex } &&
                checkpointReferences.distinctBy { it.artifactIndex to it.kind }.size ==
                checkpointReferences.size &&
                checkpointReferences.all { checkpoint ->
                    checkpoint.isStructurallyValidFor(canonicalPlan.artifacts[checkpoint.artifactIndex])
                },
        ) { "Regional job checkpoint references are inconsistent or exceed artifact bounds."
        }
        require(
            artifactOutcomes == artifactOutcomes.sortedBy(RegionalJobArtifactOutcomeV1::artifactIndex) &&
                artifactOutcomes.distinctBy(RegionalJobArtifactOutcomeV1::artifactIndex).size ==
                artifactOutcomes.size &&
                artifactOutcomes.all { outcome ->
                    outcome.artifactIndex < currentArtifactIndex &&
                        (
                            outcome.kind != RegionalJobArtifactOutcomeKind.OPTIONAL_NOT_FOUND ||
                                canonicalPlan.artifacts[outcome.artifactIndex].optionalWhenNotPublished
                            )
                },
        ) { "Regional job artifact outcomes are inconsistent with committed progress."
        }
        require(schedulerIdentity == null || isBoundedSchedulerIdentity(schedulerIdentity)) {
            "The regional scheduler identity is invalid."
        }
        when (state) {
            RegionalJobState.DRAFT -> require(
                schedulerKind == RegionalJobSchedulerKind.UNASSIGNED &&
                    schedulerGeneration == 0 &&
                    schedulerIdentity == null,
            ) { "A draft regional job cannot already have a scheduler." }

            RegionalJobState.ENQUEUE_PENDING -> require(
                schedulerKind != RegionalJobSchedulerKind.UNASSIGNED && schedulerIdentity == null,
            ) { "An enqueue-pending regional job requires a selected scheduler without an identity." }

            RegionalJobState.QUEUED,
            RegionalJobState.RUNNING_DOWNLOAD,
            RegionalJobState.RUNNING_VERIFY,
            RegionalJobState.RUNNING_PROCESS,
            RegionalJobState.PAUSED_CONSTRAINT,
            RegionalJobState.SUCCEEDED,
            -> require(
                schedulerKind != RegionalJobSchedulerKind.UNASSIGNED && schedulerIdentity != null,
            ) { "An active or successful regional job requires a scheduler identity." }

            RegionalJobState.FAILED,
            RegionalJobState.CANCELED,
            RegionalJobState.ORPHANED,
            -> Unit
        }
        require((state in setOf(RegionalJobState.FAILED, RegionalJobState.ORPHANED)) == (terminalProblem != null)) {
            "A failed or orphaned regional job requires exactly one terminal problem."
        }
        if (state == RegionalJobState.CANCELED) {
            require(cancelRequested) { "A canceled regional job must retain its cancellation request." }
        }
        if (state == RegionalJobState.SUCCEEDED) {
            require(
                currentArtifactIndex == canonicalPlan.artifacts.size &&
                    artifactOutcomes.map(RegionalJobArtifactOutcomeV1::artifactIndex) ==
                    canonicalPlan.artifacts.indices.toList() &&
                    !cancelRequested,
            ) {
                "A successful regional job requires a committed outcome for every artifact."
            }
        } else if (!state.isTerminal) {
            require(currentArtifactIndex < canonicalPlan.artifacts.size) {
                "A nonterminal regional job requires a current artifact."
            }
        }
        if (state == RegionalJobState.RUNNING_VERIFY) {
            require(checkpointReferences.any { checkpoint ->
                checkpoint.artifactIndex == currentArtifactIndex &&
                    checkpoint.kind in setOf(
                        RegionalJobCheckpointKind.TRANSFER_COMPLETE,
                        RegionalJobCheckpointKind.VERIFIED_RAW,
                    )
            }) { "A verifying regional job requires a complete transfer checkpoint." }
        }
        if (state == RegionalJobState.RUNNING_PROCESS) {
            require(checkpointReferences.any { checkpoint ->
                checkpoint.artifactIndex == currentArtifactIndex &&
                    checkpoint.kind in setOf(
                        RegionalJobCheckpointKind.VERIFIED_RAW,
                        RegionalJobCheckpointKind.PROCESSED_OUTPUT,
                    )
            }) { "A processing regional job requires a verified raw checkpoint." }
        }
    }

    fun requestCancellation(nowEpochMillis: Long): RegionalJobRecordV1 {
        if (state.isTerminal || cancelRequested) return this
        val updated = copy(
            revision = revision + 1L,
            updatedAtEpochMillis = nowEpochMillis,
            cancelRequested = true,
        )
        validateRegionalJobMutation(this, updated)
        return updated
    }

    fun transitionTo(
        nextState: RegionalJobState,
        nowEpochMillis: Long,
        schedulerKind: RegionalJobSchedulerKind = this.schedulerKind,
        schedulerGeneration: Int = this.schedulerGeneration,
        schedulerIdentity: String? = this.schedulerIdentity,
        currentArtifactIndex: Int = this.currentArtifactIndex,
        networkBytesTransferred: Long = this.networkBytesTransferred,
        artifactAttemptCounts: List<Int> = this.artifactAttemptCounts,
        checkpointReferences: List<RegionalJobCheckpointReferenceV1> = this.checkpointReferences,
        artifactOutcomes: List<RegionalJobArtifactOutcomeV1> = this.artifactOutcomes,
        terminalProblem: RegionalJobProblemV1? = null,
    ): RegionalJobRecordV1 {
        require(!state.isTerminal) { "A terminal regional job is immutable." }
        require(nextState in REGIONAL_JOB_TRANSITIONS.getValue(state)) {
            "Regional job transition $state -> $nextState is not allowed."
        }
        val updated = copy(
            revision = revision + 1L,
            schedulerKind = schedulerKind,
            schedulerGeneration = schedulerGeneration,
            schedulerIdentity = schedulerIdentity,
            state = nextState,
            currentArtifactIndex = currentArtifactIndex,
            networkBytesTransferred = networkBytesTransferred,
            artifactAttemptCounts = artifactAttemptCounts,
            checkpointReferences = checkpointReferences,
            artifactOutcomes = artifactOutcomes,
            updatedAtEpochMillis = nowEpochMillis,
            cancelRequested = cancelRequested || nextState == RegionalJobState.CANCELED,
            terminalProblem = terminalProblem,
        )
        validateRegionalJobMutation(this, updated)
        return updated
    }

    /** First phase of crash-safe re-enqueue. Persist this intent before calling a scheduler. */
    fun prepareForReenqueue(nowEpochMillis: Long): RegionalJobRecordV1 {
        require(state in RECOVERABLE_SCHEDULED_STATES) {
            "Only interrupted scheduled work can prepare a new enqueue generation."
        }
        val updated = copy(
            revision = revision + 1L,
            schedulerGeneration = schedulerGeneration + 1,
            schedulerIdentity = null,
            state = RegionalJobState.ENQUEUE_PENDING,
            updatedAtEpochMillis = nowEpochMillis,
        )
        validateRegionalJobMutation(this, updated)
        return updated
    }

    companion object {
        fun enqueuePending(
            jobId: String,
            plan: RegionalDownloadPlan,
            schedulerKind: RegionalJobSchedulerKind,
            acceptedAtEpochMillis: Long,
            createdAtEpochMillis: Long,
        ): RegionalJobRecordV1 {
            require(schedulerKind != RegionalJobSchedulerKind.UNASSIGNED) {
                "An enqueue-pending job requires an Android scheduler kind."
            }
            val canonicalPlan = RegionalPlanFingerprint.canonicalize(plan)
            return RegionalJobRecordV1(
                jobId = jobId,
                revision = 0L,
                semanticFingerprintSha256 = RegionalPlanFingerprint.semantic(canonicalPlan),
                planFingerprintSha256 = RegionalPlanFingerprint.calculate(canonicalPlan),
                catalogRevision = canonicalPlan.catalogRevision,
                canonicalPlan = canonicalPlan,
                acceptedLicenseSnapshots = canonicalPlan.licenseSnapshots.map { license ->
                    RegionalAcceptedLicenseSnapshotV1(
                        license = license,
                        acceptedAtEpochMillis = acceptedAtEpochMillis,
                    )
                },
                schedulerKind = schedulerKind,
                schedulerGeneration = 0,
                schedulerIdentity = null,
                state = RegionalJobState.ENQUEUE_PENDING,
                currentArtifactIndex = 0,
                networkBytesTransferred = 0L,
                artifactAttemptCounts = List(canonicalPlan.artifacts.size) { 0 },
                checkpointReferences = emptyList(),
                artifactOutcomes = emptyList(),
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = createdAtEpochMillis,
                cancelRequested = false,
                terminalProblem = null,
            )
        }
    }
}

enum class RegionalSchedulerSnapshotAvailability {
    COMPLETE,
    UNAVAILABLE,
}

data class RegionalScheduledJobV1(
    val jobId: String,
    val schedulerKind: RegionalJobSchedulerKind,
    val schedulerGeneration: Int,
    val schedulerIdentity: String,
    val state: RegionalScheduledJobState,
) {
    init {
        require(JOB_ID_PATTERN.matches(jobId)) { "A scheduler snapshot contains an invalid job ID." }
        require(schedulerKind != RegionalJobSchedulerKind.UNASSIGNED) {
            "A scheduler snapshot requires a concrete scheduler kind."
        }
        require(schedulerGeneration in 0..MAXIMUM_SCHEDULER_GENERATION) {
            "A scheduler snapshot contains an invalid generation."
        }
        require(isBoundedSchedulerIdentity(schedulerIdentity)) {
            "A scheduler snapshot contains an invalid scheduler identity."
        }
    }
}

enum class RegionalScheduledJobState {
    PENDING,
    RUNNING,
    FINISHED,
}

data class RegionalSchedulerSnapshotV1(
    val availability: RegionalSchedulerSnapshotAvailability,
    val jobs: List<RegionalScheduledJobV1>,
) {
    init {
        require(
            jobs.size <= MAXIMUM_REGIONAL_SCHEDULER_ENTRIES &&
                jobs.distinctBy { job ->
                    job.schedulerKind to job.schedulerIdentity
                }.size == jobs.size,
        ) {
            "The scheduler snapshot contains a reused physical target or excessive scheduler entries."
        }
        if (availability == RegionalSchedulerSnapshotAvailability.UNAVAILABLE) {
            require(jobs.isEmpty()) { "An unavailable scheduler snapshot cannot claim known jobs." }
        }
    }
}

enum class RegionalJobReconciliationActionKind {
    CANCEL_SCHEDULER_ENTRY,
    REPORT_TERMINAL_OUTCOME_INVALID,
    ADOPT_AS_QUEUED,
    ENQUEUE,
    PREPARE_REENQUEUE,
    MARK_CANCELED,
    MARK_ORPHANED,
}

data class RegionalJobReconciliationActionV1(
    val kind: RegionalJobReconciliationActionKind,
    val jobId: String,
    val expectedRevision: Long?,
    val expectedPlanFingerprintSha256: String?,
    val expectedRecordSchedulerGeneration: Int?,
    val expectedRecordAbsent: Boolean = false,
    val targetSchedulerKind: RegionalJobSchedulerKind? = null,
    val targetSchedulerGeneration: Int? = null,
    val targetSchedulerIdentity: String? = null,
    val problem: RegionalJobProblemV1? = null,
) {
    init {
        require(JOB_ID_PATTERN.matches(jobId)) { "A reconciliation action contains an invalid job ID." }
        val hasExpectedRecord = expectedRevision != null
        require(
            hasExpectedRecord == (expectedPlanFingerprintSha256 != null) &&
                hasExpectedRecord == (expectedRecordSchedulerGeneration != null) &&
                !(hasExpectedRecord && expectedRecordAbsent) &&
                (expectedRevision == null || expectedRevision >= 0L) &&
                (expectedPlanFingerprintSha256 == null ||
                    JOB_SHA256_PATTERN.matches(expectedPlanFingerprintSha256)) &&
                (expectedRecordSchedulerGeneration == null ||
                    expectedRecordSchedulerGeneration in 0..MAXIMUM_SCHEDULER_GENERATION),
        ) { "A reconciliation action has incomplete stale-decision guards." }
        val hasSchedulerTarget = targetSchedulerKind != null
        require(
            hasSchedulerTarget == (targetSchedulerGeneration != null) &&
                hasSchedulerTarget == (targetSchedulerIdentity != null) &&
                (targetSchedulerKind == null || targetSchedulerKind != RegionalJobSchedulerKind.UNASSIGNED) &&
                (targetSchedulerGeneration == null ||
                    targetSchedulerGeneration in 0..MAXIMUM_SCHEDULER_GENERATION) &&
                (targetSchedulerIdentity == null || isBoundedSchedulerIdentity(targetSchedulerIdentity)),
        ) { "A reconciliation action has an incomplete or invalid scheduler target." }
        if (kind == RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY) {
            require(hasExpectedRecord || expectedRecordAbsent) {
                "A scheduler cancellation requires a record guard or an explicit absence precondition."
            }
        } else {
            require(hasExpectedRecord) {
                "A record mutation or enqueue action requires revision and generation guards."
            }
            require(!expectedRecordAbsent) {
                "Only a recordless scheduler cancellation can require record absence."
            }
        }
        val carriesProblem = kind in setOf(
            RegionalJobReconciliationActionKind.MARK_ORPHANED,
            RegionalJobReconciliationActionKind.REPORT_TERMINAL_OUTCOME_INVALID,
        )
        require(
            carriesProblem == (problem != null),
        ) { "Only an orphan or terminal-integrity action carries a typed reconciliation problem." }
        when (kind) {
            RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
            RegionalJobReconciliationActionKind.ADOPT_AS_QUEUED,
            -> require(hasSchedulerTarget) {
                "A scheduler-entry action requires a complete scheduler target."
            }

            else -> require(!hasSchedulerTarget) {
                "A non-scheduler reconciliation action cannot carry a scheduler target."
            }
        }
    }

    fun isCurrentFor(record: RegionalJobRecordV1): Boolean =
        expectedRevision != null &&
            jobId == record.jobId &&
            expectedRevision == record.revision &&
            expectedPlanFingerprintSha256 == record.planFingerprintSha256 &&
            expectedRecordSchedulerGeneration == record.schedulerGeneration

    fun isCurrentForAbsentRecord(
        readableRecords: Collection<RegionalJobRecordV1>,
        unreadableJobIds: Set<String>,
    ): Boolean =
        expectedRecordAbsent &&
            jobId !in unreadableJobIds &&
            readableRecords.none { record -> record.jobId == jobId }
}

fun interface RegionalJobCheckpointValidator {
    fun isValid(reference: RegionalJobCheckpointReferenceV1): Boolean
}

fun interface RegionalJobArtifactOutcomeValidator {
    fun isValid(
        record: RegionalJobRecordV1,
        artifact: RegionalCanonicalArtifactV1,
        outcome: RegionalJobArtifactOutcomeV1,
    ): Boolean
}

/** Pure decision engine. It never infers success from missing scheduler work. */
object RegionalJobReconciler {
    fun reconcile(
        records: List<RegionalJobRecordV1>,
        schedulerSnapshot: RegionalSchedulerSnapshotV1,
        unreadableJobIds: Set<String>,
        checkpointValidator: RegionalJobCheckpointValidator = RegionalJobCheckpointValidator { false },
        artifactOutcomeValidator: RegionalJobArtifactOutcomeValidator =
            RegionalJobArtifactOutcomeValidator { _, _, _ -> false },
    ): List<RegionalJobReconciliationActionV1> {
        val recordsById = records.associateBy(RegionalJobRecordV1::jobId)
        require(
            recordsById.size == records.size &&
                records.size <= MAXIMUM_REGIONAL_JOBS &&
                unreadableJobIds.size <= MAXIMUM_REGIONAL_JOBS - records.size &&
                unreadableJobIds.all(JOB_ID_PATTERN::matches) &&
                recordsById.keys.none(unreadableJobIds::contains),
        ) {
            "Regional job reconciliation requires unique bounded records."
        }
        if (schedulerSnapshot.availability == RegionalSchedulerSnapshotAvailability.UNAVAILABLE) {
            return emptyList()
        }
        val scheduledById = schedulerSnapshot.jobs.groupBy(RegionalScheduledJobV1::jobId)
        val actions = mutableListOf<RegionalJobReconciliationActionV1>()

        schedulerSnapshot.jobs.filter { scheduled ->
            scheduled.jobId !in recordsById && scheduled.jobId !in unreadableJobIds
        }.forEach { scheduled ->
            actions += scheduled.externalCancelAction()
        }

        records.sortedBy(RegionalJobRecordV1::jobId).forEach { record ->
            val scheduledJobs = scheduledById[record.jobId].orEmpty()
            if (record.state.isTerminal) {
                scheduledJobs.forEach { scheduled -> actions += record.cancelSchedulerAction(scheduled) }
                if (!record.hasValidArtifactOutcomes(artifactOutcomeValidator)) {
                    actions += record.recordAction(
                        kind = RegionalJobReconciliationActionKind.REPORT_TERMINAL_OUTCOME_INVALID,
                        problem = RegionalJobProblemV1(
                            code = "terminal-artifact-outcome-invalid",
                            message = "A terminal regional artifact outcome no longer matches the durable inventory.",
                            retryableByUser = true,
                        ),
                    )
                }
                return@forEach
            }
            if (record.cancelRequested) {
                scheduledJobs.forEach { scheduled -> actions += record.cancelSchedulerAction(scheduled) }
                actions += record.recordAction(RegionalJobReconciliationActionKind.MARK_CANCELED)
                return@forEach
            }
            if (!record.hasValidArtifactOutcomes(artifactOutcomeValidator)) {
                scheduledJobs.forEach { scheduled -> actions += record.cancelSchedulerAction(scheduled) }
                actions += record.orphanAction(
                    code = "artifact-outcome-invalid",
                    message = "A committed regional artifact outcome no longer matches the durable inventory.",
                )
                return@forEach
            }
            if (!RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(record.canonicalPlan)) {
                scheduledJobs.forEach { scheduled -> actions += record.cancelSchedulerAction(scheduled) }
                actions += record.orphanAction(
                    code = "catalog-plan-incompatible",
                    message = "The persisted regional plan no longer matches the installed fixed catalog.",
                )
                return@forEach
            }
            if (record.state == RegionalJobState.DRAFT) {
                scheduledJobs.forEach { scheduled -> actions += record.cancelSchedulerAction(scheduled) }
                return@forEach
            }

            val matchingJobs = scheduledJobs.filter { scheduled ->
                scheduled.schedulerKind == record.schedulerKind &&
                    scheduled.schedulerGeneration == record.schedulerGeneration &&
                    (record.schedulerIdentity == null || scheduled.schedulerIdentity == record.schedulerIdentity)
            }.sortedWith(SCHEDULED_JOB_SELECTION_ORDER)
            val scheduled = matchingJobs.firstOrNull()
            scheduledJobs.filter { it != scheduled }.forEach { stale ->
                actions += record.cancelSchedulerAction(stale)
            }
            if (scheduled == null && scheduledJobs.isNotEmpty()) {
                actions += record.orphanAction(
                    code = "scheduler-identity-mismatch",
                    message = "The persisted regional job does not match any observed Android scheduler entry.",
                )
                return@forEach
            }
            if (scheduled?.state == RegionalScheduledJobState.FINISHED) {
                actions += record.orphanAction(
                    code = "scheduler-finished-without-result",
                    message = "The Android scheduler finished without a committed terminal regional-job result.",
                )
                return@forEach
            }
            when {
                record.state == RegionalJobState.ENQUEUE_PENDING && scheduled != null ->
                    actions += record.recordAction(
                        kind = RegionalJobReconciliationActionKind.ADOPT_AS_QUEUED,
                        schedulerTarget = scheduled,
                    )

                record.state == RegionalJobState.ENQUEUE_PENDING && scheduled == null ->
                    actions += record.recordAction(RegionalJobReconciliationActionKind.ENQUEUE)

                record.state in RECOVERABLE_SCHEDULED_STATES && scheduled == null -> {
                    if (record.schedulerGeneration == MAXIMUM_SCHEDULER_GENERATION) {
                        actions += record.orphanAction(
                            code = "scheduler-generation-exhausted",
                            message = "The regional job exhausted its bounded scheduler generations.",
                        )
                    } else {
                        val checkpointsValid = record.checkpointReferences.all(checkpointValidator::isValid)
                        val stageHasRequiredCheckpoint = when (record.state) {
                            RegionalJobState.RUNNING_VERIFY -> record.checkpointReferences.any { checkpoint ->
                                checkpoint.artifactIndex == record.currentArtifactIndex &&
                                    checkpoint.kind in setOf(
                                        RegionalJobCheckpointKind.TRANSFER_COMPLETE,
                                        RegionalJobCheckpointKind.VERIFIED_RAW,
                                    )
                            }

                            RegionalJobState.RUNNING_PROCESS -> record.checkpointReferences.any { checkpoint ->
                                checkpoint.artifactIndex == record.currentArtifactIndex &&
                                    checkpoint.kind in setOf(
                                        RegionalJobCheckpointKind.VERIFIED_RAW,
                                        RegionalJobCheckpointKind.PROCESSED_OUTPUT,
                                    )
                            }

                            else -> true
                        }
                        if (checkpointsValid && stageHasRequiredCheckpoint) {
                            actions += record.recordAction(RegionalJobReconciliationActionKind.PREPARE_REENQUEUE)
                        } else {
                            actions += record.orphanAction(
                                code = "checkpoint-invalid",
                                message = "The interrupted regional job has a missing or invalid checkpoint.",
                            )
                        }
                    }
                }
            }
        }
        return actions.sortedWith(
            compareBy<RegionalJobReconciliationActionV1>(RegionalJobReconciliationActionV1::jobId)
                .thenBy { action -> RECONCILIATION_ACTION_ORDER.getValue(action.kind) }
                .thenBy { action -> action.targetSchedulerKind?.name.orEmpty() }
                .thenBy { action -> action.targetSchedulerGeneration ?: -1 }
                .thenBy { action -> action.targetSchedulerIdentity.orEmpty() },
        )
    }
}

internal fun validateRegionalJobMutation(previous: RegionalJobRecordV1, updated: RegionalJobRecordV1) {
    require(!previous.state.isTerminal) { "A terminal regional job is immutable." }
    require(
        updated.jobId == previous.jobId &&
            updated.semanticFingerprintSha256 == previous.semanticFingerprintSha256 &&
            updated.planFingerprintSha256 == previous.planFingerprintSha256 &&
            updated.catalogRevision == previous.catalogRevision &&
            updated.canonicalPlan == previous.canonicalPlan &&
            updated.acceptedLicenseSnapshots == previous.acceptedLicenseSnapshots &&
            updated.createdAtEpochMillis == previous.createdAtEpochMillis,
    ) { "A regional job update changed immutable identity or reviewed inputs." }
    require(
        updated.schedulerKind == previous.schedulerKind ||
            previous.state == RegionalJobState.DRAFT &&
            updated.state == RegionalJobState.ENQUEUE_PENDING &&
            previous.schedulerKind == RegionalJobSchedulerKind.UNASSIGNED &&
            updated.schedulerKind != RegionalJobSchedulerKind.UNASSIGNED,
    ) { "A regional job scheduler kind can be selected only when leaving draft state." }
    require(updated.revision == previous.revision + 1L) {
        "A regional job update must advance exactly one revision."
    }
    require(
        updated.state == previous.state || updated.state in REGIONAL_JOB_TRANSITIONS.getValue(previous.state),
    ) { "The regional job state update is not allowed." }
    require(updated.updatedAtEpochMillis >= previous.updatedAtEpochMillis) {
        "A regional job update moved its timestamp backwards."
    }
    require(updated.currentArtifactIndex in previous.currentArtifactIndex..previous.currentArtifactIndex + 1) {
        "A regional job update must retain or complete exactly one current artifact."
    }
    require(updated.networkBytesTransferred >= previous.networkBytesTransferred) {
        "A regional job update moved cumulative network bytes backwards."
    }
    val attemptDeltas = updated.artifactAttemptCounts.zip(previous.artifactAttemptCounts) { next, old -> next - old }
    require(
        attemptDeltas.all { it in 0..1 } &&
            attemptDeltas.count { it == 1 } <= 1 &&
            attemptDeltas.withIndex().all { (index, delta) ->
                delta == 0 || index == updated.currentArtifactIndex
            },
    ) {
        "A regional job update must durably start at most one attempt for its current artifact."
    }
    val artifactAdvance = updated.currentArtifactIndex - previous.currentArtifactIndex
    val newOutcome = updated.artifactOutcomes.firstOrNull { outcome ->
        outcome.artifactIndex == previous.currentArtifactIndex && outcome !in previous.artifactOutcomes
    }
    require(
        previous.artifactOutcomes.all(updated.artifactOutcomes::contains) &&
            updated.artifactOutcomes.size == previous.artifactOutcomes.size + artifactAdvance &&
            (
                artifactAdvance == 0 ||
                    updated.artifactOutcomes.any { outcome ->
                        outcome.artifactIndex == previous.currentArtifactIndex
                    }
                ),
    ) { "A regional job artifact outcome was removed, changed, or skipped." }
    if (artifactAdvance == 1) {
        require(
            previous.state == RegionalJobState.RUNNING_PROCESS ||
                previous.state == RegionalJobState.RUNNING_DOWNLOAD &&
                newOutcome?.kind == RegionalJobArtifactOutcomeKind.OPTIONAL_NOT_FOUND,
        ) { "Only processed or explicitly optional-missing work can complete an artifact." }
    }
    require(checkpointsAdvanceMonotonically(previous, updated)) {
        "A regional job checkpoint was removed, changed, or regressed before artifact commitment."
    }
    val isReenqueueGeneration =
        previous.state in RECOVERABLE_SCHEDULED_STATES &&
            updated.state == RegionalJobState.ENQUEUE_PENDING &&
            updated.schedulerGeneration == previous.schedulerGeneration + 1 &&
            updated.schedulerIdentity == null
    val isSameSchedulerGeneration =
        updated.schedulerGeneration == previous.schedulerGeneration &&
            (previous.schedulerIdentity == null || previous.schedulerIdentity == updated.schedulerIdentity)
    require(isReenqueueGeneration || isSameSchedulerGeneration) {
        "A regional job scheduler identity can change only through a persisted re-enqueue generation."
    }
    if (previous.schedulerIdentity == null && updated.schedulerIdentity != null) {
        require(
            previous.state == RegionalJobState.ENQUEUE_PENDING &&
                updated.state in setOf(RegionalJobState.QUEUED, RegionalJobState.RUNNING_DOWNLOAD),
        ) { "A scheduler identity can be published only while acknowledging an enqueue intent." }
    }
    if (attemptDeltas.any { it == 1 }) {
        require(updated.state == RegionalJobState.RUNNING_DOWNLOAD) {
            "A provider attempt can start only in the download state."
        }
    }
    require(!previous.cancelRequested || updated.cancelRequested) {
        "A regional job cancellation request cannot be cleared."
    }
}

private fun checkpointsAdvanceMonotonically(
    previous: RegionalJobRecordV1,
    updated: RegionalJobRecordV1,
): Boolean {
    val committedIndexes = updated.artifactOutcomes.mapTo(hashSetOf()) { it.artifactIndex }
    val previousByKey = previous.checkpointReferences.associateBy { it.artifactIndex to it.kind }
    val updatedByKey = updated.checkpointReferences.associateBy { it.artifactIndex to it.kind }
    val previousMaximumRanks = previous.checkpointReferences.groupBy(RegionalJobCheckpointReferenceV1::artifactIndex)
        .mapValues { (_, checkpoints) -> checkpoints.maxOf { it.kind.checkpointRank } }

    val previousCheckpointsRemainValid = previous.checkpointReferences.all { old ->
        if (old.artifactIndex in committedIndexes) return@all true
        val sameKind = updatedByKey[old.artifactIndex to old.kind]
        if (sameKind != null) {
            if (old.kind == RegionalJobCheckpointKind.TRANSFER_PARTIAL) {
                sameKind.relativePath == old.relativePath && sameKind.bytes >= old.bytes
            } else {
                sameKind == old
            }
        } else {
            updated.checkpointReferences.any { candidate ->
                candidate.artifactIndex == old.artifactIndex &&
                    candidate.kind.checkpointRank > old.kind.checkpointRank
            }
        }
    }
    if (!previousCheckpointsRemainValid) return false

    return updated.checkpointReferences.all { checkpoint ->
        val existed = (checkpoint.artifactIndex to checkpoint.kind) in previousByKey
        val previousRank = previousMaximumRanks[checkpoint.artifactIndex]
        existed ||
            checkpoint.artifactIndex == previous.currentArtifactIndex &&
            (previousRank == null || checkpoint.kind.checkpointRank >= previousRank)
    }
}

private val RegionalJobCheckpointKind.checkpointRank: Int
    get() = when (this) {
        RegionalJobCheckpointKind.TRANSFER_PARTIAL -> 0
        RegionalJobCheckpointKind.TRANSFER_COMPLETE -> 1
        RegionalJobCheckpointKind.VERIFIED_RAW -> 2
        RegionalJobCheckpointKind.PROCESSED_OUTPUT -> 3
    }

private fun RegionalJobCheckpointReferenceV1.isStructurallyValidFor(
    artifact: RegionalCanonicalArtifactV1,
): Boolean {
    val maximumBytes = if (kind == RegionalJobCheckpointKind.PROCESSED_OUTPUT) {
        MAXIMUM_PROCESSED_CHECKPOINT_BYTES
    } else {
        artifact.maximumArtifactBytes
    }
    val pathMatchesArtifact = when (kind) {
        RegionalJobCheckpointKind.TRANSFER_PARTIAL,
        RegionalJobCheckpointKind.TRANSFER_COMPLETE,
        -> relativePath == "${artifact.logicalRelativePath}.part"

        RegionalJobCheckpointKind.VERIFIED_RAW -> relativePath == artifact.logicalRelativePath
        RegionalJobCheckpointKind.PROCESSED_OUTPUT -> true
    }
    return bytes <= maximumBytes && pathMatchesArtifact
}

private fun RegionalCanonicalArtifactV1.maximumTransferAttempts(): Int = when (httpMethod) {
    RegionalHttpMethod.GET -> MAXIMUM_GET_TRANSFER_ATTEMPTS
    RegionalHttpMethod.POST -> MAXIMUM_POST_TRANSFER_ATTEMPTS
}

private fun List<RegionalCanonicalArtifactV1>.checkedMaximumTransferByteSum(): Long = try {
    fold(0L) { total, artifact ->
        Math.addExact(
            total,
            Math.multiplyExact(artifact.maximumArtifactBytes, artifact.maximumTransferAttempts().toLong()),
        )
    }
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("Regional retry byte bounds overflow the supported range.", error)
}

private fun RegionalJobRecordV1.recordAction(
    kind: RegionalJobReconciliationActionKind,
    schedulerTarget: RegionalScheduledJobV1? = null,
    problem: RegionalJobProblemV1? = null,
): RegionalJobReconciliationActionV1 = RegionalJobReconciliationActionV1(
    kind = kind,
    jobId = jobId,
    expectedRevision = revision,
    expectedPlanFingerprintSha256 = planFingerprintSha256,
    expectedRecordSchedulerGeneration = schedulerGeneration,
    targetSchedulerKind = schedulerTarget?.schedulerKind,
    targetSchedulerGeneration = schedulerTarget?.schedulerGeneration,
    targetSchedulerIdentity = schedulerTarget?.schedulerIdentity,
    problem = problem,
)

private fun RegionalJobRecordV1.cancelSchedulerAction(
    scheduled: RegionalScheduledJobV1,
): RegionalJobReconciliationActionV1 = RegionalJobReconciliationActionV1(
    kind = RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
    jobId = jobId,
    expectedRevision = revision,
    expectedPlanFingerprintSha256 = planFingerprintSha256,
    expectedRecordSchedulerGeneration = schedulerGeneration,
    targetSchedulerKind = scheduled.schedulerKind,
    targetSchedulerGeneration = scheduled.schedulerGeneration,
    targetSchedulerIdentity = scheduled.schedulerIdentity,
)

private fun RegionalJobRecordV1.hasValidArtifactOutcomes(
    validator: RegionalJobArtifactOutcomeValidator,
): Boolean = artifactOutcomes.all { outcome ->
    validator.isValid(
        record = this,
        artifact = canonicalPlan.artifacts[outcome.artifactIndex],
        outcome = outcome,
    )
}

private fun RegionalScheduledJobV1.externalCancelAction(): RegionalJobReconciliationActionV1 =
    RegionalJobReconciliationActionV1(
        kind = RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY,
        jobId = jobId,
        expectedRevision = null,
        expectedPlanFingerprintSha256 = null,
        expectedRecordSchedulerGeneration = null,
        expectedRecordAbsent = true,
        targetSchedulerKind = schedulerKind,
        targetSchedulerGeneration = schedulerGeneration,
        targetSchedulerIdentity = schedulerIdentity,
    )

private fun RegionalJobRecordV1.orphanAction(
    code: String,
    message: String,
): RegionalJobReconciliationActionV1 = recordAction(
    kind = RegionalJobReconciliationActionKind.MARK_ORPHANED,
    problem = RegionalJobProblemV1(
        code = code,
        message = message,
        retryableByUser = true,
    ),
)

private fun canonicalJsonElement(element: JsonElement): String = buildString {
    appendCanonicalJson(element)
}

private fun StringBuilder.appendCanonicalJson(element: JsonElement) {
    when (element) {
        JsonNull -> append("null")
        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, child ->
                if (index > 0) append(',')
                appendCanonicalJson(child)
            }
            append(']')
        }

        is JsonObject -> {
            append('{')
            element.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append(CANONICAL_PLAN_JSON.encodeToString(String.serializer(), entry.key))
                append(':')
                appendCanonicalJson(entry.value)
            }
            append('}')
        }

        is JsonPrimitive -> if (element.isString) {
            append(CANONICAL_PLAN_JSON.encodeToString(String.serializer(), element.content))
        } else {
            append(element.content)
        }
    }
}

private fun Double.toCanonicalMicrodegrees(): Long = BigDecimal.valueOf(this)
    .setScale(CANONICAL_COORDINATE_DECIMALS, RoundingMode.HALF_EVEN)
    .movePointRight(CANONICAL_COORDINATE_DECIMALS)
    .longValueExact()

private fun normalizeRegionalJobReason(value: String): String = Normalizer
    .normalize(value.trim(), Normalizer.Form.NFC)

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> LOWER_HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() + LOWER_HEX_DIGITS[byte.toInt() and 0x0f] }

private fun isSafeJobHttpsUrl(value: String): Boolean = try {
    val uri = java.net.URI(value)
    value.length in 1..MAXIMUM_CANONICAL_URL_CHARACTERS &&
        value.none(Char::isISOControl) &&
        uri.isAbsolute &&
        uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawFragment == null &&
        uri.port in setOf(-1, 443)
} catch (_: Exception) {
    false
}

private fun isSafeJobRelativePath(value: String): Boolean =
    value.length in 1..MAXIMUM_JOB_RELATIVE_PATH_CHARACTERS &&
        !value.startsWith('/') &&
        '\\' !in value &&
        JOB_SAFE_PATH_PATTERN.matches(value) &&
        value.split('/').none { it.isBlank() || it == "." || it == ".." }

private fun isBoundedSchedulerIdentity(value: String): Boolean =
    value.length in 1..MAXIMUM_SCHEDULER_IDENTITY_CHARACTERS &&
        value.none(Char::isISOControl)

private val CANONICAL_ARTIFACT_COMPARATOR = compareBy<RegionalCanonicalArtifactV1> { artifact ->
    canonicalJsonElement(
        CANONICAL_PLAN_JSON.encodeToJsonElement(RegionalCanonicalArtifactV1.serializer(), artifact),
    )
}

private val SEMANTIC_ARTIFACT_COMPARATOR = compareBy<RegionalSemanticArtifactFingerprintV1> { artifact ->
    canonicalJsonElement(
        CANONICAL_PLAN_JSON.encodeToJsonElement(RegionalSemanticArtifactFingerprintV1.serializer(), artifact),
    )
}

private fun List<RegionalCanonicalArtifactV1>.checkedEstimatedByteSum(): Long = try {
    fold(0L) { total, artifact -> Math.addExact(total, artifact.estimatedBytes) }
} catch (error: ArithmeticException) {
    throw IllegalArgumentException("Canonical regional artifact estimates overflow the supported range.", error)
}

private val REGIONAL_JOB_TRANSITIONS = mapOf(
    RegionalJobState.DRAFT to setOf(
        RegionalJobState.ENQUEUE_PENDING,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.ENQUEUE_PENDING to setOf(
        RegionalJobState.QUEUED,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.FAILED,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.QUEUED to setOf(
        RegionalJobState.ENQUEUE_PENDING,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.PAUSED_CONSTRAINT,
        RegionalJobState.FAILED,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.RUNNING_DOWNLOAD to setOf(
        RegionalJobState.ENQUEUE_PENDING,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.RUNNING_VERIFY,
        RegionalJobState.PAUSED_CONSTRAINT,
        RegionalJobState.SUCCEEDED,
        RegionalJobState.FAILED,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.RUNNING_VERIFY to setOf(
        RegionalJobState.ENQUEUE_PENDING,
        RegionalJobState.RUNNING_PROCESS,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.PAUSED_CONSTRAINT,
        RegionalJobState.FAILED,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.RUNNING_PROCESS to setOf(
        RegionalJobState.ENQUEUE_PENDING,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.SUCCEEDED,
        RegionalJobState.PAUSED_CONSTRAINT,
        RegionalJobState.FAILED,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.PAUSED_CONSTRAINT to setOf(
        RegionalJobState.ENQUEUE_PENDING,
        RegionalJobState.QUEUED,
        RegionalJobState.RUNNING_DOWNLOAD,
        RegionalJobState.FAILED,
        RegionalJobState.CANCELED,
        RegionalJobState.ORPHANED,
    ),
    RegionalJobState.SUCCEEDED to emptySet(),
    RegionalJobState.FAILED to emptySet(),
    RegionalJobState.CANCELED to emptySet(),
    RegionalJobState.ORPHANED to emptySet(),
)

private val RECOVERABLE_SCHEDULED_STATES = setOf(
    RegionalJobState.QUEUED,
    RegionalJobState.RUNNING_DOWNLOAD,
    RegionalJobState.RUNNING_VERIFY,
    RegionalJobState.RUNNING_PROCESS,
    RegionalJobState.PAUSED_CONSTRAINT,
)

private val SCHEDULED_JOB_SELECTION_ORDER =
    compareBy<RegionalScheduledJobV1> { scheduled ->
        when (scheduled.state) {
            RegionalScheduledJobState.RUNNING -> 0
            RegionalScheduledJobState.PENDING -> 1
            RegionalScheduledJobState.FINISHED -> 2
        }
    }.thenBy(RegionalScheduledJobV1::schedulerIdentity)

private val RECONCILIATION_ACTION_ORDER = mapOf(
    RegionalJobReconciliationActionKind.CANCEL_SCHEDULER_ENTRY to 0,
    RegionalJobReconciliationActionKind.REPORT_TERMINAL_OUTCOME_INVALID to 1,
    RegionalJobReconciliationActionKind.MARK_CANCELED to 2,
    RegionalJobReconciliationActionKind.MARK_ORPHANED to 3,
    RegionalJobReconciliationActionKind.ADOPT_AS_QUEUED to 4,
    RegionalJobReconciliationActionKind.PREPARE_REENQUEUE to 5,
    RegionalJobReconciliationActionKind.ENQUEUE to 6,
)

private val CANONICAL_PLAN_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
}

const val REGIONAL_CANONICAL_PLAN_SCHEMA_VERSION = 1
const val REGIONAL_JOB_SCHEMA_VERSION = 1
const val ANDROID_REGIONAL_RESOURCE_PROFILE_ID = "android-sequential-regional-v1"
const val MAXIMUM_REGIONAL_JOBS = 64
const val MAXIMUM_REGIONAL_SCHEDULER_ENTRIES = MAXIMUM_REGIONAL_JOBS * 2

private const val REGIONAL_SEMANTIC_FINGERPRINT_SCHEMA_ID = "atx-regional-semantic-plan-v1"
private const val REGIONAL_EXECUTION_FINGERPRINT_SCHEMA_ID = "atx-regional-execution-plan-v1"
private const val CANONICAL_COORDINATE_DECIMALS = 6
private const val MICRODEGREES_PER_DEGREE = 1_000_000L
private const val MAXIMUM_JOB_CATALOG_REVISION = 1_000_000
private const val MAXIMUM_CANONICAL_URL_CHARACTERS = 2_048
private const val MAXIMUM_CANONICAL_REQUEST_BODY_CHARACTERS = 256 * 1024
private const val MAXIMUM_CANONICAL_CONTENT_TYPE_CHARACTERS = 200
private const val MAXIMUM_CANONICAL_CACHE_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val MAXIMUM_JOB_RELATIVE_PATH_CHARACTERS = 240
private const val MAXIMUM_SCHEDULER_IDENTITY_CHARACTERS = 200
private const val MAXIMUM_JOB_PROBLEM_CHARACTERS = 500
private const val MAXIMUM_JOB_CHECKPOINTS = MAX_ARTIFACTS_PER_PLAN * 4
private const val MAXIMUM_GET_TRANSFER_ATTEMPTS = 3
private const val MAXIMUM_POST_TRANSFER_ATTEMPTS = 2
private const val MAXIMUM_SCHEDULER_GENERATION = 1_000
private const val MAXIMUM_PROCESSED_CHECKPOINT_BYTES = 512L * 1024L * 1024L
private const val LOWER_HEX_DIGITS = "0123456789abcdef"

private val JOB_STABLE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9.-]{1,79}$")
private val JOB_ID_PATTERN =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val JOB_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val JOB_SAFE_PATH_PATTERN = Regex("^[A-Za-z0-9._/-]+$")
