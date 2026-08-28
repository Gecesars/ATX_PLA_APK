package com.gecesars.atxplan.ui.antenna

enum class AntennaPatternExportFormat(
    val label: String,
    val extension: String,
    val mediaType: String,
) {
    ATX_JSON(
        "ATX Antenna JSON v2",
        "atx-antenna.json",
        "application/vnd.atx-plan.antenna+json;version=2",
    ),
    ATX_DESKTOP_JSON(
        "ATX Planner desktop JSON v1",
        "atxpat.json",
        "application/json",
    ),
    PRN("PRN", "prn", "text/plain"),
    PAT("PAT", "pat", "text/plain"),
    HRP("ADT HRP", "hrp", "text/plain"),
    VRP("ADT VRP", "vrp", "text/plain"),
    VSOFT_HRP("V-Soft HRP", "vep", "text/plain"),
    VSOFT_VRP("V-Soft VRP", "vep", "text/plain"),
}

enum class AntennaArrayTaper {
    UNIFORM,
    BINOMIAL,
}

enum class AntennaPrnValueInterpretation {
    DESKTOP_POSITIVE_ATTENUATION_DB,
    NORMALIZED_LINEAR_FIELD,
}

data class AntennaArraySynthesisRequest(
    val name: String,
    val basePatternId: String?,
    val frequencyMHz: Double,
    val columns: Int,
    val rows: Int,
    val horizontalSpacingWavelengths: Double,
    val verticalSpacingWavelengths: Double,
    val horizontalScanDegrees: Double,
    val verticalScanDegrees: Double,
    val taper: AntennaArrayTaper,
)

data class AntennaPatternImportPreview(
    val token: String,
    val displayName: String,
    val detectedFormat: String,
    val sourceSha256: String,
    val sourceByteCount: Long,
    val horizontalSampleCount: Int,
    val verticalSampleCount: Int,
    val nominalFrequencyHz: Double?,
    val peakGainDbi: Double?,
    val isCalculationReady: Boolean,
    val warnings: List<String>,
    val componentDisplayNames: List<String> = emptyList(),
)

data class AntennaPatternExportPreview(
    val token: String,
    val patternId: String,
    val format: AntennaPatternExportFormat,
    val suggestedFileName: String,
    val mediaType: String,
    val byteCount: Int,
    val sha256: String,
    val warnings: List<String>,
)

data class AntennaPrnConventionChoicePreview(
    val token: String,
    val sourceDisplayNames: List<String>,
    val ambiguousPlaneLabels: List<String>,
)

data class AntennaPatternLabUiState(
    val isBusy: Boolean = false,
    val operationLabel: String? = null,
    val pendingImport: AntennaPatternImportPreview? = null,
    val pendingExport: AntennaPatternExportPreview? = null,
    val pendingPrnConventionChoice: AntennaPrnConventionChoicePreview? = null,
    val notice: String? = null,
    val error: String? = null,
    val catalogMutationCount: Long = 0L,
)
