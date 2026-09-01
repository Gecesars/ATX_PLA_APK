package com.gecesars.atxplan.data.export

import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.contour.BroadcastService
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Compact, dependency-free PDF evidence report suitable for direct Android document export. */
object BrazilDigitalTvStudyPdfExporter {
    const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    private const val PAGE_WIDTH = 595.0
    private const val PAGE_HEIGHT = 842.0
    private const val LEFT_MARGIN = 42.0
    private const val TOP_Y = 792.0
    private const val BOTTOM_Y = 48.0
    private const val MAX_PAGES = 128
    private val windows1252 = Charset.forName("windows-1252")

    fun export(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(exportedAtEpochMillis >= 0L) { "The PDF export timestamp cannot be negative." }
        val pages = paginate(reportLines(result, exportedAtEpochMillis), result)
        require(pages.isNotEmpty() && pages.size <= MAX_PAGES) {
            "The regulatory PDF exceeds the 128-page export limit."
        }
        return writePdf(pages, result).also { bytes ->
            require(bytes.size <= MAX_OUTPUT_BYTES) {
                "The regulatory PDF exceeds the 4 MiB export limit."
            }
        }
    }

    private fun reportLines(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long,
    ): List<ReportLine> = buildList {
        val serviceLabel = if (result.service == BroadcastService.FM) "FM" else "Digital TV"
        add(title("Brazil $serviceLabel Regulatory Study"))
        add(subtitle("${result.projectName} | Channel ${result.channel} | ${number(result.frequencyMHz, 3)} MHz"))
        add(
            alert(
                if (result.filingReady) {
                    "CALCULATION GATES PASSED"
                } else {
                    "NOT FILING-READY - REVIEW ALL BLOCKERS"
                },
            ),
        )
        add(body("This project transmitter is independent. No transmitter, site, antenna, ERP, or height value was copied from the Anatel Basic Plan; catalog stations are read-only references."))
        add(keyValue("Engineering gates", if (result.engineeringReady) "PASSED; external review remains" else "OPEN"))

        add(heading("Project-owned inputs"))
        add(keyValue("Project ID", result.projectId))
        add(keyValue("Site", "${result.siteName} (${result.siteId})"))
        add(keyValue("Sector", "${result.sectorName} (${result.sectorId})"))
        add(keyValue("Coordinates", "${number(result.center.latitude, 6)}, ${number(result.center.longitude, 6)} | EPSG:4326"))
        add(keyValue("Requested radius", "${number(result.radiusKm, 2)} km"))
        add(keyValue("Peak ERP", "${number(result.peakErpKw, 6)} kW"))
        add(keyValue("Antenna height", "${number(result.antennaHeightAglM, 2)} m AGL"))
        add(keyValue("Receiver height", "${number(result.receiverHeightAglM, 2)} m AGL"))

        add(heading("Methods, units, and assumptions"))
        add(keyValue("Protected contour", "${result.p1546ModelId}; ${result.protectedStatisticalBasis}"))
        add(keyValue("Protected threshold", "${number(result.protectedThresholdDbuvPerM, 2)} dBµV/m"))
        add(keyValue("D/U paths", "${result.diffractionModelId}; k=4/3; terrain spacing ${number(result.terrainSpacingM, 1)} m"))
        add(
            keyValue(
                "D/U criteria",
                if (result.service == BroadcastService.FM) {
                    "Cochannel 30 dB; first adjacent +/-200 kHz 6 dB"
                } else {
                    "Digital-to-digital cochannel +19 dB; first adjacent -36 dB"
                },
            ),
        )
        add(keyValue("D/U direction", "Both directions at each wanted protected contour"))
        add(keyValue("Coverage", "${result.coverageSurface.width} x ${result.coverageSurface.height}; ${result.coverageSurface.statisticalBasis}"))
        add(keyValue("Coverage use", "Operational visualization, not a regulatory filing contour"))
        add(
            keyValue(
                "Directional pattern",
                if (result.coverageSurface.directionalPatternApplied) {
                    "Verified HRP and VRP applied as (E/Emax)^2"
                } else {
                    "Omnidirectional fallback; see warnings"
                },
            ),
        )
        add(keyValue("Coverage NoData", result.coverageSurface.noDataMeaning))
        result.coverageGate?.let { gate ->
            add(heading("Official urban coverage gate"))
            add(keyValue("Municipality", "${gate.municipality.name}/${gate.municipality.stateAbbreviation} (${gate.municipality.ibgeCode})"))
            add(keyValue("Area requirement", "${gate.requirementPercent}% | status ${gate.status.name}"))
            add(keyValue("Coverage interval", "${gate.areaCoverageLowerPercent?.let { number(it, 3) } ?: "NoData"}% to ${gate.areaCoverageUpperPercent?.let { number(it, 3) } ?: "NoData"}%"))
            add(keyValue("Urban area", "eligible ${number(gate.eligibleUrbanAreaKm2, 4)} km2 | covered ${number(gate.coveredUrbanAreaKm2, 4)} km2"))
            add(keyValue("Raster", "${number(gate.rasterSpacingM, 1)} m | ${gate.sectorCount} sectors | ${gate.noDataCellCount} NoData cells"))
            add(small(gate.method))
        }

        add(heading("Blocking conditions"))
        if (result.blockers.isEmpty()) add(body("None."))
        result.blockers.forEach { blocker -> add(bullet(blocker)) }
        add(heading("Warnings and limitations"))
        result.warnings.forEach { warning -> add(bullet(warning)) }
        result.coverageSurface.warnings.forEach { warning -> add(bullet("Coverage: $warning")) }

        add(heading("Dataset provenance"))
        add(keyValue("Terrain", "${result.terrainProvenance.datasetTitle} (${result.terrainProvenance.dataType})"))
        add(keyValue("Terrain integrity", result.terrainProvenance.integrityScope.name))
        add(body(result.terrainProvenance.integrityEvidenceDescription))
        add(keyValue("Sampling", "${number(result.terrainProvenance.nominalResolutionM, 1)} m nominal; ${result.terrainProvenance.sampleMethod}"))
        add(keyValue("License", result.terrainProvenance.licenseTitle))
        add(keyValue("Attribution", result.terrainProvenance.attribution))
        result.terrainProvenance.allArtifacts.sortedBy { artifact -> artifact.relativePath }.forEach { artifact ->
            add(mono("TERRAIN ${artifact.relativePath}"))
            add(mono("SHA256  ${artifact.sha256}"))
            add(body("Source: ${artifact.artifactUrl}"))
        }
        result.terrainProvenance.rangeCacheEvidence.forEach { evidence ->
            add(mono("RANGES ${evidence.sourceId} ${evidence.cachedRangeCount} / ${evidence.cachedByteCount} bytes"))
            add(mono("MANIFEST ${evidence.rangeManifestSha256}"))
        }
        add(keyValue("Anatel archive SHA-256", result.anatelArchiveSha256 ?: "NoData"))
        add(keyValue("Anatel index", result.anatelIndexArtifactName ?: "NoData"))
        result.censusGeometry?.let { census ->
            add(keyValue("IBGE census geometry", "${census.sourceRelease}; ${census.sourceCrs}; ${census.sectors.size} urban sectors"))
            add(mono("IBGE SHA256 ${census.sourceSha256}"))
            add(body("IBGE source: ${census.sourceUrl}"))
        }
        result.licensedBaseline?.let { baseline ->
            add(keyValue("MCom baseline", "${baseline.generatedOn} / ${baseline.referenceDate}; ${baseline.stations.size} bounded records; ${baseline.unlocatedSameChannelStationCount} unlocated"))
            add(mono("MCOM SHA256 ${baseline.sourceSha256}"))
            add(body("MCom source: ${baseline.sourceUrl}"))
        }

        add(heading("Licensed existing versus proposed scenarios"))
        if (result.scenarioComparisons.isEmpty()) add(body("No calculation-ready licensed wanted station in the bounded query."))
        result.scenarioComparisons.forEach { scenario ->
            add(subheading("${scenario.wantedStationLabel} | ${scenario.status.name}"))
            add(
                body(
                    "Existing interferers ${scenario.baselineInterfererCount} | existing worst ${scenario.baselineWorstMarginDb?.let { number(it, 2) } ?: "None"} dB | " +
                        "project ${scenario.proposedProjectMarginDb?.let { number(it, 2) } ?: "NoData"} dB | proposed worst ${scenario.proposedWorstMarginDb?.let { number(it, 2) } ?: "NoData"} dB | NoData ${scenario.noDataAssessmentCount}",
                ),
            )
        }

        add(heading("Protected-contour radial evidence"))
        add(mono("AZ(deg)  DIST(km)  HNMT(m)  DESIRED(dBµV/m)  STATUS"))
        result.radialEvidence.sortedBy { it.azimuthDegrees }.forEach { radial ->
            add(
                mono(
                    String.format(
                        Locale.US,
                        "%7.1f  %8s  %7s  %16s  %s",
                        radial.azimuthDegrees,
                        radial.distanceKm?.let { number(it, 3) } ?: "NoData",
                        radial.hnmtM?.let { number(it, 1) } ?: "NoData",
                        radial.desiredFieldDbuvPerM?.let { number(it, 2) } ?: "NoData",
                        radial.status.name,
                    ),
                ),
            )
            radial.warning?.let { warning -> add(small("  Note: $warning")) }
        }

        add(heading("Anatel reference-station bidirectional D/U summary"))
        if (result.duAssessments.isEmpty()) add(body("No calculation-ready cochannel or first-adjacent reference station was available in the complete query."))
        result.duAssessments.forEach { assessment ->
            val stationId = assessment.station.basicPlanId ?: assessment.station.sourceRowId
            add(subheading("$stationId | ${assessment.direction.name} | ${assessment.method.name}"))
            add(
                body(
                    "Required ${number(assessment.requiredDuDb, 1)} dB | worst ${assessment.worstDuDb?.let { number(it, 2) } ?: "NoData"} dB | " +
                        "margin ${assessment.minimumMarginDb?.let { number(it, 2) } ?: "NoData"} dB | " +
                        "pass ${assessment.passingPointCount}, fail ${assessment.failingPointCount}, NoData ${assessment.noDataPointCount} | ${assessment.status.name}",
                ),
            )
        }

        if (result.duAssessments.isNotEmpty()) {
            add(heading("D/U protected-boundary point evidence"))
            add(mono("REFERENCE      DIR RAD LATITUDE   LONGITUDE   D      U     LOSS   D/U    REQ  MARGIN STATUS"))
            result.duAssessments.forEach { assessment ->
                val stationId = (assessment.station.basicPlanId ?: assessment.station.sourceRowId).take(14)
                assessment.points.forEach { point ->
                    add(
                        mono(
                            String.format(
                                Locale.US,
                                "%-14s %-3s %3d %9.5f %10.5f %6s %6s %6s %6s %5.1f %6s %s",
                                stationId,
                                if (assessment.direction.name.startsWith("REFERENCE")) "R>P" else "P>R",
                                point.radialIndex,
                                point.location.latitude,
                                point.location.longitude,
                                point.desiredFieldDbuvPerM?.let { number(it, 1) } ?: "NoData",
                                point.undesiredFieldDbuvPerM?.let { number(it, 1) } ?: "NoData",
                                point.diffractionLossDb?.let { number(it, 1) } ?: "NoData",
                                point.duDb?.let { number(it, 1) } ?: "NoData",
                                point.requiredDuDb,
                                point.marginDb?.let { number(it, 1) } ?: "NoData",
                                point.status.name,
                            ),
                        ),
                    )
                }
            }
        }

        add(heading("Artifact identity", minimumFollowingLines = 3))
        add(mono("INPUT SHA256 ${result.inputFingerprint}"))
        add(artifactBody("Contour status ${result.contour.status.name}; regulatory flag ${result.contour.regulatory}."))
        add(artifactBody("Exported on device at ${formatInstant(exportedAtEpochMillis)}."))
    }

    private fun paginate(lines: List<ReportLine>, result: BrazilDigitalTvRegulatoryStudyResult): List<Page> {
        val pages = mutableListOf<Page>()
        var current = mutableListOf<PositionedLine>()
        var y = TOP_Y

        fun beginPage() {
            current = mutableListOf()
            y = TOP_Y
        }

        fun finishPage() {
            if (current.isNotEmpty()) pages += Page(current)
            beginPage()
        }

        beginPage()
        lines.forEachIndexed { lineIndex, line ->
            val maxCharacters = ((PAGE_WIDTH - 2.0 * LEFT_MARGIN) / (line.size * line.widthFactor))
                .toInt()
                .coerceAtLeast(24)
            val wrapped = wrap(line.text, maxCharacters)
            val followingRequired = lines
                .drop(lineIndex + 1)
                .take(line.minimumFollowingLines)
                .sumOf { following ->
                    val followingMaximum = (
                        (PAGE_WIDTH - 2.0 * LEFT_MARGIN) /
                            (following.size * following.widthFactor)
                        ).toInt().coerceAtLeast(24)
                    following.before + wrap(following.text, followingMaximum).size * following.leading
                }
            val required = line.before + wrapped.size * line.leading + followingRequired
            if (y - required < BOTTOM_Y) finishPage()
            y -= line.before
            wrapped.forEach { text ->
                if (y - line.leading < BOTTOM_Y) finishPage()
                current += PositionedLine(text, LEFT_MARGIN + line.indent, y, line.font, line.size)
                y -= line.leading
            }
        }
        finishPage()
        return pages.mapIndexed { index, page ->
            val footer = "ATX Plan Android | ${result.inputFingerprint.take(16)}... | Page ${index + 1} of ${pages.size}"
            page.copy(
                lines = page.lines + PositionedLine(footer, LEFT_MARGIN, 27.0, PdfFont.HELVETICA, 7.5),
            )
        }
    }

    private fun writePdf(pages: List<Page>, result: BrazilDigitalTvRegulatoryStudyResult): ByteArray {
        val objects = mutableMapOf<Int, ByteArray>()
        val firstPageObject = 6
        val pageReferences = pages.indices.joinToString(" ") { index -> "${firstPageObject + index * 2} 0 R" }
        objects[1] = ascii("<< /Type /Catalog /Pages 2 0 R >>")
        objects[2] = ascii("<< /Type /Pages /Count ${pages.size} /Kids [$pageReferences] >>")
        objects[3] = ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        objects[4] = ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        objects[5] = ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
        pages.forEachIndexed { index, page ->
            val pageObject = firstPageObject + index * 2
            val contentObject = pageObject + 1
            val content = pageContent(page)
            objects[pageObject] = ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_WIDTH $PAGE_HEIGHT] " +
                    "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> " +
                    "/Contents $contentObject 0 R >>",
            )
            objects[contentObject] = concat(
                ascii("<< /Length ${content.size} >>\nstream\n"),
                content,
                ascii("\nendstream"),
            )
        }
        val infoObject = firstPageObject + pages.size * 2
        objects[infoObject] = windows(
            "<< /Title (${pdfText(result.projectName)} - Broadcast regulatory study) " +
                "/Author (ATX Plan Android) /Creator (ATX Plan Android) " +
                "/Subject (Bounded RF engineering evidence) >>",
        )

        val output = ByteArrayOutputStream(256 * 1024)
        output.write(ascii("%PDF-1.4\n"))
        output.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val offsets = IntArray(infoObject + 1)
        for (objectId in 1..infoObject) {
            offsets[objectId] = output.size()
            output.write(ascii("$objectId 0 obj\n"))
            output.write(checkNotNull(objects[objectId]))
            output.write(ascii("\nendobj\n"))
        }
        val xrefOffset = output.size()
        output.write(ascii("xref\n0 ${infoObject + 1}\n"))
        output.write(ascii("0000000000 65535 f \n"))
        for (objectId in 1..infoObject) {
            output.write(ascii(String.format(Locale.US, "%010d 00000 n \n", offsets[objectId])))
        }
        output.write(
            ascii(
                "trailer\n<< /Size ${infoObject + 1} /Root 1 0 R /Info $infoObject 0 R >>\n" +
                    "startxref\n$xrefOffset\n%%EOF\n",
            ),
        )
        return output.toByteArray()
    }

    private fun pageContent(page: Page): ByteArray {
        val output = ByteArrayOutputStream(16 * 1024)
        page.lines.forEach { line ->
            val font = when (line.font) {
                PdfFont.HELVETICA -> "F1"
                PdfFont.HELVETICA_BOLD -> "F2"
                PdfFont.COURIER -> "F3"
            }
            output.write(
                windows(
                    "BT /$font ${canonical(line.size)} Tf 1 0 0 1 ${canonical(line.x)} ${canonical(line.y)} Tm " +
                        "(${pdfText(line.text)}) Tj ET\n",
                ),
            )
        }
        return output.toByteArray()
    }

    private fun wrap(value: String, maximumCharacters: Int): List<String> {
        val normalized = normalizeText(value)
        if (normalized.length <= maximumCharacters) return listOf(normalized)
        val lines = mutableListOf<String>()
        var remaining = normalized
        while (remaining.length > maximumCharacters) {
            val split = remaining.lastIndexOf(' ', maximumCharacters).takeIf { it >= maximumCharacters / 2 }
                ?: maximumCharacters
            lines += remaining.substring(0, split).trimEnd()
            remaining = remaining.substring(split).trimStart()
        }
        if (remaining.isNotEmpty()) lines += remaining
        return lines.ifEmpty { listOf("") }
    }

    private fun pdfText(value: String): String = buildString {
        normalizeText(value).forEach { character ->
            when (character) {
                '\\', '(', ')' -> append('\\').append(character)
                else -> append(character)
            }
        }
    }

    private fun normalizeText(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val normalized = when (character) {
                '\u2013', '\u2014', '\u2212' -> '-'
                '\u00D7' -> 'x'
                '\u2018', '\u2019' -> '\''
                '\u201C', '\u201D' -> '"'
                '\t', '\n', '\r' -> ' '
                else -> character
            }
            if (normalized.code >= 0x20 && windows1252.newEncoder().canEncode(normalized)) {
                append(normalized)
            } else if (normalized.code >= 0x20) {
                append('?')
            }
        }
    }

    private fun concat(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        values.forEach(output::write)
    }.toByteArray()

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
    private fun windows(value: String): ByteArray = value.toByteArray(windows1252)
    private fun canonical(value: Double): String = String.format(Locale.US, "%.2f", value)
    private fun number(value: Double, decimals: Int): String {
        require(decimals in 0..9)
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun formatInstant(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))

    private fun title(value: String) = ReportLine(value, PdfFont.HELVETICA_BOLD, 19.0, 0.0, 24.0, 0.52, minimumFollowingLines = 2)
    private fun subtitle(value: String) = ReportLine(value, PdfFont.HELVETICA, 10.5, 0.0, 15.0, 0.50)
    private fun alert(value: String) = ReportLine(value, PdfFont.HELVETICA_BOLD, 10.5, 5.0, 15.0, 0.52)
    private fun heading(value: String, minimumFollowingLines: Int = 1) =
        ReportLine(value, PdfFont.HELVETICA_BOLD, 13.0, 12.0, 17.0, 0.52, minimumFollowingLines = minimumFollowingLines)
    private fun subheading(value: String) = ReportLine(value, PdfFont.HELVETICA_BOLD, 10.0, 6.0, 13.0, 0.52, minimumFollowingLines = 1)
    private fun body(value: String) = ReportLine(value, PdfFont.HELVETICA, 9.2, 1.0, 12.0, 0.50)
    private fun artifactBody(value: String) = ReportLine(value, PdfFont.HELVETICA, 9.2, 3.0, 12.0, 0.50)
    private fun small(value: String) = ReportLine(value, PdfFont.HELVETICA, 8.0, 0.0, 10.0, 0.50, 8.0)
    private fun bullet(value: String) = body("- $value").copy(indent = 8.0)
    private fun keyValue(key: String, value: String) = body("$key: $value")
    private fun mono(value: String) = ReportLine(value, PdfFont.COURIER, 7.3, 0.0, 8.25, 0.60)

    private data class ReportLine(
        val text: String,
        val font: PdfFont,
        val size: Double,
        val before: Double,
        val leading: Double,
        val widthFactor: Double,
        val indent: Double = 0.0,
        val minimumFollowingLines: Int = 0,
    )

    private data class PositionedLine(
        val text: String,
        val x: Double,
        val y: Double,
        val font: PdfFont,
        val size: Double,
    )

    private data class Page(val lines: List<PositionedLine>)

    private enum class PdfFont {
        HELVETICA,
        HELVETICA_BOLD,
        COURIER,
    }
}
