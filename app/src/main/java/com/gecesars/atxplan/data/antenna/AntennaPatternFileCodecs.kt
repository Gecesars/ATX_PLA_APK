package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.AntennaPatternLimits
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.ComplexField
import com.gecesars.atxplan.domain.antenna.PatternCoordinateFrame
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/** Hard limits applied before an untrusted antenna pattern is materialized as domain objects. */
object AntennaPatternCodecLimits {
    const val MAX_INPUT_BYTES: Int = 16 * 1024 * 1024
    const val MAX_INPUT_LINES: Int = 20_050
    const val MAX_LEGACY_LINE_CHARACTERS: Int = 4_096
}

enum class AntennaPatternFileFormat(val displayName: String) {
    PRN("PRN"),
    ADT_HRP("ADT HRP"),
    ADT_VRP("ADT VRP"),
    VSOFT_HRP("V-Soft HRP"),
    VSOFT_VRP("V-Soft VRP"),
    GENERIC_HRP_TABLE("Generic HRP table"),
    GENERIC_VRP_TABLE("Generic VRP table"),
    PROGIRA_EDX_PAT("Progira/EDX PAT"),
    /** Historical enum name retained while the format family reads schema v1 and writes v2. */
    ATX_ANTENNA_JSON_V1("ATX Antenna JSON v1/v2"),
    ATX_DESKTOP_JSON_V1("ATX Planner desktop JSON v1"),
}

enum class AntennaPatternValueConvention(val description: String) {
    POSITIVE_FIELD_ATTENUATION_DB_20_LOG10(
        "Positive field attenuation in dB, converted with E/Emax = 10^(-dB/20)",
    ),
    NORMALIZED_FIELD_AMPLITUDE(
        "Normalized linear field amplitude E/Emax",
    ),
    NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE(
        "Normalized linear field amplitude E/Emax with optional phase in degrees",
    ),
    RELATIVE_FIELD_DB_20_LOG10(
        "Relative field level in dB, converted with E/Emax = 10^((dB - peak dB)/20)",
    ),
}

/** Explicit interpretation for otherwise unmarked and ambiguous PRN values. */
enum class PrnValueConventionOverride {
    POSITIVE_FIELD_ATTENUATION_DB,
    NORMALIZED_LINEAR_FIELD,
}

data class AntennaPatternFileDetection(
    val format: AntennaPatternFileFormat,
    val valueConvention: AntennaPatternValueConvention,
)

data class AntennaPatternFileMetadata(
    val nominalFrequencyHz: Double? = null,
    val declaredGainDbi: Double? = null,
    val verticalCutAzimuthDegrees: Double? = null,
    val beamTiltDegrees: Double? = null,
) {
    init {
        require(
            nominalFrequencyHz == null ||
                nominalFrequencyHz.isFinite() &&
                nominalFrequencyHz in
                AntennaPatternLimits.MIN_FREQUENCY_HZ..AntennaPatternLimits.MAX_FREQUENCY_HZ,
        ) { "Antenna metadata frequency must be finite and within the supported Hz range." }
        require(declaredGainDbi == null || declaredGainDbi.isFinite()) {
            "Antenna metadata gain must be finite when available."
        }
        require(
            verticalCutAzimuthDegrees == null ||
                verticalCutAzimuthDegrees.isFinite() && verticalCutAzimuthDegrees in 0.0..360.0,
        ) { "Antenna metadata vertical-cut azimuth must be in [0, 360] degrees." }
        require(
            beamTiltDegrees == null ||
                beamTiltDegrees.isFinite() && beamTiltDegrees in -90.0..90.0,
        ) { "Antenna metadata beam tilt must be in [-90, 90] degrees." }
    }
}

/**
 * A decoded file may contain one independently useful cut or a complete two-cut pattern.
 * [sourceSha256] always describes the exact imported bytes, independently of embedded metadata.
 */
data class AntennaPatternImportResult(
    val detectedFormat: AntennaPatternFileFormat,
    val valueConvention: AntennaPatternValueConvention,
    val cuts: List<AntennaPatternCut>,
    val pattern: CanonicalAntennaPattern?,
    val metadata: AntennaPatternFileMetadata,
    val sourceSha256: String,
    val warnings: List<String>,
    val formatVersion: Int? = null,
) {
    init {
        require(cuts.isNotEmpty() && cuts.size <= 2) {
            "An antenna pattern import must contain one or two canonical cuts."
        }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) {
            "An imported antenna pattern SHA-256 must be lowercase hexadecimal."
        }
        require(formatVersion == null || formatVersion > 0) {
            "An imported antenna format version must be positive when available."
        }
    }
}

/** The payload plus the conventions a caller must present when saving or sharing it. */
data class AntennaPatternExportArtifact(
    val format: AntennaPatternFileFormat,
    val valueConvention: AntennaPatternValueConvention,
    val suggestedExtension: String,
    val mediaType: String,
    val payload: ByteArray,
    val provenance: PatternProvenance,
    val warnings: List<String> = emptyList(),
)

open class AntennaPatternCodecException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Stable typed signal that an unmarked PRN requires an explicit caller-selected convention. */
class PrnValueConventionRequiredException(
    ambiguousPlanes: Set<PatternCutPlane>,
) : AntennaPatternCodecException(
    "PRN ${ambiguousPlanes.sortedBy(PatternCutPlane::ordinal).joinToString { plane -> plane.name }} " +
        "values confined to [0, 1] are ambiguous between field amplitude and dB; " +
        "an explicit PRN value convention override is required.",
) {
    val ambiguousPlanes: Set<PatternCutPlane> = ambiguousPlanes.toSet()

    init {
        require(this.ambiguousPlanes.isNotEmpty()) {
            "A PRN convention-required signal must identify at least one ambiguous plane."
        }
    }
}

/**
 * Bounded, deterministic codecs for the CPU-only canonical antenna pattern model.
 *
 * These codecs deliberately do not read files or Android URIs. Storage Access Framework and UI
 * orchestration belong outside this pure Kotlin boundary.
 */
object AntennaPatternFileCodecs {
    fun detect(
        payload: ByteArray,
        sourceLabel: String,
        prnValueConventionOverride: PrnValueConventionOverride? = null,
    ): AntennaPatternFileDetection {
        val bounded = readBoundedPayload(payload, sourceLabel)
        if (bounded.looksLikeJson) {
            requireNoPrnOverrideForNonPrn(prnValueConventionOverride)
            val decoded = decodeJson(bounded, sourceLabel)
            return AntennaPatternFileDetection(
                format = decoded.detectedFormat,
                valueConvention = decoded.valueConvention,
            )
        }
        val detection = detectBounded(
            bounded = bounded,
            sourceLabel = sourceLabel,
            prnValueConventionOverride = prnValueConventionOverride,
        )
        if (detection.format != AntennaPatternFileFormat.PRN) {
            requireNoPrnOverrideForNonPrn(prnValueConventionOverride)
        }
        return detection
    }

    fun decode(
        payload: ByteArray,
        sourceLabel: String,
        prnValueConventionOverride: PrnValueConventionOverride? = null,
    ): AntennaPatternImportResult {
        val bounded = readBoundedPayload(payload, sourceLabel)
        if (bounded.looksLikeJson) {
            requireNoPrnOverrideForNonPrn(prnValueConventionOverride)
            return decodeJson(bounded, sourceLabel)
        }
        val detection = detectBounded(
            bounded = bounded,
            sourceLabel = sourceLabel,
            prnValueConventionOverride = prnValueConventionOverride,
        )
        if (detection.format != AntennaPatternFileFormat.PRN) {
            requireNoPrnOverrideForNonPrn(prnValueConventionOverride)
        }
        return when (detection.format) {
            AntennaPatternFileFormat.PRN -> decodePrn(
                bounded = bounded,
                sourceLabel = sourceLabel,
                prnValueConventionOverride = prnValueConventionOverride,
            )
            AntennaPatternFileFormat.ADT_HRP,
            AntennaPatternFileFormat.ADT_VRP,
            -> decodeAdt(bounded, sourceLabel, detection)

            AntennaPatternFileFormat.VSOFT_HRP,
            AntennaPatternFileFormat.VSOFT_VRP,
            -> decodeVSoft(bounded, sourceLabel, detection)

            AntennaPatternFileFormat.GENERIC_HRP_TABLE,
            AntennaPatternFileFormat.GENERIC_VRP_TABLE,
            -> decodeGenericTable(bounded, sourceLabel, detection)

            AntennaPatternFileFormat.PROGIRA_EDX_PAT -> decodeProgiraEdxPat(
                bounded,
                sourceLabel,
            )

            AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1 -> decodeCanonicalJson(
                bounded,
                sourceLabel,
            )

            AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1 -> decodeDesktopJsonV1(
                bounded,
                sourceLabel,
            )
        }
    }

    /** Exports the two cuts as explicit positive field attenuation in dB with optional phase. */
    fun encodePrn(
        pattern: CanonicalAntennaPattern,
        declaredGainDbi: Double? = null,
    ): AntennaPatternExportArtifact {
        requireCompletePatternForExport(pattern)
        val frequencyHz = pattern.nominalFrequencyHz ?: codecFailure(
            "PRN export requires a nominal frequency; no frequency will be invented.",
        )
        validateFrequency(frequencyHz, "PRN nominal frequency")
        declaredGainDbi?.let { gain -> requireFinite(gain, "PRN declared gain") }
        val warnings = mutableListOf<String>()
        if (
            pattern.horizontalCut.samples.any { sample -> sample.phaseDegrees == null } ||
            pattern.verticalCut.samples.any { sample -> sample.phaseDegrees == null }
        ) {
            warnings +=
                "PRN phase is optional in the canonical model; samples without phase were " +
                    "exported as 0 degrees."
        }

        var usedAttenuationFloor = false
        fun attenuation(amplitude: Double): Double {
            if (amplitude <= PRN_FIELD_FLOOR) {
                usedAttenuationFloor = true
                return PRN_ATTENUATION_FLOOR_DB
            }
            return (-20.0 * log10(amplitude.coerceAtMost(1.0))).coerceAtLeast(0.0)
        }

        val lines = mutableListOf(
            "NAME ${headerValue(pattern.name)}",
            "MAKE ATX Plan",
            "FREQUENCY ${(frequencyHz / 1.0e6).fixed(6)} MHz",
        )
        declaredGainDbi?.let { gain -> lines += "GAIN ${gain.fixed(6)} dBi" }
        lines += listOf(
            "VALUE_CONVENTION POSITIVE_FIELD_ATTENUATION_DB_20_LOG10",
            "ANGLE_UNIT DEGREE",
            "ATTENUATION_UNIT DB",
            "PHASE_UNIT DEGREE",
            "SOURCE_LABEL ${headerValue(pattern.provenance.sourceLabel)}",
            "SOURCE_FORMAT ${headerValue(pattern.provenance.sourceFormat ?: "CANONICAL")}",
            "SOURCE_SHA256 ${pattern.provenance.sourceSha256 ?: "NOT_AVAILABLE"}",
            "COORDINATE_FRAME ${pattern.provenance.coordinateFrame.name}",
            "VERTICAL_BACK_HEMISPHERE NO_DATA_EXPORTED_AS_${PRN_ATTENUATION_FLOOR_DB.toInt()}_DB",
            "HORIZONTAL 360",
        )
        for (angle in 0 until 360) {
            val field = pattern.horizontalCut.complexFieldAt(angle.toDouble())
            val amplitude = field.magnitude.coerceIn(0.0, 1.0)
            lines +=
                "$angle\t${attenuation(amplitude).fixed(6)}\t${field.phaseDegrees.fixed(9)}"
        }
        lines += "VERTICAL 360"
        for (angle in 0 until 360) {
            val canonicalElevation = when (angle) {
                in 0..90 -> angle.toDouble()
                in 270..359 -> angle.toDouble() - 360.0
                else -> null
            }
            val field = canonicalElevation?.let(pattern.verticalCut::complexFieldAt)
            val attenuationDb = if (field == null) {
                PRN_ATTENUATION_FLOOR_DB
            } else {
                attenuation(field.magnitude.coerceIn(0.0, 1.0))
            }
            lines += "$angle\t${attenuationDb.fixed(6)}\t${(field?.phaseDegrees ?: 0.0).fixed(9)}"
        }
        warnings +=
            "Canonical VRP has no back-hemisphere samples; PRN elevations 91 through 269 are " +
            "exported at ${PRN_ATTENUATION_FLOOR_DB.toInt()} dB and are NoData, not predictions."
        if (usedAttenuationFloor) {
            warnings +=
                "Zero or sub-floor field magnitudes were exported at " +
                "${PRN_ATTENUATION_FLOOR_DB.toInt()} dB attenuation."
        }
        return textArtifact(
            format = AntennaPatternFileFormat.PRN,
            convention = AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            extension = ".prn",
            text = lines.joinToString(separator = "\n", postfix = "\n"),
            provenance = pattern.provenance,
            warnings = warnings,
        )
    }

    /** Exports one native ADT voltage cut. Frequency is required because the format requires it. */
    fun encodeAdt(
        cut: AntennaPatternCut,
        nominalFrequencyHz: Double,
        title: String = "ATX Plan antenna pattern",
    ): AntennaPatternExportArtifact {
        requireAvailableCutForExport(cut)
        validateFrequency(nominalFrequencyHz, "ADT nominal frequency")
        val safeTitle = headerValue(title)
        val warnings = mutableListOf<String>()
        if (cut.samples.any { sample -> sample.phaseDegrees == null }) {
            warnings +=
                "ADT requires a phase column; samples without phase were exported as 0 degrees."
        }
        val kind = if (cut.plane == PatternCutPlane.HORIZONTAL) "HRP" else "VRP"
        val format = if (cut.plane == PatternCutPlane.HORIZONTAL) {
            AntennaPatternFileFormat.ADT_HRP
        } else {
            AntennaPatternFileFormat.ADT_VRP
        }
        val angleDigits = if (cut.plane == PatternCutPlane.HORIZONTAL) 6 else 6
        val lines = mutableListOf(
            "1/02/97 0:00 ; title : $safeTitle ; engineer : ATX Plan ; pattern_type : $kind ; " +
                "value : E/Emax ; phase_unit : degree ; source_sha256 : " +
                (cut.provenance.sourceSha256 ?: "NOT_AVAILABLE"),
            (nominalFrequencyHz / 1.0e6).fixed(6),
            "1",
            "0   0   0   1   0",
            "voltage",
        )
        cut.samples.forEach { sample ->
            lines += buildString {
                append(sample.angleDegrees.fixed(angleDigits))
                append("    ")
                append(sample.normalizedFieldAmplitude.fixed(12))
                append("    ")
                append((sample.phaseDegrees ?: 0.0).fixed(9))
            }
        }
        return textArtifact(
            format = format,
            convention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE,
            extension = if (cut.plane == PatternCutPlane.HORIZONTAL) ".hrp" else ".vrp",
            text = lines.joinToString(separator = "\n", postfix = "\n"),
            provenance = cut.provenance,
            warnings = warnings,
        )
    }

    /** Exports one cut using the desktop-compatible V-Soft VEP dialect. */
    fun encodeVSoft(
        cut: AntennaPatternCut,
        preservedBeamTiltDegrees: Double? = null,
    ): AntennaPatternExportArtifact {
        requireAvailableCutForExport(cut)
        preservedBeamTiltDegrees?.let { tilt ->
            requireFinite(tilt, "V-Soft preserved beam tilt")
            codecRequire(tilt in -90.0..90.0) {
                "V-Soft preserved beam tilt must be in [-90, 90] degrees."
            }
        }
        val warnings = mutableListOf<String>()
        if (cut.samples.any { sample -> sample.phaseDegrees != null }) {
            warnings += "V-Soft cannot represent phase; exported values contain field magnitude only."
        }
        val format: AntennaPatternFileFormat
        val lines: MutableList<String>
        if (cut.plane == PatternCutPlane.HORIZONTAL) {
            format = AntennaPatternFileFormat.VSOFT_HRP
            val targetAngles = (0 until 360).map(Int::toDouble)
            val magnitudes = normalizeEvaluatedMagnitudes(cut, targetAngles, "V-Soft HRP")
            lines = mutableListOf("360,0,1")
            lines += magnitudes.map { magnitude -> magnitude.fixed(4) }
        } else {
            format = AntennaPatternFileFormat.VSOFT_VRP
            val targetAngles = (0..1800).map { index -> -90.0 + index * 0.1 }
            val magnitudes = normalizeEvaluatedMagnitudes(cut, targetAngles, "V-Soft VRP")
            val maximum = magnitudes.max()
            val maximumAngles = targetAngles.indices
                .filter { index -> abs(magnitudes[index] - maximum) <= VSOFT_MAXIMUM_TOLERANCE }
                .map { index -> targetAngles[index] }
            val derivedBeamTilt = normalizeZero(
                round(maximumAngles.average() * 100.0) / 100.0,
            )
            val beamTilt = preservedBeamTiltDegrees?.let(::normalizeZero) ?: derivedBeamTilt
            if (preservedBeamTiltDegrees == null) {
                warnings +=
                    "No preserved V-Soft beam tilt was available; ${beamTilt.compact()} degrees " +
                        "was derived from the exported VRP maximum."
            } else {
                warnings +=
                    "The preserved V-Soft beam tilt ${beamTilt.compact()} degrees was written " +
                        "without recomputing it from the normalized VRP maximum."
            }
            lines = mutableListOf(
                VSOFT_VRP_HEADER,
                "Beam Tilt = ${beamTilt.toString()}",
            )
            targetAngles.indices.forEach { index ->
                val angle = targetAngles[index]
                val prefix = when {
                    angle <= -10.0 -> ""
                    angle < 0.0 -> " "
                    angle < 10.0 -> "  "
                    else -> " "
                }
                lines += "$prefix${angle.fixed(1)} ${magnitudes[index].fixed(4)}"
            }
        }
        return textArtifact(
            format = format,
            convention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            extension = ".vep",
            text = lines.joinToString(separator = "\n", postfix = "\n"),
            provenance = cut.provenance,
            warnings = warnings,
        )
    }

    /**
     * Exports the bounded separable pattern as Progira/EDX PAT.
     *
     * The PAT header requires gain, which the normalized canonical model does not contain. The
     * caller must therefore provide a real declared value rather than receiving an invented one.
     */
    fun encodeProgiraEdxPat(
        pattern: CanonicalAntennaPattern,
        declaredGainDbi: Double,
        verticalCutAzimuthDegrees: Double = 0.0,
    ): AntennaPatternExportArtifact {
        requireCompletePatternForExport(pattern)
        requireFinite(declaredGainDbi, "PAT declared gain")
        requireFinite(verticalCutAzimuthDegrees, "PAT vertical-cut azimuth")
        codecRequire(verticalCutAzimuthDegrees in 0.0..360.0) {
            "PAT vertical-cut azimuth must be in [0, 360] degrees."
        }
        val warnings = mutableListOf(
            "PAT contains one HRP and one VRP cut; it is not a full 3D radiation pattern.",
        )
        if (pattern.hasPhaseSamples()) {
            warnings += "Progira/EDX PAT cannot represent phase; exported values contain magnitude only."
        }
        val lines = mutableListOf("'By ADT', ${declaredGainDbi.fixed(3)}, 1")
        for (angle in 0 until 360) {
            val amplitude = pattern.horizontalCut
                .complexFieldAt(angle.toDouble())
                .magnitude
                .coerceIn(0.0, 1.0)
            lines += "$angle, ${amplitude.fixed(9)}"
        }
        lines += "999"
        lines += "1, 1801"
        lines += "${normalizeHorizontalAngle(verticalCutAzimuthDegrees).fixed(6)},"
        for (index in 0..1800) {
            val canonicalElevation = -90.0 + index * 0.1
            val sourceElevation = -canonicalElevation
            val amplitude = pattern.verticalCut
                .complexFieldAt(canonicalElevation)
                .magnitude
                .coerceIn(0.0, 1.0)
            lines += "${sourceElevation.fixed(1)}, ${amplitude.fixed(9)}"
        }
        return textArtifact(
            format = AntennaPatternFileFormat.PROGIRA_EDX_PAT,
            convention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            extension = ".pat",
            text = lines.joinToString(separator = "\n", postfix = "\n"),
            provenance = pattern.provenance,
            warnings = warnings,
        )
    }

    /**
     * Canonical ATX Antenna JSON v2 export.
     *
     * The overload without metadata remains source-compatible and records explicit `NoData` for
     * optional source-format fields. Callers that are re-encoding an import should pass the
     * decoded [AntennaPatternFileMetadata] so peak gain, vertical-cut azimuth, and beam tilt are
     * retained without inventing values.
     */
    fun encodeCanonicalJson(pattern: CanonicalAntennaPattern): AntennaPatternExportArtifact =
        encodeCanonicalJson(
            pattern = pattern,
            metadata = AntennaPatternFileMetadata(
                nominalFrequencyHz = pattern.nominalFrequencyHz,
            ),
        )

    fun encodeCanonicalJson(
        pattern: CanonicalAntennaPattern,
        metadata: AntennaPatternFileMetadata,
    ): AntennaPatternExportArtifact {
        requireCompletePatternForExport(pattern)
        codecRequire(metadata.nominalFrequencyHz == pattern.nominalFrequencyHz) {
            "ATX Antenna JSON metadata frequency must exactly match the canonical pattern " +
                "frequency, including explicit NoData."
        }
        val payload = try {
            canonicalJson.encodeToString(
                CanonicalAntennaJson.serializer(),
                CanonicalAntennaJson.fromDomain(pattern, metadata),
            ).toByteArray(Charsets.UTF_8)
        } catch (error: SerializationException) {
            throw AntennaPatternCodecException("Could not encode ATX Antenna JSON v2.", error)
        }
        ensureExportBound(payload)
        return AntennaPatternExportArtifact(
            format = AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
            valueConvention =
                AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE,
            suggestedExtension = ".atx-antenna.json",
            mediaType = "application/vnd.atx-plan.antenna+json;version=2",
            payload = payload,
            provenance = pattern.provenance,
        )
    }

    /**
     * Deterministic interchange with the ATX Planner desktop `atx-antenna-pattern` schema v1.
     *
     * The desktop contract stores positive field attenuation rather than canonical E/Emax.
     * Both real cuts, nominal frequency, and declared gain are mandatory; missing phase is the
     * only value synthesized by this exporter and is disclosed as an export warning.
     */
    fun encodeDesktopJsonV1(
        pattern: CanonicalAntennaPattern,
        declaredGainDbi: Double,
    ): AntennaPatternExportArtifact {
        requireCompletePatternForExport(pattern)
        val nominalFrequencyHz = pattern.nominalFrequencyHz ?: codecFailure(
            "ATX Planner desktop JSON v1 export requires a nominal frequency; " +
                "no frequency will be invented.",
        )
        validateFrequency(nominalFrequencyHz, "ATX Planner desktop JSON v1 nominal frequency")
        requireFinite(declaredGainDbi, "ATX Planner desktop JSON v1 declared gain")
        codecRequire(declaredGainDbi in DESKTOP_JSON_MIN_GAIN_DBI..DESKTOP_JSON_MAX_GAIN_DBI) {
            "ATX Planner desktop JSON v1 declared gain must be in " +
                "[$DESKTOP_JSON_MIN_GAIN_DBI, $DESKTOP_JSON_MAX_GAIN_DBI] dBi."
        }
        validateText(pattern.name, "ATX Planner desktop JSON v1 name")
        codecRequire(pattern.name.length <= DESKTOP_JSON_MAX_NAME_CHARACTERS) {
            "ATX Planner desktop JSON v1 name cannot exceed " +
                "$DESKTOP_JSON_MAX_NAME_CHARACTERS characters."
        }

        val warnings = mutableListOf<String>()
        val hasMissingPhase = pattern.horizontalCut.samples.any { sample ->
            sample.phaseDegrees == null
        } || pattern.verticalCut.samples.any { sample -> sample.phaseDegrees == null }
        if (hasMissingPhase) {
            warnings +=
                "ATX Planner desktop JSON v1 requires phase_deg; canonical samples without " +
                    "phase were exported as 0 degrees."
        }
        warnings +=
            "The Android canonical model does not retain cut polarization; desktop JSON " +
                "polarization was exported as 'unknown'."
        warnings +=
            "Desktop HRP labels angle 0 as geographic north, while Android project use " +
                "interprets stored angle 0 as sector boresight; numeric samples were exported " +
                "without rotation and are not a verified geodetic transform."

        var usedAttenuationFloor = false
        fun desktopSample(sample: PatternSample): DesktopJsonSample {
            val attenuationDb = if (sample.normalizedFieldAmplitude <= DESKTOP_JSON_FIELD_FLOOR) {
                usedAttenuationFloor = true
                DESKTOP_JSON_MAX_ATTENUATION_DB
            } else {
                (-20.0 * log10(sample.normalizedFieldAmplitude.coerceAtMost(1.0)))
                    .coerceIn(0.0, DESKTOP_JSON_MAX_ATTENUATION_DB)
            }
            return DesktopJsonSample(
                angleDegrees = normalizeZero(sample.angleDegrees),
                attenuationDb = normalizeZero(attenuationDb),
                phaseDegrees = normalizeZero(sample.phaseDegrees ?: 0.0),
            )
        }

        val sourceFormat = pattern.provenance.sourceFormat
        val sourceSha256 = pattern.provenance.sourceSha256
        val desktopSource = if (sourceFormat != null && sourceSha256 != null) {
            DesktopJsonSource(format = sourceFormat, sha256 = sourceSha256)
        } else {
            val canonicalSource = encodeCanonicalJson(
                pattern = pattern,
                metadata = AntennaPatternFileMetadata(
                    nominalFrequencyHz = nominalFrequencyHz,
                    declaredGainDbi = declaredGainDbi,
                ),
            )
            warnings +=
                "Source provenance was incomplete; the desktop source object identifies the " +
                    "deterministic ATX Antenna JSON v2 representation used for this export."
            DesktopJsonSource(
                format = "ATX Antenna JSON v2",
                sha256 = sha256Hex(canonicalSource.payload),
            )
        }
        validateDesktopSource(desktopSource)

        val dto = DesktopAntennaJsonV1(
            format = DESKTOP_JSON_FORMAT,
            version = DESKTOP_JSON_VERSION,
            name = pattern.name,
            nominalFrequencyHz = nominalFrequencyHz,
            gainDbi = declaredGainDbi,
            source = desktopSource,
            cuts = listOf(
                DesktopJsonCut(
                    plane = DESKTOP_JSON_HORIZONTAL_PLANE,
                    angleConvention = DESKTOP_JSON_HORIZONTAL_ANGLE_CONVENTION,
                    polarization = DESKTOP_JSON_UNKNOWN_POLARIZATION,
                    samples = pattern.horizontalCut.samples.map(::desktopSample),
                ),
                DesktopJsonCut(
                    plane = DESKTOP_JSON_VERTICAL_PLANE,
                    angleConvention = DESKTOP_JSON_VERTICAL_ANGLE_CONVENTION,
                    polarization = DESKTOP_JSON_UNKNOWN_POLARIZATION,
                    samples = pattern.verticalCut.samples.map(::desktopSample),
                ),
            ),
        )
        if (usedAttenuationFloor) {
            warnings +=
                "Canonical zero or sub-floor field values were exported at the desktop " +
                    "$DESKTOP_JSON_MAX_ATTENUATION_DB dB attenuation ceiling."
        }
        val payload = try {
            canonicalJson.encodeToString(DesktopAntennaJsonV1.serializer(), dto)
                .plus('\n')
                .toByteArray(Charsets.UTF_8)
        } catch (error: SerializationException) {
            throw AntennaPatternCodecException(
                "Could not encode ATX Planner desktop JSON v1.",
                error,
            )
        }
        ensureExportBound(payload)
        return AntennaPatternExportArtifact(
            format = AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1,
            valueConvention =
                AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            suggestedExtension = ".atxpat.json",
            mediaType = "application/json",
            payload = payload,
            provenance = pattern.provenance,
            warnings = warnings,
        )
    }

    private fun decodePrn(
        bounded: BoundedPayload,
        sourceLabel: String,
        prnValueConventionOverride: PrnValueConventionOverride?,
    ): AntennaPatternImportResult {
        val sections = parsePrnSections(bounded.lines)
        val horizontalRows = sections.horizontalRows
        val verticalRows = sections.verticalRows

        val warnings = mutableListOf<String>()
        val valueInterpretation = resolvePrnValueInterpretation(
            lines = bounded.lines,
            horizontalRows = horizontalRows,
            verticalRows = verticalRows,
            prnValueConventionOverride = prnValueConventionOverride,
        )
        valueInterpretation.inferenceWarning?.let(warnings::add)
        val frequencyHz = parsePrnFrequency(bounded.lines, warnings)
        val declaredGainDbi = parsePrnDeclaredGainDbi(bounded.lines, warnings)
        val provenanceBase = importedProvenance(
            sourceLabel = sourceLabel,
            format = AntennaPatternFileFormat.PRN,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            limitations = listOf(
                valueInterpretation.limitation,
                "PRN metadata such as gain, beamwidth, and mechanical tilt is not applied to cuts.",
                "A canonical VRP retains only elevations from -90 through +90 degrees.",
            ),
        )
        val horizontalSamples = horizontalRows.takeIf { rows -> rows.isNotEmpty() }?.let { rows ->
            canonicalizeRows(
                rows = rows,
                plane = PatternCutPlane.HORIZONTAL,
                sectionLabel = "PRN HORIZONTAL",
                valueMode = valueInterpretation.valueMode,
                warnings = warnings,
                angleTransform = { angle ->
                    codecRequire(angle in 0.0..360.0) {
                        "PRN HORIZONTAL angles must be in [0, 360] degrees."
                    }
                    normalizeHorizontalAngle(angle)
                },
            )
        }

        val verticalUsesSignedAngles =
            verticalRows.isNotEmpty() && verticalRows.all { row -> row.angleDegrees in -90.0..90.0 }
        var omittedVerticalCount = 0
        val verticalSamples = verticalRows.takeIf { rows -> rows.isNotEmpty() }?.let { rows ->
            canonicalizeRows(
                rows = rows,
                plane = PatternCutPlane.VERTICAL,
                sectionLabel = "PRN VERTICAL",
                valueMode = valueInterpretation.valueMode,
                warnings = warnings,
                angleTransform = { angle ->
                    if (verticalUsesSignedAngles) {
                        codecRequire(angle in -90.0..90.0) {
                            "Signed PRN VERTICAL angles must be in [-90, 90] degrees."
                        }
                        normalizeZero(angle)
                    } else {
                        codecRequire(angle in 0.0..360.0) {
                            "Wrapped PRN VERTICAL angles must be in [0, 360] degrees."
                        }
                        when (angle) {
                            in 0.0..90.0 -> normalizeZero(angle)
                            in 270.0..360.0 -> normalizeZero(
                                if (angle == 360.0) 0.0 else angle - 360.0,
                            )
                            else -> {
                                omittedVerticalCount += 1
                                null
                            }
                        }
                    }
                },
            )
        }
        if (omittedVerticalCount > 0) {
            warnings +=
                "PRN VERTICAL omitted $omittedVerticalCount back-hemisphere samples because " +
                "canonical VRP supports only [-90, 90] degrees."
        }

        val provenance = provenanceBase.copy(warnings = warnings.toList())
        val horizontal = horizontalSamples?.let { samples ->
            AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = samples,
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            )
        }
        val vertical = verticalSamples?.let { samples ->
            AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = samples,
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            )
        }
        val cuts = listOfNotNull(horizontal, vertical)
        val name = parsePrnName(bounded.lines) ?: sourceName(sourceLabel)
        val pattern = if (horizontal != null && vertical != null) {
            CanonicalAntennaPattern(
                id = importedPatternId(bounded.sha256),
                name = name,
                horizontalCut = horizontal,
                verticalCut = vertical,
                provenance = provenance,
                nominalFrequencyHz = frequencyHz,
            )
        } else {
            null
        }
        return AntennaPatternImportResult(
            detectedFormat = AntennaPatternFileFormat.PRN,
            valueConvention = valueInterpretation.valueConvention,
            cuts = cuts,
            pattern = pattern,
            metadata = AntennaPatternFileMetadata(
                nominalFrequencyHz = frequencyHz,
                declaredGainDbi = declaredGainDbi,
            ),
            sourceSha256 = bounded.sha256,
            warnings = warnings.toList(),
        )
    }

    private fun decodeAdt(
        bounded: BoundedPayload,
        sourceLabel: String,
        detection: AntennaPatternFileDetection,
    ): AntennaPatternImportResult {
        val voltageIndices = bounded.lines.mapIndexedNotNull { index, line ->
            index.takeIf { line.trim().equals("voltage", ignoreCase = true) }
        }
        codecRequire(voltageIndices.size == 1) {
            "ADT HRP/VRP must contain exactly one voltage header."
        }
        val voltageIndex = voltageIndices.single()
        val prefix = bounded.lines.subList(0, voltageIndex).filter { line -> line.isNotBlank() }
        codecRequire(prefix.size >= 4) {
            "ADT HRP/VRP requires title, frequency, count, and placement header lines."
        }
        val frequencyMHz = parseSingleFiniteNumber(prefix[1], "ADT frequency")
        val frequencyHz = frequencyMHz * 1.0e6
        validateFrequency(frequencyHz, "ADT nominal frequency")
        parseSingleFiniteNumber(prefix[2], "ADT pattern count")
        val placement = parseNumericFields(prefix[3], "ADT placement header", sourceLineNumber = 4)
        codecRequire(placement.size == 5) {
            "ADT placement header must contain x offset, y offset, tilt, power, and phase offset."
        }

        val rows = parsePatternRows(
            lines = bounded.lines.subList(voltageIndex + 1, bounded.lines.size),
            firstSourceLineNumber = voltageIndex + 2,
            label = detection.format.displayName,
            allowedColumnCounts = 2..3,
        )
        val plane = if (detection.format == AntennaPatternFileFormat.ADT_HRP) {
            PatternCutPlane.HORIZONTAL
        } else {
            PatternCutPlane.VERTICAL
        }
        val warnings = mutableListOf<String>()
        var omittedVerticalSamples = 0
        if (
            abs(placement[0]) > NUMERIC_TOLERANCE ||
            abs(placement[1]) > NUMERIC_TOLERANCE ||
            abs(placement[2]) > NUMERIC_TOLERANCE ||
            abs(placement[3] - 1.0) > NUMERIC_TOLERANCE ||
            abs(placement[4]) > NUMERIC_TOLERANCE
        ) {
            warnings +=
                "ADT placement, power, tilt, and phase-offset header fields were not applied; " +
                "the imported cut is normalized as a unit pattern."
        }
        val samples = canonicalizeRows(
            rows = rows,
            plane = plane,
            sectionLabel = detection.format.displayName,
            valueMode = SourceValueMode.LINEAR_FIELD,
            warnings = warnings,
            angleTransform = { angle ->
                if (plane == PatternCutPlane.HORIZONTAL) {
                    codecRequire(angle in -360.0..360.0) {
                        "ADT HRP angles must be in [-360, 360] degrees."
                    }
                    normalizeHorizontalAngle(angle)
                } else {
                    codecRequire(angle in -180.0..180.0) {
                        "ADT VRP source angles must be in [-180, 180] degrees."
                    }
                    if (angle in -90.0..90.0) {
                        normalizeZero(angle)
                    } else {
                        omittedVerticalSamples += 1
                        null
                    }
                }
            },
        )
        if (omittedVerticalSamples > 0) {
            warnings +=
                "ADT VRP omitted $omittedVerticalSamples samples outside the canonical " +
                "[-90, 90] degree elevation interval."
        }
        val provenance = importedProvenance(
            sourceLabel = sourceLabel,
            format = detection.format,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            limitations = listOf(
                "ADT placement and array context are outside this single-cut import.",
                "Linear voltage is treated as normalized field amplitude E/Emax.",
            ),
        )
        val cut = AntennaPatternCut(
            plane = plane,
            samples = samples,
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        )
        return AntennaPatternImportResult(
            detectedFormat = detection.format,
            valueConvention = detection.valueConvention,
            cuts = listOf(cut),
            pattern = null,
            metadata = AntennaPatternFileMetadata(nominalFrequencyHz = frequencyHz),
            sourceSha256 = bounded.sha256,
            warnings = warnings.toList(),
        )
    }

    private fun decodeVSoft(
        bounded: BoundedPayload,
        sourceLabel: String,
        detection: AntennaPatternFileDetection,
    ): AntennaPatternImportResult {
        val plane = if (detection.format == AntennaPatternFileFormat.VSOFT_HRP) {
            PatternCutPlane.HORIZONTAL
        } else {
            PatternCutPlane.VERTICAL
        }
        val firstContentIndex = bounded.lines.indexOfFirst { line -> line.isNotBlank() }
        codecRequire(firstContentIndex >= 0) { "A V-Soft pattern cannot be empty." }
        val warnings = mutableListOf<String>()
        val beamTiltDegrees: Double?
        val rows: List<RawPatternRow>
        if (plane == PatternCutPlane.HORIZONTAL) {
            val values = bounded.lines.drop(firstContentIndex + 1).mapNotNull { line ->
                val fields = splitTableFields(line)
                if (fields.isEmpty()) return@mapNotNull null
                fields.first().toDoubleOrNull()?.takeIf { value ->
                    value.isFinite() && abs(value) <= MAX_GENERIC_NUMERIC_ABSOLUTE_VALUE
                }
            }
            codecRequire(values.size >= VSOFT_HRP_SAMPLE_COUNT) {
                "V-Soft HRP must contain 360 finite magnitude samples after its header."
            }
            if (values.size > VSOFT_HRP_SAMPLE_COUNT) {
                warnings +=
                    "V-Soft HRP ignored ${values.size - VSOFT_HRP_SAMPLE_COUNT} trailing " +
                    "numeric sample(s), matching the desktop 360-value contract."
            }
            rows = values.take(VSOFT_HRP_SAMPLE_COUNT).mapIndexed { index, value ->
                RawPatternRow(index.toDouble(), value, null)
            }
            beamTiltDegrees = null
        } else {
            val beamTiltLines = bounded.lines.mapNotNull { line ->
                VSOFT_BEAM_TILT_REGEX.matchEntire(line.trim())
            }
            codecRequire(beamTiltLines.size <= 1) {
                "V-Soft VRP cannot contain multiple Beam Tilt declarations."
            }
            beamTiltDegrees = beamTiltLines.singleOrNull()?.let { match ->
                parseFiniteNumber(match.groupValues[1], "V-Soft VRP beam tilt").also { tilt ->
                    codecRequire(tilt in -90.0..90.0) {
                        "V-Soft VRP beam tilt must be in [-90, 90] degrees."
                    }
                }
            }
            rows = bounded.lines.drop(firstContentIndex + 1).mapNotNull { line ->
                val numeric = parseOptionalTableNumericRow(line) ?: return@mapNotNull null
                if (numeric.size < 2) return@mapNotNull null
                RawPatternRow(
                    angleDegrees = numeric[0],
                    value = numeric[1],
                    phaseDegrees = null,
                )
            }
            codecRequire(rows.size >= 2) {
                "V-Soft VRP must contain at least two finite angle and magnitude rows."
            }
        }
        warnings += "V-Soft does not represent phase; imported phase remains NoData."
        val sparseSamples = canonicalizeRows(
            rows = rows,
            plane = plane,
            sectionLabel = detection.format.displayName,
            valueMode = SourceValueMode.LINEAR_FIELD,
            warnings = warnings,
            angleTransform = { angle ->
                if (plane == PatternCutPlane.HORIZONTAL) {
                    codecRequire(angle in 0.0..<360.0) {
                        "V-Soft HRP sample positions must resolve to [0, 360) degrees."
                    }
                    normalizeHorizontalAngle(angle)
                } else {
                    codecRequire(angle in -90.0..90.0) {
                        "V-Soft VRP angles must be in [-90, 90] degrees."
                    }
                    normalizeZero(angle)
                }
            },
        )
        val provenance = importedProvenance(
            sourceLabel = sourceLabel,
            format = detection.format,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            limitations = listOf(
                "V-Soft contains one normalized magnitude cut and no phase or nominal frequency.",
                "The missing companion cut remains explicit until the application facade adds its disclosed placeholder.",
            ),
        )
        val cut = AntennaPatternCut(
            plane = plane,
            samples = resampleCanonicalSamples(
                plane = plane,
                samples = sparseSamples,
                provenance = provenance,
                preservePhase = false,
            ),
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        )
        return AntennaPatternImportResult(
            detectedFormat = detection.format,
            valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            cuts = listOf(cut),
            pattern = null,
            metadata = AntennaPatternFileMetadata(beamTiltDegrees = beamTiltDegrees),
            sourceSha256 = bounded.sha256,
            warnings = warnings.toList(),
        )
    }

    private fun decodeGenericTable(
        bounded: BoundedPayload,
        sourceLabel: String,
        detection: AntennaPatternFileDetection,
    ): AntennaPatternImportResult {
        val table = analyzeGenericTable(bounded.lines, sourceLabel)
        val expectedPlane = if (detection.format == AntennaPatternFileFormat.GENERIC_HRP_TABLE) {
            PatternCutPlane.HORIZONTAL
        } else {
            PatternCutPlane.VERTICAL
        }
        codecRequire(table.plane == expectedPlane) {
            "The generic antenna table plane changed between detection and decoding."
        }
        val warnings = mutableListOf<String>()
        if (table.phaseColumn == null) {
            warnings += "The generic antenna table has no explicit phase column; phase remains NoData."
        }
        val rawAngles = table.rows.map { row -> row[table.angleColumn] }
        val verticalAngleMode = if (table.plane == PatternCutPlane.VERTICAL) {
            genericVerticalAngleMode(rawAngles)
        } else {
            GenericVerticalAngleMode.SIGNED
        }
        val rows = table.rows.map { row ->
            RawPatternRow(
                angleDegrees = row[table.angleColumn],
                value = row[table.magnitudeColumn],
                phaseDegrees = table.phaseColumn?.let { index -> row[index] },
            )
        }
        val sparseSamples = canonicalizeRows(
            rows = rows,
            plane = table.plane,
            sectionLabel = detection.format.displayName,
            valueMode = table.valueMode,
            warnings = warnings,
            angleTransform = { sourceAngle ->
                if (table.plane == PatternCutPlane.HORIZONTAL) {
                    codecRequire(sourceAngle in -360.0..360.0) {
                        "Generic HRP angles must be in [-360, 360] degrees."
                    }
                    normalizeHorizontalAngle(sourceAngle)
                } else {
                    val canonical = when (verticalAngleMode) {
                        GenericVerticalAngleMode.SIGNED -> sourceAngle
                        GenericVerticalAngleMode.ZERO_TO_180 -> sourceAngle - 90.0
                        GenericVerticalAngleMode.WRAPPED_360 -> {
                            val wrapped = ((sourceAngle + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                            wrapped.takeIf { angle -> angle in -90.0..90.0 }
                        }
                    }
                    canonical?.let { angle ->
                        codecRequire(angle in -90.0..90.0) {
                            "Generic VRP angles must resolve to [-90, 90] degrees."
                        }
                        normalizeZero(angle)
                    }
                }
            },
        )
        val nominalFrequencyHz = parseGenericFrequencyHz(table, warnings)
        val provenance = importedProvenance(
            sourceLabel = sourceLabel,
            format = detection.format,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            limitations = listOf(
                "Generic table columns were selected using bounded desktop-compatible header and numeric heuristics.",
                "Only one explicitly identified HRP or VRP plane is present in this source file.",
            ),
        )
        val cut = AntennaPatternCut(
            plane = table.plane,
            samples = resampleCanonicalSamples(
                plane = table.plane,
                samples = sparseSamples,
                provenance = provenance,
                preservePhase = table.phaseColumn != null,
            ),
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        )
        return AntennaPatternImportResult(
            detectedFormat = detection.format,
            valueConvention = table.valueConvention,
            cuts = listOf(cut),
            pattern = null,
            metadata = AntennaPatternFileMetadata(nominalFrequencyHz = nominalFrequencyHz),
            sourceSha256 = bounded.sha256,
            warnings = warnings.toList(),
        )
    }

    private fun decodeProgiraEdxPat(
        bounded: BoundedPayload,
        sourceLabel: String,
    ): AntennaPatternImportResult {
        val nonEmptyIndices = bounded.lines.indices.filter { index -> bounded.lines[index].isNotBlank() }
        codecRequire(nonEmptyIndices.isNotEmpty()) { "Progira/EDX PAT is empty." }
        val headerIndex = nonEmptyIndices.first()
        // PAT's quoted producer label contains a space, so the generic whitespace-delimited row
        // tokenizer would incorrectly split "By ADT" into two fields. The header is explicitly
        // comma-delimited; pattern rows continue through the strict numeric tokenizer below.
        val headerFields = bounded.lines[headerIndex]
            .trim()
            .split(',')
            .map { field -> field.trim().trim('"', '\'') }
            .filter(String::isNotEmpty)
        codecRequire(headerFields.size >= 3 && headerFields.first().contains("By ADT", ignoreCase = true)) {
            "Progira/EDX PAT must start with a 'By ADT', gain, version header."
        }
        val declaredGainDbi = parseFiniteNumber(headerFields[1], "PAT declared gain")
        val declaredElementCount = parseFiniteNumber(
            headerFields[2],
            "PAT declared element count",
        )
        codecRequire(
            declaredElementCount % 1.0 == 0.0 &&
                declaredElementCount.toInt() in 1..AntennaPatternLimits.MAX_ARRAY_ELEMENTS,
        ) {
            "PAT declared element count must be an integer between 1 and " +
                "${AntennaPatternLimits.MAX_ARRAY_ELEMENTS}."
        }
        val separatorIndices = bounded.lines.mapIndexedNotNull { index, line ->
            index.takeIf { line.trim() == "999" }
        }
        codecRequire(separatorIndices.size == 1) {
            "Progira/EDX PAT must contain exactly one 999 section separator."
        }
        val separatorIndex = separatorIndices.single()
        codecRequire(separatorIndex > headerIndex) {
            "Progira/EDX PAT 999 separator must follow the HRP header and samples."
        }
        val horizontalRows = parsePatternRows(
            lines = bounded.lines.subList(headerIndex + 1, separatorIndex),
            firstSourceLineNumber = headerIndex + 2,
            label = "PAT HRP",
            allowedColumnCounts = 2..2,
        )

        var cursor = nextContentLine(bounded.lines, separatorIndex + 1)
        codecRequire(cursor != null) { "PAT is missing its VRP section header." }
        val vrpHeader = parseNumericFields(
            bounded.lines[cursor!!],
            "PAT VRP section header",
            cursor + 1,
        )
        codecRequire(vrpHeader.size == 2 && abs(vrpHeader[0] - 1.0) <= NUMERIC_TOLERANCE) {
            "PAT VRP section header must be '1, sample_count'."
        }
        val declaredVerticalCount = boundedCount(vrpHeader[1], "PAT VRP sample count")
        cursor = nextContentLine(bounded.lines, cursor + 1)
        codecRequire(cursor != null) { "PAT is missing its vertical-cut azimuth metadata." }
        val azimuthFields = parseNumericFields(
            bounded.lines[cursor!!],
            "PAT vertical-cut azimuth",
            cursor + 1,
        )
        codecRequire(azimuthFields.size == 1 && azimuthFields.single() in 0.0..360.0) {
            "PAT vertical-cut azimuth must contain one value in [0, 360] degrees."
        }
        val verticalCutAzimuth = normalizeHorizontalAngle(azimuthFields.single())
        val verticalStartIndex = cursor + 1
        val verticalRows = parsePatternRows(
            lines = bounded.lines.subList(verticalStartIndex, bounded.lines.size),
            firstSourceLineNumber = verticalStartIndex + 1,
            label = "PAT VRP",
            allowedColumnCounts = 2..2,
        )
        codecRequire(verticalRows.size == declaredVerticalCount) {
            "PAT VRP declares $declaredVerticalCount samples but contains ${verticalRows.size}."
        }

        val warnings = mutableListOf(
            "PAT declared gain ${declaredGainDbi.compact()} dBi is metadata only and was not " +
                "applied to normalized cuts.",
            "PAT vertical-cut azimuth ${verticalCutAzimuth.compact()} degrees is metadata for a " +
                "single VRP cut, not a full 3D pattern.",
            "PAT elevation sign was inverted to the canonical positive-elevation convention.",
        )
        val horizontalSamples = canonicalizeRows(
            rows = horizontalRows,
            plane = PatternCutPlane.HORIZONTAL,
            sectionLabel = "PAT HRP",
            valueMode = SourceValueMode.LINEAR_FIELD,
            warnings = warnings,
            angleTransform = { angle ->
                codecRequire(angle in -360.0..360.0) {
                    "PAT HRP angles must be in [-360, 360] degrees."
                }
                normalizeHorizontalAngle(angle)
            },
        )
        val verticalSamples = canonicalizeRows(
            rows = verticalRows,
            plane = PatternCutPlane.VERTICAL,
            sectionLabel = "PAT VRP",
            valueMode = SourceValueMode.LINEAR_FIELD,
            warnings = warnings,
            angleTransform = { sourceAngle ->
                codecRequire(sourceAngle in -90.0..90.0) {
                    "PAT VRP source angles must be in [-90, 90] degrees."
                }
                normalizeZero(-sourceAngle)
            },
        )
        val provenance = importedProvenance(
            sourceLabel = sourceLabel,
            format = AntennaPatternFileFormat.PROGIRA_EDX_PAT,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            limitations = listOf(
                "Progira/EDX PAT contains normalized HRP and one azimuth-specific VRP only.",
                "PAT gain is preserved as import metadata but is not part of normalized E/Emax.",
            ),
        )
        val horizontal = AntennaPatternCut(
            plane = PatternCutPlane.HORIZONTAL,
            samples = horizontalSamples,
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        )
        val vertical = AntennaPatternCut(
            plane = PatternCutPlane.VERTICAL,
            samples = verticalSamples,
            provenance = provenance,
            availability = PatternCutAvailability.AVAILABLE,
        )
        val pattern = CanonicalAntennaPattern(
            id = importedPatternId(bounded.sha256),
            name = sourceName(sourceLabel),
            horizontalCut = horizontal,
            verticalCut = vertical,
            provenance = provenance,
        )
        return AntennaPatternImportResult(
            detectedFormat = AntennaPatternFileFormat.PROGIRA_EDX_PAT,
            valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            cuts = listOf(horizontal, vertical),
            pattern = pattern,
            metadata = AntennaPatternFileMetadata(
                declaredGainDbi = declaredGainDbi,
                verticalCutAzimuthDegrees = verticalCutAzimuth,
            ),
            sourceSha256 = bounded.sha256,
            warnings = warnings.toList(),
        )
    }

    private fun decodeJson(
        bounded: BoundedPayload,
        sourceLabel: String,
    ): AntennaPatternImportResult {
        val discriminator = try {
            jsonFormatDiscriminator.decodeFromString(
                AntennaJsonFormatDiscriminator.serializer(),
                bounded.text,
            )
        } catch (error: SerializationException) {
            throw AntennaPatternCodecException(
                "Antenna JSON requires a valid top-level string format discriminator: " +
                    "${error.message}",
                error,
            )
        }
        return when (discriminator.format) {
            CANONICAL_JSON_FORMAT -> decodeCanonicalJson(bounded, sourceLabel)
            DESKTOP_JSON_FORMAT -> decodeDesktopJsonV1(bounded, sourceLabel)
            else -> codecFailure(
                "Unsupported antenna JSON format discriminator; expected " +
                    "'$CANONICAL_JSON_FORMAT' or '$DESKTOP_JSON_FORMAT'.",
            )
        }
    }

    private fun decodeDesktopJsonV1(
        bounded: BoundedPayload,
        sourceLabel: String,
    ): AntennaPatternImportResult {
        val dto = try {
            canonicalJson.decodeFromString(DesktopAntennaJsonV1.serializer(), bounded.text)
        } catch (error: SerializationException) {
            throw AntennaPatternCodecException(
                "ATX Planner desktop JSON v1 is invalid: ${error.message}",
                error,
            )
        }
        val warnings = mutableListOf(
            "Embedded desktop JSON source.format and source.sha256 are declarative and were " +
                "not independently verified; the imported byte SHA-256 is ${bounded.sha256}.",
        )
        val decodedCuts = try {
            dto.validateEnvelope()
            dto.cuts.map { cut -> cut.toCanonicalSamples(warnings) }
        } catch (error: IllegalArgumentException) {
            throw AntennaPatternCodecException(
                "ATX Planner desktop JSON v1 violates its interchange contract: ${error.message}",
                error,
            )
        }
        if (dto.cuts.any { cut -> cut.polarization != DESKTOP_JSON_UNKNOWN_POLARIZATION }) {
            warnings +=
                "Desktop cut polarization is declarative because the Android canonical cut " +
                    "model does not currently retain polarization metadata."
        }
        if (dto.cuts.any { cut -> cut.plane == DESKTOP_JSON_HORIZONTAL_PLANE }) {
            warnings +=
                "Desktop HRP angle values labeled clockwise from geographic north were retained " +
                    "numerically without rotation. Android project use interprets angle 0 as " +
                    "sector boresight; this is not a verified geodetic transform."
        }
        val sourceCoordinateFrame = if (
            decodedCuts.any { (plane, _) -> plane == PatternCutPlane.HORIZONTAL }
        ) {
            PatternCoordinateFrame.GEOGRAPHIC_NORTH_CLOCKWISE
        } else {
            PatternCoordinateFrame.SOURCE_RELATIVE_UNSPECIFIED
        }
        val provenance = importedProvenance(
            sourceLabel = sourceLabel,
            format = AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            limitations = listOf(
                "Desktop positive field attenuation was converted with " +
                    "E/Emax = 10^(-attenuation dB/20) and normalized per cut.",
                "Desktop polarization is not a field of the Android canonical antenna model.",
                "Desktop geographic-north HRP labels are retained as source metadata; project " +
                    "calculations use the same numeric angles relative to sector boresight.",
            ),
        ).copy(sourceCoordinateFrame = sourceCoordinateFrame)
        val cuts = decodedCuts
            .sortedBy { (plane, _) -> plane.ordinal }
            .map { (plane, samples) ->
                AntennaPatternCut(
                    plane = plane,
                    samples = samples,
                    provenance = provenance,
                    availability = PatternCutAvailability.AVAILABLE,
                )
            }
        val horizontal = cuts.singleOrNull { cut -> cut.plane == PatternCutPlane.HORIZONTAL }
        val vertical = cuts.singleOrNull { cut -> cut.plane == PatternCutPlane.VERTICAL }
        val pattern = if (horizontal != null && vertical != null) {
            CanonicalAntennaPattern(
                id = importedPatternId(bounded.sha256),
                name = dto.name,
                horizontalCut = horizontal,
                verticalCut = vertical,
                provenance = provenance,
                nominalFrequencyHz = dto.nominalFrequencyHz,
            )
        } else {
            null
        }
        return AntennaPatternImportResult(
            detectedFormat = AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1,
            valueConvention =
                AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            cuts = cuts,
            pattern = pattern,
            metadata = AntennaPatternFileMetadata(
                nominalFrequencyHz = dto.nominalFrequencyHz,
                declaredGainDbi = dto.gainDbi,
            ),
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            formatVersion = dto.version,
        )
    }

    private fun decodeCanonicalJson(
        bounded: BoundedPayload,
        sourceLabel: String,
    ): AntennaPatternImportResult {
        val dto = try {
            canonicalJson.decodeFromString(CanonicalAntennaJson.serializer(), bounded.text)
        } catch (error: SerializationException) {
            throw AntennaPatternCodecException("ATX Antenna JSON is invalid: ${error.message}", error)
        }
        val decoded = try {
            dto.toDomain()
        } catch (error: IllegalArgumentException) {
            throw AntennaPatternCodecException(
                "ATX Antenna JSON violates the canonical pattern contract: ${error.message}",
                error,
            )
        }
        val pattern = decoded.pattern
        val warnings = buildList {
            add(
                "Embedded JSON provenance and source-format metadata are declarative and were " +
                    "not independently verified; the imported byte SHA-256 is ${bounded.sha256}.",
            )
            if (dto.schemaVersion == CANONICAL_JSON_LEGACY_SCHEMA_VERSION) {
                add(
                    "ATX Antenna JSON v1 has no lossless source-format metadata envelope; " +
                        "peak gain, vertical-cut azimuth, and beam tilt remain NoData unless " +
                        "supplied by another verified source.",
                )
            }
            if (!pattern.isCalculationReady) {
                add(
                    "At least one JSON cut lacks explicit AVAILABLE status; legacy or display " +
                        "placeholder cuts remain review-only and are not calculation-ready.",
                )
            }
        }
        return AntennaPatternImportResult(
            detectedFormat = AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1,
            valueConvention =
                AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE,
            cuts = listOf(pattern.horizontalCut, pattern.verticalCut),
            pattern = pattern,
            metadata = decoded.metadata,
            sourceSha256 = bounded.sha256,
            warnings = warnings,
            formatVersion = dto.schemaVersion,
        )
    }

    private fun detectBounded(
        bounded: BoundedPayload,
        sourceLabel: String,
        prnValueConventionOverride: PrnValueConventionOverride?,
    ): AntennaPatternFileDetection {
        codecRequire(!bounded.looksLikeJson) {
            "JSON antenna input must use the bounded typed JSON decoder."
        }

        val trimmed = bounded.lines.map(String::trim)
        val firstNonEmpty = trimmed.firstOrNull(String::isNotEmpty).orEmpty()
        if (firstNonEmpty.filterNot(Char::isWhitespace) == VSOFT_HRP_HEADER) {
            return AntennaPatternFileDetection(
                format = AntennaPatternFileFormat.VSOFT_HRP,
                valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            )
        }
        if (firstNonEmpty.contains(VSOFT_VRP_DETECTION_TEXT, ignoreCase = true)) {
            return AntennaPatternFileDetection(
                format = AntennaPatternFileFormat.VSOFT_VRP,
                valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            )
        }
        val hasHorizontal = trimmed.any { line ->
            PRN_SECTION_REGEX.matchEntire(line)?.groupValues?.get(1)
                ?.equals("HORIZONTAL", ignoreCase = true) == true
        }
        val hasVertical = trimmed.any { line ->
            PRN_SECTION_REGEX.matchEntire(line)?.groupValues?.get(1)
                ?.equals("VERTICAL", ignoreCase = true) == true
        }
        if (hasHorizontal || hasVertical) {
            val sections = parsePrnSections(bounded.lines)
            val valueInterpretation = resolvePrnValueInterpretation(
                lines = bounded.lines,
                horizontalRows = sections.horizontalRows,
                verticalRows = sections.verticalRows,
                prnValueConventionOverride = prnValueConventionOverride,
            )
            return AntennaPatternFileDetection(
                format = AntennaPatternFileFormat.PRN,
                valueConvention = valueInterpretation.valueConvention,
            )
        }
        if (firstNonEmpty.contains("By ADT", ignoreCase = true) && trimmed.any { it == "999" }) {
            return AntennaPatternFileDetection(
                format = AntennaPatternFileFormat.PROGIRA_EDX_PAT,
                valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            )
        }
        val voltageIndex = trimmed.indexOfFirst { line -> line.equals("voltage", ignoreCase = true) }
        if (voltageIndex >= 0) {
            val format = detectAdtFormat(bounded.lines, sourceLabel, voltageIndex)
            val dataLines = bounded.lines.drop(voltageIndex + 1)
            val hasPhaseColumn = dataLines.any { line ->
                line.isContentLine() && splitFields(line).size == 3
            }
            return AntennaPatternFileDetection(
                format = format,
                valueConvention = if (hasPhaseColumn) {
                    AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE
                } else {
                    AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE
                },
            )
        }
        val table = analyzeGenericTable(bounded.lines, sourceLabel)
        return AntennaPatternFileDetection(
            format = if (table.plane == PatternCutPlane.HORIZONTAL) {
                AntennaPatternFileFormat.GENERIC_HRP_TABLE
            } else {
                AntennaPatternFileFormat.GENERIC_VRP_TABLE
            },
            valueConvention = table.valueConvention,
        )
    }

    private fun detectAdtFormat(
        lines: List<String>,
        sourceLabel: String,
        voltageIndex: Int,
    ): AntennaPatternFileFormat {
        val headerText = lines.take(voltageIndex).joinToString(" ")
        val explicitType = ADT_PATTERN_TYPE_REGEX.find(headerText)?.groupValues?.get(1)
        if (explicitType != null) {
            return if (explicitType.equals("VRP", ignoreCase = true)) {
                AntennaPatternFileFormat.ADT_VRP
            } else {
                AntennaPatternFileFormat.ADT_HRP
            }
        }
        val fileName = sourceLabel.substringAfterLast('/').substringAfterLast('\\')
        val suffix = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (suffix in setOf("vrp", "vup")) return AntennaPatternFileFormat.ADT_VRP
        if (suffix in setOf("hrp", "hup")) return AntennaPatternFileFormat.ADT_HRP

        val descriptiveText = fileName.substringBeforeLast('.', missingDelimiterValue = fileName) +
            " " + headerText
        val horizontalHint = ADT_HORIZONTAL_LABEL_REGEX.containsMatchIn(descriptiveText)
        val verticalHint = ADT_VERTICAL_LABEL_REGEX.containsMatchIn(descriptiveText)
        codecRequire(!(horizontalHint && verticalHint)) {
            "ADT cut plane is ambiguous because its filename or header declares both horizontal " +
                "and vertical pattern terms."
        }
        if (verticalHint) return AntennaPatternFileFormat.ADT_VRP
        if (horizontalHint) return AntennaPatternFileFormat.ADT_HRP

        val angles = lines.drop(voltageIndex + 1).mapNotNull { line ->
            if (!line.isContentLine()) return@mapNotNull null
            splitFields(line).firstOrNull()?.toDoubleOrNull()
        }.filter(Double::isFinite)
        if (angles.size >= 2) {
            val minimum = angles.min()
            val maximum = angles.max()
            val range = maximum - minimum
            if (minimum >= 0.0 && maximum > 180.0 && range > 180.0) {
                return AntennaPatternFileFormat.ADT_HRP
            }
            if (
                minimum >= -90.0 && maximum <= 90.0 &&
                minimum <= -89.0 && maximum >= 89.0
            ) {
                return AntennaPatternFileFormat.ADT_VRP
            }
        }
        codecFailure(
            "ADT cut plane is ambiguous; use an explicit HRP/VRP suffix, filename/header plane " +
                "label, or pattern_type declaration.",
        )
    }
}

private data class BoundedPayload(
    val text: String,
    val lines: List<String>,
    val sha256: String,
    val looksLikeJson: Boolean,
)

private data class RawPatternRow(
    val angleDegrees: Double,
    val value: Double,
    val phaseDegrees: Double?,
)

private data class CanonicalPatternField(
    val angleDegrees: Double,
    val field: ComplexField,
    val phaseDegrees: Double?,
)

private data class PrnHeading(
    val plane: PatternCutPlane,
    val declaredCount: Int,
    val lineIndex: Int,
)

private data class PrnSections(
    val horizontalRows: List<RawPatternRow>,
    val verticalRows: List<RawPatternRow>,
)

private data class PrnValueInterpretation(
    val valueMode: SourceValueMode,
    val valueConvention: AntennaPatternValueConvention,
    val limitation: String,
    val inferenceWarning: String? = null,
)

private enum class SourceValueMode {
    POSITIVE_ATTENUATION_DB,
    LINEAR_FIELD,
    RELATIVE_FIELD_DB,
}

private enum class GenericVerticalAngleMode {
    SIGNED,
    ZERO_TO_180,
    WRAPPED_360,
}

private data class GenericTableAnalysis(
    val sourceLines: List<String>,
    val plane: PatternCutPlane,
    val rows: List<List<Double>>,
    val headers: List<String>,
    val angleColumn: Int,
    val magnitudeColumn: Int,
    val phaseColumn: Int?,
    val valueMode: SourceValueMode,
    val valueConvention: AntennaPatternValueConvention,
)

private data class GenericNumericBlock(
    val rows: List<List<Double>>,
    val headers: List<String>,
)

private fun readBoundedPayload(
    payload: ByteArray,
    sourceLabel: String,
): BoundedPayload {
    codecRequire(payload.isNotEmpty()) { "An antenna pattern payload cannot be empty." }
    codecRequire(payload.size <= AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
        "An antenna pattern payload cannot exceed 16 MiB."
    }
    validateSourceLabel(sourceLabel)
    val sourceSuffix = sourceLabel.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    codecRequire(sourceSuffix !in setOf("kml", "kmz")) {
        "KML and KMZ are geospatial exchange formats and cannot be imported as antenna patterns."
    }
    val decoded = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    } catch (error: CharacterCodingException) {
        throw AntennaPatternCodecException("Antenna pattern text must be valid UTF-8.", error)
    }
    codecRequire(decoded.none { character ->
        character.isISOControl() && character !in setOf('\n', '\r', '\t')
    }) {
        "Antenna pattern text contains an unsupported control character."
    }
    val text = decoded.removePrefix("\uFEFF")
    val looksLikeJson = text.dropWhile(Char::isWhitespace).startsWith('{')
    val lines = if (looksLikeJson) {
        validateJsonMaterializationBounds(text)
        emptyList()
    } else {
        val lineCount = text.count { character -> character == '\n' } + 1
        codecRequire(lineCount <= AntennaPatternCodecLimits.MAX_INPUT_LINES) {
            "An antenna pattern payload cannot exceed " +
                "${AntennaPatternCodecLimits.MAX_INPUT_LINES} lines."
        }
        text.split('\n').map { line -> line.removeSuffix("\r") }
    }
    if (!looksLikeJson) {
        codecRequire(lines.all { line ->
            line.length <= AntennaPatternCodecLimits.MAX_LEGACY_LINE_CHARACTERS
        }) {
            "A legacy antenna pattern line cannot exceed " +
                "${AntennaPatternCodecLimits.MAX_LEGACY_LINE_CHARACTERS} characters."
        }
    }
    return BoundedPayload(
        text = text,
        lines = lines,
        sha256 = sha256Hex(payload),
        looksLikeJson = looksLikeJson,
    )
}

/**
 * Allocation preflight for untrusted JSON.
 *
 * Kotlin serialization necessarily materializes typed lists. These lexical limits run before
 * that allocation, bound total token/object pressure, and count every canonical or desktop
 * sample key even when the key uses JSON escapes. Full syntax and schema validation still belongs
 * to the strict serializer immediately afterward.
 */
private fun validateJsonMaterializationBounds(text: String) {
    val containerStack = CharArray(MAX_JSON_NESTING_DEPTH)
    var depth = 0
    var tokenCount = 0
    var sampleKeyCount = 0
    var index = 0

    fun countToken() {
        tokenCount += 1
        codecRequire(tokenCount <= MAX_JSON_LEXICAL_TOKENS) {
            "ATX antenna JSON exceeds the bounded lexical token limit before decoding."
        }
    }

    fun nextNonWhitespace(fromIndex: Int): Char? {
        var cursor = fromIndex
        while (cursor < text.length && text[cursor].isWhitespace()) cursor += 1
        return text.getOrNull(cursor)
    }

    while (index < text.length) {
        val character = text[index]
        when {
            character.isWhitespace() -> index += 1

            character == '{' || character == '[' -> {
                countToken()
                codecRequire(depth < MAX_JSON_NESTING_DEPTH) {
                    "ATX antenna JSON exceeds the bounded nesting depth before decoding."
                }
                containerStack[depth] = character
                depth += 1
                index += 1
            }

            character == '}' || character == ']' -> {
                countToken()
                codecRequire(depth > 0) {
                    "ATX antenna JSON closes a container that was not opened."
                }
                val expectedOpening = if (character == '}') '{' else '['
                codecRequire(containerStack[depth - 1] == expectedOpening) {
                    "ATX antenna JSON contains mismatched container delimiters."
                }
                depth -= 1
                index += 1
            }

            character == ',' || character == ':' -> {
                countToken()
                index += 1
            }

            character == '"' -> {
                countToken()
                index += 1
                var rawLength = 0
                var logicalLength = 0
                var matchesCanonicalSampleKey = true
                var matchesDesktopSampleKey = true
                while (true) {
                    codecRequire(index < text.length) {
                        "ATX antenna JSON contains an unterminated string."
                    }
                    val current = text[index]
                    if (current == '"') {
                        index += 1
                        break
                    }
                    codecRequire(current.code >= 0x20) {
                        "ATX antenna JSON contains a control character in a string."
                    }
                    rawLength += 1
                    codecRequire(rawLength <= MAX_JSON_STRING_TOKEN_CHARACTERS) {
                        "ATX antenna JSON contains an oversized string token before decoding."
                    }
                    val decodedCharacter = if (current == '\\') {
                        index += 1
                        codecRequire(index < text.length) {
                            "ATX antenna JSON ends inside a string escape."
                        }
                        rawLength += 1
                        codecRequire(rawLength <= MAX_JSON_STRING_TOKEN_CHARACTERS) {
                            "ATX antenna JSON contains an oversized string token before decoding."
                        }
                        when (val escaped = text[index]) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                codecRequire(index + 4 < text.length) {
                                    "ATX antenna JSON contains an incomplete Unicode escape."
                                }
                                val hex = text.substring(index + 1, index + 5)
                                codecRequire(hex.all { digit -> digit.isDigit() || digit.lowercaseChar() in 'a'..'f' }) {
                                    "ATX antenna JSON contains an invalid Unicode escape."
                                }
                                rawLength += 4
                                codecRequire(rawLength <= MAX_JSON_STRING_TOKEN_CHARACTERS) {
                                    "ATX antenna JSON contains an oversized string token before decoding."
                                }
                                index += 4
                                hex.toInt(16).toChar()
                            }

                            else -> codecFailure("ATX antenna JSON contains an unsupported string escape.")
                        }
                    } else {
                        current
                    }
                    if (
                        logicalLength >= CANONICAL_JSON_SAMPLE_KEY.length ||
                        decodedCharacter != CANONICAL_JSON_SAMPLE_KEY[logicalLength]
                    ) matchesCanonicalSampleKey = false
                    if (
                        logicalLength >= DESKTOP_JSON_SAMPLE_KEY.length ||
                        decodedCharacter != DESKTOP_JSON_SAMPLE_KEY[logicalLength]
                    ) matchesDesktopSampleKey = false
                    logicalLength += 1
                    index += 1
                }
                if (
                    (
                        matchesCanonicalSampleKey &&
                            logicalLength == CANONICAL_JSON_SAMPLE_KEY.length ||
                            matchesDesktopSampleKey &&
                            logicalLength == DESKTOP_JSON_SAMPLE_KEY.length
                        ) &&
                    nextNonWhitespace(index) == ':'
                ) {
                    sampleKeyCount += 1
                    codecRequire(sampleKeyCount <= MAX_JSON_TOTAL_SAMPLE_DECLARATIONS) {
                        "ATX antenna JSON exceeds the two-cut sample limit before decoding."
                    }
                }
            }

            character == '-' || character in '0'..'9' -> {
                countToken()
                val start = index
                while (
                    index < text.length &&
                    !text[index].isWhitespace() &&
                    text[index] !in JSON_TOKEN_DELIMITERS
                ) {
                    index += 1
                }
                codecRequire(index - start <= MAX_JSON_NUMBER_TOKEN_CHARACTERS) {
                    "ATX antenna JSON contains an oversized numeric token before decoding."
                }
            }

            character == 't' && text.startsWith("true", index) -> {
                countToken()
                index += 4
            }

            character == 'f' && text.startsWith("false", index) -> {
                countToken()
                index += 5
            }

            character == 'n' && text.startsWith("null", index) -> {
                countToken()
                index += 4
            }

            else -> codecFailure("ATX antenna JSON contains an invalid lexical token.")
        }
    }
    codecRequire(depth == 0) { "ATX antenna JSON contains an unclosed container." }
}

private fun parsePrnSections(lines: List<String>): PrnSections {
    val headings = lines.mapIndexedNotNull { index, line ->
        PRN_SECTION_REGEX.matchEntire(line.trim())?.let { match ->
            PrnHeading(
                plane = PatternCutPlane.valueOf(match.groupValues[1].uppercase(Locale.ROOT)),
                declaredCount = parseBoundedCount(match.groupValues[2], "PRN section count"),
                lineIndex = index,
            )
        }
    }
    codecRequire(headings.isNotEmpty() && headings.size <= 2) {
        "PRN must contain one or two explicit HORIZONTAL/VERTICAL sections."
    }
    codecRequire(headings.count { it.plane == PatternCutPlane.HORIZONTAL } <= 1) {
        "PRN cannot contain duplicate HORIZONTAL sections."
    }
    codecRequire(headings.count { it.plane == PatternCutPlane.VERTICAL } <= 1) {
        "PRN cannot contain duplicate VERTICAL sections."
    }
    val horizontalHeading = headings.singleOrNull { it.plane == PatternCutPlane.HORIZONTAL }
    val verticalHeading = headings.singleOrNull { it.plane == PatternCutPlane.VERTICAL }
    if (horizontalHeading != null && verticalHeading != null) {
        codecRequire(horizontalHeading.lineIndex < verticalHeading.lineIndex) {
            "PRN HORIZONTAL must appear before VERTICAL when both cuts are present."
        }
    }
    val horizontalRows = horizontalHeading?.let { heading ->
        parsePatternRows(
            lines = lines.subList(
                heading.lineIndex + 1,
                verticalHeading?.lineIndex ?: lines.size,
            ),
            firstSourceLineNumber = heading.lineIndex + 2,
            label = "PRN HORIZONTAL",
            allowedColumnCounts = 2..3,
        ).also { rows -> requireDeclaredCount(rows, heading, "PRN HORIZONTAL") }
    }.orEmpty()
    val verticalRows = verticalHeading?.let { heading ->
        parsePatternRows(
            lines = lines.subList(heading.lineIndex + 1, lines.size),
            firstSourceLineNumber = heading.lineIndex + 2,
            label = "PRN VERTICAL",
            allowedColumnCounts = 2..3,
        ).also { rows -> requireDeclaredCount(rows, heading, "PRN VERTICAL") }
    }.orEmpty()
    return PrnSections(horizontalRows = horizontalRows, verticalRows = verticalRows)
}

private fun resolvePrnValueInterpretation(
    lines: List<String>,
    horizontalRows: List<RawPatternRow>,
    verticalRows: List<RawPatternRow>,
    prnValueConventionOverride: PrnValueConventionOverride?,
): PrnValueInterpretation {
    codecRequire(horizontalRows.isNotEmpty() || verticalRows.isNotEmpty()) {
        "PRN must contain numeric values in at least one pattern section."
    }
    val declarations = lines.mapNotNull { line ->
        PRN_VALUE_CONVENTION_REGEX.matchEntire(line.trim())?.groupValues?.get(1)
    }
    codecRequire(declarations.size <= 1) {
        "PRN cannot contain multiple VALUE_CONVENTION declarations."
    }
    val explicit = declarations.singleOrNull()?.uppercase(Locale.ROOT)?.let { token ->
        when (token) {
            "POSITIVE_FIELD_ATTENUATION_DB_20_LOG10" -> PrnValueInterpretation(
                valueMode = SourceValueMode.POSITIVE_ATTENUATION_DB,
                valueConvention =
                    AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
                limitation =
                    "PRN values use explicitly declared positive field attenuation in dB.",
            )

            "NORMALIZED_FIELD_AMPLITUDE" -> PrnValueInterpretation(
                valueMode = SourceValueMode.LINEAR_FIELD,
                valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
                limitation =
                    "PRN values use explicitly declared normalized linear field amplitude E/Emax.",
            )

            "RELATIVE_FIELD_DB_20_LOG10" -> PrnValueInterpretation(
                valueMode = SourceValueMode.RELATIVE_FIELD_DB,
                valueConvention = AntennaPatternValueConvention.RELATIVE_FIELD_DB_20_LOG10,
                limitation = "PRN values use explicitly declared relative field level in dB.",
            )

            else -> codecFailure(
                "PRN VALUE_CONVENTION '$token' is unsupported; the value semantics cannot be inferred safely.",
            )
        }
    }
    val attenuationUnitDeclarations = lines.count { line ->
        PRN_ATTENUATION_UNIT_REGEX.matches(line.trim())
    }
    codecRequire(attenuationUnitDeclarations <= 1) {
        "PRN cannot contain multiple ATTENUATION_UNIT declarations."
    }
    val uppercaseLines = lines.map { line -> line.trim().uppercase(Locale.ROOT) }.toSet()
    val hasNativeAttenuationMarkers = PRN_NATIVE_ATTENUATION_MARKERS.all(uppercaseLines::contains)
    val hasAttenuationMetadata = attenuationUnitDeclarations == 1 || hasNativeAttenuationMarkers
    val callerSelected = when (prnValueConventionOverride) {
        null -> null
        PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB -> PrnValueInterpretation(
            valueMode = SourceValueMode.POSITIVE_ATTENUATION_DB,
            valueConvention =
                AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            limitation =
                "Unmarked PRN values use caller-selected positive field attenuation in dB.",
            inferenceWarning =
                "The unmarked PRN value convention was explicitly selected by the caller as " +
                    "positive field attenuation in dB.",
        )

        PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD -> PrnValueInterpretation(
            valueMode = SourceValueMode.LINEAR_FIELD,
            valueConvention = AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE,
            limitation =
                "Unmarked PRN values use caller-selected normalized linear field amplitude " +
                    "E/Emax.",
            inferenceWarning =
                "The unmarked PRN value convention was explicitly selected by the caller as " +
                    "normalized linear field amplitude E/Emax.",
        )
    }
    if (explicit != null) {
        codecRequire(callerSelected == null || callerSelected.valueMode == explicit.valueMode) {
            "The caller-selected PRN value convention conflicts with the explicit " +
                "VALUE_CONVENTION declaration."
        }
        codecRequire(
            !hasAttenuationMetadata ||
                explicit.valueMode == SourceValueMode.POSITIVE_ATTENUATION_DB,
        ) {
            "PRN VALUE_CONVENTION conflicts with explicit attenuation metadata."
        }
        return explicit
    }
    if (hasAttenuationMetadata) {
        codecRequire(
            callerSelected == null ||
                callerSelected.valueMode == SourceValueMode.POSITIVE_ATTENUATION_DB,
        ) {
            "The caller-selected PRN value convention conflicts with explicit attenuation " +
                "metadata."
        }
        return PrnValueInterpretation(
            valueMode = SourceValueMode.POSITIVE_ATTENUATION_DB,
            valueConvention =
                AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
            limitation = "PRN values use positive field attenuation identified by explicit metadata.",
        )
    }
    if (callerSelected != null) {
        val selectedValues = (horizontalRows + verticalRows).map(RawPatternRow::value)
        when (prnValueConventionOverride) {
            PrnValueConventionOverride.POSITIVE_FIELD_ATTENUATION_DB -> codecRequire(
                selectedValues.all { value -> value >= 0.0 },
            ) {
                "Caller-selected positive PRN attenuation cannot contain negative values."
            }

            PrnValueConventionOverride.NORMALIZED_LINEAR_FIELD -> codecRequire(
                selectedValues.all { value -> value in 0.0..1.0 },
            ) {
                "Caller-selected normalized PRN linear field values must be in [0, 1]."
            }

            null -> Unit
        }
        return callerSelected
    }
    val values = (horizontalRows + verticalRows).map(RawPatternRow::value)
    codecRequire(values.none { value -> value < 0.0 }) {
        "PRN values cannot be negative without an explicit supported VALUE_CONVENTION."
    }
    val ambiguousSections = buildList {
        if (
            horizontalRows.isNotEmpty() &&
            horizontalRows.all { row -> row.value in 0.0..1.0 }
        ) {
            add(PatternCutPlane.HORIZONTAL)
        }
        if (
            verticalRows.isNotEmpty() &&
            verticalRows.all { row -> row.value in 0.0..1.0 }
        ) {
            add(PatternCutPlane.VERTICAL)
        }
    }
    if (ambiguousSections.isNotEmpty()) {
        throw PrnValueConventionRequiredException(ambiguousSections.toSet())
    }
    return PrnValueInterpretation(
        valueMode = SourceValueMode.POSITIVE_ATTENUATION_DB,
        valueConvention = AntennaPatternValueConvention.POSITIVE_FIELD_ATTENUATION_DB_20_LOG10,
        limitation =
            "PRN values are interpreted as positive field attenuation because the source range exceeds 1.",
        inferenceWarning =
            "PRN value convention was inferred as positive field attenuation from values above 1; " +
                "no explicit convention marker was present.",
    )
}

private fun parsePatternRows(
    lines: List<String>,
    firstSourceLineNumber: Int,
    label: String,
    allowedColumnCounts: IntRange,
): List<RawPatternRow> {
    val result = ArrayList<RawPatternRow>(minOf(lines.size, AntennaPatternLimits.MAX_CUT_SAMPLES))
    lines.forEachIndexed { offset, line ->
        if (!line.isContentLine()) return@forEachIndexed
        val sourceLineNumber = firstSourceLineNumber + offset
        val fields = parseNumericFields(line, label, sourceLineNumber)
        codecRequire(fields.size in allowedColumnCounts) {
            "$label line $sourceLineNumber must contain ${allowedColumnCounts.first}" +
                if (allowedColumnCounts.first == allowedColumnCounts.last) {
                    " numeric columns."
                } else {
                    " to ${allowedColumnCounts.last} numeric columns."
                }
        }
        codecRequire(result.size < AntennaPatternLimits.MAX_CUT_SAMPLES) {
            "$label cannot exceed ${AntennaPatternLimits.MAX_CUT_SAMPLES} source samples."
        }
        result += RawPatternRow(
            angleDegrees = fields[0],
            value = fields[1],
            phaseDegrees = fields.getOrNull(2),
        )
    }
    codecRequire(result.size >= 2) { "$label must contain at least two samples." }
    return result
}

private fun canonicalizeRows(
    rows: List<RawPatternRow>,
    plane: PatternCutPlane,
    sectionLabel: String,
    valueMode: SourceValueMode,
    warnings: MutableList<String>,
    angleTransform: (Double) -> Double?,
): List<PatternSample> {
    codecRequire(rows.size <= AntennaPatternLimits.MAX_CUT_SAMPLES) {
        "$sectionLabel cannot exceed ${AntennaPatternLimits.MAX_CUT_SAMPLES} source samples."
    }
    val transformedRows = ArrayList<RawPatternRow>(rows.size)
    rows.forEach { row ->
        requireFinite(row.angleDegrees, "$sectionLabel angle")
        requireFinite(row.value, "$sectionLabel value")
        row.phaseDegrees?.let { phase -> requireFinite(phase, "$sectionLabel phase") }
        val canonicalAngle = angleTransform(row.angleDegrees) ?: return@forEach
        when (plane) {
            PatternCutPlane.HORIZONTAL -> codecRequire(canonicalAngle in 0.0..<360.0) {
                "$sectionLabel canonical horizontal angle is outside [0, 360)."
            }

            PatternCutPlane.VERTICAL -> codecRequire(canonicalAngle in -90.0..90.0) {
                "$sectionLabel canonical vertical angle is outside [-90, 90]."
            }
        }
        transformedRows += row.copy(angleDegrees = canonicalAngle)
    }
    codecRequire(transformedRows.map(RawPatternRow::angleDegrees).distinct().size >= 2) {
        "$sectionLabel must retain at least two unique canonical angles."
    }
    val sourceAmplitudes = when (valueMode) {
        SourceValueMode.POSITIVE_ATTENUATION_DB -> {
            transformedRows.forEach { row ->
                codecRequire(row.value >= 0.0) {
                    "$sectionLabel positive attenuation cannot be negative."
                }
            }
            val minimum = transformedRows.minOf(RawPatternRow::value)
            if (minimum > NUMERIC_TOLERANCE) {
                warnings +=
                    "$sectionLabel attenuation minimum ${minimum.compact()} dB was normalized " +
                    "to 0 dB before E/Emax conversion."
            }
            transformedRows.map { row -> 10.0.pow(-(row.value - minimum) / 20.0) }
        }

        SourceValueMode.LINEAR_FIELD -> {
            transformedRows.forEach { row ->
                codecRequire(row.value >= 0.0) {
                    "$sectionLabel linear field amplitude cannot be negative."
                }
            }
            val peak = transformedRows.maxOf(RawPatternRow::value)
            codecRequire(peak > 0.0) { "$sectionLabel cannot contain only zero field amplitudes." }
            if (abs(peak - 1.0) > NUMERIC_TOLERANCE) {
                warnings +=
                    "$sectionLabel linear field peak ${peak.compact()} was normalized to 1 E/Emax."
            }
            transformedRows.map { row -> row.value / peak }
        }

        SourceValueMode.RELATIVE_FIELD_DB -> {
            val peakDb = transformedRows.maxOf(RawPatternRow::value)
            if (abs(peakDb) > NUMERIC_TOLERANCE) {
                warnings +=
                    "$sectionLabel relative field peak ${peakDb.compact()} dB was normalized " +
                    "to 0 dB before E/Emax conversion."
            }
            transformedRows.map { row -> 10.0.pow((row.value - peakDb) / 20.0) }
        }
    }
    val fieldsByAngle = linkedMapOf<Double, MutableList<Pair<ComplexField, Double?>>>()
    transformedRows.zip(sourceAmplitudes).forEach { (row, amplitude) ->
        codecRequire(amplitude.isFinite() && amplitude in 0.0..1.0) {
            "$sectionLabel produced an invalid normalized field amplitude."
        }
        fieldsByAngle.getOrPut(row.angleDegrees) { mutableListOf() } +=
            ComplexField.fromPolar(amplitude, row.phaseDegrees ?: 0.0) to
            row.phaseDegrees
    }
    val duplicateCount = transformedRows.size - fieldsByAngle.size
    var duplicatePhaseNoDataCount = 0
    val averagedFields = fieldsByAngle.map { (angleDegrees, values) ->
        val sum = values.fold(ComplexField.ZERO) { total, (field, _) -> total + field }
        val mean = sum * (1.0 / values.size.toDouble())
        val hasNonZeroContributorWithMissingPhase = values.any { (field, phaseDegrees) ->
            field.magnitude > 0.0 && phaseDegrees == null
        }
        if (values.size > 1 && hasNonZeroContributorWithMissingPhase) {
            duplicatePhaseNoDataCount += 1
        }
        CanonicalPatternField(
            angleDegrees = angleDegrees,
            field = mean,
            phaseDegrees = when {
                values.size == 1 -> values.single().second
                hasNonZeroContributorWithMissingPhase -> null
                values.any { (_, phaseDegrees) -> phaseDegrees != null } -> mean.phaseDegrees
                else -> null
            },
        )
    }.sortedBy(CanonicalPatternField::angleDegrees)
    if (duplicateCount > 0) {
        warnings +=
            "$sectionLabel averaged $duplicateCount duplicate canonical angle(s) as complex " +
            "field vectors before canonical peak normalization."
    }
    if (duplicatePhaseNoDataCount > 0) {
        warnings +=
            "$sectionLabel retained phase NoData for $duplicatePhaseNoDataCount duplicate " +
                "canonical angle(s) because at least one non-zero contributor omitted phase."
    }
    val averagedPeak = averagedFields.maxOf { value -> value.field.magnitude }
    codecRequire(averagedPeak.isFinite() && averagedPeak > NUMERIC_TOLERANCE) {
        "$sectionLabel duplicate-angle complex averaging cancelled all usable field values."
    }
    return averagedFields.map { value ->
        val amplitude = (value.field.magnitude / averagedPeak).coerceIn(0.0, 1.0)
        PatternSample(
            angleDegrees = value.angleDegrees,
            normalizedFieldAmplitude = amplitude,
            phaseDegrees = value.phaseDegrees,
        )
    }
}

private fun requireDeclaredCount(
    rows: List<RawPatternRow>,
    heading: PrnHeading,
    label: String,
) {
    codecRequire(rows.size == heading.declaredCount) {
        "$label declares ${heading.declaredCount} samples but contains ${rows.size}."
    }
}

private fun parsePrnFrequency(
    lines: List<String>,
    warnings: MutableList<String>,
): Double? {
    val candidates = lines.mapNotNull { line -> PRN_FREQUENCY_REGEX.matchEntire(line.trim()) }
    codecRequire(candidates.size <= 1) { "PRN cannot contain multiple FREQUENCY declarations." }
    if (candidates.isEmpty()) {
        warnings += "PRN does not declare a nominal frequency."
        return null
    }
    val match = candidates.single()
    val value = parseFiniteNumber(match.groupValues[1], "PRN nominal frequency")
    val multiplier = when (match.groupValues[2].uppercase(Locale.ROOT)) {
        "", "MHZ" -> 1.0e6
        "HZ" -> 1.0
        "KHZ" -> 1.0e3
        "GHZ" -> 1.0e9
        else -> codecFailure("Unsupported PRN frequency unit.")
    }
    val frequencyHz = value * multiplier
    validateFrequency(frequencyHz, "PRN nominal frequency")
    return frequencyHz
}

private fun parsePrnName(lines: List<String>): String? {
    val candidates = lines.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("NAME ", ignoreCase = true)) trimmed.substring(5).trim() else null
    }
    codecRequire(candidates.size <= 1) { "PRN cannot contain multiple NAME declarations." }
    val name = candidates.singleOrNull() ?: return null
    validateText(name, "PRN name")
    return name
}

private fun parsePrnDeclaredGainDbi(
    lines: List<String>,
    warnings: MutableList<String>,
): Double? {
    val candidates = lines.mapNotNull { line -> PRN_GAIN_REGEX.matchEntire(line.trim()) }
    codecRequire(candidates.size <= 1) { "PRN cannot contain multiple GAIN declarations." }
    val match = candidates.singleOrNull() ?: return null
    val declaredValue = parseFiniteNumber(match.groupValues[1], "PRN declared gain")
    val declaredUnit = match.groupValues[2].uppercase(Locale.ROOT)
    val gainDbi = if (declaredUnit == "DBD") declaredValue + 2.15 else declaredValue
    warnings += if (declaredUnit == "DBD") {
        "PRN declared gain ${declaredValue.compact()} dBd was converted to " +
            "${gainDbi.compact()} dBi metadata and was not applied to normalized cuts."
    } else {
        "PRN declared gain ${gainDbi.compact()} dBi is metadata only and was not applied to " +
            "normalized cuts."
    }
    return gainDbi
}

private fun importedProvenance(
    sourceLabel: String,
    format: AntennaPatternFileFormat,
    sourceSha256: String,
    warnings: List<String>,
    limitations: List<String>,
): PatternProvenance = PatternProvenance(
    origin = PatternOrigin.IMPORTED,
    sourceLabel = sourceLabel,
    sourceFormat = format.displayName,
    sourceSha256 = sourceSha256,
    coordinateFrame = PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
    sourceCoordinateFrame = PatternCoordinateFrame.SOURCE_RELATIVE_UNSPECIFIED,
    engineId = "atx-plan-antenna-codecs-v1",
    warnings = warnings,
    limitations = limitations,
)

private fun textArtifact(
    format: AntennaPatternFileFormat,
    convention: AntennaPatternValueConvention,
    extension: String,
    text: String,
    provenance: PatternProvenance,
    warnings: List<String>,
): AntennaPatternExportArtifact {
    val payload = text.toByteArray(Charsets.UTF_8)
    ensureExportBound(payload)
    return AntennaPatternExportArtifact(
        format = format,
        valueConvention = convention,
        suggestedExtension = extension,
        mediaType = "text/plain; charset=utf-8",
        payload = payload,
        provenance = provenance,
        warnings = warnings,
    )
}

private fun ensureExportBound(payload: ByteArray) {
    codecRequire(payload.size <= AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
        "Encoded antenna pattern exceeds the 16 MiB mobile payload limit."
    }
}

private fun parseNumericFields(
    line: String,
    label: String,
    sourceLineNumber: Int,
): List<Double> {
    val fields = splitFields(line)
    codecRequire(fields.isNotEmpty()) { "$label line $sourceLineNumber is empty." }
    return fields.map { field ->
        parseFiniteNumber(field, "$label line $sourceLineNumber")
    }
}

private fun splitFields(line: String): List<String> = line.trim()
    .split(FIELD_SEPARATOR_REGEX)
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { field -> field.trim('"', '\'') }

private fun splitTableFields(line: String): List<String> {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return emptyList()
    val raw = when {
        '\t' in trimmed -> trimmed.split('\t')
        ';' in trimmed && trimmed.count { character -> character == ';' } >=
            trimmed.count { character -> character == ',' } -> trimmed.split(';')
        ',' in trimmed -> trimmed.split(',')
        else -> trimmed.split(Regex("\\s+"))
    }
    return raw.map(String::trim)
        .map { field -> field.trim('"', '\'') }
        .filter(String::isNotEmpty)
}

private fun parseOptionalTableNumericRow(line: String): List<Double>? {
    val fields = splitTableFields(line)
    if (fields.size < 2 || fields.size > MAX_GENERIC_TABLE_COLUMNS) return null
    val values = fields.map { field ->
        val normalized = if (field.count { character -> character == ',' } == 1 && '.' !in field) {
            field.replace(',', '.')
        } else {
            field
        }
        if (!NUMBER_REGEX.matches(normalized)) return null
        normalized.toDoubleOrNull()?.takeIf { value ->
            value.isFinite() && abs(value) <= MAX_GENERIC_NUMERIC_ABSOLUTE_VALUE
        } ?: return null
    }
    return values
}

private fun analyzeGenericTable(
    lines: List<String>,
    sourceLabel: String,
): GenericTableAnalysis {
    val plane = explicitGenericPlane(lines, sourceLabel)
    val blocks = mutableListOf<GenericNumericBlock>()
    var currentRows = mutableListOf<List<Double>>()
    var currentHeaders = emptyList<String>()
    var lastNonNumericFields = emptyList<String>()

    fun finishBlock() {
        if (currentRows.size >= MIN_GENERIC_TABLE_ROWS) {
            blocks += GenericNumericBlock(currentRows.toList(), currentHeaders)
        }
        currentRows = mutableListOf()
        currentHeaders = emptyList()
    }

    lines.forEach { line ->
        val numeric = parseOptionalTableNumericRow(line)
        if (numeric != null) {
            if (currentRows.isEmpty()) currentHeaders = lastNonNumericFields
            codecRequire(currentRows.size < AntennaPatternLimits.MAX_CUT_SAMPLES) {
                "A generic antenna table cannot exceed ${AntennaPatternLimits.MAX_CUT_SAMPLES} rows."
            }
            currentRows += numeric
        } else {
            finishBlock()
            splitTableFields(line).takeIf(List<String>::isNotEmpty)?.let { fields ->
                lastNonNumericFields = fields
            }
        }
    }
    finishBlock()
    codecRequire(blocks.isNotEmpty()) {
        "Could not find a bounded numeric antenna table with at least three rows."
    }
    val maximumRows = blocks.maxOf { block -> block.rows.size }
    val largest = blocks.filter { block -> block.rows.size == maximumRows }
    codecRequire(largest.size == 1) {
        "The antenna file contains multiple equally sized numeric tables; the source is ambiguous."
    }
    val selected = largest.single()
    val columnCounts = selected.rows.groupingBy(List<Double>::size).eachCount()
    val maximumOccurrence = columnCounts.maxOf(Map.Entry<Int, Int>::value)
    val dominantCounts = columnCounts.filterValues { count -> count == maximumOccurrence }.keys
    codecRequire(dominantCounts.size == 1) {
        "The generic antenna table has no unambiguous column count."
    }
    val columnCount = dominantCounts.single()
    codecRequire(columnCount in 2..MAX_GENERIC_TABLE_COLUMNS) {
        "A generic antenna table must contain between 2 and $MAX_GENERIC_TABLE_COLUMNS columns."
    }
    val rows = selected.rows.filter { row -> row.size >= columnCount }
        .map { row -> row.take(columnCount) }
    codecRequire(rows.size >= MIN_GENERIC_TABLE_ROWS) {
        "The generic antenna table does not retain at least three consistent rows."
    }
    val headers = selected.headers.take(columnCount) +
        List((columnCount - selected.headers.size).coerceAtLeast(0)) { "" }
    val angleColumn = inferGenericAngleColumn(rows, headers, plane)
    val magnitudeColumn = inferGenericMagnitudeColumn(rows, headers, angleColumn)
    val phaseColumn = inferGenericPhaseColumn(headers, angleColumn, magnitudeColumn)
    val magnitudeHeader = headers.getOrElse(magnitudeColumn) { "" }.lowercase(Locale.ROOT)
    val magnitudeValues = rows.map { row -> row[magnitudeColumn] }
    val linear = headerHasAny(
        magnitudeHeader,
        "10^",
        "linear",
        "magnitude",
        "field",
        "e/emax",
        "e / emax",
    ) || (
        !headerHasAny(magnitudeHeader, "db", "gain", "power", "response") &&
            magnitudeValues.all { value -> value >= 0.0 } &&
            magnitudeValues.max() <= 1.5
        )
    return GenericTableAnalysis(
        sourceLines = lines,
        plane = plane,
        rows = rows,
        headers = headers,
        angleColumn = angleColumn,
        magnitudeColumn = magnitudeColumn,
        phaseColumn = phaseColumn,
        valueMode = if (linear) SourceValueMode.LINEAR_FIELD else SourceValueMode.RELATIVE_FIELD_DB,
        valueConvention = if (linear && phaseColumn != null) {
            AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE_WITH_OPTIONAL_PHASE
        } else if (linear) {
            AntennaPatternValueConvention.NORMALIZED_FIELD_AMPLITUDE
        } else {
            AntennaPatternValueConvention.RELATIVE_FIELD_DB_20_LOG10
        },
    )
}

private fun explicitGenericPlane(
    lines: List<String>,
    sourceLabel: String,
): PatternCutPlane {
    val candidates = linkedSetOf<PatternCutPlane>()
    val fileName = sourceLabel.substringAfterLast('/').substringAfterLast('\\')
    val suffix = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    when (suffix) {
        "hrp", "hup" -> candidates += PatternCutPlane.HORIZONTAL
        "vrp", "vup" -> candidates += PatternCutPlane.VERTICAL
    }
    val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val stemTokens = semanticTokens(stem)
    if (stemTokens.any { token -> token in GENERIC_HORIZONTAL_FILE_TOKENS }) {
        candidates += PatternCutPlane.HORIZONTAL
    }
    if (stemTokens.any { token -> token in GENERIC_VERTICAL_FILE_TOKENS }) {
        candidates += PatternCutPlane.VERTICAL
    }
    lines.asSequence()
        .filter { line -> parseOptionalTableNumericRow(line) == null }
        .forEach { line ->
            val tokens = semanticTokens(line)
            if (tokens.any { token -> token in GENERIC_HORIZONTAL_HEADER_TOKENS }) {
                candidates += PatternCutPlane.HORIZONTAL
            }
            if (tokens.any { token -> token in GENERIC_VERTICAL_HEADER_TOKENS }) {
                candidates += PatternCutPlane.VERTICAL
            }
        }
    codecRequire(candidates.size == 1) {
        if (candidates.isEmpty()) {
            "Unsupported or ambiguous generic antenna table plane; use an explicit HRP/VRP suffix or " +
                "a Horizontal/Azimuth or Vertical/Elevation header."
        } else {
            "Generic antenna table plane markers conflict; exactly one HRP or VRP plane is required."
        }
    }
    return candidates.single()
}

private fun semanticTokens(value: String): Set<String> = value.uppercase(Locale.ROOT)
    .replace(Regex("[^A-Z0-9]+"), " ")
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .toSet()

private fun inferGenericAngleColumn(
    rows: List<List<Double>>,
    headers: List<String>,
    plane: PatternCutPlane,
): Int {
    val candidates = rows.first().indices.mapNotNull { index ->
        val values = rows.map { row -> row[index] }
        val uniqueCount = values.map { value -> round(value * 1.0e6) / 1.0e6 }.distinct().size
        val minimum = values.min()
        val maximum = values.max()
        val range = maximum - minimum
        if (uniqueCount < 3 || range < 1.0 || minimum < -400.0 || maximum > 400.0) {
            return@mapNotNull null
        }
        val header = headers.getOrElse(index) { "" }.lowercase(Locale.ROOT)
        if (headerHasAny(header, "freq", "frequency")) return@mapNotNull null
        val nonZeroDifferences = values.zipWithNext { first, second -> second - first }
            .filter { difference -> abs(difference) > NUMERIC_TOLERANCE }
        val monotonicRatio = if (nonZeroDifferences.isEmpty()) {
            0.0
        } else {
            maxOf(
                nonZeroDifferences.count { difference -> difference > 0.0 },
                nonZeroDifferences.count { difference -> difference < 0.0 },
            ).toDouble() / nonZeroDifferences.size
        }
        var score = uniqueCount * 5.0 + range + monotonicRatio * 50.0
        if (headerHasAny(header, "theta", "phi", "angle", "az", "el", "azimuth", "elevation")) {
            score += 120.0
        }
        if (plane == PatternCutPlane.HORIZONTAL) {
            if (range >= 300.0) score += 100.0
            if (minimum in -185.0..0.0 && maximum in 0.0..360.0) score += 50.0
        } else {
            if (minimum in -90.1..0.0 && maximum in 0.0..90.1) score += 140.0
            if (minimum >= 0.0 && maximum <= 180.1) score += 100.0
        }
        index to score
    }.sortedByDescending(Pair<Int, Double>::second)
    codecRequire(candidates.isNotEmpty()) {
        "Could not infer a bounded angular column from the generic antenna table."
    }
    if (candidates.size > 1 && abs(candidates[0].second - candidates[1].second) <= NUMERIC_TOLERANCE) {
        codecFailure("The generic antenna table has ambiguous angular columns.")
    }
    return candidates.first().first
}

private fun inferGenericMagnitudeColumn(
    rows: List<List<Double>>,
    headers: List<String>,
    angleColumn: Int,
): Int {
    val candidates = rows.first().indices.mapNotNull { index ->
        if (index == angleColumn) return@mapNotNull null
        val values = rows.map { row -> row[index] }
        val minimum = values.min()
        val maximum = values.max()
        val range = maximum - minimum
        if (range < NUMERIC_TOLERANCE) return@mapNotNull null
        val mean = values.average()
        val standardDeviation = kotlin.math.sqrt(
            values.sumOf { value -> (value - mean) * (value - mean) } / values.size,
        )
        if (standardDeviation < NUMERIC_TOLERANCE) return@mapNotNull null
        val header = headers.getOrElse(index) { "" }.lowercase(Locale.ROOT)
        if (headerHasAny(header, "phase", "freq", "frequency")) return@mapNotNull null
        var score = standardDeviation * 10.0 + range
        if (headerHasAny(header, "mag", "field", "10^", "e/emax", "e / emax", "linear")) {
            score += 300.0
        } else if (headerHasAny(header, "gain", "db", "power", "response")) {
            score += 120.0
        }
        if (values.all { value -> value >= 0.0 } && maximum <= 1.5) {
            score += 90.0
        } else if (maximum <= 40.0 && minimum >= -200.0) {
            score += 80.0
        }
        if (minimum < -360.0 || maximum > 360.0) score -= 40.0
        index to score
    }.sortedByDescending(Pair<Int, Double>::second)
    codecRequire(candidates.isNotEmpty()) {
        "Could not infer a magnitude column from the generic antenna table."
    }
    if (candidates.size > 1 && abs(candidates[0].second - candidates[1].second) <= NUMERIC_TOLERANCE) {
        codecFailure("The generic antenna table has ambiguous magnitude columns.")
    }
    return candidates.first().first
}

private fun inferGenericPhaseColumn(
    headers: List<String>,
    angleColumn: Int,
    magnitudeColumn: Int,
): Int? {
    val candidates = headers.indices.filter { index ->
        index !in setOf(angleColumn, magnitudeColumn) &&
            headerHasAny(headers[index].lowercase(Locale.ROOT), "phase")
    }
    codecRequire(candidates.size <= 1) {
        "A generic antenna table cannot contain multiple explicit phase columns."
    }
    return candidates.singleOrNull()
}

private fun headerHasAny(header: String, vararg tokens: String): Boolean =
    tokens.any { token -> token in header }

private fun genericVerticalAngleMode(rawAngles: List<Double>): GenericVerticalAngleMode {
    val minimum = rawAngles.min()
    val maximum = rawAngles.max()
    return when {
        minimum >= 0.0 && maximum <= 180.0 -> GenericVerticalAngleMode.ZERO_TO_180
        minimum >= 0.0 && maximum <= 360.0 -> GenericVerticalAngleMode.WRAPPED_360
        else -> GenericVerticalAngleMode.SIGNED
    }
}

private fun parseGenericFrequencyHz(
    table: GenericTableAnalysis,
    warnings: MutableList<String>,
): Double? {
    val candidates = mutableListOf<Double>()
    table.sourceLines.take(20).forEach { line ->
        if (!line.contains("frequency", ignoreCase = true)) return@forEach
        val number = NUMBER_SEARCH_REGEX.find(line)?.value?.toDoubleOrNull() ?: return@forEach
        val unitMultiplier = frequencyUnitMultiplier(line, defaultMegahertz = true)
        candidates += number * unitMultiplier
    }
    table.headers.forEachIndexed { index, header ->
        if (!headerHasAny(header.lowercase(Locale.ROOT), "freq", "frequency")) return@forEachIndexed
        val values = table.rows.map { row -> row[index] }
        if (values.max() - values.min() > NUMERIC_TOLERANCE) return@forEachIndexed
        val hasUnit = FREQUENCY_UNIT_REGEX.containsMatchIn(header)
        if (!hasUnit) {
            warnings +=
                "A constant generic frequency column had no unit and was interpreted as MHz " +
                "following the desktop table convention."
        }
        candidates += values.first() * frequencyUnitMultiplier(header, defaultMegahertz = true)
    }
    if (candidates.isEmpty()) return null
    candidates.forEach { frequencyHz -> validateFrequency(frequencyHz, "Generic table nominal frequency") }
    val first = candidates.first()
    codecRequire(candidates.all { frequencyHz -> abs(frequencyHz - first) <= maxOf(1.0, first * 1.0e-9) }) {
        "The generic antenna table contains conflicting nominal frequency declarations."
    }
    return first
}

private fun frequencyUnitMultiplier(
    source: String,
    defaultMegahertz: Boolean,
): Double {
    val uppercase = source.uppercase(Locale.ROOT)
    return when {
        "GHZ" in uppercase -> 1.0e9
        "MHZ" in uppercase -> 1.0e6
        "KHZ" in uppercase -> 1.0e3
        Regex("(^|[^A-Z])HZ([^A-Z]|$)").containsMatchIn(uppercase) -> 1.0
        defaultMegahertz -> 1.0e6
        else -> 1.0
    }
}

private fun resampleCanonicalSamples(
    plane: PatternCutPlane,
    samples: List<PatternSample>,
    provenance: PatternProvenance,
    preservePhase: Boolean,
): List<PatternSample> {
    val source = AntennaPatternCut(
        plane = plane,
        samples = samples,
        provenance = provenance,
        availability = PatternCutAvailability.AVAILABLE,
    )
    val targetAngles = if (plane == PatternCutPlane.HORIZONTAL) {
        (0 until 360).map(Int::toDouble)
    } else {
        (0..1800).map { index -> -90.0 + index * 0.1 }
    }
    val fields = targetAngles.map(source::complexFieldAt)
    val peak = fields.maxOf { field -> field.magnitude }
    codecRequire(peak.isFinite() && peak > 0.0) {
        "The interpolated antenna table cannot contain only zero field amplitudes."
    }
    return targetAngles.indices.map { index ->
        val field = fields[index]
        PatternSample(
            angleDegrees = normalizeZero(targetAngles[index]),
            normalizedFieldAmplitude = (field.magnitude / peak).coerceIn(0.0, 1.0),
            phaseDegrees = field.phaseDegrees.takeIf { preservePhase },
        )
    }
}

private fun normalizeEvaluatedMagnitudes(
    cut: AntennaPatternCut,
    targetAngles: List<Double>,
    label: String,
): List<Double> {
    val magnitudes = targetAngles.map { angle -> cut.complexFieldAt(angle).magnitude }
    val peak = magnitudes.max()
    codecRequire(peak.isFinite() && peak > 0.0) {
        "$label cannot export only zero interpolated field magnitudes."
    }
    return magnitudes.map { magnitude -> (magnitude / peak).coerceIn(0.0, 1.0) }
}

private fun parseSingleFiniteNumber(line: String, label: String): Double {
    val fields = splitFields(line)
    codecRequire(fields.size == 1) { "$label must contain exactly one numeric value." }
    return parseFiniteNumber(fields.single(), label)
}

private fun parseFiniteNumber(token: String, label: String): Double {
    codecRequire(NUMBER_REGEX.matches(token)) { "$label contains an invalid numeric value '$token'." }
    val value = token.toDoubleOrNull() ?: codecFailure("$label contains an invalid numeric value.")
    requireFinite(value, label)
    return value
}

private fun requireFinite(value: Double, label: String) {
    codecRequire(value.isFinite()) { "$label must be finite; NaN and Infinity are not accepted." }
}

private fun parseBoundedCount(token: String, label: String): Int {
    codecRequire(token.matches(Regex("[0-9]+"))) { "$label must be a positive integer." }
    val value = token.toIntOrNull() ?: codecFailure("$label is outside the supported integer range.")
    codecRequire(value in 2..AntennaPatternLimits.MAX_CUT_SAMPLES) {
        "$label must be between 2 and ${AntennaPatternLimits.MAX_CUT_SAMPLES}."
    }
    return value
}

private fun boundedCount(value: Double, label: String): Int {
    requireFinite(value, label)
    codecRequire(value % 1.0 == 0.0) { "$label must be an integer." }
    val integer = value.toInt()
    codecRequire(integer.toDouble() == value) { "$label is outside the supported integer range." }
    codecRequire(integer in 2..AntennaPatternLimits.MAX_CUT_SAMPLES) {
        "$label must be between 2 and ${AntennaPatternLimits.MAX_CUT_SAMPLES}."
    }
    return integer
}

private fun nextContentLine(lines: List<String>, startIndex: Int): Int? =
    (startIndex until lines.size).firstOrNull { index -> lines[index].isContentLine() }

private fun String.isContentLine(): Boolean {
    val trimmed = trim()
    return trimmed.isNotEmpty() && !trimmed.startsWith('#')
}

private fun normalizeHorizontalAngle(angleDegrees: Double): Double {
    val wrapped = ((angleDegrees % 360.0) + 360.0) % 360.0
    return normalizeZero(wrapped)
}

private fun normalizeZero(value: Double): Double = if (value == 0.0) 0.0 else value

private fun validateFrequency(frequencyHz: Double, label: String) {
    codecRequire(
        frequencyHz.isFinite() &&
            frequencyHz in AntennaPatternLimits.MIN_FREQUENCY_HZ..AntennaPatternLimits.MAX_FREQUENCY_HZ,
    ) {
        "$label must be finite and between ${AntennaPatternLimits.MIN_FREQUENCY_HZ.compact()} " +
            "and ${AntennaPatternLimits.MAX_FREQUENCY_HZ.compact()} Hz."
    }
}

private fun validateSourceLabel(sourceLabel: String) = validateText(sourceLabel, "Source label")

private fun validateText(value: String, label: String) {
    codecRequire(value.isNotBlank() && value.length <= AntennaPatternLimits.MAX_TEXT_LENGTH) {
        "$label must be non-blank and no longer than ${AntennaPatternLimits.MAX_TEXT_LENGTH} characters."
    }
    codecRequire(value.none(Char::isISOControl)) { "$label cannot contain control characters." }
}

private fun sourceName(sourceLabel: String): String {
    val fileName = sourceLabel.substringAfterLast('/').substringAfterLast('\\')
    val withoutExtension = fileName.substringBeforeLast('.', missingDelimiterValue = fileName).trim()
    return withoutExtension.ifBlank { "Imported antenna pattern" }
}

private fun importedPatternId(sourceSha256: String): String = "import-${sourceSha256.take(16)}"

private fun headerValue(value: String): String {
    validateText(value, "Antenna pattern header value")
    return value.replace(';', '_').replace('\t', ' ').trim()
}

private fun CanonicalAntennaPattern.hasPhaseSamples(): Boolean =
    horizontalCut.samples.any { sample -> sample.phaseDegrees != null } ||
        verticalCut.samples.any { sample -> sample.phaseDegrees != null }

private fun requireCompletePatternForExport(pattern: CanonicalAntennaPattern) {
    codecRequire(pattern.isCalculationReady) {
        "Antenna export requires explicitly available HRP and VRP cuts; display placeholders " +
            "and legacy cut availability cannot be exported."
    }
}

private fun requireAvailableCutForExport(cut: AntennaPatternCut) {
    codecRequire(cut.availability == PatternCutAvailability.AVAILABLE) {
        "Antenna cut export requires explicit AVAILABLE status; display placeholders and legacy " +
            "cut availability cannot be exported."
    }
}

private fun Double.fixed(digits: Int): String =
    String.format(Locale.US, "%.${digits}f", normalizeZero(this))

private fun Double.compact(): String = String.format(Locale.US, "%.12g", normalizeZero(this))

private fun sha256Hex(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(payload)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private inline fun codecRequire(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) codecFailure(lazyMessage())
}

private fun requireNoPrnOverrideForNonPrn(override: PrnValueConventionOverride?) {
    codecRequire(override == null) {
        "A PRN value convention override can only be applied to a detected PRN payload."
    }
}

private fun codecFailure(message: String): Nothing = throw AntennaPatternCodecException(message)

private const val CANONICAL_JSON_FORMAT = "ATX Antenna JSON"
private const val CANONICAL_JSON_VALUE_CONVENTION = "normalized-field-amplitude-e-over-emax"
private const val CANONICAL_JSON_ANGLE_UNIT = "degree"
private const val CANONICAL_JSON_PHASE_UNIT = "degree"
private const val CANONICAL_JSON_FREQUENCY_UNIT = "Hz"
private const val CANONICAL_JSON_LEGACY_SCHEMA_VERSION = 1
private const val CANONICAL_JSON_CURRENT_SCHEMA_VERSION = 2
private const val MAX_JSON_NESTING_DEPTH = 32
private const val MAX_JSON_LEXICAL_TOKENS = 400_000
private const val MAX_JSON_STRING_TOKEN_CHARACTERS = 4_096
private const val MAX_JSON_NUMBER_TOKEN_CHARACTERS = 64
private const val MAX_JSON_TOTAL_SAMPLE_DECLARATIONS = 20_000
private const val CANONICAL_JSON_SAMPLE_KEY = "angleDegrees"
private const val DESKTOP_JSON_SAMPLE_KEY = "angle_deg"
private const val JSON_TOKEN_DELIMITERS = "{}[],:\""
private val CANONICAL_JSON_SUPPORTED_SCHEMA_VERSIONS = setOf(
    CANONICAL_JSON_LEGACY_SCHEMA_VERSION,
    CANONICAL_JSON_CURRENT_SCHEMA_VERSION,
)
private const val DESKTOP_JSON_FORMAT = "atx-antenna-pattern"
private const val DESKTOP_JSON_VERSION = 1
private const val DESKTOP_JSON_HORIZONTAL_PLANE = "horizontal"
private const val DESKTOP_JSON_VERTICAL_PLANE = "vertical"
private const val DESKTOP_JSON_HORIZONTAL_ANGLE_CONVENTION =
    "azimuth-clockwise-from-north-0-360"
private const val DESKTOP_JSON_VERTICAL_ANGLE_CONVENTION =
    "elevation-positive-up-minus90-plus90"
private const val DESKTOP_JSON_UNKNOWN_POLARIZATION = "unknown"
private const val DESKTOP_JSON_MAX_NAME_CHARACTERS = 160
private const val DESKTOP_JSON_MIN_GAIN_DBI = -100.0
private const val DESKTOP_JSON_MAX_GAIN_DBI = 100.0
private const val DESKTOP_JSON_MAX_ATTENUATION_DB = 400.0
private const val DESKTOP_JSON_NORMALIZATION_TOLERANCE_DB = 1.0e-6
private const val DESKTOP_JSON_FIELD_FLOOR = 1.0e-20
private const val PRN_FIELD_FLOOR = 1.0e-15
private const val PRN_ATTENUATION_FLOOR_DB = 300.0
private const val NUMERIC_TOLERANCE = 1.0e-9
private const val VSOFT_MAXIMUM_TOLERANCE = 1.0e-12
private const val VSOFT_HRP_SAMPLE_COUNT = 360
private const val VSOFT_HRP_HEADER = "360,0,1"
private const val VSOFT_VRP_HEADER =
    "Generated by EFTX ADT Elevation Pattern for use in V-Soft software"
private const val VSOFT_VRP_DETECTION_TEXT = "elevation pattern for use in v-soft software"
private const val MIN_GENERIC_TABLE_ROWS = 3
private const val MAX_GENERIC_TABLE_COLUMNS = 32
private const val MAX_GENERIC_NUMERIC_ABSOLUTE_VALUE = 1.0e12
private const val NUMBER_PATTERN = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?"

private val NUMBER_REGEX = Regex("^$NUMBER_PATTERN$")
private val NUMBER_SEARCH_REGEX = Regex(NUMBER_PATTERN)
private val FREQUENCY_UNIT_REGEX = Regex("\\b(?:HZ|KHZ|MHZ|GHZ)\\b", RegexOption.IGNORE_CASE)
private val VSOFT_BEAM_TILT_REGEX = Regex(
    "^Beam\\s+Tilt\\s*=\\s*($NUMBER_PATTERN)\\s*$",
    RegexOption.IGNORE_CASE,
)
private val FIELD_SEPARATOR_REGEX = Regex("[\\s,;]+")
private val GENERIC_HORIZONTAL_FILE_TOKENS = setOf(
    "HRP",
    "HORIZONTAL",
    "AZIMUTH",
    "HPOL",
)
private val GENERIC_VERTICAL_FILE_TOKENS = setOf(
    "VRP",
    "VERTICAL",
    "ELEVATION",
    "VPOL",
)
private val GENERIC_HORIZONTAL_HEADER_TOKENS = setOf("HRP", "HORIZONTAL", "AZIMUTH")
private val GENERIC_VERTICAL_HEADER_TOKENS = setOf("VRP", "VERTICAL", "ELEVATION")
private val PRN_SECTION_REGEX = Regex(
    "^(HORIZONTAL|VERTICAL)\\s+([0-9]+)$",
    RegexOption.IGNORE_CASE,
)
private val PRN_VALUE_CONVENTION_REGEX = Regex(
    "^VALUE_CONVENTION\\s+(\\S+)$",
    RegexOption.IGNORE_CASE,
)
private val PRN_ATTENUATION_UNIT_REGEX = Regex(
    "^ATTENUATION_UNIT\\s+DB$",
    RegexOption.IGNORE_CASE,
)
private val PRN_NATIVE_ATTENUATION_MARKERS = setOf("H_WIDTH", "V_WIDTH", "FRONT_TO_BACK")
private val PRN_FREQUENCY_REGEX = Regex(
    "^FREQUENCY\\s+($NUMBER_PATTERN)\\s*(HZ|KHZ|MHZ|GHZ)?$",
    RegexOption.IGNORE_CASE,
)
private val PRN_GAIN_REGEX = Regex(
    "^GAIN\\s+($NUMBER_PATTERN)\\s*(DBI|DBD)$",
    RegexOption.IGNORE_CASE,
)
private val ADT_PATTERN_TYPE_REGEX = Regex(
    "pattern_type\\s*:\\s*(HRP|VRP)",
    RegexOption.IGNORE_CASE,
)
private val ADT_HORIZONTAL_LABEL_REGEX = Regex(
    "(^|[^A-Z0-9])(HRP|HORIZONTAL|AZIMUTH)([^A-Z0-9]|$)",
    RegexOption.IGNORE_CASE,
)
private val ADT_VERTICAL_LABEL_REGEX = Regex(
    "(^|[^A-Z0-9])(VRP|VERTICAL|ELEVATION)([^A-Z0-9]|$)",
    RegexOption.IGNORE_CASE,
)

private val canonicalJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
    prettyPrint = false
}

/** Reads only the root discriminator and skips untrusted schema bodies without materializing them. */
private val jsonFormatDiscriminator = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    isLenient = false
    allowSpecialFloatingPointValues = false
}

@Serializable
private data class AntennaJsonFormatDiscriminator(
    val format: String,
)

@Serializable
private data class DesktopAntennaJsonV1(
    val format: String,
    val version: Int,
    val name: String,
    @SerialName("nominal_frequency_hz")
    val nominalFrequencyHz: Double,
    @SerialName("gain_dbi")
    val gainDbi: Double,
    val source: DesktopJsonSource,
    val cuts: List<DesktopJsonCut>,
) {
    fun validateEnvelope() {
        codecRequire(format == DESKTOP_JSON_FORMAT && version == DESKTOP_JSON_VERSION) {
            "Only format '$DESKTOP_JSON_FORMAT' version $DESKTOP_JSON_VERSION is supported."
        }
        validateText(name, "ATX Planner desktop JSON v1 name")
        codecRequire(name.length <= DESKTOP_JSON_MAX_NAME_CHARACTERS) {
            "ATX Planner desktop JSON v1 name cannot exceed " +
                "$DESKTOP_JSON_MAX_NAME_CHARACTERS characters."
        }
        validateFrequency(
            nominalFrequencyHz,
            "ATX Planner desktop JSON v1 nominal frequency",
        )
        requireFinite(gainDbi, "ATX Planner desktop JSON v1 declared gain")
        codecRequire(gainDbi in DESKTOP_JSON_MIN_GAIN_DBI..DESKTOP_JSON_MAX_GAIN_DBI) {
            "ATX Planner desktop JSON v1 declared gain must be in " +
                "[$DESKTOP_JSON_MIN_GAIN_DBI, $DESKTOP_JSON_MAX_GAIN_DBI] dBi."
        }
        validateDesktopSource(source)
        codecRequire(cuts.size in 1..2) {
            "ATX Planner desktop JSON v1 must contain one or two cuts."
        }
        codecRequire(cuts.map(DesktopJsonCut::plane).distinct().size == cuts.size) {
            "ATX Planner desktop JSON v1 cannot contain duplicate cut planes."
        }
        cuts.forEach(DesktopJsonCut::validateContract)
    }
}

@Serializable
private data class DesktopJsonSource(
    val format: String,
    val sha256: String,
)

@Serializable
private data class DesktopJsonCut(
    val plane: String,
    @SerialName("angle_convention")
    val angleConvention: String,
    val polarization: String,
    val samples: List<DesktopJsonSample>,
) {
    fun validateContract() {
        val canonicalPlane = canonicalPlane()
        val expectedConvention = when (canonicalPlane) {
            PatternCutPlane.HORIZONTAL -> DESKTOP_JSON_HORIZONTAL_ANGLE_CONVENTION
            PatternCutPlane.VERTICAL -> DESKTOP_JSON_VERTICAL_ANGLE_CONVENTION
        }
        codecRequire(angleConvention == expectedConvention) {
            "ATX Planner desktop JSON v1 $plane angle_convention must be " +
                "'$expectedConvention'."
        }
        validateText(polarization, "ATX Planner desktop JSON v1 $plane polarization")
        codecRequire(samples.size in 2..AntennaPatternLimits.MAX_CUT_SAMPLES) {
            "ATX Planner desktop JSON v1 $plane cut must contain between 2 and " +
                "${AntennaPatternLimits.MAX_CUT_SAMPLES} samples."
        }
        samples.forEach { sample -> sample.validateContract(canonicalPlane, plane) }
        samples.zipWithNext().forEach { (first, second) ->
            codecRequire(first.angleDegrees < second.angleDegrees) {
                "ATX Planner desktop JSON v1 $plane sample angles must be strictly increasing " +
                    "and unique."
            }
        }
        codecRequire(
            samples.minOf(DesktopJsonSample::attenuationDb) <=
                DESKTOP_JSON_NORMALIZATION_TOLERANCE_DB,
        ) {
            "ATX Planner desktop JSON v1 $plane attenuation must have a 0 dB normalized peak."
        }
    }

    fun toCanonicalSamples(warnings: MutableList<String>): Pair<PatternCutPlane, List<PatternSample>> {
        val canonicalPlane = canonicalPlane()
        val canonical = canonicalizeRows(
            rows = samples.map { sample ->
                RawPatternRow(
                    angleDegrees = sample.angleDegrees,
                    value = sample.attenuationDb,
                    phaseDegrees = sample.phaseDegrees,
                )
            },
            plane = canonicalPlane,
            sectionLabel = "ATX Planner desktop JSON v1 ${canonicalPlane.name}",
            valueMode = SourceValueMode.POSITIVE_ATTENUATION_DB,
            warnings = warnings,
            angleTransform = { angle -> angle },
        )
        return canonicalPlane to canonical
    }

    private fun canonicalPlane(): PatternCutPlane = when (plane) {
        DESKTOP_JSON_HORIZONTAL_PLANE -> PatternCutPlane.HORIZONTAL
        DESKTOP_JSON_VERTICAL_PLANE -> PatternCutPlane.VERTICAL
        else -> codecFailure(
            "ATX Planner desktop JSON v1 cut plane must be '$DESKTOP_JSON_HORIZONTAL_PLANE' " +
                "or '$DESKTOP_JSON_VERTICAL_PLANE'.",
        )
    }
}

@Serializable
private data class DesktopJsonSample(
    @SerialName("angle_deg")
    val angleDegrees: Double,
    @SerialName("attenuation_db")
    val attenuationDb: Double,
    @SerialName("phase_deg")
    val phaseDegrees: Double,
) {
    fun validateContract(
        plane: PatternCutPlane,
        planeLabel: String,
    ) {
        requireFinite(angleDegrees, "ATX Planner desktop JSON v1 $planeLabel sample angle")
        requireFinite(
            attenuationDb,
            "ATX Planner desktop JSON v1 $planeLabel sample attenuation",
        )
        requireFinite(phaseDegrees, "ATX Planner desktop JSON v1 $planeLabel sample phase")
        codecRequire(attenuationDb in 0.0..DESKTOP_JSON_MAX_ATTENUATION_DB) {
            "ATX Planner desktop JSON v1 $planeLabel attenuation must be in " +
                "[0, $DESKTOP_JSON_MAX_ATTENUATION_DB] dB."
        }
        when (plane) {
            PatternCutPlane.HORIZONTAL -> codecRequire(angleDegrees >= 0.0 && angleDegrees < 360.0) {
                "ATX Planner desktop JSON v1 horizontal angles must be in [0, 360) degrees."
            }

            PatternCutPlane.VERTICAL -> codecRequire(angleDegrees in -90.0..90.0) {
                "ATX Planner desktop JSON v1 vertical angles must be in [-90, 90] degrees."
            }
        }
    }
}

private fun validateDesktopSource(source: DesktopJsonSource) {
    validateText(source.format, "ATX Planner desktop JSON v1 source format")
    codecRequire(source.sha256.matches(Regex("[0-9a-f]{64}"))) {
        "ATX Planner desktop JSON v1 source SHA-256 must contain 64 lowercase hexadecimal " +
            "characters."
    }
}

@Serializable
private data class CanonicalAntennaJson(
    val format: String,
    val schemaVersion: Int,
    val valueConvention: String,
    val angleUnit: String,
    val phaseUnit: String,
    val frequencyUnit: String,
    val id: String,
    val name: String,
    val nominalFrequencyHz: Double?,
    val provenance: JsonProvenance,
    val horizontalCut: JsonCut,
    val verticalCut: JsonCut,
    val metadata: JsonPatternMetadata? = null,
) {
    fun toDomain(): DecodedCanonicalAntennaJson {
        codecRequire(
            format == CANONICAL_JSON_FORMAT &&
                schemaVersion in CANONICAL_JSON_SUPPORTED_SCHEMA_VERSIONS,
        ) {
            "Only ATX Antenna JSON schema versions 1 and 2 are supported."
        }
        codecRequire(valueConvention == CANONICAL_JSON_VALUE_CONVENTION) {
            "ATX Antenna JSON valueConvention must be '$CANONICAL_JSON_VALUE_CONVENTION'."
        }
        codecRequire(angleUnit == CANONICAL_JSON_ANGLE_UNIT) {
            "ATX Antenna JSON angleUnit must be '$CANONICAL_JSON_ANGLE_UNIT'."
        }
        codecRequire(phaseUnit == CANONICAL_JSON_PHASE_UNIT) {
            "ATX Antenna JSON phaseUnit must be '$CANONICAL_JSON_PHASE_UNIT'."
        }
        codecRequire(frequencyUnit == CANONICAL_JSON_FREQUENCY_UNIT) {
            "ATX Antenna JSON frequencyUnit must be '$CANONICAL_JSON_FREQUENCY_UNIT'."
        }
        val requiresExplicitAvailability = schemaVersion == CANONICAL_JSON_CURRENT_SCHEMA_VERSION
        val patternProvenance = provenance.toDomain()
        val horizontal = horizontalCut.toDomain(
            expectedPlane = PatternCutPlane.HORIZONTAL,
            requireExplicitAvailability = requiresExplicitAvailability,
        )
        val vertical = verticalCut.toDomain(
            expectedPlane = PatternCutPlane.VERTICAL,
            requireExplicitAvailability = requiresExplicitAvailability,
        )
        val decodedMetadata = when (schemaVersion) {
            CANONICAL_JSON_LEGACY_SCHEMA_VERSION -> {
                codecRequire(metadata == null) {
                    "ATX Antenna JSON v1 cannot contain the schema-v2 metadata envelope."
                }
                AntennaPatternFileMetadata(nominalFrequencyHz = nominalFrequencyHz)
            }

            CANONICAL_JSON_CURRENT_SCHEMA_VERSION -> {
                val requiredMetadata = metadata ?: codecFailure(
                    "ATX Antenna JSON v2 requires an explicit metadata object.",
                )
                requiredMetadata.toDomain(nominalFrequencyHz)
            }

            else -> codecFailure("Unsupported ATX Antenna JSON schema version $schemaVersion.")
        }
        return DecodedCanonicalAntennaJson(
            pattern = CanonicalAntennaPattern(
                id = id,
                name = name,
                horizontalCut = horizontal,
                verticalCut = vertical,
                provenance = patternProvenance,
                nominalFrequencyHz = nominalFrequencyHz,
            ),
            metadata = decodedMetadata,
        )
    }

    companion object {
        fun fromDomain(
            pattern: CanonicalAntennaPattern,
            metadata: AntennaPatternFileMetadata,
        ): CanonicalAntennaJson =
            CanonicalAntennaJson(
                format = CANONICAL_JSON_FORMAT,
                schemaVersion = CANONICAL_JSON_CURRENT_SCHEMA_VERSION,
                valueConvention = CANONICAL_JSON_VALUE_CONVENTION,
                angleUnit = CANONICAL_JSON_ANGLE_UNIT,
                phaseUnit = CANONICAL_JSON_PHASE_UNIT,
                frequencyUnit = CANONICAL_JSON_FREQUENCY_UNIT,
                id = pattern.id,
                name = pattern.name,
                nominalFrequencyHz = pattern.nominalFrequencyHz,
                provenance = JsonProvenance.fromDomain(pattern.provenance),
                horizontalCut = JsonCut.fromDomain(pattern.horizontalCut),
                verticalCut = JsonCut.fromDomain(pattern.verticalCut),
                metadata = JsonPatternMetadata.fromDomain(metadata),
            )
    }
}

private data class DecodedCanonicalAntennaJson(
    val pattern: CanonicalAntennaPattern,
    val metadata: AntennaPatternFileMetadata,
)

@Serializable
private data class JsonPatternMetadata(
    /** Peak or declared gain from the source, in dBi. Null is explicit NoData. */
    val peakGainDbi: Double?,
    /** Azimuth of the represented vertical cut, in degrees. Null means not declared. */
    val verticalCutAzimuthDegrees: Double?,
    /** Electrical or declared beam tilt from the source, in degrees. Null means not declared. */
    val beamTiltDegrees: Double?,
) {
    fun toDomain(nominalFrequencyHz: Double?): AntennaPatternFileMetadata =
        AntennaPatternFileMetadata(
            nominalFrequencyHz = nominalFrequencyHz,
            declaredGainDbi = peakGainDbi,
            verticalCutAzimuthDegrees = verticalCutAzimuthDegrees,
            beamTiltDegrees = beamTiltDegrees,
        )

    companion object {
        fun fromDomain(metadata: AntennaPatternFileMetadata): JsonPatternMetadata =
            JsonPatternMetadata(
                peakGainDbi = metadata.declaredGainDbi,
                verticalCutAzimuthDegrees = metadata.verticalCutAzimuthDegrees,
                beamTiltDegrees = metadata.beamTiltDegrees,
            )
    }
}

@Serializable
private data class JsonCut(
    val plane: String,
    val provenance: JsonProvenance,
    val samples: List<JsonSample>,
    val availability: String? = null,
) {
    fun toDomain(
        expectedPlane: PatternCutPlane,
        requireExplicitAvailability: Boolean,
    ): AntennaPatternCut {
        codecRequire(plane == expectedPlane.name) {
            "JSON ${expectedPlane.name} cut has an inconsistent plane '$plane'."
        }
        codecRequire(samples.size in 2..AntennaPatternLimits.MAX_CUT_SAMPLES) {
            "JSON ${expectedPlane.name} cut must contain between 2 and " +
                "${AntennaPatternLimits.MAX_CUT_SAMPLES} samples."
        }
        if (requireExplicitAvailability) {
            codecRequire(availability != null) {
                "ATX Antenna JSON v2 requires explicit ${expectedPlane.name} cut availability."
            }
        }
        return AntennaPatternCut(
            plane = expectedPlane,
            samples = samples.map(JsonSample::toDomain),
            provenance = provenance.toDomain(),
            availability = enumValue<PatternCutAvailability>(
                availability ?: PatternCutAvailability.LEGACY_UNSPECIFIED.name,
                "pattern cut availability",
            ),
        )
    }

    companion object {
        fun fromDomain(cut: AntennaPatternCut): JsonCut = JsonCut(
            plane = cut.plane.name,
            provenance = JsonProvenance.fromDomain(cut.provenance),
            samples = cut.samples.map(JsonSample::fromDomain),
            availability = cut.availability.name,
        )
    }
}

@Serializable
private data class JsonSample(
    val angleDegrees: Double,
    val normalizedFieldAmplitude: Double,
    val phaseDegrees: Double?,
) {
    fun toDomain(): PatternSample = PatternSample(
        angleDegrees = angleDegrees,
        normalizedFieldAmplitude = normalizedFieldAmplitude,
        phaseDegrees = phaseDegrees,
    )

    companion object {
        fun fromDomain(sample: PatternSample): JsonSample = JsonSample(
            angleDegrees = sample.angleDegrees,
            normalizedFieldAmplitude = sample.normalizedFieldAmplitude,
            phaseDegrees = sample.phaseDegrees,
        )
    }
}

@Serializable
private data class JsonProvenance(
    val origin: String,
    val sourceLabel: String,
    val sourceFormat: String?,
    val sourceSha256: String?,
    val coordinateFrame: String,
    val sourceCoordinateFrame: String?,
    val engineId: String?,
    val warnings: List<String>,
    val limitations: List<String>,
) {
    fun toDomain(): PatternProvenance = PatternProvenance(
        origin = enumValue<PatternOrigin>(origin, "pattern origin"),
        sourceLabel = sourceLabel,
        sourceFormat = sourceFormat,
        sourceSha256 = sourceSha256,
        coordinateFrame = enumValue<PatternCoordinateFrame>(
            coordinateFrame,
            "pattern coordinate frame",
        ),
        sourceCoordinateFrame = sourceCoordinateFrame?.let { value ->
            enumValue<PatternCoordinateFrame>(value, "source coordinate frame")
        },
        engineId = engineId,
        warnings = warnings,
        limitations = limitations,
    )

    companion object {
        fun fromDomain(provenance: PatternProvenance): JsonProvenance = JsonProvenance(
            origin = provenance.origin.name,
            sourceLabel = provenance.sourceLabel,
            sourceFormat = provenance.sourceFormat,
            sourceSha256 = provenance.sourceSha256,
            coordinateFrame = provenance.coordinateFrame.name,
            sourceCoordinateFrame = provenance.sourceCoordinateFrame?.name,
            engineId = provenance.engineId,
            warnings = provenance.warnings,
            limitations = provenance.limitations,
        )
    }
}

private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
    enumValues<T>().singleOrNull { candidate -> candidate.name == value }
        ?: codecFailure("Unknown $label '$value'.")
