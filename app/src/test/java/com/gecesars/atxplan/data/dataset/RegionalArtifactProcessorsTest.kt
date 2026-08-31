package com.gecesars.atxplan.data.dataset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class RegionalArtifactProcessorsTest {
    private lateinit var sandboxDirectory: File

    @Before
    fun setUp() {
        sandboxDirectory = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toFile()
    }

    @After
    fun tearDown() {
        check(sandboxDirectory.name.startsWith(TEST_DIRECTORY_PREFIX)) {
            "Refusing to remove an unexpected test directory."
        }
        val systemTemporaryDirectory = File(checkNotNull(System.getProperty("java.io.tmpdir")))
        check(sandboxDirectory.toPath().parent == systemTemporaryDirectory.toPath()) {
            "The processor test directory is outside the system temporary directory."
        }
        check(sandboxDirectory.deleteRecursively()) {
            "The processor test directory could not be removed."
        }
    }

    @Test
    fun `classic GeoTIFF metadata is indexed without decoding raster samples`() {
        val order = ByteOrder.LITTLE_ENDIAN
        val geoAscii = "WGS 84|".toByteArray(Charsets.US_ASCII)
        val source = sourceFile(
            "terrain.tif",
            classicTiff(
                order = order,
                entries = listOf(
                    TiffTestEntry(256, 4, 1, unsignedValues(order, 4, 3_600)),
                    TiffTestEntry(257, 4, 1, unsignedValues(order, 4, 1_800)),
                    TiffTestEntry(258, 3, 1, unsignedValues(order, 2, 16)),
                    TiffTestEntry(259, 3, 1, unsignedValues(order, 2, 8)),
                    TiffTestEntry(277, 3, 1, unsignedValues(order, 2, 1)),
                    TiffTestEntry(339, 3, 1, unsignedValues(order, 2, 2)),
                    TiffTestEntry(33_550, 12, 3, doubles(order, 0.000277, 0.000277, 1.0)),
                    TiffTestEntry(33_922, 12, 6, doubles(order, 0.0, 0.0, 0.0, -48.0, -15.0, 0.0)),
                    TiffTestEntry(
                        34_735,
                        3,
                        12,
                        unsignedValues(
                            order,
                            2,
                            1, 1, 0, 2,
                            2_048, 0, 1, 4_326,
                            2_049, 34_737, geoAscii.size.toLong(), 0,
                        ),
                    ),
                    TiffTestEntry(34_737, 2, geoAscii.size.toLong(), geoAscii),
                    TiffTestEntry(42_113, 2, 6, "-9999\u0000".toByteArray(Charsets.US_ASCII)),
                ),
            ),
        )

        val index = RegionalTiffMetadataIndexer.index(source)

        assertEquals(TiffByteOrder.LITTLE_ENDIAN, index.byteOrder)
        assertEquals(TiffVariant.TIFF, index.variant)
        assertEquals(3_600L, index.width)
        assertEquals(1_800L, index.height)
        assertEquals(1, index.bandCount)
        assertTrue(index.bandCountDeclared)
        assertEquals(listOf(16), index.bitsPerSample)
        assertEquals(8, index.compression)
        assertTrue(index.compressionDeclared)
        assertEquals(listOf(2), index.sampleFormat)
        assertEquals(listOf(0.000277, 0.000277, 1.0), index.pixelScale)
        assertEquals(listOf(0.0, 0.0, 0.0, -48.0, -15.0, 0.0), index.tiePoints)
        assertNull(index.modelTransformation)
        assertEquals(4_326, index.crs?.epsgCode)
        assertEquals("WGS 84", index.crs?.citation)
        assertEquals(2_048, index.crs?.sourceGeoKey)
        assertEquals("-9999", index.noData)
        assertTrue(index.isMetadataOnly)
        assertFalse(index.rasterSamplesDecoded)
        assertFalse(index.cloudOptimizedLayoutValidated)
        assertEquals(source.length(), index.byteCount)
    }

    @Test
    fun `GeoTIFF pixel scale accepts the conventional zero vertical component`() {
        val order = ByteOrder.LITTLE_ENDIAN
        val source = sourceFile(
            "terrain-zero-z-scale.tif",
            classicTiff(
                order = order,
                entries = listOf(
                    TiffTestEntry(256, 4, 1, unsignedValues(order, 4, 3_600)),
                    TiffTestEntry(257, 4, 1, unsignedValues(order, 4, 3_600)),
                    TiffTestEntry(
                        33_550,
                        12,
                        3,
                        doubles(order, 1.0 / 3_600.0, 1.0 / 3_600.0, 0.0),
                    ),
                    TiffTestEntry(
                        33_922,
                        12,
                        6,
                        doubles(order, 0.0, 0.0, 0.0, -47.0, -23.0, 0.0),
                    ),
                ),
            ),
        )

        val index = RegionalTiffMetadataIndexer.index(source)

        assertEquals(0.0, checkNotNull(index.pixelScale)[2], 0.0)
    }

    @Test
    fun `big endian BigTIFF metadata uses bounded 64 bit fields`() {
        val order = ByteOrder.BIG_ENDIAN
        val source = sourceFile(
            "surface-big.tif",
            bigTiff(
                order = order,
                entries = listOf(
                    TiffTestEntry(256, 16, 1, unsignedValues(order, 8, 12_000)),
                    TiffTestEntry(257, 16, 1, unsignedValues(order, 8, 6_000)),
                    TiffTestEntry(258, 3, 2, unsignedValues(order, 2, 32, 32)),
                    TiffTestEntry(259, 3, 1, unsignedValues(order, 2, 1)),
                    TiffTestEntry(277, 3, 1, unsignedValues(order, 2, 2)),
                ),
            ),
        )

        val index = RegionalTiffMetadataIndexer.index(source)

        assertEquals(TiffByteOrder.BIG_ENDIAN, index.byteOrder)
        assertEquals(TiffVariant.BIG_TIFF, index.variant)
        assertEquals(12_000L, index.width)
        assertEquals(6_000L, index.height)
        assertEquals(2, index.bandCount)
        assertEquals(listOf(32, 32), index.bitsPerSample)
        assertEquals(1, index.compression)
        assertTrue(index.compressionDeclared)
        assertNull(index.crs)
    }

    @Test
    fun `TIFF metadata offsets cannot escape the source file`() {
        val order = ByteOrder.LITTLE_ENDIAN
        val payload = classicTiff(
            order = order,
            entries = listOf(
                TiffTestEntry(256, 4, 1, unsignedValues(order, 4, 10)),
                TiffTestEntry(257, 4, 1, unsignedValues(order, 4, 10)),
                TiffTestEntry(33_550, 12, 3, doubles(order, 1.0, 1.0, 1.0)),
            ),
        )
        val pixelScaleEntryValueOffset = 8 + 2 + 2 * 12 + 8
        ByteBuffer.wrap(payload).order(order).putInt(pixelScaleEntryValueOffset, Int.MAX_VALUE)
        val source = sourceFile("bad-offset.tif", payload)

        val error = assertThrows(IOException::class.java) {
            RegionalTiffMetadataIndexer.index(source)
        }

        assertTrue(error.message.orEmpty().contains("outside the source file"))
    }

    @Test
    fun `TIFF metadata value counts are rejected before allocation`() {
        val order = ByteOrder.LITTLE_ENDIAN
        val payload = ByteBuffer.allocate(8 + 2 + 3 * 12 + 4).order(order).apply {
            put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
            putShort(3)
            putClassicEntry(256, 4, 1, unsignedValues(order, 4, 10))
            putClassicEntry(257, 4, 1, unsignedValues(order, 4, 10))
            putShort(258).putShort(3).putInt(1_000_000).putInt(0)
            putInt(0)
        }.array()
        val source = sourceFile("oversized-count.tif", payload)

        val error = assertThrows(IOException::class.java) {
            RegionalTiffMetadataIndexer.index(source)
        }

        assertTrue(error.message.orEmpty().contains("value-count limit"))
    }

    @Test
    fun `Overpass buildings become deterministic attributed GeoJSON`() {
        val raw = sourceFile("buildings.json", BUILDING_RESPONSE.toByteArray(Charsets.UTF_8))
        val output = File(sandboxDirectory, "processed/buildings.geojson")
        val request = request(raw, output)

        val first = OverpassBuildingProcessor.process(request)
        val firstBytes = output.readBytes()
        val second = OverpassBuildingProcessor.process(request)

        assertEquals(2, first.featureCount)
        assertEquals(1, first.wayFeatureCount)
        assertEquals(1, first.relationFeatureCount)
        assertEquals(1, first.omittedInnerRingCount)
        assertEquals(0, first.unsupportedElementCount)
        assertEquals(2, first.sourceElementCount)
        assertEquals(10, first.vertexCount)
        assertEquals(OSM_ATTRIBUTION_TEXT, first.attribution)
        assertEquals(sha256(raw.readBytes()), first.rawSha256)
        assertEquals(sha256(firstBytes), first.outputSha256)
        assertEquals(first.outputSha256, second.outputSha256)
        assertTrue(firstBytes.contentEquals(output.readBytes()))
        assertTrue(output.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".part") })

        val json = Json.parseToJsonElement(output.readText()).jsonObject
        assertEquals("FeatureCollection", json.getValue("type").jsonPrimitive.content)
        assertEquals("EPSG:4326", json.getValue("coordinate_reference_system").jsonPrimitive.content)
        assertEquals(OSM_ATTRIBUTION_TEXT, json.getValue("attribution").jsonPrimitive.content)
        val source = json.getValue("source").jsonObject
        assertEquals(SOURCE_URL, source.getValue("url").jsonPrimitive.content)
        assertEquals(QUERY, source.getValue("query").jsonPrimitive.content)
        assertEquals(TEST_TIMESTAMP.toString(), source.getValue("queried_at_epoch_millis").jsonPrimitive.content)
        assertEquals(OSM_SOURCE_TIMESTAMP, source.getValue("timestamp_osm_base").jsonPrimitive.content)
        assertEquals(OSM_SOURCE_TIMESTAMP, first.sourceTimestampOsmBase)
        assertEquals(first.rawSha256, source.getValue("raw_sha256").jsonPrimitive.content)
        val processing = json.getValue("processing").jsonObject
        assertEquals("1", processing.getValue("inner_rings_omitted").jsonPrimitive.content)
        assertEquals("0", processing.getValue("unsupported_elements").jsonPrimitive.content)
        val features = json.getValue("features").jsonArray
        assertEquals("relation/10", features[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("MultiPolygon", features[0].jsonObject.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("way/20", features[1].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("Polygon", features[1].jsonObject.getValue("geometry").jsonObject.getValue("type").jsonPrimitive.content)
        val wayProperties = features[1].jsonObject.getValue("properties").jsonObject
        assertEquals("18.5", wayProperties.getValue("height").jsonPrimitive.content)
        assertEquals("5", wayProperties.getValue("building_levels").jsonPrimitive.content)
        assertEquals("2.5", wayProperties.getValue("roof_height").jsonPrimitive.content)
        assertEquals("gabled", wayProperties.getValue("roof_shape").jsonPrimitive.content)
    }

    @Test
    fun `unclosed building ways remain explicit unsupported data`() {
        val raw = sourceFile(
            "unclosed.json",
            """{"elements":[{"type":"way","id":7,"tags":{"building":"yes"},"geometry":[{"lat":0,"lon":0},{"lat":0,"lon":1},{"lat":1,"lon":1}]}]}"""
                .toByteArray(),
        )
        val output = File(sandboxDirectory, "unclosed.geojson")

        val result = OverpassBuildingProcessor.process(request(raw, output))

        assertEquals(0, result.featureCount)
        assertEquals(1, result.unsupportedElementCount)
        assertEquals(0, Json.parseToJsonElement(output.readText()).jsonObject.getValue("features").jsonArray.size)
    }

    @Test
    fun `invalid WGS 84 coordinates reject the whole processed output`() {
        val raw = sourceFile(
            "invalid-coordinate.json",
            """{"elements":[{"type":"way","id":8,"tags":{"building":"yes"},"geometry":[{"lat":91,"lon":0},{"lat":0,"lon":1},{"lat":1,"lon":1},{"lat":91,"lon":0}]}]}"""
                .toByteArray(),
        )
        val output = File(sandboxDirectory, "invalid-coordinate.geojson")

        val error = assertThrows(IOException::class.java) {
            OverpassBuildingProcessor.process(request(raw, output))
        }

        assertTrue(error.message.orEmpty().contains("latitude"))
        assertFalse(output.exists())
    }

    @Test
    fun `raw Overpass responses are bounded before JSON parsing`() {
        val raw = sourceFile("too-large.json", BUILDING_RESPONSE.toByteArray())
        val output = File(sandboxDirectory, "too-large.geojson")
        val limits = OverpassBuildingLimits(maximumRawBytes = 32)

        val error = assertThrows(IOException::class.java) {
            OverpassBuildingProcessor.process(request(raw, output, limits))
        }

        assertTrue(error.message.orEmpty().contains("file-size limit"))
        assertFalse(output.exists())
    }

    @Test
    fun `feature limits stop publication and leave no staging file`() {
        val raw = sourceFile("feature-limit.json", BUILDING_RESPONSE.toByteArray())
        val output = File(sandboxDirectory, "bounded/buildings.geojson")
        val limits = OverpassBuildingLimits(maximumFeatures = 1)

        val error = assertThrows(IOException::class.java) {
            OverpassBuildingProcessor.process(request(raw, output, limits))
        }

        assertTrue(error.message.orEmpty().contains("feature limit"))
        assertFalse(output.exists())
        assertTrue(output.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `atomic publication never replaces different processed data`() {
        val raw = sourceFile("immutable.json", BUILDING_RESPONSE.toByteArray())
        val output = sourceFile("immutable.geojson", "existing trusted data".toByteArray())
        val original = output.readBytes()

        val error = assertThrows(IOException::class.java) {
            OverpassBuildingProcessor.process(request(raw, output))
        }

        assertTrue(error.message.orEmpty().contains("different data"))
        assertTrue(original.contentEquals(output.readBytes()))
        assertTrue(sandboxDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    private fun request(
        raw: File,
        output: File,
        limits: OverpassBuildingLimits = OverpassBuildingLimits(),
    ) = OverpassBuildingProcessRequest(
        rawFile = raw,
        outputGeoJsonFile = output,
        sourceUrl = SOURCE_URL,
        query = QUERY,
        queriedAtEpochMillis = TEST_TIMESTAMP,
        expectedRawSha256 = sha256(raw.readBytes()),
        limits = limits,
    )

    private fun sourceFile(name: String, payload: ByteArray): File =
        File(sandboxDirectory, name).apply { writeBytes(payload) }

    private data class TiffTestEntry(
        val tag: Int,
        val type: Int,
        val count: Long,
        val value: ByteArray,
    )

    private fun classicTiff(order: ByteOrder, entries: List<TiffTestEntry>): ByteArray {
        val directoryBytes = 8 + 2 + entries.size * 12 + 4
        val externalBytes = entries.filter { it.value.size > 4 }.sumOf { it.value.size }
        val result = ByteBuffer.allocate(directoryBytes + externalBytes).order(order)
        result.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        result.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        result.putShort(42).putInt(8).putShort(entries.size.toShort())
        var dataOffset = directoryBytes
        entries.forEach { entry ->
            result.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count.toInt())
            if (entry.value.size <= 4) {
                result.put(entry.value)
                repeat(4 - entry.value.size) { result.put(0) }
            } else {
                result.putInt(dataOffset)
                dataOffset += entry.value.size
            }
        }
        result.putInt(0)
        entries.filter { it.value.size > 4 }.forEach { result.put(it.value) }
        return result.array()
    }

    private fun bigTiff(order: ByteOrder, entries: List<TiffTestEntry>): ByteArray {
        val directoryBytes = 16 + 8 + entries.size * 20 + 8
        val externalBytes = entries.filter { it.value.size > 8 }.sumOf { it.value.size }
        val result = ByteBuffer.allocate(directoryBytes + externalBytes).order(order)
        result.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        result.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        result.putShort(43).putShort(8).putShort(0).putLong(16)
        result.putLong(entries.size.toLong())
        var dataOffset = directoryBytes
        entries.forEach { entry ->
            result.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putLong(entry.count)
            if (entry.value.size <= 8) {
                result.put(entry.value)
                repeat(8 - entry.value.size) { result.put(0) }
            } else {
                result.putLong(dataOffset.toLong())
                dataOffset += entry.value.size
            }
        }
        result.putLong(0)
        entries.filter { it.value.size > 8 }.forEach { result.put(it.value) }
        return result.array()
    }

    private fun ByteBuffer.putClassicEntry(
        tag: Int,
        type: Int,
        count: Int,
        inlineValue: ByteArray,
    ): ByteBuffer {
        putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
        put(inlineValue)
        repeat(4 - inlineValue.size) { put(0) }
        return this
    }

    private fun unsignedValues(order: ByteOrder, width: Int, vararg values: Long): ByteArray {
        val buffer = ByteBuffer.allocate(width * values.size).order(order)
        values.forEach { value ->
            when (width) {
                2 -> buffer.putShort(value.toShort())
                4 -> buffer.putInt(value.toInt())
                8 -> buffer.putLong(value)
                else -> error("Unsupported test integer width.")
            }
        }
        return buffer.array()
    }

    private fun doubles(order: ByteOrder, vararg values: Double): ByteArray =
        ByteBuffer.allocate(8 * values.size).order(order).apply {
            values.forEach(::putDouble)
        }.array()

    private fun sha256(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val TEST_DIRECTORY_PREFIX = "atx-regional-processor-test-"
        const val TEST_TIMESTAMP = 1_725_000_000_000L
        const val SOURCE_URL = "https://lambert.openstreetmap.de/api/interpreter"
        const val QUERY = "[out:json];way[\"building\"](-16,-48,-15,-47);out geom;"
        const val OSM_SOURCE_TIMESTAMP = "2026-08-27T15:30:00Z"
        const val OSM_ATTRIBUTION_TEXT = "© OpenStreetMap contributors"
        const val BUILDING_RESPONSE = """
            {
              "osm3s": {"timestamp_osm_base": "2026-08-27T15:30:00Z"},
              "elements": [
                {
                  "type": "way",
                  "id": 20,
                  "tags": {
                    "building": "yes",
                    "height": "18.5",
                    "building:levels": "5",
                    "roof:height": "2.5",
                    "roof:shape": "gabled"
                  },
                  "geometry": [
                    {"lat": -15.0, "lon": -48.0},
                    {"lat": -15.0, "lon": -47.9},
                    {"lat": -14.9, "lon": -47.9},
                    {"lat": -14.9, "lon": -48.0},
                    {"lat": -15.0, "lon": -48.0}
                  ]
                },
                {
                  "type": "relation",
                  "id": 10,
                  "tags": {"type": "multipolygon", "building": "apartments"},
                  "members": [
                    {
                      "type": "way",
                      "ref": 201,
                      "role": "outer",
                      "geometry": [
                        {"lat": -15.2, "lon": -48.2},
                        {"lat": -15.2, "lon": -48.0},
                        {"lat": -15.0, "lon": -48.0}
                      ]
                    },
                    {
                      "type": "way",
                      "ref": 202,
                      "role": "outer",
                      "geometry": [
                        {"lat": -15.0, "lon": -48.0},
                        {"lat": -15.0, "lon": -48.2},
                        {"lat": -15.2, "lon": -48.2}
                      ]
                    },
                    {
                      "type": "way",
                      "ref": 203,
                      "role": "inner",
                      "geometry": [
                        {"lat": -15.15, "lon": -48.15},
                        {"lat": -15.15, "lon": -48.10},
                        {"lat": -15.10, "lon": -48.10},
                        {"lat": -15.10, "lon": -48.15},
                        {"lat": -15.15, "lon": -48.15}
                      ]
                    }
                  ]
                }
              ]
            }
        """
    }
}
