package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class BroadcastService {
    FM,
    DIGITAL_TV,
}

enum class ContourPurpose {
    PROTECTED,
    INTERFERING,
    SCREENING,
}

enum class ContourStatus {
    COMPLETE,
    INCOMPLETE,
    NO_DATA,
}

enum class ContourRadialStatus {
    COMPLETE,
    MODEL_BOUNDARY,
    NO_DATA,
}

data class ContourRadial(
    val azimuthDegrees: Double,
    val distanceKm: Double?,
    val erpKw: Double,
    val effectiveHeightM: Double,
    val status: ContourRadialStatus,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(azimuthDegrees.isFinite() && azimuthDegrees in 0.0..<360.0) {
            "A contour radial azimuth must be in [0, 360) degrees."
        }
        require(distanceKm == null || distanceKm.isFinite() && distanceKm > 0.0) {
            "A contour radial distance must be positive when available."
        }
        require(
            erpKw.isFinite() &&
                (erpKw > 0.0 || status == ContourRadialStatus.NO_DATA && erpKw == 0.0),
        ) {
            "A contour radial ERP must be positive, or zero only when the radial is NoData."
        }
        require(effectiveHeightM.isFinite()) { "A contour radial height must be finite." }
        require(warnings.none(String::isBlank)) { "Contour radial warnings cannot be blank." }
    }
}

/**
 * A transient map overlay with enough evidence to avoid presenting a reference curve as a filing
 * result. Geometry completeness and regulatory fitness are deliberately separate properties.
 */
data class ServiceContourOverlay(
    val id: String,
    val siteId: String,
    val sectorId: String,
    val service: BroadcastService,
    val purpose: ContourPurpose,
    val statisticalBasis: String,
    val thresholdDbuvPerM: Double?,
    val points: List<GeoPoint>,
    val status: ContourStatus,
    val model: String,
    val rulesetId: String,
    val warnings: List<String>,
    val sourceUrl: String = "",
    val regulatory: Boolean = false,
    val radials: List<ContourRadial> = emptyList(),
    val inputFingerprint: String = "",
) {
    init {
        require(id.isNotBlank() && siteId.isNotBlank() && sectorId.isNotBlank()) {
            "A service contour requires stable project references."
        }
        require(statisticalBasis.isNotBlank() && model.isNotBlank() && rulesetId.isNotBlank()) {
            "A service contour requires statistical, model, and ruleset provenance."
        }
        require(thresholdDbuvPerM == null || thresholdDbuvPerM.isFinite()) {
            "A service contour threshold must be finite when available."
        }
        require(status == ContourStatus.NO_DATA || points.size >= 2) {
            "A drawable service contour requires at least two points."
        }
        require(status != ContourStatus.COMPLETE || points.first() == points.last()) {
            "A complete service contour must contain a closed ring."
        }
        require(warnings.none(String::isBlank)) { "Contour warnings cannot be blank." }
    }
}

data class BroadcastContourPlan(
    val overlays: List<ServiceContourOverlay>,
    val skippedSectorCount: Int,
) {
    init {
        require(skippedSectorCount >= 0) { "The skipped sector count cannot be negative." }
        require(overlays.map(ServiceContourOverlay::id).distinct().size == overlays.size) {
            "A broadcast contour plan contains duplicate overlay IDs."
        }
    }
}

data class BrazilProtectedContourProfile(
    val service: BroadcastService,
    val channel: Int?,
    val statisticalBasis: String,
    val thresholdDbuvPerM: Double,
    val rulesetId: String,
    val sourceUrl: String,
)

enum class BroadcastInterferenceRelation(val label: String) {
    COCHANNEL("cochannel"),
    FIRST_ADJACENT("first-adjacent"),
}

data class BrazilLegacyInterferingContourProfile(
    val service: BroadcastService,
    val channel: Int,
    val relation: BroadcastInterferenceRelation,
    val statisticalBasis: String,
    val thresholdDbuvPerM: Double,
    val protectionRatioDb: Double,
    val rulesetId: String,
    val sourceUrl: String,
)

/** Current and historical first-generation FM/digital-TV rules checked on 2026-09-01. */
object BrazilBroadcastRules {
    const val FM_RULESET_ID = "ANATEL-ACT-8104-2022"
    const val DIGITAL_TV_RULESET_ID = "ANATEL-ACT-9751-2022"
    const val FM_SOURCE_URL =
        "https://informacoes.anatel.gov.br/legislacao/atos-de-requisitos-tecnicos-de-gestao-do-espectro/2022/1687-ato-8104"
    const val DIGITAL_TV_SOURCE_URL =
        "https://informacoes.anatel.gov.br/legislacao/atos-de-requisitos-tecnicos-de-gestao-do-espectro/2022/1688-ato-9751"
    const val LEGACY_FM_RULESET_ID = "ANATEL-RESOLUTION-67-1998-REVOKED"
    const val LEGACY_FM_SOURCE_URL =
        "https://informacoes.anatel.gov.br/legislacao/resolucoes/2004/resolucoes/13-1998/168-resolucao-67"
    const val LEGACY_TV_RULESET_ID = "ANATEL-RESOLUTION-398-2005-REVOKED"
    const val LEGACY_TV_SOURCE_URL =
        "https://informacoes.anatel.gov.br/legislacao/resolucoes/resolucoes/20-2005/288-resolucao-398"

    const val FM_PROTECTED_THRESHOLD_DBUV_PER_M = 66.0
    const val DIGITAL_TV_HIGH_VHF_THRESHOLD_DBUV_PER_M = 43.0
    const val DIGITAL_TV_UHF_THRESHOLD_DBUV_PER_M = 51.0
    const val FM_CURRENT_COCHANNEL_DU_DB = 30.0
    const val FM_CURRENT_FIRST_ADJACENT_DU_DB = 6.0
    const val FM_LEGACY_COCHANNEL_DU_DB = 34.0
    const val FM_LEGACY_FIRST_ADJACENT_DU_DB = 6.0
    const val DIGITAL_TV_COCHANNEL_DU_DB = BrazilDigitalTvRegulatoryStudyPlanner.TVD_COCHANNEL_DU_DB
    const val DIGITAL_TV_FIRST_ADJACENT_DU_DB = BrazilDigitalTvRegulatoryStudyPlanner.TVD_ADJACENT_DU_DB

    fun currentProtectionRatioDb(
        service: BroadcastService,
        relation: BroadcastInterferenceRelation,
    ): Double = when (service) {
        BroadcastService.FM -> when (relation) {
            BroadcastInterferenceRelation.COCHANNEL -> FM_CURRENT_COCHANNEL_DU_DB
            BroadcastInterferenceRelation.FIRST_ADJACENT -> FM_CURRENT_FIRST_ADJACENT_DU_DB
        }

        BroadcastService.DIGITAL_TV -> when (relation) {
            BroadcastInterferenceRelation.COCHANNEL -> DIGITAL_TV_COCHANNEL_DU_DB
            BroadcastInterferenceRelation.FIRST_ADJACENT -> DIGITAL_TV_FIRST_ADJACENT_DU_DB
        }
    }

    fun protectedProfile(
        service: BroadcastService,
        frequencyMHz: Double,
    ): BrazilProtectedContourProfile? {
        require(frequencyMHz.isFinite() && frequencyMHz > 0.0) {
            "Broadcast frequency must be positive and finite."
        }
        return when (service) {
            BroadcastService.FM -> fmChannel(frequencyMHz)?.let { channel ->
                BrazilProtectedContourProfile(
                    service = service,
                    channel = channel,
                    statisticalBasis = "E(50,50)",
                    thresholdDbuvPerM = FM_PROTECTED_THRESHOLD_DBUV_PER_M,
                    rulesetId = FM_RULESET_ID,
                    sourceUrl = FM_SOURCE_URL,
                )
            }

            BroadcastService.DIGITAL_TV -> digitalTvProfile(frequencyMHz)
        }
    }

    /**
     * Reconstructs the statistical E(50,10) envelopes used by revoked FM/TV rules. These are
     * deliberately separate from the current Acts 8104/2022 and 9751/2022, whose interfering
     * signal method is point-to-point ITU-R P.526 associated with Assis (1971).
     */
    fun legacyInterferingProfiles(
        service: BroadcastService,
        frequencyMHz: Double,
    ): List<BrazilLegacyInterferingContourProfile> {
        val protected = protectedProfile(service, frequencyMHz) ?: return emptyList()
        val channel = protected.channel ?: return emptyList()
        val inputs = when (service) {
            BroadcastService.FM -> listOf(
                Triple(
                    BroadcastInterferenceRelation.COCHANNEL,
                    FM_LEGACY_COCHANNEL_DU_DB,
                    LEGACY_FM_RULESET_ID,
                ),
                Triple(
                    BroadcastInterferenceRelation.FIRST_ADJACENT,
                    FM_LEGACY_FIRST_ADJACENT_DU_DB,
                    LEGACY_FM_RULESET_ID,
                ),
            )

            BroadcastService.DIGITAL_TV -> listOf(
                Triple(
                    BroadcastInterferenceRelation.COCHANNEL,
                    DIGITAL_TV_COCHANNEL_DU_DB,
                    LEGACY_TV_RULESET_ID,
                ),
                Triple(
                    BroadcastInterferenceRelation.FIRST_ADJACENT,
                    DIGITAL_TV_FIRST_ADJACENT_DU_DB,
                    LEGACY_TV_RULESET_ID,
                ),
            )
        }
        val sourceUrl = when (service) {
            BroadcastService.FM -> LEGACY_FM_SOURCE_URL
            BroadcastService.DIGITAL_TV -> LEGACY_TV_SOURCE_URL
        }
        return inputs.map { (relation, protectionRatioDb, rulesetId) ->
            BrazilLegacyInterferingContourProfile(
                service = service,
                channel = channel,
                relation = relation,
                statisticalBasis =
                    "E(50,10) legacy ${relation.label} interfering envelope",
                thresholdDbuvPerM = protected.thresholdDbuvPerM - protectionRatioDb,
                protectionRatioDb = protectionRatioDb,
                rulesetId = rulesetId,
                sourceUrl = sourceUrl,
            )
        }
    }

    private fun fmChannel(frequencyMHz: Double): Int? {
        val channel = ((frequencyMHz - 87.9) / 0.2 + 200.0).roundToInt()
        val centerFrequencyMHz = 87.9 + (channel - 200) * 0.2
        val supported = channel in 141..197 || channel in 201..300
        return channel.takeIf {
            supported && kotlin.math.abs(centerFrequencyMHz - frequencyMHz) <= 0.01
        }
    }

    private fun digitalTvProfile(frequencyMHz: Double): BrazilProtectedContourProfile? {
        val channelAndThreshold = when {
            frequencyMHz >= 174.0 && frequencyMHz < 216.0 -> {
                val channel = floor((frequencyMHz - 174.0) / 6.0).toInt() + 7
                channel to DIGITAL_TV_HIGH_VHF_THRESHOLD_DBUV_PER_M
            }

            frequencyMHz >= 470.0 && frequencyMHz < 698.0 -> {
                val channel = floor((frequencyMHz - 470.0) / 6.0).toInt() + 14
                channel to DIGITAL_TV_UHF_THRESHOLD_DBUV_PER_M
            }

            else -> return null
        }
        return BrazilProtectedContourProfile(
            service = BroadcastService.DIGITAL_TV,
            channel = channelAndThreshold.first,
            statisticalBasis = "E(50,90) = 2 × E(50,50) − E(50,10)",
            thresholdDbuvPerM = channelAndThreshold.second,
            rulesetId = DIGITAL_TV_RULESET_ID,
            sourceUrl = DIGITAL_TV_SOURCE_URL,
        )
    }
}

/**
 * CPU-only land-path reference planner for the engineering map.
 *
 * It intentionally does not claim a strict regulatory result: the current project schema has no
 * radial height-over-mean-terrain samples. Sector AGL is used as an explicit effective-height
 * proxy. A calculation-ready assigned horizontal cut shapes radial ERP when available; otherwise
 * the nominal ERP fallback is explicit.
 */
object BrazilBroadcastContourPlanner {
    const val RADIAL_STEP_DEGREES = 5
    const val RADIAL_COUNT = 72
    const val MAX_DISTANCE_KM = 1000.0

    private const val P1546_MODEL =
        "ITU-R P.1546-6 land reference tables, 0.01 dB, table 47db8b26cb88"
    private const val HEIGHT_WARNING =
        "Sector AGL is used as an effective-height proxy because radial HNMT terrain samples are unavailable."
    private const val PATTERN_FALLBACK_WARNING =
        "Nominal ERP fallback is applied to every radial because no assigned calculation-ready horizontal antenna cut is available."
    private const val PATTERN_APPLIED_WARNING =
        "Directional ERP uses the assigned canonical horizontal E/Emax cut; the stored sector gain remains the single peak-gain input."
    private const val PATTERN_REJECTED_WARNING =
        "The assigned antenna pattern was rejected because its explicit HRP/VRP availability " +
            "and gain-bound normalized content identity could not be verified; nominal ERP " +
            "fallback is applied to every radial."
    private const val ERP_WARNING =
        "ERP is derived from the stored dBi gain by subtracting the 2.15 dB isotropic-to-dipole reference."
    private const val REGULATORY_WARNING =
        "This planning reference is not a regulatory filing result."
    private const val INTERFERENCE_WARNING =
        "Current interference compliance requires point-to-point ITU-R P.526 plus Assis and D/U evaluation; it is not represented by this curve."

    fun plan(project: PlannerProject?): BroadcastContourPlan {
        if (project == null) return BroadcastContourPlan(emptyList(), 0)
        val networksById = project.networks.associateBy(RfNetwork::id)
        val antennaPatternsById = project.antennaPatterns.associateBy(AntennaPatternRecord::id)
        val overlays = mutableListOf<ServiceContourOverlay>()
        var skipped = 0

        project.sites.sortedBy(RadioSite::id).forEach { site ->
            site.sectors.sortedBy(Sector::id).forEach sectorLoop@ { sector ->
                val network = sector.networkId?.let(networksById::get)
                val service = network?.system?.toBroadcastService()
                if (!sector.active || network == null || !network.active || service == null) {
                    skipped += 1
                    return@sectorLoop
                }
                val assignedPattern = sector.transmitAntennaPatternId?.let(antennaPatternsById::get)
                overlays += buildProtectedOverlay(
                    project.id,
                    site,
                    sector,
                    network,
                    service,
                    assignedPattern,
                )
                overlays += buildLegacyInterferingOverlays(
                    project.id,
                    site,
                    sector,
                    network,
                    service,
                    assignedPattern,
                )
                if (service == BroadcastService.FM) {
                    overlays += unsupportedFmEightyEighty(
                        project.id,
                        site,
                        sector,
                        network,
                        assignedPattern,
                    )
                }
            }
        }
        return BroadcastContourPlan(
            overlays = overlays.sortedBy(ServiceContourOverlay::id),
            skippedSectorCount = skipped,
        )
    }

    private fun buildProtectedOverlay(
        projectId: String,
        site: RadioSite,
        sector: Sector,
        network: RfNetwork,
        service: BroadcastService,
        assignedPattern: AntennaPatternRecord?,
    ): ServiceContourOverlay {
        val profile = BrazilBroadcastRules.protectedProfile(service, sector.frequencyMHz)
            ?: return noDataOverlay(
                projectId = projectId,
                site = site,
                sector = sector,
                network = network,
                assignedPattern = assignedPattern,
                service = service,
                purpose = ContourPurpose.PROTECTED,
                statisticalBasis = if (service == BroadcastService.DIGITAL_TV) {
                    "E(50,90)"
                } else {
                    "E(50,50)"
                },
                rulesetId = if (service == BroadcastService.DIGITAL_TV) {
                    BrazilBroadcastRules.DIGITAL_TV_RULESET_ID
                } else {
                    BrazilBroadcastRules.FM_RULESET_ID
                },
                sourceUrl = if (service == BroadcastService.DIGITAL_TV) {
                    BrazilBroadcastRules.DIGITAL_TV_SOURCE_URL
                } else {
                    BrazilBroadcastRules.FM_SOURCE_URL
                },
                warning = if (service == BroadcastService.DIGITAL_TV) {
                    "The stored frequency does not resolve to a supported first-generation digital-TV channel 7–51."
                } else {
                    "The stored frequency does not resolve to a supported FM channel 141–197 or 201–300."
                },
            )
        return calculatedOverlay(
            projectId = projectId,
            site = site,
            sector = sector,
            network = network,
            assignedPattern = assignedPattern,
            service = service,
            purpose = ContourPurpose.PROTECTED,
            profile = profile,
            fieldAtDistance = { distanceKm, erpKw, heightM ->
                when (service) {
                    BroadcastService.FM -> P1546LandReference.fieldStrengthDbuvPerM(
                        frequencyMHz = sector.frequencyMHz,
                        timePercent = 50,
                        effectiveHeightM = heightM,
                        distanceKm = distanceKm,
                        erpKw = erpKw,
                    )

                    BroadcastService.DIGITAL_TV -> {
                        val e50 = P1546LandReference.fieldStrengthDbuvPerM(
                            frequencyMHz = sector.frequencyMHz,
                            timePercent = 50,
                            effectiveHeightM = heightM,
                            distanceKm = distanceKm,
                            erpKw = erpKw,
                        )
                        val e10 = P1546LandReference.fieldStrengthDbuvPerM(
                            frequencyMHz = sector.frequencyMHz,
                            timePercent = 10,
                            effectiveHeightM = heightM,
                            distanceKm = distanceKm,
                            erpKw = erpKw,
                        )
                        2.0 * e50 - e10
                    }
                }
            },
            additionalWarnings = buildList {
                if (service == BroadcastService.DIGITAL_TV) {
                    add(
                        "TV_BROADCAST is interpreted as first-generation digital TV from its channel-band frequency because the project schema has no generation field.",
                    )
                }
                add(INTERFERENCE_WARNING)
            },
        )
    }

    private fun buildLegacyInterferingOverlays(
        projectId: String,
        site: RadioSite,
        sector: Sector,
        network: RfNetwork,
        service: BroadcastService,
        assignedPattern: AntennaPatternRecord?,
    ): List<ServiceContourOverlay> = BrazilBroadcastRules
        .legacyInterferingProfiles(service, sector.frequencyMHz)
        .map { legacy ->
            val profile = BrazilProtectedContourProfile(
                service = legacy.service,
                channel = legacy.channel,
                statisticalBasis = legacy.statisticalBasis,
                thresholdDbuvPerM = legacy.thresholdDbuvPerM,
                rulesetId = legacy.rulesetId,
                sourceUrl = legacy.sourceUrl,
            )
            calculatedOverlay(
                projectId = projectId,
                site = site,
                sector = sector,
                network = network,
                assignedPattern = assignedPattern,
                service = service,
                purpose = ContourPurpose.INTERFERING,
                profile = profile,
                fieldAtDistance = { distanceKm, erpKw, heightM ->
                    P1546LandReference.fieldStrengthDbuvPerM(
                        frequencyMHz = sector.frequencyMHz,
                        timePercent = 10,
                        effectiveHeightM = heightM,
                        distanceKm = distanceKm,
                        erpKw = erpKw,
                    )
                },
                additionalWarnings = listOf(
                    "This E(50,10) envelope reconstructs a revoked planning method and is not a result under the current Anatel rules.",
                    "Its ${formatRuleValue(legacy.thresholdDbuvPerM)} dBµV/m threshold equals the protected-field threshold minus the historical ${formatRuleValue(legacy.protectionRatioDb)} dB ${legacy.relation.label} D/U ratio.",
                    INTERFERENCE_WARNING,
                ),
            )
        }

    private fun unsupportedFmEightyEighty(
        projectId: String,
        site: RadioSite,
        sector: Sector,
        network: RfNetwork,
        assignedPattern: AntennaPatternRecord?,
    ): ServiceContourOverlay = noDataOverlay(
        projectId = projectId,
        site = site,
        sector = sector,
        network = network,
        assignedPattern = assignedPattern,
        service = BroadcastService.FM,
        purpose = ContourPurpose.SCREENING,
        statisticalBasis = "E(80,80) requested profile — unsupported",
        rulesetId = "UNSUPPORTED-E80-80",
        sourceUrl = BrazilBroadcastRules.FM_SOURCE_URL,
        warning =
            "No current Anatel FM rule defines E(80,80), and P.1546 does not permit a direct 80% time prediction; the overlay is NoData.",
        suffix = "e80-80-nodata",
    )

    private fun formatRuleValue(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun calculatedOverlay(
        projectId: String,
        site: RadioSite,
        sector: Sector,
        network: RfNetwork,
        assignedPattern: AntennaPatternRecord?,
        service: BroadcastService,
        purpose: ContourPurpose,
        profile: BrazilProtectedContourProfile,
        fieldAtDistance: (distanceKm: Double, erpKw: Double, heightM: Double) -> Double,
        additionalWarnings: List<String>,
    ): ServiceContourOverlay {
        val peakErpKw = nominalErpKw(sector)
        val heightM = sector.antennaHeightM
        val baseWarnings = buildWarnings(sector, network, assignedPattern) + additionalWarnings
        val calculationPattern = assignedPattern?.takeIf { pattern ->
            pattern.hasVerifiedNormalizedContentIdentity()
        }
        if (!peakErpKw.isFinite() || peakErpKw <= 0.0) {
            return noDataOverlay(
                projectId = projectId,
                site = site,
                sector = sector,
                network = network,
                assignedPattern = assignedPattern,
                service = service,
                purpose = purpose,
                statisticalBasis = profile.statisticalBasis,
                rulesetId = profile.rulesetId,
                sourceUrl = profile.sourceUrl,
                warning = "The stored transmit chain does not produce a positive finite ERP.",
                thresholdDbuvPerM = profile.thresholdDbuvPerM,
            )
        }
        if (heightM < P1546LandReference.MIN_EFFECTIVE_HEIGHT_M ||
            heightM > P1546LandReference.MAX_EFFECTIVE_HEIGHT_M
        ) {
            return noDataOverlay(
                projectId = projectId,
                site = site,
                sector = sector,
                network = network,
                assignedPattern = assignedPattern,
                service = service,
                purpose = purpose,
                statisticalBasis = profile.statisticalBasis,
                rulesetId = profile.rulesetId,
                sourceUrl = profile.sourceUrl,
                warning = "The AGL height is outside the packaged 10–3000 m P.1546 reference range.",
                thresholdDbuvPerM = profile.thresholdDbuvPerM,
            )
        }

        val radials = List(RADIAL_COUNT) { index ->
            val trueBearingDegrees = (index * RADIAL_STEP_DEGREES).toDouble()
            val relativeAzimuthDegrees = wrap360(trueBearingDegrees - sector.azimuthDegrees)
            val horizontalField = calculationPattern?.horizontalCut?.let { cut ->
                val samplePosition = (relativeAzimuthDegrees - cut.startAngleDegrees) / cut.stepDegrees
                val lowerUnwrapped = floor(samplePosition).toInt()
                val lowerIndex = Math.floorMod(lowerUnwrapped, cut.normalizedField.size)
                val upperIndex = (lowerIndex + 1) % cut.normalizedField.size
                val fraction = samplePosition - floor(samplePosition)
                cut.normalizedField[lowerIndex] * (1.0 - fraction) +
                    cut.normalizedField[upperIndex] * fraction
            } ?: 1.0

            when {
                !horizontalField.isFinite() -> noDataRadial(
                    azimuthDegrees = trueBearingDegrees,
                    heightM = heightM,
                    warning =
                        "The assigned horizontal pattern returned non-finite field data for this radial.",
                )

                horizontalField <= 0.0 -> noDataRadial(
                    azimuthDegrees = trueBearingDegrees,
                    heightM = heightM,
                    warning =
                        "The assigned horizontal pattern has zero field for this radial; its distance is NoData.",
                )

                else -> {
                    // The stored sector gain already supplies peak gain. E/Emax therefore shapes
                    // directional power exactly once through the field-squared ratio.
                    val radialErpKw = peakErpKw * horizontalField.pow(2.0)
                    if (!radialErpKw.isFinite() || radialErpKw <= 0.0) {
                        noDataRadial(
                            azimuthDegrees = trueBearingDegrees,
                            heightM = heightM,
                            warning =
                                "The directional ERP is zero or non-finite for this radial; its distance is NoData.",
                        )
                    } else {
                        val crossing = findOutermostCrossing(
                            thresholdDbuvPerM = profile.thresholdDbuvPerM,
                            fieldAtDistance = { distanceKm ->
                                fieldAtDistance(distanceKm, radialErpKw, heightM)
                            },
                        )
                        ContourRadial(
                            azimuthDegrees = trueBearingDegrees,
                            distanceKm = crossing.distanceKm,
                            erpKw = radialErpKw,
                            effectiveHeightM = heightM,
                            status = when {
                                crossing.distanceKm == null -> ContourRadialStatus.NO_DATA
                                crossing.complete -> ContourRadialStatus.COMPLETE
                                else -> ContourRadialStatus.MODEL_BOUNDARY
                            },
                            warnings = listOfNotNull(crossing.warning),
                        )
                    }
                }
            }
        }
        val drawableRadials = longestContiguousDrawableRun(radials)
        val openGeometry = drawableRadials.map { radial ->
            BroadcastContourGeodesy.destination(
                origin = site.location,
                bearingDegrees = radial.azimuthDegrees,
                distanceKm = checkNotNull(radial.distanceKm),
            )
        }
        val allRadialsComplete = radials.all { radial ->
            radial.status == ContourRadialStatus.COMPLETE
        }
        val contourStatus = when {
            allRadialsComplete -> ContourStatus.COMPLETE
            openGeometry.size >= 2 -> ContourStatus.INCOMPLETE
            else -> ContourStatus.NO_DATA
        }
        val geometry = when (contourStatus) {
            ContourStatus.COMPLETE -> openGeometry + openGeometry.first()
            ContourStatus.INCOMPLETE -> openGeometry
            ContourStatus.NO_DATA -> emptyList()
        }
        val noDataRadialCount = radials.count { radial ->
            radial.status == ContourRadialStatus.NO_DATA
        }
        val warnings = buildList {
            addAll(baseWarnings)
            addAll(radials.flatMap(ContourRadial::warnings))
            if (noDataRadialCount > 0) {
                add("$noDataRadialCount of $RADIAL_COUNT radials are NoData; the geometry is incomplete.")
            }
            if (drawableRadials.size < radials.count { radial -> radial.distanceKm != null }) {
                add(
                    "Only the longest contiguous run of valid radials is rendered because this overlay cannot encode multiple NoData gaps.",
                )
            }
        }.distinct()
        val fingerprint = fingerprint(
            "broadcast-contour-v2-directional-erp",
            P1546_MODEL,
            P1546LandReference.PACKAGED_TABLE_SHA256,
            projectId,
            site.id,
            site.location.latitude.toString(),
            site.location.longitude.toString(),
            sector.id,
            service.name,
            purpose.name,
            profile.statisticalBasis,
            profile.thresholdDbuvPerM.toString(),
            profile.rulesetId,
            profile.sourceUrl,
            sector.frequencyMHz.toString(),
            peakErpKw.toString(),
            heightM.toString(),
            sector.azimuthDegrees.toString(),
            sector.transmitPowerDbm.toString(),
            sector.antennaGainDbi.toString(),
            sector.feederLossDb.toString(),
            RADIAL_STEP_DEGREES.toString(),
            MAX_DISTANCE_KM.toString(),
            *patternFingerprintValues(sector, assignedPattern).toTypedArray(),
        )
        return ServiceContourOverlay(
            id = "${site.id}:${sector.id}:${purpose.name.lowercase(Locale.ROOT)}:${profile.statisticalBasis.stableSlug()}",
            siteId = site.id,
            sectorId = sector.id,
            service = service,
            purpose = purpose,
            statisticalBasis = profile.statisticalBasis,
            thresholdDbuvPerM = profile.thresholdDbuvPerM,
            points = geometry,
            status = contourStatus,
            model = P1546_MODEL,
            rulesetId = profile.rulesetId,
            warnings = warnings,
            sourceUrl = profile.sourceUrl,
            regulatory = false,
            radials = radials,
            inputFingerprint = fingerprint,
        )
    }

    private fun noDataOverlay(
        projectId: String,
        site: RadioSite,
        sector: Sector,
        network: RfNetwork,
        assignedPattern: AntennaPatternRecord?,
        service: BroadcastService,
        purpose: ContourPurpose,
        statisticalBasis: String,
        rulesetId: String,
        sourceUrl: String,
        warning: String,
        suffix: String = "nodata",
        thresholdDbuvPerM: Double? = null,
    ): ServiceContourOverlay = ServiceContourOverlay(
        id = "${site.id}:${sector.id}:${purpose.name.lowercase(Locale.ROOT)}:$suffix:${statisticalBasis.stableSlug()}",
        siteId = site.id,
        sectorId = sector.id,
        service = service,
        purpose = purpose,
        statisticalBasis = statisticalBasis,
        thresholdDbuvPerM = thresholdDbuvPerM,
        points = emptyList(),
        status = ContourStatus.NO_DATA,
        model = P1546_MODEL,
        rulesetId = rulesetId,
        warnings = (buildWarnings(sector, network, assignedPattern, describeDirectionalErp = false) + warning)
            .distinct(),
        sourceUrl = sourceUrl,
        regulatory = false,
        inputFingerprint = fingerprint(
            "broadcast-contour-v2-directional-erp",
            P1546_MODEL,
            P1546LandReference.PACKAGED_TABLE_SHA256,
            projectId,
            site.id,
            site.location.latitude.toString(),
            site.location.longitude.toString(),
            sector.id,
            service.name,
            purpose.name,
            statisticalBasis,
            thresholdDbuvPerM.toString(),
            rulesetId,
            sourceUrl,
            sector.frequencyMHz.toString(),
            sector.antennaHeightM.toString(),
            sector.transmitPowerDbm.toString(),
            sector.antennaGainDbi.toString(),
            sector.feederLossDb.toString(),
            warning,
            *patternFingerprintValues(sector, assignedPattern).toTypedArray(),
        ),
    )

    private fun buildWarnings(
        sector: Sector,
        network: RfNetwork,
        assignedPattern: AntennaPatternRecord?,
        describeDirectionalErp: Boolean = true,
    ): List<String> = buildList {
        add(HEIGHT_WARNING)
        if (describeDirectionalErp) {
            add(
                when {
                    assignedPattern == null -> PATTERN_FALLBACK_WARNING
                    !assignedPattern.hasVerifiedNormalizedContentIdentity() ->
                        PATTERN_REJECTED_WARNING
                    else -> PATTERN_APPLIED_WARNING
                },
            )
        }
        add(ERP_WARNING)
        add(REGULATORY_WARNING)
        if (kotlin.math.abs(sector.frequencyMHz - network.downlinkFrequencyMHz) > 0.01) {
            add("The sector frequency is used because it differs from the linked network frequency.")
        }
    }

    private fun noDataRadial(
        azimuthDegrees: Double,
        heightM: Double,
        warning: String,
    ): ContourRadial = ContourRadial(
        azimuthDegrees = azimuthDegrees,
        distanceKm = null,
        erpKw = 0.0,
        effectiveHeightM = heightM,
        status = ContourRadialStatus.NO_DATA,
        warnings = listOf(warning),
    )

    private fun longestContiguousDrawableRun(radials: List<ContourRadial>): List<ContourRadial> {
        if (radials.isEmpty()) return emptyList()
        if (radials.all { radial -> radial.distanceKm != null }) return radials

        var bestStart = -1
        var bestLength = 0
        radials.indices.forEach { start ->
            val previous = (start - 1 + radials.size) % radials.size
            if (radials[start].distanceKm == null || radials[previous].distanceKm != null) {
                return@forEach
            }
            var length = 0
            while (length < radials.size && radials[(start + length) % radials.size].distanceKm != null) {
                length += 1
            }
            if (length > bestLength) {
                bestStart = start
                bestLength = length
            }
        }
        return if (bestStart < 0) {
            emptyList()
        } else {
            List(bestLength) { offset -> radials[(bestStart + offset) % radials.size] }
        }
    }

    private fun patternFingerprintValues(
        sector: Sector,
        assignedPattern: AntennaPatternRecord?,
    ): List<String> {
        val cut = assignedPattern?.horizontalCut
        val calculatedCutHash = cut?.let { horizontalCut ->
            fingerprint(
                "canonical-horizontal-cut-v1",
                horizontalCut.startAngleDegrees.toString(),
                horizontalCut.stepDegrees.toString(),
                horizontalCut.availability.name,
                horizontalCut.normalizedField.joinToString(separator = ",", transform = Double::toString),
            )
        }
        return listOf(
            "assignedPatternId=${sector.transmitAntennaPatternId.orEmpty()}",
            "resolvedPatternId=${assignedPattern?.id.orEmpty()}",
            "canonicalDataVersion=${assignedPattern?.canonicalDataVersion?.toString().orEmpty()}",
            "origin=${assignedPattern?.origin?.name.orEmpty()}",
            "coordinateConvention=${assignedPattern?.coordinateConvention?.name.orEmpty()}",
            "normalizedContentSha256=${assignedPattern?.normalizedContentSha256.orEmpty()}",
            "sourceSha256=${assignedPattern?.sourceSha256.orEmpty()}",
            "dataArtifactId=${assignedPattern?.dataArtifactId.orEmpty()}",
            "calculatedHorizontalCutSha256=${calculatedCutHash.orEmpty()}",
        )
    }

    private fun wrap360(angleDegrees: Double): Double {
        val wrapped = angleDegrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    private fun findOutermostCrossing(
        thresholdDbuvPerM: Double,
        fieldAtDistance: (Double) -> Double,
    ): ThresholdCrossing {
        val distances = P1546LandReference.nominalDistancesKm
        val fields = distances.map(fieldAtDistance)
        if (fields.any { !it.isFinite() }) {
            return ThresholdCrossing(null, false, "The propagation model returned non-finite field data.")
        }
        var lastBracket: Pair<Double, Double>? = null
        for (index in 0 until distances.lastIndex) {
            val nearDelta = fields[index] - thresholdDbuvPerM
            val farDelta = fields[index + 1] - thresholdDbuvPerM
            if (nearDelta >= 0.0 && farDelta < 0.0) {
                lastBracket = distances[index] to distances[index + 1]
            }
        }
        if (lastBracket != null) {
            var lower = checkNotNull(lastBracket).first
            var upper = checkNotNull(lastBracket).second
            repeat(48) {
                val middle = 10.0.pow((log10(lower) + log10(upper)) / 2.0)
                if (fieldAtDistance(middle) >= thresholdDbuvPerM) lower = middle else upper = middle
            }
            return ThresholdCrossing(
                distanceKm = (lower + upper) / 2.0,
                complete = true,
                warning = null,
            )
        }
        if (fields.last() >= thresholdDbuvPerM) {
            return ThresholdCrossing(
                distanceKm = MAX_DISTANCE_KM,
                complete = false,
                warning =
                    "The threshold remains exceeded at the 1000 km model boundary; the contour is incomplete.",
            )
        }
        return ThresholdCrossing(
            distanceKm = null,
            complete = false,
            warning =
                "The field is below the requested threshold at the 1 km packaged-model boundary.",
        )
    }

    private fun nominalErpKw(sector: Sector): Double {
        val erpDbm = sector.transmitPowerDbm + sector.antennaGainDbi - sector.feederLossDb - 2.15
        return 10.0.pow((erpDbm - 60.0) / 10.0)
    }

    private fun RadioSystem.toBroadcastService(): BroadcastService? = when (this) {
        RadioSystem.FM_BROADCAST -> BroadcastService.FM
        RadioSystem.TV_BROADCAST -> BroadcastService.DIGITAL_TV
        else -> null
    }

    private fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun String.stableSlug(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(48)

    private data class ThresholdCrossing(
        val distanceKm: Double?,
        val complete: Boolean,
        val warning: String?,
    )
}

object BroadcastContourGeodesy {
    private const val MEAN_EARTH_RADIUS_KM = 6371.0088

    fun destination(
        origin: GeoPoint,
        bearingDegrees: Double,
        distanceKm: Double,
    ): GeoPoint {
        require(bearingDegrees.isFinite()) { "Bearing must be finite." }
        require(distanceKm.isFinite() && distanceKm >= 0.0) {
            "Geodesic distance must be finite and non-negative."
        }
        val bearing = Math.toRadians(((bearingDegrees % 360.0) + 360.0) % 360.0)
        val angularDistance = distanceKm / MEAN_EARTH_RADIUS_KM
        val latitude1 = Math.toRadians(origin.latitude)
        val longitude1 = Math.toRadians(origin.longitude)
        val latitude2 = asin(
            sin(latitude1) * cos(angularDistance) +
                cos(latitude1) * sin(angularDistance) * cos(bearing),
        )
        val longitude2 = longitude1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude1),
            cos(angularDistance) - sin(latitude1) * sin(latitude2),
        )
        val longitudeDegrees = ((Math.toDegrees(longitude2) + 540.0) % 360.0) - 180.0
        return GeoPoint(
            latitude = Math.toDegrees(latitude2).coerceIn(-90.0, 90.0),
            longitude = longitudeDegrees,
        )
    }
}
