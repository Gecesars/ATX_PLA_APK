package com.gecesars.atxplan.domain.antenna

import kotlin.math.abs

/** Hard bounds shared by untrusted pattern importers and the CPU synthesis engine. */
object AntennaPatternLimits {
    const val MAX_CUT_SAMPLES = 10_000
    const val MAX_ARRAY_ELEMENTS = 512
    const val MAX_TEXT_LENGTH = 512
    const val MIN_FREQUENCY_HZ = 1_000.0
    const val MAX_FREQUENCY_HZ = 1.0e12

    internal const val NORMALIZATION_TOLERANCE = 1.0e-9
}

enum class PatternCutPlane {
    HORIZONTAL,
    VERTICAL,
}

/**
 * Engineering availability of one canonical cut.
 *
 * [ISOTROPIC_DISPLAY_PLACEHOLDER] may be rendered so a single-cut import can be reviewed, but it
 * is not antenna data. [LEGACY_UNSPECIFIED] is the fail-closed state for older serialized data
 * that did not bind availability to its content identity.
 */
enum class PatternCutAvailability {
    AVAILABLE,
    ISOTROPIC_DISPLAY_PLACEHOLDER,
    LEGACY_UNSPECIFIED,
}

enum class PatternOrigin {
    BUILT_IN_REFERENCE,
    IMPORTED,
    SYNTHESIZED,
}

/**
 * Coordinate frames are explicit so an imported source is never silently reinterpreted.
 * Canonical calculations use [APERTURE_XY_BORESIGHT_Z].
 */
enum class PatternCoordinateFrame {
    APERTURE_XY_BORESIGHT_Z,
    PATTERN3D_LOCAL,
    GEOGRAPHIC_NORTH_CLOCKWISE,
    SOURCE_RELATIVE_UNSPECIFIED,
}

data class PatternProvenance(
    val origin: PatternOrigin,
    val sourceLabel: String,
    val sourceFormat: String? = null,
    val sourceSha256: String? = null,
    val coordinateFrame: PatternCoordinateFrame =
        PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
    val sourceCoordinateFrame: PatternCoordinateFrame? = null,
    val engineId: String? = null,
    val warnings: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
) {
    init {
        requireValidText(sourceLabel, "A pattern provenance source label")
        sourceFormat?.let { requireValidText(it, "A pattern provenance source format") }
        engineId?.let { requireValidText(it, "A pattern provenance engine ID") }
        require(
            sourceSha256 == null || sourceSha256.matches(Regex("[0-9a-f]{64}")),
        ) { "A pattern source SHA-256 must contain 64 lowercase hexadecimal characters." }
        require(warnings.size <= 100 && limitations.size <= 100) {
            "Pattern provenance cannot contain more than 100 warnings or limitations."
        }
        warnings.forEach { requireValidText(it, "A pattern provenance warning") }
        limitations.forEach { requireValidText(it, "A pattern provenance limitation") }
    }
}

/** One normalized complex-field sample. Amplitude is linear E/Emax, not power. */
data class PatternSample(
    val angleDegrees: Double,
    val normalizedFieldAmplitude: Double,
    val phaseDegrees: Double? = null,
) {
    init {
        require(angleDegrees.isFinite()) { "A pattern sample angle must be finite." }
        require(
            normalizedFieldAmplitude.isFinite() && normalizedFieldAmplitude in 0.0..1.0,
        ) { "A normalized pattern field amplitude must be finite and in [0, 1]." }
        require(phaseDegrees == null || phaseDegrees.isFinite()) {
            "A pattern sample phase must be finite when available."
        }
    }
}

/**
 * Canonical normalized cut.
 *
 * Horizontal angles are strictly increasing in [0, 360) and are evaluated cyclically. Vertical
 * angles are strictly increasing in [-90, 90] and are evaluated with endpoint clamping.
 */
data class AntennaPatternCut(
    val plane: PatternCutPlane,
    val samples: List<PatternSample>,
    val provenance: PatternProvenance,
    val availability: PatternCutAvailability,
) {
    init {
        require(samples.size in 2..AntennaPatternLimits.MAX_CUT_SAMPLES) {
            "A pattern cut must contain between 2 and ${AntennaPatternLimits.MAX_CUT_SAMPLES} samples."
        }
        samples.forEach { sample ->
            when (plane) {
                PatternCutPlane.HORIZONTAL -> require(
                    sample.angleDegrees >= 0.0 && sample.angleDegrees < 360.0,
                ) { "A horizontal pattern angle must be in [0, 360) degrees." }

                PatternCutPlane.VERTICAL -> require(sample.angleDegrees in -90.0..90.0) {
                    "A vertical pattern angle must be in [-90, 90] degrees."
                }
            }
        }
        samples.zipWithNext().forEach { (first, second) ->
            require(first.angleDegrees < second.angleDegrees) {
                "Pattern sample angles must be strictly increasing and unique."
            }
        }
        val peak = samples.maxOf(PatternSample::normalizedFieldAmplitude)
        require(peak > 0.0) { "A pattern cut cannot contain only zero field amplitudes." }
        require(abs(peak - 1.0) <= AntennaPatternLimits.NORMALIZATION_TOLERANCE) {
            "A canonical pattern cut must be normalized to a peak field amplitude of 1."
        }
    }

    fun complexFieldAt(angleDegrees: Double): ComplexField =
        AntennaPatternEngine.evaluateCut(this, angleDegrees)
}

/** A separable HRP/VRP antenna pattern in the public physical aperture coordinate frame. */
data class CanonicalAntennaPattern(
    val id: String,
    val name: String,
    val horizontalCut: AntennaPatternCut,
    val verticalCut: AntennaPatternCut,
    val provenance: PatternProvenance,
    val nominalFrequencyHz: Double? = null,
) {
    init {
        requireValidText(id, "A canonical antenna pattern ID")
        requireValidText(name, "A canonical antenna pattern name")
        require(horizontalCut.plane == PatternCutPlane.HORIZONTAL) {
            "A canonical antenna pattern requires a horizontal HRP cut."
        }
        require(verticalCut.plane == PatternCutPlane.VERTICAL) {
            "A canonical antenna pattern requires a vertical VRP cut."
        }
        require(provenance.coordinateFrame == PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z) {
            "A canonical antenna pattern must use the aperture XY, boresight +Z coordinate frame."
        }
        require(
            nominalFrequencyHz == null ||
                nominalFrequencyHz.isFinite() &&
                nominalFrequencyHz in
                AntennaPatternLimits.MIN_FREQUENCY_HZ..AntennaPatternLimits.MAX_FREQUENCY_HZ,
        ) {
            "A nominal pattern frequency must be finite and within the supported frequency range."
        }
    }

    fun fieldAt(
        horizontalAngleDegrees: Double,
        elevationAngleDegrees: Double,
    ): ComplexField = AntennaPatternEngine.evaluatePattern(
        pattern = this,
        horizontalAngleDegrees = horizontalAngleDegrees,
        elevationAngleDegrees = elevationAngleDegrees,
    )

    fun horizontalRelativeField(relativeAzimuthDegrees: Double): ComplexField =
        horizontalCut.complexFieldAt(relativeAzimuthDegrees)

    fun horizontalCorrectionDb(
        relativeAzimuthDegrees: Double,
        floorDb: Double = -120.0,
    ): Double = AntennaPatternEngine.horizontalCorrectionDb(
        pattern = this,
        relativeAzimuthDegrees = relativeAzimuthDegrees,
        floorDb = floorDb,
    )

    /** True only when both cuts are real, explicit inputs suitable for engineering calculation. */
    val isCalculationReady: Boolean
        get() = horizontalCut.availability == PatternCutAvailability.AVAILABLE &&
            verticalCut.availability == PatternCutAvailability.AVAILABLE

    companion object {
        fun isotropic(
            id: String = "isotropic",
            name: String = "Isotropic reference element",
            nominalFrequencyHz: Double? = null,
            provenance: PatternProvenance = PatternProvenance(
                origin = PatternOrigin.BUILT_IN_REFERENCE,
                sourceLabel = "Mathematical isotropic reference",
                engineId = "atx-plan-antenna-pattern-v1",
                limitations = listOf(
                    "This ideal reference is not a measured or full-wave antenna model.",
                ),
            ),
        ): CanonicalAntennaPattern {
            val horizontal = AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = listOf(0.0, 90.0, 180.0, 270.0).map { angle ->
                    PatternSample(angle, 1.0, 0.0)
                },
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            )
            val vertical = AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = listOf(-90.0, 0.0, 90.0).map { angle ->
                    PatternSample(angle, 1.0, 0.0)
                },
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            )
            return CanonicalAntennaPattern(
                id = id,
                name = name,
                horizontalCut = horizontal,
                verticalCut = vertical,
                provenance = provenance,
                nominalFrequencyHz = nominalFrequencyHz,
            )
        }
    }
}

/** Explicit availability prevents missing or unsupported pattern data from becoming unity gain. */
sealed interface CanonicalPatternAvailability {
    val provenance: PatternProvenance

    data class Available(
        val pattern: CanonicalAntennaPattern,
    ) : CanonicalPatternAvailability {
        override val provenance: PatternProvenance = pattern.provenance
    }

    data class NoData(
        val reason: String,
        override val provenance: PatternProvenance,
    ) : CanonicalPatternAvailability {
        init {
            requireValidText(reason, "A pattern NoData reason")
        }
    }

    data class Unsupported(
        val reason: String,
        override val provenance: PatternProvenance,
    ) : CanonicalPatternAvailability {
        init {
            requireValidText(reason, "An unsupported pattern reason")
        }
    }
}

internal fun requireValidText(
    value: String,
    label: String,
) {
    require(value.isNotBlank() && value.length <= AntennaPatternLimits.MAX_TEXT_LENGTH) {
        "$label must be non-blank and no longer than ${AntennaPatternLimits.MAX_TEXT_LENGTH} characters."
    }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters." }
}
