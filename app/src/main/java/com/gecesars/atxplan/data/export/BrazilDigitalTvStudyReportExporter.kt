package com.gecesars.atxplan.data.export

import com.gecesars.atxplan.domain.anatel.OfficialAnatelBasicPlanSource
import com.gecesars.atxplan.domain.contour.BrazilDigitalTvRegulatoryStudyResult
import com.gecesars.atxplan.domain.contour.RegulatoryDuAssessment
import com.gecesars.atxplan.domain.contour.RegulatoryDuPointEvidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Self-contained, bounded HTML evidence report for an already calculated study result. */
object BrazilDigitalTvStudyReportExporter {
    const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024

    fun export(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ByteArray {
        require(exportedAtEpochMillis >= 0L) { "The report export timestamp cannot be negative." }
        val html = buildReport(result, exportedAtEpochMillis)
        val bytes = html.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_OUTPUT_BYTES) { "The regulatory HTML report exceeds 2 MiB." }
        return bytes
    }

    private fun buildReport(
        result: BrazilDigitalTvRegulatoryStudyResult,
        exportedAtEpochMillis: Long,
    ): String = buildString(64 * 1024) {
        append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
        append("<title>").appendHtml(result.projectName).append(" — Digital TV regulatory study</title>\n")
        append("<style>")
        append("body{font:13px/1.35 system-ui,sans-serif;color:#17202a;margin:18px;max-width:1180px}")
        append("h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:18px 0 6px}")
        append("p{margin:5px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:6px}")
        append(".box{border:1px solid #ccd4da;border-radius:6px;padding:8px}.ok{color:#087f5b}.warn{color:#a15c00}")
        append("table{border-collapse:collapse;width:100%;font-size:11px}th,td{border:1px solid #d9dfe3;padding:3px 5px;text-align:left}")
        append("th{background:#eef3f5;position:sticky;top:0}.num{text-align:right;font-variant-numeric:tabular-nums}")
        append("code{font-size:11px;overflow-wrap:anywhere}@media print{body{margin:9mm}th{position:static}}")
        append("</style></head><body>\n")
        append("<h1>Brazil Digital TV Regulatory Study</h1>\n")
        append("<p><strong>").appendHtml(result.projectName).append("</strong> · Channel ")
            .append(result.channel).append(" · ").append(number(result.frequencyMHz, 3)).append(" MHz</p>\n")
        append("<p class=\"").append(if (result.filingReady) "ok" else "warn").append("\"><strong>")
            .append(if (result.filingReady) "Calculation gates passed" else "Not filing-ready")
            .append("</strong></p>\n")
        append("<p>This project was created independently. No transmitter, site, antenna, ERP, or height value was copied from the Anatel Basic Plan. Catalog records are read-only external references for viability and interference screening.</p>\n")

        append("<h2>Project-owned inputs</h2><div class=\"grid\">\n")
        item("Project ID", result.projectId)
        item("Site", "${result.siteName} (${result.siteId})")
        item("Sector", "${result.sectorName} (${result.sectorId})")
        item("Coordinates", "${number(result.center.latitude, 6)}, ${number(result.center.longitude, 6)} (EPSG:4326)")
        item("Requested study radius", "${number(result.radiusKm, 2)} km")
        item("Peak uniform-radial ERP", "${number(result.peakErpKw, 6)} kW")
        item("Antenna height", "${number(result.antennaHeightAglM, 2)} m AGL")
        item("Receiver height", "${number(result.receiverHeightAglM, 2)} m AGL")
        append("</div>\n")

        append("<h2>Methods and regulatory criteria</h2><div class=\"grid\">\n")
        item("Protected contour", "${result.p1546ModelId}; ${result.protectedStatisticalBasis}")
        item("Protected field threshold", "${number(result.protectedThresholdDbuvPerM, 2)} dBµV/m")
        item("Interference diffraction", result.diffractionModelId)
        item("Terrain profile spacing", "${number(result.terrainSpacingM, 1)} m")
        item("D/U criteria", "Cochannel 19 dB; first adjacent −36 dB")
        item("Effective Earth radius", "k = 4/3 on P.526 paths")
        item(
            "Coverage surface",
            "${result.coverageSurface.width} × ${result.coverageSurface.height}; " +
                "${result.coverageSurface.statisticalBasis}; operational visualization",
        )
        item(
            "Coverage pattern",
            if (result.coverageSurface.directionalPatternApplied) {
                "Verified HRP and VRP applied"
            } else {
                "Omnidirectional fallback disclosed"
            },
        )
        append("</div>\n")

        append("<h2>Dataset provenance</h2><div class=\"grid\">\n")
        item("Terrain dataset", "${result.terrainProvenance.datasetTitle} (${result.terrainProvenance.dataType})")
        item("Terrain artifact count", result.terrainProvenance.allArtifacts.size.toString())
        item("Terrain sampling", "${result.terrainProvenance.nominalResolutionM} m nominal; ${result.terrainProvenance.sampleMethod}")
        item("Terrain license", result.terrainProvenance.licenseTitle)
        item("Anatel archive SHA-256", result.anatelArchiveSha256 ?: "NoData", code = true)
        item("Anatel index", result.anatelIndexArtifactName ?: "NoData")
        item("Anatel acquisition epoch", result.anatelArchiveAcquiredAtEpochMillis?.toString() ?: "NoData")
        append("</div>\n")
        append("<table><thead><tr><th>Terrain artifact</th><th>SHA-256</th><th>Acquired</th><th>Artifact URL</th></tr></thead><tbody>\n")
        result.terrainProvenance.allArtifacts.sortedBy { artifact -> artifact.relativePath }
            .forEach { artifact ->
                append("<tr>")
                appendCell(artifact.relativePath)
                append("<td><code>").appendHtml(artifact.sha256).append("</code></td>")
                appendCell(artifact.acquiredAt ?: "NoData")
                appendCell(artifact.artifactUrl)
                append("</tr>\n")
            }
        append("</tbody></table>\n")
        append("<p>Terrain attribution: ").appendHtml(result.terrainProvenance.attribution).append("</p>\n")
        append("<p>Terrain source: <a href=\"").appendHtml(result.terrainProvenance.sourceUrl)
            .append("\">").appendHtml(result.terrainProvenance.sourceUrl).append("</a></p>\n")
        append("<p>Anatel reference source: <a href=\"").appendHtml(OfficialAnatelBasicPlanSource.LANDING_PAGE_URL)
            .append("\">").appendHtml(OfficialAnatelBasicPlanSource.LANDING_PAGE_URL).append("</a></p>\n")

        appendMessages("Blocking conditions", result.blockers)
        appendMessages("Warnings and assumptions", result.warnings)

        append("<h2>Protected-contour radial evidence</h2>\n")
        append("<table><thead><tr><th>Azimuth</th><th>Distance</th><th>HNMT</th><th>Desired field</th><th>Status</th><th>Evidence note</th></tr></thead><tbody>\n")
        result.radialEvidence.sortedBy { it.azimuthDegrees }.forEach { radial ->
            append("<tr><td class=\"num\">").append(number(radial.azimuthDegrees, 1)).append("°</td>")
            appendCell(radial.distanceKm?.let { "${number(it, 4)} km" } ?: "NoData", numeric = true)
            appendCell(radial.hnmtM?.let { "${number(it, 2)} m" } ?: "NoData", numeric = true)
            appendCell(radial.desiredFieldDbuvPerM?.let { "${number(it, 3)} dBµV/m" } ?: "NoData", numeric = true)
            appendCell(radial.status.name)
            appendCell(radial.warning.orEmpty())
            append("</tr>\n")
        }
        append("</tbody></table>\n")

        append("<h2>Anatel reference-station D/U screening</h2>\n")
        append("<p>").append(result.referenceStationCount).append(" calculation-ready reference station(s); ")
            .append(result.duAssessments.size).append(" D/U assessment(s).</p>\n")
        append("<table><thead><tr><th>Origin / ID</th><th>Location</th><th>Channel</th><th>ERP / height</th><th>Relation</th><th>Required D/U</th><th>Worst D/U</th><th>Pass / fail / NoData</th><th>Status</th></tr></thead><tbody>\n")
        result.duAssessments.forEach { assessment -> appendDuRow(assessment) }
        append("</tbody></table>\n")
        if (result.duAssessments.isNotEmpty()) {
            append("<h2>D/U protected-boundary point evidence</h2>\n")
            append("<table><thead><tr><th>Reference</th><th>Radial</th><th>Coordinates</th><th>Desired</th><th>Undesired</th><th>Diffraction</th><th>D/U</th><th>Required</th><th>Status</th></tr></thead><tbody>\n")
            result.duAssessments.forEach { assessment ->
                assessment.points.forEach { point -> appendDuPointRow(assessment, point) }
            }
            append("</tbody></table>\n")
        }

        append("<h2>Artifact identity</h2>\n")
        append("<p>Input fingerprint: <code>").appendHtml(result.inputFingerprint).append("</code></p>\n")
        append("<p>Contour geometry status: ").appendHtml(result.contour.status.name)
            .append("; regulatory flag: ").append(result.contour.regulatory).append(".</p>\n")
        append("<p>Exported on device at ").appendHtml(formatInstant(exportedAtEpochMillis)).append(".</p>\n")
        append("</body></html>\n")
    }

    private fun StringBuilder.item(label: String, value: String, code: Boolean = false) {
        append("<div class=\"box\"><strong>").appendHtml(label).append("</strong><br>")
        if (code) append("<code>")
        appendHtml(value)
        if (code) append("</code>")
        append("</div>\n")
    }

    private fun StringBuilder.appendMessages(title: String, messages: List<String>) {
        append("<h2>").appendHtml(title).append("</h2>")
        if (messages.isEmpty()) {
            append("<p>None.</p>\n")
        } else {
            append("<ul>")
            messages.forEach { message -> append("<li>").appendHtml(message).append("</li>") }
            append("</ul>\n")
        }
    }

    private fun StringBuilder.appendDuRow(assessment: RegulatoryDuAssessment) {
        val station = assessment.station
        append("<tr>")
        appendCell("${station.origin.name} / ${station.basicPlanId ?: station.sourceRowId}")
        appendCell("${station.municipalityName ?: "NoData"}; ${number(station.latitude, 6)}, ${number(station.longitude, 6)}")
        appendCell("${station.channel} / ${number(station.frequencyMHz, 3)} MHz", numeric = true)
        appendCell("${number(station.erpKw, 4)} kW / ${number(station.antennaHeightM, 1)} m", numeric = true)
        appendCell(assessment.channelRelation)
        appendCell("${number(assessment.requiredDuDb, 1)} dB", numeric = true)
        appendCell(assessment.worstDuDb?.let { "${number(it, 2)} dB" } ?: "NoData", numeric = true)
        appendCell("${assessment.passingPointCount} / ${assessment.failingPointCount} / ${assessment.noDataPointCount}", numeric = true)
        appendCell(assessment.status.name)
        append("</tr>\n")
    }

    private fun StringBuilder.appendDuPointRow(
        assessment: RegulatoryDuAssessment,
        point: RegulatoryDuPointEvidence,
    ) {
        append("<tr>")
        appendCell(assessment.station.basicPlanId ?: assessment.station.sourceRowId)
        appendCell(point.radialIndex.toString(), numeric = true)
        appendCell(
            "${number(point.location.latitude, 6)}, ${number(point.location.longitude, 6)}",
            numeric = true,
        )
        appendCell(point.desiredFieldDbuvPerM?.let { number(it, 3) } ?: "NoData", numeric = true)
        appendCell(point.undesiredFieldDbuvPerM?.let { number(it, 3) } ?: "NoData", numeric = true)
        appendCell(point.diffractionLossDb?.let { number(it, 3) } ?: "NoData", numeric = true)
        appendCell(point.duDb?.let { number(it, 3) } ?: "NoData", numeric = true)
        appendCell(number(point.requiredDuDb, 1), numeric = true)
        appendCell(point.status.name)
        append("</tr>\n")
    }

    private fun StringBuilder.appendCell(value: String, numeric: Boolean = false) {
        append("<td")
        if (numeric) append(" class=\"num\"")
        append(">").appendHtml(value).append("</td>")
    }

    private fun StringBuilder.appendHtml(value: String): StringBuilder {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
        return this
    }

    private fun number(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun formatInstant(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epochMillis))
}
