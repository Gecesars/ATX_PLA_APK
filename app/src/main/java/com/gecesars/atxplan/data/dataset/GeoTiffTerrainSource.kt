package com.gecesars.atxplan.data.dataset

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.zip.InflaterInputStream
import kotlin.math.floor

data class GeoTiffTerrainEvidence(
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val westLongitude: Double,
    val northLatitude: Double,
    val pixelWidthDegrees: Double,
    val pixelHeightDegrees: Double,
    val epsgCode: Int,
    val compression: String,
    val predictor: String,
    val sampling: String,
)

/**
 * Bounded, CPU-only reader for the single-band float32 Copernicus GLO-30 COG layout used by the
 * Android regional-data catalog. Unsupported TIFF variants fail closed; they are never treated as
 * flat terrain or zero elevation.
 */
internal interface GeoTiffRandomAccessSource : AutoCloseable {
    val length: Long
    fun readFully(offset: Long, destination: ByteArray)
}

private class FileGeoTiffRandomAccessSource(file: File) : GeoTiffRandomAccessSource {
    private val fileHandle = RandomAccessFile(file, "r")
    override val length: Long = file.length()

    @Synchronized
    override fun readFully(offset: Long, destination: ByteArray) {
        fileHandle.seek(offset)
        fileHandle.readFully(destination)
    }

    override fun close() = fileHandle.close()
}

internal class Float32GeoTiffTerrainSource(
    private val source: GeoTiffRandomAccessSource,
    maximumSourceBytes: Long,
    maximumCachedTiles: Int = DEFAULT_MAXIMUM_CACHED_TILES,
) : AutoCloseable {
    private val layout: Layout
    private val cache = object : LinkedHashMap<Int, FloatArray>(maximumCachedTiles, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FloatArray>?): Boolean =
            size > maximumCachedTiles
    }
    private var closed = false

    init {
        require(maximumCachedTiles in 1..MAXIMUM_CACHED_TILES) {
            "The terrain tile-cache size is outside the approved bound."
        }
        require(maximumSourceBytes in MINIMUM_TIFF_BYTES..MAXIMUM_APPROVED_SOURCE_BYTES) {
            "The terrain source-size policy is invalid."
        }
        if (source.length !in MINIMUM_TIFF_BYTES..maximumSourceBytes) {
            throw IOException("The terrain GeoTIFF is outside the approved file-size bound.")
        }
        layout = try {
            readLayout(source, source.length)
        } catch (error: Exception) {
            source.close()
            throw error
        }
    }

    val evidence: GeoTiffTerrainEvidence
        get() = GeoTiffTerrainEvidence(
            width = layout.width,
            height = layout.height,
            tileWidth = layout.tileWidth,
            tileHeight = layout.tileHeight,
            westLongitude = layout.westLongitude,
            northLatitude = layout.northLatitude,
            pixelWidthDegrees = layout.pixelWidthDegrees,
            pixelHeightDegrees = layout.pixelHeightDegrees,
            epsgCode = EPSG_WGS84,
            compression = "TIFF Deflate (8)",
            predictor = "TIFF floating-point horizontal differencing (3)",
            sampling = "nearest source pixel",
        )

    @Synchronized
    fun elevationMeters(latitude: Double, longitude: Double): Double? {
        check(!closed) { "The terrain GeoTIFF reader is closed." }
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Terrain latitude must be finite and in [-90, 90]."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Terrain longitude must be finite and in [-180, 180]."
        }
        val column = floor((longitude - layout.westLongitude) / layout.pixelWidthDegrees).toInt()
        val row = floor((layout.northLatitude - latitude) / layout.pixelHeightDegrees).toInt()
        if (column !in 0 until layout.width || row !in 0 until layout.height) return null
        val tileColumn = column / layout.tileWidth
        val tileRow = row / layout.tileHeight
        val tileIndex = tileRow * layout.tileColumns + tileColumn
        val tile = cache[tileIndex] ?: decodeTile(tileIndex).also { cache[tileIndex] = it }
        val localColumn = column % layout.tileWidth
        val localRow = row % layout.tileHeight
        val value = tile[localRow * layout.tileWidth + localColumn].toDouble()
        return value.takeIf { it.isFinite() && (layout.noData == null || it != layout.noData) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cache.clear()
        source.close()
    }

    private fun decodeTile(tileIndex: Int): FloatArray {
        if (tileIndex !in layout.tileOffsets.indices) {
            throw IOException("The requested terrain tile is outside the TIFF tile table.")
        }
        val offset = layout.tileOffsets[tileIndex]
        val byteCount = layout.tileByteCounts[tileIndex]
        if (byteCount !in 1..MAXIMUM_COMPRESSED_TILE_BYTES) {
            throw IOException("A terrain TIFF tile exceeds the approved compressed-size bound.")
        }
        requireRange(offset, byteCount.toLong(), source.length)
        val compressed = ByteArray(byteCount)
        source.readFully(offset, compressed)
        val expectedBytes = checkedProduct(layout.tileWidth, layout.tileHeight, FLOAT_BYTES)
        if (expectedBytes > MAXIMUM_INFLATED_TILE_BYTES) {
            throw IOException("A terrain TIFF tile exceeds the approved decoded-size bound.")
        }
        val predicted = ByteArray(expectedBytes)
        InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            var cursor = 0
            while (cursor < predicted.size) {
                val read = input.read(predicted, cursor, predicted.size - cursor)
                if (read < 0) throw EOFException("A terrain TIFF tile ended before its declared decoded size.")
                if (read == 0) continue
                cursor += read
            }
            if (input.read() >= 0) {
                throw IOException("A terrain TIFF tile exceeds its declared decoded size.")
            }
        }
        return undoFloatingPointPredictor(predicted, layout.tileWidth, layout.tileHeight)
    }

    private fun undoFloatingPointPredictor(
        predicted: ByteArray,
        width: Int,
        height: Int,
    ): FloatArray {
        val rowBytes = checkedProduct(width, FLOAT_BYTES)
        val values = FloatArray(checkedProduct(width, height))
        repeat(height) { row ->
            val rowStart = row * rowBytes
            for (index in 1 until rowBytes) {
                val position = rowStart + index
                predicted[position] = (
                    (predicted[position].toInt() and 0xff) +
                        (predicted[position - 1].toInt() and 0xff)
                    ).toByte()
            }
            repeat(width) { column ->
                val mostSignificant = predicted[rowStart + column].toInt() and 0xff
                val high = predicted[rowStart + width + column].toInt() and 0xff
                val low = predicted[rowStart + 2 * width + column].toInt() and 0xff
                val leastSignificant = predicted[rowStart + 3 * width + column].toInt() and 0xff
                val bits = (mostSignificant shl 24) or
                    (high shl 16) or
                    (low shl 8) or
                    leastSignificant
                values[row * width + column] = Float.fromBits(bits)
            }
        }
        return values
    }

    private data class Layout(
        val width: Int,
        val height: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val tileColumns: Int,
        val tileOffsets: LongArray,
        val tileByteCounts: IntArray,
        val westLongitude: Double,
        val northLatitude: Double,
        val pixelWidthDegrees: Double,
        val pixelHeightDegrees: Double,
        val noData: Double?,
    )

    private data class Entry(
        val type: Int,
        val count: Long,
        val inlineValue: ByteArray,
    )

    private fun readLayout(source: GeoTiffRandomAccessSource, length: Long): Layout {
        val marker = readAt(source, 0L, 2)
        if (!marker.contentEquals(byteArrayOf(0x49, 0x49))) {
            throw IOException("The terrain reader supports only little-endian classic TIFF files.")
        }
        val order = ByteOrder.LITTLE_ENDIAN
        if (unsigned(readAt(source, 2L, 2), order).toInt() != CLASSIC_TIFF_MAGIC) {
            throw IOException("The terrain source is not a supported classic TIFF file.")
        }
        val ifdOffset = unsigned(readAt(source, 4L, 4), order)
        requireRange(ifdOffset, 2L, length)
        val entryCount = unsigned(readAt(source, ifdOffset, 2), order).toInt()
        if (entryCount !in 1..MAXIMUM_IFD_ENTRIES) {
            throw IOException("The terrain TIFF IFD entry count is outside the approved bound.")
        }
        val entries = mutableMapOf<Int, Entry>()
        repeat(entryCount) { index ->
            val entryOffset = ifdOffset + 2L + index * CLASSIC_ENTRY_BYTES
            val raw = readAt(source, entryOffset, CLASSIC_ENTRY_BYTES)
            val buffer = ByteBuffer.wrap(raw).order(order)
            val tag = buffer.short.toInt() and 0xffff
            val type = buffer.short.toInt() and 0xffff
            val count = buffer.int.toLong() and 0xffff_ffffL
            val inline = ByteArray(4).also(buffer::get)
            if (tag in REQUIRED_OR_OPTIONAL_TAGS) {
                if (entries.put(tag, Entry(type, count, inline)) != null) {
                    throw IOException("The terrain TIFF repeats a required layout tag.")
                }
            }
        }

        fun unsignedValues(tag: Int): LongArray {
            val entry = entries[tag] ?: throw IOException("The terrain TIFF is missing tag $tag.")
            if (entry.type !in setOf(TYPE_SHORT, TYPE_LONG)) {
                throw IOException("Terrain TIFF tag $tag has an unsupported integer type.")
            }
            val bytes = entryBytes(source, entry, length, order)
            val buffer = ByteBuffer.wrap(bytes).order(order)
            return LongArray(entry.count.toBoundedInt(MAXIMUM_TAG_VALUES)) {
                if (entry.type == TYPE_SHORT) buffer.short.toLong() and 0xffffL
                else buffer.int.toLong() and 0xffff_ffffL
            }
        }

        fun singleUnsigned(tag: Int, default: Long? = null): Long {
            val entry = entries[tag] ?: return default
                ?: throw IOException("The terrain TIFF is missing tag $tag.")
            val values = unsignedValues(tag)
            if (values.size != 1) throw IOException("Terrain TIFF tag $tag is not scalar.")
            return values.single()
        }

        fun doubles(tag: Int): DoubleArray {
            val entry = entries[tag] ?: throw IOException("The terrain TIFF is missing tag $tag.")
            if (entry.type != TYPE_DOUBLE) {
                throw IOException("Terrain TIFF tag $tag does not contain doubles.")
            }
            val bytes = entryBytes(source, entry, length, order)
            val buffer = ByteBuffer.wrap(bytes).order(order)
            return DoubleArray(entry.count.toBoundedInt(MAXIMUM_TAG_VALUES)) { buffer.double }
        }

        val width = singleUnsigned(TAG_IMAGE_WIDTH).toDimension("width")
        val height = singleUnsigned(TAG_IMAGE_LENGTH).toDimension("height")
        val bits = singleUnsigned(TAG_BITS_PER_SAMPLE).toInt()
        val compression = singleUnsigned(TAG_COMPRESSION, 1L).toInt()
        val samples = singleUnsigned(TAG_SAMPLES_PER_PIXEL, 1L).toInt()
        val planar = singleUnsigned(TAG_PLANAR_CONFIGURATION, 1L).toInt()
        val predictor = singleUnsigned(TAG_PREDICTOR, 1L).toInt()
        val sampleFormat = singleUnsigned(TAG_SAMPLE_FORMAT, 1L).toInt()
        if (
            bits != 32 || compression != COMPRESSION_DEFLATE || samples != 1 ||
            planar != PLANAR_CONTIGUOUS || predictor != PREDICTOR_FLOATING_POINT ||
            sampleFormat != SAMPLE_FORMAT_FLOAT
        ) {
            throw IOException(
                "The terrain TIFF must be single-band float32, tiled Deflate, predictor 3.",
            )
        }
        val tileWidth = singleUnsigned(TAG_TILE_WIDTH).toTileDimension("width")
        val tileHeight = singleUnsigned(TAG_TILE_LENGTH).toTileDimension("height")
        val tileColumns = (width + tileWidth - 1) / tileWidth
        val tileRows = (height + tileHeight - 1) / tileHeight
        val expectedTileCount = checkedProduct(tileColumns, tileRows)
        val offsets = unsignedValues(TAG_TILE_OFFSETS)
        val byteCounts = unsignedValues(TAG_TILE_BYTE_COUNTS)
        if (offsets.size != expectedTileCount || byteCounts.size != expectedTileCount) {
            throw IOException("The terrain TIFF tile tables do not match its dimensions.")
        }
        val pixelScale = doubles(TAG_MODEL_PIXEL_SCALE)
        val tiePoint = doubles(TAG_MODEL_TIEPOINT)
        if (
            pixelScale.size != 3 || pixelScale.any { !it.isFinite() } ||
            pixelScale[0] <= 0.0 || pixelScale[1] <= 0.0 || pixelScale[2] < 0.0 ||
            tiePoint.size != 6 || tiePoint.any { !it.isFinite() } ||
            tiePoint[0] != 0.0 || tiePoint[1] != 0.0
        ) {
            throw IOException("The terrain TIFF georeferencing tags are unsupported.")
        }
        val geoKeys = unsignedValues(TAG_GEO_KEY_DIRECTORY).map(Long::toInt)
        if (!containsGeoKey(geoKeys, GEO_KEY_GEOGRAPHIC_TYPE, EPSG_WGS84)) {
            throw IOException("The terrain TIFF CRS is not EPSG:4326.")
        }
        val noData = entries[TAG_GDAL_NO_DATA]?.let { entry ->
            if (entry.type != TYPE_ASCII) throw IOException("The terrain TIFF NoData tag is malformed.")
            entryBytes(source, entry, length, order)
                .toString(Charsets.US_ASCII)
                .trimEnd('\u0000')
                .trim()
                .takeIf(String::isNotEmpty)
                ?.toDoubleOrNull()
                ?: throw IOException("The terrain TIFF NoData value is malformed.")
        }
        return Layout(
            width = width,
            height = height,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            tileColumns = tileColumns,
            tileOffsets = offsets,
            tileByteCounts = IntArray(byteCounts.size) { index ->
                byteCounts[index].takeIf { it in 1..MAXIMUM_COMPRESSED_TILE_BYTES.toLong() }
                    ?.toInt()
                    ?: throw IOException("A terrain TIFF tile byte count is outside the approved bound.")
            },
            westLongitude = tiePoint[3],
            northLatitude = tiePoint[4],
            pixelWidthDegrees = pixelScale[0],
            pixelHeightDegrees = pixelScale[1],
            noData = noData,
        )
    }

    private fun entryBytes(
        source: GeoTiffRandomAccessSource,
        entry: Entry,
        length: Long,
        order: ByteOrder,
    ): ByteArray {
        val typeBytes = TYPE_BYTES[entry.type]
            ?: throw IOException("The terrain TIFF contains an unsupported field type.")
        val byteCount = entry.count * typeBytes
        if (entry.count !in 1..MAXIMUM_TAG_VALUES.toLong() || byteCount > MAXIMUM_TAG_BYTES) {
            throw IOException("A terrain TIFF tag exceeds the approved metadata bound.")
        }
        if (byteCount <= entry.inlineValue.size) return entry.inlineValue.copyOf(byteCount.toInt())
        val offset = unsigned(entry.inlineValue, order)
        return readAt(source, offset, byteCount.toInt(), length)
    }

    private fun containsGeoKey(values: List<Int>, key: Int, expected: Int): Boolean {
        if (values.size < 4 || values[3] < 0 || values.size != 4 + values[3] * 4) return false
        repeat(values[3]) { index ->
            val base = 4 + index * 4
            if (values[base] == key && values[base + 1] == 0 && values[base + 2] == 1) {
                return values[base + 3] == expected
            }
        }
        return false
    }

    private fun readAt(
        source: GeoTiffRandomAccessSource,
        offset: Long,
        count: Int,
        length: Long = source.length,
    ): ByteArray {
        requireRange(offset, count.toLong(), length)
        return ByteArray(count).also { bytes ->
            source.readFully(offset, bytes)
        }
    }

    private fun unsigned(bytes: ByteArray, order: ByteOrder): Long {
        val buffer = ByteBuffer.wrap(bytes).order(order)
        return when (bytes.size) {
            2 -> buffer.short.toLong() and 0xffffL
            4 -> buffer.int.toLong() and 0xffff_ffffL
            else -> throw IOException("An unsupported TIFF integer width was requested.")
        }
    }

    private fun Long.toDimension(label: String): Int =
        takeIf { it in 1..MAXIMUM_DIMENSION.toLong() }?.toInt()
            ?: throw IOException("The terrain TIFF $label is outside the approved bound.")

    private fun Long.toTileDimension(label: String): Int =
        takeIf { it in 1..MAXIMUM_TILE_DIMENSION.toLong() }?.toInt()
            ?: throw IOException("The terrain TIFF tile $label is outside the approved bound.")

    private fun Long.toBoundedInt(maximum: Int): Int =
        takeIf { it in 1..maximum.toLong() }?.toInt()
            ?: throw IOException("A terrain TIFF value count is outside the approved bound.")

    private fun checkedProduct(vararg values: Int): Int {
        var result = 1L
        values.forEach { value ->
            result *= value.toLong()
            if (value < 0 || result > Int.MAX_VALUE) throw IOException("A terrain TIFF size overflows.")
        }
        return result.toInt()
    }

    private fun requireRange(offset: Long, count: Long, length: Long) {
        if (offset < 0L || count < 0L || offset > length || count > length - offset) {
            throw IOException("A terrain TIFF byte range is outside the source file.")
        }
    }

    companion object {
        private const val MINIMUM_TIFF_BYTES = 8L
        private const val MAXIMUM_APPROVED_SOURCE_BYTES = 4_294_967_295L
        private const val MAXIMUM_DIMENSION = 100_000
        private const val MAXIMUM_TILE_DIMENSION = 2_048
        private const val MAXIMUM_IFD_ENTRIES = 4_096
        private const val MAXIMUM_TAG_VALUES = 100_000
        private const val MAXIMUM_TAG_BYTES = 2L * 1024L * 1024L
        private const val MAXIMUM_COMPRESSED_TILE_BYTES = 16 * 1024 * 1024
        private const val MAXIMUM_INFLATED_TILE_BYTES = 16 * 1024 * 1024
        private const val DEFAULT_MAXIMUM_CACHED_TILES = 4
        private const val MAXIMUM_CACHED_TILES = 16
        private const val CLASSIC_TIFF_MAGIC = 42
        private const val CLASSIC_ENTRY_BYTES = 12
        private const val FLOAT_BYTES = 4
        private const val TYPE_ASCII = 2
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_DOUBLE = 12
        private const val COMPRESSION_DEFLATE = 8
        private const val PLANAR_CONTIGUOUS = 1
        private const val PREDICTOR_FLOATING_POINT = 3
        private const val SAMPLE_FORMAT_FLOAT = 3
        private const val EPSG_WGS84 = 4_326
        private const val GEO_KEY_GEOGRAPHIC_TYPE = 2_048
        private const val TAG_IMAGE_WIDTH = 256
        private const val TAG_IMAGE_LENGTH = 257
        private const val TAG_BITS_PER_SAMPLE = 258
        private const val TAG_COMPRESSION = 259
        private const val TAG_SAMPLES_PER_PIXEL = 277
        private const val TAG_PLANAR_CONFIGURATION = 284
        private const val TAG_PREDICTOR = 317
        private const val TAG_TILE_WIDTH = 322
        private const val TAG_TILE_LENGTH = 323
        private const val TAG_TILE_OFFSETS = 324
        private const val TAG_TILE_BYTE_COUNTS = 325
        private const val TAG_SAMPLE_FORMAT = 339
        private const val TAG_MODEL_PIXEL_SCALE = 33_550
        private const val TAG_MODEL_TIEPOINT = 33_922
        private const val TAG_GEO_KEY_DIRECTORY = 34_735
        private const val TAG_GDAL_NO_DATA = 42_113
        private val TYPE_BYTES = mapOf(
            TYPE_ASCII to 1L,
            TYPE_SHORT to 2L,
            TYPE_LONG to 4L,
            TYPE_DOUBLE to 8L,
        )
        private val REQUIRED_OR_OPTIONAL_TAGS = setOf(
            TAG_IMAGE_WIDTH,
            TAG_IMAGE_LENGTH,
            TAG_BITS_PER_SAMPLE,
            TAG_COMPRESSION,
            TAG_SAMPLES_PER_PIXEL,
            TAG_PLANAR_CONFIGURATION,
            TAG_PREDICTOR,
            TAG_TILE_WIDTH,
            TAG_TILE_LENGTH,
            TAG_TILE_OFFSETS,
            TAG_TILE_BYTE_COUNTS,
            TAG_SAMPLE_FORMAT,
            TAG_MODEL_PIXEL_SCALE,
            TAG_MODEL_TIEPOINT,
            TAG_GEO_KEY_DIRECTORY,
            TAG_GDAL_NO_DATA,
        )
    }
}

/** Local Copernicus GLO-30 adapter retained for the regional-data workflow. */
class CopernicusGeoTiffTerrainSource(
    file: File,
    maximumCachedTiles: Int = 4,
) : AutoCloseable {
    private val delegate: Float32GeoTiffTerrainSource

    init {
        if (!file.isFile) throw IOException("The terrain GeoTIFF is not a regular file.")
        delegate = Float32GeoTiffTerrainSource(
            source = FileGeoTiffRandomAccessSource(file),
            maximumSourceBytes = 512L * 1024L * 1024L,
            maximumCachedTiles = maximumCachedTiles,
        )
    }

    val evidence: GeoTiffTerrainEvidence
        get() = delegate.evidence

    fun elevationMeters(latitude: Double, longitude: Double): Double? =
        delegate.elevationMeters(latitude, longitude)

    override fun close() = delegate.close()
}
