package com.gecesars.atxplan.domain.antenna

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class AntennaArrayElement(
    val id: String,
    val positionMeters: AperturePositionMeters,
    val pattern: CanonicalAntennaPattern,
    val powerFraction: Double,
    val feedPhaseDegrees: Double = 0.0,
    val orientation: ElementOrientation = ElementOrientation(),
    val active: Boolean = true,
) {
    init {
        requireValidText(id, "An antenna array element ID")
        require(powerFraction.isFinite() && powerFraction in 0.0..1.0) {
            "An antenna array element power fraction must be finite and in [0, 1]."
        }
        require(feedPhaseDegrees.isFinite() && kotlin.math.abs(feedPhaseDegrees) <= 1.0e6) {
            "An antenna array element feed phase must be finite and within the supported bound."
        }
    }
}

data class AntennaArrayConfiguration(
    val id: String,
    val name: String,
    val frequencyHz: Double,
    val elements: List<AntennaArrayElement>,
    val efficiency: Double = 1.0,
    val declaredScanAngleDegrees: Double? = null,
    val coordinateFrame: PatternCoordinateFrame =
        PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
) {
    init {
        requireValidText(id, "An antenna array configuration ID")
        requireValidText(name, "An antenna array configuration name")
        requireSupportedFrequency(frequencyHz)
        require(elements.size in 1..AntennaPatternLimits.MAX_ARRAY_ELEMENTS) {
            "An antenna array must contain between 1 and ${AntennaPatternLimits.MAX_ARRAY_ELEMENTS} elements."
        }
        require(elements.map(AntennaArrayElement::id).distinct().size == elements.size) {
            "Antenna array element IDs must be unique."
        }
        require(efficiency.isFinite() && efficiency > 0.0 && efficiency <= 1.0) {
            "Antenna efficiency must be finite and in (0, 1]."
        }
        require(
            declaredScanAngleDegrees == null ||
                declaredScanAngleDegrees.isFinite() && declaredScanAngleDegrees in 0.0..90.0,
        ) { "A declared scan angle must be finite and in [0, 90] degrees." }
        require(coordinateFrame == PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z) {
            "The Android coherent array engine supports only aperture XY, boresight +Z geometry."
        }
    }
}

data class AntennaCompositionOptions(
    val directivityGridStepDegrees: Double = 2.0,
    val minimumDirectivityGridStepDegrees: Double = maxOf(
        AntennaArrayComposer.MINIMUM_SUPPORTED_GRID_STEP_DEGREES,
        directivityGridStepDegrees / 8.0,
    ),
    val relativeConvergenceTolerance: Double = 5.0e-4,
    val maximumRefinementLevels: Int = 4,
) {
    init {
        require(
            directivityGridStepDegrees.isFinite() &&
                directivityGridStepDegrees in 0.5..10.0 &&
                tilesSphere(directivityGridStepDegrees),
        ) {
            "The initial directivity grid step must be finite, in [0.5, 10] degrees, and tile 180 and 360 degrees."
        }
        require(
            minimumDirectivityGridStepDegrees.isFinite() &&
                minimumDirectivityGridStepDegrees >=
                AntennaArrayComposer.MINIMUM_SUPPORTED_GRID_STEP_DEGREES &&
                minimumDirectivityGridStepDegrees < directivityGridStepDegrees &&
                tilesSphere(minimumDirectivityGridStepDegrees),
        ) {
            "The minimum directivity grid step must be finite, tile 180 and 360 degrees, and be in " +
                "[${AntennaArrayComposer.MINIMUM_SUPPORTED_GRID_STEP_DEGREES}, initial step) degrees."
        }
        val refinementRatio = directivityGridStepDegrees / minimumDirectivityGridStepDegrees
        val roundedRefinementRatio = refinementRatio.roundToInt()
        require(
            abs(refinementRatio - roundedRefinementRatio) <= GRID_RATIO_TOLERANCE &&
                roundedRefinementRatio >= 2 &&
                roundedRefinementRatio and (roundedRefinementRatio - 1) == 0,
        ) {
            "The initial-to-minimum grid-step ratio must be an exact power of two."
        }
        require(
            relativeConvergenceTolerance.isFinite() &&
                relativeConvergenceTolerance in 1.0e-12..0.05,
        ) {
            "The relative convergence tolerance must be finite and in [1e-12, 0.05]."
        }
        require(maximumRefinementLevels in 2..8) {
            "The maximum number of directivity refinement levels must be in [2, 8]."
        }
    }
}

enum class AntennaArrayWarningCode {
    GRATING_LOBE_RISK,
    MUTUAL_COUPLING_NOT_MODELED,
    LARGE_SCAN_ANGLE,
    POWER_FRACTIONS_NOT_NORMALIZED,
    SOURCE_FREQUENCY_MISMATCH,
    SOURCE_PHASE_NO_DATA,
    SEPARABLE_CUT_REPRESENTATION,
}

data class AntennaArrayWarning(
    val code: AntennaArrayWarningCode,
    val message: String,
) {
    init {
        requireValidText(message, "An antenna array warning")
    }
}

data class AntennaGainMetrics(
    val directivityLinear: Double,
    val directivityDbi: Double,
    val efficiency: Double,
    val gainDbi: Double,
    val gainDbd: Double,
    val peakPower: Double,
    val peakHorizontalAngleDegrees: Double,
    val peakElevationAngleDegrees: Double,
    val integrationAzimuthStepDegrees: Double,
    val integrationElevationStepDegrees: Double,
    val refinementLevels: Int,
    val peakRelativeChange: Double,
    val directivityRelativeChange: Double,
    val fieldEvaluationCount: Long,
    val method: String =
        "Converged 3D spherical integration with deterministic peak refinement; declared efficiency applies only to gain",
) {
    init {
        require(
            directivityLinear.isFinite() && directivityLinear > 0.0 &&
                directivityDbi.isFinite() && gainDbi.isFinite() && gainDbd.isFinite(),
        ) { "Antenna gain metrics must contain positive finite directivity and finite gains." }
        require(efficiency.isFinite() && efficiency > 0.0 && efficiency <= 1.0) {
            "Antenna gain metric efficiency must be finite and in (0, 1]."
        }
        require(peakPower.isFinite() && peakPower > 0.0) {
            "Antenna peak power must be positive and finite."
        }
        require(
            peakHorizontalAngleDegrees.isFinite() &&
                peakHorizontalAngleDegrees >= 0.0 && peakHorizontalAngleDegrees < 360.0 &&
                peakElevationAngleDegrees.isFinite() &&
                peakElevationAngleDegrees in -90.0..90.0,
        ) { "An antenna peak direction must use finite canonical angles." }
        require(
            integrationAzimuthStepDegrees.isFinite() && integrationAzimuthStepDegrees > 0.0 &&
                integrationElevationStepDegrees.isFinite() && integrationElevationStepDegrees > 0.0,
        ) {
            "Antenna gain integration steps must be positive."
        }
        require(refinementLevels >= 2) {
            "Available antenna gain metrics require at least two numerical refinement levels."
        }
        require(
            peakRelativeChange.isFinite() && peakRelativeChange >= 0.0 &&
                directivityRelativeChange.isFinite() && directivityRelativeChange >= 0.0,
        ) { "Antenna convergence changes must be finite and non-negative." }
        require(
            fieldEvaluationCount > 0L &&
                fieldEvaluationCount <= AntennaArrayComposer.MAX_FIELD_EVALUATIONS,
        ) { "Antenna field-evaluation count exceeds the bounded CPU budget." }
        requireValidText(method, "An antenna gain method")
    }
}

sealed interface AntennaCompositionOutcome {
    val provenance: PatternProvenance
    val warnings: List<AntennaArrayWarning>

    data class Available(
        val pattern: CanonicalAntennaPattern,
        val metrics: AntennaGainMetrics,
        override val provenance: PatternProvenance,
        override val warnings: List<AntennaArrayWarning>,
    ) : AntennaCompositionOutcome

    data class NoData(
        val reason: String,
        override val provenance: PatternProvenance,
        override val warnings: List<AntennaArrayWarning>,
    ) : AntennaCompositionOutcome {
        init {
            requireValidText(reason, "An antenna composition NoData reason")
        }
    }

    data class Unsupported(
        val reason: String,
        override val provenance: PatternProvenance,
        override val warnings: List<AntennaArrayWarning>,
    ) : AntennaCompositionOutcome {
        init {
            requireValidText(reason, "An unsupported antenna composition reason")
        }
    }
}

/**
 * Deterministic CPU coherent-field array synthesis.
 *
 * Each active contribution is
 * `sqrt(power fraction) * element field * exp(j(feed phase + k * position dot direction))`.
 * Directivity uses nested spherical trapezoidal grids and deterministic local peak refinement
 * seeded from up to twelve spatially separated grid-local maxima.
 * An available result requires successive peak and directivity estimates to meet the configured
 * tolerance at a grid fine enough to bound inter-element phase advance and source-pattern complex
 * field variation. Budget, resolution, or convergence failures are explicit
 * [AntennaCompositionOutcome.Unsupported] results.
 * The returned HRP and VRP are independently normalized separable cuts, not a lossless encoding of
 * the converged 3D or absolute field. This model deliberately excludes mutual coupling, tower
 * scattering and full-wave simulation.
 */
object AntennaArrayComposer {
    const val ENGINE_ID = "atx-plan-android-coherent-array-v2"
    const val HORIZONTAL_SAMPLE_COUNT = 360
    const val VERTICAL_SAMPLE_COUNT = 181
    const val MAX_FIELD_EVALUATIONS = 20_000_000L
    const val MINIMUM_SUPPORTED_GRID_STEP_DEGREES = 0.25

    private const val FIELD_ZERO_RELATIVE_TOLERANCE = 1.0e-12
    private const val MAX_GRID_CACHE_POINTS = 1_100_000
    private const val PEAK_SEED_COUNT = 12
    private const val PEAK_SEED_MINIMUM_SEPARATION_STEPS = 2.0
    private const val MAX_PEAK_REFINEMENT_ITERATIONS = 128
    private const val PEAK_ANGULAR_TOLERANCE_DEGREES = 1.0e-4
    private const val PEAK_IMPROVEMENT_RELATIVE_TOLERANCE = 1.0e-13
    private const val MAX_INTER_ELEMENT_PHASE_ADVANCE_RADIANS = PI / 4.0
    private const val MAX_COMPLEX_FIELD_CHANGE_PER_GRID_STEP = 0.5
    private const val MAX_HORIZONTAL_PEAK_DEFICIT_DB = 0.1

    fun compose(
        configuration: AntennaArrayConfiguration,
        options: AntennaCompositionOptions = AntennaCompositionOptions(),
    ): AntennaCompositionOutcome {
        val poweredElements = configuration.elements.filter { element ->
            element.active && element.powerFraction > 0.0
        }
        val unavailableElement = poweredElements.firstOrNull { element ->
            !element.pattern.isCalculationReady
        }
        val missingRequiredPhase = if (unavailableElement == null) {
            firstMissingRequiredSourcePhase(poweredElements)
        } else {
            null
        }
        val phaseWarning = missingRequiredPhase?.toWarning()
        val warnings = (
            geometryWarnings(configuration, poweredElements) + listOfNotNull(phaseWarning)
        ).distinctBy { warning -> warning.code to warning.message }
        val provenance = synthesizedProvenance(configuration, warnings)
        if (poweredElements.isEmpty()) {
            return AntennaCompositionOutcome.NoData(
                reason = "The array has no active element with a positive power fraction.",
                provenance = provenance,
                warnings = warnings,
            )
        }
        if (unavailableElement != null) {
            return AntennaCompositionOutcome.NoData(
                reason =
                    "Element ${unavailableElement.id} lacks explicitly available HRP and VRP cuts; " +
                        "display placeholders and legacy cut availability cannot be composed.",
                provenance = provenance,
                warnings = warnings,
            )
        }
        if (missingRequiredPhase != null) {
            return AntennaCompositionOutcome.NoData(
                reason = missingRequiredPhase.message(),
                provenance = provenance,
                warnings = warnings,
            )
        }

        val resolutionRequirement = requiredGridStepRequirement(
            configuration = configuration,
            poweredElements = poweredElements,
            initialStepDegrees = options.directivityGridStepDegrees,
        )
        if (
            resolutionRequirement.stepDegrees + GRID_RATIO_TOLERANCE <
            options.minimumDirectivityGridStepDegrees
        ) {
            return AntennaCompositionOutcome.Unsupported(
                reason =
                    "The ${resolutionRequirement.basis} requires a spherical grid step no larger than " +
                        "${resolutionRequirement.stepDegrees.formatEngineering()} degrees, below the supported minimum " +
                        "${options.minimumDirectivityGridStepDegrees.formatEngineering()} degrees.",
                provenance = provenance,
                warnings = warnings,
            )
        }

        val evaluator = BoundedFieldEvaluator(configuration, poweredElements)
        val horizontalFields = mutableListOf<ComplexField>()
        for (angle in 0 until HORIZONTAL_SAMPLE_COUNT) {
            horizontalFields += evaluator.evaluate(
                ApertureDirection.fromAngles(angle.toDouble(), 0.0),
            ) ?: return AntennaCompositionOutcome.Unsupported(
                reason = cpuBudgetReason(),
                provenance = provenance,
                warnings = warnings,
            )
        }
        val verticalFields = mutableListOf<ComplexField>()
        for (angle in -90..90) {
            verticalFields += evaluator.evaluate(
                ApertureDirection.fromAngles(0.0, angle.toDouble()),
            ) ?: return AntennaCompositionOutcome.Unsupported(
                reason = cpuBudgetReason(),
                provenance = provenance,
                warnings = warnings,
            )
        }
        val totalExcitation = poweredElements.sumOf { element -> sqrt(element.powerFraction) }
        val meaningfulPeak = totalExcitation * FIELD_ZERO_RELATIVE_TOLERANCE
        val horizontalPeak = horizontalFields.maxOf(ComplexField::magnitude)
        val verticalPeak = verticalFields.maxOf(ComplexField::magnitude)
        if (horizontalPeak <= meaningfulPeak || verticalPeak <= meaningfulPeak) {
            return AntennaCompositionOutcome.NoData(
                reason =
                    "The coherent field cancels throughout at least one canonical HRP or VRP plane.",
                provenance = provenance,
                warnings = warnings,
            )
        }

        val horizontalCut = normalizedCut(
            plane = PatternCutPlane.HORIZONTAL,
            anglesDegrees = (0 until HORIZONTAL_SAMPLE_COUNT).map(Int::toDouble),
            fields = horizontalFields,
            peak = horizontalPeak,
            provenance = provenance,
        )
        val verticalCut = normalizedCut(
            plane = PatternCutPlane.VERTICAL,
            anglesDegrees = (-90..90).map(Int::toDouble),
            fields = verticalFields,
            peak = verticalPeak,
            provenance = provenance,
        )
        val integration = when (
            val result = convergeDirectivity(
                evaluator = evaluator,
                options = options,
                requiredGridStepDegrees = resolutionRequirement.stepDegrees,
            )
        ) {
            is DirectivityConvergence.Available -> result
            is DirectivityConvergence.NoData -> return AntennaCompositionOutcome.NoData(
                reason = result.reason,
                provenance = provenance,
                warnings = warnings,
            )
            is DirectivityConvergence.Unsupported -> return AntennaCompositionOutcome.Unsupported(
                reason = result.reason,
                provenance = provenance,
                warnings = warnings,
            )
        }
        val horizontalPeakPower = horizontalPeak * horizontalPeak
        val horizontalPeakDeficitDb = linearPowerToDb(
            integration.peak.power / horizontalPeakPower,
        )
        if (horizontalPeakDeficitDb > MAX_HORIZONTAL_PEAK_DEFICIT_DB) {
            return AntennaCompositionOutcome.Unsupported(
                reason =
                    "The sampled canonical HRP peak is " +
                        "${horizontalPeakDeficitDb.formatEngineering()} dB below the converged 3D peak, " +
                        "which exceeds the ${MAX_HORIZONTAL_PEAK_DEFICIT_DB.formatEngineering()} dB " +
                        "representation limit. Scalar gain cannot be stored with this independently " +
                        "normalized HRP without misrepresenting the absolute field.",
                provenance = provenance,
                warnings = warnings,
            )
        }
        val directivityDbi = linearPowerToDb(integration.directivityLinear)
        val gainDbi = linearPowerToDb(
            integration.directivityLinear * configuration.efficiency,
        )
        val metrics = AntennaGainMetrics(
            directivityLinear = integration.directivityLinear,
            directivityDbi = directivityDbi,
            efficiency = configuration.efficiency,
            gainDbi = gainDbi,
            gainDbd = gainDbi - 2.15,
            peakPower = integration.peak.power,
            peakHorizontalAngleDegrees = integration.peak.horizontalDegrees,
            peakElevationAngleDegrees = integration.peak.elevationDegrees,
            integrationAzimuthStepDegrees = integration.stepDegrees,
            integrationElevationStepDegrees = integration.stepDegrees,
            refinementLevels = integration.refinementLevels,
            peakRelativeChange = integration.peakRelativeChange,
            directivityRelativeChange = integration.directivityRelativeChange,
            fieldEvaluationCount = evaluator.fieldEvaluationCount,
        )
        val pattern = CanonicalAntennaPattern(
            id = withBoundedSuffix(configuration.id, "-synthesized"),
            name = withBoundedSuffix(configuration.name, " synthesized pattern"),
            horizontalCut = horizontalCut,
            verticalCut = verticalCut,
            provenance = provenance,
            nominalFrequencyHz = configuration.frequencyHz,
        )
        return AntennaCompositionOutcome.Available(
            pattern = pattern,
            metrics = metrics,
            provenance = provenance,
            warnings = warnings,
        )
    }

    fun evaluateField(
        configuration: AntennaArrayConfiguration,
        horizontalAngleDegrees: Double,
        elevationAngleDegrees: Double,
    ): ComplexField = evaluateField(
        configuration = configuration,
        direction = ApertureDirection.fromAngles(
            horizontalAngleDegrees = horizontalAngleDegrees,
            elevationAngleDegrees = elevationAngleDegrees,
        ),
    )

    fun evaluateField(
        configuration: AntennaArrayConfiguration,
        direction: ApertureDirection,
    ): ComplexField {
        val poweredElements = configuration.elements.filter { element ->
            element.active && element.powerFraction > 0.0
        }
        require(
            poweredElements.all { element -> element.pattern.isCalculationReady },
        ) {
            "Coherent array evaluation requires explicitly available HRP and VRP cuts for every powered element."
        }
        val missingRequiredPhase = firstMissingRequiredSourcePhase(poweredElements)
        if (missingRequiredPhase != null) {
            throw IllegalArgumentException(missingRequiredPhase.message())
        }
        val waveNumberRadiansPerMeter = waveNumberRadiansPerMeter(configuration.frequencyHz)
        return evaluateAvailableField(
            poweredElements = poweredElements,
            waveNumberRadiansPerMeter = waveNumberRadiansPerMeter,
            direction = direction,
        )
    }

    private fun evaluateAvailableField(
        poweredElements: List<AntennaArrayElement>,
        waveNumberRadiansPerMeter: Double,
        direction: ApertureDirection,
    ): ComplexField {
        var total = ComplexField.ZERO
        poweredElements.forEach { element ->
            val elementField = AntennaPatternEngine.localPatternField(
                pattern = element.pattern,
                orientation = element.orientation,
                direction = direction,
            )
            val positionProjectionMeters =
                element.positionMeters.xMeters * direction.x +
                    element.positionMeters.yMeters * direction.y +
                    element.positionMeters.zMeters * direction.z
            val totalPhaseRadians = Math.toRadians(element.feedPhaseDegrees) +
                waveNumberRadiansPerMeter * positionProjectionMeters
            val phase = ComplexField(
                real = cos(totalPhaseRadians),
                imaginary = sin(totalPhaseRadians),
            )
            total += elementField * phase * sqrt(element.powerFraction)
        }
        return total
    }

    private fun normalizedCut(
        plane: PatternCutPlane,
        anglesDegrees: List<Double>,
        fields: List<ComplexField>,
        peak: Double,
        provenance: PatternProvenance,
    ): AntennaPatternCut = AntennaPatternCut(
        plane = plane,
        samples = anglesDegrees.zip(fields) { angle, field ->
            PatternSample(
                angleDegrees = angle,
                normalizedFieldAmplitude = (field.magnitude / peak).coerceIn(0.0, 1.0),
                phaseDegrees = field.phaseDegrees,
            )
        },
        provenance = provenance,
        availability = PatternCutAvailability.AVAILABLE,
    )

    private fun convergeDirectivity(
        evaluator: BoundedFieldEvaluator,
        options: AntennaCompositionOptions,
        requiredGridStepDegrees: Double,
    ): DirectivityConvergence {
        val cachePointCount = sphericalGridPointCount(
            options.minimumDirectivityGridStepDegrees,
        )
        if (cachePointCount > MAX_GRID_CACHE_POINTS) {
            return DirectivityConvergence.Unsupported(
                "The requested convergence grid exceeds the bounded $MAX_GRID_CACHE_POINTS-point cache.",
            )
        }
        val cache = SphericalPowerGridCache(
            minimumStepDegrees = options.minimumDirectivityGridStepDegrees,
            evaluator = evaluator,
        )
        val refinementSteps = refinementSteps(options)
        var previousPeakPower: Double? = null
        var previousDirectivity: Double? = null
        var lastPeakRelativeChange = Double.POSITIVE_INFINITY
        var lastDirectivityRelativeChange = Double.POSITIVE_INFINITY

        refinementSteps.forEachIndexed { levelIndex, stepDegrees ->
            val grid = when (val result = integrateGrid(cache, stepDegrees)) {
                is GridIntegration.Available -> result
                GridIntegration.BudgetExceeded -> return DirectivityConvergence.Unsupported(
                    cpuBudgetReason(),
                )
                GridIntegration.Invalid -> return DirectivityConvergence.NoData(
                    "The bounded spherical integration produced no positive finite radiated field.",
                )
            }
            val refinedPeak = when (
                val result = refinePeak(
                    evaluator = evaluator,
                    seeds = grid.peakSeeds,
                    initialStepDegrees = stepDegrees / 2.0,
                )
            ) {
                is PeakRefinement.Available -> result.peak
                PeakRefinement.BudgetExceeded -> return DirectivityConvergence.Unsupported(
                    cpuBudgetReason(),
                )
                PeakRefinement.DidNotConverge -> return DirectivityConvergence.Unsupported(
                    "Deterministic peak refinement did not converge within " +
                        "$MAX_PEAK_REFINEMENT_ITERATIONS iterations per seed.",
                )
                PeakRefinement.Invalid -> return DirectivityConvergence.NoData(
                    "Deterministic peak refinement produced no positive finite radiated field.",
                )
            }
            val directivity = 4.0 * PI * refinedPeak.power / grid.solidAnglePowerIntegral
            if (!directivity.isFinite() || directivity <= 0.0) {
                return DirectivityConvergence.NoData(
                    "The bounded spherical integration produced no positive finite directivity.",
                )
            }

            val priorPeak = previousPeakPower
            val priorDirectivity = previousDirectivity
            if (priorPeak != null && priorDirectivity != null) {
                lastPeakRelativeChange = relativeDifference(refinedPeak.power, priorPeak)
                lastDirectivityRelativeChange = relativeDifference(directivity, priorDirectivity)
                val resolutionSatisfied =
                    stepDegrees <= requiredGridStepDegrees + GRID_RATIO_TOLERANCE
                val convergenceSatisfied =
                    lastPeakRelativeChange <= options.relativeConvergenceTolerance &&
                        lastDirectivityRelativeChange <= options.relativeConvergenceTolerance
                if (resolutionSatisfied && convergenceSatisfied) {
                    return DirectivityConvergence.Available(
                        directivityLinear = directivity,
                        peak = refinedPeak,
                        stepDegrees = stepDegrees,
                        refinementLevels = levelIndex + 1,
                        peakRelativeChange = lastPeakRelativeChange,
                        directivityRelativeChange = lastDirectivityRelativeChange,
                    )
                }
            }
            previousPeakPower = refinedPeak.power
            previousDirectivity = directivity
        }

        val finalStep = refinementSteps.last()
        val resolutionClause = if (
            finalStep > requiredGridStepDegrees + GRID_RATIO_TOLERANCE
        ) {
            " The final ${finalStep.formatEngineering()}-degree grid did not reach the " +
                "${requiredGridStepDegrees.formatEngineering()}-degree aperture bound."
        } else {
            ""
        }
        return DirectivityConvergence.Unsupported(
            "Peak/directivity estimation did not converge within ${refinementSteps.size} levels " +
                "at relative tolerance ${options.relativeConvergenceTolerance.formatEngineering()}." +
                resolutionClause,
        )
    }

    private fun integrateGrid(
        cache: SphericalPowerGridCache,
        stepDegrees: Double,
    ): GridIntegration {
        val stride = (stepDegrees / cache.minimumStepDegrees).roundToInt()
        if (
            stride <= 0 ||
            abs(stepDegrees / cache.minimumStepDegrees - stride) > GRID_RATIO_TOLERANCE ||
            cache.azimuthIntervals % stride != 0 ||
            cache.elevationIntervals % stride != 0
        ) {
            return GridIntegration.Invalid
        }
        val azimuthCount = cache.azimuthIntervals / stride
        val elevationIntervals = cache.elevationIntervals / stride
        val azimuthStepRadians = Math.toRadians(stepDegrees)
        val elevationStepRadians = Math.toRadians(stepDegrees)
        var previousRowIntegral: Double? = null
        var solidAnglePowerIntegral = 0.0

        for (elevationIndex in 0..elevationIntervals) {
            val cacheElevationIndex = elevationIndex * stride
            val elevationDegrees = -90.0 + elevationIndex * stepDegrees
            var azimuthPowerSum = 0.0
            for (azimuthIndex in 0 until azimuthCount) {
                val cacheAzimuthIndex = azimuthIndex * stride
                val power = cache.powerAt(cacheAzimuthIndex, cacheElevationIndex)
                    ?: return GridIntegration.BudgetExceeded
                if (!power.isFinite() || power < 0.0) return GridIntegration.Invalid
                azimuthPowerSum += power
            }
            val rowIntegral = azimuthPowerSum * azimuthStepRadians *
                cos(Math.toRadians(elevationDegrees))
            if (!rowIntegral.isFinite()) return GridIntegration.Invalid
            previousRowIntegral?.let { previous ->
                solidAnglePowerIntegral +=
                    (previous + rowIntegral) * 0.5 * elevationStepRadians
            }
            previousRowIntegral = rowIntegral
        }

        val peakSeeds = PeakSeedAccumulator(
            minimumAngularSeparationDegrees =
                stepDegrees * PEAK_SEED_MINIMUM_SEPARATION_STEPS,
        )
        for (elevationIndex in 0..elevationIntervals) {
            for (azimuthIndex in 0 until azimuthCount) {
                if (
                    (elevationIndex == 0 || elevationIndex == elevationIntervals) &&
                    azimuthIndex != 0
                ) {
                    continue
                }
                val power = cache.powerAt(
                    azimuthIndex = azimuthIndex * stride,
                    elevationIndex = elevationIndex * stride,
                ) ?: return GridIntegration.BudgetExceeded
                val isLocalMaximum = isGridLocalMaximum(
                    cache = cache,
                    stride = stride,
                    azimuthIndex = azimuthIndex,
                    elevationIndex = elevationIndex,
                    azimuthCount = azimuthCount,
                    elevationIntervals = elevationIntervals,
                    candidatePower = power,
                ) ?: return GridIntegration.BudgetExceeded
                if (isLocalMaximum) {
                    peakSeeds.consider(
                        PeakEstimate(
                            power = power,
                            horizontalDegrees = azimuthIndex * stepDegrees,
                            elevationDegrees = -90.0 + elevationIndex * stepDegrees,
                        ),
                    )
                }
            }
        }

        if (
            !solidAnglePowerIntegral.isFinite() || solidAnglePowerIntegral <= 0.0 ||
            peakSeeds.values.isEmpty() || peakSeeds.values.first().power <= 0.0
        ) {
            return GridIntegration.Invalid
        }
        return GridIntegration.Available(
            solidAnglePowerIntegral = solidAnglePowerIntegral,
            peakSeeds = peakSeeds.values,
        )
    }

    private fun isGridLocalMaximum(
        cache: SphericalPowerGridCache,
        stride: Int,
        azimuthIndex: Int,
        elevationIndex: Int,
        azimuthCount: Int,
        elevationIntervals: Int,
        candidatePower: Double,
    ): Boolean? {
        if (elevationIndex == 0 || elevationIndex == elevationIntervals) {
            val adjacentElevationIndex = if (elevationIndex == 0) 1 else elevationIntervals - 1
            for (neighborAzimuthIndex in 0 until azimuthCount) {
                val neighborPower = cache.powerAt(
                    azimuthIndex = neighborAzimuthIndex * stride,
                    elevationIndex = adjacentElevationIndex * stride,
                ) ?: return null
                if (isMeaningfullyGreater(neighborPower, candidatePower)) return false
                val powersAreEquivalent =
                    !isMeaningfullyGreater(candidatePower, neighborPower) &&
                        !isMeaningfullyGreater(neighborPower, candidatePower)
                if (powersAreEquivalent && adjacentElevationIndex < elevationIndex) return false
            }
            return true
        }
        for (elevationOffset in -1..1) {
            val neighborElevationIndex = elevationIndex + elevationOffset
            if (neighborElevationIndex !in 0..elevationIntervals) continue
            for (azimuthOffset in -1..1) {
                if (elevationOffset == 0 && azimuthOffset == 0) continue
                val neighborAzimuthIndex = if (
                    neighborElevationIndex == 0 ||
                    neighborElevationIndex == elevationIntervals
                ) {
                    0
                } else {
                    Math.floorMod(azimuthIndex + azimuthOffset, azimuthCount)
                }
                if (
                    neighborElevationIndex == elevationIndex &&
                    neighborAzimuthIndex == azimuthIndex
                ) {
                    continue
                }
                val neighborPower = cache.powerAt(
                    azimuthIndex = neighborAzimuthIndex * stride,
                    elevationIndex = neighborElevationIndex * stride,
                ) ?: return null
                if (isMeaningfullyGreater(neighborPower, candidatePower)) return false
                val powersAreEquivalent =
                    !isMeaningfullyGreater(candidatePower, neighborPower) &&
                        !isMeaningfullyGreater(neighborPower, candidatePower)
                val neighborPrecedesCandidate =
                    neighborElevationIndex < elevationIndex ||
                        neighborElevationIndex == elevationIndex &&
                        neighborAzimuthIndex < azimuthIndex
                if (powersAreEquivalent && neighborPrecedesCandidate) return false
            }
        }
        return true
    }

    private fun refinePeak(
        evaluator: BoundedFieldEvaluator,
        seeds: List<PeakEstimate>,
        initialStepDegrees: Double,
    ): PeakRefinement {
        var globalPeak: PeakEstimate? = null
        seeds.forEach { seed ->
            var current = seed
            var stepDegrees = initialStepDegrees
            var iterationCount = 0
            while (stepDegrees > PEAK_ANGULAR_TOLERANCE_DEGREES) {
                if (iterationCount >= MAX_PEAK_REFINEMENT_ITERATIONS) {
                    return PeakRefinement.DidNotConverge
                }
                iterationCount += 1
                var improved = current
                for (elevationOffset in -1..1) {
                    for (horizontalOffset in -1..1) {
                        if (horizontalOffset == 0 && elevationOffset == 0) continue
                        val candidateHorizontal = wrap360(
                            current.horizontalDegrees + horizontalOffset * stepDegrees,
                        )
                        val candidateElevation = (
                            current.elevationDegrees + elevationOffset * stepDegrees
                            ).coerceIn(-90.0, 90.0)
                        if (
                            candidateHorizontal == current.horizontalDegrees &&
                            candidateElevation == current.elevationDegrees
                        ) {
                            continue
                        }
                        val field = evaluator.evaluate(
                            ApertureDirection.fromAngles(
                                horizontalAngleDegrees = candidateHorizontal,
                                elevationAngleDegrees = candidateElevation,
                            ),
                        ) ?: return PeakRefinement.BudgetExceeded
                        val candidate = PeakEstimate(
                            power = field.power,
                            horizontalDegrees = candidateHorizontal,
                            elevationDegrees = candidateElevation,
                        )
                        if (!candidate.power.isFinite() || candidate.power < 0.0) {
                            return PeakRefinement.Invalid
                        }
                        if (isMeaningfullyGreater(candidate.power, improved.power)) {
                            improved = candidate
                        }
                    }
                }
                if (improved === current) {
                    stepDegrees /= 2.0
                } else {
                    current = improved
                }
            }
            val priorGlobalPeak = globalPeak
            if (priorGlobalPeak == null || isMeaningfullyGreater(current.power, priorGlobalPeak.power)) {
                globalPeak = current
            }
        }
        val peak = globalPeak
        return if (peak == null || !peak.power.isFinite() || peak.power <= 0.0) {
            PeakRefinement.Invalid
        } else {
            PeakRefinement.Available(peak)
        }
    }

    private fun requiredGridStepRequirement(
        configuration: AntennaArrayConfiguration,
        poweredElements: List<AntennaArrayElement>,
        initialStepDegrees: Double,
    ): NumericalResolutionRequirement {
        val spatialStepDegrees = requiredSpatialGridStepDegrees(
            configuration = configuration,
            poweredElements = poweredElements,
            initialStepDegrees = initialStepDegrees,
        )
        val sourcePatternStepDegrees = requiredSourcePatternGridStepDegrees(
            poweredElements = poweredElements,
            initialStepDegrees = initialStepDegrees,
        )
        return when {
            spatialStepDegrees < sourcePatternStepDegrees -> NumericalResolutionRequirement(
                stepDegrees = spatialStepDegrees,
                basis = "array aperture phase-advance bound",
            )
            sourcePatternStepDegrees < spatialStepDegrees -> NumericalResolutionRequirement(
                stepDegrees = sourcePatternStepDegrees,
                basis = "source-pattern complex-field variation bound",
            )
            else -> NumericalResolutionRequirement(
                stepDegrees = spatialStepDegrees,
                basis = "array aperture and source-pattern variation bounds",
            )
        }
    }

    private fun requiredSpatialGridStepDegrees(
        configuration: AntennaArrayConfiguration,
        poweredElements: List<AntennaArrayElement>,
        initialStepDegrees: Double,
    ): Double {
        var maximumBaselineMeters = 0.0
        for (firstIndex in 0 until poweredElements.lastIndex) {
            val first = poweredElements[firstIndex].positionMeters
            for (secondIndex in firstIndex + 1..poweredElements.lastIndex) {
                val second = poweredElements[secondIndex].positionMeters
                val dx = first.xMeters - second.xMeters
                val dy = first.yMeters - second.yMeters
                val dz = first.zMeters - second.zMeters
                maximumBaselineMeters = maxOf(
                    maximumBaselineMeters,
                    sqrt(dx.pow(2) + dy.pow(2) + dz.pow(2)),
                )
            }
        }
        if (maximumBaselineMeters <= 0.0) return initialStepDegrees
        val wavelengthMeters = AntennaPatternEngine.SPEED_OF_LIGHT_METERS_PER_SECOND /
            configuration.frequencyHz
        val maximumBaselineWavelengths = maximumBaselineMeters / wavelengthMeters
        val phaseBoundRadians = MAX_INTER_ELEMENT_PHASE_ADVANCE_RADIANS /
            (2.0 * PI * maximumBaselineWavelengths)
        return minOf(initialStepDegrees, Math.toDegrees(phaseBoundRadians))
    }

    private fun requiredSourcePatternGridStepDegrees(
        poweredElements: List<AntennaArrayElement>,
        initialStepDegrees: Double,
    ): Double {
        val maximumComplexSlopePerDegree = poweredElements.maxOf { element ->
            maximumCutComplexSlopePerDegree(element.pattern.horizontalCut) +
                maximumCutComplexSlopePerDegree(element.pattern.verticalCut)
        }
        if (maximumComplexSlopePerDegree <= 0.0) return initialStepDegrees
        return minOf(
            initialStepDegrees,
            MAX_COMPLEX_FIELD_CHANGE_PER_GRID_STEP / maximumComplexSlopePerDegree,
        )
    }

    private fun maximumCutComplexSlopePerDegree(cut: AntennaPatternCut): Double {
        var maximumSlope = 0.0
        cut.samples.zipWithNext().forEach { (first, second) ->
            maximumSlope = maxOf(
                maximumSlope,
                complexSampleSlopePerDegree(
                    first = first,
                    second = second,
                    angularSeparationDegrees = second.angleDegrees - first.angleDegrees,
                ),
            )
        }
        if (cut.plane == PatternCutPlane.HORIZONTAL) {
            val first = cut.samples.first()
            val last = cut.samples.last()
            maximumSlope = maxOf(
                maximumSlope,
                complexSampleSlopePerDegree(
                    first = last,
                    second = first,
                    angularSeparationDegrees = first.angleDegrees + 360.0 - last.angleDegrees,
                ),
            )
        }
        return maximumSlope
    }

    private fun complexSampleSlopePerDegree(
        first: PatternSample,
        second: PatternSample,
        angularSeparationDegrees: Double,
    ): Double {
        val firstField = ComplexField.fromPolar(
            amplitude = first.normalizedFieldAmplitude,
            phaseDegrees = first.phaseDegrees ?: 0.0,
        )
        val secondField = ComplexField.fromPolar(
            amplitude = second.normalizedFieldAmplitude,
            phaseDegrees = second.phaseDegrees ?: 0.0,
        )
        val realDifference = secondField.real - firstField.real
        val imaginaryDifference = secondField.imaginary - firstField.imaginary
        return sqrt(realDifference.pow(2) + imaginaryDifference.pow(2)) /
            angularSeparationDegrees
    }

    private fun refinementSteps(options: AntennaCompositionOptions): List<Double> = buildList {
        var stepDegrees = options.directivityGridStepDegrees
        repeat(options.maximumRefinementLevels) {
            add(stepDegrees)
            val nextStep = stepDegrees / 2.0
            if (
                nextStep + GRID_RATIO_TOLERANCE <
                options.minimumDirectivityGridStepDegrees
            ) {
                return@buildList
            }
            stepDegrees = nextStep
        }
    }

    private fun sphericalGridPointCount(stepDegrees: Double): Int {
        val azimuthCount = (360.0 / stepDegrees).roundToInt()
        val elevationCount = (180.0 / stepDegrees).roundToInt() + 1
        return Math.multiplyExact(azimuthCount, elevationCount)
    }

    private fun waveNumberRadiansPerMeter(frequencyHz: Double): Double =
        2.0 * PI * frequencyHz / AntennaPatternEngine.SPEED_OF_LIGHT_METERS_PER_SECOND

    private fun relativeDifference(current: Double, previous: Double): Double =
        abs(current - previous) / maxOf(abs(current), abs(previous), 1.0e-300)

    private fun isMeaningfullyGreater(candidate: Double, incumbent: Double): Boolean =
        candidate > incumbent + maxOf(
            incumbent * PEAK_IMPROVEMENT_RELATIVE_TOLERANCE,
            1.0e-300,
        )

    private fun cpuBudgetReason(): String =
        "Peak/directivity convergence exceeds the bounded $MAX_FIELD_EVALUATIONS element-field evaluation budget."

    private class BoundedFieldEvaluator(
        private val configuration: AntennaArrayConfiguration,
        private val poweredElements: List<AntennaArrayElement>,
    ) {
        private val waveNumberRadiansPerMeter =
            waveNumberRadiansPerMeter(configuration.frequencyHz)

        var fieldEvaluationCount: Long = 0L
            private set

        fun evaluate(direction: ApertureDirection): ComplexField? {
            val requestedEvaluations = poweredElements.size.toLong()
            if (fieldEvaluationCount > MAX_FIELD_EVALUATIONS - requestedEvaluations) return null
            fieldEvaluationCount += requestedEvaluations
            return evaluateAvailableField(
                poweredElements = poweredElements,
                waveNumberRadiansPerMeter = waveNumberRadiansPerMeter,
                direction = direction,
            )
        }
    }

    private class SphericalPowerGridCache(
        val minimumStepDegrees: Double,
        private val evaluator: BoundedFieldEvaluator,
    ) {
        val azimuthIntervals = (360.0 / minimumStepDegrees).roundToInt()
        val elevationIntervals = (180.0 / minimumStepDegrees).roundToInt()
        private val powers = DoubleArray(
            Math.multiplyExact(azimuthIntervals, elevationIntervals + 1),
        ) { Double.NaN }

        fun powerAt(azimuthIndex: Int, elevationIndex: Int): Double? {
            val index = elevationIndex * azimuthIntervals + azimuthIndex
            val cached = powers[index]
            if (!cached.isNaN()) return cached
            val field = evaluator.evaluate(
                ApertureDirection.fromAngles(
                    horizontalAngleDegrees = azimuthIndex * minimumStepDegrees,
                    elevationAngleDegrees = -90.0 + elevationIndex * minimumStepDegrees,
                ),
            ) ?: return null
            val power = field.power
            powers[index] = power
            return power
        }
    }

    private class PeakSeedAccumulator(
        private val minimumAngularSeparationDegrees: Double,
    ) {
        private val mutableValues = mutableListOf<PeakEstimate>()
        private val comparator = compareByDescending<PeakEstimate>(PeakEstimate::power)
            .thenBy(PeakEstimate::elevationDegrees)
            .thenBy(PeakEstimate::horizontalDegrees)
        val values: List<PeakEstimate>
            get() = mutableValues

        fun consider(candidate: PeakEstimate) {
            val conflictingSeeds = mutableValues.filter { seed ->
                sphericalAngularSeparationDegrees(candidate, seed) + GRID_RATIO_TOLERANCE <
                    minimumAngularSeparationDegrees
            }
            if (conflictingSeeds.isNotEmpty()) {
                val bestConflict = conflictingSeeds.minWith(comparator)
                if (comparator.compare(candidate, bestConflict) >= 0) return
                mutableValues.removeAll(conflictingSeeds.toSet())
            }
            mutableValues += candidate
            mutableValues.sortWith(comparator)
            if (mutableValues.size > PEAK_SEED_COUNT) {
                mutableValues.removeAt(mutableValues.lastIndex)
            }
        }
    }

    private fun sphericalAngularSeparationDegrees(
        first: PeakEstimate,
        second: PeakEstimate,
    ): Double {
        val firstElevationRadians = Math.toRadians(first.elevationDegrees)
        val secondElevationRadians = Math.toRadians(second.elevationDegrees)
        val horizontalDifferenceRadians = Math.toRadians(
            first.horizontalDegrees - second.horizontalDegrees,
        )
        val directionDotProduct =
            sin(firstElevationRadians) * sin(secondElevationRadians) +
                cos(firstElevationRadians) * cos(secondElevationRadians) *
                cos(horizontalDifferenceRadians)
        return Math.toDegrees(acos(directionDotProduct.coerceIn(-1.0, 1.0)))
    }

    private data class PeakEstimate(
        val power: Double,
        val horizontalDegrees: Double,
        val elevationDegrees: Double,
    )

    private data class NumericalResolutionRequirement(
        val stepDegrees: Double,
        val basis: String,
    )

    private data class MissingRequiredSourcePhase(
        val elementId: String,
        val plane: PatternCutPlane,
        val angleDegrees: Double,
    ) {
        fun message(): String =
            "Element $elementId has phase NoData at the non-zero ${plane.name} field sample " +
                "${angleDegrees.formatEngineering()} degrees; coherent synthesis was not run " +
                "because zero phase will not be assumed."

        fun toWarning(): AntennaArrayWarning = AntennaArrayWarning(
            code = AntennaArrayWarningCode.SOURCE_PHASE_NO_DATA,
            message = message(),
        )
    }

    private fun firstMissingRequiredSourcePhase(
        poweredElements: List<AntennaArrayElement>,
    ): MissingRequiredSourcePhase? {
        poweredElements.forEach { element ->
            listOf(element.pattern.horizontalCut, element.pattern.verticalCut).forEach { cut ->
                val sample = cut.samples.firstOrNull { candidate ->
                    candidate.normalizedFieldAmplitude > 0.0 && candidate.phaseDegrees == null
                }
                if (sample != null) {
                    return MissingRequiredSourcePhase(
                        elementId = element.id,
                        plane = cut.plane,
                        angleDegrees = sample.angleDegrees,
                    )
                }
            }
        }
        return null
    }

    private sealed interface GridIntegration {
        data class Available(
            val solidAnglePowerIntegral: Double,
            val peakSeeds: List<PeakEstimate>,
        ) : GridIntegration

        object BudgetExceeded : GridIntegration
        object Invalid : GridIntegration
    }

    private sealed interface PeakRefinement {
        data class Available(val peak: PeakEstimate) : PeakRefinement
        object BudgetExceeded : PeakRefinement
        object DidNotConverge : PeakRefinement
        object Invalid : PeakRefinement
    }

    private sealed interface DirectivityConvergence {
        data class Available(
            val directivityLinear: Double,
            val peak: PeakEstimate,
            val stepDegrees: Double,
            val refinementLevels: Int,
            val peakRelativeChange: Double,
            val directivityRelativeChange: Double,
        ) : DirectivityConvergence

        data class NoData(val reason: String) : DirectivityConvergence
        data class Unsupported(val reason: String) : DirectivityConvergence
    }

    private fun geometryWarnings(
        configuration: AntennaArrayConfiguration,
        poweredElements: List<AntennaArrayElement>,
    ): List<AntennaArrayWarning> = buildList {
        add(
            AntennaArrayWarning(
                code = AntennaArrayWarningCode.SEPARABLE_CUT_REPRESENTATION,
                message =
                    "Available scalar gain is derived from the converged 3D field, while HRP and " +
                        "VRP are independently normalized separable cuts; the cuts do not " +
                        "reconstruct the full 3D or absolute field.",
            ),
        )
        if (poweredElements.size >= 2) {
            val wavelengthMeters = AntennaPatternEngine.SPEED_OF_LIGHT_METERS_PER_SECOND /
                configuration.frequencyHz
            var nearestSpacingMeters = Double.POSITIVE_INFINITY
            for (firstIndex in 0 until poweredElements.lastIndex) {
                val first = poweredElements[firstIndex].positionMeters
                for (secondIndex in firstIndex + 1..poweredElements.lastIndex) {
                    val second = poweredElements[secondIndex].positionMeters
                    val dx = first.xMeters - second.xMeters
                    val dy = first.yMeters - second.yMeters
                    val dz = first.zMeters - second.zMeters
                    nearestSpacingMeters = minOf(
                        nearestSpacingMeters,
                        sqrt(dx.pow(2) + dy.pow(2) + dz.pow(2)),
                    )
                }
            }
            val nearestSpacingWavelengths = nearestSpacingMeters / wavelengthMeters
            if (nearestSpacingWavelengths > 0.5) {
                add(
                    AntennaArrayWarning(
                        code = AntennaArrayWarningCode.GRATING_LOBE_RISK,
                        message =
                            "Nearest active-element spacing exceeds 0.5 wavelength; grating lobes may occur.",
                    ),
                )
            }
            if (nearestSpacingWavelengths < 0.45) {
                add(
                    AntennaArrayWarning(
                        code = AntennaArrayWarningCode.MUTUAL_COUPLING_NOT_MODELED,
                        message =
                            "Nearest active-element spacing is below 0.45 wavelength; mutual coupling is not modeled.",
                    ),
                )
            }
        }
        if ((configuration.declaredScanAngleDegrees ?: 0.0) > 45.0) {
            add(
                AntennaArrayWarning(
                    code = AntennaArrayWarningCode.LARGE_SCAN_ANGLE,
                    message =
                        "The declared scan angle exceeds 45 degrees; gain loss and sidelobe growth require validation.",
                ),
            )
        }
        val totalPowerFraction = poweredElements.sumOf(AntennaArrayElement::powerFraction)
        if (kotlin.math.abs(totalPowerFraction - 1.0) > 1.0e-9) {
            add(
                AntennaArrayWarning(
                    code = AntennaArrayWarningCode.POWER_FRACTIONS_NOT_NORMALIZED,
                    message =
                        "Active element power fractions total $totalPowerFraction instead of 1.0; field shape is retained and normalized only at the output cuts.",
                ),
            )
        }
        poweredElements.forEach { element ->
            val nominalFrequency = element.pattern.nominalFrequencyHz ?: return@forEach
            if (kotlin.math.abs(nominalFrequency - configuration.frequencyHz) /
                configuration.frequencyHz > 0.05
            ) {
                add(
                    AntennaArrayWarning(
                        code = AntennaArrayWarningCode.SOURCE_FREQUENCY_MISMATCH,
                        message =
                            "Element ${element.id} pattern frequency differs from the array frequency by more than 5 percent.",
                    ),
                )
            }
        }
    }.distinctBy { warning -> warning.code to warning.message }

    private fun synthesizedProvenance(
        configuration: AntennaArrayConfiguration,
        warnings: List<AntennaArrayWarning>,
    ): PatternProvenance = PatternProvenance(
        origin = PatternOrigin.SYNTHESIZED,
        sourceLabel = configuration.name,
        sourceFormat = "ATX coherent array",
        coordinateFrame = PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
        sourceCoordinateFrame = configuration.coordinateFrame,
        engineId = ENGINE_ID,
        warnings = warnings.map(AntennaArrayWarning::message),
        limitations = listOf(
            "This synthesized result is not a measured or full-wave antenna pattern.",
            "Mutual coupling, tower scattering and the physical feed network are not modeled.",
            "The converged 3D scalar gain and independently normalized separable HRP/VRP do not " +
                "reconstruct the full 3D or absolute field.",
        ),
    )

    private fun linearPowerToDb(value: Double): Double = 10.0 * ln(value) / ln(10.0)

    private fun withBoundedSuffix(
        value: String,
        suffix: String,
    ): String = value.take(AntennaPatternLimits.MAX_TEXT_LENGTH - suffix.length) + suffix
}

private const val GRID_RATIO_TOLERANCE = 1.0e-9

private fun tilesSphere(stepDegrees: Double): Boolean =
    isEffectivelyInteger(180.0 / stepDegrees) &&
        isEffectivelyInteger(360.0 / stepDegrees)

private fun isEffectivelyInteger(value: Double): Boolean =
    value.isFinite() && abs(value - value.roundToInt()) <= GRID_RATIO_TOLERANCE

private fun wrap360(angleDegrees: Double): Double {
    val wrapped = angleDegrees % 360.0
    return if (wrapped < 0.0) wrapped + 360.0 else wrapped
}

private fun Double.formatEngineering(): String = toString()
