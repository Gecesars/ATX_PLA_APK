package com.gecesars.atxplan.data.export

import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.contour.RegulatoryDuAssessment
import com.gecesars.atxplan.domain.contour.RegulatoryDuPointEvidence
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln

/** Bounded Office Open XML workbook with numerical study evidence and explicit NoData cells. */
object BrazilDigitalTvStudyXlsxExporter {
    const val MAX_OUTPUT_BYTES = 12 * 1024 * 1024
    private const val MAX_CELL_TEXT_CHARS = 32_767

    fun export(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(exportedAtEpochMillis >= 0L) { "The workbook export timestamp cannot be negative." }
        val sheets = sheets(result, exportedAtEpochMillis)
        val output = ByteArrayOutputStream(512 * 1024)
        ZipOutputStream(output).use { zip ->
            zip.setLevel(6)
            zip.writeEntry("[Content_Types].xml", contentTypes(sheets.size))
            zip.writeEntry("_rels/.rels", packageRelationships())
            zip.writeEntry("docProps/core.xml", coreProperties(result, exportedAtEpochMillis))
            zip.writeEntry("docProps/app.xml", applicationProperties(sheets))
            zip.writeEntry("xl/workbook.xml", workbook(sheets))
            zip.writeEntry("xl/_rels/workbook.xml.rels", workbookRelationships(sheets.size))
            zip.writeEntry("xl/styles.xml", styles())
            sheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(stableEntry("xl/worksheets/sheet${index + 1}.xml"))
                writeWorksheet(zip, sheet)
                zip.closeEntry()
            }
        }
        return output.toByteArray().also { bytes ->
            require(bytes.size <= MAX_OUTPUT_BYTES) {
                "The regulatory workbook exceeds the 12 MiB export limit."
            }
        }
    }

    private fun sheets(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long,
    ): List<Worksheet> = listOf(
        summarySheet(result, exportedAtEpochMillis),
        radialSheet(result),
        duSummarySheet(result),
        duPointSheet(result),
        coverageSheet(result),
        provenanceSheet(result),
    )

    private fun summarySheet(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long,
    ) = Worksheet(
        name = "Summary",
        headers = listOf("Field", "Value", "Unit or status"),
        widths = listOf(31.0, 54.0, 28.0),
        rows = sequence {
            yield(textRow("Report", "Brazil Digital TV Regulatory Study", "Engineering evidence"))
            yield(textRow("Project", result.projectName, result.projectId))
            yield(textRow("Site", result.siteName, result.siteId))
            yield(textRow("Sector", result.sectorName, result.sectorId))
            yield(mixedRow("Channel", number(result.channel.toDouble()), text("Digital TV")))
            yield(mixedRow("Frequency", number(result.frequencyMHz), text("MHz")))
            yield(mixedRow("Latitude", number(result.center.latitude), text("EPSG:4326")))
            yield(mixedRow("Longitude", number(result.center.longitude), text("EPSG:4326")))
            yield(mixedRow("Study radius", number(result.radiusKm), text("km")))
            yield(mixedRow("Peak ERP", number(result.peakErpKw), text("kW")))
            yield(mixedRow("Antenna height", number(result.antennaHeightAglM), text("m AGL")))
            yield(mixedRow("Receiver height", number(result.receiverHeightAglM), text("m AGL")))
            yield(mixedRow("Protected threshold", number(result.protectedThresholdDbuvPerM), text("dBµV/m")))
            yield(textRow("Protected statistical basis", result.protectedStatisticalBasis, result.contour.status.name))
            yield(textRow("Propagation model", result.p1546ModelId, "CPU-only"))
            yield(textRow("Diffraction model", result.diffractionModelId, "D/U paths"))
            yield(textRow("D/U criteria", "Cochannel 19 dB; first adjacent -36 dB", "Protected boundary"))
            yield(textRow("Coverage surface", "${result.coverageSurface.width} x ${result.coverageSurface.height}", result.coverageSurface.statisticalBasis))
            yield(textRow("Coverage pattern", if (result.coverageSurface.directionalPatternApplied) "Verified HRP and VRP" else "Omnidirectional fallback", "Operational visualization"))
            yield(mixedRow("Reference stations", number(result.referenceStationCount.toDouble()), text("Anatel Basic Plan")))
            yield(textRow("Filing gate", if (result.filingReady) "PASSED" else "NOT FILING-READY", result.contour.status.name))
            yield(textRow("Input fingerprint", result.inputFingerprint, "SHA-256"))
            yield(textRow("Exported at", formatInstant(exportedAtEpochMillis), "UTC"))
            result.blockers.forEachIndexed { index, blocker ->
                yield(textRow("Blocker ${index + 1}", blocker, "BLOCKING"))
            }
            result.warnings.forEachIndexed { index, warning ->
                yield(textRow("Warning ${index + 1}", warning, "ASSUMPTION"))
            }
        },
    )

    private fun radialSheet(result: BrazilDigitalTvRegulatoryStudyResult) = Worksheet(
        name = "Protected Radials",
        headers = listOf(
            "Azimuth (deg)",
            "Distance (km)",
            "HNMT (m)",
            "Desired field (dBµV/m)",
            "Status",
            "Evidence note",
        ),
        widths = listOf(15.0, 16.0, 14.0, 24.0, 18.0, 68.0),
        rows = result.radialEvidence.sortedBy { it.azimuthDegrees }.asSequence().map { radial ->
            listOf(
                number(radial.azimuthDegrees),
                numberOrNoData(radial.distanceKm),
                numberOrNoData(radial.hnmtM),
                numberOrNoData(radial.desiredFieldDbuvPerM),
                text(radial.status.name),
                text(radial.warning.orEmpty()),
            )
        },
    )

    private fun duSummarySheet(result: BrazilDigitalTvRegulatoryStudyResult) = Worksheet(
        name = "D-U Summary",
        headers = listOf(
            "Reference ID",
            "Municipality",
            "Channel",
            "Frequency (MHz)",
            "Relation",
            "Required D/U (dB)",
            "Worst D/U (dB)",
            "Passing points",
            "Failing points",
            "NoData points",
            "Status",
        ),
        widths = listOf(24.0, 28.0, 10.0, 17.0, 18.0, 20.0, 18.0, 16.0, 16.0, 16.0, 15.0),
        rows = result.duAssessments.asSequence().map { assessment ->
            listOf(
                text(assessment.station.basicPlanId ?: assessment.station.sourceRowId),
                text(assessment.station.municipalityName ?: "NoData"),
                number(assessment.station.channel.toDouble()),
                number(assessment.station.frequencyMHz),
                text(assessment.channelRelation),
                number(assessment.requiredDuDb),
                numberOrNoData(assessment.worstDuDb),
                number(assessment.passingPointCount.toDouble()),
                number(assessment.failingPointCount.toDouble()),
                number(assessment.noDataPointCount.toDouble()),
                text(assessment.status.name),
            )
        },
    )

    private fun duPointSheet(result: BrazilDigitalTvRegulatoryStudyResult) = Worksheet(
        name = "D-U Points",
        headers = listOf(
            "Reference ID",
            "Radial index",
            "Latitude",
            "Longitude",
            "Desired (dBµV/m)",
            "Undesired (dBµV/m)",
            "Diffraction loss (dB)",
            "D/U (dB)",
            "Required D/U (dB)",
            "Status",
        ),
        widths = listOf(24.0, 14.0, 15.0, 15.0, 21.0, 23.0, 23.0, 14.0, 20.0, 14.0),
        rows = result.duAssessments.asSequence().flatMap { assessment ->
            assessment.points.asSequence().map { point -> duPointRow(assessment, point) }
        },
    )

    private fun duPointRow(
        assessment: RegulatoryDuAssessment,
        point: RegulatoryDuPointEvidence,
    ): List<Cell> = listOf(
        text(assessment.station.basicPlanId ?: assessment.station.sourceRowId),
        number(point.radialIndex.toDouble()),
        number(point.location.latitude),
        number(point.location.longitude),
        numberOrNoData(point.desiredFieldDbuvPerM),
        numberOrNoData(point.undesiredFieldDbuvPerM),
        numberOrNoData(point.diffractionLossDb),
        numberOrNoData(point.duDb),
        number(point.requiredDuDb),
        text(point.status.name),
    )

    private fun coverageSheet(result: BrazilDigitalTvRegulatoryStudyResult): Worksheet {
        val surface = result.coverageSurface
        val northY = mercatorY(surface.bounds.northLatitude)
        val southY = mercatorY(surface.bounds.southLatitude)
        return Worksheet(
            name = "Coverage Values",
            headers = listOf("Row", "Column", "Latitude", "Longitude", "Field (dBµV/m)", "State"),
            widths = listOf(10.0, 10.0, 15.0, 15.0, 21.0, 12.0),
            rows = sequence {
                repeat(surface.height) { row ->
                    val rowFraction = row.toDouble() / (surface.height - 1)
                    val latitude = inverseMercatorY(northY + (southY - northY) * rowFraction)
                    repeat(surface.width) { column ->
                        val columnFraction = column.toDouble() / (surface.width - 1)
                        val longitude = surface.bounds.westLongitude +
                            (surface.bounds.eastLongitude - surface.bounds.westLongitude) * columnFraction
                        val value = surface.valueAt(column, row)
                        yield(
                            listOf(
                                number(row.toDouble()),
                                number(column.toDouble()),
                                number(latitude),
                                number(longitude),
                                numberOrNoData(value),
                                text(if (value == null) "NoData" else "CALCULATED"),
                            ),
                        )
                    }
                }
            },
        )
    }

    private fun provenanceSheet(result: BrazilDigitalTvRegulatoryStudyResult) = Worksheet(
        name = "Provenance",
        headers = listOf("Dataset or artifact", "Type", "Relative path", "SHA-256", "Acquired", "Source URL", "License or attribution"),
        widths = listOf(36.0, 24.0, 52.0, 68.0, 25.0, 64.0, 60.0),
        rows = sequence {
            result.terrainProvenance.allArtifacts.sortedBy { it.relativePath }.forEach { artifact ->
                yield(
                    listOf(
                        text(result.terrainProvenance.datasetTitle),
                        text(result.terrainProvenance.dataType),
                        text(artifact.relativePath),
                        text(artifact.sha256),
                        text(artifact.acquiredAt ?: "NoData"),
                        text(artifact.artifactUrl),
                        text("${result.terrainProvenance.licenseTitle}; ${result.terrainProvenance.attribution}"),
                    ),
                )
            }
            yield(
                listOf(
                    text("Anatel Basic Plan archive"),
                    text("READ_ONLY_REFERENCE"),
                    text(result.anatelIndexArtifactName ?: "NoData"),
                    text(result.anatelArchiveSha256 ?: "NoData"),
                    text(result.anatelArchiveAcquiredAtEpochMillis?.toString() ?: "NoData"),
                    text("https://www.gov.br/anatel/pt-br/regulado/radiodifusao/planos-basicos"),
                    text("Official Anatel source; project transmitter fields remain independent"),
                ),
            )
        },
    )

    private fun writeWorksheet(zip: ZipOutputStream, sheet: Worksheet) {
        zip.writeUtf8("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        zip.writeUtf8("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        zip.writeUtf8("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        zip.writeUtf8("<cols>")
        sheet.widths.forEachIndexed { index, width ->
            zip.writeUtf8("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        zip.writeUtf8("</cols><sheetData>")
        writeRow(zip, 1, sheet.headers.map(::text), header = true)
        var rowNumber = 2
        sheet.rows.forEach { row ->
            require(row.size == sheet.headers.size) { "Worksheet ${sheet.name} contains a malformed row." }
            require(rowNumber <= 1_048_576) { "Worksheet ${sheet.name} exceeds the Excel row limit." }
            writeRow(zip, rowNumber, row, header = false)
            rowNumber += 1
        }
        zip.writeUtf8("</sheetData>")
        if (rowNumber > 2) {
            zip.writeUtf8("<autoFilter ref=\"A1:${columnName(sheet.headers.size)}${rowNumber - 1}\"/>")
        }
        zip.writeUtf8("<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>")
        zip.writeUtf8("</worksheet>")
    }

    private fun writeRow(zip: ZipOutputStream, rowNumber: Int, cells: List<Cell>, header: Boolean) {
        zip.writeUtf8("<row r=\"$rowNumber\">")
        cells.forEachIndexed { index, cell ->
            val reference = "${columnName(index + 1)}$rowNumber"
            when (cell) {
                is Cell.Number -> zip.writeUtf8(
                    "<c r=\"$reference\" s=\"${if (header) 1 else 0}\" t=\"n\"><v>${canonicalNumber(cell.value)}</v></c>",
                )
                is Cell.Text -> zip.writeUtf8(
                    "<c r=\"$reference\" s=\"${if (header) 1 else 0}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(safeText(cell.value))}</t></is></c>",
                )
            }
        }
        zip.writeUtf8("</row>")
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        repeat(sheetCount) { index ->
            append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun packageRelationships() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private fun workbook(sheets: List<Worksheet>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><workbookPr date1904=\"false\"/><sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"").append(xml(sheet.name)).append("\" sheetId=\"")
                .append(index + 1).append("\" r:id=\"rId").append(index + 1).append("\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelationships(sheetCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        repeat(sheetCount) { index ->
            append("<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun styles() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="10"/><name val="Aptos"/><family val="2"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="10"/><name val="Aptos Display"/><family val="2"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF16324F"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    private fun coreProperties(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long,
    ): String {
        val instant = formatInstant(exportedAtEpochMillis)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${xml(result.projectName)} - Digital TV regulatory study</dc:title><dc:creator>ATX Plan Android</dc:creator><dc:description>Bounded engineering evidence workbook; Basic Plan records are read-only references.</dc:description><dcterms:created xsi:type="dcterms:W3CDTF">$instant</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$instant</dcterms:modified></cp:coreProperties>"""
    }

    private fun applicationProperties(sheets: List<Worksheet>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>ATX Plan Android</Application><HeadingPairs><vt:vector size=\"2\" baseType=\"variant\"><vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>${sheets.size}</vt:i4></vt:variant></vt:vector></HeadingPairs><TitlesOfParts><vt:vector size=\"${sheets.size}\" baseType=\"lpstr\">")
        sheets.forEach { sheet -> append("<vt:lpstr>${xml(sheet.name)}</vt:lpstr>") }
        append("</vt:vector></TitlesOfParts></Properties>")
    }

    private fun ZipOutputStream.writeEntry(path: String, content: String) {
        putNextEntry(stableEntry(path))
        writeUtf8(content)
        closeEntry()
    }

    private fun ZipOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private fun stableEntry(path: String) = ZipEntry(path).apply { time = 0L }

    private fun columnName(oneBasedIndex: Int): String {
        require(oneBasedIndex in 1..16_384)
        var value = oneBasedIndex
        val result = StringBuilder()
        while (value > 0) {
            value -= 1
            result.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return result.reverse().toString()
    }

    private fun textRow(first: String, second: String, third: String): List<Cell> =
        listOf(text(first), text(second), text(third))

    private fun mixedRow(first: String, second: Cell, third: Cell): List<Cell> =
        listOf(text(first), second, third)

    private fun text(value: String): Cell = Cell.Text(value)

    private fun number(value: Double): Cell {
        require(value.isFinite()) { "Workbook numeric values must be finite." }
        return Cell.Number(value)
    }

    private fun numberOrNoData(value: Double?): Cell = value?.let(::number) ?: text("NoData")

    private fun canonicalNumber(value: Double): String =
        if (value == 0.0) "0" else java.lang.Double.toString(value)

    private fun safeText(value: String): String {
        val bounded = value.take(MAX_CELL_TEXT_CHARS).map { character ->
            if (character == '\t' || character == '\n' || character == '\r' || character.code >= 0x20) {
                character
            } else {
                '�'
            }
        }.joinToString("")
        val formulaLike = bounded.firstOrNull()?.let { first ->
            first in charArrayOf('=', '+', '-', '@')
        } == true
        return if (formulaLike) "'$bounded" else bounded
    }

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun mercatorY(latitude: Double): Double {
        val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - ln(kotlin.math.tan(radians) + 1.0 / kotlin.math.cos(radians)) / Math.PI) / 2.0
    }

    private fun inverseMercatorY(y: Double): Double =
        Math.toDegrees(2.0 * atan(exp(Math.PI * (1.0 - 2.0 * y))) - Math.PI / 2.0)

    private fun formatInstant(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))

    private data class Worksheet(
        val name: String,
        val headers: List<String>,
        val widths: List<Double>,
        val rows: Sequence<List<Cell>>,
    ) {
        init {
            require(name.isNotBlank() && name.length <= 31)
            require(headers.isNotEmpty() && headers.size == widths.size)
            require(widths.all { it.isFinite() && it in 5.0..80.0 })
        }
    }

    private sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: Double) : Cell
    }
}
