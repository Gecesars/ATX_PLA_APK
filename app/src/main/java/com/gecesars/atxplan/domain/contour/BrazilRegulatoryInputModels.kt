package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.model.GeoPoint

data class RegulatoryMunicipalityContext(
    val ibgeCode: String,
    val name: String,
    val stateAbbreviation: String,
) {
    init {
        require(ibgeCode.matches(Regex("^[0-9]{7}$"))) {
            "A regulatory municipality requires a seven-digit IBGE code."
        }
        require(name.isNotBlank() && name.length <= 120) {
            "A regulatory municipality requires a bounded name."
        }
        require(stateAbbreviation.matches(Regex("^[A-Z]{2}$"))) {
            "A regulatory municipality requires a two-letter state abbreviation."
        }
    }
}

data class RegulatoryCensusRing(
    val points: List<GeoPoint>,
) {
    init {
        require(points.size in 4..MAXIMUM_CENSUS_RING_POINTS) {
            "A census ring must contain 4 to $MAXIMUM_CENSUS_RING_POINTS points."
        }
        require(points.first() == points.last()) { "A census ring must be closed." }
    }
}

data class RegulatoryCensusPolygon(
    val rings: List<RegulatoryCensusRing>,
) {
    init {
        require(rings.size in 1..MAXIMUM_CENSUS_POLYGON_RINGS) {
            "A census polygon has an invalid ring count."
        }
    }
}

data class RegulatoryCensusSector(
    val sectorCode: String,
    val areaKm2: Double,
    val residentPopulation: Long,
    val polygons: List<RegulatoryCensusPolygon>,
) {
    init {
        require(sectorCode.matches(Regex("^[0-9]{15}$"))) {
            "A census sector requires a 15-digit IBGE code."
        }
        require(areaKm2.isFinite() && areaKm2 >= 0.0) {
            "A census sector area must be finite and non-negative."
        }
        require(residentPopulation >= 0L) { "A census sector population cannot be negative." }
        require(polygons.size in 1..MAXIMUM_CENSUS_SECTOR_POLYGONS) {
            "A census sector has an invalid polygon count."
        }
    }
}

data class RegulatoryCensusGeometrySnapshot(
    val municipality: RegulatoryMunicipalityContext,
    val sectors: List<RegulatoryCensusSector>,
    val transmitterInsideMunicipality: Boolean,
    val sourceUrl: String,
    val sourcePageUrl: String,
    val sourceSha256: String,
    val sourceByteCount: Long,
    val sourceEtag: String,
    val sourceLastModified: String?,
    val sourceCrs: String = "EPSG:4674",
    val sourceRelease: String = "2022 definitive mesh published 2024-11-12",
) {
    init {
        require(sectors.isNotEmpty() && sectors.size <= MAXIMUM_MUNICIPALITY_CENSUS_SECTORS) {
            "A regulatory census snapshot requires a bounded non-empty sector collection."
        }
        require(sectors.map(RegulatoryCensusSector::sectorCode).distinct().size == sectors.size) {
            "A regulatory census snapshot cannot repeat sector codes."
        }
        require(sourceUrl.startsWith("https://") && sourcePageUrl.startsWith("https://")) {
            "Regulatory census sources must use HTTPS."
        }
        require(sourceSha256.matches(Regex("^[0-9a-f]{64}$")) && sourceByteCount > 0L) {
            "Regulatory census provenance requires SHA-256 and byte count."
        }
        require(sourceEtag.isNotBlank() && !sourceEtag.startsWith("W/")) {
            "Regulatory census provenance requires a strong ETag."
        }
        require(sourceCrs == "EPSG:4674") {
            "The current regulatory census reader supports only SIRGAS 2000 geographic data."
        }
    }
}

enum class BroadcastTechnology {
    ANALOG,
    DIGITAL,
}

enum class LicensedBroadcastRole {
    GENERATOR,
    RETRANSMITTER,
    FM_STATION,
    OTHER,
}

enum class LicensedBroadcastLocationBasis {
    LICENSED_COORDINATES,
    BASIC_PLAN_DISCOVERY_ONLY,
}

data class LicensedBroadcastStation(
    val sourceId: String,
    val basicPlanId: String?,
    val serviceCode: Int,
    val rawService: String,
    val technology: BroadcastTechnology,
    val role: LicensedBroadcastRole,
    val channel: Int,
    val frequencyMHz: Double,
    val location: GeoPoint,
    val municipalityCode: String?,
    val municipalityName: String?,
    val stateAbbreviation: String,
    val licensee: String?,
    val licenseId: String?,
    val licensedOn: String?,
    val stationClassRaw: String,
    val erpKw: Double?,
    val antennaHeightAglM: Double?,
    /** 72 field-amplitude samples at true-north azimuths 0, 5, ... 355 degrees. */
    val horizontalPattern: List<Double>?,
    val rawStatus: String,
    val locationBasis: LicensedBroadcastLocationBasis = LicensedBroadcastLocationBasis.LICENSED_COORDINATES,
) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 160) {
            "A licensed station requires a bounded source identity."
        }
        require(serviceCode >= 0 && rawService.isNotBlank()) {
            "A licensed station requires its source service identity."
        }
        require(channel in 1..999 && frequencyMHz.isFinite() && frequencyMHz > 0.0) {
            "A licensed station requires a valid channel and frequency."
        }
        require(stateAbbreviation.matches(Regex("^[A-Z]{2}$"))) {
            "A licensed station requires a two-letter state abbreviation."
        }
        require(municipalityCode == null || municipalityCode.matches(Regex("^[0-9]{7}$"))) {
            "A licensed station municipality code is invalid."
        }
        require(erpKw == null || erpKw.isFinite() && erpKw > 0.0) {
            "A licensed station ERP must be positive when present."
        }
        require(antennaHeightAglM == null || antennaHeightAglM.isFinite() && antennaHeightAglM > 0.0) {
            "A licensed station antenna height must be positive when present."
        }
        require(horizontalPattern == null || horizontalPattern.size == 72 && horizontalPattern.all {
            it.isFinite() && it in 0.0..1.0
        }) { "A licensed station horizontal pattern must contain 72 normalized field samples." }
    }
}

data class LicensedBroadcastBaselineSnapshot(
    val stations: List<LicensedBroadcastStation>,
    val sourceUrl: String,
    val sourcePageUrl: String,
    val sourceSha256: String,
    val sourceByteCount: Long,
    val sourceEtag: String,
    val sourceLastModified: String?,
    val generatedOn: String,
    val referenceDate: String,
    val sourceRowCount: Long,
    val rejectedRowCount: Long,
    val unlocatedSameChannelStationCount: Int = 0,
) {
    init {
        require(stations.size <= MAXIMUM_LICENSED_BASELINE_STATIONS) {
            "The licensed baseline station collection exceeds its safety bound."
        }
        require(stations.map(LicensedBroadcastStation::sourceId).distinct().size == stations.size) {
            "The licensed baseline cannot repeat source station identities."
        }
        require(sourceUrl.startsWith("https://") && sourcePageUrl.startsWith("https://")) {
            "Licensed baseline sources must use HTTPS."
        }
        require(sourceSha256.matches(Regex("^[0-9a-f]{64}$")) && sourceByteCount > 0L) {
            "Licensed baseline provenance requires SHA-256 and byte count."
        }
        require(sourceEtag.isNotBlank() && !sourceEtag.startsWith("W/")) {
            "Licensed baseline provenance requires a strong ETag."
        }
        require(generatedOn.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))) {
            "The licensed baseline generation date is invalid."
        }
        require(referenceDate.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))) {
            "The licensed baseline reference date is invalid."
        }
        require(sourceRowCount >= stations.size && rejectedRowCount >= 0L) {
            "Licensed baseline row counts are inconsistent."
        }
        require(unlocatedSameChannelStationCount >= 0)
    }
}

data class RegulatoryCoverageGateEvidence(
    val municipality: RegulatoryMunicipalityContext,
    val requirementPercent: Int,
    val rasterSpacingM: Double,
    val eligibleUrbanAreaKm2: Double,
    val coveredUrbanAreaKm2: Double,
    val areaCoveragePercent: Double?,
    val areaCoverageLowerPercent: Double?,
    val areaCoverageUpperPercent: Double?,
    val eligibleUrbanPopulation: Long,
    val coveredUrbanPopulationEstimate: Double,
    val populationCoveragePercent: Double?,
    val sectorCount: Int,
    val noDataCellCount: Long,
    val status: RegulatoryGateStatus,
    val method: String,
) {
    init {
        require(requirementPercent in 1..100 && rasterSpacingM.isFinite() && rasterSpacingM > 0.0)
        require(eligibleUrbanAreaKm2.isFinite() && eligibleUrbanAreaKm2 >= 0.0)
        require(coveredUrbanAreaKm2.isFinite() && coveredUrbanAreaKm2 >= 0.0)
        require(areaCoveragePercent == null || areaCoveragePercent.isFinite() && areaCoveragePercent in 0.0..100.0)
        require(areaCoverageLowerPercent == null || areaCoverageLowerPercent.isFinite() && areaCoverageLowerPercent in 0.0..100.0)
        require(areaCoverageUpperPercent == null || areaCoverageUpperPercent.isFinite() && areaCoverageUpperPercent in 0.0..100.0)
        require(
            areaCoverageLowerPercent == null || areaCoverageUpperPercent == null ||
                areaCoverageLowerPercent <= areaCoverageUpperPercent
        )
        require(eligibleUrbanPopulation >= 0L && coveredUrbanPopulationEstimate.isFinite() && coveredUrbanPopulationEstimate >= 0.0)
        require(populationCoveragePercent == null || populationCoveragePercent.isFinite() && populationCoveragePercent in 0.0..100.0)
        require(sectorCount >= 0 && noDataCellCount >= 0L && method.isNotBlank())
    }
}

enum class RegulatoryGateStatus {
    PASS,
    FAIL,
    NO_DATA,
}

data class RegulatoryScenarioComparison(
    val wantedStationId: String,
    val wantedStationLabel: String,
    val baselineWorstMarginDb: Double?,
    val proposedWorstMarginDb: Double?,
    val proposedProjectMarginDb: Double?,
    val status: RegulatoryScenarioStatus,
    val baselineInterfererCount: Int,
    val noDataAssessmentCount: Int,
) {
    init {
        require(wantedStationId.isNotBlank() && wantedStationLabel.isNotBlank())
        require(baselineWorstMarginDb == null || baselineWorstMarginDb.isFinite())
        require(proposedWorstMarginDb == null || proposedWorstMarginDb.isFinite())
        require(proposedProjectMarginDb == null || proposedProjectMarginDb.isFinite())
        require(baselineInterfererCount >= 0 && noDataAssessmentCount >= 0)
    }
}

enum class RegulatoryScenarioStatus {
    UNCHANGED_COMPLIANT,
    UNCHANGED_EXISTING_CONFLICT,
    IMPROVED,
    AGGRAVATED,
    NEW_CONFLICT,
    NO_DATA,
}

data class BrazilBroadcastRegulatoryContext(
    val municipality: RegulatoryMunicipalityContext,
    val censusGeometry: RegulatoryCensusGeometrySnapshot,
    val licensedBaseline: LicensedBroadcastBaselineSnapshot,
)

private const val MAXIMUM_CENSUS_RING_POINTS = 2_000_000
private const val MAXIMUM_CENSUS_POLYGON_RINGS = 10_000
private const val MAXIMUM_CENSUS_SECTOR_POLYGONS = 10_000
private const val MAXIMUM_MUNICIPALITY_CENSUS_SECTORS = 100_000
private const val MAXIMUM_LICENSED_BASELINE_STATIONS = 100_000
