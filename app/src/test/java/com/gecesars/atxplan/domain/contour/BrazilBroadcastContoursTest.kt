package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.domain.antenna.AntennaPatternCut
import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import com.gecesars.atxplan.domain.antenna.PatternSample
import com.gecesars.atxplan.domain.application.ProjectAntennaPatternIdentity
import com.gecesars.atxplan.domain.application.toProjectRecord
import com.gecesars.atxplan.domain.model.AntennaPatternOrigin
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class BrazilBroadcastContoursTest {
    @Test
    fun `official profiles expose thresholds statistics sources and supported channel bands`() {
        val fm = requireNotNull(
            BrazilBroadcastRules.protectedProfile(BroadcastService.FM, 100.1),
        )

        assertEquals(BroadcastService.FM, fm.service)
        assertEquals(261, fm.channel)
        assertEquals("E(50,50)", fm.statisticalBasis)
        assertEquals(66.0, fm.thresholdDbuvPerM, STRICT_TOLERANCE)
        assertEquals(BrazilBroadcastRules.FM_RULESET_ID, fm.rulesetId)
        assertEquals(BrazilBroadcastRules.FM_SOURCE_URL, fm.sourceUrl)

        listOf(
            76.1 to 141,
            87.3 to 197,
            88.1 to 201,
            100.1 to 261,
            107.9 to 300,
        ).forEach { (frequencyMHz, channel) ->
            val profile = requireNotNull(
                BrazilBroadcastRules.protectedProfile(BroadcastService.FM, frequencyMHz),
            )

            assertEquals(channel, profile.channel)
            assertEquals(66.0, profile.thresholdDbuvPerM, STRICT_TOLERANCE)
            assertEquals("E(50,50)", profile.statisticalBasis)
        }
        listOf(76.0, 87.5, 87.7, 87.9, 100.0, 108.0).forEach { frequencyMHz ->
            assertNull(
                BrazilBroadcastRules.protectedProfile(BroadcastService.FM, frequencyMHz),
            )
        }

        listOf(
            Triple(174.0, 7, 43.0),
            Triple(179.999, 7, 43.0),
            Triple(180.0, 8, 43.0),
            Triple(215.999, 13, 43.0),
            Triple(470.0, 14, 51.0),
            Triple(475.999, 14, 51.0),
            Triple(476.0, 15, 51.0),
            Triple(697.999, 51, 51.0),
        ).forEach { (frequencyMHz, channel, thresholdDbuvPerM) ->
            val profile = requireNotNull(
                BrazilBroadcastRules.protectedProfile(
                    service = BroadcastService.DIGITAL_TV,
                    frequencyMHz = frequencyMHz,
                ),
            )

            assertEquals(BroadcastService.DIGITAL_TV, profile.service)
            assertEquals(channel, profile.channel)
            assertTrue(profile.statisticalBasis.startsWith("E(50,90) = 2"))
            assertTrue(profile.statisticalBasis.contains("E(50,50)"))
            assertTrue(profile.statisticalBasis.endsWith("E(50,10)"))
            assertEquals(thresholdDbuvPerM, profile.thresholdDbuvPerM, STRICT_TOLERANCE)
            assertEquals(BrazilBroadcastRules.DIGITAL_TV_RULESET_ID, profile.rulesetId)
            assertEquals(BrazilBroadcastRules.DIGITAL_TV_SOURCE_URL, profile.sourceUrl)
        }

        assertNull(BrazilBroadcastRules.protectedProfile(BroadcastService.DIGITAL_TV, 216.0))
        assertNull(BrazilBroadcastRules.protectedProfile(BroadcastService.DIGITAL_TV, 469.999))
        assertNull(BrazilBroadcastRules.protectedProfile(BroadcastService.DIGITAL_TV, 698.0))
    }

    @Test
    fun `digital TV low VHF fails closed in rules and planner`() {
        assertNull(BrazilBroadcastRules.protectedProfile(BroadcastService.DIGITAL_TV, 85.0))

        val project = project(
            networks = listOf(network(id = "tv", system = RadioSystem.TV_BROADCAST, frequencyMHz = 85.0)),
            sites = listOf(
                site(
                    sectors = listOf(sector(id = "low-vhf", networkId = "tv", frequencyMHz = 85.0)),
                ),
            ),
        )

        val plan = BrazilBroadcastContourPlanner.plan(project)
        val overlay = plan.overlays.single()

        assertEquals(0, plan.skippedSectorCount)
        assertEquals(BroadcastService.DIGITAL_TV, overlay.service)
        assertEquals(ContourPurpose.PROTECTED, overlay.purpose)
        assertEquals(ContourStatus.NO_DATA, overlay.status)
        assertNull(overlay.thresholdDbuvPerM)
        assertTrue(overlay.points.isEmpty())
        assertTrue(overlay.radials.isEmpty())
        assertFalse(overlay.regulatory)
        assertTrue(overlay.warnings.any { warning -> warning.contains("channel 7") })
    }

    @Test
    fun `P1546 nominal land vectors match the reference values`() {
        assertEquals(
            "7ecf708a2d693fbde7a5651184820dbd35f0e7cffa6bbae53d64ef7234128925",
            P1546LandReference.UPSTREAM_SOURCE_SHA256,
        )
        assertEquals(
            "47db8b26cb88efab38d872622a8a08450728dce2b335b365b170b247a999992b",
            P1546LandReference.PACKAGED_TABLE_SHA256,
        )
        val e10 = P1546LandReference.fieldStrengthDbuvPerM(
            frequencyMHz = 100.0,
            timePercent = 10,
            effectiveHeightM = 150.0,
            distanceKm = 50.0,
            erpKw = 1.0,
        )
        val e50 = P1546LandReference.fieldStrengthDbuvPerM(
            frequencyMHz = 100.0,
            timePercent = 50,
            effectiveHeightM = 150.0,
            distanceKm = 50.0,
            erpKw = 1.0,
        )

        assertEquals(44.002, e10, TABLE_QUANTIZATION_TOLERANCE)
        assertEquals(42.685_292, e50, TABLE_QUANTIZATION_TOLERANCE)
        assertTrue(e10 > e50)
    }

    @Test
    fun `class distance vectors remain close to the Anatel table values`() {
        val fmDistanceKm = protectedDistanceKm(
            service = RadioSystem.FM_BROADCAST,
            frequencyMHz = 100.1,
            erpKw = 100.0,
            effectiveHeightM = 600.0,
        )
        val highVhfDistanceKm = protectedDistanceKm(
            service = RadioSystem.TV_BROADCAST,
            frequencyMHz = 200.0,
            erpKw = 16.0,
            effectiveHeightM = 150.0,
        )
        val uhfDistanceKm = protectedDistanceKm(
            service = RadioSystem.TV_BROADCAST,
            frequencyMHz = 600.0,
            erpKw = 80.0,
            effectiveHeightM = 150.0,
        )

        assertEquals(78.5, fmDistanceKm, CLASS_DISTANCE_TOLERANCE_KM)
        assertEquals(65.6, highVhfDistanceKm, CLASS_DISTANCE_TOLERANCE_KM)
        assertEquals(58.0, uhfDistanceKm, CLASS_DISTANCE_TOLERANCE_KM)
    }

    @Test
    fun `digital TV planner derives E5090 from E5050 and E5010`() {
        val project = project(
            networks = listOf(network(id = "tv", system = RadioSystem.TV_BROADCAST, frequencyMHz = 177.0)),
            sites = listOf(
                site(sectors = listOf(sector(id = "tv-7", networkId = "tv", frequencyMHz = 177.0))),
            ),
        )

        val overlay = BrazilBroadcastContourPlanner.plan(project).overlays.single()
        val radial = overlay.radials.single { item -> item.azimuthDegrees == 0.0 }
        val distanceKm = requireNotNull(radial.distanceKm)
        val e50 = P1546LandReference.fieldStrengthDbuvPerM(
            frequencyMHz = 177.0,
            timePercent = 50,
            effectiveHeightM = radial.effectiveHeightM,
            distanceKm = distanceKm,
            erpKw = radial.erpKw,
        )
        val e10 = P1546LandReference.fieldStrengthDbuvPerM(
            frequencyMHz = 177.0,
            timePercent = 10,
            effectiveHeightM = radial.effectiveHeightM,
            distanceKm = distanceKm,
            erpKw = radial.erpKw,
        )
        val derivedE5090 = 2.0 * e50 - e10

        assertEquals(ContourStatus.COMPLETE, overlay.status)
        assertEquals(43.0, requireNotNull(overlay.thresholdDbuvPerM), STRICT_TOLERANCE)
        assertEquals(43.0, derivedE5090, CONTOUR_CROSSING_TOLERANCE)
        assertTrue(e50 > derivedE5090)
        assertTrue(overlay.statisticalBasis.startsWith("E(50,90) = 2"))
    }

    @Test
    fun `FM planner exposes protected screening and unsupported NoData overlays`() {
        val plan = BrazilBroadcastContourPlanner.plan(fmProject())

        assertEquals(0, plan.skippedSectorCount)
        assertEquals(3, plan.overlays.size)

        val protected = plan.overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }
        val e5010 = plan.overlays.single { overlay ->
            overlay.statisticalBasis.contains("E(50,10)")
        }
        val e8080 = plan.overlays.single { overlay ->
            overlay.statisticalBasis.contains("E(80,80)")
        }

        assertEquals("E(50,50)", protected.statisticalBasis)
        assertEquals(66.0, requireNotNull(protected.thresholdDbuvPerM), STRICT_TOLERANCE)
        assertEquals(ContourStatus.COMPLETE, protected.status)
        assertEquals(BrazilBroadcastRules.FM_RULESET_ID, protected.rulesetId)

        assertEquals(ContourPurpose.SCREENING, e5010.purpose)
        assertEquals(ContourStatus.COMPLETE, e5010.status)
        assertEquals(66.0, requireNotNull(e5010.thresholdDbuvPerM), STRICT_TOLERANCE)
        assertEquals("CUSTOM-SCREENING-E50-10", e5010.rulesetId)
        assertTrue(e5010.warnings.any { warning -> warning.contains("not the current Anatel interference method") })

        assertEquals(ContourPurpose.SCREENING, e8080.purpose)
        assertEquals(ContourStatus.NO_DATA, e8080.status)
        assertEquals("UNSUPPORTED-E80-80", e8080.rulesetId)
        assertNull(e8080.thresholdDbuvPerM)
        assertTrue(e8080.points.isEmpty())
        assertTrue(e8080.radials.isEmpty())
        assertFalse(e8080.regulatory)
        assertTrue(e8080.warnings.any { warning -> warning.contains("P.1546") && warning.contains("80% time") })
    }

    @Test
    fun `calculated contour carries radial geometry ERP conversion and non regulatory evidence`() {
        val protected = BrazilBroadcastContourPlanner.plan(fmProject()).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }

        assertEquals(ContourStatus.COMPLETE, protected.status)
        assertEquals(BrazilBroadcastContourPlanner.RADIAL_COUNT, protected.radials.size)
        assertEquals(72, protected.radials.size)
        assertEquals(73, protected.points.size)
        assertEquals(protected.points.first(), protected.points.last())
        assertEquals(
            (0 until 360 step BrazilBroadcastContourPlanner.RADIAL_STEP_DEGREES).map(Int::toDouble),
            protected.radials.map(ContourRadial::azimuthDegrees),
        )
        assertTrue(protected.radials.all { radial -> radial.status == ContourRadialStatus.COMPLETE })
        assertTrue(protected.radials.all { radial ->
            kotlin.math.abs(radial.erpKw - 1.0) <= STRICT_TOLERANCE
        })
        assertFalse(protected.regulatory)
        assertTrue(protected.inputFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(protected.model.contains("P.1546-6"))
        assertTrue(protected.warnings.contains(ERP_CONVERSION_WARNING))
        assertTrue(protected.warnings.contains(NON_REGULATORY_WARNING))
    }

    @Test
    fun `assigned pattern boresight follows the sector true bearing`() {
        val pattern = horizontalPattern(
            normalizedField = List(360) { angle -> if (angle == 0) 1.0 else 0.25 },
        )
        fun peakBearing(azimuthDegrees: Double): Double {
            val project = project(
                networks = listOf(network()),
                sites = listOf(
                    site(
                        sectors = listOf(
                            sector(
                                azimuthDegrees = azimuthDegrees,
                                transmitAntennaPatternId = pattern.id,
                            ),
                        ),
                    ),
                ),
                antennaPatterns = listOf(pattern),
            )
            val protected = BrazilBroadcastContourPlanner.plan(project).overlays.single { overlay ->
                overlay.purpose == ContourPurpose.PROTECTED
            }
            return protected.radials.maxBy(ContourRadial::erpKw).azimuthDegrees
        }

        assertEquals(0.0, peakBearing(0.0), STRICT_TOLERANCE)
        assertEquals(90.0, peakBearing(90.0), STRICT_TOLERANCE)
    }

    @Test
    fun `rear field attenuation shapes ERP once and reduces distance`() {
        val pattern = horizontalPattern(
            normalizedField = List(360) { angle ->
                when (angle) {
                    0 -> 1.0
                    180 -> 0.1
                    else -> 0.5
                }
            },
        )
        val protected = protectedOverlay(pattern = pattern)
        val front = protected.radials.single { radial -> radial.azimuthDegrees == 0.0 }
        val rear = protected.radials.single { radial -> radial.azimuthDegrees == 180.0 }

        assertEquals(1.0, front.erpKw, ERP_TOLERANCE)
        assertEquals(0.01, rear.erpKw, ERP_TOLERANCE)
        assertTrue(requireNotNull(front.distanceKm) > requireNotNull(rear.distanceKm))
    }

    @Test
    fun `directional pattern produces noncircular radial distances`() {
        val pattern = horizontalPattern(
            normalizedField = List(360) { angle ->
                0.2 + 0.8 * (1.0 + kotlin.math.cos(Math.toRadians(angle.toDouble()))) / 2.0
            },
        )
        val protected = protectedOverlay(pattern = pattern)
        val roundedDistances = protected.radials.mapNotNull(ContourRadial::distanceKm)
            .map { distanceKm -> (distanceKm * 1_000.0).roundToInt() }
            .toSet()

        assertEquals(ContourStatus.COMPLETE, protected.status)
        assertTrue(roundedDistances.size > 10)
        assertTrue(protected.points.first() == protected.points.last())
    }

    @Test
    fun `pattern peak gain is not added a second time`() {
        val pattern = horizontalPattern(
            peakGainDbi = 32.15,
            normalizedField = List(360) { 1.0 },
        )
        val protected = protectedOverlay(pattern = pattern)

        assertTrue(protected.radials.all { radial ->
            kotlin.math.abs(radial.erpKw - 1.0) <= ERP_TOLERANCE
        })
        assertTrue(protected.warnings.any { warning -> warning.contains("single peak-gain input") })
    }

    @Test
    fun `missing calculation-ready pattern uses explicit nominal ERP fallback`() {
        val protected = BrazilBroadcastContourPlanner.plan(fmProject()).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }

        assertTrue(protected.radials.all { radial ->
            kotlin.math.abs(radial.erpKw - 1.0) <= ERP_TOLERANCE
        })
        assertTrue(protected.warnings.contains(PATTERN_FALLBACK_WARNING))
        assertFalse(protected.regulatory)
    }

    @Test
    fun `gain-unbound V1 and tampered identities use visible nominal fallback without changing geometry`() {
        val baseline = BrazilBroadcastContourPlanner.plan(fmProject()).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }
        val gainUnboundV1 = horizontalPattern(
            peakGainDbi = 6.5,
            nominalFrequencyHz = 99_500_000.0,
            normalizedField = List(360) { 1.0 },
        ).copy(normalizedContentSha256 = VERSION_ONE_GAIN_UNBOUND_SHA256)
        val validDirectional = horizontalPattern(
            normalizedField = List(360) { angle -> if (angle == 180) 0.1 else 1.0 },
        )
        val validCut = requireNotNull(validDirectional.horizontalCut)
        val tampered = validDirectional.copy(
            horizontalCut = validCut.copy(
                normalizedField = validCut.normalizedField.mapIndexed { index, field ->
                    if (index == 180) 0.2 else field
                },
            ),
        )

        listOf(gainUnboundV1, tampered).forEach { rejected ->
            val overlay = protectedOverlay(rejected)

            assertEquals(baseline.points, overlay.points)
            assertEquals(baseline.radials, overlay.radials)
            assertTrue(overlay.radials.all { radial ->
                kotlin.math.abs(radial.erpKw - 1.0) <= ERP_TOLERANCE
            })
            assertTrue(
                overlay.warnings.any { warning ->
                    warning.contains("gain-bound normalized content identity") &&
                        warning.contains("fallback")
                },
            )
            assertFalse(overlay.warnings.any { warning -> warning.contains("Directional ERP uses") })
        }
    }

    @Test
    fun `fractional boresight interpolates the periodic field grid`() {
        val samples = MutableList(360) { 0.5 }
        samples[0] = 1.0
        samples[359] = 0.2
        val pattern = horizontalPattern(normalizedField = samples)
        val protected = protectedOverlay(pattern = pattern, azimuthDegrees = 0.5)
        val north = protected.radials.single { radial -> radial.azimuthDegrees == 0.0 }

        assertEquals(0.36, north.erpKw, ERP_TOLERANCE)
    }

    @Test
    fun `zero pattern field preserves radial NoData evidence without nominal fallback`() {
        val pattern = horizontalPattern(
            normalizedField = List(360) { angle -> if (angle == 180) 0.0 else 1.0 },
        )
        val protected = protectedOverlay(pattern = pattern)
        val rear = protected.radials.single { radial -> radial.azimuthDegrees == 180.0 }

        assertEquals(ContourRadialStatus.NO_DATA, rear.status)
        assertNull(rear.distanceKm)
        assertEquals(0.0, rear.erpKw, STRICT_TOLERANCE)
        assertTrue(rear.warnings.any { warning -> warning.contains("zero field") })
        assertEquals(ContourStatus.INCOMPLETE, protected.status)
        assertFalse(protected.warnings.contains(PATTERN_FALLBACK_WARNING))
        assertFalse(protected.regulatory)
    }

    @Test
    fun `assigned pattern identity and hash participate in deterministic provenance`() {
        val samples = List(360) { angle -> if (angle == 0) 1.0 else 0.5 }
        val firstPattern = horizontalPattern(
            id = "pattern-a",
            normalizedField = samples,
        )
        val secondPattern = horizontalPattern(
            id = "pattern-a",
            peakGainDbi = 25.1,
            normalizedField = samples,
        )
        val thirdPattern = horizontalPattern(
            id = "pattern-b",
            normalizedField = samples,
        )
        val firstProject = patternedProject(firstPattern)
        val secondProject = patternedProject(secondPattern)
        val thirdProject = patternedProject(thirdPattern)

        val first = BrazilBroadcastContourPlanner.plan(firstProject)
        val repeated = BrazilBroadcastContourPlanner.plan(firstProject)
        val second = BrazilBroadcastContourPlanner.plan(secondProject)
        val third = BrazilBroadcastContourPlanner.plan(thirdProject)

        assertEquals(first, repeated)
        assertTrue(first.overlays.all { overlay -> overlay.inputFingerprint.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(
            first.overlays.map(ServiceContourOverlay::inputFingerprint) !=
                second.overlays.map(ServiceContourOverlay::inputFingerprint),
        )
        assertTrue(
            first.overlays.map(ServiceContourOverlay::inputFingerprint) !=
                third.overlays.map(ServiceContourOverlay::inputFingerprint),
        )
    }

    @Test
    fun `finite extreme FM ERP stops at the model boundary with open incomplete geometry`() {
        val extremeErpKw = 1e100
        val project = project(
            networks = listOf(network()),
            sites = listOf(
                site(
                    sectors = listOf(
                        sector(
                            id = "extreme-erp",
                            transmitPowerDbm = 60.0 + 10.0 * kotlin.math.log10(extremeErpKw),
                            antennaGainDbi = 2.15,
                            feederLossDb = 0.0,
                        ),
                    ),
                ),
            ),
        )

        val protected = BrazilBroadcastContourPlanner.plan(project).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }

        assertEquals(ContourStatus.INCOMPLETE, protected.status)
        assertEquals(72, protected.radials.size)
        assertEquals(72, protected.points.size)
        assertTrue(protected.points.first() != protected.points.last())
        assertTrue(protected.radials.all { radial -> radial.status == ContourRadialStatus.MODEL_BOUNDARY })
        assertTrue(protected.radials.all { radial -> radial.distanceKm == 1000.0 })
        assertTrue(protected.radials.all { radial -> radial.erpKw.isFinite() })
        assertTrue(
            protected.warnings.any { warning ->
                warning.contains("threshold remains exceeded at the 1000 km model boundary") &&
                    warning.contains("incomplete")
            },
        )
    }

    @Test
    fun `geodesic destination normalizes an antimeridian crossing`() {
        val destination = BroadcastContourGeodesy.destination(
            origin = GeoPoint(latitude = 0.0, longitude = 179.9),
            bearingDegrees = 90.0,
            distanceKm = 50.0,
        )

        assertEquals(0.0, destination.latitude, GEODESIC_TOLERANCE)
        assertEquals(-179.650_339_818, destination.longitude, GEODESIC_TOLERANCE)
        assertTrue(destination.longitude in -180.0..180.0)
    }

    @Test
    fun `planner skips unlinked inactive unsupported and inactive network sectors`() {
        val fm = network(id = "fm", system = RadioSystem.FM_BROADCAST, frequencyMHz = 100.1)
        val inactiveFm = network(
            id = "inactive-fm",
            system = RadioSystem.FM_BROADCAST,
            frequencyMHz = 100.1,
            active = false,
        )
        val generic = network(id = "generic", system = RadioSystem.GENERIC, frequencyMHz = 900.0)
        val project = project(
            networks = listOf(fm, inactiveFm, generic),
            sites = listOf(
                site(
                    sectors = listOf(
                        sector(id = "included", networkId = fm.id),
                        sector(id = "unlinked", networkId = null),
                        sector(id = "inactive-sector", networkId = fm.id, active = false),
                        sector(id = "unsupported", networkId = generic.id, frequencyMHz = 900.0),
                        sector(id = "inactive-network", networkId = inactiveFm.id),
                    ),
                ),
            ),
        )

        val plan = BrazilBroadcastContourPlanner.plan(project)

        assertEquals(4, plan.skippedSectorCount)
        assertEquals(3, plan.overlays.size)
        assertEquals(setOf("included"), plan.overlays.map(ServiceContourOverlay::sectorId).toSet())
    }

    @Test
    fun `height outside the packaged model returns NoData without geometry`() {
        val project = project(
            networks = listOf(network()),
            sites = listOf(
                site(
                    sectors = listOf(
                        sector(id = "short", antennaHeightM = 9.999),
                    ),
                ),
            ),
        )

        val plan = BrazilBroadcastContourPlanner.plan(project)

        assertEquals(3, plan.overlays.size)
        assertTrue(plan.overlays.all { overlay -> overlay.status == ContourStatus.NO_DATA })
        assertTrue(plan.overlays.all { overlay -> overlay.points.isEmpty() })
        assertTrue(plan.overlays.all { overlay -> overlay.radials.isEmpty() })
        plan.overlays
            .filterNot { overlay -> overlay.statisticalBasis.contains("E(80,80)") }
            .forEach { overlay ->
                assertTrue(
                    overlay.warnings.any { warning ->
                        warning.contains("outside the packaged") && warning.contains("P.1546")
                    },
                )
            }
    }

    @Test
    fun `planning is deterministic across project collection ordering`() {
        val fmNetwork = network(id = "network-fm", system = RadioSystem.FM_BROADCAST, frequencyMHz = 100.1)
        val tvNetwork = network(id = "network-tv", system = RadioSystem.TV_BROADCAST, frequencyMHz = 509.0)
        val fmSite = site(
            id = "site-z",
            sectors = listOf(sector(id = "sector-fm", networkId = fmNetwork.id)),
        )
        val tvSite = site(
            id = "site-a",
            location = GeoPoint(-22.9, -43.2),
            sectors = listOf(
                sector(id = "sector-tv", networkId = tvNetwork.id, frequencyMHz = 509.0),
            ),
        )
        val firstProject = project(
            networks = listOf(fmNetwork, tvNetwork),
            sites = listOf(fmSite, tvSite),
        )
        val reorderedProject = project(
            networks = listOf(tvNetwork, fmNetwork),
            sites = listOf(tvSite, fmSite),
        )

        val first = BrazilBroadcastContourPlanner.plan(firstProject)
        val repeated = BrazilBroadcastContourPlanner.plan(firstProject)
        val reordered = BrazilBroadcastContourPlanner.plan(reorderedProject)

        assertEquals(first, repeated)
        assertEquals(first, reordered)
        assertEquals(
            first.overlays.map(ServiceContourOverlay::inputFingerprint),
            reordered.overlays.map(ServiceContourOverlay::inputFingerprint),
        )
        assertTrue(first.overlays.all { overlay -> overlay.inputFingerprint.isNotBlank() })
        assertNotNull(first.overlays.firstOrNull())
    }

    @Test
    fun `moving a site changes provenance and WGS84 geometry but not radial distances`() {
        val fmNetwork = network()
        val original = project(
            networks = listOf(fmNetwork),
            sites = listOf(
                site(
                    location = GeoPoint(-23.55, -46.63),
                    sectors = listOf(sector()),
                ),
            ),
        )
        val moved = project(
            networks = listOf(fmNetwork),
            sites = listOf(
                site(
                    location = GeoPoint(-22.90, -43.20),
                    sectors = listOf(sector()),
                ),
            ),
        )

        val originalProtected = BrazilBroadcastContourPlanner.plan(original).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }
        val movedProtected = BrazilBroadcastContourPlanner.plan(moved).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }

        assertTrue(originalProtected.inputFingerprint != movedProtected.inputFingerprint)
        assertTrue(originalProtected.points != movedProtected.points)
        assertEquals(
            originalProtected.radials.map(ContourRadial::distanceKm),
            movedProtected.radials.map(ContourRadial::distanceKm),
        )
        assertEquals(originalProtected.radials, movedProtected.radials)
    }

    private fun protectedOverlay(
        pattern: AntennaPatternRecord,
        azimuthDegrees: Double = 0.0,
    ): ServiceContourOverlay = BrazilBroadcastContourPlanner.plan(
        patternedProject(pattern, azimuthDegrees),
    ).overlays.single { overlay -> overlay.purpose == ContourPurpose.PROTECTED }

    private fun patternedProject(
        pattern: AntennaPatternRecord,
        azimuthDegrees: Double = 0.0,
    ): PlannerProject = project(
        networks = listOf(network()),
        sites = listOf(
            site(
                sectors = listOf(
                    sector(
                        azimuthDegrees = azimuthDegrees,
                        transmitAntennaPatternId = pattern.id,
                    ),
                ),
            ),
        ),
        antennaPatterns = listOf(pattern),
    )

    private fun horizontalPattern(
        id: String = "pattern",
        peakGainDbi: Double = 25.0,
        nominalFrequencyHz: Double = 100.1e6,
        normalizedField: List<Double>,
    ): AntennaPatternRecord {
        val provenance = PatternProvenance(
            origin = PatternOrigin.SYNTHESIZED,
            sourceLabel = "Contour test fixture",
        )
        val canonical = CanonicalAntennaPattern(
            id = "canonical-$id",
            name = "$id horizontal pattern",
            horizontalCut = AntennaPatternCut(
                plane = PatternCutPlane.HORIZONTAL,
                samples = normalizedField.mapIndexed { angle, field ->
                    PatternSample(angle.toDouble(), field)
                },
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            verticalCut = AntennaPatternCut(
                plane = PatternCutPlane.VERTICAL,
                samples = (-90..90).map { angle -> PatternSample(angle.toDouble(), 1.0) },
                provenance = provenance,
                availability = PatternCutAvailability.AVAILABLE,
            ),
            provenance = provenance,
            nominalFrequencyHz = nominalFrequencyHz,
        )
        return canonical.toProjectRecord(
            ProjectAntennaPatternIdentity(
                id = id,
                name = canonical.name,
                peakGainDbi = peakGainDbi,
                sourceFormat = "ATX canonical test fixture",
                sourceSha256 = null,
                sourceArtifactId = null,
                canonicalArtifactId = "artifact-$id",
                origin = AntennaPatternOrigin.SYNTHESIZED,
            ),
        ).copy(dataArtifactId = null)
    }

    private fun fmProject(): PlannerProject = project(
        networks = listOf(network()),
        sites = listOf(site(sectors = listOf(sector()))),
    )

    private fun protectedDistanceKm(
        service: RadioSystem,
        frequencyMHz: Double,
        erpKw: Double,
        effectiveHeightM: Double,
    ): Double {
        val network = network(
            id = "class-vector",
            system = service,
            frequencyMHz = frequencyMHz,
        )
        val project = project(
            networks = listOf(network),
            sites = listOf(
                site(
                    sectors = listOf(
                        sector(
                            id = "class-vector",
                            networkId = network.id,
                            frequencyMHz = frequencyMHz,
                            antennaHeightM = effectiveHeightM,
                            transmitPowerDbm = 60.0 + 10.0 * kotlin.math.log10(erpKw),
                            antennaGainDbi = 2.15,
                            feederLossDb = 0.0,
                        ),
                    ),
                ),
            ),
        )
        val protected = BrazilBroadcastContourPlanner.plan(project).overlays.single { overlay ->
            overlay.purpose == ContourPurpose.PROTECTED
        }

        assertEquals(ContourStatus.COMPLETE, protected.status)
        assertEquals(erpKw, protected.radials.first().erpKw, ERP_TOLERANCE)
        return requireNotNull(protected.radials.first().distanceKm)
    }

    private fun project(
        networks: List<RfNetwork>,
        sites: List<RadioSite>,
        antennaPatterns: List<AntennaPatternRecord> = emptyList(),
    ): PlannerProject = PlannerProject(
        id = "project",
        name = "Broadcast contour test project",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        networks = networks,
        sites = sites,
        antennaPatterns = antennaPatterns,
    )

    private fun network(
        id: String = "fm",
        system: RadioSystem = RadioSystem.FM_BROADCAST,
        frequencyMHz: Double = 100.1,
        active: Boolean = true,
    ): RfNetwork = RfNetwork(
        id = id,
        name = "$id network",
        system = system,
        downlinkFrequencyMHz = frequencyMHz,
        bandwidthMHz = if (system == RadioSystem.TV_BROADCAST) 6.0 else 0.2,
        active = active,
    )

    private fun site(
        id: String = "site",
        location: GeoPoint = GeoPoint(-23.55, -46.63),
        sectors: List<Sector>,
    ): RadioSite = RadioSite(
        id = id,
        name = "$id broadcast site",
        location = location,
        sectors = sectors,
    )

    private fun sector(
        id: String = "sector",
        networkId: String? = "fm",
        frequencyMHz: Double = 100.1,
        antennaHeightM: Double = 150.0,
        active: Boolean = true,
        azimuthDegrees: Double = 0.0,
        transmitPowerDbm: Double = 60.0,
        antennaGainDbi: Double = 5.15,
        feederLossDb: Double = 3.0,
        transmitAntennaPatternId: String? = null,
    ): Sector = Sector(
        id = id,
        name = "$id broadcast sector",
        active = active,
        azimuthDegrees = azimuthDegrees,
        antennaHeightM = antennaHeightM,
        transmitPowerDbm = transmitPowerDbm,
        antennaGainDbi = antennaGainDbi,
        feederLossDb = feederLossDb,
        frequencyMHz = frequencyMHz,
        networkId = networkId,
        transmitAntennaPatternId = transmitAntennaPatternId,
    )

    private companion object {
        const val STRICT_TOLERANCE = 1e-9
        const val TABLE_QUANTIZATION_TOLERANCE = 0.02
        const val CONTOUR_CROSSING_TOLERANCE = 1e-7
        const val GEODESIC_TOLERANCE = 1e-6
        const val CLASS_DISTANCE_TOLERANCE_KM = 2.0
        const val ERP_TOLERANCE = 1e-10
        const val ERP_CONVERSION_WARNING =
            "ERP is derived from the stored dBi gain by subtracting the 2.15 dB isotropic-to-dipole reference."
        const val NON_REGULATORY_WARNING =
            "This planning reference is not a regulatory filing result."
        const val PATTERN_FALLBACK_WARNING =
            "Nominal ERP fallback is applied to every radial because no assigned calculation-ready horizontal antenna cut is available."
        const val VERSION_ONE_GAIN_UNBOUND_SHA256 =
            "345405b099920614fa8adc6030ad116449574d28885d39948fb85f31564c8ecb"
    }
}
