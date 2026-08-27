package com.gecesars.atxplan.domain.study

import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.Receiver
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import com.gecesars.atxplan.domain.rf.RfCalculator
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val PROJECT_LINK_STUDY_ENGINE_ID = "atx-project-link-study-v1"
const val MEAN_EARTH_GEODESY_ID = "mean-earth-great-circle-v1"
const val EARTH_MEAN_RADIUS_M = 6_371_008.8
private const val PROJECT_LINK_STUDY_RF_IMPLEMENTATION_ID = "atx-plan-kotlin-fspl-v2"

@Serializable
data class LinkStudyCoordinate(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
) {
    init {
        require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "A link-study latitude must be finite and between -90 and 90 degrees."
        }
        require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "A link-study longitude must be finite and between -180 and 180 degrees."
        }
    }
}

@Serializable
data class LinkStudyTransmitterSnapshot(
    val siteId: String,
    val siteName: String,
    val sectorId: String,
    val sectorName: String,
    val location: LinkStudyCoordinate,
    val storedSiteGroundElevationM: Double?,
    val antennaHeightAglM: Double,
    val sectorAzimuthDegrees: Double,
    val electricalTiltDegrees: Double,
    val sectorActive: Boolean,
    val directionalPatternReferenced: Boolean,
) {
    init {
        require(siteId.isNotBlank() && sectorId.isNotBlank()) {
            "A link-study transmitter requires source IDs."
        }
        require(siteName.isNotBlank() && sectorName.isNotBlank()) {
            "A link-study transmitter requires source names."
        }
        require(siteName.length <= MAX_SOURCE_NAME_CHARS && sectorName.length <= MAX_SOURCE_NAME_CHARS) {
            "A link-study transmitter source name exceeds the safe limit."
        }
        require(antennaHeightAglM.isFinite() && antennaHeightAglM >= 0.0) {
            "A transmitter height must be finite and nonnegative."
        }
        require(storedSiteGroundElevationM == null || storedSiteGroundElevationM.isFinite()) {
            "A stored transmitter-site ground elevation must be finite when available."
        }
        require(sectorAzimuthDegrees.isFinite() && sectorAzimuthDegrees in 0.0..360.0) {
            "A transmitter azimuth must be between 0 and 360 degrees."
        }
        require(electricalTiltDegrees.isFinite() && electricalTiltDegrees in -90.0..90.0) {
            "A transmitter tilt must be between -90 and 90 degrees."
        }
    }
}

@Serializable
data class LinkStudyReceiverSnapshot(
    val receiverId: String,
    val receiverName: String,
    val location: LinkStudyCoordinate,
    val antennaHeightAglM: Double,
) {
    init {
        require(receiverId.isNotBlank() && receiverName.isNotBlank()) {
            "A link-study receiver requires a source ID and name."
        }
        require(receiverName.length <= MAX_SOURCE_NAME_CHARS) {
            "A link-study receiver source name exceeds the safe limit."
        }
        require(antennaHeightAglM.isFinite() && antennaHeightAglM >= 0.0) {
            "A receiver height must be finite and nonnegative."
        }
    }
}

@Serializable
data class ProjectLinkStudyInputSnapshot(
    val projectId: String,
    val projectName: String,
    val networkId: String,
    val networkName: String,
    val networkDownlinkFrequencyMHz: Double,
    val networkBandwidthMHz: Double,
    val networkActive: Boolean,
    val transmitter: LinkStudyTransmitterSnapshot,
    val receiver: LinkStudyReceiverSnapshot,
    val receiverCompatibilityProfilePresent: Boolean,
    val receiverCompatibilityOverridesApplied: Boolean,
    val linkBudget: LinkBudgetInput,
) {
    init {
        require(projectId.isNotBlank() && projectName.isNotBlank()) {
            "A project link study requires a project snapshot."
        }
        require(projectName.length <= MAX_SOURCE_NAME_CHARS) {
            "A link-study project source name exceeds the safe limit."
        }
        require(networkId.isNotBlank() && networkName.isNotBlank()) {
            "A project link study requires a network snapshot."
        }
        require(networkName.length <= MAX_SOURCE_NAME_CHARS) {
            "A link-study network source name exceeds the safe limit."
        }
        require(networkDownlinkFrequencyMHz.isFinite() && networkDownlinkFrequencyMHz > 0.0) {
            "A link-study network downlink frequency must be positive and finite."
        }
        require(networkBandwidthMHz.isFinite() && networkBandwidthMHz > 0.0) {
            "A link-study network bandwidth must be positive and finite."
        }
        require(!receiverCompatibilityOverridesApplied || receiverCompatibilityProfilePresent) {
            "Receiver compatibility overrides require a compatibility profile."
        }
    }
}

@Serializable
data class ProjectLinkStudyGeometry(
    val geodesyId: String = MEAN_EARTH_GEODESY_ID,
    val earthMeanRadiusM: Double = EARTH_MEAN_RADIUS_M,
    val horizontalDistanceM: Double,
    val heightDeltaM: Double,
    val inclinedDistanceM: Double,
    val initialBearingDegrees: Double,
    val relativeAzimuthDegrees: Double,
    val elevationAngleDegrees: Double,
) {
    init {
        require(geodesyId.isNotBlank()) { "A project link study requires a geodesy ID." }
        require(earthMeanRadiusM.isFinite() && earthMeanRadiusM > 0.0) {
            "The mean-Earth radius must be positive and finite."
        }
        require(horizontalDistanceM.isFinite() && horizontalDistanceM > 0.0) {
            "A project link study requires a positive horizontal distance."
        }
        require(heightDeltaM.isFinite()) { "The endpoint height difference must be finite." }
        require(inclinedDistanceM.isFinite() && inclinedDistanceM >= horizontalDistanceM) {
            "The inclined distance cannot be shorter than the horizontal distance."
        }
        require(initialBearingDegrees.isFinite() && initialBearingDegrees in 0.0..<360.0) {
            "The initial bearing must be in the range [0, 360) degrees."
        }
        require(relativeAzimuthDegrees.isFinite() && relativeAzimuthDegrees in 0.0..<360.0) {
            "The relative azimuth must be in the range [0, 360) degrees."
        }
        require(elevationAngleDegrees.isFinite() && elevationAngleDegrees in -90.0..90.0) {
            "The elevation angle must be between -90 and 90 degrees."
        }
    }
}

@Serializable
enum class LinkStudyTerrainState {
    NO_DATA,
}

@Serializable
data class ProjectLinkStudyRecord(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val engineId: String = PROJECT_LINK_STUDY_ENGINE_ID,
    val inputFingerprintSha256: String,
    val input: ProjectLinkStudyInputSnapshot,
    val geometry: ProjectLinkStudyGeometry,
    val result: LinkBudgetResult,
    val terrainState: LinkStudyTerrainState = LinkStudyTerrainState.NO_DATA,
    val warnings: List<String>,
) {
    init {
        require(id.isNotBlank()) { "A project link study requires an ID." }
        require(name.trim().length in 2..80) {
            "Use a study name between 2 and 80 characters."
        }
        require(createdAtEpochMillis >= 0L) { "A study timestamp cannot be negative." }
        require(engineId == PROJECT_LINK_STUDY_ENGINE_ID) {
            "The project link-study engine is not supported."
        }
        require(SHA256_PATTERN.matches(inputFingerprintSha256)) {
            "A project link study requires a lowercase SHA-256 input fingerprint."
        }
        require(warnings.size <= MAX_WARNINGS && warnings.all { it.isNotBlank() && it.length <= MAX_WARNING_CHARS }) {
            "Project link-study warnings exceed their safe limits."
        }
        require(inputFingerprintSha256 == ProjectLinkStudyFingerprint.calculate(input, geometry)) {
            "The project link-study input fingerprint does not match its immutable snapshot."
        }
        require(geometry.geodesyId == MEAN_EARTH_GEODESY_ID && geometry.earthMeanRadiusM == EARTH_MEAN_RADIUS_M) {
            "The project link-study geodesy is not supported."
        }
        val canonicalInverse = MeanEarthGeodesy.inverse(
            input.transmitter.location,
            input.receiver.location,
        )
        require(abs(canonicalInverse.horizontalDistanceM - geometry.horizontalDistanceM) <= GEOMETRY_TOLERANCE) {
            "The stored horizontal distance does not match the endpoint snapshot."
        }
        require(
            angularDifferenceDegrees(
                canonicalInverse.initialBearingDegrees,
                geometry.initialBearingDegrees,
            ) <= GEOMETRY_TOLERANCE
        ) {
            "The stored initial bearing does not match the endpoint snapshot."
        }
        val canonicalHeightDelta = input.receiver.antennaHeightAglM - input.transmitter.antennaHeightAglM
        val canonicalInclinedDistance = hypot(canonicalInverse.horizontalDistanceM, canonicalHeightDelta)
        require(abs(canonicalHeightDelta - geometry.heightDeltaM) <= GEOMETRY_TOLERANCE) {
            "The stored height difference does not match the endpoint snapshot."
        }
        require(abs(canonicalInclinedDistance - geometry.inclinedDistanceM) <= GEOMETRY_TOLERANCE) {
            "The stored inclined distance does not match the endpoint snapshot."
        }
        val canonicalRelativeAzimuth = canonicalDegrees(
            canonicalInverse.initialBearingDegrees - input.transmitter.sectorAzimuthDegrees,
        )
        require(angularDifferenceDegrees(canonicalRelativeAzimuth, geometry.relativeAzimuthDegrees) <= GEOMETRY_TOLERANCE) {
            "The stored relative azimuth does not match the transmitter snapshot."
        }
        val canonicalElevationAngle = Math.toDegrees(
            atan2(canonicalHeightDelta, canonicalInverse.horizontalDistanceM),
        )
        require(abs(canonicalElevationAngle - geometry.elevationAngleDegrees) <= GEOMETRY_TOLERANCE) {
            "The stored elevation angle does not match the endpoint snapshot."
        }
        require(abs(input.linkBudget.distanceKm * 1_000.0 - geometry.inclinedDistanceM) <= GEOMETRY_TOLERANCE) {
            "The link-budget distance does not match the project geometry."
        }
        require(input.linkBudget.bandwidthMHz == input.networkBandwidthMHz) {
            "The link-budget bandwidth does not match the network snapshot."
        }
        require(input.linkBudget.additionalPathLossDb == 0.0) {
            "The project link-study engine does not support additional path loss."
        }
        require(result.provenance.implementationId == PROJECT_LINK_STUDY_RF_IMPLEMENTATION_ID) {
            "The stored project link-study RF implementation is not supported."
        }
        val canonicalResult = RfCalculator.linkBudgetForImplementation(
            PROJECT_LINK_STUDY_RF_IMPLEMENTATION_ID,
            input.linkBudget,
        )
        require(result.matches(canonicalResult)) {
            "The stored project link-study result does not match its effective inputs."
        }
        require(warnings == projectLinkStudyWarnings(input)) {
            "The project link-study warnings do not match its immutable source snapshot."
        }
    }
}

data class MeanEarthInverse(
    val horizontalDistanceM: Double,
    val initialBearingDegrees: Double,
)

object MeanEarthGeodesy {
    fun inverse(start: LinkStudyCoordinate, end: LinkStudyCoordinate): MeanEarthInverse {
        require(start != end) { "A project link study requires distinct endpoint coordinates." }
        val startVector = start.unitVector()
        val endVector = end.unitVector()
        val dot = startVector.zip(endVector).sumOf { (left, right) -> left * right }.coerceIn(-1.0, 1.0)
        val centralAngle = acos(dot)
        require(centralAngle > 0.0) { "A project link study requires distinct endpoint coordinates." }
        require(kotlin.math.abs(PI - centralAngle) >= ANTIPODAL_TOLERANCE_RADIANS) {
            "The selected endpoints are antipodal; the initial bearing is ambiguous for this baseline."
        }

        val latitude1 = Math.toRadians(start.latitudeDegrees)
        val latitude2 = Math.toRadians(end.latitudeDegrees)
        val deltaLongitude = Math.toRadians(shortestLongitudeDelta(start.longitudeDegrees, end.longitudeDegrees))
        val y = sin(deltaLongitude) * cos(latitude2)
        val x = cos(latitude1) * sin(latitude2) -
            sin(latitude1) * cos(latitude2) * cos(deltaLongitude)
        require(kotlin.math.abs(x) > BEARING_TOLERANCE || kotlin.math.abs(y) > BEARING_TOLERANCE) {
            "The initial bearing is undefined for the selected endpoints."
        }
        val bearing = canonicalDegrees(Math.toDegrees(atan2(y, x)))
        return MeanEarthInverse(
            horizontalDistanceM = centralAngle * EARTH_MEAN_RADIUS_M,
            initialBearingDegrees = bearing,
        )
    }

    private fun LinkStudyCoordinate.unitVector(): List<Double> {
        val latitude = Math.toRadians(latitudeDegrees)
        val longitude = Math.toRadians(longitudeDegrees)
        val cosineLatitude = cos(latitude)
        return listOf(
            cosineLatitude * cos(longitude),
            cosineLatitude * sin(longitude),
            sin(latitude),
        )
    }

    private fun shortestLongitudeDelta(startDegrees: Double, endDegrees: Double): Double {
        var delta = (endDegrees - startDegrees) % 360.0
        if (delta >= 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }
}

object ProjectLinkStudyEngine {
    fun calculate(
        id: String,
        name: String,
        createdAtEpochMillis: Long,
        projectId: String,
        projectName: String,
        network: RfNetwork,
        site: RadioSite,
        sector: Sector,
        receiver: Receiver,
    ): ProjectLinkStudyRecord {
        require(sector.networkId == network.id) {
            "The selected sector does not reference the selected network."
        }
        val compatibilityProfile = receiver.networkProfiles.firstOrNull { profile ->
            profile.networkId == network.id
        }
        require(receiver.networkId == network.id || compatibilityProfile != null) {
            "The selected receiver is not compatible with the sector network."
        }
        val compatibilityOverridesApplied = compatibilityProfile?.let { profile ->
            profile.antennaGainDbi != null ||
                profile.systemLossDb != null ||
                profile.sensitivityDbm != null
        } == true

        val transmitter = LinkStudyTransmitterSnapshot(
            siteId = site.id,
            siteName = site.name,
            sectorId = sector.id,
            sectorName = sector.name,
            location = site.location.toStudyCoordinate(),
            storedSiteGroundElevationM = site.groundElevationM,
            antennaHeightAglM = sector.antennaHeightM,
            sectorAzimuthDegrees = canonicalDegrees(sector.azimuthDegrees),
            electricalTiltDegrees = sector.electricalTiltDegrees,
            sectorActive = sector.active,
            directionalPatternReferenced = sector.transmitAntennaPatternId != null,
        )
        val receiverSnapshot = LinkStudyReceiverSnapshot(
            receiverId = receiver.id,
            receiverName = receiver.name,
            location = LinkStudyCoordinate(
                latitudeDegrees = receiver.location.latitude.value,
                longitudeDegrees = receiver.location.longitude.value,
            ),
            antennaHeightAglM = receiver.antennaHeightM.value,
        )
        val inverse = MeanEarthGeodesy.inverse(transmitter.location, receiverSnapshot.location)
        val heightDeltaM = receiverSnapshot.antennaHeightAglM - transmitter.antennaHeightAglM
        val inclinedDistanceM = hypot(inverse.horizontalDistanceM, heightDeltaM)
        val geometry = ProjectLinkStudyGeometry(
            horizontalDistanceM = inverse.horizontalDistanceM,
            heightDeltaM = heightDeltaM,
            inclinedDistanceM = inclinedDistanceM,
            initialBearingDegrees = inverse.initialBearingDegrees,
            relativeAzimuthDegrees = canonicalDegrees(
                inverse.initialBearingDegrees - transmitter.sectorAzimuthDegrees,
            ),
            elevationAngleDegrees = Math.toDegrees(atan2(heightDeltaM, inverse.horizontalDistanceM)),
        )
        val linkBudgetInput = LinkBudgetInput(
            frequencyMHz = sector.frequencyMHz,
            distanceKm = inclinedDistanceM / 1_000.0,
            transmitPowerDbm = sector.transmitPowerDbm,
            transmitAntennaGainDbi = sector.antennaGainDbi,
            transmitLossDb = sector.feederLossDb,
            receiveAntennaGainDbi = compatibilityProfile?.antennaGainDbi
                ?: receiver.antennaGainDbi.value,
            receiveLossDb = compatibilityProfile?.systemLossDb
                ?: receiver.systemLossDb.value,
            additionalPathLossDb = 0.0,
            receiverSensitivityDbm = compatibilityProfile?.sensitivityDbm
                ?: receiver.sensitivityDbm.value,
            bandwidthMHz = network.bandwidthMHz,
            receiverNoiseFigureDb = receiver.noiseFigureDb.value,
        )
        val input = ProjectLinkStudyInputSnapshot(
            projectId = projectId,
            projectName = projectName,
            networkId = network.id,
            networkName = network.name,
            networkDownlinkFrequencyMHz = network.downlinkFrequencyMHz,
            networkBandwidthMHz = network.bandwidthMHz,
            networkActive = network.active,
            transmitter = transmitter,
            receiver = receiverSnapshot,
            receiverCompatibilityProfilePresent = compatibilityProfile != null,
            receiverCompatibilityOverridesApplied = compatibilityOverridesApplied,
            linkBudget = linkBudgetInput,
        )
        return ProjectLinkStudyRecord(
            id = id,
            name = name.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
            inputFingerprintSha256 = ProjectLinkStudyFingerprint.calculate(input, geometry),
            input = input,
            geometry = geometry,
            result = RfCalculator.linkBudgetForImplementation(
                PROJECT_LINK_STUDY_RF_IMPLEMENTATION_ID,
                linkBudgetInput,
            ),
            warnings = projectLinkStudyWarnings(input),
        )
    }
}

object ProjectLinkStudyFingerprint {
    fun calculate(
        input: ProjectLinkStudyInputSnapshot,
        geometry: ProjectLinkStudyGeometry,
    ): String {
        val values = listOf(
            FINGERPRINT_FORMAT,
            input.projectId,
            input.projectName,
            input.networkId,
            input.networkName,
            input.networkDownlinkFrequencyMHz.toString(),
            input.networkBandwidthMHz.toString(),
            input.networkActive.toString(),
            input.transmitter.siteId,
            input.transmitter.siteName,
            input.transmitter.sectorId,
            input.transmitter.sectorName,
            input.transmitter.location.latitudeDegrees.toString(),
            input.transmitter.location.longitudeDegrees.toString(),
            input.transmitter.storedSiteGroundElevationM?.toString() ?: "NoData",
            input.transmitter.antennaHeightAglM.toString(),
            input.transmitter.sectorAzimuthDegrees.toString(),
            input.transmitter.electricalTiltDegrees.toString(),
            input.transmitter.sectorActive.toString(),
            input.transmitter.directionalPatternReferenced.toString(),
            input.receiver.receiverId,
            input.receiver.receiverName,
            input.receiver.location.latitudeDegrees.toString(),
            input.receiver.location.longitudeDegrees.toString(),
            input.receiver.antennaHeightAglM.toString(),
            input.receiverCompatibilityProfilePresent.toString(),
            input.receiverCompatibilityOverridesApplied.toString(),
            input.linkBudget.frequencyMHz.toString(),
            input.linkBudget.distanceKm.toString(),
            input.linkBudget.transmitPowerDbm.toString(),
            input.linkBudget.transmitAntennaGainDbi.toString(),
            input.linkBudget.transmitLossDb.toString(),
            input.linkBudget.receiveAntennaGainDbi.toString(),
            input.linkBudget.receiveLossDb.toString(),
            input.linkBudget.additionalPathLossDb.toString(),
            input.linkBudget.receiverSensitivityDbm.toString(),
            input.linkBudget.bandwidthMHz.toString(),
            input.linkBudget.receiverNoiseFigureDb.toString(),
            geometry.geodesyId,
            geometry.earthMeanRadiusM.toString(),
            geometry.horizontalDistanceM.toString(),
            geometry.heightDeltaM.toString(),
            geometry.inclinedDistanceM.toString(),
            geometry.initialBearingDegrees.toString(),
            geometry.relativeAzimuthDegrees.toString(),
            geometry.elevationAngleDegrees.toString(),
        )
        val canonical = buildString {
            values.forEach { value ->
                append(value.toByteArray(Charsets.UTF_8).size)
                append(':')
                append(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private fun GeoPoint.toStudyCoordinate() = LinkStudyCoordinate(
    latitudeDegrees = latitude,
    longitudeDegrees = longitude,
)

private fun canonicalDegrees(value: Double): Double {
    val normalized = value % 360.0
    return if (normalized < 0.0) normalized + 360.0 else normalized
}

private fun angularDifferenceDegrees(left: Double, right: Double): Double =
    abs(((left - right + 540.0) % 360.0) - 180.0)

private fun LinkBudgetResult.matches(expected: LinkBudgetResult): Boolean =
    provenance == expected.provenance &&
        numericallyMatches(freeSpacePathLossDb, expected.freeSpacePathLossDb) &&
        numericallyMatches(eirpDbm, expected.eirpDbm) &&
        numericallyMatches(receivedPowerDbm, expected.receivedPowerDbm) &&
        numericallyMatches(fadeMarginDb, expected.fadeMarginDb) &&
        numericallyMatches(
            firstFresnelMidpointRadiusM,
            expected.firstFresnelMidpointRadiusM,
        ) &&
        numericallyMatches(noiseFloorDbm, expected.noiseFloorDbm) &&
        numericallyMatches(signalToNoiseDb, expected.signalToNoiseDb)

private fun numericallyMatches(actual: Double, expected: Double): Boolean {
    if (!actual.isFinite() || !expected.isFinite()) return false
    val scale = maxOf(1.0, abs(expected))
    return abs(actual - expected) <= RF_RESULT_ABSOLUTE_TOLERANCE +
        RF_RESULT_RELATIVE_TOLERANCE * scale
}

private fun projectLinkStudyWarnings(input: ProjectLinkStudyInputSnapshot): List<String> = buildList {
    add(
        "Terrain profile is NoData. Stored site ground elevation was not evaluated; " +
            "inclined distance uses antenna heights AGL over a flat reference.",
    )
    add(
        "Terrain, Earth-curvature clearance, effective-Earth propagation, LOS, Fresnel clearance, diffraction, clutter, buildings, vegetation, atmospheric gas, rain, and variability were not evaluated.",
    )
    if (input.transmitter.directionalPatternReferenced) {
        add(
            "The sector references an antenna pattern, but this engine used nominal gain without directional attenuation.",
        )
    } else {
        add("Nominal isotropic-referenced antenna gain was used; no directional pattern was evaluated.")
    }
    if (!input.transmitter.sectorActive) {
        add("The selected sector was inactive when this snapshot was created.")
    }
    if (!input.networkActive) {
        add("The selected network was inactive when this snapshot was created.")
    }
    if (input.receiverCompatibilityOverridesApplied) {
        add(
            "The receiver compatibility profile for ${input.networkName} supplied available receive-chain overrides.",
        )
    } else if (input.receiverCompatibilityProfilePresent) {
        add(
            "The receiver compatibility profile for ${input.networkName} declared compatibility without receive-chain overrides.",
        )
    }
    if (
        abs(input.linkBudget.frequencyMHz - input.networkDownlinkFrequencyMHz) >
        FREQUENCY_TOLERANCE_MHZ
    ) {
        add(
            "Sector frequency ${input.linkBudget.frequencyMHz} MHz was used instead of network downlink " +
                "${input.networkDownlinkFrequencyMHz} MHz.",
        )
    }
}

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private const val FINGERPRINT_FORMAT = "atx-project-link-study-input-v1"
private const val MAX_WARNINGS = 32
private const val MAX_WARNING_CHARS = 500
private const val MAX_SOURCE_NAME_CHARS = 160
private const val ANTIPODAL_TOLERANCE_RADIANS = 1e-12
private const val BEARING_TOLERANCE = 1e-15
private const val FREQUENCY_TOLERANCE_MHZ = 1e-9
private const val GEOMETRY_TOLERANCE = 1e-6
private const val RF_RESULT_ABSOLUTE_TOLERANCE = 1e-9
private const val RF_RESULT_RELATIVE_TOLERANCE = 1e-12
