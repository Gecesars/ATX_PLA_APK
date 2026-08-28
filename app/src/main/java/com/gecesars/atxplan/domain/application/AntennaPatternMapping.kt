package com.gecesars.atxplan.domain.application

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCoordinateFrame
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import com.gecesars.atxplan.domain.model.AntennaPatternCutAvailability as StoredCutAvailability
import com.gecesars.atxplan.domain.model.AntennaPatternCutRecord
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.AntennaPatternPlane
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

data class ProjectAntennaPatternIdentity(
    val id: String,
    val name: String,
    val peakGainDbi: Double?,
    val sourceFormat: String,
    val sourceSha256: String?,
    val sourceArtifactId: String?,
    val canonicalArtifactId: String,
    val origin: AntennaPatternOrigin,
    val warnings: List<String> = emptyList(),
)

/**
 * Produces the stable identity of calculation-ready antenna content.
 *
 * The identity deliberately excludes names, project IDs, source file names, hashes, and
 * provenance. It includes the canonical format version, coordinate convention, nominal
 * frequency, peak gain, structured cut availability, and fixed one-degree complex HRP/VRP
 * samples. This lets imports with equivalent engineering content deduplicate even when their
 * containers or labels differ, without conflating a display placeholder with real data.
 */
fun CanonicalAntennaPattern.calculateNormalizedContentSha256(
    peakGainDbi: Double?,
): String =
    AntennaPatternContentIdentity.sha256(
        nominalFrequencyHz = nominalFrequencyHz,
        peakGainDbi = peakGainDbi,
        horizontalCut = horizontalCut.toProjectCutRecord(),
        verticalCut = verticalCut.toProjectCutRecord(),
    )

internal object AntennaPatternContentIdentity {
    /**
     * Version 2 binds peak gain and explicit per-cut availability to normalized content identity.
     * The project schema and its one-degree canonical cut version remain unchanged at version 1.
     * Older hashes and records without explicit availability remain deserializable but
     * intentionally fail calculation-ready verification.
     */
    private const val CANONICAL_DATA_VERSION = 1
    private const val COORDINATE_CONVENTION =
        "RELATIVE_AZIMUTH_CLOCKWISE_ELEVATION_UP"
    private val DOMAIN_SEPARATOR =
        "ATX-PLAN-ANTENNA-CONTENT-SHA256-V2\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun sha256(
        nominalFrequencyHz: Double?,
        peakGainDbi: Double?,
        horizontalCut: AntennaPatternCutRecord,
        verticalCut: AntennaPatternCutRecord,
    ): String {
        require(peakGainDbi == null || peakGainDbi.isFinite()) {
            "Normalized antenna content identity requires finite peak gain when available."
        }
        require(horizontalCut.plane == AntennaPatternPlane.HORIZONTAL) {
            "The normalized antenna identity requires a horizontal cut."
        }
        require(verticalCut.plane == AntennaPatternPlane.VERTICAL) {
            "The normalized antenna identity requires a vertical cut."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val writer = DigestWriter(digest)
        digest.update(DOMAIN_SEPARATOR)
        writer.writeInt(CANONICAL_DATA_VERSION)
        writer.writeAscii(COORDINATE_CONVENTION)
        writer.writeNullableDouble(nominalFrequencyHz)
        writer.writeNullableDouble(peakGainDbi)
        writer.writeCut(horizontalCut)
        writer.writeCut(verticalCut)
        return digest.digest().joinToString("") { byte ->
            HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
                HEX_DIGITS[byte.toInt() and 0x0f]
        }
    }

    private class DigestWriter(
        private val digest: MessageDigest,
    ) {
        private val scratch = ByteBuffer.allocate(SHA_DOUBLE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)

        fun writeCut(cut: AntennaPatternCutRecord) {
            writeAscii(cut.plane.name)
            writeAscii(cut.availability.name)
            writeDouble(cut.startAngleDegrees)
            writeDouble(cut.stepDegrees)
            writeInt(cut.normalizedField.size)
            cut.normalizedField.forEach(::writeDouble)
            writeInt(cut.phaseDegrees.size)
            cut.phaseDegrees.forEach(::writeDouble)
        }

        fun writeNullableDouble(value: Double?) {
            digest.update((if (value == null) 0 else 1).toByte())
            value?.let(::writeDouble)
        }

        fun writeDouble(value: Double) {
            val canonicalValue = if (value == 0.0) 0.0 else value
            scratch.clear()
            scratch.putLong(java.lang.Double.doubleToLongBits(canonicalValue))
            digest.update(scratch.array())
        }

        fun writeInt(value: Int) {
            scratch.clear()
            scratch.putInt(value)
            digest.update(scratch.array(), 0, SHA_INT_BYTES)
        }

        fun writeAscii(value: String) {
            val bytes = value.toByteArray(StandardCharsets.US_ASCII)
            writeInt(bytes.size)
            digest.update(bytes)
        }
    }

    private const val SHA_DOUBLE_BYTES = 8
    private const val SHA_INT_BYTES = 4
    private const val HEX_DIGITS = "0123456789abcdef"
}

/** Converts a canonical engine result into the fixed one-degree project calculation cache. */
fun CanonicalAntennaPattern.toProjectRecord(
    identity: ProjectAntennaPatternIdentity,
): AntennaPatternRecord {
    val horizontal = horizontalCut.toProjectCutRecord()
    val vertical = verticalCut.toProjectCutRecord()
    return AntennaPatternRecord(
        id = identity.id,
        name = identity.name,
        nominalFrequencyHz = nominalFrequencyHz,
        peakGainDbi = identity.peakGainDbi,
        sourceFormat = identity.sourceFormat,
        sourceSha256 = identity.sourceSha256,
        sourceArtifactId = identity.sourceArtifactId,
        dataArtifactId = identity.canonicalArtifactId,
        canonicalDataVersion = 1,
        origin = identity.origin,
        horizontalCut = horizontal,
        verticalCut = vertical,
        normalizedContentSha256 = AntennaPatternContentIdentity.sha256(
            nominalFrequencyHz = nominalFrequencyHz,
            peakGainDbi = identity.peakGainDbi,
            horizontalCut = horizontal,
            verticalCut = vertical,
        ),
        warnings = (provenance.warnings + provenance.limitations + identity.warnings)
            .distinct()
            .take(100),
    )
}

/**
 * Verifies that stored calculation fields match their gain-bound identity.
 *
 * A false result is explicit NoData for calculation purposes. In particular, legacy version-1
 * hashes remain schema-readable but are not accepted because they did not bind peak gain.
 */
fun AntennaPatternRecord.hasVerifiedNormalizedContentIdentity(): Boolean = runCatching {
    if (canonicalDataVersion != 1) return@runCatching false
    val horizontal = horizontalCut ?: return@runCatching false
    val vertical = verticalCut ?: return@runCatching false
    if (horizontal.availability != StoredCutAvailability.AVAILABLE ||
        vertical.availability != StoredCutAvailability.AVAILABLE
    ) {
        return@runCatching false
    }
    val storedHash = normalizedContentSha256 ?: return@runCatching false
    val calculatedHash = AntennaPatternContentIdentity.sha256(
        nominalFrequencyHz = nominalFrequencyHz,
        peakGainDbi = peakGainDbi,
        horizontalCut = horizontal,
        verticalCut = vertical,
    )
    storedHash == calculatedHash
}.getOrDefault(false)

/**
 * Rehydrates the verified fixed-grid project cache without consulting a provider URI.
 *
 * The cache intentionally supports bounded magnitude consumers but cannot represent per-sample
 * phase NoData losslessly: an empty phase array is compatible with both unknown and known-zero
 * phase, while mixed missing phase was historically sampled as zero. Coherent composition must
 * reopen and verify the canonical antenna artifact instead of using this projection.
 */
fun AntennaPatternRecord.toCanonicalPatternOrNull(): CanonicalAntennaPattern? {
    if (!hasVerifiedNormalizedContentIdentity()) return null
    val horizontal = horizontalCut ?: return null
    val vertical = verticalCut ?: return null
    val provenance = PatternProvenance(
        origin = when (origin) {
            AntennaPatternOrigin.SYNTHESIZED,
            AntennaPatternOrigin.ANALYTICAL,
            -> PatternOrigin.SYNTHESIZED

            AntennaPatternOrigin.IMPORTED,
            AntennaPatternOrigin.MEASURED,
            AntennaPatternOrigin.SIMULATED,
            AntennaPatternOrigin.UNKNOWN,
            -> PatternOrigin.IMPORTED
        },
        sourceLabel = name,
        sourceFormat = sourceFormat.ifBlank { null },
        sourceSha256 = sourceSha256,
        coordinateFrame = PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
        sourceCoordinateFrame = PatternCoordinateFrame.GEOGRAPHIC_NORTH_CLOCKWISE,
        engineId = "atx-plan-android-canonical-pattern-v1",
        warnings = warnings,
    )
    return runCatching {
        CanonicalAntennaPattern(
            id = id,
            name = name,
            horizontalCut = horizontal.toEngineCut(provenance),
            verticalCut = vertical.toEngineCut(provenance),
            provenance = provenance,
            nominalFrequencyHz = nominalFrequencyHz,
        )
    }.getOrNull()
}

private fun AntennaPatternCut.toProjectCutRecord(): AntennaPatternCutRecord {
    val angles = when (plane) {
        PatternCutPlane.HORIZONTAL -> 0..359
        PatternCutPlane.VERTICAL -> -90..90
    }
    val fields = angles.map { angle -> complexFieldAt(angle.toDouble()) }
    val peak = fields.maxOf { field -> field.magnitude }
    require(peak.isFinite() && peak > 0.0) {
        "A canonical antenna cut produced no positive finite one-degree field samples."
    }
    val amplitudes = fields.map { field -> (field.magnitude / peak).coerceIn(0.0, 1.0) }
    val phases = fields.map { field -> field.phaseDegrees }
    return AntennaPatternCutRecord(
        plane = when (plane) {
            PatternCutPlane.HORIZONTAL -> AntennaPatternPlane.HORIZONTAL
            PatternCutPlane.VERTICAL -> AntennaPatternPlane.VERTICAL
        },
        startAngleDegrees = angles.first.toDouble(),
        stepDegrees = 1.0,
        normalizedField = amplitudes,
        phaseDegrees = if (phases.all { phase -> abs(phase) <= 1e-12 }) emptyList() else phases,
        availability = when (availability) {
            PatternCutAvailability.AVAILABLE -> StoredCutAvailability.AVAILABLE
            PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER ->
                StoredCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER
            PatternCutAvailability.LEGACY_UNSPECIFIED -> StoredCutAvailability.LEGACY_UNSPECIFIED
        },
    )
}

private fun AntennaPatternCutRecord.toEngineCut(
    provenance: PatternProvenance,
): AntennaPatternCut = AntennaPatternCut(
    plane = when (plane) {
        AntennaPatternPlane.HORIZONTAL -> PatternCutPlane.HORIZONTAL
        AntennaPatternPlane.VERTICAL -> PatternCutPlane.VERTICAL
    },
    samples = normalizedField.mapIndexed { index, field ->
        PatternSample(
            angleDegrees = startAngleDegrees + index * stepDegrees,
            normalizedFieldAmplitude = field,
            phaseDegrees = phaseDegrees.getOrNull(index),
        )
    },
    provenance = provenance,
    availability = when (availability) {
        StoredCutAvailability.AVAILABLE -> PatternCutAvailability.AVAILABLE
        StoredCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER ->
            PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER
        StoredCutAvailability.LEGACY_UNSPECIFIED -> PatternCutAvailability.LEGACY_UNSPECIFIED
    },
)
