package com.gecesars.atxplan.data.export

import com.gecesars.atxplan.domain.contour.ContourPurpose
import com.gecesars.atxplan.domain.contour.ContourRadial
import com.gecesars.atxplan.domain.contour.ContourStatus
import com.gecesars.atxplan.domain.contour.ServiceContourOverlay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32

data class ServiceContourKmzExportSummary(
    val byteCount: Int,
    val sha256: String,
    val includedOverlayCount: Int,
    val omittedNoDataOverlayCount: Int,
    val warnings: List<String>,
)

/**
 * Deterministic, CPU-only KMZ export for already calculated service-contour overlays.
 *
 * This exporter does not calculate, approve, or reinterpret RF results. It preserves the overlay
 * classification and evidence, writes WGS 84 coordinates in longitude/latitude order, and records
 * every NoData omission in the bundled manifest. The output stream remains owned by the caller.
 */
class ServiceContourKmzExporter(
    private val maximumOutputBytes: Int = MAX_OUTPUT_BYTES,
) {
    init {
        require(maximumOutputBytes in 1..MAX_OUTPUT_BYTES) {
            "The KMZ output limit must be between 1 byte and 16 MiB."
        }
    }

    fun export(overlays: List<ServiceContourOverlay>): ByteArray = buildArchive(overlays).bytes

    fun write(
        overlays: List<ServiceContourOverlay>,
        output: OutputStream,
    ): ServiceContourKmzExportSummary {
        val archive = buildArchive(overlays)
        output.write(archive.bytes)
        return ServiceContourKmzExportSummary(
            byteCount = archive.bytes.size,
            sha256 = sha256(archive.bytes),
            includedOverlayCount = archive.includedOverlayCount,
            omittedNoDataOverlayCount = archive.omittedNoDataOverlayCount,
            warnings = archive.warnings,
        )
    }

    private fun buildArchive(overlays: List<ServiceContourOverlay>): BuiltArchive {
        val sortedOverlays = validateAndSort(overlays)
        val noDataCount = sortedOverlays.count { overlay -> overlay.status == ContourStatus.NO_DATA }
        val exportWarnings = if (noDataCount == 0) {
            emptyList()
        } else {
            listOf(
                "$noDataCount NoData overlay${if (noDataCount == 1) " is" else "s are"} recorded in manifest.json and omitted from doc.kml.",
            )
        }
        val kmlBytes = buildKml(sortedOverlays).toByteArray(Charsets.UTF_8)
        val manifestBytes = buildManifest(
            overlays = sortedOverlays,
            kmlBytes = kmlBytes,
            exportWarnings = exportWarnings,
        ).toByteArray(Charsets.UTF_8)
        require(kmlBytes.size <= MAX_ENTRY_BYTES && manifestBytes.size <= MAX_ENTRY_BYTES) {
            "A KMZ entry exceeds the 16 MiB hard limit."
        }
        val bytes = DeterministicStoredZip.build(
            entries = listOf(
                StoredZipEntry(DOC_KML_ENTRY, kmlBytes),
                StoredZipEntry(MANIFEST_ENTRY, manifestBytes),
            ),
            maximumOutputBytes = maximumOutputBytes,
        )
        return BuiltArchive(
            bytes = bytes,
            includedOverlayCount = sortedOverlays.size - noDataCount,
            omittedNoDataOverlayCount = noDataCount,
            warnings = exportWarnings,
        )
    }

    private fun validateAndSort(overlays: List<ServiceContourOverlay>): List<ServiceContourOverlay> {
        require(overlays.size <= MAX_OVERLAYS) {
            "A KMZ export cannot contain more than $MAX_OVERLAYS overlays."
        }
        require(overlays.map(ServiceContourOverlay::id).distinct().size == overlays.size) {
            "A KMZ export cannot contain duplicate overlay IDs."
        }
        var totalRadials = 0
        var totalPoints = 0
        var totalTextChars = 0L
        overlays.forEachIndexed { overlayIndex, overlay ->
            val overlayLabel = "Overlay ${overlayIndex + 1}"
            require(overlay.radials.size <= MAX_RADIALS_PER_OVERLAY) {
                "$overlayLabel exceeds the $MAX_RADIALS_PER_OVERLAY-radial limit."
            }
            require(overlay.points.size <= MAX_POINTS_PER_OVERLAY) {
                "$overlayLabel exceeds the $MAX_POINTS_PER_OVERLAY-point limit."
            }
            require(overlay.warnings.size <= MAX_WARNINGS_PER_OVERLAY) {
                "$overlayLabel exceeds the $MAX_WARNINGS_PER_OVERLAY-warning limit."
            }
            if (overlay.status == ContourStatus.COMPLETE) {
                require(overlay.points.size >= MIN_POLYGON_POINTS) {
                    "A complete contour requires at least four KML polygon coordinates."
                }
            }
            val radialAzimuths = overlay.radials.map { radial -> normalizedZero(radial.azimuthDegrees) }
            require(radialAzimuths.distinct().size == radialAzimuths.size) {
                "$overlayLabel contains duplicate radial azimuths."
            }
            overlay.radials.forEach { radial ->
                require(radial.warnings.size <= MAX_WARNINGS_PER_RADIAL) {
                    "A contour radial exceeds the $MAX_WARNINGS_PER_RADIAL-warning limit."
                }
            }
            totalRadials += overlay.radials.size
            totalPoints += overlay.points.size
            val textValues = buildList {
                add(overlay.id)
                add(overlay.siteId)
                add(overlay.sectorId)
                add(overlay.statisticalBasis)
                add(overlay.model)
                add(overlay.rulesetId)
                add(overlay.sourceUrl)
                add(overlay.inputFingerprint)
                addAll(overlay.warnings)
                overlay.radials.forEach { radial -> addAll(radial.warnings) }
            }
            textValues.forEach { text ->
                requirePortableText(text)
                totalTextChars += text.length
            }
        }
        require(totalRadials <= MAX_TOTAL_RADIALS) {
            "A KMZ export cannot contain more than $MAX_TOTAL_RADIALS total radials."
        }
        require(totalPoints <= MAX_TOTAL_POINTS) {
            "A KMZ export cannot contain more than $MAX_TOTAL_POINTS total geometry points."
        }
        require(totalTextChars <= MAX_TOTAL_TEXT_CHARS) {
            "A KMZ export exceeds the $MAX_TOTAL_TEXT_CHARS-character metadata limit."
        }
        return overlays.sortedBy(ServiceContourOverlay::id)
    }

    private fun buildKml(overlays: List<ServiceContourOverlay>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        append("  <Document>\n")
        append("    <name>ATX Plan service contours</name>\n")
        appendKmlStyles()
        overlays.filterNot { overlay -> overlay.status == ContourStatus.NO_DATA }
            .forEachIndexed { index, overlay ->
                append("    <Placemark id=\"contour-")
                append((index + 1).toString().padStart(4, '0'))
                append("\">\n")
                append("      <name>").appendXml(overlay.id).append("</name>\n")
                if (overlay.warnings.isNotEmpty()) {
                    append("      <description>")
                    appendXml(overlay.warnings.sorted().joinToString(separator = "\n"))
                    append("</description>\n")
                }
                append("      <styleUrl>#").append(styleId(overlay)).append("</styleUrl>\n")
                append("      <ExtendedData>\n")
                appendKmlData("service", overlay.service.name)
                appendKmlData("purpose", overlay.purpose.name)
                appendKmlData("geometryStatus", overlay.status.name)
                appendKmlData("siteId", overlay.siteId)
                appendKmlData("sectorId", overlay.sectorId)
                appendKmlData("statisticalBasis", overlay.statisticalBasis)
                appendKmlData(
                    "thresholdDbuvPerM",
                    overlay.thresholdDbuvPerM?.let(::canonicalDouble) ?: "NoData",
                )
                appendKmlData("model", overlay.model)
                appendKmlData("rulesetId", overlay.rulesetId)
                appendKmlData("sourceUrl", overlay.sourceUrl)
                appendKmlData("regulatory", overlay.regulatory.toString())
                appendKmlData("inputFingerprint", overlay.inputFingerprint)
                append("      </ExtendedData>\n")
                if (overlay.purpose == ContourPurpose.PROTECTED &&
                    overlay.status == ContourStatus.COMPLETE
                ) {
                    append("      <Polygon>\n")
                    append("        <tessellate>1</tessellate>\n")
                    append("        <altitudeMode>clampToGround</altitudeMode>\n")
                    append("        <outerBoundaryIs><LinearRing><coordinates>\n")
                    appendKmlCoordinates(overlay)
                    append("        </coordinates></LinearRing></outerBoundaryIs>\n")
                    append("      </Polygon>\n")
                } else {
                    append("      <LineString>\n")
                    append("        <tessellate>1</tessellate>\n")
                    append("        <altitudeMode>clampToGround</altitudeMode>\n")
                    append("        <coordinates>\n")
                    appendKmlCoordinates(overlay)
                    append("        </coordinates>\n")
                    append("      </LineString>\n")
                }
                append("    </Placemark>\n")
            }
        append("  </Document>\n")
        append("</kml>\n")
    }

    private fun StringBuilder.appendKmlStyles() {
        append("    <Style id=\"protected-complete\">\n")
        append("      <LineStyle><color>ff7b8900</color><width>2</width></LineStyle>\n")
        append("      <PolyStyle><color>337b8900</color><fill>1</fill></PolyStyle>\n")
        append("    </Style>\n")
        append("    <Style id=\"protected-incomplete\">\n")
        append("      <LineStyle><color>ff7b8900</color><width>2</width></LineStyle>\n")
        append("    </Style>\n")
        append("    <Style id=\"screening\">\n")
        append("      <LineStyle><color>ff00b3ff</color><width>2</width></LineStyle>\n")
        append("    </Style>\n")
    }

    private fun StringBuilder.appendKmlData(name: String, value: String) {
        append("        <Data name=\"").append(name).append("\"><value>")
        appendXml(value)
        append("</value></Data>\n")
    }

    private fun StringBuilder.appendKmlCoordinates(overlay: ServiceContourOverlay) {
        overlay.points.forEach { point ->
            append("          ")
            append(canonicalDouble(point.longitude))
            append(',')
            append(canonicalDouble(point.latitude))
            append(",0\n")
        }
    }

    private fun buildManifest(
        overlays: List<ServiceContourOverlay>,
        kmlBytes: ByteArray,
        exportWarnings: List<String>,
    ): String = buildString {
        val includedCount = overlays.count { overlay -> overlay.status != ContourStatus.NO_DATA }
        val noDataCount = overlays.size - includedCount
        append("{\n")
        appendJsonStringProperty("schema", MANIFEST_SCHEMA, 2, trailingComma = true)
        appendJsonNumberProperty("version", MANIFEST_VERSION.toString(), 2, trailingComma = true)
        appendJsonStringProperty("coordinateReferenceSystem", "EPSG:4326", 2, trailingComma = true)
        appendJsonStringProperty("coordinateOrder", "longitude,latitude,altitude", 2, trailingComma = true)
        appendJsonStringProperty("zipTimestamp", "1980-01-01T00:00:00", 2, trailingComma = true)
        appendJsonNumberProperty("overlayCount", overlays.size.toString(), 2, trailingComma = true)
        appendJsonNumberProperty("includedOverlayCount", includedCount.toString(), 2, trailingComma = true)
        appendJsonNumberProperty("omittedNoDataOverlayCount", noDataCount.toString(), 2, trailingComma = true)
        append("  \"warnings\": ")
        appendJsonStringArray(exportWarnings)
        append(",\n")
        append("  \"entries\": {\n")
        append("    \"doc.kml\": {\n")
        appendJsonStringProperty("mediaType", KML_MEDIA_TYPE, 6, trailingComma = true)
        appendJsonNumberProperty("byteCount", kmlBytes.size.toString(), 6, trailingComma = true)
        appendJsonStringProperty("sha256", sha256(kmlBytes), 6, trailingComma = false)
        append("    }\n")
        append("  },\n")
        append("  \"overlays\": [\n")
        overlays.forEachIndexed { index, overlay ->
            appendOverlayManifest(overlay)
            if (index != overlays.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private fun StringBuilder.appendOverlayManifest(overlay: ServiceContourOverlay) {
        val included = overlay.status != ContourStatus.NO_DATA
        val geometryType = when {
            !included -> null
            overlay.purpose == ContourPurpose.PROTECTED && overlay.status == ContourStatus.COMPLETE ->
                "Polygon"
            else -> "LineString"
        }
        append("    {\n")
        appendJsonStringProperty("id", overlay.id, 6, trailingComma = true)
        appendJsonStringProperty("siteId", overlay.siteId, 6, trailingComma = true)
        appendJsonStringProperty("sectorId", overlay.sectorId, 6, trailingComma = true)
        appendJsonStringProperty("service", overlay.service.name, 6, trailingComma = true)
        appendJsonStringProperty("purpose", overlay.purpose.name, 6, trailingComma = true)
        appendJsonStringProperty("statisticalBasis", overlay.statisticalBasis, 6, trailingComma = true)
        appendJsonNullableNumberProperty(
            "thresholdDbuvPerM",
            overlay.thresholdDbuvPerM,
            6,
            trailingComma = true,
        )
        appendJsonStringProperty("status", overlay.status.name, 6, trailingComma = true)
        appendJsonStringProperty("model", overlay.model, 6, trailingComma = true)
        appendJsonStringProperty("rulesetId", overlay.rulesetId, 6, trailingComma = true)
        appendJsonStringProperty("sourceUrl", overlay.sourceUrl, 6, trailingComma = true)
        appendJsonBooleanProperty("regulatory", overlay.regulatory, 6, trailingComma = true)
        appendJsonStringProperty("inputFingerprint", overlay.inputFingerprint, 6, trailingComma = true)
        appendJsonBooleanProperty("includedInKml", included, 6, trailingComma = true)
        appendJsonNullableStringProperty(
            "omissionReason",
            if (included) null else NO_DATA_OMISSION_REASON,
            6,
            trailingComma = true,
        )
        appendJsonNullableStringProperty("geometryType", geometryType, 6, trailingComma = true)
        appendJsonNumberProperty("pointCount", overlay.points.size.toString(), 6, trailingComma = true)
        appendJsonNumberProperty("radialCount", overlay.radials.size.toString(), 6, trailingComma = true)
        append("      \"warnings\": ")
        appendJsonStringArray(overlay.warnings.sorted())
        append(",\n")
        append("      \"radials\": [\n")
        val sortedRadials = overlay.radials.sortedBy(ContourRadial::azimuthDegrees)
        sortedRadials.forEachIndexed { index, radial ->
            appendRadialManifest(radial)
            if (index != sortedRadials.lastIndex) append(',')
            append('\n')
        }
        append("      ]\n")
        append("    }")
    }

    private fun StringBuilder.appendRadialManifest(radial: ContourRadial) {
        append("        {\n")
        appendJsonNumberProperty(
            "azimuthDegrees",
            canonicalDouble(radial.azimuthDegrees),
            10,
            trailingComma = true,
        )
        appendJsonNullableNumberProperty("distanceKm", radial.distanceKm, 10, trailingComma = true)
        appendJsonNumberProperty("erpKw", canonicalDouble(radial.erpKw), 10, trailingComma = true)
        appendJsonNumberProperty(
            "effectiveHeightM",
            canonicalDouble(radial.effectiveHeightM),
            10,
            trailingComma = true,
        )
        appendJsonStringProperty("status", radial.status.name, 10, trailingComma = true)
        append("          \"warnings\": ")
        appendJsonStringArray(radial.warnings.sorted())
        append('\n')
        append("        }")
    }

    private fun StringBuilder.appendJsonStringProperty(
        name: String,
        value: String,
        indent: Int,
        trailingComma: Boolean,
    ) {
        append(" ".repeat(indent)).appendJsonString(name).append(": ").appendJsonString(value)
        if (trailingComma) append(',')
        append('\n')
    }

    private fun StringBuilder.appendJsonNullableStringProperty(
        name: String,
        value: String?,
        indent: Int,
        trailingComma: Boolean,
    ) {
        append(" ".repeat(indent)).appendJsonString(name).append(": ")
        if (value == null) append("null") else appendJsonString(value)
        if (trailingComma) append(',')
        append('\n')
    }

    private fun StringBuilder.appendJsonNumberProperty(
        name: String,
        value: String,
        indent: Int,
        trailingComma: Boolean,
    ) {
        append(" ".repeat(indent)).appendJsonString(name).append(": ").append(value)
        if (trailingComma) append(',')
        append('\n')
    }

    private fun StringBuilder.appendJsonNullableNumberProperty(
        name: String,
        value: Double?,
        indent: Int,
        trailingComma: Boolean,
    ) = appendJsonNumberProperty(
        name = name,
        value = value?.let(::canonicalDouble) ?: "null",
        indent = indent,
        trailingComma = trailingComma,
    )

    private fun StringBuilder.appendJsonBooleanProperty(
        name: String,
        value: Boolean,
        indent: Int,
        trailingComma: Boolean,
    ) = appendJsonNumberProperty(name, value.toString(), indent, trailingComma)

    private fun StringBuilder.appendJsonStringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            appendJsonString(value)
        }
        append(']')
    }

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
        return this
    }

    private fun StringBuilder.appendXml(value: String): StringBuilder {
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
        return this
    }

    private fun styleId(overlay: ServiceContourOverlay): String = when (overlay.purpose) {
        ContourPurpose.SCREENING -> "screening"
        ContourPurpose.PROTECTED -> if (overlay.status == ContourStatus.COMPLETE) {
            "protected-complete"
        } else {
            "protected-incomplete"
        }
    }

    private fun requirePortableText(value: String) {
        require(value.length <= MAX_TEXT_VALUE_CHARS) {
            "A KMZ metadata value exceeds the $MAX_TEXT_VALUE_CHARS-character limit."
        }
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            require(isXml10CodePoint(codePoint)) {
                "A KMZ metadata value contains a character that is invalid in XML 1.0."
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun isXml10CodePoint(codePoint: Int): Boolean =
        codePoint == 0x09 ||
            codePoint == 0x0a ||
            codePoint == 0x0d ||
            codePoint in 0x20..0xd7ff ||
            codePoint in 0xe000..0xfffd ||
            codePoint in 0x10000..0x10ffff

    private fun canonicalDouble(value: Double): String =
        if (normalizedZero(value) == 0.0) "0" else java.lang.Double.toString(value)

    private fun normalizedZero(value: Double): Double = if (value == 0.0) 0.0 else value

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }

    private data class BuiltArchive(
        val bytes: ByteArray,
        val includedOverlayCount: Int,
        val omittedNoDataOverlayCount: Int,
        val warnings: List<String>,
    )

    companion object {
        const val MEDIA_TYPE = "application/vnd.google-earth.kmz"
        const val DEFAULT_FILE_NAME = "service-contours.kmz"
        const val MAX_OVERLAYS = 256
        const val MAX_RADIALS_PER_OVERLAY = 360
        const val MAX_TOTAL_RADIALS = 20_000
        const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024

        internal const val MAX_POINTS_PER_OVERLAY = 4_096
        internal const val MAX_TOTAL_POINTS = 100_000
        internal const val MAX_WARNINGS_PER_OVERLAY = 128
        internal const val MAX_WARNINGS_PER_RADIAL = 16
        internal const val MAX_TEXT_VALUE_CHARS = 4_096
        internal const val MAX_TOTAL_TEXT_CHARS = 1_000_000L
        internal const val MAX_ENTRY_BYTES = 16 * 1024 * 1024

        private const val MIN_POLYGON_POINTS = 4
        private const val DOC_KML_ENTRY = "doc.kml"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val KML_MEDIA_TYPE = "application/vnd.google-earth.kml+xml"
        private const val MANIFEST_SCHEMA = "atx.service-contours.kmz-manifest"
        private const val MANIFEST_VERSION = 1
        private const val NO_DATA_OMISSION_REASON =
            "NoData overlays are retained as evidence in manifest.json but have no geometry in doc.kml."
    }
}

private data class StoredZipEntry(
    val name: String,
    val data: ByteArray,
)

/** Minimal canonical ZIP writer: UTF-8 names, STORED entries, and fixed DOS 1980 timestamp. */
private object DeterministicStoredZip {
    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
    private const val VERSION_NEEDED = 20
    private const val VERSION_MADE_BY = 20
    private const val UTF8_FLAG = 0x0800
    private const val STORED_METHOD = 0
    private const val FIXED_DOS_TIME = 0
    private const val FIXED_DOS_DATE = 0x0021

    fun build(
        entries: List<StoredZipEntry>,
        maximumOutputBytes: Int,
    ): ByteArray {
        require(entries.isNotEmpty() && entries.size <= 0xffff) {
            "A deterministic ZIP requires between 1 and 65535 entries."
        }
        require(entries.map(StoredZipEntry::name).distinct().size == entries.size) {
            "A deterministic ZIP cannot contain duplicate entry names."
        }
        val prepared = entries.map { entry ->
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            require(nameBytes.isNotEmpty() && nameBytes.size <= 0xffff) {
                "A deterministic ZIP entry name has an invalid size."
            }
            val crc = CRC32().apply { update(entry.data) }.value
            PreparedEntry(entry, nameBytes, crc)
        }
        val expectedBytes = ZIP_END_BYTES.toLong() + prepared.sumOf { entry ->
            ZIP_LOCAL_HEADER_BYTES.toLong() + entry.nameBytes.size + entry.entry.data.size +
                ZIP_CENTRAL_HEADER_BYTES + entry.nameBytes.size
        }
        require(expectedBytes <= maximumOutputBytes && expectedBytes <= Int.MAX_VALUE) {
            "The deterministic KMZ exceeds the approved output-size limit."
        }

        val output = ByteArrayOutputStream(expectedBytes.toInt())
        val writer = LittleEndianWriter(output)
        val offsets = ArrayList<Int>(prepared.size)
        prepared.forEach { entry ->
            offsets += output.size()
            writer.u32(LOCAL_FILE_HEADER_SIGNATURE)
            writer.u16(VERSION_NEEDED)
            writer.u16(UTF8_FLAG)
            writer.u16(STORED_METHOD)
            writer.u16(FIXED_DOS_TIME)
            writer.u16(FIXED_DOS_DATE)
            writer.u32(entry.crc32)
            writer.u32(entry.entry.data.size.toLong())
            writer.u32(entry.entry.data.size.toLong())
            writer.u16(entry.nameBytes.size)
            writer.u16(0)
            writer.bytes(entry.nameBytes)
            writer.bytes(entry.entry.data)
        }
        val centralDirectoryOffset = output.size()
        prepared.forEachIndexed { index, entry ->
            writer.u32(CENTRAL_DIRECTORY_SIGNATURE)
            writer.u16(VERSION_MADE_BY)
            writer.u16(VERSION_NEEDED)
            writer.u16(UTF8_FLAG)
            writer.u16(STORED_METHOD)
            writer.u16(FIXED_DOS_TIME)
            writer.u16(FIXED_DOS_DATE)
            writer.u32(entry.crc32)
            writer.u32(entry.entry.data.size.toLong())
            writer.u32(entry.entry.data.size.toLong())
            writer.u16(entry.nameBytes.size)
            writer.u16(0)
            writer.u16(0)
            writer.u16(0)
            writer.u16(0)
            writer.u32(0)
            writer.u32(offsets[index].toLong())
            writer.bytes(entry.nameBytes)
        }
        val centralDirectorySize = output.size() - centralDirectoryOffset
        writer.u32(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        writer.u16(0)
        writer.u16(0)
        writer.u16(prepared.size)
        writer.u16(prepared.size)
        writer.u32(centralDirectorySize.toLong())
        writer.u32(centralDirectoryOffset.toLong())
        writer.u16(0)
        check(output.size() == expectedBytes.toInt()) {
            "The deterministic ZIP size did not match its bounded allocation."
        }
        return output.toByteArray()
    }

    private data class PreparedEntry(
        val entry: StoredZipEntry,
        val nameBytes: ByteArray,
        val crc32: Long,
    )

    private class LittleEndianWriter(
        private val output: ByteArrayOutputStream,
    ) {
        fun u16(value: Int) {
            require(value in 0..0xffff) { "A ZIP unsigned 16-bit field is out of range." }
            output.write(value and 0xff)
            output.write(value ushr 8 and 0xff)
        }

        fun u32(value: Long) {
            require(value in 0..0xffff_ffffL) { "A ZIP unsigned 32-bit field is out of range." }
            repeat(4) { shift -> output.write((value ushr (shift * 8) and 0xff).toInt()) }
        }

        fun bytes(value: ByteArray) {
            output.write(value)
        }
    }

    private const val ZIP_LOCAL_HEADER_BYTES = 30
    private const val ZIP_CENTRAL_HEADER_BYTES = 46
    private const val ZIP_END_BYTES = 22
}
