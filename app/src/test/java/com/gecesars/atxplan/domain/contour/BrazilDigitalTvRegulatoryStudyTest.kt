package com.gecesars.atxplan.domain.contour

import com.gecesars.atxplan.data.export.BrazilDigitalTvStudyReportExporter
import com.gecesars.atxplan.data.export.BrazilDigitalTvStudyPdfExporter
import com.gecesars.atxplan.data.export.BrazilDigitalTvStudyXlsxExporter
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecordProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanStatus
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.AnatelResolvedFrequency
import com.gecesars.atxplan.domain.model.GeoPoint
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.RadioSite
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.RfNetwork
import com.gecesars.atxplan.domain.model.Sector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class BrazilDigitalTvRegulatoryStudyTest {
    @Test
    fun calculatesIndependentChannel42ContourWithExactProgressAndReportEvidence() {
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = BrazilDigitalTvRegulatoryStudyPlanner.calculate(
            project = project(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = RegulatoryTerrainProvenance(
                datasetId = "copernicus-dem-glo30-2021",
                datasetTitle = "Copernicus DEM GLO-30 Public 2021 DSM",
                dataType = "DIGITAL_SURFACE_MODEL",
                relativePath = "elevation/copernicus-dem-glo30/S24_00_W047_00_DEM.tif",
                sha256 = "a".repeat(64),
                acquiredAt = "2026-08-31T12:00:00Z",
                sourceUrl = "https://example.test/S24_00_W047_00_DEM.tif",
                licenseTitle = "Copernicus DEM License",
                attribution = "Copernicus attribution",
                nominalResolutionM = 30.0,
                sampleMethod = "nearest source pixel",
            ),
            referenceRecords = emptyList(),
            catalogSnapshot = null,
            onProgress = { completed, total -> progress += completed to total },
        )

        assertEquals(42, result.channel)
        assertEquals(1.0, result.peakErpKw, 1e-12)
        assertEquals(72, result.radialEvidence.size)
        assertEquals(ContourStatus.COMPLETE, result.contour.status)
        assertEquals(253 to 253, progress.last())
        assertEquals(181, result.coverageSurface.width)
        assertTrue(result.coverageSurface.maximumCalculatedDbuvPerM != null)
        assertTrue(result.coverageSurface.warnings.any { it.contains("operational visualization") })
        assertFalse(result.filingReady)
        assertTrue(result.warnings.any { it.contains("independent") })
        val report = BrazilDigitalTvStudyReportExporter.export(result, 0L).toString(Charsets.UTF_8)
        assertTrue(report.contains("No transmitter, site, antenna, ERP, or height value was copied"))
        assertTrue(report.contains("Channel 42"))
        assertTrue(report.contains("DIGITAL_SURFACE_MODEL"))

        val pdf = BrazilDigitalTvStudyPdfExporter.export(result, 0L)
        assertTrue(pdf.copyOfRange(0, 8).toString(Charsets.US_ASCII).startsWith("%PDF-1.4"))
        assertTrue(pdf.toString(Charsets.ISO_8859_1).endsWith("%%EOF\n"))
        assertTrue(pdf.size < BrazilDigitalTvStudyPdfExporter.MAX_OUTPUT_BYTES)

        val workbook = BrazilDigitalTvStudyXlsxExporter.export(
            result.copy(projectName = "=UNTRUSTED()"),
            0L,
        )
        val workbookEntries = unzipTextEntries(workbook)
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "docProps/core.xml",
                "docProps/app.xml",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
                "xl/worksheets/sheet3.xml",
                "xl/worksheets/sheet4.xml",
                "xl/worksheets/sheet5.xml",
                "xl/worksheets/sheet6.xml",
                "xl/worksheets/sheet7.xml",
                "xl/worksheets/sheet8.xml",
            ),
            workbookEntries.keys,
        )
        assertTrue(checkNotNull(workbookEntries["xl/worksheets/sheet1.xml"]).contains("&apos;=UNTRUSTED()"))
        assertTrue(checkNotNull(workbookEntries["xl/worksheets/sheet7.xml"]).contains("Field (dBµV/m)"))
        assertTrue(workbook.size < BrazilDigitalTvStudyXlsxExporter.MAX_OUTPUT_BYTES)

        System.getenv("ATX_VALIDATION_OUTPUT_ROOT")?.takeIf(String::isNotBlank)?.let { root ->
            val outputRoot = Path.of(root)
            val pdfDirectory = outputRoot.resolve("pdf")
            val workbookDirectory = outputRoot.resolve("spreadsheets")
            Files.createDirectories(pdfDirectory)
            Files.createDirectories(workbookDirectory)
            Files.write(pdfDirectory.resolve("channel-42-regulatory-study.pdf"), pdf)
            Files.write(
                workbookDirectory.resolve("channel-42-engineering-data.xlsx"),
                BrazilDigitalTvStudyXlsxExporter.export(result, 0L),
            )
        }
    }

    @Test
    fun retainsCompletePointEvidenceForEveryEvaluatedDuBoundaryPath() {
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = BrazilDigitalTvRegulatoryStudyPlanner.calculate(
            project = project(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance(),
            referenceRecords = listOf(referenceStation()),
            catalogSnapshot = null,
            onProgress = { completed, total -> progress += completed to total },
        )

        assertEquals(2, result.duAssessments.size)
        assertEquals(RegulatoryDuDirection.REFERENCE_TO_PROJECT, result.duAssessments[0].direction)
        assertEquals(RegulatoryDuDirection.PROJECT_TO_REFERENCE, result.duAssessments[1].direction)
        result.duAssessments.forEach { assessment ->
            assertEquals(72, assessment.points.size)
            assertEquals(72, assessment.evaluatedPointCount)
            assertEquals(0, assessment.noDataPointCount)
            assertEquals(
                72,
                assessment.passingPointCount + assessment.failingPointCount,
            )
            assertTrue(assessment.points.all { point -> point.duDb != null })
            assertTrue(assessment.points.all { point -> point.diffractionLossDb != null })
        }
        assertEquals(469 to 469, progress.last())
        val report = BrazilDigitalTvStudyReportExporter.export(result, 0L).toString(Charsets.UTF_8)
        assertTrue(report.contains("D/U protected-boundary point evidence"))
        assertTrue(report.contains("PB-REFERENCE-42"))
    }

    @Test
    fun calculatesCurrentFmBidirectionalRatiosAndE5050() {
        val result = BrazilBroadcastRegulatoryStudyPlanner.calculate(
            project = fmProject(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance(),
            referenceRecords = listOf(fmReferenceStation()),
            catalogSnapshot = null,
        )

        assertEquals(BroadcastService.FM, result.service)
        assertEquals(251, result.channel)
        assertEquals("E(50,50)", result.protectedStatisticalBasis)
        assertEquals(2, result.duAssessments.size)
        assertTrue(result.duAssessments.all { it.requiredDuDb == 6.0 })
        assertEquals(
            setOf(RegulatoryDuDirection.REFERENCE_TO_PROJECT, RegulatoryDuDirection.PROJECT_TO_REFERENCE),
            result.duAssessments.map { it.direction }.toSet(),
        )
        assertTrue(result.warnings.any { it.contains("30 dB cochannel") })
        assertTrue(result.blockers.any { it.contains("urban census-sector coverage gate") })

        val report = BrazilDigitalTvStudyReportExporter.export(result, 0L).toString(Charsets.UTF_8)
        assertTrue(report.contains("Brazil FM Regulatory Study"))
        assertTrue(report.contains(RegulatoryDuDirection.REFERENCE_TO_PROJECT.displayName))
        assertTrue(report.contains(RegulatoryDuDirection.PROJECT_TO_REFERENCE.displayName))

        val workbookEntries = unzipTextEntries(BrazilDigitalTvStudyXlsxExporter.export(result, 0L))
        val duSummary = checkNotNull(workbookEntries["xl/worksheets/sheet3.xml"])
        assertTrue(duSummary.contains(RegulatoryDuDirection.REFERENCE_TO_PROJECT.displayName))
        assertTrue(duSummary.contains(RegulatoryDuDirection.PROJECT_TO_REFERENCE.displayName))

        val pdf = BrazilDigitalTvStudyPdfExporter.export(result, 0L)
        assertTrue(pdf.copyOfRange(0, 8).toString(Charsets.US_ASCII).startsWith("%PDF-1.4"))
        assertTrue(pdf.toString(Charsets.ISO_8859_1).endsWith("%%EOF\n"))
    }

    @Test
    fun calculatesExtendedFmWithChannels141Through197InsteadOfFormerTvFiveAndSix() {
        val project = fmProject().copy(
            id = "project-extended-fm-141",
            name = "Extended FM Channel 141 Study",
            networks = fmProject().networks.map { network ->
                network.copy(name = "Extended FM Channel 141", downlinkFrequencyMHz = 76.1)
            },
            sites = fmProject().sites.map { site ->
                site.copy(
                    sectors = site.sectors.map { sector ->
                        sector.copy(name = "Extended FM Channel 141", frequencyMHz = 76.1)
                    },
                )
            },
        )
        val reference = fmReferenceStation().copy(
            sourceRowId = "reference-extended-fm-142",
            basicPlanId = "PBFM-REFERENCE-142",
            channelRaw = "142",
            channel = 142,
            frequency = AnatelResolvedFrequency(
                frequencyMHz = 76.3,
                origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
                sourceFrequencyRaw = "76.3",
                explanation = "Test source frequency",
            ),
        )

        val result = BrazilBroadcastRegulatoryStudyPlanner.calculate(
            project = project,
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance(),
            referenceRecords = listOf(reference),
            catalogSnapshot = null,
        )

        assertEquals(BroadcastService.FM, result.service)
        assertEquals(141, result.channel)
        assertEquals(76.1, result.frequencyMHz, 0.0)
        assertEquals(2, result.duAssessments.size)
        assertTrue(result.duAssessments.all { it.station.channel == 142 })
    }

    @Test
    fun usesErpRatioForColocatedDigitalAdjacentChannelsInBothDirections() {
        val colocated = referenceStation().copy(
            sourceRowId = "reference-channel-43-colocated",
            basicPlanId = "PB-REFERENCE-43-COLOCATED",
            channel = 43,
            frequency = AnatelResolvedFrequency(
                frequencyMHz = 647.0,
                origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
                sourceFrequencyRaw = "647",
                explanation = "Test source frequency",
            ),
            longitudeDegrees = -46.6330,
        )
        val result = BrazilDigitalTvRegulatoryStudyPlanner.calculate(
            project = project(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance(),
            referenceRecords = listOf(colocated),
            catalogSnapshot = null,
        )

        assertEquals(2, result.duAssessments.size)
        assertTrue(result.duAssessments.all { it.method == RegulatoryDuMethod.COLOCATED_ERP_RATIO })
        assertTrue(result.duAssessments.all { it.evaluatedPointCount == 1 })
        assertTrue(result.duAssessments.all { it.requiredDuDb == -36.0 })
        assertTrue(result.referenceContours.isEmpty())
    }

    @Test
    fun excludesAnalogTelevisionBasicPlanRowsFromDigitalStudy() {
        val analogRow = referenceStation().copy(
            sourceRowId = "legacy-analog-tv-42",
            basicPlanId = "LEGACY-TV-42",
            rawService = "TV",
        )

        val result = BrazilDigitalTvRegulatoryStudyPlanner.calculate(
            project = project(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance(),
            referenceRecords = listOf(analogRow),
            catalogSnapshot = null,
        )

        assertEquals(0, result.applicableReferenceRecordCount)
        assertEquals(0, result.referenceStationCount)
        assertTrue(result.duAssessments.isEmpty())
    }

    @Test
    fun excludesReferenceWhoseFrequencyConflictsWithItsDeclaredDigitalChannel() {
        val inconsistent = referenceStation().copy(
            frequency = AnatelResolvedFrequency(
                frequencyMHz = 647.0,
                origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
                sourceFrequencyRaw = "647",
                explanation = "Test conflicting source frequency",
            ),
        )

        val result = BrazilDigitalTvRegulatoryStudyPlanner.calculate(
            project = project(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance(),
            referenceRecords = listOf(inconsistent),
            catalogSnapshot = null,
        )

        assertEquals(1, result.applicableReferenceRecordCount)
        assertEquals(1, result.unevaluatedReferenceRecordCount)
        assertTrue(result.duAssessments.isEmpty())
        assertTrue(result.blockers.any { it.contains("required RF fields were missing") })
    }

    @Test
    fun retainsLicensedExistingVersusProposedScenarioEvidence() {
        val municipality = RegulatoryMunicipalityContext("3550308", "São Paulo", "SP")
        val licensed = LicensedBroadcastStation(
            sourceId = "mcom-licensed-42",
            basicPlanId = "PB-LICENSED-42",
            serviceCode = 248,
            rawService = "GTVD",
            technology = BroadcastTechnology.DIGITAL,
            role = LicensedBroadcastRole.GENERATOR,
            channel = 42,
            frequencyMHz = 641.0,
            location = GeoPoint(-23.550520, -46.500000),
            municipalityCode = municipality.ibgeCode,
            municipalityName = municipality.name,
            stateAbbreviation = municipality.stateAbbreviation,
            licensee = "Licensed wanted station",
            licenseId = "license-42",
            licensedOn = "2026-08-01",
            stationClassRaw = "A",
            erpKw = 1.0,
            antennaHeightAglM = 100.0,
            horizontalPattern = null,
            rawStatus = "licensed",
        )
        val sector = RegulatoryCensusSector(
            sectorCode = "355030801000001",
            areaKm2 = 4.9,
            residentPopulation = 1_000,
            polygons = listOf(
                RegulatoryCensusPolygon(
                    listOf(RegulatoryCensusRing(closedSquare(-23.550520, -46.633308, 0.02))),
                ),
            ),
        )
        val context = BrazilBroadcastRegulatoryContext(
            municipality = municipality,
            censusGeometry = RegulatoryCensusGeometrySnapshot(
                municipality = municipality,
                sectors = listOf(sector),
                transmitterInsideMunicipality = true,
                sourceUrl = "https://example.test/SP.gpkg",
                sourcePageUrl = "https://example.test/ibge",
                sourceSha256 = "c".repeat(64),
                sourceByteCount = 4_096L,
                sourceEtag = "\"ibge-test\"",
                sourceLastModified = null,
            ),
            licensedBaseline = LicensedBroadcastBaselineSnapshot(
                stations = listOf(licensed),
                sourceUrl = "https://example.test/mcom.csv",
                sourcePageUrl = "https://example.test/mcom",
                sourceSha256 = "d".repeat(64),
                sourceByteCount = 1_024L,
                sourceEtag = "\"mcom-test\"",
                sourceLastModified = null,
                generatedOn = "2026-09-01",
                referenceDate = "2026-08-31",
                sourceRowCount = 1L,
                rejectedRowCount = 0L,
            ),
        )

        val result = BrazilDigitalTvRegulatoryStudyPlanner.calculate(
            project = project(),
            radiusKm = 30.0,
            terrain = TerrainElevationProvider { _, _ -> 700.0 },
            terrainProvenance = terrainProvenance().copy(dataType = "DIGITAL_TERRAIN_MODEL"),
            referenceRecords = emptyList(),
            catalogSnapshot = null,
            regulatoryContext = context,
        )

        assertEquals(1, result.scenarioComparisons.size)
        val comparison = result.scenarioComparisons.single()
        assertEquals(licensed.sourceId, comparison.wantedStationId)
        assertEquals(0, comparison.baselineInterfererCount)
        assertTrue(comparison.proposedProjectMarginDb != null)
        assertTrue(comparison.status != RegulatoryScenarioStatus.NO_DATA)
        assertEquals("d".repeat(64), result.licensedBaseline?.sourceSha256)
    }

    private fun project() = PlannerProject(
        id = "project-independent-channel-42",
        name = "SP Channel 42 - 30 km Regulatory Study",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        networks = listOf(
            RfNetwork(
                id = "dtv-network",
                name = "Independent DTV Channel 42",
                system = RadioSystem.TV_BROADCAST,
                downlinkFrequencyMHz = 641.0,
                bandwidthMHz = 6.0,
            ),
        ),
        sites = listOf(
            RadioSite(
                id = "central-sp-site",
                name = "Central SP Independent Study Site",
                location = GeoPoint(-23.550520, -46.633308),
                sectors = listOf(
                    Sector(
                        id = "channel-42-sector",
                        name = "Channel 42 Omnidirectional",
                        azimuthDegrees = 0.0,
                        antennaHeightM = 100.0,
                        transmitPowerDbm = 60.0,
                        antennaGainDbi = 2.15,
                        feederLossDb = 0.0,
                        frequencyMHz = 641.0,
                        networkId = "dtv-network",
                    ),
                ),
            ),
        ),
    )

    private fun fmProject() = PlannerProject(
        id = "project-independent-fm-251",
        name = "FM Channel 251 Regulatory Study",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        networks = listOf(
            RfNetwork(
                id = "fm-network",
                name = "Independent FM Channel 251",
                system = RadioSystem.FM_BROADCAST,
                downlinkFrequencyMHz = 98.1,
                bandwidthMHz = 0.2,
            ),
        ),
        sites = listOf(
            RadioSite(
                id = "central-sp-fm-site",
                name = "Central SP FM Study Site",
                location = GeoPoint(-23.550520, -46.633308),
                sectors = listOf(
                    Sector(
                        id = "fm-251-sector",
                        name = "FM Channel 251",
                        azimuthDegrees = 0.0,
                        antennaHeightM = 100.0,
                        transmitPowerDbm = 60.0,
                        antennaGainDbi = 2.15,
                        feederLossDb = 0.0,
                        frequencyMHz = 98.1,
                        networkId = "fm-network",
                    ),
                ),
            ),
        ),
    )

    private fun terrainProvenance() = RegulatoryTerrainProvenance(
        datasetId = "copernicus-dem-glo30-2021",
        datasetTitle = "Copernicus DEM GLO-30 Public 2021 DSM",
        dataType = "DIGITAL_SURFACE_MODEL",
        relativePath = "elevation/copernicus-dem-glo30/S24_00_W047_00_DEM.tif",
        sha256 = "a".repeat(64),
        acquiredAt = "2026-08-31T12:00:00Z",
        sourceUrl = "https://example.test/S24_00_W047_00_DEM.tif",
        licenseTitle = "Copernicus DEM License",
        attribution = "Copernicus attribution",
        nominalResolutionM = 30.0,
        sampleMethod = "nearest source pixel",
    )

    private fun referenceStation() = AnatelBasicPlanRecord(
        sourceRowId = "reference-channel-42",
        basicPlanId = "PB-REFERENCE-42",
        itemNumber = 1L,
        origin = AnatelBasicPlanOrigin.BASIC_PLAN,
        service = AnatelBroadcastService.TELEVISION,
        rawService = "GTVD",
        status = AnatelBasicPlanStatus("PB"),
        channelRaw = "42",
        channel = 42,
        frequency = AnatelResolvedFrequency(
            frequencyMHz = 641.0,
            origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
            sourceFrequencyRaw = "641",
            explanation = "Test source frequency",
        ),
        countryCode = "BR",
        stateCode = "SP",
        ibgeMunicipalityCode = "3550308",
        municipalityName = "São Paulo",
        channelOffsetRaw = "",
        stationClassRaw = "A",
        characterRaw = "",
        purposeRaw = "",
        entityName = "Read-only reference station",
        cnpjRaw = "",
        stationCategoryRaw = "",
        latitudeDegrees = -23.550520,
        longitudeDegrees = -46.500000,
        erpKw = 0.001,
        antennaHeightMeters = 100.0,
        antennaLimitationsRaw = "",
        antennaPatternDbdRaw = "",
        observationsRaw = "",
        fistelRaw = "",
        generatorFistelRaw = "",
        dicRaw = "",
        provenance = AnatelBasicPlanRecordProvenance(
            archive = AnatelBasicPlanArchiveProvenance(
                acquiredAtEpochMillis = 0L,
                archiveSha256 = "b".repeat(64),
                archiveByteCount = 1024L,
            ),
            entryName = AnatelBasicPlanOrigin.BASIC_PLAN.officialArchiveEntryName,
            origin = AnatelBasicPlanOrigin.BASIC_PLAN,
            generationDate = "2026-08-31",
            sourceRowNumber = 1L,
        ),
    )

    private fun fmReferenceStation() = referenceStation().copy(
        sourceRowId = "reference-fm-252",
        basicPlanId = "PBFM-REFERENCE-252",
        service = AnatelBroadcastService.FM,
        rawService = "FM",
        channelRaw = "252",
        channel = 252,
        frequency = AnatelResolvedFrequency(
            frequencyMHz = 98.3,
            origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
            sourceFrequencyRaw = "98.3",
            explanation = "Test source frequency",
        ),
        stationClassRaw = "B2",
        longitudeDegrees = -46.500000,
    )

    private fun unzipTextEntries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun closedSquare(latitude: Double, longitude: Double, halfSize: Double): List<GeoPoint> =
        listOf(
            GeoPoint(latitude - halfSize, longitude - halfSize),
            GeoPoint(latitude - halfSize, longitude + halfSize),
            GeoPoint(latitude + halfSize, longitude + halfSize),
            GeoPoint(latitude + halfSize, longitude - halfSize),
            GeoPoint(latitude - halfSize, longitude - halfSize),
        )
}
