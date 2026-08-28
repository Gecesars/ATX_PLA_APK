package com.gecesars.atxplan.data.dataset

import com.gecesars.atxplan.domain.dataset.DEFAULT_MAXIMUM_BATCH_BYTES
import com.gecesars.atxplan.domain.dataset.MAX_STATUS_MESSAGE_LENGTH
import com.gecesars.atxplan.domain.dataset.MEBIBYTE
import com.gecesars.atxplan.domain.dataset.REGIONAL_INVENTORY_SCHEMA_VERSION
import com.gecesars.atxplan.domain.dataset.RegionalArtifact
import com.gecesars.atxplan.domain.dataset.RegionalArtifactCachePolicy
import com.gecesars.atxplan.domain.dataset.RegionalArtifactResult
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetCatalog
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRepository
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalDownloadPlan
import com.gecesars.atxplan.domain.dataset.RegionalDownloadProgress
import com.gecesars.atxplan.domain.dataset.RegionalDownloadResult
import com.gecesars.atxplan.domain.dataset.RegionalHttpMethod
import com.gecesars.atxplan.domain.dataset.RegionalInventory
import com.gecesars.atxplan.domain.dataset.RegionalInventoryRecord
import com.gecesars.atxplan.domain.dataset.RegionalProcessedOutput
import com.gecesars.atxplan.domain.dataset.RegionalProcessingState
import com.gecesars.atxplan.domain.dataset.RegionalFileFormat
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RegionalDatasetFailure {
    INVALID_PLAN,
    STORAGE_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    NETWORK_FAILED,
    INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
    PROCESSING_FAILED,
    INVENTORY_INVALID,
    INVENTORY_WRITE_FAILED,
}

class RegionalDatasetException(
    val failure: RegionalDatasetFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class RegionalProcessingOutcome(
    val output: RegionalProcessedOutput? = null,
    val notes: String = "",
) {
    init {
        require(notes.length <= MAX_STATUS_MESSAGE_LENGTH) {
            "Regional processing notes are too long."
        }
    }
}

/**
 * A format-specific processor validates the completed raw staging file and, when applicable,
 * writes a queryable derivative below [outputRoot]. Implementations must publish derivatives
 * atomically; the repository verifies the declared output before accepting it.
 */
fun interface RegionalArtifactProcessor {
    fun process(
        artifact: RegionalArtifact,
        rawStagingFile: File,
        outputRoot: File,
        effectiveSourceUrl: String,
    ): RegionalProcessingOutcome
}

/** CPU-only processor used by the Android repository. Raster output is metadata-only by design. */
class DefaultRegionalArtifactProcessor(
    private val clock: () -> Long = System::currentTimeMillis,
) : RegionalArtifactProcessor {
    override fun process(
        artifact: RegionalArtifact,
        rawStagingFile: File,
        outputRoot: File,
        effectiveSourceUrl: String,
    ): RegionalProcessingOutcome = when (artifact.source.fileFormat) {
        RegionalFileFormat.COG_GEOTIFF -> processTiff(artifact, rawStagingFile, outputRoot)
        RegionalFileFormat.OVERPASS_JSON ->
            processBuildings(artifact, rawStagingFile, outputRoot, effectiveSourceUrl)
    }

    private fun processTiff(
        artifact: RegionalArtifact,
        rawFile: File,
        outputRoot: File,
    ): RegionalProcessingOutcome {
        val index = RegionalTiffMetadataIndexer.index(
            file = rawFile,
            limits = TiffIndexLimits(maximumFileBytes = artifact.source.maximumArtifactBytes),
        )
        val rawSha256 = sha256(rawFile, artifact.source.maximumArtifactBytes)
        val record = PersistedTiffMetadataIndex(
            schemaVersion = TIFF_METADATA_SCHEMA_VERSION,
            datasetId = artifact.source.datasetId,
            sourceVersion = artifact.source.version,
            sourceCrs = artifact.source.sourceCrs,
            rawRelativePath = artifact.relativePath,
            rawSha256 = rawSha256,
            requestBounds = listOf(
                artifact.requestBounds.west,
                artifact.requestBounds.south,
                artifact.requestBounds.east,
                artifact.requestBounds.north,
            ),
            coverageBounds = listOf(
                artifact.coverageBounds.west,
                artifact.coverageBounds.south,
                artifact.coverageBounds.east,
                artifact.coverageBounds.north,
            ),
            byteOrder = index.byteOrder.name,
            tiffVariant = index.variant.name,
            width = index.width,
            height = index.height,
            bandCount = index.bandCount,
            bandCountDeclared = index.bandCountDeclared,
            bitsPerSample = index.bitsPerSample,
            compression = index.compression,
            compressionDeclared = index.compressionDeclared,
            sampleFormat = index.sampleFormat,
            pixelScale = index.pixelScale,
            tiePoints = index.tiePoints,
            modelTransformation = index.modelTransformation,
            epsgCode = index.crs?.epsgCode,
            crsCitation = index.crs?.citation,
            noData = index.noData,
            rawByteCount = index.byteCount,
            firstIfdOffset = index.firstIfdOffset,
            firstIfdEntryCount = index.firstIfdEntryCount,
            metadataOnly = index.isMetadataOnly,
            rasterSamplesDecoded = index.rasterSamplesDecoded,
            cloudOptimizedLayoutValidated = index.cloudOptimizedLayoutValidated,
        )
        val relativePath = "derived/raster-metadata/${artifact.source.datasetId}/$rawSha256.json"
        val output = File(outputRoot, relativePath)
        val payload = PROCESSOR_JSON.encodeToString(PersistedTiffMetadataIndex.serializer(), record)
            .toByteArray(StandardCharsets.UTF_8)
        writeImmutableOutput(output, payload)
        return RegionalProcessingOutcome(
            output = RegionalProcessedOutput(
                relativePath = relativePath,
                format = "ATX TIFF metadata index v1",
                bytes = payload.size.toLong(),
                sha256 = sha256(output, MAXIMUM_PROCESSOR_CONTROL_BYTES),
                notes = "Metadata only; raster samples and COG layout were not validated.",
            ),
            notes = "TIFF dimensions, bands, georeferencing tags, CRS tags, and NoData metadata were indexed. Raster samples are not yet decoded for engineering use.",
        )
    }

    private fun processBuildings(
        artifact: RegionalArtifact,
        rawFile: File,
        outputRoot: File,
        effectiveSourceUrl: String,
    ): RegionalProcessingOutcome {
        val rawSha256 = sha256(rawFile, artifact.source.maximumArtifactBytes)
        val queriedAt = rawFile.lastModified().takeIf { it > 0L } ?: clock()
        val relativePath =
            "derived/buildings/openstreetmap-overpass/$rawSha256-$queriedAt.geojson"
        val output = File(outputRoot, relativePath)
        val result = OverpassBuildingProcessor.process(
            OverpassBuildingProcessRequest(
                rawFile = rawFile,
                outputGeoJsonFile = output,
                sourceUrl = effectiveSourceUrl,
                query = decodeOverpassFormBody(
                    artifact.requestBody ?: throw IOException("The fixed Overpass request body is missing."),
                ),
                queriedAtEpochMillis = queriedAt,
                expectedRawSha256 = rawSha256,
            ),
        )
        return RegionalProcessingOutcome(
            output = RegionalProcessedOutput(
                relativePath = relativePath,
                format = "GeoJSON RFC 7946 building footprints",
                bytes = result.outputByteCount,
                sha256 = result.outputSha256,
                recordCount = result.featureCount.toLong(),
                notes = "Experimental OpenStreetMap snapshot; bounded height/level/roof tags are retained as raw text but not interpreted.",
            ),
            notes = buildString {
                append("Processed ${result.featureCount} building footprints and ${result.vertexCount} vertices; ")
                append("${result.unsupportedElementCount} unsupported elements were omitted.")
                result.sourceTimestampOsmBase?.let { timestamp ->
                    append(" OSM source timestamp: $timestamp.")
                }
            },
        )
    }
}

class FileRegionalDatasetRepository(
    rootDirectory: File,
    private val processor: RegionalArtifactProcessor,
    private val transport: RegionalHttpTransport = AllowlistedHttpsRegionalHttpTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val availableBytes: (File) -> Long = File::getUsableSpace,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
    private val retryDelay: suspend (Long) -> Unit = { delayMillis -> delay(delayMillis) },
) : RegionalDatasetRepository {
    private val rootDirectory = rootDirectory.absoluteFile.normalizePathWithoutExistingRequirement()
    private val inventoryFile = File(this.rootDirectory, INVENTORY_FILE_NAME)
    private val operationMutex = APPLICATION_REGIONAL_DATASET_MUTEX

    override suspend fun acquire(
        plan: RegionalDownloadPlan,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): RegionalDownloadResult = withContext(ioDispatcher) {
        operationMutex.withLock {
            validateCanonicalPlan(plan)
            ensureStorageRoot()
            preflightStorage(plan)
            var inventory = readInventory()
            val results = ArrayList<RegionalArtifactResult>(plan.artifacts.size)

            for ((index, artifact) in plan.artifacts.withIndex()) {
                coroutineContext.ensureActive()
                if (isCancelled()) {
                    for (remaining in plan.artifacts.drop(index)) {
                        val cancelled = cancelledResult(remaining)
                        results += cancelled
                        onProgress(cancelled.toProgress("Regional acquisition was cancelled."))
                    }
                    break
                }

                onProgress(
                    RegionalDownloadProgress(
                        artifact = artifact,
                        status = RegionalTransferStatus.QUEUED,
                        message = "Regional artifact queued.",
                    ),
                )
                val result = acquireArtifact(
                    artifact = artifact,
                    priorRecord = inventory.artifacts[artifact.relativePath],
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                results += result
                inventory = inventory.withResult(result, nowIso8601(), plan)
                writeInventory(inventory)

                if (result.status == RegionalTransferStatus.CANCELLED) {
                    for (remaining in plan.artifacts.drop(index + 1)) {
                        val cancelled = cancelledResult(remaining)
                        results += cancelled
                        onProgress(cancelled.toProgress("Regional acquisition was cancelled."))
                    }
                    break
                }
            }
            RegionalDownloadResult(results)
        }
    }

    override suspend fun loadInventory(): RegionalInventory = withContext(ioDispatcher) {
        operationMutex.withLock {
            ensureStorageRoot()
            readInventory()
        }
    }

    private suspend fun acquireArtifact(
        artifact: RegionalArtifact,
        priorRecord: RegionalInventoryRecord?,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): RegionalArtifactResult {
        val destination = safeTarget(artifact.relativePath)
        ensureDirectory(destination.parentFile ?: rootDirectory)

        verifiedExisting(artifact, destination, priorRecord)?.let { existing ->
            if (
                priorRecord?.processingState == RegionalProcessingState.READY &&
                processedOutputIsValid(priorRecord.processedOutput)
            ) {
                val result = RegionalArtifactResult(
                    artifact = artifact,
                    status = RegionalTransferStatus.EXISTING,
                    effectiveUrl = existing.effectiveUrl,
                    acquiredAt = existing.acquiredAt,
                    bytes = existing.bytes,
                    sha256 = existing.sha256,
                    etag = priorRecord.etag,
                    lastModified = priorRecord.lastModified,
                    processedOutput = priorRecord.processedOutput,
                    notes = priorRecord.notes,
                )
                onProgress(result.toProgress("Verified existing regional artifact."))
                return result
            }
            return processAndRecord(
                artifact = artifact,
                staging = destination,
                payload = existing.copy(
                    etag = priorRecord?.etag,
                    lastModified = priorRecord?.lastModified,
                ),
                finalStatus = RegionalTransferStatus.EXISTING,
                promoteAfterProcessing = false,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
        }

        val staging = File("${destination.path}$PARTIAL_SUFFIX")
        val partialMetadataFile = File("${destination.path}$PARTIAL_METADATA_SUFFIX")
        return try {
            val payload = transfer(
                artifact = artifact,
                staging = staging,
                metadataFile = partialMetadataFile,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            if (isCancelled()) {
                preserveOrDiscardCancelledPartial(artifact, staging, partialMetadataFile, payload.etag)
                val cancelled = cancelledResult(artifact, payload.bytes, payload.etag, payload.lastModified)
                onProgress(cancelled.toProgress("Regional acquisition was cancelled."))
                cancelled
            } else {
                processAndRecord(
                    artifact = artifact,
                    staging = staging,
                    payload = payload,
                    finalStatus = RegionalTransferStatus.READY,
                    promoteAfterProcessing = true,
                    destination = destination,
                    metadataFile = partialMetadataFile,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
            }
        } catch (cancelled: ExplicitRegionalCancellation) {
            val metadata = readPartialMetadata(partialMetadataFile, artifact)
            preserveOrDiscardCancelledPartial(artifact, staging, partialMetadataFile, metadata?.etag)
            cancelledResult(
                artifact = artifact,
                bytes = staging.length().takeIf { staging.isFile },
                etag = metadata?.etag,
                lastModified = metadata?.lastModified,
            ).also { result ->
                onProgress(result.toProgress("Regional acquisition was cancelled."))
            }
        } catch (cancelled: CancellationException) {
            val metadata = readPartialMetadata(partialMetadataFile, artifact)
            preserveOrDiscardCancelledPartial(artifact, staging, partialMetadataFile, metadata?.etag)
            throw cancelled
        } catch (missing: RegionalArtifactNotFound) {
            discardPartial(staging, partialMetadataFile)
            RegionalArtifactResult(
                artifact = artifact,
                status = RegionalTransferStatus.NOT_FOUND,
                notes = if (artifact.source.optionalWhenNotPublished) {
                    "The provider does not publish this optional regional tile."
                } else {
                    "The provider did not return the requested regional artifact."
                },
            ).also { result -> onProgress(result.toProgress(result.notes)) }
        } catch (error: Exception) {
            val concise = error.conciseMessage("Regional acquisition failed.")
            val metadata = readPartialMetadata(partialMetadataFile, artifact)
            val completedDownload = metadata?.complete == true &&
                metadata.totalBytes == staging.length() &&
                staging.length() in 1..artifact.source.maximumArtifactBytes
            val mustDiscard = error is RegionalDatasetException &&
                error.failure in setOf(
                    RegionalDatasetFailure.INVALID_RESPONSE,
                    RegionalDatasetFailure.RESPONSE_TOO_LARGE,
                )
            if (
                artifact.httpMethod == RegionalHttpMethod.POST ||
                !strongEtag(metadata?.etag) ||
                !staging.isFile ||
                staging.length() !in 1..artifact.source.maximumArtifactBytes ||
                mustDiscard
            ) {
                discardPartial(staging, partialMetadataFile)
            }
            val retained = staging.isFile
            RegionalArtifactResult(
                artifact = artifact,
                status = RegionalTransferStatus.FAILED,
                bytes = staging.length().takeIf { retained },
                sha256 = if (retained && completedDownload) {
                    sha256(staging, artifact.source.maximumArtifactBytes)
                } else {
                    null
                },
                etag = metadata?.etag.takeIf { retained },
                lastModified = metadata?.lastModified.takeIf { retained },
                error = concise,
            ).also { result -> onProgress(result.toProgress(concise)) }
        }
    }

    private suspend fun transfer(
        artifact: RegionalArtifact,
        staging: File,
        metadataFile: File,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): TransferPayload {
        val maximumAttempts = maximumTransferAttempts(artifact)
        var attempt = 1
        while (true) {
            if (isCancelled()) throw ExplicitRegionalCancellation()
            coroutineContext.ensureActive()
            try {
                return transferOnce(
                    artifact = artifact,
                    staging = staging,
                    metadataFile = metadataFile,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
            } catch (restart: RestartTransferFromZero) {
                discardPartial(staging, metadataFile)
                if (attempt >= maximumAttempts) {
                    throw RegionalDatasetException(
                        RegionalDatasetFailure.INVALID_RESPONSE,
                        "The provider repeatedly rejected the saved GET range.",
                        restart,
                    )
                }
                attempt += 1
                awaitRetry(
                    artifact = artifact,
                    staging = staging,
                    attempt = attempt,
                    maximumAttempts = maximumAttempts,
                    message = "The provider rejected the saved range.",
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
            } catch (retryable: RetryableRegionalTransferException) {
                preparePartialForRetry(artifact, staging, metadataFile)
                if (attempt >= maximumAttempts) {
                    throw RegionalDatasetException(
                        RegionalDatasetFailure.NETWORK_FAILED,
                        "Regional transfer failed after $maximumAttempts attempts.",
                        retryable,
                    )
                }
                attempt += 1
                awaitRetry(
                    artifact = artifact,
                    staging = staging,
                    attempt = attempt,
                    maximumAttempts = maximumAttempts,
                    message = retryable.message ?: "A transient network failure interrupted the transfer.",
                    providerDelayMillis = retryable.providerDelayMillis,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
            }
        }
    }

    private suspend fun transferOnce(
        artifact: RegionalArtifact,
        staging: File,
        metadataFile: File,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): TransferPayload {
        if (artifact.httpMethod == RegionalHttpMethod.POST) {
            discardPartial(staging, metadataFile)
            return executeTransfer(
                artifact = artifact,
                staging = staging,
                metadataFile = metadataFile,
                resume = null,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
        }

        var metadata = readPartialMetadata(metadataFile, artifact)
        if (!validResumablePartial(staging, metadata, artifact.source.maximumArtifactBytes)) {
            discardPartial(staging, metadataFile)
            metadata = null
        }
        if (
            metadata?.complete == true &&
            metadata.totalBytes == staging.length() &&
            staging.length() in 1..artifact.source.maximumArtifactBytes
        ) {
            onProgress(
                RegionalDownloadProgress(
                    artifact = artifact,
                    status = RegionalTransferStatus.VERIFYING,
                    completedBytes = staging.length(),
                    totalBytes = staging.length(),
                    message = "Verifying the completed resumable artifact.",
                ),
            )
            return TransferPayload(
                file = staging,
                bytes = staging.length(),
                sha256 = sha256(staging, artifact.source.maximumArtifactBytes),
                requestedUrl = artifact.url,
                effectiveUrl = metadata.effectiveUrl ?: artifact.url,
                acquiredAt = metadata.acquiredAt ?: nowIso8601(),
                etag = metadata.etag,
                lastModified = metadata.lastModified,
            )
        }
        return executeTransfer(
            artifact = artifact,
            staging = staging,
            metadataFile = metadataFile,
            resume = metadata,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    private fun maximumTransferAttempts(artifact: RegionalArtifact): Int = when {
        artifact.httpMethod == RegionalHttpMethod.GET -> MAXIMUM_GET_TRANSFER_ATTEMPTS
        artifact.isReplaySafeOverpassQuery() -> MAXIMUM_REPLAY_SAFE_POST_ATTEMPTS
        else -> 1
    }

    // acquire() first proves that the plan matches the fixed planner. Replay safety therefore
    // follows the validated read-only dataset contract and is not coupled to one endpoint host.
    private fun RegionalArtifact.isReplaySafeOverpassQuery(): Boolean =
        httpMethod == RegionalHttpMethod.POST &&
            source.selection == RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL &&
            source == RegionalDatasetCatalog.osmBuildingsExperimental &&
            requestBody != null &&
            contentType?.startsWith("application/x-www-form-urlencoded", ignoreCase = true) == true

    private fun preparePartialForRetry(
        artifact: RegionalArtifact,
        staging: File,
        metadataFile: File,
    ) {
        if (artifact.httpMethod == RegionalHttpMethod.POST) {
            discardPartial(staging, metadataFile)
            return
        }
        val metadata = readPartialMetadata(metadataFile, artifact)
        if (!validResumablePartial(staging, metadata, artifact.source.maximumArtifactBytes)) {
            discardPartial(staging, metadataFile)
        }
    }

    private suspend fun awaitRetry(
        artifact: RegionalArtifact,
        staging: File,
        attempt: Int,
        maximumAttempts: Int,
        message: String,
        providerDelayMillis: Long? = null,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        if (isCancelled()) throw ExplicitRegionalCancellation()
        coroutineContext.ensureActive()
        val retainedBytes = staging.length().takeIf { staging.isFile } ?: 0L
        onProgress(
            RegionalDownloadProgress(
                artifact = artifact,
                status = RegionalTransferStatus.QUEUED,
                completedBytes = retainedBytes,
                message = "$message Retrying attempt $attempt of $maximumAttempts.",
            ),
        )
        retryDelay(
            providerDelayMillis
                ?: RETRY_BACKOFF_MILLIS[(attempt - 2).coerceIn(RETRY_BACKOFF_MILLIS.indices)],
        )
        if (isCancelled()) throw ExplicitRegionalCancellation()
        coroutineContext.ensureActive()
    }

    private suspend fun executeTransfer(
        artifact: RegionalArtifact,
        staging: File,
        metadataFile: File,
        resume: PartialTransferMetadata?,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): TransferPayload {
        val requestedStart = resume?.let { staging.length() }?.takeIf { it > 0L }
        val request = RegionalHttpRequest(
            url = artifact.url,
            method = when (artifact.httpMethod) {
                RegionalHttpMethod.GET -> RegionalHttpRequestMethod.GET
                RegionalHttpMethod.POST -> RegionalHttpRequestMethod.POST
            },
            rangeStart = requestedStart,
            ifRangeEtag = resume?.etag,
            body = artifact.requestBody,
            contentType = artifact.contentType,
        )
        if (isCancelled()) throw ExplicitRegionalCancellation()
        coroutineContext.ensureActive()
        val requestStarted = nanoClock()
        val response = try {
            transport.execute(request)
        } catch (error: RegionalDatasetException) {
            throw error
        } catch (error: RegionalHttpSecurityException) {
            throw error
        } catch (error: IOException) {
            if (!error.isRetryableTransportFailure()) {
                throw RegionalDatasetException(
                    failure = RegionalDatasetFailure.NETWORK_FAILED,
                    message = "The regional-data secure connection could not be validated.",
                    cause = error,
                )
            }
            throw RetryableRegionalTransferException(
                message = "The regional-data provider could not be reached.",
                cause = error,
            )
        }
        response.use {
            if (response.finalUrl != artifact.url) {
                // Production transport validates each redirect. This exact check also prevents an
                // injected or future transport from silently changing fixed-provider provenance.
                validateFinalRedirect(artifact, response.finalUrl)
            }
            if (response.statusCode == 404) throw RegionalArtifactNotFound()
            if (response.statusCode == 416 && requestedStart != null) {
                throw RestartTransferFromZero()
            }
            if (response.statusCode == 429) {
                val retryAfterMillis = parseBoundedRetryAfterMillis(response.retryAfter, clock())
                    ?: throw RegionalDatasetException(
                        failure = RegionalDatasetFailure.NETWORK_FAILED,
                        message = "The regional-data provider is rate limiting this request. Wait before retrying.",
                    )
                throw RetryableRegionalTransferException(
                    message = "The regional-data provider returned HTTP 429 and requested a bounded wait.",
                    providerDelayMillis = retryAfterMillis,
                )
            }
            if (response.statusCode in 500..599 || response.statusCode in setOf(408, 425)) {
                val requestedDelay = response.retryAfter?.let { value ->
                    parseBoundedRetryAfterMillis(value, clock())
                        ?: throw RegionalDatasetException(
                            failure = RegionalDatasetFailure.NETWORK_FAILED,
                            message = "The regional-data provider requested a retry outside the supported wait window.",
                        )
                }
                throw RetryableRegionalTransferException(
                    message = "The regional-data provider temporarily returned HTTP ${response.statusCode}.",
                    providerDelayMillis = requestedDelay,
                )
            }
            if (response.statusCode !in setOf(200, 206)) {
                throw RegionalDatasetException(
                    failure = RegionalDatasetFailure.INVALID_RESPONSE,
                    message = "The regional-data provider returned HTTP ${response.statusCode}.",
                )
            }

            val append: Boolean
            val totalBytes: Long?
            when (response.statusCode) {
                206 -> {
                    if (requestedStart == null) {
                        throw RegionalDatasetException(
                            RegionalDatasetFailure.INVALID_RESPONSE,
                            "The provider returned an unsolicited partial response.",
                        )
                    }
                    val range = parseContentRange(response.contentRange)
                    if (range.start != requestedStart) {
                        throw invalidRangeResponse("The provider resumed from an unexpected byte offset.")
                    }
                    if (response.etag != resume.etag || !strongEtag(response.etag)) {
                        throw invalidRangeResponse("The provider changed the strong ETag during resume.")
                    }
                    if (resume.effectiveUrl != null && response.finalUrl != resume.effectiveUrl) {
                        throw RestartTransferFromZero()
                    }
                    val expectedResponseBytes = range.end - range.start + 1L
                    if (response.contentLength != null && response.contentLength != expectedResponseBytes) {
                        throw invalidRangeResponse("The partial response length does not match Content-Range.")
                    }
                    if (range.total > artifact.source.maximumArtifactBytes) {
                        discardPartial(staging, metadataFile)
                        throw RegionalDatasetException(
                            RegionalDatasetFailure.RESPONSE_TOO_LARGE,
                            "The provider response exceeds the approved artifact limit.",
                        )
                    }
                    append = true
                    totalBytes = range.total
                }
                else -> {
                    append = false
                    totalBytes = response.contentLength
                    if (totalBytes != null && totalBytes > artifact.source.maximumArtifactBytes) {
                        discardPartial(staging, metadataFile)
                        throw RegionalDatasetException(
                            RegionalDatasetFailure.RESPONSE_TOO_LARGE,
                            "The provider response exceeds the approved artifact limit.",
                        )
                    }
                    metadataFile.delete()
                    File("${metadataFile.path}.tmp").delete()
                    File("${metadataFile.path}.bak").delete()
                }
            }

            if (!append && staging.exists() && !staging.delete()) {
                throw RegionalDatasetException(
                    RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                    "The stale regional staging file could not be reset.",
                )
            }
            val initialBytes = if (append) staging.length() else 0L
            val responseEtag = response.etag?.takeIf(::strongEtag)
            val resumableEtag = if (append) resume?.etag else responseEtag
            val metadata = PartialTransferMetadata(
                url = artifact.url,
                effectiveUrl = response.finalUrl,
                etag = resumableEtag,
                lastModified = response.lastModified,
                totalBytes = totalBytes,
                complete = false,
            )
            if (artifact.httpMethod == RegionalHttpMethod.GET && strongEtag(metadata.etag)) {
                writePartialMetadata(metadataFile, metadata)
            }

            onProgress(
                RegionalDownloadProgress(
                    artifact = artifact,
                    status = RegionalTransferStatus.DOWNLOADING,
                    completedBytes = initialBytes,
                    totalBytes = totalBytes,
                    message = if (append) "Resuming regional download." else "Downloading regional raw data.",
                ),
            )
            val finalBytes = writeResponseBody(
                artifact = artifact,
                responseBody = response.body,
                staging = staging,
                append = append,
                initialBytes = initialBytes,
                declaredResponseBytes = response.contentLength,
                totalBytes = totalBytes,
                requestStartedNanos = requestStarted,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            if (totalBytes != null && finalBytes != totalBytes) {
                throw RetryableRegionalTransferException(
                    "The regional-data response ended before its declared byte count.",
                )
            }

            val completedMetadata = metadata.copy(
                totalBytes = finalBytes,
                complete = true,
                acquiredAt = nowIso8601(),
            )
            if (artifact.httpMethod == RegionalHttpMethod.GET && strongEtag(completedMetadata.etag)) {
                writePartialMetadata(metadataFile, completedMetadata)
            }
            onProgress(
                RegionalDownloadProgress(
                    artifact = artifact,
                    status = RegionalTransferStatus.VERIFYING,
                    completedBytes = finalBytes,
                    totalBytes = finalBytes,
                    message = "Verifying downloaded bytes and SHA-256.",
                ),
            )
            return TransferPayload(
                file = staging,
                bytes = finalBytes,
                sha256 = sha256(staging, artifact.source.maximumArtifactBytes),
                requestedUrl = artifact.url,
                effectiveUrl = response.finalUrl,
                acquiredAt = requireNotNull(completedMetadata.acquiredAt),
                etag = completedMetadata.etag,
                lastModified = completedMetadata.lastModified,
            )
        }
    }

    private suspend fun writeResponseBody(
        artifact: RegionalArtifact,
        responseBody: InputStream,
        staging: File,
        append: Boolean,
        initialBytes: Long,
        declaredResponseBytes: Long?,
        totalBytes: Long?,
        requestStartedNanos: Long,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        var completedBytes = initialBytes
        var responseBytes = 0L
        var lastProgressNanos = requestStartedNanos
        FileOutputStream(staging, append).use { output ->
            try {
                val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    if (isCancelled()) throw ExplicitRegionalCancellation()
                    val read = try {
                        responseBody.read(buffer)
                    } catch (error: IOException) {
                        if (!error.isRetryableTransportFailure()) {
                            throw RegionalDatasetException(
                                RegionalDatasetFailure.NETWORK_FAILED,
                                "The regional-data secure response could not be validated.",
                                error,
                            )
                        }
                        throw RetryableRegionalTransferException(
                            "The regional-data response was interrupted.",
                            error,
                        )
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    responseBytes += read
                    completedBytes += read
                    if (declaredResponseBytes != null && responseBytes > declaredResponseBytes) {
                        throw RegionalDatasetException(
                            RegionalDatasetFailure.INVALID_RESPONSE,
                            "The provider sent more bytes than its declared response length.",
                        )
                    }
                    if (completedBytes > artifact.source.maximumArtifactBytes) {
                        throw RegionalDatasetException(
                            RegionalDatasetFailure.RESPONSE_TOO_LARGE,
                            "The provider response exceeds the approved artifact limit.",
                        )
                    }
                    output.write(buffer, 0, read)
                    val now = nanoClock()
                    if (now - lastProgressNanos >= PROGRESS_INTERVAL_NANOS) {
                        val elapsedSeconds = ((now - requestStartedNanos).coerceAtLeast(1L)) / 1_000_000_000.0
                        onProgress(
                            RegionalDownloadProgress(
                                artifact = artifact,
                                status = RegionalTransferStatus.DOWNLOADING,
                                completedBytes = completedBytes,
                                totalBytes = totalBytes,
                                bytesPerSecond = responseBytes / elapsedSeconds,
                                message = "Downloading regional raw data.",
                            ),
                        )
                        lastProgressNanos = now
                    }
                }
            } finally {
                output.fd.sync()
            }
        }
        if (declaredResponseBytes != null && responseBytes < declaredResponseBytes) {
            throw RetryableRegionalTransferException(
                "The regional-data response ended before its declared byte count.",
            )
        }
        return completedBytes
    }

    private fun processAndRecord(
        artifact: RegionalArtifact,
        staging: File,
        payload: TransferPayload,
        finalStatus: RegionalTransferStatus,
        promoteAfterProcessing: Boolean,
        destination: File = staging,
        metadataFile: File? = null,
        onProgress: (RegionalDownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): RegionalArtifactResult {
        if (isCancelled()) throw ExplicitRegionalCancellation()
        onProgress(
            RegionalDownloadProgress(
                artifact = artifact,
                status = RegionalTransferStatus.PROCESSING,
                completedBytes = payload.bytes,
                totalBytes = payload.bytes,
                message = "Validating and processing regional raw data.",
            ),
        )
        val outcome = try {
            processor.process(
                artifact = artifact,
                rawStagingFile = staging,
                outputRoot = rootDirectory,
                effectiveSourceUrl = payload.effectiveUrl,
            )
        } catch (error: RegionalDatasetException) {
            throw error
        } catch (error: Exception) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.PROCESSING_FAILED,
                "The regional artifact failed validation or processing.",
                error,
            )
        }
        validateProcessedOutput(outcome.output)
        if (isCancelled()) throw ExplicitRegionalCancellation()
        if (promoteAfterProcessing) {
            promoteAtomically(staging, destination)
            metadataFile?.delete()
        }
        val result = RegionalArtifactResult(
            artifact = artifact,
            status = finalStatus,
            requestedUrl = payload.requestedUrl,
            effectiveUrl = payload.effectiveUrl,
            acquiredAt = payload.acquiredAt,
            bytes = payload.bytes,
            sha256 = payload.sha256,
            etag = payload.etag,
            lastModified = payload.lastModified,
            processedOutput = outcome.output,
            notes = outcome.notes,
        )
        onProgress(
            result.toProgress(
                if (finalStatus == RegionalTransferStatus.EXISTING) {
                    "Existing raw data was verified and processed."
                } else {
                    "Regional raw data is verified and processed."
                },
            ),
        )
        return result
    }

    private fun verifiedExisting(
        artifact: RegionalArtifact,
        destination: File,
        record: RegionalInventoryRecord?,
    ): TransferPayload? {
        if (
            record == null ||
            record.relativePath != artifact.relativePath ||
            record.datasetId != artifact.source.datasetId ||
            record.sourceSnapshot.datasetFamily != artifact.source.datasetFamily ||
            record.sourceSnapshot.datasetRelease != artifact.source.datasetRelease ||
            record.sourceSnapshot.queryVersion != artifact.source.queryVersion ||
            record.sourceSnapshot.normalizerVersion != artifact.source.normalizerVersion ||
            !cacheEntryMayBeReused(artifact, record) ||
            record.bytes == null ||
            record.sha256 == null ||
            !destination.isFile ||
            destination.length() != record.bytes ||
            destination.length() !in 1..artifact.source.maximumArtifactBytes
        ) return null
        val actualSha256 = sha256(destination, artifact.source.maximumArtifactBytes)
        if (actualSha256 != record.sha256) return null
        return TransferPayload(
            file = destination,
            bytes = destination.length(),
            sha256 = actualSha256,
            requestedUrl = record.requestedUrl,
            effectiveUrl = record.effectiveUrl ?: record.requestedUrl,
            acquiredAt = record.acquiredAt,
            etag = record.etag,
            lastModified = record.lastModified,
        )
    }

    private fun cacheEntryMayBeReused(
        artifact: RegionalArtifact,
        record: RegionalInventoryRecord,
    ): Boolean = when (artifact.cachePolicy) {
        RegionalArtifactCachePolicy.IMMUTABLE_RELEASE -> true
        RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH -> false
        RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE -> {
            val acquiredAtMillis = record.acquiredAt?.let(::parseInventoryTimestamp) ?: return false
            val maximumAge = artifact.source.maximumCacheAgeMillis ?: return false
            val age = clock() - acquiredAtMillis
            age in 0L..maximumAge
        }
    }

    private fun parseInventoryTimestamp(value: String): Long? = try {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        formatter.parse(value, position)?.time?.takeIf { position.index == value.length }
    } catch (_: Exception) {
        null
    }

    private fun processedOutputIsValid(output: RegionalProcessedOutput?): Boolean {
        if (output == null) return true
        return try {
            val target = safeTarget(output.relativePath)
            target.isFile &&
                target.length() == output.bytes &&
                (output.sha256 == null || sha256(target, MAXIMUM_PROCESSED_OUTPUT_BYTES) == output.sha256)
        } catch (_: Exception) {
            false
        }
    }

    private fun validateProcessedOutput(output: RegionalProcessedOutput?) {
        if (output == null) return
        val target = safeTarget(output.relativePath)
        if (!target.isFile || target.length() != output.bytes) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.PROCESSING_FAILED,
                "The regional processor did not publish its declared output.",
            )
        }
        if (output.bytes > MAXIMUM_PROCESSED_OUTPUT_BYTES) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.PROCESSING_FAILED,
                "The processed regional output exceeds its storage limit.",
            )
        }
        if (output.sha256 != null && sha256(target, MAXIMUM_PROCESSED_OUTPUT_BYTES) != output.sha256) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.PROCESSING_FAILED,
                "The processed regional output failed SHA-256 verification.",
            )
        }
    }

    private fun validateCanonicalPlan(plan: RegionalDownloadPlan) {
        val canonical = try {
            RegionalDatasetPlanner(plan.maximumBatchBytes).plan(plan.request)
        } catch (error: Exception) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_PLAN,
                "The regional download plan is invalid.",
                error,
            )
        }
        if (plan != canonical || plan.estimatedBytes > DEFAULT_MAXIMUM_BATCH_BYTES) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_PLAN,
                "The regional download plan does not match the fixed provider catalog.",
            )
        }
    }

    private fun preflightStorage(plan: RegionalDownloadPlan) {
        val processingBytes = plan.artifacts.sumOf { artifact ->
            when (artifact.source.fileFormat) {
                RegionalFileFormat.COG_GEOTIFF -> MAXIMUM_PROCESSOR_CONTROL_BYTES
                RegionalFileFormat.OVERPASS_JSON -> MAXIMUM_BUILDING_OUTPUT_BYTES
            }
        }
        val safetyBytes = maxOf(
            MINIMUM_STORAGE_SAFETY_BYTES,
            ceil(plan.estimatedBytes * STORAGE_SAFETY_RATIO).toLong(),
        )
        val required = try {
            Math.addExact(Math.addExact(plan.estimatedBytes, processingBytes), safetyBytes)
        } catch (error: ArithmeticException) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_PLAN,
                "The regional storage estimate overflowed its supported range.",
                error,
            )
        }
        val free = try {
            availableBytes(rootDirectory)
        } catch (error: Exception) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "Available private storage could not be measured.",
                error,
            )
        }
        if (free < required) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INSUFFICIENT_STORAGE,
                "Private storage has $free bytes available; $required bytes are required for this regional plan.",
            )
        }
    }

    private fun ensureStorageRoot() {
        ensureDirectory(rootDirectory)
        if (!rootDirectory.canonicalFile.isSamePath(rootDirectory)) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "The regional storage root cannot be a symbolic path.",
            )
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "Private regional storage could not be created.",
            )
        }
        val canonical = try {
            directory.canonicalFile
        } catch (error: IOException) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "A regional storage path could not be resolved.",
                error,
            )
        }
        if (!canonical.isWithin(rootDirectory)) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "A regional storage path escapes the private dataset root.",
            )
        }
    }

    private fun safeTarget(relativePath: String): File {
        if (!SAFE_RELATIVE_PATH.matches(relativePath) || relativePath.split('/').any { it in setOf("", ".", "..") }) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_PLAN,
                "A regional artifact path is unsafe.",
            )
        }
        val target = File(rootDirectory, relativePath).canonicalFile
        if (!target.isWithin(rootDirectory)) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_PLAN,
                "A regional artifact path escapes the private dataset root.",
            )
        }
        return target
    }

    private fun readInventory(): RegionalInventory {
        if (!inventoryFile.isFile && !inventoryBackupFile().isFile) return RegionalInventory()
        val candidates = listOf(inventoryFile, inventoryBackupFile()).filter(File::isFile)
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val payload = FileInputStream(candidate).use { input ->
                    input.readBounded(MAXIMUM_INVENTORY_BYTES)
                }
                val decoded = decodeStrictUtf8(payload)
                val schemaVersion = INVENTORY_SCHEMA_JSON
                    .decodeFromString<InventorySchemaHeader>(decoded)
                    .schemaVersion
                val migrated = schemaVersion == LEGACY_REGIONAL_INVENTORY_SCHEMA_VERSION
                val inventory = when (schemaVersion) {
                    REGIONAL_INVENTORY_SCHEMA_VERSION ->
                        INVENTORY_JSON.decodeFromString<RegionalInventory>(decoded)
                    LEGACY_REGIONAL_INVENTORY_SCHEMA_VERSION ->
                        migrateLegacyInventory(
                            INVENTORY_JSON.decodeFromString<LegacyRegionalInventoryV1>(decoded),
                        )
                    else -> throw IOException("The regional inventory schema is unsupported.")
                }
                validateInventoryFiles(inventory)
                // Persist both a schema migration and recovery from the atomic backup. Otherwise
                // every later load would have to fall through the same invalid or legacy primary.
                if (migrated || candidate != inventoryFile) writeInventory(inventory)
                return inventory
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw RegionalDatasetException(
            RegionalDatasetFailure.INVENTORY_INVALID,
            "The regional dataset inventory is invalid or exceeds its size limit.",
            lastError,
        )
    }

    private fun validateInventoryFiles(inventory: RegionalInventory) {
        if (inventory.schemaVersion != REGIONAL_INVENTORY_SCHEMA_VERSION) {
            throw IOException("The regional inventory schema is unsupported.")
        }
        inventory.artifacts.values.forEach { record -> safeTarget(record.relativePath) }
    }

    private fun migrateLegacyInventory(legacy: LegacyRegionalInventoryV1): RegionalInventory {
        if (
            legacy.schemaVersion != LEGACY_REGIONAL_INVENTORY_SCHEMA_VERSION ||
            legacy.artifacts.size > com.gecesars.atxplan.domain.dataset.MAX_INVENTORY_RECORDS
        ) {
            throw IOException("The legacy regional inventory is unsupported or too large.")
        }
        val migrated = legacy.artifacts.mapValues { (key, record) ->
            if (key != record.relativePath) {
                throw IOException("Legacy regional inventory keys do not match artifact paths.")
            }
            safeTarget(record.relativePath)
            val catalogSource = RegionalDatasetCatalog.sources.singleOrNull { source ->
                source.datasetId == record.datasetId
            } ?: throw IOException("The legacy inventory references an unknown dataset.")
            val sourceSnapshot = catalogSource.toSourceSnapshot().copy(
                catalogRevision = LEGACY_REGIONAL_INVENTORY_SCHEMA_VERSION,
                sourceUrl = record.sourceUrl,
                license = catalogSource.license.copy(
                    id = record.licenseId,
                    url = record.licenseUrl,
                    attribution = record.attribution,
                ),
                provenance = record.provenance,
            )
            RegionalInventoryRecord(
                datasetId = record.datasetId,
                relativePath = record.relativePath,
                requestedUrl = record.url,
                effectiveUrl = null,
                routeId = sourceSnapshot.routeId,
                routePolicyVersion = sourceSnapshot.routePolicyVersion,
                // Schema 1 did not distinguish checking time from acquisition time.
                // Keep the historical value unknown instead of inventing provenance.
                acquiredAt = null,
                sourceSnapshot = sourceSnapshot,
                status = record.status,
                bytes = record.bytes,
                sha256 = record.sha256,
                etag = record.etag,
                lastModified = record.lastModified,
                checkedAt = record.checkedAt,
                bounds = record.bounds,
                processingState = record.processingState,
                processedOutput = record.processedOutput,
                notes = record.notes,
                error = record.error,
            )
        }
        return RegionalInventory(
            artifacts = migrated,
            updatedAt = legacy.updatedAt,
            lastBounds = legacy.lastBounds,
        )
    }

    private fun writeInventory(inventory: RegionalInventory) {
        val payload = try {
            INVENTORY_JSON.encodeToString(RegionalInventory.serializer(), inventory)
                .toByteArray(StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVENTORY_WRITE_FAILED,
                "The regional dataset inventory could not be encoded.",
                error,
            )
        }
        if (payload.size > MAXIMUM_INVENTORY_BYTES) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVENTORY_WRITE_FAILED,
                "The regional dataset inventory exceeds its size limit.",
            )
        }
        ensureDirectory(inventoryFile.parentFile ?: rootDirectory)
        val staging = File("${inventoryFile.path}$INVENTORY_STAGING_SUFFIX")
        try {
            FileOutputStream(staging, false).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            replaceFileAtomically(staging, inventoryFile, inventoryBackupFile())
        } catch (error: Exception) {
            staging.delete()
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVENTORY_WRITE_FAILED,
                "The regional dataset inventory could not be committed atomically.",
                error,
            )
        }
    }

    private fun RegionalInventory.withResult(
        result: RegionalArtifactResult,
        timestamp: String,
        plan: RegionalDownloadPlan,
    ): RegionalInventory {
        val artifact = result.artifact
        val priorRecord = artifacts[artifact.relativePath]
        if (
            result.status in setOf(
                RegionalTransferStatus.FAILED,
                RegionalTransferStatus.CANCELLED,
                RegionalTransferStatus.NOT_FOUND,
            ) &&
            priorRecord != null &&
            storedRecordIsValid(artifact, priorRecord)
        ) {
            return copy(updatedAt = timestamp, lastBounds = plan.request.bounds)
        }
        val processingState = when {
            result.status in setOf(RegionalTransferStatus.READY, RegionalTransferStatus.EXISTING) ->
                RegionalProcessingState.READY
            result.status == RegionalTransferStatus.FAILED && result.sha256 != null ->
                RegionalProcessingState.FAILED
            else -> RegionalProcessingState.PENDING
        }
        val record = if (result.status == RegionalTransferStatus.EXISTING && priorRecord != null) {
            priorRecord.copy(
                status = RegionalTransferStatus.EXISTING,
                checkedAt = timestamp,
                processingState = processingState,
                processedOutput = result.processedOutput,
                notes = result.notes,
                error = null,
            )
        } else RegionalInventoryRecord(
            datasetId = artifact.source.datasetId,
            relativePath = artifact.relativePath,
            requestedUrl = result.requestedUrl,
            effectiveUrl = result.effectiveUrl,
            routeId = result.routeId,
            routePolicyVersion = result.routePolicyVersion,
            acquiredAt = result.acquiredAt,
            sourceSnapshot = result.sourceSnapshot,
            status = result.status,
            bytes = result.bytes,
            sha256 = result.sha256,
            etag = result.etag,
            lastModified = result.lastModified,
            checkedAt = timestamp,
            bounds = artifact.requestBounds,
            processingState = processingState,
            processedOutput = result.processedOutput,
            notes = result.notes,
            error = result.error,
        )
        return copy(
            artifacts = artifacts + (artifact.relativePath to record),
            updatedAt = timestamp,
            lastBounds = plan.request.bounds,
        )
    }

    private fun storedRecordIsValid(
        artifact: RegionalArtifact,
        record: RegionalInventoryRecord,
    ): Boolean = try {
        val bytes = record.bytes ?: return false
        val expectedSha256 = record.sha256 ?: return false
        val target = safeTarget(record.relativePath)
        record.datasetId == artifact.source.datasetId &&
            target.isFile &&
            target.length() == bytes &&
            bytes in 1..artifact.source.maximumArtifactBytes &&
            sha256(target, artifact.source.maximumArtifactBytes) == expectedSha256 &&
            processedOutputIsValid(record.processedOutput)
    } catch (_: Exception) {
        false
    }

    private fun readPartialMetadata(file: File, artifact: RegionalArtifact): PartialTransferMetadata? {
        if (!file.isFile || file.length() !in 1..MAXIMUM_PARTIAL_METADATA_BYTES.toLong()) return null
        return try {
            val payload = FileInputStream(file).use { it.readBounded(MAXIMUM_PARTIAL_METADATA_BYTES) }
            PARTIAL_JSON.decodeFromString<PartialTransferMetadata>(decodeStrictUtf8(payload))
                .takeIf { metadata -> metadata.isValidFor(artifact) }
        } catch (_: Exception) {
            null
        }
    }

    private fun PartialTransferMetadata.isValidFor(artifact: RegionalArtifact): Boolean {
        val effective = effectiveUrl ?: return false
        val acquiredAtMillis = acquiredAt?.let(::parseInventoryTimestamp)
        val completionFieldsAreValid = if (complete) {
            totalBytes != null && acquiredAtMillis != null && acquiredAtMillis in 0L..clock()
        } else {
            acquiredAt == null
        }
        val finalOriginIsValid = try {
            validateFinalRedirect(artifact, effective)
            true
        } catch (_: RegionalDatasetException) {
            false
        }
        return schemaVersion == PARTIAL_METADATA_SCHEMA_VERSION &&
            url == artifact.url &&
            finalOriginIsValid &&
            strongEtag(etag) &&
            (lastModified == null || lastModified.isBoundedMetadataHeader()) &&
            (totalBytes == null || totalBytes in 1..artifact.source.maximumArtifactBytes) &&
            completionFieldsAreValid
    }

    private fun writePartialMetadata(file: File, metadata: PartialTransferMetadata) {
        require(strongEtag(metadata.etag))
        val payload = PARTIAL_JSON.encodeToString(PartialTransferMetadata.serializer(), metadata)
            .toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAXIMUM_PARTIAL_METADATA_BYTES) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_RESPONSE,
                "The provider resume metadata exceeds its size limit.",
            )
        }
        val staging = File("${file.path}.tmp")
        FileOutputStream(staging, false).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        replaceFileAtomically(staging, file, File("${file.path}.bak"))
    }

    private fun validResumablePartial(
        staging: File,
        metadata: PartialTransferMetadata?,
        maximumBytes: Long,
    ): Boolean = metadata != null &&
        strongEtag(metadata.etag) &&
        staging.isFile &&
        staging.length() in 1..maximumBytes &&
        (metadata.totalBytes == null || staging.length() <= metadata.totalBytes)

    private fun preserveOrDiscardCancelledPartial(
        artifact: RegionalArtifact,
        staging: File,
        metadataFile: File,
        etag: String?,
    ) {
        if (
            artifact.httpMethod == RegionalHttpMethod.POST ||
            !staging.isFile ||
            staging.length() !in 1..artifact.source.maximumArtifactBytes ||
            !strongEtag(etag)
        ) {
            discardPartial(staging, metadataFile)
        }
    }

    private fun discardPartial(staging: File, metadataFile: File) {
        staging.delete()
        metadataFile.delete()
        File("${metadataFile.path}.tmp").delete()
        File("${metadataFile.path}.bak").delete()
    }

    private fun promoteAtomically(staging: File, destination: File) {
        if (!staging.isFile) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "The verified regional staging file is missing.",
            )
        }
        val backup = File("${destination.path}$PROMOTION_BACKUP_SUFFIX")
        try {
            replaceFileAtomically(staging, destination, backup)
        } catch (error: Exception) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.STORAGE_UNAVAILABLE,
                "The verified regional artifact could not be promoted atomically.",
                error,
            )
        }
    }

    private fun replaceFileAtomically(staging: File, destination: File, backup: File) {
        if (staging.renameTo(destination)) {
            backup.delete()
            return
        }
        if (backup.exists() && !backup.delete()) throw IOException("A stale backup could not be removed.")
        val hadDestination = destination.exists()
        if (hadDestination && !destination.renameTo(backup)) {
            throw IOException("The previous file could not be backed up.")
        }
        if (!staging.renameTo(destination)) {
            if (hadDestination) backup.renameTo(destination)
            throw IOException("The staged file could not be promoted.")
        }
        if (backup.exists() && !backup.delete()) {
            // The committed destination is authoritative. A stale backup is recoverable cleanup.
            backup.deleteOnExit()
        }
    }

    private fun inventoryBackupFile(): File = File("${inventoryFile.path}$INVENTORY_BACKUP_SUFFIX")

    private fun validateFinalRedirect(artifact: RegionalArtifact, finalUrl: String) {
        if (
            finalUrl.length !in 1..MAXIMUM_REGIONAL_URL_CHARACTERS ||
            finalUrl.any(Char::isISOControl)
        ) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_RESPONSE,
                "The regional-data provider returned an invalid or excessive URL.",
            )
        }
        val (originalUri, finalUri) = try {
            java.net.URI(artifact.url) to java.net.URI(finalUrl)
        } catch (error: Exception) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_RESPONSE,
                "The regional-data provider redirected to an invalid URL.",
                error,
            )
        }
        val originalHost = originalUri.host?.trimEnd('.')?.lowercase(Locale.US)
        val finalHost = finalUri.host?.trimEnd('.')?.lowercase(Locale.US)
        val originalPort = if (originalUri.port == -1) HTTPS_DEFAULT_PORT else originalUri.port
        val finalPort = if (finalUri.port == -1) HTTPS_DEFAULT_PORT else finalUri.port
        if (
            !finalUri.isAbsolute ||
            !finalUri.scheme.equals("https", ignoreCase = true) ||
            finalUri.rawUserInfo != null ||
            finalUri.rawFragment != null ||
            originalHost == null ||
            finalHost == null ||
            finalHost != originalHost ||
            finalPort != originalPort
        ) {
            throw RegionalDatasetException(
                RegionalDatasetFailure.INVALID_RESPONSE,
                "The regional-data provider redirected outside its approved HTTPS origin.",
            )
        }
    }

    private fun nowIso8601(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(clock()))
    }

    private companion object {
        val APPLICATION_REGIONAL_DATASET_MUTEX = Mutex()
    }
}

@Serializable
private data class PartialTransferMetadata(
    val schemaVersion: Int = PARTIAL_METADATA_SCHEMA_VERSION,
    val url: String,
    val effectiveUrl: String? = null,
    val etag: String?,
    val lastModified: String? = null,
    val totalBytes: Long? = null,
    val complete: Boolean = false,
    val acquiredAt: String? = null,
)

@Serializable
private data class InventorySchemaHeader(
    val schemaVersion: Int,
)

@Serializable
private data class LegacyRegionalInventoryV1(
    val schemaVersion: Int = LEGACY_REGIONAL_INVENTORY_SCHEMA_VERSION,
    val artifacts: Map<String, LegacyRegionalInventoryRecordV1> = emptyMap(),
    val updatedAt: String? = null,
    val lastBounds: com.gecesars.atxplan.domain.dataset.RegionalBounds? = null,
)

@Serializable
private data class LegacyRegionalInventoryRecordV1(
    val datasetId: String,
    val relativePath: String,
    val url: String,
    val sourceUrl: String,
    val licenseId: String,
    val licenseUrl: String,
    val attribution: String,
    val provenance: String,
    val status: RegionalTransferStatus,
    val bytes: Long? = null,
    val sha256: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val checkedAt: String,
    val bounds: com.gecesars.atxplan.domain.dataset.RegionalBounds,
    val processingState: RegionalProcessingState,
    val processedOutput: RegionalProcessedOutput? = null,
    val notes: String = "",
    val error: String? = null,
)

private data class TransferPayload(
    val file: File,
    val bytes: Long,
    val sha256: String,
    val requestedUrl: String,
    val effectiveUrl: String,
    val acquiredAt: String?,
    val etag: String? = null,
    val lastModified: String? = null,
)

private data class ParsedContentRange(
    val start: Long,
    val end: Long,
    val total: Long,
)

@Serializable
private data class PersistedTiffMetadataIndex(
    val schemaVersion: Int,
    val datasetId: String,
    val sourceVersion: String,
    val sourceCrs: String,
    val rawRelativePath: String,
    val rawSha256: String,
    val requestBounds: List<Double>,
    val coverageBounds: List<Double>,
    val byteOrder: String,
    val tiffVariant: String,
    val width: Long,
    val height: Long,
    val bandCount: Int,
    val bandCountDeclared: Boolean,
    val bitsPerSample: List<Int>,
    val compression: Int,
    val compressionDeclared: Boolean,
    val sampleFormat: List<Int>,
    val pixelScale: List<Double>? = null,
    val tiePoints: List<Double>? = null,
    val modelTransformation: List<Double>? = null,
    val epsgCode: Int? = null,
    val crsCitation: String? = null,
    val noData: String? = null,
    val rawByteCount: Long,
    val firstIfdOffset: Long,
    val firstIfdEntryCount: Int,
    val metadataOnly: Boolean,
    val rasterSamplesDecoded: Boolean,
    val cloudOptimizedLayoutValidated: Boolean,
)

private class ExplicitRegionalCancellation : IOException()
private class RestartTransferFromZero : IOException()
private class RegionalArtifactNotFound : IOException()
private class RetryableRegionalTransferException(
    message: String,
    cause: Throwable? = null,
    val providerDelayMillis: Long? = null,
) : IOException(message, cause)

private fun parseContentRange(value: String?): ParsedContentRange {
    val match = value?.let(CONTENT_RANGE_PATTERN::matchEntire)
        ?: throw invalidRangeResponse("The partial response has an invalid Content-Range header.")
    val start = match.groupValues[1].toLongOrNull()
        ?: throw invalidRangeResponse("The partial response range start is invalid.")
    val end = match.groupValues[2].toLongOrNull()
        ?: throw invalidRangeResponse("The partial response range end is invalid.")
    val total = match.groupValues[3].toLongOrNull()
        ?: throw invalidRangeResponse("The partial response total length is invalid.")
    if (start < 0L || end < start || total <= end) {
        throw invalidRangeResponse("The partial response Content-Range values are inconsistent.")
    }
    return ParsedContentRange(start, end, total)
}

private fun parseBoundedRetryAfterMillis(value: String?, nowEpochMillis: Long): Long? {
    val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val delayMillis = if (candidate.all(Char::isDigit)) {
        val seconds = candidate.toLongOrNull() ?: return null
        if (seconds > MAXIMUM_RETRY_AFTER_MILLIS / 1_000L) return null
        (seconds * 1_000L).coerceAtLeast(MINIMUM_RETRY_AFTER_MILLIS)
    } else {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val position = ParsePosition(0)
        val retryAt = formatter.parse(candidate, position)?.time ?: return null
        if (position.index != candidate.length) return null
        (retryAt - nowEpochMillis).coerceAtLeast(MINIMUM_RETRY_AFTER_MILLIS)
    }
    return delayMillis.takeIf { it in MINIMUM_RETRY_AFTER_MILLIS..MAXIMUM_RETRY_AFTER_MILLIS }
}

private fun invalidRangeResponse(message: String): RegionalDatasetException =
    RegionalDatasetException(RegionalDatasetFailure.INVALID_RESPONSE, message)

private fun IOException.isRetryableTransportFailure(): Boolean {
    if (
        this is RegionalHttpSecurityException ||
        this is ProtocolException ||
        this is SSLHandshakeException ||
        this is SSLPeerUnverifiedException
    ) return false
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAXIMUM_CAUSE_INSPECTION_DEPTH) {
        if (current is CertificateException) return false
        current = current.cause
        depth += 1
    }
    return true
}

private fun RegionalArtifactResult.toProgress(message: String): RegionalDownloadProgress =
    RegionalDownloadProgress(
        artifact = artifact,
        status = status,
        completedBytes = bytes ?: 0L,
        totalBytes = bytes,
        message = message.take(MAX_STATUS_MESSAGE_LENGTH),
    )

private fun cancelledResult(
    artifact: RegionalArtifact,
    bytes: Long? = null,
    etag: String? = null,
    lastModified: String? = null,
): RegionalArtifactResult = RegionalArtifactResult(
    artifact = artifact,
    status = RegionalTransferStatus.CANCELLED,
    bytes = bytes,
    etag = etag,
    lastModified = lastModified,
    notes = if (
        artifact.httpMethod == RegionalHttpMethod.GET &&
        bytes != null &&
        bytes > 0L &&
        strongEtag(etag)
    ) {
        "Regional acquisition was cancelled; the validated GET partial can be resumed."
    } else {
        "Regional acquisition was cancelled; no resumable partial was retained."
    },
)

private fun Exception.conciseMessage(fallback: String): String =
    (message?.trim()?.takeIf(String::isNotEmpty) ?: fallback).take(MAX_STATUS_MESSAGE_LENGTH)

private fun strongEtag(value: String?): Boolean =
    value != null &&
        value.length in 2..MAXIMUM_ETAG_LENGTH &&
        value.startsWith('"') &&
        value.endsWith('"') &&
        !value.startsWith("W/", ignoreCase = true) &&
        '\r' !in value &&
        '\n' !in value

private fun String.isBoundedMetadataHeader(): Boolean =
    length in 1..MAXIMUM_METADATA_HEADER_CHARACTERS && none(Char::isISOControl)

private fun sha256(file: File, maximumBytes: Long): String {
    if (!file.isFile || file.length() !in 0..maximumBytes) {
        throw RegionalDatasetException(
            RegionalDatasetFailure.RESPONSE_TOO_LARGE,
            "The regional file exceeds its approved verification limit.",
        )
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumBytes) {
                throw RegionalDatasetException(
                    RegionalDatasetFailure.RESPONSE_TOO_LARGE,
                    "The regional file exceeds its approved verification limit.",
                )
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) throw IOException("The control file exceeds its size limit.")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeStrictUtf8(payload: ByteArray): String =
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(payload))
        .toString()

private fun decodeOverpassFormBody(body: String): String {
    require(body.startsWith("data=") && body.length <= MAXIMUM_OVERPASS_FORM_CHARACTERS) {
        "The fixed Overpass form body is invalid."
    }
    return try {
        URLDecoder.decode(body.removePrefix("data="), StandardCharsets.UTF_8.name())
    } catch (error: Exception) {
        throw IOException("The fixed Overpass query could not be decoded.", error)
    }
}

private fun writeImmutableOutput(target: File, payload: ByteArray) {
    if (payload.size > MAXIMUM_PROCESSOR_CONTROL_BYTES) {
        throw IOException("The processed metadata index exceeds its size limit.")
    }
    val parent = target.parentFile ?: throw IOException("The processed output path is invalid.")
    if (!parent.isDirectory && !parent.mkdirs()) {
        throw IOException("The processed output directory could not be created.")
    }
    if (target.isFile) {
        val existing = FileInputStream(target).use { input ->
            input.readBounded(MAXIMUM_PROCESSOR_CONTROL_BYTES.toInt())
        }
        if (existing.contentEquals(payload)) return
        throw IOException("A different processed output already uses this immutable identity.")
    }
    val staging = File("${target.path}.tmp")
    FileOutputStream(staging, false).use { output ->
        output.write(payload)
        output.fd.sync()
    }
    if (!staging.renameTo(target)) {
        val backup = File("${target.path}.bak")
        if (backup.exists() && !backup.delete()) throw IOException("A stale output backup could not be removed.")
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) throw IOException("The prior processed output could not be backed up.")
        if (!staging.renameTo(target)) {
            if (hadTarget) backup.renameTo(target)
            throw IOException("The processed output could not be committed atomically.")
        }
        backup.delete()
    }
}

private fun File.normalizePathWithoutExistingRequirement(): File =
    try {
        canonicalFile
    } catch (_: IOException) {
        absoluteFile
    }

private fun File.isWithin(root: File): Boolean =
    path == root.path || path.startsWith(root.path + File.separator)

private fun File.isSamePath(other: File): Boolean = path == other.path

private val INVENTORY_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

private val INVENTORY_SCHEMA_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

private val PARTIAL_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

private val PROCESSOR_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = false
}

private val SAFE_RELATIVE_PATH = Regex("^[A-Za-z0-9._/-]{1,240}$")
private val CONTENT_RANGE_PATTERN = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")

private const val INVENTORY_FILE_NAME = ".atx-regional-inventory.json"
private const val INVENTORY_STAGING_SUFFIX = ".tmp"
private const val INVENTORY_BACKUP_SUFFIX = ".bak"
private const val PARTIAL_SUFFIX = ".part"
private const val PARTIAL_METADATA_SUFFIX = ".part.json"
private const val PROMOTION_BACKUP_SUFFIX = ".replace-backup"
private const val PARTIAL_METADATA_SCHEMA_VERSION = 1
private const val LEGACY_REGIONAL_INVENTORY_SCHEMA_VERSION = 1
private const val MAXIMUM_INVENTORY_BYTES = 4 * 1024 * 1024
private const val MAXIMUM_PARTIAL_METADATA_BYTES = 8 * 1024
private const val MAXIMUM_ETAG_LENGTH = 1_024
private const val MAXIMUM_METADATA_HEADER_CHARACTERS = 2_048
private const val MAXIMUM_REGIONAL_URL_CHARACTERS = 2_048
private const val TRANSFER_BUFFER_BYTES = 64 * 1024
private const val PROGRESS_INTERVAL_NANOS = 200_000_000L
private const val STORAGE_SAFETY_RATIO = 0.15
private const val MINIMUM_STORAGE_SAFETY_BYTES = 64L * MEBIBYTE
private const val MAXIMUM_PROCESSED_OUTPUT_BYTES = 512L * MEBIBYTE
private const val TIFF_METADATA_SCHEMA_VERSION = 1
private const val MAXIMUM_PROCESSOR_CONTROL_BYTES = 4L * MEBIBYTE
private const val MAXIMUM_BUILDING_OUTPUT_BYTES = 128L * MEBIBYTE
private const val MAXIMUM_OVERPASS_FORM_CHARACTERS = 256 * 1024
private const val MAXIMUM_GET_TRANSFER_ATTEMPTS = 3
private const val MAXIMUM_REPLAY_SAFE_POST_ATTEMPTS = 2
private const val MAXIMUM_CAUSE_INSPECTION_DEPTH = 16
private const val HTTPS_DEFAULT_PORT = 443
private const val MINIMUM_RETRY_AFTER_MILLIS = 1_000L
private const val MAXIMUM_RETRY_AFTER_MILLIS = 30_000L
private val RETRY_BACKOFF_MILLIS = longArrayOf(750L, 2_000L)
