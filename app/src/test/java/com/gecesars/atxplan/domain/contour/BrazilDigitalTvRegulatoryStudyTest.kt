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
            ),
            workbookEntries.keys,
        )
        assertTrue(checkNotNull(workbookEntries["xl/worksheets/sheet1.xml"]).contains("&apos;=UNTRUSTED()"))
        assertTrue(checkNotNull(workbookEntries["xl/worksheets/sheet5.xml"]).contains("Field (dBµV/m)"))
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

        val assessment = result.duAssessments.single()
        assertEquals(72, assessment.points.size)
        assertEquals(72, assessment.evaluatedPointCount)
        assertEquals(0, assessment.noDataPointCount)
        assertEquals(
            72,
            assessment.passingPointCount + assessment.failingPointCount,
        )
        assertTrue(assessment.points.all { point -> point.duDb != null })
        assertTrue(assessment.points.all { point -> point.diffractionLossDb != null })
        assertEquals(325 to 325, progress.last())
        val report = BrazilDigitalTvStudyReportExporter.export(result, 0L).toString(Charsets.UTF_8)
        assertTrue(report.contains("D/U protected-boundary point evidence"))
        assertTrue(report.contains("PB-REFERENCE-42"))
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
        rawService = "TV",
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

    private fun unzipTextEntries(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
