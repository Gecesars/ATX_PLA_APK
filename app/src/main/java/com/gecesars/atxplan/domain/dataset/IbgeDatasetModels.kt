package com.gecesars.atxplan.domain.dataset

import java.text.Normalizer
import java.util.Locale

enum class IbgeDatasetFailure {
    EMBEDDED_ASSET_MISSING,
    INVALID_MANIFEST,
    INSUFFICIENT_STORAGE,
    INTEGRITY_CHECK_FAILED,
    INCOMPATIBLE_DATABASE,
    INSTALLATION_FAILED,
    QUERY_FAILED,
}

class IbgeDatasetException(
    val failure: IbgeDatasetFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

enum class IbgeDatasetPreparationPhase {
    CHECKING,
    INSTALLING,
    VALIDATING,
}

data class IbgeDatasetPreparationProgress(
    val phase: IbgeDatasetPreparationPhase,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
) {
    init {
        require(completedBytes >= 0L && totalBytes >= 0L && completedBytes <= totalBytes) {
            "Dataset progress requires a valid completed and total byte count."
        }
    }

    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let { completedBytes.toFloat() / it.toFloat() }
}

data class IbgeDatasetDescriptor(
    val datasetId: String,
    val title: String,
    val provider: String,
    val censusYear: Int,
    val sourceCrs: String,
    val sourceCrsName: String,
    val sourceUrl: String,
    val sourcePageUrl: String,
    val sourceAccessedOn: String,
    val attribution: String,
    val licenseStatus: String,
    val geometryIncluded: Boolean,
    val sectorBoundsDescription: String,
    val populationField: String,
    val sectorCount: Int,
    val municipalityCount: Int,
    val unassignedSectorCount: Int,
    val missingPopulationSectorCount: Int,
    val populationTotal: Long,
    val compressedByteCount: Long,
    val installedByteCount: Long,
    val databaseSha256: String,
) {
    init {
        require(datasetId.isNotBlank() && title.isNotBlank() && provider.isNotBlank()) {
            "An IBGE dataset descriptor requires a stable identity."
        }
        require(censusYear in 1900..2200) { "The census year is invalid." }
        require(sourceCrs.isNotBlank() && sourceCrsName.isNotBlank()) {
            "The source CRS must be explicit."
        }
        require(sourceUrl.startsWith("https://") && sourcePageUrl.startsWith("https://")) {
            "IBGE source references must use HTTPS."
        }
        require(attribution.isNotBlank() && licenseStatus.isNotBlank()) {
            "Attribution and distribution status must be explicit."
        }
        require(sectorCount > 0 && municipalityCount > 0) {
            "The IBGE dataset must contain sectors and municipalities."
        }
        require(unassignedSectorCount in 0..sectorCount) {
            "The unassigned sector count is invalid."
        }
        require(missingPopulationSectorCount in 0..sectorCount) {
            "The missing-population sector count is invalid."
        }
        require(populationTotal >= 0L && compressedByteCount > 0L && installedByteCount > 0L) {
            "Population and storage sizes must be non-negative."
        }
        require(SHA256_PATTERN.matches(databaseSha256)) {
            "The database hash must be a lowercase SHA-256 digest."
        }
    }
}

data class IbgeMunicipalitySummary(
    val code: String,
    val stateCode: String,
    val stateAbbreviation: String,
    val stateName: String,
    val name: String,
    val sectorCount: Int,
    val urbanSectorCount: Int,
    val ruralSectorCount: Int,
    val unspecifiedSectorCount: Int,
    val missingPopulationSectorCount: Int,
    val populationTotal: Long,
    val urbanPopulation: Long,
    val ruralPopulation: Long,
    val unspecifiedPopulation: Long,
    val areaTotalKm2: Double,
    val urbanAreaKm2: Double,
    val ruralAreaKm2: Double,
    val unspecifiedAreaKm2: Double,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(code.length == 7 && code.all(Char::isDigit)) {
            "An IBGE municipality code must contain seven digits."
        }
        require(stateCode.length == 2 && stateCode.all(Char::isDigit)) {
            "An IBGE state code must contain two digits."
        }
        require(stateAbbreviation.length == 2 && stateAbbreviation.all(Char::isLetter)) {
            "A state abbreviation must contain two letters."
        }
        require(name.isNotBlank() && stateName.isNotBlank()) {
            "Municipality and state names must be present."
        }
        require(
            sectorCount > 0 &&
                urbanSectorCount >= 0 && ruralSectorCount >= 0 &&
                unspecifiedSectorCount >= 0 &&
                urbanSectorCount + ruralSectorCount + unspecifiedSectorCount == sectorCount,
        ) {
            "Municipality sector counts are inconsistent."
        }
        require(missingPopulationSectorCount in 0..sectorCount) {
            "The missing-population sector count is invalid."
        }
        require(
            populationTotal >= 0L && urbanPopulation >= 0L && ruralPopulation >= 0L &&
                unspecifiedPopulation >= 0L &&
                urbanPopulation + ruralPopulation + unspecifiedPopulation == populationTotal,
        ) {
            "Municipality population totals are inconsistent."
        }
        require(
            listOf(areaTotalKm2, urbanAreaKm2, ruralAreaKm2, unspecifiedAreaKm2).all {
                it.isFinite() && it >= 0.0
            },
        ) {
            "Municipality areas must be finite and non-negative."
        }
        require(
            listOf(west, south, east, north).all(Double::isFinite) &&
                west in -180.0..180.0 && east in -180.0..180.0 && west <= east &&
                south in -90.0..90.0 && north in -90.0..90.0 && south <= north,
        ) {
            "Municipality bounds are invalid."
        }
    }

    val urbanPopulationFraction: Double?
        get() = populationTotal.takeIf { it > 0L }?.let { urbanPopulation.toDouble() / it.toDouble() }
}

data class IbgeCensusSectorAttribute(
    val sectorCode: String,
    val municipalityCode: String,
    val situationCode: Int,
    val areaKm2: Double,
    val residentPopulation: Long,
) {
    init {
        require(sectorCode.length == 15 && sectorCode.all(Char::isDigit)) {
            "An IBGE census-sector code must contain 15 digits."
        }
        require(municipalityCode.length == 7 && municipalityCode.all(Char::isDigit)) {
            "An IBGE municipality code must contain seven digits."
        }
        require(situationCode in 0..2) { "An IBGE sector situation code is invalid." }
        require(areaKm2.isFinite() && areaKm2 >= 0.0 && residentPopulation >= 0L) {
            "IBGE sector area and population must be non-negative."
        }
    }
}

interface IbgeDatasetRepository {
    suspend fun prepare(
        onProgress: (IbgeDatasetPreparationProgress) -> Unit = {},
    ): IbgeDatasetDescriptor

    suspend fun searchMunicipalities(
        query: String,
        limit: Int = DEFAULT_MUNICIPALITY_RESULT_LIMIT,
    ): List<IbgeMunicipalitySummary>
}

fun normalizeIbgeMunicipalitySearch(raw: String): String {
    require(raw.length <= MAX_MUNICIPALITY_QUERY_LENGTH) {
        "The municipality query exceeds $MAX_MUNICIPALITY_QUERY_LENGTH characters."
    }
    val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
    return decomposed
        .filterNot { character -> Character.getType(character) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .joinToString(" ")
}

const val DEFAULT_MUNICIPALITY_RESULT_LIMIT = 12
const val MAX_MUNICIPALITY_RESULT_LIMIT = 25
const val MAX_MUNICIPALITY_QUERY_LENGTH = 80

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
