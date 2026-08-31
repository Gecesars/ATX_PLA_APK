package com.gecesars.atxplan.ui.antenna

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gecesars.atxplan.data.antenna.AntennaPatternCodec
import com.gecesars.atxplan.data.antenna.AntennaPatternCodecLimits
import com.gecesars.atxplan.data.antenna.AntennaPatternCanonicalArtifactVerifier
import com.gecesars.atxplan.data.antenna.AntennaPatternEncodeOptions
import com.gecesars.atxplan.data.antenna.AntennaPatternFileFormat
import com.gecesars.atxplan.data.antenna.AntennaPatternFileMetadata
import com.gecesars.atxplan.data.antenna.AntennaPatternPairCodec
import com.gecesars.atxplan.data.antenna.AntennaPatternPairSource
import com.gecesars.atxplan.data.antenna.PairedAntennaPatternImport
import com.gecesars.atxplan.data.antenna.ParsedAntennaPattern
import com.gecesars.atxplan.data.antenna.PreparedAntennaExport
import com.gecesars.atxplan.data.antenna.PreparedAntennaExportCache
import com.gecesars.atxplan.data.antenna.PrnValueConventionOverride
import com.gecesars.atxplan.data.antenna.PrnValueConventionRequiredException
import com.gecesars.atxplan.data.project.ArtifactAvailability
import com.gecesars.atxplan.data.project.FileProjectRepository
import com.gecesars.atxplan.data.project.ProjectArtifactRepository
import com.gecesars.atxplan.domain.antenna.AntennaArrayConfiguration
import com.gecesars.atxplan.domain.antenna.AntennaArrayElement
import com.gecesars.atxplan.domain.antenna.AntennaArrayComposer
import com.gecesars.atxplan.domain.antenna.AntennaCompositionOutcome
import com.gecesars.atxplan.domain.antenna.ApertureDirection
import com.gecesars.atxplan.domain.antenna.AperturePositionMeters
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.ElementOrientation
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.application.AntennaPatternCatalogUseCase
import com.gecesars.atxplan.domain.application.AntennaPatternMutationStatus
import com.gecesars.atxplan.domain.application.AssignTransmitAntennaPatternCommand
import com.gecesars.atxplan.domain.application.DeleteAntennaPatternCommand
import com.gecesars.atxplan.domain.application.InstallAntennaPatternCommand
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import com.gecesars.atxplan.domain.model.Sector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.acos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AntennaPatternLabViewModel private constructor(
    private val applicationContext: Context,
    private val projectId: String,
    private val repository: FileProjectRepository,
    private val catalogUseCase: AntennaPatternCatalogUseCase = AntennaPatternCatalogUseCase(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(AntennaPatternLabUiState())
    val state: StateFlow<AntennaPatternLabUiState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var pendingImport: PendingAntennaImport? = null
    private var pendingPrnConventionChoice: PendingPrnConventionChoice? = null
    private var pendingExport: PendingAntennaExport? = null
    private val preparedExportCache = PreparedAntennaExportCache(
        directory = File(
            applicationContext.noBackupFilesDir,
            PREPARED_EXPORT_CACHE_DIRECTORY,
        ),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { preparedExportCache.cleanup() }
        }
    }

    fun inspectImport(uri: Uri) = launchOperation("Inspecting antenna file") {
        clearPendingPrnConventionChoice()
        val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) }
        val sourceBytes = withContext(Dispatchers.IO) {
            applicationContext.contentResolver.openInputStream(uri)?.use(::readBounded)
                ?: throw IOException("The selected document could not be opened.")
        }
        val parsed = try {
            withContext(Dispatchers.Default) {
                AntennaPatternCodec.parse(sourceBytes, displayName)
            }
        } catch (required: PrnValueConventionRequiredException) {
            publishPrnConventionChoice(
                pending = PendingPrnConventionChoice.Single(
                    token = UUID.randomUUID().toString(),
                    displayName = displayName,
                    sourceBytes = sourceBytes,
                ),
                required = required,
            )
            return@launchOperation
        }
        publishSingleImport(displayName, sourceBytes, parsed)
    }

    fun inspectImportPair(uris: List<Uri>) = launchOperation("Inspecting HRP and VRP files") {
        clearPendingPrnConventionChoice()
        require(uris.size == 2 && uris.distinct().size == 2) {
            "Select exactly two distinct files: one HRP and one VRP."
        }
        val sources = withContext(Dispatchers.IO) {
            var remainingBytes = AntennaPatternPairCodec.MAX_PAIR_SOURCE_BYTES.toInt()
            uris.map { uri ->
                val displayName = resolveDisplayName(uri)
                val payload = applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                    readBounded(
                        input = input,
                        maximumBytes = remainingBytes,
                        exceededMessage =
                            "The selected HRP/VRP files exceed the combined 15 MiB import limit.",
                    )
                }
                    ?: throw IOException("The selected document $displayName could not be opened.")
                remainingBytes -= payload.size
                AntennaPatternPairSource(displayName, payload)
            }
        }
        val paired = try {
            withContext(Dispatchers.Default) {
                AntennaPatternPairCodec.parsePair(sources)
            }
        } catch (required: PrnValueConventionRequiredException) {
            publishPrnConventionChoice(
                pending = PendingPrnConventionChoice.Pair(
                    token = UUID.randomUUID().toString(),
                    sources = sources,
                ),
                required = required,
            )
            return@launchOperation
        }
        publishPairedImport(paired)
    }

    fun resolvePrnConventionChoice(
        token: String,
        interpretation: AntennaPrnValueInterpretation,
    ) = launchOperation("Applying PRN value interpretation") {
        val pending = pendingPrnConventionChoice
            ?.takeIf { candidate -> candidate.token == token }
            ?: throw IllegalArgumentException(
                "The pending PRN interpretation choice is no longer available.",
            )
        val override = when (interpretation) {
            AntennaPrnValueInterpretation.DESKTOP_POSITIVE_ATTENUATION_DB ->
                PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB

            AntennaPrnValueInterpretation.NORMALIZED_LINEAR_FIELD ->
                PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD
        }
        try {
            when (pending) {
                is PendingPrnConventionChoice.Single -> {
                    val parsed = withContext(Dispatchers.Default) {
                        AntennaPatternCodec.parse(
                            input = pending.sourceBytes,
                            displayName = pending.displayName,
                            prnValueConventionOverride = override,
                        )
                    }
                    publishSingleImport(pending.displayName, pending.sourceBytes, parsed)
                }

                is PendingPrnConventionChoice.Pair -> {
                    val paired = withContext(Dispatchers.Default) {
                        AntennaPatternPairCodec.parsePair(
                            sources = pending.sources,
                            prnValueConventionOverride = override,
                        )
                    }
                    publishPairedImport(paired)
                }
            }
        } finally {
            clearPendingPrnConventionChoice()
        }
    }

    fun dismissPrnConventionChoice(token: String) {
        if (pendingPrnConventionChoice?.token == token ||
            mutableState.value.pendingPrnConventionChoice?.token == token
        ) {
            clearPendingPrnConventionChoice()
        }
    }

    fun confirmImport() = launchOperation("Storing antenna pattern") {
        val pending = pendingImport
            ?: throw IllegalStateException("No reviewed antenna import is pending.")
        check(pending.pattern.isCalculationReady) {
            "This import is review-only because it lacks an explicitly available HRP or VRP cut. " +
                "Import the missing companion cut as a complete pattern before storing it."
        }
        val canonicalPayload = withContext(Dispatchers.Default) {
            AntennaPatternCodec.encode(
                pending.pattern,
                AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
                AntennaPatternEncodeOptions(
                    nominalFrequencyHz = pending.metadata.nominalFrequencyHz,
                    title = pending.pattern.name,
                    declaredGainDbi = pending.metadata.declaredGainDbi,
                    verticalCutAzimuthDegrees = pending.metadata.verticalCutAzimuthDegrees,
                    beamTiltDegrees = pending.metadata.beamTiltDegrees,
                ),
            )
        }
        val canonicalArtifactSha256 = sha256(canonicalPayload)
        val peakGainDbi = pending.metadata.declaredGainDbi
        val sourceArtifact = repository.storeArtifact(
            role = ProjectArtifactRole.IMPORT_SOURCE,
            fileName = pending.displayName.safeArtifactFileName(),
            mediaType = pending.sourceMediaType,
            input = ByteArrayInputStream(pending.sourceBytes),
            maximumBytes = AntennaPatternCodecLimits.MAX_INPUT_BYTES.toLong(),
            expectedSha256 = pending.sourceSha256,
        )
        val canonicalArtifact = repository.storeArtifact(
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            fileName = "${pending.pattern.name.safeFileStem()}.atx-antenna.json",
            mediaType = ATX_ANTENNA_MEDIA_TYPE,
            input = ByteArrayInputStream(canonicalPayload),
            maximumBytes = AntennaPatternCodecLimits.MAX_INPUT_BYTES.toLong(),
            expectedSha256 = canonicalArtifactSha256,
        )
        val record = pending.pattern.toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "pattern-${UUID.randomUUID()}",
                name = pending.pattern.name.take(160),
                peakGainDbi = peakGainDbi,
                sourceFormat = pending.sourceFormatLabel,
                sourceSha256 = pending.sourceSha256,
                sourceArtifactId = sourceArtifact.id,
                canonicalArtifactId = canonicalArtifact.id,
                origin = AntennaPatternOrigin.IMPORTED,
                warnings = pending.warnings,
            ),
        )
        var resultStatus = AntennaPatternMutationStatus.NOT_FOUND
        var resultPatternId: String? = null
        val resultCatalog = repository.updateCatalog { catalog ->
            catalogUseCase.install(
                catalog,
                InstallAntennaPatternCommand(
                    projectId = projectId,
                    pattern = record,
                    canonicalArtifact = canonicalArtifact,
                    sourceArtifact = sourceArtifact,
                ),
            ).also { result ->
                resultStatus = result.status
                resultPatternId = result.patternId
            }.catalog
        }
        if (resultStatus == AntennaPatternMutationStatus.DUPLICATE) {
            val committedProject = resultCatalog.projects.firstOrNull { project -> project.id == projectId }
                ?: throw IllegalStateException("The project is no longer available.")
            requireAvailableAntennaPatternArtifacts(
                project = committedProject,
                patternId = checkNotNull(resultPatternId) {
                    "The duplicate antenna result did not identify its stored pattern."
                },
                repository = repository,
            )
        }
        when (resultStatus) {
            AntennaPatternMutationStatus.INSTALLED -> {
                clearPendingImport()
                catalogChanged("Antenna pattern ${record.name} was imported and verified.")
            }

            AntennaPatternMutationStatus.DUPLICATE -> {
                clearPendingImport()
                mutableState.update { current ->
                    current.copy(
                        notice = "The same canonical antenna artifact was committed by another operation.",
                        error = null,
                    )
                }
            }

            AntennaPatternMutationStatus.NOT_FOUND -> throw IllegalStateException(
                "The project is no longer available; no antenna reference was committed.",
            )

            else -> throw IllegalStateException("The antenna import could not be committed safely.")
        }
    }

    fun dismissImport() {
        if (mutableState.value.isBusy) return
        clearPendingImport()
    }

    fun synthesize(request: AntennaArraySynthesisRequest) = launchOperation("Synthesizing array") {
        val latestProject = repository.loadCatalog().projects.firstOrNull { project -> project.id == projectId }
            ?: throw IllegalStateException("The project is no longer available.")
        val requestedPatternIds = buildSet {
            request.basePatternId?.let(::add)
            request.arbitraryElements.mapNotNullTo(this) { element -> element.patternId }
        }
        require(requestedPatternIds.size <= 513) {
            "The array references too many distinct canonical antenna patterns."
        }
        val verifiedPatterns = requestedPatternIds.associateWith { patternId ->
            loadVerifiedPattern(latestProject, patternId)
        }
        val basePattern = request.basePatternId?.let(verifiedPatterns::getValue)
            ?: CanonicalAntennaPattern.isotropic(nominalFrequencyHz = request.frequencyMHz * 1.0e6)
        val configuration = withContext(Dispatchers.Default) {
            buildArrayConfiguration(request, basePattern, verifiedPatterns)
        }
        val outcome = withContext(Dispatchers.Default) {
            AntennaArrayComposer.compose(configuration)
        }
        val available = when (outcome) {
            is AntennaCompositionOutcome.Available -> outcome
            is AntennaCompositionOutcome.NoData -> throw IllegalArgumentException(outcome.reason)
            is AntennaCompositionOutcome.Unsupported -> throw IllegalArgumentException(outcome.reason)
        }
        val peakGainDbi = available.metrics.gainDbi
        val canonicalPayload = withContext(Dispatchers.Default) {
            AntennaPatternCodec.encode(
                pattern = available.pattern,
                format = AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
                options = AntennaPatternEncodeOptions(
                    nominalFrequencyHz = available.pattern.nominalFrequencyHz,
                    title = available.pattern.name,
                    declaredGainDbi = peakGainDbi,
                    // The composer calculates its canonical VRP at horizontal azimuth zero.
                    verticalCutAzimuthDegrees = 0.0,
                ),
            )
        }
        val canonicalArtifactSha256 = sha256(canonicalPayload)
        val canonicalArtifact = repository.storeArtifact(
            role = ProjectArtifactRole.ANTENNA_PATTERN,
            fileName = "${request.name.safeFileStem()}.atx-antenna.json",
            mediaType = ATX_ANTENNA_MEDIA_TYPE,
            input = ByteArrayInputStream(canonicalPayload),
            maximumBytes = AntennaPatternCodecLimits.MAX_INPUT_BYTES.toLong(),
            expectedSha256 = canonicalArtifactSha256,
        )
        val warnings = available.warnings.map { warning -> warning.message }
        val record = available.pattern.toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = "pattern-${UUID.randomUUID()}",
                name = request.name,
                peakGainDbi = peakGainDbi,
                sourceFormat = AntennaArrayComposer.ENGINE_ID,
                sourceSha256 = null,
                sourceArtifactId = null,
                canonicalArtifactId = canonicalArtifact.id,
                origin = AntennaPatternOrigin.SYNTHESIZED,
                warnings = warnings,
            ),
        )
        var resultStatus = AntennaPatternMutationStatus.NOT_FOUND
        var resultPatternId: String? = null
        val resultCatalog = repository.updateCatalog { catalog ->
            catalogUseCase.install(
                catalog,
                InstallAntennaPatternCommand(projectId, record, canonicalArtifact),
            ).also { result ->
                resultStatus = result.status
                resultPatternId = result.patternId
            }.catalog
        }
        if (resultStatus == AntennaPatternMutationStatus.DUPLICATE) {
            val committedProject = resultCatalog.projects.firstOrNull { project -> project.id == projectId }
                ?: throw IllegalStateException("The project is no longer available.")
            requireAvailableAntennaPatternArtifacts(
                project = committedProject,
                patternId = checkNotNull(resultPatternId) {
                    "The duplicate antenna result did not identify its stored pattern."
                },
                repository = repository,
            )
        }
        when (resultStatus) {
            AntennaPatternMutationStatus.INSTALLED -> catalogChanged(
                "${record.name} was synthesized at ${formatDb(available.metrics.gainDbi)} dBi and stored.",
            )

            AntennaPatternMutationStatus.DUPLICATE -> mutableState.update { current ->
                current.copy(notice = "The same synthesized pattern already exists.", error = null)
            }

            AntennaPatternMutationStatus.NOT_FOUND -> throw IllegalStateException(
                "The project is no longer available; the synthesized artifact is unreferenced.",
            )

            else -> throw IllegalStateException("The synthesized pattern could not be committed safely.")
        }
    }

    private suspend fun loadVerifiedPattern(
        project: PlannerProject,
        patternId: String,
    ): CanonicalAntennaPattern {
        val record = project.antennaPatterns.firstOrNull { pattern -> pattern.id == patternId }
            ?: throw IllegalArgumentException("An element references an unavailable base pattern.")
        val artifactId = record.dataArtifactId
            ?: throw IllegalArgumentException(
                "An element base pattern has no canonical artifact reference.",
            )
        val artifact = project.artifacts.singleOrNull { candidate -> candidate.id == artifactId }
            ?: throw IllegalArgumentException(
                "An element base pattern canonical artifact is missing from the project.",
            )
        val canonicalBytes = ByteArrayOutputStream()
        repository.copyArtifact(
            reference = artifact,
            output = canonicalBytes,
            maximumBytes = AntennaPatternCodecLimits.MAX_INPUT_BYTES.toLong(),
        )
        return withContext(Dispatchers.Default) {
            AntennaPatternCanonicalArtifactVerifier.verify(
                record = record,
                artifact = artifact,
                payload = canonicalBytes.toByteArray(),
            ).pattern
        }
    }

    fun prepareExport(
        patternId: String,
        format: AntennaPatternExportFormat,
    ) = launchOperation("Preparing ${format.label}") {
        discardPendingExportForReplacement()
        val project = repository.loadCatalog().projects
            .firstOrNull { candidate -> candidate.id == projectId }
            ?: throw IllegalArgumentException("The project no longer exists.")
        val record = project.antennaPatterns
            .firstOrNull { pattern -> pattern.id == patternId }
            ?: throw IllegalArgumentException("The selected antenna pattern no longer exists.")
        val canonicalArtifactId = record.dataArtifactId
            ?: throw IllegalArgumentException("The antenna pattern has no canonical artifact reference.")
        val canonicalArtifact = project.artifacts
            .singleOrNull { artifact -> artifact.id == canonicalArtifactId }
            ?: throw IllegalArgumentException("The canonical antenna artifact is missing from the project.")
        val canonicalBytes = ByteArrayOutputStream()
        repository.copyArtifact(
            reference = canonicalArtifact,
            output = canonicalBytes,
            maximumBytes = AntennaPatternCodecLimits.MAX_INPUT_BYTES.toLong(),
        )
        val verifiedCanonical = withContext(Dispatchers.Default) {
            AntennaPatternCanonicalArtifactVerifier.verify(
                record = record,
                artifact = canonicalArtifact,
                payload = canonicalBytes.toByteArray(),
            )
        }
        val exportArtifact = withContext(Dispatchers.Default) {
            AntennaPatternCodec.encodeArtifact(
                pattern = verifiedCanonical.pattern,
                format = format.toCodecFormat(),
                options = AntennaPatternEncodeOptions(
                    nominalFrequencyHz = verifiedCanonical.pattern.nominalFrequencyHz,
                    title = record.name,
                    declaredGainDbi = verifiedCanonical.metadata.declaredGainDbi,
                    verticalCutAzimuthDegrees =
                        verifiedCanonical.metadata.verticalCutAzimuthDegrees,
                    beamTiltDegrees = verifiedCanonical.metadata.beamTiltDegrees,
                ),
            )
        }
        val payload = exportArtifact.payload
        require(payload.size <= AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
            "The generated antenna export exceeds the 16 MiB mobile limit."
        }
        val expectedSha256 = sha256(payload)
        val cached = withContext(Dispatchers.IO) {
            preparedExportCache.store(
                patternId = patternId,
                formatName = format.name,
                suggestedFileName = "${record.name.safeFileStem()}.${format.extension}",
                payload = payload,
                warnings = exportArtifact.warnings,
            )
        }
        require(cached.sha256 == expectedSha256 && cached.payload.contentEquals(payload)) {
            "The prepared antenna export cache failed payload correlation."
        }
        val prepared = cached.toPendingAntennaExport(format)
        pendingExport = prepared
        publishPendingExport(prepared)
    }

    fun export(
        token: String,
        format: AntennaPatternExportFormat,
        destination: Uri,
    ) = launchOperation("Exporting ${format.label}") {
        val cached = withContext(Dispatchers.IO) { preparedExportCache.load(token) }
        if (cached == null) {
            clearPendingExport()
            throw IllegalArgumentException(
                "The prepared antenna export expired or is no longer available. Prepare it again.",
            )
        }
        require(cached.formatName == format.name) {
            "The prepared antenna export format does not match the restored destination request."
        }
        require(
            cached.payload.size <= AntennaPatternCodecLimits.MAX_INPUT_BYTES &&
                cached.sha256 == sha256(cached.payload),
        ) {
            "The recovered antenna export failed bounded payload verification."
        }
        val prepared = cached.toPendingAntennaExport(format)
        pendingExport?.let { inMemory ->
            require(
                inMemory.token == prepared.token &&
                    inMemory.patternId == prepared.patternId &&
                    inMemory.format == prepared.format &&
                    inMemory.suggestedFileName == prepared.suggestedFileName &&
                    inMemory.sha256 == prepared.sha256 &&
                    inMemory.warnings == prepared.warnings &&
                    inMemory.payload.contentEquals(prepared.payload),
            ) {
                "The in-memory and recovered antenna exports do not match."
            }
        }
        pendingExport = prepared
        publishPendingExport(prepared)
        val payload = prepared.payload
        withContext(Dispatchers.IO) {
            applicationContext.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                output.write(payload)
                output.flush()
            } ?: throw IOException("The selected export destination could not be opened.")
            val verified = applicationContext.contentResolver.openInputStream(destination)?.use { input ->
                readBounded(
                    input = input,
                    exceededMessage = "The reopened antenna export exceeds the 16 MiB limit.",
                )
            } ?: throw IOException("The exported document could not be reopened for verification.")
            if (!verified.contentEquals(payload)) {
                throw IOException("The exported document failed read-back verification.")
            }
        }
        withContext(Dispatchers.IO) { preparedExportCache.remove(prepared.token) }
        clearPendingExport()
        mutableState.update { current ->
            current.copy(
                notice = buildString {
                    append("${format.label} export verified · SHA-256 ${prepared.sha256.take(12)}…")
                    if (prepared.warnings.isNotEmpty()) {
                        append("\nFormat warnings (${prepared.warnings.size}):")
                        prepared.warnings.forEach { warning ->
                            append("\n• ")
                            append(warning)
                        }
                    }
                },
                error = null,
            )
        }
    }

    fun dismissExport(token: String) = launchOperation("Discarding prepared export") {
        withContext(Dispatchers.IO) { preparedExportCache.remove(token) }
        if (pendingExport?.token == token || mutableState.value.pendingExport?.token == token) {
            clearPendingExport()
        }
    }

    fun assignTransmitPattern(
        siteId: String,
        expectedSector: Sector,
        patternId: String?,
    ) = launchOperation("Assigning transmit pattern") {
        var status = AntennaPatternMutationStatus.NOT_FOUND
        var rejectionReason: String? = null
        repository.updateCatalog { catalog ->
            catalogUseCase.assignTransmitPattern(
                catalog,
                AssignTransmitAntennaPatternCommand(
                    projectId = projectId,
                    siteId = siteId,
                    expectedSector = expectedSector,
                    patternId = patternId,
                ),
            ).also { result ->
                status = result.status
                rejectionReason = result.reason
            }.catalog
        }
        when (status) {
            AntennaPatternMutationStatus.ASSIGNED -> catalogChanged(
                if (patternId == null) {
                    "The sector now uses nominal omnidirectional ERP."
                } else {
                    "The transmit pattern was assigned to the sector."
                },
            )

            AntennaPatternMutationStatus.UNCHANGED -> mutableState.update { current ->
                current.copy(notice = "The sector pattern assignment is already current.", error = null)
            }

            AntennaPatternMutationStatus.STALE -> throw IllegalStateException(
                "The sector changed in storage. Review the latest values and assign again.",
            )

            AntennaPatternMutationStatus.NOT_FOUND -> throw IllegalStateException(
                "The project, sector, or pattern is no longer available.",
            )

            AntennaPatternMutationStatus.NOT_CALCULATION_READY -> throw IllegalStateException(
                rejectionReason
                    ?: "The antenna pattern is not calculation-ready and was not assigned.",
            )

            else -> throw IllegalStateException("The transmit pattern assignment was not accepted.")
        }
    }

    fun delete(pattern: AntennaPatternRecord) = launchOperation("Deleting antenna pattern") {
        var status = AntennaPatternMutationStatus.NOT_FOUND
        var affectedSectors = 0
        repository.updateCatalog { catalog ->
            catalogUseCase.delete(
                catalog,
                DeleteAntennaPatternCommand(projectId, pattern),
            ).also { result ->
                status = result.status
                affectedSectors = result.affectedSectorCount
            }.catalog
        }
        when (status) {
            AntennaPatternMutationStatus.DELETED -> catalogChanged(
                "The antenna pattern and its project artifact references were removed.",
            )

            AntennaPatternMutationStatus.BLOCKED_REFERENCES -> throw IllegalStateException(
                "Unassign this pattern from $affectedSectors sector(s) before deleting it.",
            )

            AntennaPatternMutationStatus.STALE -> throw IllegalStateException(
                "The antenna pattern changed in storage. Review it before deleting.",
            )

            AntennaPatternMutationStatus.NOT_FOUND -> throw IllegalStateException(
                "The antenna pattern no longer exists.",
            )

            else -> throw IllegalStateException("The antenna pattern was not deleted.")
        }
    }

    fun dismissMessage() {
        mutableState.update { current -> current.copy(notice = null, error = null) }
    }

    private fun launchOperation(
        label: String,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            operationMutex.withLock {
                mutableState.update { current ->
                    current.copy(isBusy = true, operationLabel = label, notice = null, error = null)
                }
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableState.update { current ->
                        current.copy(error = error.safeUserMessage(), notice = null)
                    }
                } finally {
                    mutableState.update { current ->
                        current.copy(isBusy = false, operationLabel = null)
                    }
                }
            }
        }
    }

    private fun catalogChanged(message: String) {
        mutableState.update { current ->
            current.copy(
                notice = message,
                error = null,
                catalogMutationCount = current.catalogMutationCount + 1L,
            )
        }
    }

    private fun clearPendingImport() {
        pendingImport = null
        mutableState.update { current -> current.copy(pendingImport = null) }
    }

    private fun publishSingleImport(
        displayName: String,
        sourceBytes: ByteArray,
        parsed: ParsedAntennaPattern,
    ) {
        val token = UUID.randomUUID().toString()
        pendingImport = PendingAntennaImport(
            token = token,
            displayName = displayName,
            sourceBytes = sourceBytes,
            sourceSha256 = parsed.sourceSha256,
            sourceFormatLabel = parsed.detectedFormat.displayName,
            sourceMediaType = parsed.detectedFormat.sourceMediaType(),
            pattern = parsed.pattern,
            metadata = parsed.metadata,
            warnings = parsed.warnings,
            componentDisplayNames = listOf(displayName),
        )
        mutableState.update { current ->
            current.copy(
                pendingImport = AntennaPatternImportPreview(
                    token = token,
                    displayName = displayName,
                    detectedFormat = parsed.detectedFormat.displayName,
                    sourceSha256 = parsed.sourceSha256,
                    sourceByteCount = sourceBytes.size.toLong(),
                    horizontalSampleCount = parsed.pattern.horizontalCut.samples.size,
                    verticalSampleCount = parsed.pattern.verticalCut.samples.size,
                    nominalFrequencyHz = parsed.pattern.nominalFrequencyHz,
                    peakGainDbi = parsed.metadata.declaredGainDbi,
                    isCalculationReady = parsed.isCalculationReady,
                    warnings = parsed.warnings,
                    componentDisplayNames = listOf(displayName),
                ),
                notice = null,
                error = null,
            )
        }
    }

    private fun publishPairedImport(paired: PairedAntennaPatternImport) {
        val token = UUID.randomUUID().toString()
        pendingImport = PendingAntennaImport(
            token = token,
            displayName = paired.sourceBundleFileName,
            sourceBytes = paired.sourceBundle,
            sourceSha256 = paired.sourceBundleSha256,
            sourceFormatLabel = paired.sourceFormatLabel,
            sourceMediaType = "application/zip",
            pattern = paired.pattern,
            metadata = paired.metadata,
            warnings = paired.warnings,
            componentDisplayNames = paired.componentDisplayNames,
        )
        mutableState.update { current ->
            current.copy(
                pendingImport = AntennaPatternImportPreview(
                    token = token,
                    displayName = paired.pattern.name,
                    detectedFormat = paired.sourceFormatLabel,
                    sourceSha256 = paired.sourceBundleSha256,
                    sourceByteCount = paired.sourceBundle.size.toLong(),
                    horizontalSampleCount = paired.pattern.horizontalCut.samples.size,
                    verticalSampleCount = paired.pattern.verticalCut.samples.size,
                    nominalFrequencyHz = paired.metadata.nominalFrequencyHz,
                    peakGainDbi = paired.metadata.declaredGainDbi,
                    isCalculationReady = paired.pattern.isCalculationReady,
                    warnings = paired.warnings,
                    componentDisplayNames = paired.componentDisplayNames,
                ),
                notice = null,
                error = null,
            )
        }
    }

    private fun publishPrnConventionChoice(
        pending: PendingPrnConventionChoice,
        required: PrnValueConventionRequiredException,
    ) {
        pendingImport = null
        pendingPrnConventionChoice = pending
        mutableState.update { current ->
            current.copy(
                pendingImport = null,
                pendingPrnConventionChoice = AntennaPrnConventionChoicePreview(
                    token = pending.token,
                    sourceDisplayNames = pending.sourceDisplayNames,
                    ambiguousPlaneLabels = required.ambiguousPlanes
                        .sortedBy(PatternCutPlane::ordinal)
                        .map { plane -> plane.prnUiLabel() },
                ),
                notice = null,
                error = null,
            )
        }
    }

    private fun clearPendingPrnConventionChoice() {
        pendingPrnConventionChoice = null
        mutableState.update { current -> current.copy(pendingPrnConventionChoice = null) }
    }

    private fun clearPendingExport() {
        pendingExport = null
        mutableState.update { current -> current.copy(pendingExport = null) }
    }

    private suspend fun discardPendingExportForReplacement() {
        val previousToken = pendingExport?.token ?: mutableState.value.pendingExport?.token
        clearPendingExport()
        if (previousToken != null) {
            withContext(Dispatchers.IO) { preparedExportCache.remove(previousToken) }
        }
    }

    private fun publishPendingExport(prepared: PendingAntennaExport) {
        mutableState.update { current ->
            current.copy(
                pendingExport = AntennaPatternExportPreview(
                    token = prepared.token,
                    patternId = prepared.patternId,
                    format = prepared.format,
                    suggestedFileName = prepared.suggestedFileName,
                    mediaType = prepared.format.mediaType,
                    byteCount = prepared.payload.size,
                    sha256 = prepared.sha256,
                    warnings = prepared.warnings,
                ),
                notice = null,
                error = null,
            )
        }
    }

    private fun PreparedAntennaExport.toPendingAntennaExport(
        expectedFormat: AntennaPatternExportFormat,
    ): PendingAntennaExport {
        require(formatName == expectedFormat.name) {
            "The cached antenna export format does not match the requested format."
        }
        return PendingAntennaExport(
            token = token,
            patternId = patternId,
            format = expectedFormat,
            suggestedFileName = suggestedFileName,
            payload = payload,
            sha256 = sha256,
            warnings = warnings,
        )
    }

    private fun resolveDisplayName(uri: Uri): String {
        val candidate = applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return candidate
            ?.takeIf { name -> name.isNotBlank() && name.length <= 240 && name.none(Char::isISOControl) }
            ?: "antenna-pattern.dat"
    }

    private fun readBounded(
        input: InputStream,
        maximumBytes: Int = AntennaPatternCodecLimits.MAX_INPUT_BYTES,
        exceededMessage: String = "The antenna document exceeds the 16 MiB import limit.",
    ): ByteArray {
        require(maximumBytes in 0..AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
            "The antenna read limit is outside the supported bound."
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maximumBytes) {
                throw IOException(exceededMessage)
            }
            output.write(buffer, 0, read)
        }
        if (total == 0) throw IOException("The selected antenna document is empty.")
        return output.toByteArray()
    }

    private data class PendingAntennaImport(
        val token: String,
        val displayName: String,
        val sourceBytes: ByteArray,
        val sourceSha256: String,
        val sourceFormatLabel: String,
        val sourceMediaType: String,
        val pattern: CanonicalAntennaPattern,
        val metadata: AntennaPatternFileMetadata,
        val warnings: List<String>,
        val componentDisplayNames: List<String>,
    )

    private sealed interface PendingPrnConventionChoice {
        val token: String
        val sourceDisplayNames: List<String>

        data class Single(
            override val token: String,
            val displayName: String,
            val sourceBytes: ByteArray,
        ) : PendingPrnConventionChoice {
            override val sourceDisplayNames: List<String> = listOf(displayName)

            init {
                require(sourceBytes.isNotEmpty() &&
                    sourceBytes.size <= AntennaPatternCodecLimits.MAX_INPUT_BYTES
                ) {
                    "A pending PRN choice requires one already-bounded source payload."
                }
            }
        }

        data class Pair(
            override val token: String,
            val sources: List<AntennaPatternPairSource>,
        ) : PendingPrnConventionChoice {
            override val sourceDisplayNames: List<String> = sources.map { source ->
                source.displayName
            }

            init {
                require(sources.size == 2 &&
                    sources.sumOf { source -> source.payload.size.toLong() } in
                    1L..AntennaPatternPairCodec.MAX_PAIR_SOURCE_BYTES
                ) {
                    "A pending paired PRN choice requires two already-bounded source payloads."
                }
            }
        }
    }

    private data class PendingAntennaExport(
        val token: String,
        val patternId: String,
        val format: AntennaPatternExportFormat,
        val suggestedFileName: String,
        val payload: ByteArray,
        val sha256: String,
        val warnings: List<String>,
    )

    companion object {
        fun factory(
            context: Context,
            projectId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AntennaPatternLabViewModel::class.java))
                val applicationContext = context.applicationContext
                return AntennaPatternLabViewModel(
                    applicationContext = applicationContext,
                    projectId = projectId,
                    repository = FileProjectRepository(applicationContext),
                ) as T
            }
        }
    }
}

internal fun buildArrayConfiguration(
    request: AntennaArraySynthesisRequest,
    basePattern: CanonicalAntennaPattern,
    elementPatterns: Map<String, CanonicalAntennaPattern> = emptyMap(),
): AntennaArrayConfiguration {
    val frequencyHz = request.frequencyMHz * 1.0e6
    if (request.topology == AntennaArrayTopology.ARBITRARY) {
        return buildArbitraryArrayConfiguration(
            request = request,
            basePattern = basePattern,
            elementPatterns = elementPatterns,
            frequencyHz = frequencyHz,
        )
    }
    require(request.arbitraryElements.isEmpty()) {
        "Only the arbitrary topology accepts per-element geometry."
    }
    val direction = ApertureDirection.fromAngles(
        horizontalAngleDegrees = request.horizontalScanDegrees,
        elevationAngleDegrees = request.verticalScanDegrees,
    )
    val plannedElements = planArrayGeometry(request)
    val amplitudeWeights = plannedElements.map { planned ->
        taperAmplitude(planned.horizontalTaperCount, planned.horizontalTaperIndex, request.taper) *
            taperAmplitude(planned.verticalTaperCount, planned.verticalTaperIndex, request.taper)
    }
    val totalRawPower = amplitudeWeights.sumOf { amplitude -> amplitude * amplitude }
    require(totalRawPower.isFinite() && totalRawPower > 0.0) {
        "The selected array taper produced no positive finite excitation."
    }
    val elements = plannedElements.mapIndexed { elementIndex, planned ->
        val feedPhaseDegrees = -360.0 * (
            planned.xWavelengths * direction.x +
                planned.yWavelengths * direction.y +
                planned.zWavelengths * direction.z
            )
        val amplitude = amplitudeWeights[elementIndex]
        AntennaArrayElement(
            id = "element-${elementIndex + 1}",
            positionMeters = AperturePositionMeters.fromWavelengths(
                xWavelengths = planned.xWavelengths,
                yWavelengths = planned.yWavelengths,
                zWavelengths = planned.zWavelengths,
                frequencyHz = frequencyHz,
            ),
            pattern = basePattern,
            powerFraction = amplitude * amplitude / totalRawPower,
            feedPhaseDegrees = feedPhaseDegrees,
            orientation = planned.orientation,
        )
    }
    val deterministicId = sha256(
        listOf(
            request.name,
            request.basePatternId.orEmpty(),
            request.frequencyMHz,
            request.topology.name,
            request.columns,
            request.rows,
            request.horizontalSpacingWavelengths,
            request.verticalSpacingWavelengths,
            request.horizontalScanDegrees,
            request.verticalScanDegrees,
            request.taper.name,
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    ).take(24)
    val scanFromBoresight = Math.toDegrees(acos(direction.z.coerceIn(-1.0, 1.0)))
    return AntennaArrayConfiguration(
        id = "array-$deterministicId",
        name = request.name,
        frequencyHz = frequencyHz,
        elements = elements,
        declaredScanAngleDegrees = scanFromBoresight,
    )
}

private fun buildArbitraryArrayConfiguration(
    request: AntennaArraySynthesisRequest,
    basePattern: CanonicalAntennaPattern,
    elementPatterns: Map<String, CanonicalAntennaPattern>,
    frequencyHz: Double,
): AntennaArrayConfiguration {
    require(request.arbitraryElements.size in 1..512) {
        "An arbitrary array must contain between 1 and 512 elements."
    }
    val ids = request.arbitraryElements.map { element -> element.id }
    require(ids.distinct().size == ids.size) { "Arbitrary array element IDs must be unique." }
    request.arbitraryElements.forEach { element ->
        require(
            element.id == element.id.trim() &&
                element.id.length in 1..80 &&
                element.id.none(Char::isISOControl),
        ) { "An arbitrary element ID must contain 1–80 safe characters." }
        require(
            listOf(element.xWavelengths, element.yWavelengths, element.zWavelengths).all { value ->
                value.isFinite() && kotlin.math.abs(value) <= 10_000.0
            },
        ) { "Arbitrary element coordinates must be finite and within ±10000 wavelengths." }
        require(
            element.relativePower.isFinite() && element.relativePower in 0.0..1.0e12,
        ) { "An arbitrary element relative power must be finite and in [0, 1e12]." }
        require(element.feedPhaseDegrees.isFinite() && kotlin.math.abs(element.feedPhaseDegrees) <= 1.0e6) {
            "An arbitrary element feed phase must be finite and within ±1000000 degrees."
        }
        require(
            element.feedDelayNanoseconds.isFinite() &&
                kotlin.math.abs(element.feedDelayNanoseconds) <= 1.0e6,
        ) { "An arbitrary element feed delay must be finite and within ±1000000 ns." }
        require(element.horizontalOrientationDegrees in 0.0..<360.0) {
            "An arbitrary element azimuth orientation must be in [0, 360) degrees."
        }
        require(element.elevationOrientationDegrees in -90.0..90.0) {
            "An arbitrary element elevation orientation must be in [-90, 90] degrees."
        }
        require(element.rollDegrees in -180.0..180.0) {
            "An arbitrary element roll must be in [-180, 180] degrees."
        }
    }
    val totalRelativePower = request.arbitraryElements.sumOf { element ->
        if (element.active) element.relativePower else 0.0
    }
    require(totalRelativePower.isFinite() && totalRelativePower > 0.0) {
        "An arbitrary array requires at least one active element with positive relative power."
    }
    val elements = request.arbitraryElements.map { element ->
        val pattern = element.patternId?.let { patternId ->
            elementPatterns[patternId]
                ?: throw IllegalArgumentException("Element ${element.id} references an unavailable pattern.")
        } ?: basePattern
        val delayPhaseDegrees = -360.0 * frequencyHz * element.feedDelayNanoseconds * 1.0e-9
        AntennaArrayElement(
            id = element.id,
            positionMeters = AperturePositionMeters.fromWavelengths(
                xWavelengths = element.xWavelengths,
                yWavelengths = element.yWavelengths,
                zWavelengths = element.zWavelengths,
                frequencyHz = frequencyHz,
            ),
            pattern = pattern,
            powerFraction = if (element.active) element.relativePower / totalRelativePower else 0.0,
            feedPhaseDegrees = wrapPhaseDegrees(element.feedPhaseDegrees + delayPhaseDegrees),
            orientation = ElementOrientation(
                horizontalAngleDegrees = element.horizontalOrientationDegrees,
                elevationAngleDegrees = element.elevationOrientationDegrees,
                rollDegrees = element.rollDegrees,
            ),
            active = element.active,
        )
    }
    val elementSignature = request.arbitraryElements.joinToString("\u0001") { element ->
        listOf(
            element.id,
            element.patternId.orEmpty(),
            element.xWavelengths,
            element.yWavelengths,
            element.zWavelengths,
            element.relativePower,
            element.feedPhaseDegrees,
            element.feedDelayNanoseconds,
            element.horizontalOrientationDegrees,
            element.elevationOrientationDegrees,
            element.rollDegrees,
            element.active,
        ).joinToString("\u0000")
    }
    val deterministicId = sha256(
        listOf(
            request.name,
            request.basePatternId.orEmpty(),
            request.frequencyMHz,
            request.topology.name,
            elementSignature,
        ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
    ).take(24)
    return AntennaArrayConfiguration(
        id = "array-$deterministicId",
        name = request.name,
        frequencyHz = frequencyHz,
        elements = elements,
        declaredScanAngleDegrees = null,
    )
}

private fun wrapPhaseDegrees(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

private data class PlannedArrayElement(
    val xWavelengths: Double,
    val yWavelengths: Double,
    val zWavelengths: Double = 0.0,
    val horizontalTaperCount: Int = 1,
    val horizontalTaperIndex: Int = 0,
    val verticalTaperCount: Int = 1,
    val verticalTaperIndex: Int = 0,
    val orientation: ElementOrientation = ElementOrientation(),
)

private fun planArrayGeometry(request: AntennaArraySynthesisRequest): List<PlannedArrayElement> =
    when (request.topology) {
        AntennaArrayTopology.SINGLE -> listOf(PlannedArrayElement(0.0, 0.0))

        AntennaArrayTopology.VERTICAL_STACK -> List(request.rows) { row ->
            PlannedArrayElement(
                xWavelengths = 0.0,
                yWavelengths =
                    (row - (request.rows - 1) / 2.0) * request.verticalSpacingWavelengths,
                verticalTaperCount = request.rows,
                verticalTaperIndex = row,
            )
        }

        AntennaArrayTopology.HORIZONTAL_LINEAR -> List(request.columns) { column ->
            PlannedArrayElement(
                xWavelengths =
                    (column - (request.columns - 1) / 2.0) * request.horizontalSpacingWavelengths,
                yWavelengths = 0.0,
                horizontalTaperCount = request.columns,
                horizontalTaperIndex = column,
            )
        }

        AntennaArrayTopology.PLANAR -> buildList {
            repeat(request.rows) { row ->
                repeat(request.columns) { column ->
                    add(
                        PlannedArrayElement(
                            xWavelengths =
                                (column - (request.columns - 1) / 2.0) *
                                    request.horizontalSpacingWavelengths,
                            yWavelengths =
                                (row - (request.rows - 1) / 2.0) *
                                    request.verticalSpacingWavelengths,
                            horizontalTaperCount = request.columns,
                            horizontalTaperIndex = column,
                            verticalTaperCount = request.rows,
                            verticalTaperIndex = row,
                        ),
                    )
                }
            }
        }

        AntennaArrayTopology.CIRCULAR -> List(request.columns) { element ->
            val angleRadians = 2.0 * PI * element / request.columns
            PlannedArrayElement(
                xWavelengths = request.horizontalSpacingWavelengths * cos(angleRadians),
                yWavelengths = request.horizontalSpacingWavelengths * sin(angleRadians),
            )
        }

        AntennaArrayTopology.MULTIPANEL -> buildList {
            repeat(request.columns) { panel ->
                val horizontalOrientation = 360.0 * panel / request.columns
                val orientationRadians = Math.toRadians(horizontalOrientation)
                repeat(request.rows) { row ->
                    add(
                        PlannedArrayElement(
                            xWavelengths =
                                request.horizontalSpacingWavelengths * sin(orientationRadians),
                            yWavelengths =
                                (row - (request.rows - 1) / 2.0) *
                                    request.verticalSpacingWavelengths,
                            zWavelengths =
                                request.horizontalSpacingWavelengths * cos(orientationRadians),
                            verticalTaperCount = request.rows,
                            verticalTaperIndex = row,
                            orientation = ElementOrientation(
                                horizontalAngleDegrees = horizontalOrientation,
                            ),
                        ),
                    )
                }
            }
        }

        AntennaArrayTopology.ARBITRARY -> error(
            "Arbitrary elements are built directly and do not use structured geometry planning.",
        )
    }

private fun taperAmplitude(
    count: Int,
    index: Int,
    taper: AntennaArrayTaper,
): Double = when (taper) {
    AntennaArrayTaper.UNIFORM -> 1.0
    AntennaArrayTaper.COSINE -> if (count == 1) 1.0 else sin(PI * (index + 0.5) / count)
    AntennaArrayTaper.BINOMIAL -> binomialCoefficient(count - 1, index)
}

private fun binomialCoefficient(n: Int, k: Int): Double {
    if (k == 0 || k == n) return 1.0
    val order = minOf(k, n - k)
    var result = 1.0
    for (index in 1..order) {
        result *= (n - order + index).toDouble() / index.toDouble()
    }
    return result
}

private fun AntennaPatternExportFormat.toCodecFormat(): AntennaPatternFileFormat = when (this) {
    AntennaPatternExportFormat.ATX_JSON -> AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1
    AntennaPatternExportFormat.ATX_DESKTOP_JSON -> AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1
    AntennaPatternExportFormat.PRN -> AntennaPatternFileFormat.PRN
    AntennaPatternExportFormat.PAT -> AntennaPatternFileFormat.PROGIRA_EDX_PAT
    AntennaPatternExportFormat.HRP -> AntennaPatternFileFormat.ADT_HRP
    AntennaPatternExportFormat.VRP -> AntennaPatternFileFormat.ADT_VRP
    AntennaPatternExportFormat.VSOFT_HRP -> AntennaPatternFileFormat.VSOFT_HRP
    AntennaPatternExportFormat.VSOFT_VRP -> AntennaPatternFileFormat.VSOFT_VRP
}

private fun PatternCutPlane.prnUiLabel(): String = when (this) {
    PatternCutPlane.HORIZONTAL -> "Horizontal (HRP)"
    PatternCutPlane.VERTICAL -> "Vertical (VRP)"
}

private fun AntennaPatternFileFormat.sourceMediaType(): String = when (this) {
    AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1 -> ATX_ANTENNA_MEDIA_TYPE
    AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1 -> "application/json"
    else -> "text/plain"
}

private fun String.safeArtifactFileName(): String = trim()
    .filterNot(Char::isISOControl)
    .replace('/', '_')
    .replace('\\', '_')
    .take(200)
    .ifBlank { "antenna-pattern.dat" }

private fun String.safeFileStem(): String = substringBeforeLast('.', missingDelimiterValue = this)
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9._-]+"), "-")
    .trim('-', '.')
    .ifBlank { "antenna-pattern" }
    .take(80)

internal suspend fun requireAvailableAntennaPatternArtifacts(
    project: PlannerProject,
    patternId: String,
    repository: ProjectArtifactRepository,
) {
    val pattern = project.antennaPatterns.singleOrNull { candidate -> candidate.id == patternId }
        ?: throw IllegalStateException("The duplicate antenna pattern is unavailable in the committed project.")
    check(pattern.hasVerifiedNormalizedContentIdentity()) {
        "The duplicate antenna pattern has an invalid normalized content identity."
    }
    val canonicalArtifact = project.artifacts.singleOrNull { artifact ->
        artifact.id == pattern.dataArtifactId &&
            artifact.role == ProjectArtifactRole.ANTENNA_PATTERN
    } ?: throw IllegalStateException(
        "The duplicate antenna pattern does not reference exactly one canonical artifact.",
    )
    val referencedArtifacts = buildList {
        add("canonical" to canonicalArtifact)
        pattern.sourceArtifactId?.let { sourceArtifactId ->
            val sourceArtifact = project.artifacts.singleOrNull { artifact ->
                artifact.id == sourceArtifactId &&
                    artifact.role == ProjectArtifactRole.IMPORT_SOURCE
            } ?: throw IllegalStateException(
                "The duplicate antenna pattern does not reference exactly one import-source artifact.",
            )
            add("import-source" to sourceArtifact)
        }
    }
    referencedArtifacts.forEach { (label, artifact) ->
        when (repository.artifactAvailability(artifact)) {
            ArtifactAvailability.AVAILABLE -> Unit
            ArtifactAvailability.MISSING -> throw IllegalStateException(
                "The duplicate antenna $label artifact is missing; the duplicate was not accepted.",
            )
            ArtifactAvailability.CORRUPT -> throw IllegalStateException(
                "The duplicate antenna $label artifact is corrupt; the duplicate was not accepted.",
            )
        }
    }
}

private fun Throwable.safeUserMessage(): String = when (this) {
    is IllegalArgumentException,
    is IllegalStateException,
    is IOException,
    -> message?.take(500) ?: "The antenna operation failed validation."

    else -> "The antenna operation could not be completed."
}

private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(payload)
    .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

private fun formatDb(value: Double): String = String.format(Locale.US, "%.2f", value)

private const val ATX_ANTENNA_MEDIA_TYPE = "application/vnd.atx-plan.antenna+json;version=2"
private const val PREPARED_EXPORT_CACHE_DIRECTORY = "prepared-antenna-exports-v1"
