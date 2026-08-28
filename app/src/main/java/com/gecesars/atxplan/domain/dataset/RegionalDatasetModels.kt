package com.gecesars.atxplan.domain.dataset

import java.net.URI
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlinx.serialization.Serializable

/**
 * A WGS84 longitude/latitude envelope with half-open east and north edges.
 *
 * The half-open convention prevents an envelope ending exactly on a tile edge from
 * requesting the adjacent tile. Antimeridian-crossing envelopes are intentionally
 * rejected; callers must split them into two explicit regional requests.
 */
@Serializable
data class RegionalBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(listOf(west, south, east, north).all(Double::isFinite)) {
            "Regional bounds must contain finite WGS84 coordinates."
        }
        require(west >= -180.0 && east <= 180.0 && west < east) {
            "Regional bounds must have west < east within -180..180 and cannot cross the antimeridian."
        }
        require(south >= -90.0 && north <= 90.0 && south < north) {
            "Regional bounds must have south < north within -90..90."
        }
    }

    val widthDegrees: Double
        get() = east - west

    val heightDegrees: Double
        get() = north - south

    /** Spherical WGS84 approximation used only for request safety limits. */
    val approximateAreaKm2: Double
        get() {
            val westRadians = west * PI / 180.0
            val eastRadians = east * PI / 180.0
            val southRadians = south * PI / 180.0
            val northRadians = north * PI / 180.0
            return EARTH_MEAN_RADIUS_KM * EARTH_MEAN_RADIUS_KM *
                abs(sin(northRadians) - sin(southRadians)) *
                abs(eastRadians - westRadians)
        }

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude >= south && latitude < north && longitude >= west && longitude < east

    /** One deterministic normalization used by queries, paths, tiles, and durable fingerprints. */
    fun normalizedToMicrodegrees(): RegionalBounds = RegionalBounds(
        west = west.normalizedCoordinateToMicrodegrees(),
        south = south.normalizedCoordinateToMicrodegrees(),
        east = east.normalizedCoordinateToMicrodegrees(),
        north = north.normalizedCoordinateToMicrodegrees(),
    )
}

@Serializable
enum class RegionalDatasetSelection {
    /** Copernicus GLO-30 is a digital surface model, not a bare-earth DTM. */
    COPERNICUS_GLO_30_DSM,
    ESA_WORLDCOVER_2021,
    OSM_BUILDINGS_EXPERIMENTAL,
}

@Serializable
enum class RegionalDataType {
    DIGITAL_SURFACE_MODEL,
    LAND_COVER,
    BUILDING_FOOTPRINTS,
}

@Serializable
enum class RegionalFileFormat {
    COG_GEOTIFF,
    OVERPASS_JSON,
}

@Serializable
enum class RegionalHttpMethod {
    GET,
    POST,
}

@Serializable
enum class RegionalSnapshotPolicy {
    IMMUTABLE_RELEASE,
    LIVE_SNAPSHOT_BOUNDED_CACHE,
}

@Serializable
enum class RegionalArtifactCachePolicy {
    IMMUTABLE_RELEASE,
    LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE,
    LIVE_SNAPSHOT_FORCE_REFRESH,
}

@Serializable
data class RegionalDatasetLicense(
    val id: String,
    val title: String,
    val url: String,
    val attribution: String,
    val acceptanceRequired: Boolean = true,
) {
    init {
        require(STABLE_ID_PATTERN.matches(id)) { "A dataset license requires a stable identifier." }
        require(isBoundedText(title, MAX_SOURCE_TITLE_LENGTH) && isBoundedText(attribution, MAX_ATTRIBUTION_LENGTH)) {
            "A dataset license requires a title and attribution."
        }
        require(isStructurallySafeHttpsUrl(url)) { "A dataset license reference must use a bounded HTTPS URL." }
    }
}

@Serializable
data class RegionalDatasetSource(
    val selection: RegionalDatasetSelection,
    val datasetId: String,
    val datasetFamily: String,
    val datasetRelease: String,
    val catalogRevision: Int,
    val title: String,
    val provider: String,
    val dataType: RegionalDataType,
    val fileFormat: RegionalFileFormat,
    val version: String,
    val sourceCrs: String,
    val nominalResolutionMeters: Double?,
    val sourceUrl: String,
    val license: RegionalDatasetLicense,
    val provenance: String,
    val limitations: String,
    val queryVersion: String,
    val normalizerVersion: String,
    val routeId: String,
    val routePolicyVersion: Int,
    val snapshotPolicy: RegionalSnapshotPolicy,
    val maximumCacheAgeMillis: Long? = null,
    val estimatedBytesPerArtifact: Long,
    val maximumArtifactBytes: Long,
    val optionalWhenNotPublished: Boolean = false,
) {
    init {
        require(STABLE_ID_PATTERN.matches(datasetId)) { "A regional dataset requires a stable identifier." }
        require(STABLE_ID_PATTERN.matches(datasetFamily) && STABLE_ID_PATTERN.matches(datasetRelease)) {
            "A regional dataset requires stable family and release identifiers."
        }
        require(catalogRevision in 1..MAX_CATALOG_REVISION) {
            "A regional dataset catalog revision is outside the supported range."
        }
        require(
            isBoundedText(title, MAX_SOURCE_TITLE_LENGTH) &&
                isBoundedText(provider, MAX_PROVIDER_LENGTH) &&
                isBoundedText(version, MAX_SOURCE_VERSION_LENGTH)
        ) {
            "A regional dataset requires a title, provider, and version."
        }
        require(isBoundedText(sourceCrs, MAX_CRS_LENGTH)) { "A regional dataset source CRS must be explicit." }
        require(nominalResolutionMeters == null || nominalResolutionMeters.isFinite() && nominalResolutionMeters > 0.0) {
            "Nominal source resolution must be positive when known."
        }
        require(isStructurallySafeHttpsUrl(sourceUrl)) {
            "A regional dataset source reference must use a bounded HTTPS URL."
        }
        require(
            isBoundedText(provenance, MAX_PROVENANCE_LENGTH) &&
                isBoundedText(limitations, MAX_LIMITATIONS_LENGTH)
        ) {
            "Dataset provenance and limitations must be explicit."
        }
        require(
            STABLE_ID_PATTERN.matches(queryVersion) &&
                STABLE_ID_PATTERN.matches(normalizerVersion) &&
                STABLE_ID_PATTERN.matches(routeId)
        ) {
            "Dataset query, normalizer, and route identifiers must be stable."
        }
        require(routePolicyVersion in 1..MAX_ROUTE_POLICY_VERSION) {
            "A regional route policy version is outside the supported range."
        }
        when (snapshotPolicy) {
            RegionalSnapshotPolicy.IMMUTABLE_RELEASE -> require(maximumCacheAgeMillis == null) {
                "An immutable dataset release cannot declare a live cache age."
            }
            RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE -> require(
                maximumCacheAgeMillis != null &&
                    maximumCacheAgeMillis in 1L..MAXIMUM_LIVE_SNAPSHOT_CACHE_AGE_MILLIS
            ) {
                "A live dataset requires a bounded positive cache age."
            }
        }
        require(estimatedBytesPerArtifact > 0L && maximumArtifactBytes >= estimatedBytesPerArtifact) {
            "Dataset byte estimates and hard limits are inconsistent."
        }
    }

    fun toSourceSnapshot(): RegionalSourceSnapshot = RegionalSourceSnapshot(
        datasetId = datasetId,
        datasetFamily = datasetFamily,
        datasetRelease = datasetRelease,
        catalogRevision = catalogRevision,
        title = title,
        provider = provider,
        dataType = dataType,
        fileFormat = fileFormat,
        version = version,
        sourceCrs = sourceCrs,
        nominalResolutionMeters = nominalResolutionMeters,
        sourceUrl = sourceUrl,
        license = license,
        provenance = provenance,
        limitations = limitations,
        queryVersion = queryVersion,
        normalizerVersion = normalizerVersion,
        routeId = routeId,
        routePolicyVersion = routePolicyVersion,
        snapshotPolicy = snapshotPolicy,
        maximumCacheAgeMillis = maximumCacheAgeMillis,
    )
}

/** Immutable acquisition-time source metadata for passive inventory and portable manifests. */
@Serializable
data class RegionalSourceSnapshot(
    val datasetId: String,
    val datasetFamily: String,
    val datasetRelease: String,
    val catalogRevision: Int,
    val title: String,
    val provider: String,
    val dataType: RegionalDataType,
    val fileFormat: RegionalFileFormat,
    val version: String,
    val sourceCrs: String,
    val nominalResolutionMeters: Double?,
    val sourceUrl: String,
    val license: RegionalDatasetLicense,
    val provenance: String,
    val limitations: String,
    val queryVersion: String,
    val normalizerVersion: String,
    val routeId: String,
    val routePolicyVersion: Int,
    val snapshotPolicy: RegionalSnapshotPolicy,
    val maximumCacheAgeMillis: Long? = null,
) {
    init {
        require(STABLE_ID_PATTERN.matches(datasetId)) { "A source snapshot requires a stable dataset identifier." }
        require(STABLE_ID_PATTERN.matches(datasetFamily) && STABLE_ID_PATTERN.matches(datasetRelease)) {
            "A source snapshot requires stable family and release identifiers."
        }
        require(catalogRevision in 1..MAX_CATALOG_REVISION) {
            "A source snapshot catalog revision is outside the supported range."
        }
        require(
            isBoundedText(title, MAX_SOURCE_TITLE_LENGTH) &&
                isBoundedText(provider, MAX_PROVIDER_LENGTH) &&
                isBoundedText(version, MAX_SOURCE_VERSION_LENGTH) &&
                isBoundedText(sourceCrs, MAX_CRS_LENGTH)
        ) { "A source snapshot contains invalid descriptive metadata." }
        require(nominalResolutionMeters == null || nominalResolutionMeters.isFinite() && nominalResolutionMeters > 0.0) {
            "A source snapshot resolution must be positive when known."
        }
        require(isStructurallySafeHttpsUrl(sourceUrl)) {
            "A source snapshot reference must use a bounded HTTPS URL."
        }
        require(
            isBoundedText(provenance, MAX_PROVENANCE_LENGTH) &&
                isBoundedText(limitations, MAX_LIMITATIONS_LENGTH)
        ) { "A source snapshot requires bounded provenance and limitations." }
        require(
            STABLE_ID_PATTERN.matches(queryVersion) &&
                STABLE_ID_PATTERN.matches(normalizerVersion) &&
                STABLE_ID_PATTERN.matches(routeId)
        ) { "A source snapshot contains invalid contract identifiers." }
        require(routePolicyVersion in 1..MAX_ROUTE_POLICY_VERSION) {
            "A source snapshot route policy version is outside the supported range."
        }
        when (snapshotPolicy) {
            RegionalSnapshotPolicy.IMMUTABLE_RELEASE -> require(maximumCacheAgeMillis == null) {
                "An immutable source snapshot cannot declare a live cache age."
            }
            RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE -> require(
                maximumCacheAgeMillis != null &&
                    maximumCacheAgeMillis in 1L..MAXIMUM_LIVE_SNAPSHOT_CACHE_AGE_MILLIS
            ) { "A live source snapshot requires a bounded positive cache age." }
        }
    }
}

/** Fixed source catalog. Acquisition URLs are derived by [RegionalDatasetPlanner]. */
object RegionalDatasetCatalog {
    val copernicusGlo30Dsm = RegionalDatasetSource(
        selection = RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
        datasetId = "copernicus-dem-glo30-2021",
        datasetFamily = "copernicus-dem-glo30",
        datasetRelease = "2021",
        catalogRevision = REGIONAL_DATASET_CATALOG_REVISION,
        title = "Copernicus DEM GLO-30 Public 2021 DSM",
        provider = "Copernicus Programme",
        dataType = RegionalDataType.DIGITAL_SURFACE_MODEL,
        fileFormat = RegionalFileFormat.COG_GEOTIFF,
        version = "2021",
        sourceCrs = "EPSG:4326",
        nominalResolutionMeters = 30.0,
        sourceUrl = "https://registry.opendata.aws/copernicus-dem/",
        license = RegionalDatasetLicense(
            id = "copernicus-dem-license",
            title = "Copernicus DEM License",
            url = "https://documentation.dataspace.copernicus.eu/APIs/SentinelHub/Data/DEM/resources/license/License-COPDEM-30.pdf",
            attribution = "© DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH " +
                "2014-2018 provided under COPERNICUS by the European Union and ESA; all rights " +
                "reserved. The organisations in charge of the Copernicus programme by law or " +
                "by delegation do not incur any liability for any use of the Copernicus " +
                "WorldDEM-30.",
        ),
        provenance = "Public Copernicus GLO-30 2021 one-degree Cloud Optimized GeoTIFF tiles.",
        limitations = "This is a digital surface model that can include buildings and vegetation; it is not a bare-earth DTM. Raw tiles must be validated and processed before engineering use.",
        queryVersion = "copernicus-glo30-tile-v1",
        normalizerVersion = "atx-tiff-metadata-v1",
        routeId = "copernicus-dem-aws-eu-central-1",
        routePolicyVersion = 1,
        snapshotPolicy = RegionalSnapshotPolicy.IMMUTABLE_RELEASE,
        estimatedBytesPerArtifact = 65_000_000L,
        maximumArtifactBytes = 96L * MEBIBYTE,
        optionalWhenNotPublished = true,
    )

    val esaWorldCover2021 = RegionalDatasetSource(
        selection = RegionalDatasetSelection.ESA_WORLDCOVER_2021,
        datasetId = "esa-worldcover-2021-v200",
        datasetFamily = "esa-worldcover",
        datasetRelease = "2021-v200",
        catalogRevision = REGIONAL_DATASET_CATALOG_REVISION,
        title = "ESA WorldCover 10 m 2021 v200",
        provider = "European Space Agency",
        dataType = RegionalDataType.LAND_COVER,
        fileFormat = RegionalFileFormat.COG_GEOTIFF,
        version = "2021 v200",
        sourceCrs = "EPSG:4326",
        nominalResolutionMeters = 10.0,
        sourceUrl = "https://esa-worldcover.org/en/data-access",
        license = RegionalDatasetLicense(
            id = "cc-by-4.0-esa-worldcover",
            title = "Creative Commons Attribution 4.0 International",
            url = "https://creativecommons.org/licenses/by/4.0/",
            attribution = "© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by the ESA WorldCover consortium",
        ),
        provenance = "ESA WorldCover 2021 v200 categorical three-degree Cloud Optimized GeoTIFF tiles.",
        limitations = "Land-cover classes are categorical source observations, not RF clutter loss coefficients. Raw tiles must be validated and processed before engineering use.",
        queryVersion = "esa-worldcover-v200-tile-v1",
        normalizerVersion = "atx-tiff-metadata-v1",
        routeId = "esa-worldcover-aws-eu-central-1",
        routePolicyVersion = 1,
        snapshotPolicy = RegionalSnapshotPolicy.IMMUTABLE_RELEASE,
        estimatedBytesPerArtifact = 150_000_000L,
        maximumArtifactBytes = 256L * MEBIBYTE,
        optionalWhenNotPublished = true,
    )

    val osmBuildingsExperimental = RegionalDatasetSource(
        selection = RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL,
        datasetId = "openstreetmap-buildings-overpass-experimental",
        datasetFamily = "openstreetmap-buildings",
        datasetRelease = "live",
        catalogRevision = REGIONAL_DATASET_CATALOG_REVISION,
        title = "OpenStreetMap Building and Building-Part Ways (Experimental)",
        provider = "OpenStreetMap contributors via Overpass API",
        dataType = RegionalDataType.BUILDING_FOOTPRINTS,
        fileFormat = RegionalFileFormat.OVERPASS_JSON,
        version = "live snapshot",
        sourceCrs = "EPSG:4326",
        nominalResolutionMeters = null,
        sourceUrl = "https://www.openstreetmap.org/copyright",
        license = RegionalDatasetLicense(
            id = "odbl-1.0-openstreetmap",
            title = "Open Data Commons Open Database License 1.0",
            url = "https://opendatacommons.org/licenses/odbl/1-0/",
            attribution = "© OpenStreetMap contributors",
        ),
        provenance = "A user-triggered live Overpass API snapshot of ways carrying a building or building:part tag within the selected bounds.",
        limitations = "OpenStreetMap is community-contributed and can be incomplete or inaccurate. This experimental request includes building and building-part ways only, not multipolygon relations, and is not authoritative building data.",
        queryVersion = "osm-building-and-part-ways-bbox-v1",
        normalizerVersion = "atx-osm-building-geojson-v1",
        routeId = "osm-overpass-lambert",
        routePolicyVersion = 1,
        snapshotPolicy = RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE,
        maximumCacheAgeMillis = OSM_BUILDINGS_CACHE_MAX_AGE_MILLIS,
        estimatedBytesPerArtifact = 4L * MEBIBYTE,
        maximumArtifactBytes = OVERPASS_MAX_RESPONSE_BYTES,
    )

    val sources: List<RegionalDatasetSource> = listOf(
        copernicusGlo30Dsm,
        esaWorldCover2021,
        osmBuildingsExperimental,
    )

    fun sourceFor(selection: RegionalDatasetSelection): RegionalDatasetSource = when (selection) {
        RegionalDatasetSelection.COPERNICUS_GLO_30_DSM -> copernicusGlo30Dsm
        RegionalDatasetSelection.ESA_WORLDCOVER_2021 -> esaWorldCover2021
        RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL -> osmBuildingsExperimental
    }
}

@Serializable
data class RegionalDatasetRequest(
    val bounds: RegionalBounds,
    val selections: Set<RegionalDatasetSelection>,
    val reason: String = "regional data preparation",
    val liveSnapshotRefresh: Boolean = false,
) {
    init {
        require(selections.isNotEmpty()) { "Select at least one regional dataset." }
        require(reason.isNotBlank() && reason.length <= MAX_REASON_LENGTH && reason.none(Char::isISOControl)) {
            "The regional data reason must contain 1 to $MAX_REASON_LENGTH printable characters."
        }
        require(bounds.widthDegrees <= MAX_REGIONAL_WIDTH_DEGREES + COORDINATE_EPSILON) {
            "Regional bounds cannot exceed $MAX_REGIONAL_WIDTH_DEGREES degrees of longitude."
        }
        require(bounds.heightDegrees <= MAX_REGIONAL_HEIGHT_DEGREES + COORDINATE_EPSILON) {
            "Regional bounds cannot exceed $MAX_REGIONAL_HEIGHT_DEGREES degrees of latitude."
        }
        if (RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL in selections) {
            require(
                bounds.widthDegrees <= MAX_BUILDING_SPAN_DEGREES + COORDINATE_EPSILON &&
                    bounds.heightDegrees <= MAX_BUILDING_SPAN_DEGREES + COORDINATE_EPSILON,
            ) {
                "Experimental building requests cannot exceed $MAX_BUILDING_SPAN_DEGREES degrees per axis."
            }
            require(bounds.approximateAreaKm2 <= MAX_BUILDING_AREA_KM2 + COORDINATE_EPSILON) {
                "Experimental building requests cannot exceed $MAX_BUILDING_AREA_KM2 km²."
            }
        }
        require(!liveSnapshotRefresh || RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL in selections) {
            "A forced live-snapshot refresh requires the experimental building selection."
        }
    }
}

@Serializable
data class RegionalArtifact(
    val source: RegionalDatasetSource,
    val requestBounds: RegionalBounds,
    val coverageBounds: RegionalBounds,
    val south: Int? = null,
    val west: Int? = null,
    val url: String,
    val relativePath: String,
    val estimatedBytes: Long,
    val httpMethod: RegionalHttpMethod = RegionalHttpMethod.GET,
    val requestBody: String? = null,
    val contentType: String? = null,
    val cachePolicy: RegionalArtifactCachePolicy = if (
        source.snapshotPolicy == RegionalSnapshotPolicy.IMMUTABLE_RELEASE
    ) {
        RegionalArtifactCachePolicy.IMMUTABLE_RELEASE
    } else {
        RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE
    },
) {
    init {
        require(source == RegionalDatasetCatalog.sourceFor(source.selection)) {
            "Regional artifacts must use an unchanged source from the fixed catalog."
        }
        require(isSafeRelativePath(relativePath)) { "A regional artifact path is unsafe." }
        require(estimatedBytes > 0L && estimatedBytes <= source.maximumArtifactBytes) {
            "A regional artifact estimate exceeds its source limit."
        }
        when (source.snapshotPolicy) {
            RegionalSnapshotPolicy.IMMUTABLE_RELEASE -> require(
                cachePolicy == RegionalArtifactCachePolicy.IMMUTABLE_RELEASE,
            ) { "A static dataset release must use immutable cache semantics." }
            RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE -> require(
                cachePolicy == RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE ||
                    cachePolicy == RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH,
            ) { "A live dataset must use an explicit bounded-cache or forced-refresh policy." }
        }
        validateProviderRequest(this)
    }
}

@Serializable
data class RegionalDownloadPlan(
    val request: RegionalDatasetRequest,
    val artifacts: List<RegionalArtifact>,
    val estimatedBytes: Long,
    val maximumBatchBytes: Long,
    val licenses: List<RegionalDatasetLicense>,
) {
    init {
        require(artifacts.isNotEmpty() && artifacts.size <= MAX_ARTIFACTS_PER_PLAN) {
            "A regional plan must contain 1 to $MAX_ARTIFACTS_PER_PLAN artifacts."
        }
        require(artifacts.all { it.requestBounds == request.bounds && it.source.selection in request.selections }) {
            "Every regional artifact must belong to the request bounds and selections."
        }
        require(artifacts.all { artifact ->
            when (artifact.source.snapshotPolicy) {
                RegionalSnapshotPolicy.IMMUTABLE_RELEASE ->
                    artifact.cachePolicy == RegionalArtifactCachePolicy.IMMUTABLE_RELEASE
                RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE -> artifact.cachePolicy == if (
                    request.liveSnapshotRefresh
                ) {
                    RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH
                } else {
                    RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE
                }
            }
        }) { "Every regional artifact must match the request cache policy." }
        require(artifacts.map(RegionalArtifact::relativePath).distinct().size == artifacts.size) {
            "A regional plan cannot contain duplicate artifact paths."
        }
        require(estimatedBytes == artifacts.sumOf(RegionalArtifact::estimatedBytes)) {
            "The regional plan byte estimate is inconsistent."
        }
        require(maximumBatchBytes in 1L..DEFAULT_MAXIMUM_BATCH_BYTES && estimatedBytes <= maximumBatchBytes) {
            "The regional plan exceeds the configured download budget."
        }
        val expectedLicenses = artifacts
            .map { it.source.license }
            .distinctBy(RegionalDatasetLicense::id)
            .sortedBy(RegionalDatasetLicense::id)
        require(licenses == expectedLicenses) { "The regional plan license list is incomplete." }
    }

    val datasetIds: Set<String>
        get() = artifacts.mapTo(linkedSetOf()) { it.source.datasetId }
}

class RegionalDatasetPlanner(
    private val maximumBatchBytes: Long = DEFAULT_MAXIMUM_BATCH_BYTES,
) {
    init {
        require(maximumBatchBytes in 1L..DEFAULT_MAXIMUM_BATCH_BYTES) {
            "The regional download budget must be between 1 byte and $DEFAULT_MAXIMUM_BATCH_BYTES bytes."
        }
    }

    fun plan(request: RegionalDatasetRequest): RegionalDownloadPlan {
        val normalizedRequest = request.copy(bounds = request.bounds.normalizedToMicrodegrees())
        val artifacts = buildList {
            RegionalDatasetSelection.entries.forEach { selection ->
                if (selection !in normalizedRequest.selections) return@forEach
                when (selection) {
                    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM ->
                        addAll(rasterArtifacts(normalizedRequest, RegionalDatasetCatalog.copernicusGlo30Dsm, tileStep = 1))
                    RegionalDatasetSelection.ESA_WORLDCOVER_2021 ->
                        addAll(rasterArtifacts(normalizedRequest, RegionalDatasetCatalog.esaWorldCover2021, tileStep = 3))
                    RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL ->
                        add(buildingArtifact(normalizedRequest))
                }
            }
        }
        require(artifacts.size <= MAX_ARTIFACTS_PER_PLAN) {
            "The regional request resolves to too many artifacts. Reduce its bounds or selections."
        }
        val estimatedBytes = artifacts.sumOf(RegionalArtifact::estimatedBytes)
        require(estimatedBytes <= maximumBatchBytes) {
            "The estimated regional download is $estimatedBytes bytes, above the $maximumBatchBytes-byte budget. Reduce the bounds or selections."
        }
        val licenses = artifacts
            .map { it.source.license }
            .distinctBy(RegionalDatasetLicense::id)
            .sortedBy(RegionalDatasetLicense::id)
        return RegionalDownloadPlan(
            request = normalizedRequest,
            artifacts = artifacts,
            estimatedBytes = estimatedBytes,
            maximumBatchBytes = maximumBatchBytes,
            licenses = licenses,
        )
    }

    private fun rasterArtifacts(
        request: RegionalDatasetRequest,
        source: RegionalDatasetSource,
        tileStep: Int,
    ): List<RegionalArtifact> = tileOrigins(request.bounds, tileStep).map { (south, west) ->
        val latitude = signedLatitudeToken(south)
        val longitude = signedLongitudeToken(west)
        val tile = "$latitude$longitude"
        val copernicusToken = "${latitude}_00_${longitude}_00"
        val url: String
        val relativePath: String
        when (source.selection) {
            RegionalDatasetSelection.COPERNICUS_GLO_30_DSM -> {
                url = "$COPERNICUS_DOWNLOAD_ROOT/Copernicus_DSM_COG_10_${copernicusToken}_DEM/Copernicus_DSM_COG_10_${copernicusToken}_DEM.tif"
                relativePath = "elevation/copernicus-dem-glo30/${copernicusToken}_DEM.tif"
            }
            RegionalDatasetSelection.ESA_WORLDCOVER_2021 -> {
                url = "$WORLDCOVER_DOWNLOAD_ROOT/ESA_WorldCover_10m_2021_v200_${tile}_Map.tif"
                relativePath = "land-cover/esa-worldcover-2021/${tile}_Map.tif"
            }
            RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL -> error("Buildings are not raster tiles.")
        }
        RegionalArtifact(
            source = source,
            requestBounds = request.bounds,
            coverageBounds = RegionalBounds(
                west = west.toDouble(),
                south = south.toDouble(),
                east = (west + tileStep).toDouble(),
                north = (south + tileStep).toDouble(),
            ),
            south = south,
            west = west,
            url = url,
            relativePath = relativePath,
            estimatedBytes = source.estimatedBytesPerArtifact,
        )
    }

    private fun buildingArtifact(request: RegionalDatasetRequest): RegionalArtifact {
        val bounds = request.bounds
        val estimate = (
            MEBIBYTE + ceil(bounds.approximateAreaKm2 * BUILDING_ESTIMATE_BYTES_PER_KM2).toLong()
            ).coerceIn(RegionalDatasetCatalog.osmBuildingsExperimental.estimatedBytesPerArtifact, OVERPASS_MAX_RESPONSE_BYTES)
        return RegionalArtifact(
            source = RegionalDatasetCatalog.osmBuildingsExperimental,
            requestBounds = bounds,
            coverageBounds = bounds,
            url = OVERPASS_ENDPOINT,
            relativePath = "buildings/openstreetmap-overpass/${boundsPathToken(bounds)}.json",
            estimatedBytes = estimate,
            httpMethod = RegionalHttpMethod.POST,
            requestBody = overpassRequestBody(bounds),
            contentType = FORM_CONTENT_TYPE,
            cachePolicy = if (request.liveSnapshotRefresh) {
                RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH
            } else {
                RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE
            },
        )
    }
}

@Serializable
enum class RegionalTransferStatus {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    PROCESSING,
    READY,
    EXISTING,
    NOT_FOUND,
    FAILED,
    CANCELLED,
}

@Serializable
enum class RegionalProcessingState {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
}

@Serializable
data class RegionalDownloadProgress(
    val artifact: RegionalArtifact,
    val status: RegionalTransferStatus,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Double = 0.0,
    val message: String = "",
) {
    init {
        require(completedBytes >= 0L && (totalBytes == null || totalBytes >= completedBytes)) {
            "Regional download progress byte counts are invalid."
        }
        require(bytesPerSecond.isFinite() && bytesPerSecond >= 0.0) {
            "Regional download speed must be finite and non-negative."
        }
        require(message.length <= MAX_STATUS_MESSAGE_LENGTH) { "A regional progress message is too long." }
    }

    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { completedBytes.toFloat() / it.toFloat() }
}

@Serializable
data class RegionalProcessedOutput(
    val relativePath: String,
    val format: String,
    val bytes: Long,
    val sha256: String? = null,
    val recordCount: Long? = null,
    val notes: String = "",
) {
    init {
        require(isSafeRelativePath(relativePath)) { "A processed regional output path is unsafe." }
        require(format.isNotBlank() && format.length <= 80) { "A processed output format is required." }
        require(bytes >= 0L && (recordCount == null || recordCount >= 0L)) {
            "Processed output counts must be non-negative."
        }
        require(sha256 == null || SHA256_PATTERN.matches(sha256)) {
            "A processed output hash must be a lowercase SHA-256 digest."
        }
        require(notes.length <= MAX_STATUS_MESSAGE_LENGTH) { "Processed output notes are too long." }
    }
}

@Serializable
data class RegionalArtifactResult(
    val artifact: RegionalArtifact,
    val status: RegionalTransferStatus,
    val requestedUrl: String = artifact.url,
    val effectiveUrl: String? = null,
    val routeId: String = artifact.source.routeId,
    val routePolicyVersion: Int = artifact.source.routePolicyVersion,
    val acquiredAt: String? = null,
    val sourceSnapshot: RegionalSourceSnapshot = artifact.source.toSourceSnapshot(),
    val bytes: Long? = null,
    val sha256: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val processedOutput: RegionalProcessedOutput? = null,
    val notes: String = "",
    val error: String? = null,
) {
    init {
        require(requestedUrl == artifact.url && isStructurallySafeHttpsUrl(requestedUrl)) {
            "A regional result must retain its canonical requested URL."
        }
        require(effectiveUrl == null || hasSameHttpsOrigin(requestedUrl, effectiveUrl)) {
            "A regional result effective URL must remain within the requested HTTPS origin."
        }
        require(
            routeId == artifact.source.routeId &&
                routeId == sourceSnapshot.routeId &&
                routePolicyVersion == artifact.source.routePolicyVersion &&
                routePolicyVersion == sourceSnapshot.routePolicyVersion &&
                sourceSnapshot.datasetId == artifact.source.datasetId
        ) { "A regional result acquisition contract is inconsistent." }
        require(acquiredAt == null || isBoundedTimestamp(acquiredAt)) {
            "A regional result acquisition timestamp is invalid."
        }
        require(bytes == null || bytes >= 0L) { "A regional result size cannot be negative." }
        require(sha256 == null || SHA256_PATTERN.matches(sha256)) {
            "A regional result hash must be a lowercase SHA-256 digest."
        }
        require(notes.length <= MAX_STATUS_MESSAGE_LENGTH) { "Regional result notes are too long." }
        require(error == null || error.isNotBlank() && error.length <= MAX_STATUS_MESSAGE_LENGTH) {
            "A regional result error must be concise when present."
        }
        if (status == RegionalTransferStatus.READY || status == RegionalTransferStatus.EXISTING) {
            require(bytes != null && sha256 != null) {
                "Ready regional artifacts require a byte count and SHA-256 digest."
            }
        }
        if (status == RegionalTransferStatus.FAILED) {
            require(error != null) { "A failed regional result requires an error message." }
        }
    }
}

@Serializable
data class RegionalDownloadResult(
    val results: List<RegionalArtifactResult>,
) {
    init {
        require(results.isNotEmpty()) { "A regional download result cannot be empty." }
        require(results.map { it.artifact.relativePath }.distinct().size == results.size) {
            "A regional download result cannot contain duplicate artifacts."
        }
    }

    val isSuccessful: Boolean
        get() = results.all { result ->
            result.status == RegionalTransferStatus.READY ||
                result.status == RegionalTransferStatus.EXISTING ||
                (
                    result.status == RegionalTransferStatus.NOT_FOUND &&
                        result.artifact.source.optionalWhenNotPublished
                    )
        }

    val readyCount: Int
        get() = results.count { it.status == RegionalTransferStatus.READY }

    val existingCount: Int
        get() = results.count { it.status == RegionalTransferStatus.EXISTING }
}

@Serializable
data class RegionalInventoryRecord(
    val datasetId: String,
    val relativePath: String,
    val requestedUrl: String,
    val effectiveUrl: String? = null,
    val routeId: String,
    val routePolicyVersion: Int,
    val acquiredAt: String? = null,
    val sourceSnapshot: RegionalSourceSnapshot,
    val status: RegionalTransferStatus,
    val bytes: Long? = null,
    val sha256: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val checkedAt: String,
    val bounds: RegionalBounds,
    val processingState: RegionalProcessingState,
    val processedOutput: RegionalProcessedOutput? = null,
    val notes: String = "",
    val error: String? = null,
) {
    init {
        require(
            datasetId == sourceSnapshot.datasetId &&
                routeId == sourceSnapshot.routeId &&
                routePolicyVersion == sourceSnapshot.routePolicyVersion
        ) { "Regional inventory identity and source snapshot are inconsistent." }
        require(isSafeRelativePath(relativePath)) { "A regional inventory path is unsafe." }
        require(isStructurallySafeHttpsUrl(requestedUrl)) {
            "A regional inventory requested URL must be a bounded HTTPS URL."
        }
        require(effectiveUrl == null || hasSameHttpsOrigin(requestedUrl, effectiveUrl)) {
            "A regional inventory effective URL must remain within the requested HTTPS origin."
        }
        require(routePolicyVersion in 1..MAX_ROUTE_POLICY_VERSION) {
            "A regional inventory route policy version is outside the supported range."
        }
        require(acquiredAt == null || isBoundedTimestamp(acquiredAt)) {
            "A regional inventory acquisition timestamp is invalid."
        }
        require(bytes == null || bytes >= 0L) { "A regional inventory byte count cannot be negative." }
        require(sha256 == null || SHA256_PATTERN.matches(sha256)) {
            "A regional inventory hash must be a lowercase SHA-256 digest."
        }
        require(isBoundedTimestamp(checkedAt)) { "A regional inventory timestamp is invalid." }
        require(notes.length <= MAX_STATUS_MESSAGE_LENGTH) { "Regional inventory notes are too long." }
        require(error == null || error.isNotBlank() && error.length <= MAX_STATUS_MESSAGE_LENGTH) {
            "A regional inventory error must be concise when present."
        }
    }

    /** Compatibility accessors for UI code; persisted schema 2 uses [sourceSnapshot]. */
    val url: String
        get() = requestedUrl

    val sourceUrl: String
        get() = sourceSnapshot.sourceUrl

    val licenseId: String
        get() = sourceSnapshot.license.id

    val licenseUrl: String
        get() = sourceSnapshot.license.url

    val attribution: String
        get() = sourceSnapshot.license.attribution

    val provenance: String
        get() = sourceSnapshot.provenance
}

@Serializable
data class RegionalInventory(
    val schemaVersion: Int = REGIONAL_INVENTORY_SCHEMA_VERSION,
    val artifacts: Map<String, RegionalInventoryRecord> = emptyMap(),
    val updatedAt: String? = null,
    val lastBounds: RegionalBounds? = null,
) {
    init {
        require(schemaVersion == REGIONAL_INVENTORY_SCHEMA_VERSION) {
            "The regional inventory schema is unsupported."
        }
        require(artifacts.size <= MAX_INVENTORY_RECORDS) { "The regional inventory contains too many records." }
        require(artifacts.all { (path, record) -> path == record.relativePath }) {
            "Regional inventory keys must match their artifact paths."
        }
        require(updatedAt == null || isBoundedTimestamp(updatedAt)) {
            "The regional inventory update timestamp is invalid."
        }
    }
}

interface RegionalDatasetRepository {
    suspend fun acquire(
        plan: RegionalDownloadPlan,
        onProgress: (RegionalDownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RegionalDownloadResult

    suspend fun loadInventory(): RegionalInventory
}

private fun tileOrigins(bounds: RegionalBounds, step: Int): List<Pair<Int, Int>> {
    require(step > 0) { "Tile step must be positive." }
    val firstSouth = floor(bounds.south / step).toInt() * step
    val lastSouth = (ceil(bounds.north / step).toInt() - 1) * step
    val firstWest = floor(bounds.west / step).toInt() * step
    val lastWest = (ceil(bounds.east / step).toInt() - 1) * step
    return buildList {
        for (south in firstSouth..lastSouth step step) {
            if (south !in -90 until 90) continue
            for (west in firstWest..lastWest step step) {
                if (west !in -180 until 180) continue
                add(south to west)
            }
        }
    }
}

private fun signedLatitudeToken(value: Int): String =
    "${if (value >= 0) 'N' else 'S'}${abs(value).toString().padStart(2, '0')}"

private fun signedLongitudeToken(value: Int): String =
    "${if (value >= 0) 'E' else 'W'}${abs(value).toString().padStart(3, '0')}"

private fun boundsPathToken(bounds: RegionalBounds): String = listOf(
    coordinatePathToken(bounds.south, latitude = true),
    coordinatePathToken(bounds.west, latitude = false),
    coordinatePathToken(bounds.north, latitude = true),
    coordinatePathToken(bounds.east, latitude = false),
).joinToString("_")

private fun coordinatePathToken(value: Double, latitude: Boolean): String {
    val hemisphere = when {
        latitude && value >= 0.0 -> 'N'
        latitude -> 'S'
        value >= 0.0 -> 'E'
        else -> 'W'
    }
    val width = if (latitude) 8 else 9
    val scaled = (abs(value) * COORDINATE_PATH_SCALE).roundToLong()
    return "$hemisphere${scaled.toString().padStart(width, '0')}"
}

private fun Double.normalizedCoordinateToMicrodegrees(): Double = BigDecimal.valueOf(this)
    .setScale(CANONICAL_COORDINATE_DECIMAL_PLACES, RoundingMode.HALF_EVEN)
    .toDouble()

private fun overpassRequestBody(bounds: RegionalBounds): String {
    fun Double.queryCoordinate(): String = String.format(Locale.US, "%.6f", this)
    val bbox = "${bounds.south.queryCoordinate()},${bounds.west.queryCoordinate()}," +
        "${bounds.north.queryCoordinate()},${bounds.east.queryCoordinate()}"
    val query = "[out:json][timeout:$OVERPASS_TIMEOUT_SECONDS][maxsize:$OVERPASS_MAX_RESPONSE_BYTES];" +
        "(way[\"building\"]($bbox);way[\"building:part\"]($bbox););out geom;"
    return "data=${formEncodeAscii(query)}"
}

private fun formEncodeAscii(value: String): String = buildString(value.length * 2) {
    value.forEach { character ->
        when {
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '-' || character == '_' || character == '.' || character == '*' -> append(character)
            character == ' ' -> append('+')
            else -> {
                require(character.code <= 0x7f) { "The fixed Overpass query must be ASCII." }
                append('%')
                append(HEX_DIGITS[character.code ushr 4])
                append(HEX_DIGITS[character.code and 0x0f])
            }
        }
    }
}

private fun validateProviderRequest(artifact: RegionalArtifact) {
    when (artifact.source.selection) {
        RegionalDatasetSelection.COPERNICUS_GLO_30_DSM -> {
            val south = requireNotNull(artifact.south) { "A Copernicus artifact requires a tile latitude." }
            val west = requireNotNull(artifact.west) { "A Copernicus artifact requires a tile longitude." }
            require(artifact.httpMethod == RegionalHttpMethod.GET && artifact.requestBody == null && artifact.contentType == null) {
                "Copernicus artifacts require a bodyless GET request."
            }
            val token = "${signedLatitudeToken(south)}_00_${signedLongitudeToken(west)}_00"
            val expectedUrl = "$COPERNICUS_DOWNLOAD_ROOT/Copernicus_DSM_COG_10_${token}_DEM/Copernicus_DSM_COG_10_${token}_DEM.tif"
            require(artifact.url == expectedUrl && artifact.relativePath == "elevation/copernicus-dem-glo30/${token}_DEM.tif") {
                "The Copernicus artifact endpoint or path is not allowed."
            }
            require(artifact.coverageBounds == RegionalBounds(west.toDouble(), south.toDouble(), west + 1.0, south + 1.0)) {
                "The Copernicus artifact coverage does not match its tile."
            }
        }
        RegionalDatasetSelection.ESA_WORLDCOVER_2021 -> {
            val south = requireNotNull(artifact.south) { "A WorldCover artifact requires a tile latitude." }
            val west = requireNotNull(artifact.west) { "A WorldCover artifact requires a tile longitude." }
            require(south % 3 == 0 && west % 3 == 0) { "WorldCover tile origins must align to three degrees." }
            require(artifact.httpMethod == RegionalHttpMethod.GET && artifact.requestBody == null && artifact.contentType == null) {
                "WorldCover artifacts require a bodyless GET request."
            }
            val tile = "${signedLatitudeToken(south)}${signedLongitudeToken(west)}"
            val expectedUrl = "$WORLDCOVER_DOWNLOAD_ROOT/ESA_WorldCover_10m_2021_v200_${tile}_Map.tif"
            require(artifact.url == expectedUrl && artifact.relativePath == "land-cover/esa-worldcover-2021/${tile}_Map.tif") {
                "The WorldCover artifact endpoint or path is not allowed."
            }
            require(artifact.coverageBounds == RegionalBounds(west.toDouble(), south.toDouble(), west + 3.0, south + 3.0)) {
                "The WorldCover artifact coverage does not match its tile."
            }
        }
        RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL -> {
            require(artifact.south == null && artifact.west == null && artifact.coverageBounds == artifact.requestBounds) {
                "An Overpass building artifact must use its exact request bounds."
            }
            require(
                artifact.url == OVERPASS_ENDPOINT &&
                    artifact.httpMethod == RegionalHttpMethod.POST &&
                    artifact.requestBody == overpassRequestBody(artifact.requestBounds) &&
                    artifact.contentType == FORM_CONTENT_TYPE,
            ) { "The Overpass artifact request is not the fixed bounded building query." }
        }
    }
}

private fun isSafeRelativePath(value: String): Boolean {
    if (value.isBlank() || value.length > MAX_RELATIVE_PATH_LENGTH || value.startsWith('/') || '\\' in value) return false
    if (!SAFE_PATH_PATTERN.matches(value)) return false
    return value.split('/').none { it.isBlank() || it == "." || it == ".." }
}

private fun isBoundedText(value: String, maximumLength: Int): Boolean =
    value.isNotBlank() &&
        value.length <= maximumLength &&
        value.none(Char::isISOControl)

private fun isBoundedTimestamp(value: String): Boolean {
    if (value.length != UTC_TIMESTAMP_LENGTH || !UTC_TIMESTAMP_PATTERN.matches(value)) return false
    return try {
        val formatter = SimpleDateFormat(UTC_TIMESTAMP_FORMAT, Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        formatter.parse(value, position) != null && position.index == value.length
    } catch (_: Exception) {
        false
    }
}

private fun isStructurallySafeHttpsUrl(value: String): Boolean {
    if (value.length !in 1..MAX_URL_LENGTH || value.any(Char::isISOControl)) return false
    return try {
        val uri = URI(value)
        uri.isAbsolute &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            uri.port in setOf(-1, 443)
    } catch (_: Exception) {
        false
    }
}

private fun hasSameHttpsOrigin(requestedUrl: String, effectiveUrl: String): Boolean {
    if (!isStructurallySafeHttpsUrl(requestedUrl) || !isStructurallySafeHttpsUrl(effectiveUrl)) return false
    return try {
        val requested = URI(requestedUrl)
        val effective = URI(effectiveUrl)
        val requestedHost = requested.host.trimEnd('.').lowercase(Locale.US)
        val effectiveHost = effective.host.trimEnd('.').lowercase(Locale.US)
        val requestedPort = if (requested.port == -1) DEFAULT_HTTPS_PORT else requested.port
        val effectivePort = if (effective.port == -1) DEFAULT_HTTPS_PORT else effective.port
        requestedHost == effectiveHost && requestedPort == effectivePort
    } catch (_: Exception) {
        false
    }
}

const val REGIONAL_INVENTORY_SCHEMA_VERSION = 2
const val REGIONAL_DATASET_CATALOG_REVISION = 2
const val MAX_REASON_LENGTH = 120
const val MAX_STATUS_MESSAGE_LENGTH = 500
const val MAX_REGIONAL_WIDTH_DEGREES = 1.0
const val MAX_REGIONAL_HEIGHT_DEGREES = 1.0
const val MAX_BUILDING_SPAN_DEGREES = 0.05
const val MAX_BUILDING_AREA_KM2 = 25.0
const val MAX_ARTIFACTS_PER_PLAN = 12
const val MAX_INVENTORY_RECORDS = 2_048
const val MEBIBYTE = 1024L * 1024L
const val DEFAULT_MAXIMUM_BATCH_BYTES = 384L * MEBIBYTE
const val OVERPASS_MAX_RESPONSE_BYTES = 16L * MEBIBYTE
const val OSM_BUILDINGS_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L

private const val EARTH_MEAN_RADIUS_KM = 6_371.0088
private const val COORDINATE_EPSILON = 1e-9
private const val COORDINATE_PATH_SCALE = 1_000_000.0
private const val CANONICAL_COORDINATE_DECIMAL_PLACES = 6
private const val BUILDING_ESTIMATE_BYTES_PER_KM2 = 512.0 * 1024.0
private const val OVERPASS_TIMEOUT_SECONDS = 25
private const val COPERNICUS_DOWNLOAD_ROOT = "https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com"
private const val WORLDCOVER_DOWNLOAD_ROOT = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map"
private const val OVERPASS_ENDPOINT = "https://lambert.openstreetmap.de/api/interpreter"
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
private const val MAX_RELATIVE_PATH_LENGTH = 240
private const val MAX_URL_LENGTH = 2_048
private const val DEFAULT_HTTPS_PORT = 443
private const val UTC_TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
private const val UTC_TIMESTAMP_LENGTH = 24
private val UTC_TIMESTAMP_PATTERN = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
private const val MAX_SOURCE_TITLE_LENGTH = 200
private const val MAX_PROVIDER_LENGTH = 200
private const val MAX_SOURCE_VERSION_LENGTH = 100
private const val MAX_CRS_LENGTH = 100
private const val MAX_ATTRIBUTION_LENGTH = 1_000
private const val MAX_PROVENANCE_LENGTH = 2_000
private const val MAX_LIMITATIONS_LENGTH = 2_000
private const val MAX_CATALOG_REVISION = 1_000_000
private const val MAX_ROUTE_POLICY_VERSION = 1_000_000
private const val MAXIMUM_LIVE_SNAPSHOT_CACHE_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val HEX_DIGITS = "0123456789ABCDEF"

private val STABLE_ID_PATTERN = Regex("^[a-z0-9][a-z0-9.-]{1,79}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val SAFE_PATH_PATTERN = Regex("^[A-Za-z0-9._/-]+$")
