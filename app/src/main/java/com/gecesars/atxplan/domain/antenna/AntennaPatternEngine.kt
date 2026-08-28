package com.gecesars.atxplan.domain.antenna

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

data class ComplexField(
    val real: Double,
    val imaginary: Double,
) {
    init {
        require(real.isFinite() && imaginary.isFinite()) {
            "Complex field components must be finite."
        }
    }

    val magnitude: Double
        get() = hypot(real, imaginary)

    val power: Double
        get() = real * real + imaginary * imaginary

    val phaseDegrees: Double
        get() = if (power <= ZERO_POWER_EPSILON) {
            0.0
        } else {
            Math.toDegrees(atan2(imaginary, real))
        }

    operator fun plus(other: ComplexField): ComplexField =
        ComplexField(real + other.real, imaginary + other.imaginary)

    operator fun times(scale: Double): ComplexField {
        require(scale.isFinite()) { "A complex field scale must be finite." }
        return ComplexField(real * scale, imaginary * scale)
    }

    operator fun times(other: ComplexField): ComplexField = ComplexField(
        real = real * other.real - imaginary * other.imaginary,
        imaginary = real * other.imaginary + imaginary * other.real,
    )

    companion object {
        val ZERO = ComplexField(0.0, 0.0)

        fun fromPolar(
            amplitude: Double,
            phaseDegrees: Double,
        ): ComplexField {
            require(amplitude.isFinite() && amplitude >= 0.0) {
                "A complex field amplitude must be finite and non-negative."
            }
            require(phaseDegrees.isFinite()) { "A complex field phase must be finite." }
            val phaseRadians = Math.toRadians(phaseDegrees)
            return ComplexField(
                real = amplitude * cos(phaseRadians),
                imaginary = amplitude * sin(phaseRadians),
            )
        }

        private const val ZERO_POWER_EPSILON = 1.0e-30
    }
}

/** A normalized observation direction in the physical aperture XY, boresight +Z frame. */
data class ApertureDirection(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "An aperture direction must contain finite components."
        }
        val magnitudeSquared = x * x + y * y + z * z
        require(kotlin.math.abs(magnitudeSquared - 1.0) <= UNIT_VECTOR_TOLERANCE) {
            "An aperture direction must be a unit vector."
        }
    }

    companion object {
        /**
         * Maps canonical cut angles to the physical frame:
         * `u=(cos(elevation)sin(horizontal), sin(elevation), cos(elevation)cos(horizontal))`.
         * HRP is therefore XZ and VRP at horizontal 0 degrees is YZ.
         */
        fun fromAngles(
            horizontalAngleDegrees: Double,
            elevationAngleDegrees: Double,
        ): ApertureDirection {
            require(horizontalAngleDegrees.isFinite()) {
                "A horizontal observation angle must be finite."
            }
            require(
                elevationAngleDegrees.isFinite() && elevationAngleDegrees in -90.0..90.0,
            ) { "An elevation observation angle must be finite and in [-90, 90] degrees." }
            val horizontalRadians = Math.toRadians(horizontalAngleDegrees)
            val elevationRadians = Math.toRadians(elevationAngleDegrees)
            val cosineElevation = cos(elevationRadians)
            return ApertureDirection(
                x = cosineElevation * sin(horizontalRadians),
                y = sin(elevationRadians),
                z = cosineElevation * cos(horizontalRadians),
            )
        }

        private const val UNIT_VECTOR_TOLERANCE = 1.0e-9
    }
}

/** Position of an element phase center in metres in the physical aperture frame. */
data class AperturePositionMeters(
    val xMeters: Double,
    val yMeters: Double,
    val zMeters: Double = 0.0,
) {
    init {
        require(xMeters.isFinite() && yMeters.isFinite() && zMeters.isFinite()) {
            "An aperture position must contain finite metre coordinates."
        }
        require(
            kotlin.math.abs(xMeters) <= MAX_ABSOLUTE_POSITION_METERS &&
                kotlin.math.abs(yMeters) <= MAX_ABSOLUTE_POSITION_METERS &&
                kotlin.math.abs(zMeters) <= MAX_ABSOLUTE_POSITION_METERS,
        ) {
            "An aperture position exceeds the supported absolute coordinate bound."
        }
    }

    companion object {
        const val MAX_ABSOLUTE_POSITION_METERS = 1_000_000.0

        fun fromWavelengths(
            xWavelengths: Double,
            yWavelengths: Double,
            zWavelengths: Double = 0.0,
            frequencyHz: Double,
        ): AperturePositionMeters {
            requireSupportedFrequency(frequencyHz)
            require(
                xWavelengths.isFinite() &&
                    yWavelengths.isFinite() &&
                    zWavelengths.isFinite(),
            ) { "Element coordinates expressed in wavelengths must be finite." }
            val wavelengthMeters = AntennaPatternEngine.SPEED_OF_LIGHT_METERS_PER_SECOND /
                frequencyHz
            return AperturePositionMeters(
                xMeters = xWavelengths * wavelengthMeters,
                yMeters = yWavelengths * wavelengthMeters,
                zMeters = zWavelengths * wavelengthMeters,
            )
        }
    }
}

/**
 * Element orientation in the physical frame. Positive elevation tilts +Z toward +Y; positive roll
 * rotates the local horizontal axis toward the local vertical axis around boresight.
 */
data class ElementOrientation(
    val horizontalAngleDegrees: Double = 0.0,
    val elevationAngleDegrees: Double = 0.0,
    val rollDegrees: Double = 0.0,
) {
    init {
        require(
            horizontalAngleDegrees.isFinite() && horizontalAngleDegrees >= 0.0 &&
                horizontalAngleDegrees < 360.0,
        ) { "An element horizontal orientation must be in [0, 360) degrees." }
        require(elevationAngleDegrees.isFinite() && elevationAngleDegrees in -90.0..90.0) {
            "An element elevation orientation must be in [-90, 90] degrees."
        }
        require(rollDegrees.isFinite() && rollDegrees in -180.0..180.0) {
            "An element roll must be in [-180, 180] degrees."
        }
    }
}

object AntennaPatternEngine {
    const val SPEED_OF_LIGHT_METERS_PER_SECOND = 299_792_458.0

    fun evaluateCut(
        cut: AntennaPatternCut,
        angleDegrees: Double,
    ): ComplexField {
        require(angleDegrees.isFinite()) { "A pattern evaluation angle must be finite." }
        return when (cut.plane) {
            PatternCutPlane.HORIZONTAL -> evaluateCyclic(cut.samples, angleDegrees)
            PatternCutPlane.VERTICAL -> evaluateClamped(cut.samples, angleDegrees)
        }
    }

    /** Multiplies separable HRP and VRP complex fields, preserving their phase contributions. */
    fun evaluatePattern(
        pattern: CanonicalAntennaPattern,
        horizontalAngleDegrees: Double,
        elevationAngleDegrees: Double,
    ): ComplexField {
        require(pattern.isCalculationReady) {
            "A canonical antenna pattern requires explicitly available HRP and VRP cuts for calculation."
        }
        return evaluateAvailablePattern(pattern, horizontalAngleDegrees, elevationAngleDegrees)
    }

    private fun evaluateAvailablePattern(
        pattern: CanonicalAntennaPattern,
        horizontalAngleDegrees: Double,
        elevationAngleDegrees: Double,
    ): ComplexField = pattern.horizontalCut.complexFieldAt(horizontalAngleDegrees) *
        pattern.verticalCut.complexFieldAt(elevationAngleDegrees)

    fun horizontalCorrectionDb(
        pattern: CanonicalAntennaPattern,
        relativeAzimuthDegrees: Double,
        floorDb: Double = -120.0,
    ): Double {
        require(pattern.isCalculationReady) {
            "A canonical antenna pattern requires explicitly available HRP and VRP cuts for calculation."
        }
        require(floorDb.isFinite() && floorDb in -300.0..0.0) {
            "A horizontal correction floor must be finite and in [-300, 0] dB."
        }
        val amplitude = pattern.horizontalRelativeField(relativeAzimuthDegrees).magnitude
        if (amplitude <= 0.0) return floorDb
        val correction = 20.0 * ln(amplitude) / ln(10.0)
        return correction.coerceIn(floorDb, 0.0)
    }

    /** Converts north-clockwise geographic azimuth to the historical Pattern3D/physical HRP angle. */
    fun geographicAzimuthToPhysicalHorizontal(geographicAzimuthDegrees: Double): Double {
        require(geographicAzimuthDegrees.isFinite()) { "A geographic azimuth must be finite." }
        return wrap360(geographicAzimuthDegrees - 90.0)
    }

    fun physicalHorizontalToGeographicAzimuth(horizontalAngleDegrees: Double): Double {
        require(horizontalAngleDegrees.isFinite()) { "A physical horizontal angle must be finite." }
        return wrap360(horizontalAngleDegrees + 90.0)
    }

    internal fun localPatternField(
        pattern: CanonicalAntennaPattern,
        orientation: ElementOrientation,
        direction: ApertureDirection,
    ): ComplexField {
        val horizontalRadians = Math.toRadians(orientation.horizontalAngleDegrees)
        val elevationRadians = Math.toRadians(orientation.elevationAngleDegrees)
        val rollRadians = Math.toRadians(orientation.rollDegrees)

        val forward = Vector3(
            x = cos(elevationRadians) * sin(horizontalRadians),
            y = sin(elevationRadians),
            z = cos(elevationRadians) * cos(horizontalRadians),
        )
        val baseHorizontal = Vector3(
            x = cos(horizontalRadians),
            y = 0.0,
            z = -sin(horizontalRadians),
        )
        val baseVertical = Vector3(
            x = -sin(elevationRadians) * sin(horizontalRadians),
            y = cos(elevationRadians),
            z = -sin(elevationRadians) * cos(horizontalRadians),
        )
        val localHorizontalAxis = baseHorizontal * cos(rollRadians) +
            baseVertical * sin(rollRadians)
        val localVerticalAxis = baseHorizontal * -sin(rollRadians) +
            baseVertical * cos(rollRadians)
        val worldDirection = Vector3(direction.x, direction.y, direction.z)
        val localForward = worldDirection dot forward
        val localHorizontal = worldDirection dot localHorizontalAxis
        val localVertical = worldDirection dot localVerticalAxis
        val localHorizontalDegrees = Math.toDegrees(atan2(localHorizontal, localForward))
        val localElevationDegrees = Math.toDegrees(
            atan2(localVertical, hypot(localForward, localHorizontal)),
        )
        return evaluateAvailablePattern(
            pattern = pattern,
            horizontalAngleDegrees = localHorizontalDegrees,
            elevationAngleDegrees = localElevationDegrees,
        )
    }

    private fun evaluateCyclic(
        samples: List<PatternSample>,
        angleDegrees: Double,
    ): ComplexField {
        val wrapped = wrap360(angleDegrees)
        val exactOrInsertion = binarySearch(samples, wrapped)
        if (exactOrInsertion >= 0) return samples[exactOrInsertion].toComplexField()
        val insertion = -exactOrInsertion - 1
        val lower = if (insertion == 0) samples.last() else samples[insertion - 1]
        val upper = if (insertion == samples.size) samples.first() else samples[insertion]
        val lowerAngle = if (insertion == 0) lower.angleDegrees - 360.0 else lower.angleDegrees
        val upperAngle = if (insertion == samples.size) upper.angleDegrees + 360.0 else upper.angleDegrees
        return interpolate(
            lower = lower,
            upper = upper,
            fraction = (wrapped - lowerAngle) / (upperAngle - lowerAngle),
        )
    }

    private fun evaluateClamped(
        samples: List<PatternSample>,
        angleDegrees: Double,
    ): ComplexField {
        if (angleDegrees <= samples.first().angleDegrees) return samples.first().toComplexField()
        if (angleDegrees >= samples.last().angleDegrees) return samples.last().toComplexField()
        val exactOrInsertion = binarySearch(samples, angleDegrees)
        if (exactOrInsertion >= 0) return samples[exactOrInsertion].toComplexField()
        val insertion = -exactOrInsertion - 1
        val lower = samples[insertion - 1]
        val upper = samples[insertion]
        return interpolate(
            lower = lower,
            upper = upper,
            fraction = (angleDegrees - lower.angleDegrees) /
                (upper.angleDegrees - lower.angleDegrees),
        )
    }

    private fun binarySearch(
        samples: List<PatternSample>,
        angleDegrees: Double,
    ): Int {
        var low = 0
        var high = samples.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val comparison = samples[middle].angleDegrees.compareTo(angleDegrees)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -(low + 1)
    }

    private fun interpolate(
        lower: PatternSample,
        upper: PatternSample,
        fraction: Double,
    ): ComplexField {
        val boundedFraction = fraction.coerceIn(0.0, 1.0)
        val lowerField = lower.toComplexField()
        val upperField = upper.toComplexField()
        return ComplexField(
            real = lowerField.real + (upperField.real - lowerField.real) * boundedFraction,
            imaginary = lowerField.imaginary +
                (upperField.imaginary - lowerField.imaginary) * boundedFraction,
        )
    }

    private fun PatternSample.toComplexField(): ComplexField = ComplexField.fromPolar(
        amplitude = normalizedFieldAmplitude,
        phaseDegrees = phaseDegrees ?: 0.0,
    )

    private fun wrap360(angleDegrees: Double): Double {
        val wrapped = angleDegrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    private data class Vector3(
        val x: Double,
        val y: Double,
        val z: Double,
    ) {
        operator fun plus(other: Vector3): Vector3 =
            Vector3(x + other.x, y + other.y, z + other.z)

        operator fun times(scale: Double): Vector3 = Vector3(x * scale, y * scale, z * scale)

        infix fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z
    }
}

internal fun requireSupportedFrequency(frequencyHz: Double) {
    require(
        frequencyHz.isFinite() && frequencyHz in
            AntennaPatternLimits.MIN_FREQUENCY_HZ..AntennaPatternLimits.MAX_FREQUENCY_HZ,
    ) { "Frequency must be finite and within the supported frequency range in hertz." }
}
