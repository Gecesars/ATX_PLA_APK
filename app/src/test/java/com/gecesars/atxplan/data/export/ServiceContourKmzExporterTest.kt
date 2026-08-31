package com.gecesars.atxplan.data.export

import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.ContourPurpose
import com.gecesars.atxplan.domain.contour.ContourRadial
import com.gecesars.atxplan.domain.contour.ContourRadialStatus
import com.gecesars.atxplan.domain.contour.ContourStatus
import com.gecesars.atxplan.domain.contour.ServiceContourOverlay
import com.gecesars.atxplan.domain.model.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ServiceContourKmzExporterTest {
    @Test
    fun `archive is byte deterministic across overlay ordering with fixed stored entries`() {
        val protected = protectedOverlay()
        val screening = screeningOverlay()
        val noData = noDataOverlay()
        val exporter = ServiceContourKmzExporter()

        val first = exporter.export(listOf(noData, screening, protected))
        val reordered = exporter.export(listOf(protected, noData, screening))
        val archive = unzip(first)

        assertArrayEquals(first, reordered)
        assertEquals(listOf("doc.kml", "manifest.json"), archive.keys.toList())
        assertTrue(archive.values.all { entry -> entry.method == ZipEntry.STORED })
        assertEquals(0, unsignedShort(first, LOCAL_HEADER_TIME_OFFSET))
        assertEquals(FIXED_DOS_DATE, unsignedShort(first, LOCAL_HEADER_DATE_OFFSET))
        val secondHeaderOffset = localEntryEndOffset(first, 0)
        assertEquals(0, unsignedShort(first, secondHeaderOffset + LOCAL_HEADER_TIME_OFFSET))
        assertEquals(FIXED_DOS_DATE, unsignedShort(first, secondHeaderOffset + LOCAL_HEADER_DATE_OFFSET))
    }

    @Test
    fun `KML escapes metadata uses longitude latitude and classifies geometry`() {
        val protected = protectedOverlay(id = "protected<&>\"'")
        val screening = screeningOverlay()
        val noData = noDataOverlay()
        val kmlBytes = unzip(
            ServiceContourKmzExporter().export(listOf(noData, protected, screening)),
        ).getValue("doc.kml").bytes
        val kml = kmlBytes.toString(Charsets.UTF_8)

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(ByteArrayInputStream(kmlBytes))

        assertEquals("kml", document.documentElement.localName)
        assertEquals(2, document.getElementsByTagNameNS(KML_NAMESPACE, "Placemark").length)
        assertEquals(1, document.getElementsByTagNameNS(KML_NAMESPACE, "Polygon").length)
        assertEquals(1, document.getElementsByTagNameNS(KML_NAMESPACE, "LineString").length)
        assertTrue(kml.contains("protected&lt;&amp;&gt;&quot;&apos;"))
        assertTrue(kml.contains("-46.63,-23.55,0"))
        assertFalse(kml.contains("-23.55,-46.63,0"))
        assertTrue(kml.contains("<styleUrl>#protected-complete</styleUrl>"))
        assertTrue(kml.contains("<styleUrl>#screening</styleUrl>"))
        assertFalse(kml.contains(noData.id))
    }

    @Test
    fun `legacy interfering envelope uses its own KML style and manifest provenance`() {
        val interfering = interferingOverlay()
        val archive = unzip(ServiceContourKmzExporter().export(listOf(interfering)))
        val kml = archive.getValue("doc.kml").bytes.toString(Charsets.UTF_8)
        val manifest = archive.getValue("manifest.json").bytes.toString(Charsets.UTF_8)

        assertTrue(kml.contains("<Style id=\"interfering\">"))
        assertTrue(kml.contains("<styleUrl>#interfering</styleUrl>"))
        assertTrue(manifest.contains("ANATEL-RESOLUTION-67-1998-REVOKED"))
        assertTrue(manifest.contains("INTERFERING"))
    }

    @Test
    fun `manifest preserves warnings radial evidence and explicit NoData omission`() {
        val warning = "Quoted \"warning\"\nsource C:\\data & <review>"
        val protected = protectedOverlay(warnings = listOf(warning))
        val noData = noDataOverlay(warnings = listOf("Terrain tile is unavailable."))
        val bytes = ServiceContourKmzExporter().export(listOf(noData, protected))
        val archive = unzip(bytes)
        val manifestText = archive.getValue("manifest.json").bytes.toString(Charsets.UTF_8)
        val manifest = Json.parseToJsonElement(manifestText).jsonObject
        val overlays = manifest.getValue("overlays").jsonArray
        val protectedJson = overlays.single { item ->
            item.jsonObject.getValue("id").jsonPrimitive.content == protected.id
        }.jsonObject
        val noDataJson = overlays.single { item ->
            item.jsonObject.getValue("id").jsonPrimitive.content == noData.id
        }.jsonObject

        assertEquals("longitude,latitude,altitude", manifest.getValue("coordinateOrder").jsonPrimitive.content)
        assertEquals("1", manifest.getValue("includedOverlayCount").jsonPrimitive.content)
        assertEquals("1", manifest.getValue("omittedNoDataOverlayCount").jsonPrimitive.content)
        assertEquals(warning, protectedJson.getValue("warnings").jsonArray.single().jsonPrimitive.content)
        assertEquals("true", protectedJson.getValue("includedInKml").jsonPrimitive.content)
        assertEquals("false", noDataJson.getValue("includedInKml").jsonPrimitive.content)
        assertTrue(noDataJson.getValue("omissionReason").jsonPrimitive.content.contains("NoData"))
        assertEquals("2", protectedJson.getValue("radialCount").jsonPrimitive.content)
        assertEquals(
            listOf("0", "180.0"),
            protectedJson.getValue("radials").jsonArray.map { radial ->
                radial.jsonObject.getValue("azimuthDegrees").jsonPrimitive.content
            },
        )
        assertEquals(
            sha256(archive.getValue("doc.kml").bytes),
            manifest.getValue("entries").jsonObject
                .getValue("doc.kml").jsonObject
                .getValue("sha256").jsonPrimitive.content,
        )
    }

    @Test
    fun `write reports exact immutable archive evidence without closing caller output`() {
        val output = TrackingOutputStream()
        val overlays = listOf(protectedOverlay(), noDataOverlay())
        val exporter = ServiceContourKmzExporter()

        val summary = exporter.write(overlays, output)
        val expected = exporter.export(overlays)

        assertFalse(output.closed)
        assertArrayEquals(expected, output.delegate.toByteArray())
        assertEquals(expected.size, summary.byteCount)
        assertEquals(sha256(expected), summary.sha256)
        assertEquals(1, summary.includedOverlayCount)
        assertEquals(1, summary.omittedNoDataOverlayCount)
        assertTrue(summary.warnings.single().contains("omitted from doc.kml"))
    }

    @Test
    fun `overlay radial metadata and output bounds fail closed`() {
        val base = protectedOverlay()
        val tooManyOverlays = List(ServiceContourKmzExporter.MAX_OVERLAYS + 1) { index ->
            base.copy(id = "overlay-$index")
        }
        val tooManyRadials = List(ServiceContourKmzExporter.MAX_RADIALS_PER_OVERLAY + 1) { index ->
            ContourRadial(
                azimuthDegrees = index * 360.0 / (ServiceContourKmzExporter.MAX_RADIALS_PER_OVERLAY + 1),
                distanceKm = 10.0,
                erpKw = 1.0,
                effectiveHeightM = 150.0,
                status = ContourRadialStatus.COMPLETE,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            ServiceContourKmzExporter().export(tooManyOverlays)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceContourKmzExporter().export(listOf(base.copy(radials = tooManyRadials)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceContourKmzExporter(maximumOutputBytes = 256).export(listOf(base))
        }
    }

    @Test
    fun `duplicate IDs and invalid XML metadata fail closed`() {
        val base = protectedOverlay()

        assertThrows(IllegalArgumentException::class.java) {
            ServiceContourKmzExporter().export(listOf(base, base.copy()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceContourKmzExporter().export(listOf(base.copy(model = "invalid\u0000model")))
        }
    }

    private fun protectedOverlay(
        id: String = "protected-fm",
        warnings: List<String> = listOf("Planning reference only; not a regulatory filing result."),
    ): ServiceContourOverlay = ServiceContourOverlay(
        id = id,
        siteId = "site-a",
        sectorId = "sector-a",
        service = BroadcastService.FM,
        purpose = ContourPurpose.PROTECTED,
        statisticalBasis = "E(50,50)",
        thresholdDbuvPerM = 66.0,
        points = listOf(
            GeoPoint(-23.55, -46.63),
            GeoPoint(-23.50, -46.55),
            GeoPoint(-23.60, -46.50),
            GeoPoint(-23.55, -46.63),
        ),
        status = ContourStatus.COMPLETE,
        model = "ITU-R P.1546-6 land reference",
        rulesetId = "ANATEL-ACT-8104-2022",
        warnings = warnings,
        sourceUrl = "https://example.test/rules?x=1&y=2",
        regulatory = false,
        radials = listOf(
            ContourRadial(
                azimuthDegrees = 180.0,
                distanceKm = 12.5,
                erpKw = 0.25,
                effectiveHeightM = 148.0,
                status = ContourRadialStatus.COMPLETE,
                warnings = listOf("Rear attenuation applied."),
            ),
            ContourRadial(
                azimuthDegrees = 0.0,
                distanceKm = 32.0,
                erpKw = 1.0,
                effectiveHeightM = 150.0,
                status = ContourRadialStatus.COMPLETE,
            ),
        ),
        inputFingerprint = "a".repeat(64),
    )

    private fun screeningOverlay(): ServiceContourOverlay = ServiceContourOverlay(
        id = "screening-fm",
        siteId = "site-a",
        sectorId = "sector-a",
        service = BroadcastService.FM,
        purpose = ContourPurpose.SCREENING,
        statisticalBasis = "E(50,10) statistical screening",
        thresholdDbuvPerM = 66.0,
        points = listOf(
            GeoPoint(-23.56, -46.64),
            GeoPoint(-23.48, -46.54),
            GeoPoint(-23.61, -46.49),
            GeoPoint(-23.56, -46.64),
        ),
        status = ContourStatus.COMPLETE,
        model = "ITU-R P.1546-6 land reference",
        rulesetId = "TEST-SCREENING-E50-10",
        warnings = listOf("Statistical screening is not an interference-compliance result."),
        regulatory = false,
        inputFingerprint = "b".repeat(64),
    )

    private fun interferingOverlay(): ServiceContourOverlay = screeningOverlay().copy(
        id = "interfering-fm-legacy",
        purpose = ContourPurpose.INTERFERING,
        statisticalBasis = "E(50,10) legacy cochannel interfering envelope",
        thresholdDbuvPerM = 32.0,
        rulesetId = "ANATEL-RESOLUTION-67-1998-REVOKED",
        warnings = listOf("Revoked method; not a current regulatory result."),
    )

    private fun noDataOverlay(
        warnings: List<String> = listOf("Radial HNMT data is unavailable."),
    ): ServiceContourOverlay = ServiceContourOverlay(
        id = "nodata-dtv",
        siteId = "site-b",
        sectorId = "sector-b",
        service = BroadcastService.DIGITAL_TV,
        purpose = ContourPurpose.PROTECTED,
        statisticalBasis = "E(50,90)",
        thresholdDbuvPerM = null,
        points = emptyList(),
        status = ContourStatus.NO_DATA,
        model = "NoData",
        rulesetId = "ANATEL-ACT-9751-2022",
        warnings = warnings,
        regulatory = false,
        inputFingerprint = "c".repeat(64),
    )

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ArchiveEntry> {
        val entries = linkedMapOf<String, ArchiveEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1_024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                entries[entry.name] = ArchiveEntry(output.toByteArray(), entry.method)
                input.closeEntry()
            }
        }
        return LinkedHashMap(entries)
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }

    private fun localEntryEndOffset(bytes: ByteArray, offset: Int): Int {
        val compressedBytes = unsignedInt(bytes, offset + LOCAL_HEADER_COMPRESSED_SIZE_OFFSET)
        val nameBytes = unsignedShort(bytes, offset + LOCAL_HEADER_NAME_LENGTH_OFFSET)
        val extraBytes = unsignedShort(bytes, offset + LOCAL_HEADER_EXTRA_LENGTH_OFFSET)
        return offset + LOCAL_HEADER_BYTES + nameBytes + extraBytes + compressedBytes.toInt()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }

    private data class ArchiveEntry(
        val bytes: ByteArray,
        val method: Int,
    )

    private class TrackingOutputStream : java.io.OutputStream() {
        val delegate = ByteArrayOutputStream()
        var closed = false
            private set

        override fun write(value: Int) {
            delegate.write(value)
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            delegate.write(value, offset, length)
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val KML_NAMESPACE = "http://www.opengis.net/kml/2.2"
        const val FIXED_DOS_DATE = 0x0021
        const val LOCAL_HEADER_BYTES = 30
        const val LOCAL_HEADER_TIME_OFFSET = 10
        const val LOCAL_HEADER_DATE_OFFSET = 12
        const val LOCAL_HEADER_COMPRESSED_SIZE_OFFSET = 18
        const val LOCAL_HEADER_NAME_LENGTH_OFFSET = 26
        const val LOCAL_HEADER_EXTRA_LENGTH_OFFSET = 28
    }
}
