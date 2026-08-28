package com.gecesars.atxplan.domain.antenna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntennaArrayComposerTest {
    @Test
    fun `single isotropic element produces normalized cuts and unit directivity`() {
        val configuration = configuration(
            elements = listOf(
                element(
                    id = "single",
                    xWavelengths = 0.0,
                    powerFraction = 1.0,
                ),
            ),
            efficiency = 0.5,
        )

        val result = AntennaArrayComposer.compose(configuration).requireAvailable()

        assertEquals(360, result.pattern.horizontalCut.samples.size)
        assertEquals(181, result.pattern.verticalCut.samples.size)
        assertTrue(result.pattern.horizontalCut.samples.all { sample ->
            kotlin.math.abs(sample.normalizedFieldAmplitude - 1.0) <= STRICT_TOLERANCE
        })
        assertTrue(result.pattern.verticalCut.samples.all { sample ->
            kotlin.math.abs(sample.normalizedFieldAmplitude - 1.0) <= STRICT_TOLERANCE
        })
        assertEquals(1.0, result.metrics.directivityLinear, DIRECTIVITY_TOLERANCE)
        assertEquals(0.0, result.metrics.directivityDbi, DIRECTIVITY_DB_TOLERANCE)
        assertEquals(-3.010_299_956_64, result.metrics.gainDbi, DIRECTIVITY_DB_TOLERANCE)
        assertEquals(result.metrics.gainDbi - 2.15, result.metrics.gainDbd, STRICT_TOLERANCE)
        assertEquals(PatternOrigin.SYNTHESIZED, result.provenance.origin)
        assertTrue(result.provenance.limitations.any { limitation ->
            limitation.contains("not a measured or full-wave")
        })
        val representationWarning = result.warnings.single { warning ->
            warning.code == AntennaArrayWarningCode.SEPARABLE_CUT_REPRESENTATION
        }
        assertTrue(representationWarning.message.contains("converged 3D field"))
        assertTrue(representationWarning.message.contains("independently normalized separable cuts"))
        assertTrue(representationWarning.message.contains("full 3D or absolute field"))
        assertTrue(representationWarning.message in result.provenance.warnings)
        assertTrue(result.provenance.limitations.any { limitation ->
            limitation.contains("converged 3D scalar gain") &&
                limitation.contains("independently normalized separable HRP/VRP") &&
                limitation.contains("full 3D or absolute field")
        })
    }

    @Test
    fun `two element half wavelength array is symmetric and has broadside maximum`() {
        val configuration = configuration(
            elements = listOf(
                element("left", xWavelengths = -0.25, powerFraction = 0.5),
                element("right", xWavelengths = 0.25, powerFraction = 0.5),
            ),
        )

        val broadside = AntennaArrayComposer.evaluateField(configuration, 0.0, 0.0).magnitude
        val positiveDiagonal = AntennaArrayComposer.evaluateField(configuration, 45.0, 0.0).magnitude
        val negativeDiagonal = AntennaArrayComposer.evaluateField(configuration, 315.0, 0.0).magnitude
        val arrayPlane = AntennaArrayComposer.evaluateField(configuration, 90.0, 0.0).magnitude
        val result = AntennaArrayComposer.compose(configuration).requireAvailable()

        assertEquals(sqrtTwo(), broadside, NUMERICAL_TOLERANCE)
        assertEquals(positiveDiagonal, negativeDiagonal, NUMERICAL_TOLERANCE)
        assertEquals(0.0, arrayPlane, NUMERICAL_TOLERANCE)
        assertEquals(
            1.0,
            result.pattern.horizontalCut.samples.single { sample ->
                sample.angleDegrees == 0.0
            }.normalizedFieldAmplitude,
            STRICT_TOLERANCE,
        )
        assertEquals(
            0.0,
            result.pattern.horizontalCut.samples.single { sample ->
                sample.angleDegrees == 90.0
            }.normalizedFieldAmplitude,
            NUMERICAL_TOLERANCE,
        )
    }

    @Test
    fun `feed phase steers a two element array toward positive horizontal angle`() {
        val configuration = configuration(
            elements = listOf(
                element(
                    id = "left",
                    xWavelengths = -0.25,
                    powerFraction = 0.5,
                    feedPhaseDegrees = 45.0,
                ),
                element(
                    id = "right",
                    xWavelengths = 0.25,
                    powerFraction = 0.5,
                    feedPhaseDegrees = -45.0,
                ),
            ),
            declaredScanAngleDegrees = 30.0,
        )

        val steered = AntennaArrayComposer.evaluateField(configuration, 30.0, 0.0).magnitude
        val opposite = AntennaArrayComposer.evaluateField(configuration, 330.0, 0.0).magnitude
        val result = AntennaArrayComposer.compose(configuration).requireAvailable()
        val peak = result.pattern.horizontalCut.samples.maxBy(PatternSample::normalizedFieldAmplitude)

        assertEquals(sqrtTwo(), steered, NUMERICAL_TOLERANCE)
        assertEquals(0.0, opposite, NUMERICAL_TOLERANCE)
        assertEquals(30.0, peak.angleDegrees, STRICT_TOLERANCE)
        assertEquals(1.0, peak.normalizedFieldAmplitude, STRICT_TOLERANCE)
    }

    @Test
    fun `narrow scanned array converges to its analytic peak and directivity`() {
        val elementCount = 16
        val scanAngleDegrees = 17.35
        val configuration = scannedLinearArray(
            elementCount = elementCount,
            spacingWavelengths = 0.5,
            scanAngleDegrees = scanAngleDegrees,
        )

        val result = AntennaArrayComposer.compose(configuration).requireAvailable()

        // Equal-power isotropic elements at integer half-wavelength spacings have zero spherical
        // cross-term integrals, so their ideal coherent peak and directivity both equal N.
        assertEquals(elementCount.toDouble(), result.metrics.peakPower, NARROW_ARRAY_TOLERANCE)
        assertEquals(
            elementCount.toDouble(),
            result.metrics.directivityLinear,
            NARROW_ARRAY_TOLERANCE,
        )
        assertTrue(result.metrics.integrationAzimuthStepDegrees <= 0.5)
        assertTrue(result.metrics.refinementLevels >= 3)
        assertTrue(
            result.metrics.peakRelativeChange <=
                AntennaCompositionOptions().relativeConvergenceTolerance,
        )
        assertTrue(
            result.metrics.directivityRelativeChange <=
                AntennaCompositionOptions().relativeConvergenceTolerance,
        )
        assertTrue(result.metrics.fieldEvaluationCount <= AntennaArrayComposer.MAX_FIELD_EVALUATIONS)
        assertEquals("atx-plan-android-coherent-array-v2", result.provenance.engineId)
    }

    @Test
    fun `vertically scanned y axis array fails closed when HRP misses the 3D peak`() {
        val scanAngleDegrees = 30.0
        val scanDirectionY = kotlin.math.sin(Math.toRadians(scanAngleDegrees))
        val configuration = configuration(
            elements = listOf(-0.25, 0.25).mapIndexed { index, yWavelengths ->
                element(
                    id = "vertical-$index",
                    xWavelengths = 0.0,
                    powerFraction = 0.5,
                    feedPhaseDegrees = -360.0 * yWavelengths * scanDirectionY,
                    yWavelengths = yWavelengths,
                )
            },
            declaredScanAngleDegrees = scanAngleDegrees,
        )

        val result = AntennaArrayComposer.compose(configuration)

        assertTrue(result is AntennaCompositionOutcome.Unsupported)
        val unsupported = result as AntennaCompositionOutcome.Unsupported
        assertTrue(unsupported.reason.contains("sampled canonical HRP peak"))
        assertTrue(unsupported.reason.contains("converged 3D peak"))
        assertTrue(unsupported.reason.contains("0.1 dB representation limit"))
        assertTrue(unsupported.warnings.any { warning ->
            warning.code == AntennaArrayWarningCode.SEPARABLE_CUT_REPRESENTATION
        })
    }

    @Test
    fun `spatially separated grating lobes retain deterministic converged peak search`() {
        val configuration = configuration(
            elements = listOf(
                element("left", xWavelengths = -0.5, powerFraction = 0.5),
                element("right", xWavelengths = 0.5, powerFraction = 0.5),
            ),
        )

        val first = AntennaArrayComposer.compose(configuration).requireAvailable()
        val second = AntennaArrayComposer.compose(configuration).requireAvailable()

        assertEquals(2.0, first.metrics.peakPower, NARROW_ARRAY_TOLERANCE)
        assertEquals(first.metrics, second.metrics)
        assertTrue(first.warnings.any { warning ->
            warning.code == AntennaArrayWarningCode.GRATING_LOBE_RISK
        })
    }

    @Test
    fun `converged synthesis is exactly deterministic across repeated runs`() {
        val configuration = scannedLinearArray(
            elementCount = 4,
            spacingWavelengths = 0.5,
            scanAngleDegrees = 11.25,
        )

        val first = AntennaArrayComposer.compose(configuration).requireAvailable()
        val second = AntennaArrayComposer.compose(configuration).requireAvailable()

        assertEquals(first.metrics, second.metrics)
        assertEquals(first.pattern, second.pattern)
    }

    @Test
    fun `unconverged peak and directivity return explicit Unsupported`() {
        val configuration = scannedLinearArray(
            elementCount = 4,
            spacingWavelengths = 0.5,
            scanAngleDegrees = 11.25,
        )

        val result = AntennaArrayComposer.compose(
            configuration = configuration,
            options = AntennaCompositionOptions(
                relativeConvergenceTolerance = 1.0e-12,
                maximumRefinementLevels = 2,
            ),
        )

        assertTrue(result is AntennaCompositionOutcome.Unsupported)
        assertTrue((result as AntennaCompositionOutcome.Unsupported).reason.contains("did not converge"))
    }

    @Test
    fun `aperture finer than bounded mobile grid returns explicit Unsupported`() {
        val configuration = configuration(
            elements = listOf(
                element("left", xWavelengths = -32.0, powerFraction = 0.5),
                element("right", xWavelengths = 32.0, powerFraction = 0.5),
            ),
        )

        val result = AntennaArrayComposer.compose(configuration)

        assertTrue(result is AntennaCompositionOutcome.Unsupported)
        val unsupported = result as AntennaCompositionOutcome.Unsupported
        assertTrue(unsupported.reason.contains("aperture"))
        assertTrue(unsupported.reason.contains("supported minimum"))
    }

    @Test
    fun `source pattern finer than bounded mobile grid returns explicit Unsupported`() {
        val isotropic = CanonicalAntennaPattern.isotropic(
            nominalFrequencyHz = TEST_FREQUENCY_HZ,
        )
        val sharpHorizontalCut = AntennaPatternCut(
            plane = PatternCutPlane.HORIZONTAL,
            samples = listOf(
                PatternSample(0.0, 1.0, 0.0),
                PatternSample(0.1, 0.0, 0.0),
                PatternSample(180.0, 0.0, 0.0),
            ),
            provenance = isotropic.provenance,
            availability = PatternCutAvailability.AVAILABLE,
        )
        val sharpPattern = isotropic.copy(horizontalCut = sharpHorizontalCut)
        val result = AntennaArrayComposer.compose(
            configuration(
                elements = listOf(
                    element("sharp", xWavelengths = 0.0, powerFraction = 1.0)
                        .copy(pattern = sharpPattern),
                ),
            ),
        )

        assertTrue(result is AntennaCompositionOutcome.Unsupported)
        val unsupported = result as AntennaCompositionOutcome.Unsupported
        assertTrue(unsupported.reason.contains("source-pattern"))
        assertTrue(unsupported.reason.contains("supported minimum"))
    }

    @Test
    fun `spacing scan and coupling risks are surfaced as structured warnings`() {
        val wide = configuration(
            elements = listOf(
                element("left", xWavelengths = -0.3, powerFraction = 0.5),
                element("right", xWavelengths = 0.3, powerFraction = 0.5),
            ),
            declaredScanAngleDegrees = 50.0,
        )
        val close = configuration(
            elements = listOf(
                element("left", xWavelengths = -0.2, powerFraction = 0.5),
                element("right", xWavelengths = 0.2, powerFraction = 0.5),
            ),
        )

        val wideWarnings = AntennaArrayComposer.compose(wide).requireAvailable().warnings
            .map(AntennaArrayWarning::code)
        val closeWarnings = AntennaArrayComposer.compose(close).requireAvailable().warnings
            .map(AntennaArrayWarning::code)

        assertTrue(AntennaArrayWarningCode.GRATING_LOBE_RISK in wideWarnings)
        assertTrue(AntennaArrayWarningCode.LARGE_SCAN_ANGLE in wideWarnings)
        assertTrue(AntennaArrayWarningCode.MUTUAL_COUPLING_NOT_MODELED in closeWarnings)
    }

    @Test
    fun `complete coherent cancellation returns explicit NoData`() {
        val configuration = configuration(
            elements = listOf(
                element("first", xWavelengths = 0.0, powerFraction = 0.5),
                element(
                    id = "second",
                    xWavelengths = 0.0,
                    powerFraction = 0.5,
                    feedPhaseDegrees = 180.0,
                ),
            ),
        )

        val result = AntennaArrayComposer.compose(configuration)

        assertTrue(result is AntennaCompositionOutcome.NoData)
        assertTrue((result as AntennaCompositionOutcome.NoData).reason.contains("cancels"))
    }

    @Test
    fun `display placeholder element returns explicit NoData instead of composing unity field`() {
        val source = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = TEST_FREQUENCY_HZ)
        val incomplete = source.copy(
            verticalCut = source.verticalCut.copy(
                availability = PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER,
            ),
        )
        val result = AntennaArrayComposer.compose(
            configuration(
                elements = listOf(
                    element("placeholder", xWavelengths = 0.0, powerFraction = 1.0)
                        .copy(pattern = incomplete),
                ),
            ),
        )

        assertTrue(result is AntennaCompositionOutcome.NoData)
        assertTrue((result as AntennaCompositionOutcome.NoData).reason.contains("display placeholders"))
    }

    @Test
    fun `magnitude-only source returns phase NoData instead of a synthesized phase`() {
        val source = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = TEST_FREQUENCY_HZ)
        val magnitudeOnly = source.copy(
            horizontalCut = source.horizontalCut.copy(
                samples = source.horizontalCut.samples.map { sample ->
                    sample.copy(phaseDegrees = null)
                },
            ),
            verticalCut = source.verticalCut.copy(
                samples = source.verticalCut.samples.map { sample ->
                    sample.copy(phaseDegrees = null)
                },
            ),
        )
        val configuration = configuration(
            elements = listOf(
                element("magnitude-only", xWavelengths = 0.0, powerFraction = 1.0)
                    .copy(pattern = magnitudeOnly),
            ),
        )

        val result = AntennaArrayComposer.compose(configuration)

        assertTrue(result is AntennaCompositionOutcome.NoData)
        val noData = result as AntennaCompositionOutcome.NoData
        assertTrue(noData.reason.contains("Element magnitude-only"))
        assertTrue(noData.reason.contains("phase NoData"))
        assertTrue(noData.reason.contains("non-zero HORIZONTAL field sample"))
        assertTrue(noData.reason.contains("zero phase will not be assumed"))
        val warning = noData.warnings.single { candidate ->
            candidate.code == AntennaArrayWarningCode.SOURCE_PHASE_NO_DATA
        }
        assertEquals(noData.reason, warning.message)
        assertTrue(warning.message in noData.provenance.warnings)
    }

    @Test
    fun `direct coherent evaluation rejects a magnitude-only source`() {
        val source = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = TEST_FREQUENCY_HZ)
        val magnitudeOnly = source.copy(
            horizontalCut = source.horizontalCut.copy(
                samples = source.horizontalCut.samples.map { sample ->
                    sample.copy(phaseDegrees = null)
                },
            ),
        )
        val configuration = configuration(
            elements = listOf(
                element("magnitude-only", xWavelengths = 0.0, powerFraction = 1.0)
                    .copy(pattern = magnitudeOnly),
            ),
        )

        val failure = try {
            AntennaArrayComposer.evaluateField(configuration, 0.0, 0.0)
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertTrue(failure?.message.orEmpty().contains("phase NoData"))
        assertTrue(failure?.message.orEmpty().contains("zero phase will not be assumed"))
    }

    @Test
    fun `phase NoData at an exact field null does not block coherent evaluation`() {
        val source = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = TEST_FREQUENCY_HZ)
        val nullWithNoPhase = source.copy(
            horizontalCut = source.horizontalCut.copy(
                samples = source.horizontalCut.samples.map { sample ->
                    if (sample.angleDegrees == 90.0) {
                        sample.copy(normalizedFieldAmplitude = 0.0, phaseDegrees = null)
                    } else {
                        sample
                    }
                },
            ),
        )
        val configuration = configuration(
            elements = listOf(
                element("field-null", xWavelengths = 0.0, powerFraction = 1.0)
                    .copy(pattern = nullWithNoPhase),
            ),
        )

        val field = AntennaArrayComposer.evaluateField(configuration, 0.0, 0.0)

        assertEquals(1.0, field.magnitude, STRICT_TOLERANCE)
    }

    private fun configuration(
        elements: List<AntennaArrayElement>,
        efficiency: Double = 1.0,
        declaredScanAngleDegrees: Double? = null,
    ): AntennaArrayConfiguration = AntennaArrayConfiguration(
        id = "array",
        name = "Array under test",
        frequencyHz = TEST_FREQUENCY_HZ,
        elements = elements,
        efficiency = efficiency,
        declaredScanAngleDegrees = declaredScanAngleDegrees,
    )

    private fun element(
        id: String,
        xWavelengths: Double,
        powerFraction: Double,
        feedPhaseDegrees: Double = 0.0,
        yWavelengths: Double = 0.0,
    ): AntennaArrayElement = AntennaArrayElement(
        id = id,
        positionMeters = AperturePositionMeters.fromWavelengths(
            xWavelengths = xWavelengths,
            yWavelengths = yWavelengths,
            frequencyHz = TEST_FREQUENCY_HZ,
        ),
        pattern = CanonicalAntennaPattern.isotropic(nominalFrequencyHz = TEST_FREQUENCY_HZ),
        powerFraction = powerFraction,
        feedPhaseDegrees = feedPhaseDegrees,
    )

    private fun scannedLinearArray(
        elementCount: Int,
        spacingWavelengths: Double,
        scanAngleDegrees: Double,
    ): AntennaArrayConfiguration {
        val centerIndex = (elementCount - 1) / 2.0
        val scanDirectionX = kotlin.math.sin(Math.toRadians(scanAngleDegrees))
        return configuration(
            elements = (0 until elementCount).map { index ->
                val xWavelengths = (index - centerIndex) * spacingWavelengths
                element(
                    id = "element-$index",
                    xWavelengths = xWavelengths,
                    powerFraction = 1.0 / elementCount,
                    feedPhaseDegrees = -360.0 * xWavelengths * scanDirectionX,
                )
            },
            declaredScanAngleDegrees = scanAngleDegrees,
        )
    }

    private fun AntennaCompositionOutcome.requireAvailable(): AntennaCompositionOutcome.Available {
        assertTrue(
            "Expected an available composition but received $this",
            this is AntennaCompositionOutcome.Available,
        )
        return this as AntennaCompositionOutcome.Available
    }

    private fun sqrtTwo(): Double = kotlin.math.sqrt(2.0)

    private companion object {
        const val TEST_FREQUENCY_HZ = 100_000_000.0
        const val STRICT_TOLERANCE = 1.0e-9
        const val NUMERICAL_TOLERANCE = 1.0e-10
        const val DIRECTIVITY_TOLERANCE = 5.0e-4
        const val DIRECTIVITY_DB_TOLERANCE = 0.002
        const val NARROW_ARRAY_TOLERANCE = 0.02
    }
}
