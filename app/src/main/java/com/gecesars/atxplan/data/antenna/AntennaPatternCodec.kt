package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternSample

/** Compact facade intended for ViewModels and document/storage adapters. */
data class ParsedAntennaPattern(
    val pattern: CanonicalAntennaPattern,
    val detectedFormat: AntennaPatternFileFormat,
    val valueConvention: AntennaPatternValueConvention,
    val metadata: AntennaPatternFileMetadata,
    val warnings: List<String>,
    val sourceSha256: String,
    val formatVersion: Int? = null,
) {
    val isCalculationReady: Boolean
        get() = pattern.isCalculationReady
}

data class AntennaPatternEncodeOptions(
    val nominalFrequencyHz: Double? = null,
    val title: String = "ATX Plan antenna pattern",
    val declaredGainDbi: Double? = null,
    val verticalCutAzimuthDegrees: Double? = null,
    val beamTiltDegrees: Double? = null,
)

/**
 * Single entry point for application-layer antenna import/export.
 *
 * A one-cut source file is made structurally usable by adding a clearly disclosed isotropic
 * placeholder for the missing plane. The placeholder is a compatibility assumption, never
 * presented as a measurement or calculated antenna result.
 */
object AntennaPatternCodec {
    fun parse(
        input: ByteArray,
        displayName: String,
        prnValueConventionOverride: PrnValueConventionOverride? = null,
    ): ParsedAntennaPattern {
        val decoded = AntennaPatternFileCodecs.decode(
            payload = input,
            sourceLabel = displayName,
            prnValueConventionOverride = prnValueConventionOverride,
        )
        val (pattern, facadeWarnings) = decoded.pattern?.let { complete ->
            complete to emptyList()
        } ?: completeSingleCut(decoded, displayName)
        return ParsedAntennaPattern(
            pattern = pattern,
            detectedFormat = decoded.detectedFormat,
            valueConvention = decoded.valueConvention,
            metadata = decoded.metadata,
            warnings = (decoded.warnings + facadeWarnings).distinct(),
            sourceSha256 = decoded.sourceSha256,
            formatVersion = decoded.formatVersion,
        )
    }

    /**
     * Convenience export. PAT intentionally needs the options overload because normalized cuts do
     * not contain gain or the azimuth of the represented vertical cut, and the codec will not
     * invent either source-format value.
     */
    fun encode(
        pattern: CanonicalAntennaPattern,
        format: AntennaPatternFileFormat,
    ): ByteArray = encode(pattern, format, AntennaPatternEncodeOptions())

    fun encode(
        pattern: CanonicalAntennaPattern,
        format: AntennaPatternFileFormat,
        options: AntennaPatternEncodeOptions,
    ): ByteArray = encodeArtifact(pattern, format, options).payload

    fun encodeArtifact(
        pattern: CanonicalAntennaPattern,
        format: AntennaPatternFileFormat,
        options: AntennaPatternEncodeOptions = AntennaPatternEncodeOptions(),
    ): AntennaPatternExportArtifact {
        if (!pattern.isCalculationReady) {
            throw AntennaPatternCodecException(
                "Antenna export requires explicitly available HRP and VRP cuts; " +
                    "display placeholders and legacy cut availability cannot be exported.",
            )
        }
        return when (format) {
            AntennaPatternFileFormat.PRN -> {
                val exportPattern = options.nominalFrequencyHz?.let { frequencyHz ->
                    pattern.copy(nominalFrequencyHz = frequencyHz)
                } ?: pattern
                AntennaPatternFileCodecs.encodePrn(
                    pattern = exportPattern,
                    declaredGainDbi = options.declaredGainDbi,
                )
            }

            AntennaPatternFileFormat.ADT_HRP -> AntennaPatternFileCodecs.encodeAdt(
                cut = pattern.horizontalCut,
                nominalFrequencyHz = options.nominalFrequencyHz
                    ?: pattern.nominalFrequencyHz
                    ?: throw AntennaPatternCodecException(
                        "ADT export requires a nominal frequency; no frequency will be invented.",
                    ),
                title = options.title,
            )

            AntennaPatternFileFormat.ADT_VRP -> AntennaPatternFileCodecs.encodeAdt(
                cut = pattern.verticalCut,
                nominalFrequencyHz = options.nominalFrequencyHz
                    ?: pattern.nominalFrequencyHz
                    ?: throw AntennaPatternCodecException(
                        "ADT export requires a nominal frequency; no frequency will be invented.",
                    ),
                title = options.title,
            )

            AntennaPatternFileFormat.VSOFT_HRP ->
                AntennaPatternFileCodecs.encodeVSoft(pattern.horizontalCut)

            AntennaPatternFileFormat.VSOFT_VRP ->
                AntennaPatternFileCodecs.encodeVSoft(
                    cut = pattern.verticalCut,
                    preservedBeamTiltDegrees = options.beamTiltDegrees,
                )

            AntennaPatternFileFormat.GENERIC_HRP_TABLE,
            AntennaPatternFileFormat.GENERIC_VRP_TABLE,
            -> throw AntennaPatternCodecException(
                "Generic antenna tables are import-only because their source dialect is not unique.",
            )

            AntennaPatternFileFormat.PROGIRA_EDX_PAT ->
                AntennaPatternFileCodecs.encodeProgiraEdxPat(
                    pattern = pattern,
                    declaredGainDbi = options.declaredGainDbi
                        ?: throw AntennaPatternCodecException(
                            "PAT export requires declaredGainDbi; normalized cuts do not contain gain.",
                        ),
                    verticalCutAzimuthDegrees = options.verticalCutAzimuthDegrees
                        ?: throw AntennaPatternCodecException(
                            "PAT export requires verticalCutAzimuthDegrees; " +
                                "the represented VRP azimuth will not be invented.",
                        ),
                )

            AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1 ->
                AntennaPatternFileCodecs.encodeCanonicalJson(
                    pattern = pattern,
                    metadata = AntennaPatternFileMetadata(
                        nominalFrequencyHz = options.nominalFrequencyHz ?: pattern.nominalFrequencyHz,
                        declaredGainDbi = options.declaredGainDbi,
                        verticalCutAzimuthDegrees = options.verticalCutAzimuthDegrees,
                        beamTiltDegrees = options.beamTiltDegrees,
                    ),
                )

            AntennaPatternFileFormat.ATX_DESKTOP_JSON_V1 -> {
                val exportPattern = options.nominalFrequencyHz?.let { frequencyHz ->
                    pattern.copy(nominalFrequencyHz = frequencyHz)
                } ?: pattern
                AntennaPatternFileCodecs.encodeDesktopJsonV1(
                    pattern = exportPattern,
                    declaredGainDbi = options.declaredGainDbi
                        ?: throw AntennaPatternCodecException(
                            "ATX Planner desktop JSON v1 export requires declaredGainDbi; " +
                                "normalized cuts do not contain gain.",
                        ),
                )
            }
        }
    }
}

private fun completeSingleCut(
    decoded: AntennaPatternImportResult,
    displayName: String,
): Pair<CanonicalAntennaPattern, List<String>> {
    val suppliedCut = decoded.cuts.singleOrNull() ?: throw AntennaPatternCodecException(
        "A partial antenna import must contain exactly one cut.",
    )
    val missingPlane = if (suppliedCut.plane == PatternCutPlane.HORIZONTAL) {
        PatternCutPlane.VERTICAL
    } else {
        PatternCutPlane.HORIZONTAL
    }
    val missingLabel = if (missingPlane == PatternCutPlane.VERTICAL) "VRP" else "HRP"
    val warning =
        "$missingLabel was not present in the ${decoded.detectedFormat.displayName} file; " +
            "an isotropic E/Emax = 1 placeholder " +
            "was added for canonical compatibility. It is not measured or calculated data."
    val limitation =
        "The $missingLabel cut is an isotropic planning placeholder and must be replaced before " +
            "directional engineering use."
    val commonProvenance = suppliedCut.provenance.copy(
        warnings = (suppliedCut.provenance.warnings + warning).distinct(),
        limitations = (suppliedCut.provenance.limitations + limitation).distinct(),
    )
    val preservedCut = suppliedCut.copy(provenance = commonProvenance)
    val placeholder = AntennaPatternCut(
        plane = missingPlane,
        samples = if (missingPlane == PatternCutPlane.HORIZONTAL) {
            listOf(0.0, 90.0, 180.0, 270.0).map { angle ->
                PatternSample(angle, 1.0, null)
            }
        } else {
            listOf(-90.0, 0.0, 90.0).map { angle ->
                PatternSample(angle, 1.0, null)
            }
        },
        provenance = commonProvenance,
        availability = PatternCutAvailability.ISOTROPIC_DISPLAY_PLACEHOLDER,
    )
    val horizontal = if (preservedCut.plane == PatternCutPlane.HORIZONTAL) {
        preservedCut
    } else {
        placeholder
    }
    val vertical = if (preservedCut.plane == PatternCutPlane.VERTICAL) {
        preservedCut
    } else {
        placeholder
    }
    val fileName = displayName.substringAfterLast('/').substringAfterLast('\\')
    val patternName = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        .trim()
        .ifBlank { "Imported antenna pattern" }
    return CanonicalAntennaPattern(
        id = "import-${decoded.sourceSha256.take(16)}",
        name = patternName,
        horizontalCut = horizontal,
        verticalCut = vertical,
        provenance = commonProvenance,
        nominalFrequencyHz = decoded.metadata.nominalFrequencyHz,
    ) to listOf(warning)
}
