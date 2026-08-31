package com.gecesars.atxplan.data.dataset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.DigestOutputStream
import java.security.MessageDigest

enum class TiffByteOrder {
    LITTLE_ENDIAN,
    BIG_ENDIAN,
}

enum class TiffVariant {
    TIFF,
    BIG_TIFF,
}

data class TiffCrs(
    val epsgCode: Int?,
    val citation: String?,
    val sourceGeoKey: Int?,
)

data class TiffIndexLimits(
    val maximumFileBytes: Long = 4L * 1024L * 1024L * 1024L,
    val maximumIfdEntries: Int = 4_096,
    val maximumTagValueBytes: Int = 1024 * 1024,
    val maximumMetadataBytes: Int = 4 * 1024 * 1024,
    val maximumListValues: Int = 4_096,
    val maximumDimension: Long = 1_000_000L,
    val maximumBands: Int = 1_024,
) {
    init {
        require(maximumFileBytes in 16L..MAXIMUM_TIFF_FILE_BYTES) {
            "The TIFF file limit must be between 16 bytes and 16 GiB."
        }
        require(maximumIfdEntries in 1..MAXIMUM_TIFF_IFD_ENTRIES) {
            "The TIFF IFD entry limit must be between 1 and 65,536."
        }
        require(maximumTagValueBytes in 1..MAXIMUM_TIFF_TAG_BYTES) {
            "The TIFF tag value limit must be between 1 byte and 8 MiB."
        }
        require(maximumMetadataBytes in maximumTagValueBytes..MAXIMUM_TIFF_METADATA_BYTES) {
            "The TIFF metadata limit must include one tag and cannot exceed 32 MiB."
        }
        require(maximumListValues in 1..MAXIMUM_TIFF_LIST_VALUES) {
            "The TIFF list value limit must be between 1 and 65,536."
        }
        require(maximumDimension in 1L..MAXIMUM_TIFF_DIMENSION) {
            "The TIFF dimension limit must be between 1 and 10,000,000 pixels."
        }
        require(maximumBands in 1..MAXIMUM_TIFF_BANDS) {
            "The TIFF band limit must be between 1 and 4,096."
        }
    }
}

data class TiffMetadataIndex(
    val byteOrder: TiffByteOrder,
    val variant: TiffVariant,
    val width: Long,
    val height: Long,
    val bandCount: Int,
    val bandCountDeclared: Boolean,
    val bitsPerSample: List<Int>,
    val compression: Int,
    val compressionDeclared: Boolean,
    val sampleFormat: List<Int>,
    val pixelScale: List<Double>?,
    val tiePoints: List<Double>?,
    val modelTransformation: List<Double>?,
    val crs: TiffCrs?,
    val noData: String?,
    val byteCount: Long,
    val firstIfdOffset: Long,
    val firstIfdEntryCount: Int,
    val isMetadataOnly: Boolean = true,
    val rasterSamplesDecoded: Boolean = false,
    val cloudOptimizedLayoutValidated: Boolean = false,
)

/**
 * Reads only bounded TIFF metadata. It does not decode pixels, prove a COG layout, or make the
 * raster usable by an RF calculation.
 */
object RegionalTiffMetadataIndexer {
    fun index(
        file: File,
        limits: TiffIndexLimits = TiffIndexLimits(),
    ): TiffMetadataIndex {
        if (!file.isFile) throw IOException("The TIFF source is not a regular file.")
        val initialLength = file.length()
        if (initialLength !in MINIMUM_TIFF_BYTES..limits.maximumFileBytes) {
            throw IOException("The TIFF source is outside the approved file-size limit.")
        }

        RandomAccessFile(file, "r").use { randomAccessFile ->
            val reader = TiffReader(randomAccessFile, initialLength)
            val marker = reader.readAt(0L, 2)
            val byteOrder = when {
                marker.contentEquals(byteArrayOf(0x49, 0x49)) -> TiffByteOrder.LITTLE_ENDIAN
                marker.contentEquals(byteArrayOf(0x4D, 0x4D)) -> TiffByteOrder.BIG_ENDIAN
                else -> throw IOException("The source does not have a valid TIFF byte-order marker.")
            }
            reader.byteOrder = byteOrder
            val magic = reader.readUnsignedAt(2L, 2).toInt()
            val header = when (magic) {
                CLASSIC_TIFF_MAGIC -> TiffHeader(
                    variant = TiffVariant.TIFF,
                    firstIfdOffset = reader.readUnsignedAt(4L, 4),
                    inlineValueBytes = 4,
                    entryBytes = 12,
                    headerBytes = 8,
                )

                BIG_TIFF_MAGIC -> {
                    if (reader.readUnsignedAt(4L, 2) != 8L || reader.readUnsignedAt(6L, 2) != 0L) {
                        throw IOException("The BigTIFF header uses an unsupported offset layout.")
                    }
                    TiffHeader(
                        variant = TiffVariant.BIG_TIFF,
                        firstIfdOffset = reader.readUnsignedAt(8L, 8),
                        inlineValueBytes = 8,
                        entryBytes = 20,
                        headerBytes = 16,
                    )
                }

                else -> throw IOException("The source does not have a supported TIFF signature.")
            }
            if (header.firstIfdOffset < header.headerBytes || header.firstIfdOffset >= initialLength) {
                throw IOException("The first TIFF IFD offset is outside the source file.")
            }

            val countBytes = if (header.variant == TiffVariant.TIFF) 2 else 8
            val rawEntryCount = reader.readUnsignedAt(header.firstIfdOffset, countBytes)
            if (rawEntryCount > limits.maximumIfdEntries.toLong()) {
                throw IOException("The TIFF IFD contains more entries than the approved limit.")
            }
            val entryCount = rawEntryCount.toInt()
            val directoryBytes = checkedMultiply(entryCount.toLong(), header.entryBytes.toLong())
            val entriesOffset = checkedAdd(header.firstIfdOffset, countBytes.toLong())
            reader.requireRange(entriesOffset, directoryBytes)
            val budget = MetadataBudget(limits.maximumMetadataBytes)
            budget.consume(checkedAdd(countBytes.toLong(), directoryBytes))

            val relevantEntries = mutableMapOf<Int, TiffEntry>()
            repeat(entryCount) { index ->
                val offset = checkedAdd(entriesOffset, checkedMultiply(index.toLong(), header.entryBytes.toLong()))
                val tag = reader.readUnsignedAt(offset, 2).toInt()
                val type = reader.readUnsignedAt(checkedAdd(offset, 2), 2).toInt()
                val count = reader.readUnsignedAt(
                    checkedAdd(offset, 4),
                    if (header.variant == TiffVariant.TIFF) 4 else 8,
                )
                val valueFieldOffset = checkedAdd(
                    offset,
                    if (header.variant == TiffVariant.TIFF) 8 else 12,
                )
                val inlineValue = reader.readAt(valueFieldOffset, header.inlineValueBytes)
                if (tag in RELEVANT_TIFF_TAGS) {
                    if (relevantEntries.containsKey(tag)) {
                        throw IOException("The TIFF IFD repeats a required metadata tag.")
                    }
                    relevantEntries[tag] = TiffEntry(tag, type, count, inlineValue)
                }
            }

            fun entry(tag: Int): TiffEntry? = relevantEntries[tag]
            fun unsignedValues(tag: Int, allowedTypes: Set<Int>): List<Long>? =
                entry(tag)?.readUnsignedValues(reader, header, limits, budget, allowedTypes)

            val width = unsignedValues(TAG_IMAGE_WIDTH, INTEGER_FIELD_TYPES)
                ?.singleOrNull()
                ?: throw IOException("The TIFF metadata does not contain one image width value.")
            val height = unsignedValues(TAG_IMAGE_LENGTH, INTEGER_FIELD_TYPES)
                ?.singleOrNull()
                ?: throw IOException("The TIFF metadata does not contain one image height value.")
            if (width !in 1L..limits.maximumDimension || height !in 1L..limits.maximumDimension) {
                throw IOException("The TIFF image dimensions exceed the approved limit.")
            }

            val bandValues = unsignedValues(TAG_SAMPLES_PER_PIXEL, setOf(TYPE_SHORT))
            val bandCount = when {
                bandValues == null -> 1
                bandValues.size != 1 -> throw IOException("The TIFF band count is malformed.")
                else -> bandValues.single().toInt()
            }
            if (bandCount !in 1..limits.maximumBands) {
                throw IOException("The TIFF band count exceeds the approved limit.")
            }
            val bitsPerSample = unsignedValues(TAG_BITS_PER_SAMPLE, setOf(TYPE_SHORT))
                .orEmpty()
                .map { value ->
                    if (value !in 1L..64L) throw IOException("The TIFF bits-per-sample value is unsupported.")
                    value.toInt()
                }
            validatePerBandValues(bitsPerSample, bandCount, "bits-per-sample")

            val compressionValues = unsignedValues(TAG_COMPRESSION, setOf(TYPE_SHORT))
            val compression = when {
                compressionValues == null -> 1
                compressionValues.size != 1 || compressionValues.single() !in 1L..65_535L ->
                    throw IOException("The TIFF compression value is malformed.")

                else -> compressionValues.single().toInt()
            }
            val sampleFormat = unsignedValues(TAG_SAMPLE_FORMAT, setOf(TYPE_SHORT))
                .orEmpty()
                .map { value ->
                    if (value !in 1L..6L) throw IOException("The TIFF sample-format value is unsupported.")
                    value.toInt()
                }
            validatePerBandValues(sampleFormat, bandCount, "sample-format")

            val pixelScale = entry(TAG_MODEL_PIXEL_SCALE)
                ?.readDoubleValues(reader, header, limits, budget)
                ?.also { values ->
                    if (
                        values.size != 3 ||
                        values.any { !it.isFinite() } ||
                        values[0] <= 0.0 ||
                        values[1] <= 0.0 ||
                        values[2] < 0.0
                    ) {
                        throw IOException("The TIFF model pixel scale is malformed.")
                    }
                }
            val tiePoints = entry(TAG_MODEL_TIEPOINT)
                ?.readDoubleValues(reader, header, limits, budget)
                ?.also { values ->
                    if (values.size < 6 || values.size % 6 != 0 || values.any { !it.isFinite() }) {
                        throw IOException("The TIFF model tie points are malformed.")
                    }
                }
            val modelTransformation = entry(TAG_MODEL_TRANSFORMATION)
                ?.readDoubleValues(reader, header, limits, budget)
                ?.also { values ->
                    if (values.size != 16 || values.any { !it.isFinite() }) {
                        throw IOException("The TIFF model transformation is malformed.")
                    }
                }
            if ((pixelScale == null) != (tiePoints == null) && modelTransformation == null) {
                throw IOException("The TIFF georeferencing tags are incomplete.")
            }

            val geoAscii = entry(TAG_GEO_ASCII_PARAMS)
                ?.readAscii(reader, header, limits, budget, "GeoTIFF ASCII parameters")
            val geoKeys = unsignedValues(TAG_GEO_KEY_DIRECTORY, setOf(TYPE_SHORT))
                ?.map { it.toInt() }
            val crs = parseCrs(geoKeys, geoAscii)
            val noData = entry(TAG_GDAL_NO_DATA)
                ?.readAscii(reader, header, limits, budget, "GDAL NoData")
                ?.trimEnd('\u0000')
                ?.also { value ->
                    if (value.isBlank() || value.length > MAXIMUM_NO_DATA_CHARACTERS || value.anyInvalidControl()) {
                        throw IOException("The TIFF NoData value is malformed.")
                    }
                }

            if (randomAccessFile.length() != initialLength) {
                throw IOException("The TIFF source changed while its metadata was being indexed.")
            }
            return TiffMetadataIndex(
                byteOrder = byteOrder,
                variant = header.variant,
                width = width,
                height = height,
                bandCount = bandCount,
                bandCountDeclared = bandValues != null,
                bitsPerSample = bitsPerSample,
                compression = compression,
                compressionDeclared = compressionValues != null,
                sampleFormat = sampleFormat,
                pixelScale = pixelScale,
                tiePoints = tiePoints,
                modelTransformation = modelTransformation,
                crs = crs,
                noData = noData,
                byteCount = initialLength,
                firstIfdOffset = header.firstIfdOffset,
                firstIfdEntryCount = entryCount,
            )
        }
    }
}

data class OverpassBuildingLimits(
    val maximumRawBytes: Int = 16 * 1024 * 1024,
    val maximumJsonDepth: Int = 128,
    val maximumElements: Int = 100_000,
    val maximumFeatures: Int = 20_000,
    val maximumOutputVertices: Int = 500_000,
    val maximumSourceCoordinates: Int = 750_000,
    val maximumMembersPerRelation: Int = 20_000,
    val maximumCoordinatesPerGeometry: Int = 100_000,
    val maximumQueryCharacters: Int = 128 * 1024,
    val maximumOutputBytes: Int = 64 * 1024 * 1024,
) {
    init {
        require(maximumRawBytes in 1..MAXIMUM_OVERPASS_RAW_BYTES) {
            "The Overpass raw-response limit must be between 1 byte and 16 MiB."
        }
        require(maximumJsonDepth in 1..MAXIMUM_JSON_DEPTH) {
            "The Overpass JSON depth limit must be between 1 and 256."
        }
        require(maximumElements in 1..MAXIMUM_OVERPASS_ELEMENTS) {
            "The Overpass element limit must be between 1 and 200,000."
        }
        require(maximumFeatures in 1..MAXIMUM_BUILDING_FEATURES) {
            "The building feature limit must be between 1 and 50,000."
        }
        require(maximumOutputVertices in 4..MAXIMUM_BUILDING_VERTICES) {
            "The building vertex limit must be between 4 and 1,000,000."
        }
        require(maximumSourceCoordinates in maximumOutputVertices..MAXIMUM_SOURCE_COORDINATES) {
            "The source coordinate limit must include the output limit and cannot exceed 2,000,000."
        }
        require(maximumMembersPerRelation in 1..MAXIMUM_RELATION_MEMBERS) {
            "The relation member limit must be between 1 and 50,000."
        }
        require(maximumCoordinatesPerGeometry in 4..maximumSourceCoordinates) {
            "The geometry coordinate limit must be at least 4 and within the source limit."
        }
        require(maximumQueryCharacters in 1..MAXIMUM_OVERPASS_QUERY_CHARACTERS) {
            "The Overpass query limit must be between 1 and 256 KiB characters."
        }
        require(maximumOutputBytes in 1..MAXIMUM_GEOJSON_OUTPUT_BYTES) {
            "The processed GeoJSON limit must be between 1 byte and 128 MiB."
        }
    }
}

data class OverpassBuildingProcessRequest(
    val rawFile: File,
    val outputGeoJsonFile: File,
    val sourceUrl: String,
    val query: String,
    val queriedAtEpochMillis: Long,
    val expectedRawSha256: String? = null,
    val limits: OverpassBuildingLimits = OverpassBuildingLimits(),
)

data class OverpassBuildingProcessResult(
    val outputFile: File,
    val rawSha256: String,
    val outputSha256: String,
    val rawByteCount: Long,
    val outputByteCount: Long,
    val sourceElementCount: Int,
    val featureCount: Int,
    val vertexCount: Int,
    val wayFeatureCount: Int,
    val relationFeatureCount: Int,
    val omittedInnerRingCount: Int,
    val unsupportedElementCount: Int,
    val attribution: String,
    val sourceUrl: String,
    val query: String,
    val queriedAtEpochMillis: Long,
    val sourceTimestampOsmBase: String?,
)

/**
 * Converts a bounded Overpass JSON response into an immutable, deterministic WGS 84 GeoJSON file.
 * Only closed building and building-part ways and complete relation outer rings are published.
 * Relation inner rings are validated and counted but intentionally omitted from the output.
 */
object OverpassBuildingProcessor {
    fun process(request: OverpassBuildingProcessRequest): OverpassBuildingProcessResult {
        validateRequest(request)
        val rawBytes = readBounded(request.rawFile, request.limits.maximumRawBytes)
        validateJsonDepth(rawBytes, request.limits.maximumJsonDepth)
        val rawSha256 = sha256(rawBytes)
        if (request.expectedRawSha256 != null && request.expectedRawSha256 != rawSha256) {
            throw IOException("The Overpass response does not match the expected SHA-256 digest.")
        }
        val rawText = decodeStrictUtf8(rawBytes)
        val root = try {
            Json.parseToJsonElement(rawText).jsonObject
        } catch (error: Exception) {
            throw IOException("The Overpass response is not valid JSON.", error)
        }
        val elements = root["elements"] as? JsonArray
            ?: throw IOException("The Overpass response does not contain an elements array.")
        val sourceTimestampOsmBase = parseSourceTimestampOsmBase(root)
        if (elements.size > request.limits.maximumElements) {
            throw IOException("The Overpass response exceeds the approved element limit.")
        }

        val sourceCoordinateBudget = CountBudget(
            request.limits.maximumSourceCoordinates,
            "The Overpass response exceeds the approved source coordinate limit.",
        )
        val parsedElements = parseElements(elements, request.limits, sourceCoordinateBudget)
        val features = mutableListOf<BuildingFeature>()
        val consumedWayIds = mutableSetOf<Long>()
        var omittedInnerRings = 0
        var unsupportedElements = 0

        parsedElements.filterIsInstance<ParsedRelation>().sortedBy { it.id }.forEach { relation ->
            if (!relation.isBuilding) return@forEach
            val assembled = assembleRelation(relation, request.limits)
            if (assembled == null) {
                unsupportedElements += 1
            } else {
                omittedInnerRings = checkedCountAdd(
                    omittedInnerRings,
                    assembled.innerRingCount,
                    "The omitted inner-ring count overflowed.",
                )
                features += BuildingFeature(
                    osmType = OSM_TYPE_RELATION,
                    osmId = relation.id,
                    tags = relation.tags,
                    rings = assembled.outerRings,
                    omittedInnerRings = assembled.innerRingCount,
                )
                consumedWayIds += assembled.memberWayIds
            }
        }

        parsedElements.filterIsInstance<ParsedWay>().sortedBy { it.id }.forEach { way ->
            if (!way.isBuilding || way.id in consumedWayIds) return@forEach
            val geometry = way.geometry
            if (geometry == null || !isValidRing(geometry)) {
                unsupportedElements += 1
            } else {
                features += BuildingFeature(
                    osmType = OSM_TYPE_WAY,
                    osmId = way.id,
                    tags = way.tags,
                    rings = listOf(geometry),
                    omittedInnerRings = 0,
                )
            }
        }
        unsupportedElements += parsedElements.count { element ->
            element is ParsedUnsupported && element.hasBuildingTag
        }
        if (features.size > request.limits.maximumFeatures) {
            throw IOException("The processed building data exceeds the approved feature limit.")
        }
        val sortedFeatures = features.sortedWith(compareBy<BuildingFeature>({ it.osmType }, { it.osmId }))
        val vertexCount = sortedFeatures.fold(0) { total, feature ->
            feature.rings.fold(total) { featureTotal, ring ->
                checkedCountAdd(
                    featureTotal,
                    ring.size,
                    "The processed building vertex count overflowed.",
                )
            }
        }
        if (vertexCount > request.limits.maximumOutputVertices) {
            throw IOException("The processed building data exceeds the approved vertex limit.")
        }

        val publication = writeGeoJsonAtomically(
            request = request,
            rawSha256 = rawSha256,
            features = sortedFeatures,
            sourceElementCount = elements.size,
            vertexCount = vertexCount,
            omittedInnerRingCount = omittedInnerRings,
            unsupportedElementCount = unsupportedElements,
            sourceTimestampOsmBase = sourceTimestampOsmBase,
        )
        return OverpassBuildingProcessResult(
            outputFile = request.outputGeoJsonFile,
            rawSha256 = rawSha256,
            outputSha256 = publication.sha256,
            rawByteCount = rawBytes.size.toLong(),
            outputByteCount = publication.byteCount,
            sourceElementCount = elements.size,
            featureCount = sortedFeatures.size,
            vertexCount = vertexCount,
            wayFeatureCount = sortedFeatures.count { it.osmType == OSM_TYPE_WAY },
            relationFeatureCount = sortedFeatures.count { it.osmType == OSM_TYPE_RELATION },
            omittedInnerRingCount = omittedInnerRings,
            unsupportedElementCount = unsupportedElements,
            attribution = OSM_ATTRIBUTION,
            sourceUrl = request.sourceUrl,
            query = request.query,
            queriedAtEpochMillis = request.queriedAtEpochMillis,
            sourceTimestampOsmBase = sourceTimestampOsmBase,
        )
    }
}

private data class TiffHeader(
    val variant: TiffVariant,
    val firstIfdOffset: Long,
    val inlineValueBytes: Int,
    val entryBytes: Int,
    val headerBytes: Long,
)

private data class TiffEntry(
    val tag: Int,
    val type: Int,
    val count: Long,
    val inlineValue: ByteArray,
) {
    fun readUnsignedValues(
        reader: TiffReader,
        header: TiffHeader,
        limits: TiffIndexLimits,
        budget: MetadataBudget,
        allowedTypes: Set<Int>,
    ): List<Long> {
        if (type !in allowedTypes) throw IOException("TIFF tag $tag uses an unsupported field type.")
        if (count > limits.maximumListValues.toLong()) {
            throw IOException("TIFF tag $tag exceeds the approved value-count limit.")
        }
        val data = readData(reader, header, limits, budget)
        val valueBytes = tiffTypeBytes(type)
        return List(count.toInt()) { index ->
            decodeUnsigned(data, index * valueBytes, valueBytes, reader.byteOrder)
        }
    }

    fun readDoubleValues(
        reader: TiffReader,
        header: TiffHeader,
        limits: TiffIndexLimits,
        budget: MetadataBudget,
    ): List<Double> {
        if (type != TYPE_DOUBLE) throw IOException("TIFF tag $tag does not contain double values.")
        if (count > limits.maximumListValues.toLong()) {
            throw IOException("TIFF tag $tag exceeds the approved value-count limit.")
        }
        val data = readData(reader, header, limits, budget)
        val order = if (reader.byteOrder == TiffByteOrder.LITTLE_ENDIAN) {
            ByteOrder.LITTLE_ENDIAN
        } else {
            ByteOrder.BIG_ENDIAN
        }
        val buffer = ByteBuffer.wrap(data).order(order)
        return List(count.toInt()) { buffer.double }
    }

    fun readAscii(
        reader: TiffReader,
        header: TiffHeader,
        limits: TiffIndexLimits,
        budget: MetadataBudget,
        label: String,
    ): String {
        if (type != TYPE_ASCII) throw IOException("The $label tag is not ASCII.")
        val data = readData(reader, header, limits, budget)
        if (data.any { byte -> byte.toInt() and 0x80 != 0 }) {
            throw IOException("The $label tag contains non-ASCII data.")
        }
        return data.toString(StandardCharsets.US_ASCII)
    }

    private fun readData(
        reader: TiffReader,
        header: TiffHeader,
        limits: TiffIndexLimits,
        budget: MetadataBudget,
    ): ByteArray {
        val typeBytes = tiffTypeBytes(type)
        val byteCount = checkedMultiply(count, typeBytes.toLong())
        if (byteCount > limits.maximumTagValueBytes.toLong() || byteCount > Int.MAX_VALUE.toLong()) {
            throw IOException("TIFF tag $tag exceeds the approved metadata value limit.")
        }
        budget.consume(byteCount)
        return if (byteCount <= header.inlineValueBytes.toLong()) {
            inlineValue.copyOf(byteCount.toInt())
        } else {
            val offset = decodeUnsigned(inlineValue, 0, header.inlineValueBytes, reader.byteOrder)
            reader.readAt(offset, byteCount.toInt())
        }
    }
}

private class TiffReader(
    private val source: RandomAccessFile,
    private val sourceLength: Long,
) {
    lateinit var byteOrder: TiffByteOrder

    fun readAt(offset: Long, byteCount: Int): ByteArray {
        requireRange(offset, byteCount.toLong())
        val result = ByteArray(byteCount)
        source.seek(offset)
        source.readFully(result)
        return result
    }

    fun readUnsignedAt(offset: Long, byteCount: Int): Long =
        decodeUnsigned(readAt(offset, byteCount), 0, byteCount, byteOrder)

    fun requireRange(offset: Long, byteCount: Long) {
        if (offset < 0L || byteCount < 0L || offset > sourceLength || byteCount > sourceLength - offset) {
            throw IOException("A TIFF metadata offset is outside the source file.")
        }
    }
}

private class MetadataBudget(private val maximumBytes: Int) {
    private var consumedBytes = 0L

    fun consume(byteCount: Long) {
        consumedBytes = checkedAdd(consumedBytes, byteCount)
        if (consumedBytes > maximumBytes.toLong()) {
            throw IOException("The TIFF metadata exceeds the approved read budget.")
        }
    }
}

private sealed interface ParsedElement {
    val id: Long
}

private data class ParsedWay(
    override val id: Long,
    val tags: BuildingTags,
    val geometry: List<Coordinate>?,
) : ParsedElement {
    val isBuilding: Boolean get() = tags.isBuilding
}

private data class ParsedRelation(
    override val id: Long,
    val tags: BuildingTags,
    val members: List<RelationMember>,
) : ParsedElement {
    val isBuilding: Boolean get() = tags.isBuilding
}

private data class ParsedUnsupported(
    override val id: Long,
    val hasBuildingTag: Boolean,
) : ParsedElement

private data class RelationMember(
    val wayId: Long,
    val role: String,
    val geometry: List<Coordinate>?,
)

private data class Coordinate(
    val longitude: Double,
    val latitude: Double,
)

private data class BuildingFeature(
    val osmType: String,
    val osmId: Long,
    val tags: BuildingTags,
    val rings: List<List<Coordinate>>,
    val omittedInnerRings: Int,
)

/** Raw, bounded OSM values are retained; height interpretation is a separate versioned adapter. */
private data class BuildingTags(
    val building: String?,
    val buildingPart: String?,
    val height: String?,
    val buildingLevels: String?,
    val roofHeight: String?,
    val roofLevels: String?,
    val minimumHeight: String?,
    val buildingMinimumLevel: String?,
    val roofShape: String?,
) {
    val isBuilding: Boolean get() = building != null || buildingPart != null
}

private data class AssembledRelation(
    val outerRings: List<List<Coordinate>>,
    val innerRingCount: Int,
    val memberWayIds: Set<Long>,
)

private data class Segment(
    val wayId: Long,
    val coordinates: List<Coordinate>,
)

private data class Publication(
    val sha256: String,
    val byteCount: Long,
)

private class CountBudget(
    private val maximum: Int,
    private val errorMessage: String,
) {
    private var count: Int = 0

    fun consume(increment: Int) {
        count = checkedCountAdd(count, increment, errorMessage)
        if (count > maximum) throw IOException(errorMessage)
    }
}

private class BoundedOutputStream(
    private val delegate: OutputStream,
    private val maximumBytes: Int,
) : OutputStream() {
    var byteCount: Long = 0L
        private set

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
        byteCount += 1
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        ensureCapacity(length)
        delegate.write(bytes, offset, length)
        byteCount += length
    }

    override fun flush() = delegate.flush()

    private fun ensureCapacity(increment: Int) {
        if (increment < 0 || byteCount > maximumBytes.toLong() - increment.toLong()) {
            throw IOException("The processed GeoJSON exceeds the approved output-size limit.")
        }
    }
}

private fun parseElements(
    elements: JsonArray,
    limits: OverpassBuildingLimits,
    coordinateBudget: CountBudget,
): List<ParsedElement> {
    val parsed = ArrayList<ParsedElement>(elements.size)
    val seen = mutableSetOf<Pair<String, Long>>()
    elements.forEach { element ->
        val item = element as? JsonObject
            ?: throw IOException("An Overpass element is not a JSON object.")
        val type = item.stringValue("type")
            ?: throw IOException("An Overpass element does not declare its type.")
        val id = item["id"]?.jsonPrimitive?.longOrNull
            ?: throw IOException("An Overpass element does not have a valid identifier.")
        if (id <= 0L) throw IOException("An Overpass element identifier is outside the valid range.")
        if (!seen.add(type to id)) {
            throw IOException("The Overpass response contains duplicate element identifiers.")
        }
        val tags = item["tags"]?.let { value ->
            value as? JsonObject ?: throw IOException("An Overpass tags value is not a JSON object.")
        }
        val buildingTags = tags.toBuildingTags()
        when (type) {
            OSM_TYPE_WAY -> parsed += ParsedWay(
                id = id,
                tags = buildingTags,
                geometry = item["geometry"]?.let { geometry ->
                    parseGeometry(geometry, limits, coordinateBudget, minimumCoordinates = 2)
                },
            )

            OSM_TYPE_RELATION -> {
                val rawMembers = (item["members"] as? JsonArray).orEmpty()
                if (rawMembers.size > limits.maximumMembersPerRelation) {
                    throw IOException("An Overpass relation exceeds the approved member limit.")
                }
                val members = rawMembers.map { rawMember ->
                    val member = rawMember as? JsonObject
                        ?: throw IOException("An Overpass relation member is not a JSON object.")
                    val memberType = member.stringValue("type")
                    val role = member.stringValue("role").orEmpty()
                    val wayId = member["ref"]?.jsonPrimitive?.longOrNull
                    if (memberType != OSM_TYPE_WAY || wayId == null || wayId <= 0L) {
                        RelationMember(-1L, UNSUPPORTED_MEMBER_ROLE, null)
                    } else {
                        RelationMember(
                            wayId = wayId,
                            role = role,
                            geometry = member["geometry"]?.let { geometry ->
                                parseGeometry(
                                    geometry,
                                    limits,
                                    coordinateBudget,
                                    minimumCoordinates = 2,
                                )
                            },
                        )
                    }
                }
                parsed += ParsedRelation(id, buildingTags, members)
            }

            else -> parsed += ParsedUnsupported(
                id = id,
                hasBuildingTag = buildingTags.isBuilding,
            )
        }
    }
    return parsed
}

private fun JsonObject.stringValue(key: String): String? {
    val value = this[key] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IOException("The Overpass $key value is not a JSON primitive.")
    if (!primitive.isString) throw IOException("The Overpass $key value is not a string.")
    return primitive.content
}

private fun parseSourceTimestampOsmBase(root: JsonObject): String? {
    val rawOsm3s = root["osm3s"] ?: return null
    val osm3s = rawOsm3s as? JsonObject
        ?: throw IOException("The Overpass osm3s metadata is not a JSON object.")
    val timestamp = osm3s.stringValue("timestamp_osm_base") ?: return null
    if (
        timestamp.length !in 1..MAXIMUM_OSM_SOURCE_TIMESTAMP_CHARACTERS ||
        timestamp.anyInvalidControl() ||
        !OSM_SOURCE_TIMESTAMP_PATTERN.matches(timestamp)
    ) {
        throw IOException("The Overpass source timestamp is invalid.")
    }
    return timestamp
}

private fun JsonObject?.activeOsmTag(key: String): String? {
    if (this == null) return null
    val value = this[key] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IOException("The OpenStreetMap $key tag is not a JSON primitive.")
    if (!primitive.isString) throw IOException("The OpenStreetMap $key tag is not a string.")
    val content = primitive.content
    if (content.length > MAXIMUM_OSM_TAG_CHARACTERS || content.anyInvalidControl()) {
        throw IOException("The OpenStreetMap $key tag is outside the approved text limit.")
    }
    return content.takeUnless { tag -> tag.isBlank() || tag.lowercase() in INACTIVE_OSM_TAG_VALUES }
}

private fun JsonObject?.boundedOsmTag(key: String): String? {
    if (this == null) return null
    val value = this[key] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IOException("The OpenStreetMap $key tag is not a JSON primitive.")
    if (!primitive.isString) throw IOException("The OpenStreetMap $key tag is not a string.")
    val content = primitive.content
    if (content.length > MAXIMUM_OSM_TAG_CHARACTERS || content.anyInvalidControl()) {
        throw IOException("The OpenStreetMap $key tag is outside the approved text limit.")
    }
    return content.takeUnless(String::isBlank)
}

private fun JsonObject?.toBuildingTags(): BuildingTags = BuildingTags(
    building = activeOsmTag("building"),
    buildingPart = activeOsmTag("building:part"),
    height = boundedOsmTag("height"),
    buildingLevels = boundedOsmTag("building:levels"),
    roofHeight = boundedOsmTag("roof:height"),
    roofLevels = boundedOsmTag("roof:levels"),
    minimumHeight = boundedOsmTag("min_height"),
    buildingMinimumLevel = boundedOsmTag("building:min_level"),
    roofShape = boundedOsmTag("roof:shape"),
)

private fun parseGeometry(
    rawGeometry: JsonElement,
    limits: OverpassBuildingLimits,
    coordinateBudget: CountBudget,
    minimumCoordinates: Int,
): List<Coordinate> {
    val geometry = rawGeometry as? JsonArray
        ?: throw IOException("An Overpass geometry is not a JSON array.")
    if (geometry.size !in minimumCoordinates..limits.maximumCoordinatesPerGeometry) {
        throw IOException("An Overpass geometry is outside the approved coordinate-count limit.")
    }
    coordinateBudget.consume(geometry.size)
    return geometry.map { rawCoordinate ->
        val coordinate = rawCoordinate as? JsonObject
            ?: throw IOException("An Overpass coordinate is not a JSON object.")
        val latitude = coordinate["lat"]?.jsonPrimitive?.doubleOrNull
            ?: throw IOException("An Overpass coordinate does not have a valid latitude.")
        val longitude = coordinate["lon"]?.jsonPrimitive?.doubleOrNull
            ?: throw IOException("An Overpass coordinate does not have a valid longitude.")
        if (!latitude.isFinite() || latitude !in -90.0..90.0) {
            throw IOException("An Overpass latitude is outside the WGS 84 range.")
        }
        if (!longitude.isFinite() || longitude !in -180.0..180.0) {
            throw IOException("An Overpass longitude is outside the WGS 84 range.")
        }
        Coordinate(normalizeZero(longitude), normalizeZero(latitude))
    }
}

private fun assembleRelation(
    relation: ParsedRelation,
    limits: OverpassBuildingLimits,
): AssembledRelation? {
    if (relation.members.isEmpty()) return null
    val memberIds = mutableSetOf<Long>()
    val outerSegments = mutableListOf<Segment>()
    val innerSegments = mutableListOf<Segment>()
    for (member in relation.members) {
        if (member.wayId <= 0L || member.role == UNSUPPORTED_MEMBER_ROLE || member.geometry == null) return null
        if (!memberIds.add(member.wayId)) return null
        val segment = Segment(member.wayId, member.geometry)
        when (member.role) {
            "", "outer" -> outerSegments += segment
            "inner" -> innerSegments += segment
            else -> return null
        }
    }
    if (outerSegments.isEmpty()) return null
    val outerRings = stitchRings(outerSegments, limits.maximumOutputVertices) ?: return null
    val innerRings = stitchRings(innerSegments, limits.maximumOutputVertices) ?: return null
    return AssembledRelation(
        outerRings = outerRings,
        innerRingCount = innerRings.size,
        memberWayIds = memberIds,
    )
}

private fun stitchRings(
    sourceSegments: List<Segment>,
    maximumVertices: Int,
): List<List<Coordinate>>? {
    if (sourceSegments.isEmpty()) return emptyList()
    val remaining = sourceSegments.sortedBy { it.wayId }.toMutableList()
    val rings = mutableListOf<List<Coordinate>>()
    var assembledVertices = 0
    while (remaining.isNotEmpty()) {
        val chain = remaining.removeAt(0).coordinates.toMutableList()
        while (!isClosed(chain)) {
            val endpoint = chain.last()
            val nextIndex = remaining.indexOfFirst { segment ->
                segment.coordinates.first() == endpoint || segment.coordinates.last() == endpoint
            }
            if (nextIndex < 0) return null
            val next = remaining.removeAt(nextIndex).coordinates
            val oriented = if (next.first() == endpoint) next else next.asReversed()
            chain.addAll(oriented.drop(1))
            if (chain.size > maximumVertices) return null
        }
        if (!isValidRing(chain)) return null
        assembledVertices = checkedCountAdd(
            assembledVertices,
            chain.size,
            "The relation ring vertex count overflowed.",
        )
        if (assembledVertices > maximumVertices) return null
        rings += chain
    }
    return rings
}

private fun isClosed(coordinates: List<Coordinate>): Boolean =
    coordinates.size >= 2 && coordinates.first() == coordinates.last()

private fun isValidRing(coordinates: List<Coordinate>): Boolean =
    coordinates.size >= 4 &&
        isClosed(coordinates) &&
        coordinates.dropLast(1).distinct().size >= 3

private fun validateRequest(request: OverpassBuildingProcessRequest) {
    if (!request.rawFile.isFile) throw IOException("The Overpass response is not a regular file.")
    if (request.rawFile.canonicalFile == request.outputGeoJsonFile.canonicalFile) {
        throw IOException("The raw Overpass response and processed output must be different files.")
    }
    val sourceUri = try {
        URI(request.sourceUrl)
    } catch (error: Exception) {
        throw IllegalArgumentException("The Overpass source URL is invalid.", error)
    }
    require(
        sourceUri.scheme.equals("https", ignoreCase = true) &&
            !sourceUri.host.isNullOrBlank() &&
            sourceUri.userInfo == null &&
            sourceUri.fragment == null,
    ) { "The Overpass source must be an HTTPS URL without credentials or a fragment." }
    require(request.query.isNotBlank()) { "The Overpass query cannot be blank." }
    require(request.query.length <= request.limits.maximumQueryCharacters) {
        "The Overpass query exceeds the approved text limit."
    }
    require(!request.query.contains('\u0000')) { "The Overpass query contains an invalid control character." }
    require(request.queriedAtEpochMillis >= 0L) { "The Overpass source timestamp cannot be negative." }
    require(
        request.expectedRawSha256 == null || LOWERCASE_SHA256.matches(request.expectedRawSha256),
    ) { "The expected Overpass hash must be a lowercase SHA-256 digest." }
}

private fun readBounded(file: File, maximumBytes: Int): ByteArray {
    val declaredLength = file.length()
    if (declaredLength !in 1L..maximumBytes.toLong()) {
        throw IOException("The Overpass response is outside the approved file-size limit.")
    }
    val output = ByteArrayOutputStream(declaredLength.toInt())
    FileInputStream(file).use { input ->
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total = checkedCountAdd(total, read, "The Overpass response size overflowed.")
            if (total > maximumBytes) {
                throw IOException("The Overpass response exceeds the approved file-size limit.")
            }
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}

private fun validateJsonDepth(bytes: ByteArray, maximumDepth: Int) {
    var depth = 0
    var inString = false
    var escaped = false
    bytes.forEach { rawByte ->
        val character = (rawByte.toInt() and 0xFF).toChar()
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > maximumDepth) {
                        throw IOException("The Overpass JSON exceeds the approved nesting depth.")
                    }
                }

                '}', ']' -> {
                    depth -= 1
                    if (depth < 0) throw IOException("The Overpass JSON nesting is malformed.")
                }
            }
        }
    }
    if (inString || escaped || depth != 0) {
        throw IOException("The Overpass JSON nesting is incomplete.")
    }
}

private fun decodeStrictUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: Exception) {
    throw IOException("The Overpass response is not valid UTF-8.", error)
}

private fun writeGeoJsonAtomically(
    request: OverpassBuildingProcessRequest,
    rawSha256: String,
    features: List<BuildingFeature>,
    sourceElementCount: Int,
    vertexCount: Int,
    omittedInnerRingCount: Int,
    unsupportedElementCount: Int,
    sourceTimestampOsmBase: String?,
): Publication {
    val target = request.outputGeoJsonFile
    val parent = target.absoluteFile.parentFile
        ?: throw IOException("The processed GeoJSON destination is invalid.")
    if (!parent.isDirectory && !parent.mkdirs()) {
        throw IOException("The processed GeoJSON directory could not be created.")
    }
    val staging = File.createTempFile("building-", ".geojson.part", parent)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        lateinit var boundedOutput: BoundedOutputStream
        FileOutputStream(staging).use { fileOutput ->
            boundedOutput = BoundedOutputStream(
                DigestOutputStream(fileOutput, digest),
                request.limits.maximumOutputBytes,
            )
            val writer = BufferedWriter(OutputStreamWriter(boundedOutput, StandardCharsets.UTF_8))
            writeGeoJson(
                writer = writer,
                request = request,
                rawSha256 = rawSha256,
                features = features,
                sourceElementCount = sourceElementCount,
                vertexCount = vertexCount,
                omittedInnerRingCount = omittedInnerRingCount,
                unsupportedElementCount = unsupportedElementCount,
                sourceTimestampOsmBase = sourceTimestampOsmBase,
            )
            writer.flush()
            fileOutput.fd.sync()
        }
        val publication = Publication(digest.digest().toHex(), boundedOutput.byteCount)
        if (target.exists()) {
            if (!target.isFile || target.length() != publication.byteCount || sha256(target) != publication.sha256) {
                throw IOException("The processed GeoJSON destination already contains different data.")
            }
            return publication
        }
        if (!staging.renameTo(target)) {
            if (!target.isFile || target.length() != publication.byteCount || sha256(target) != publication.sha256) {
                throw IOException("The processed GeoJSON could not be published atomically.")
            }
        }
        return publication
    } finally {
        if (staging.exists()) staging.delete()
    }
}

private fun writeGeoJson(
    writer: BufferedWriter,
    request: OverpassBuildingProcessRequest,
    rawSha256: String,
    features: List<BuildingFeature>,
    sourceElementCount: Int,
    vertexCount: Int,
    omittedInnerRingCount: Int,
    unsupportedElementCount: Int,
    sourceTimestampOsmBase: String?,
) {
    writer.append("{\"type\":\"FeatureCollection\",")
    writer.append("\"coordinate_reference_system\":\"EPSG:4326\",")
    writer.append("\"attribution\":").append(jsonString(OSM_ATTRIBUTION)).append(',')
    writer.append("\"source\":{")
    writer.append("\"url\":").append(jsonString(request.sourceUrl)).append(',')
    writer.append("\"query\":").append(jsonString(request.query)).append(',')
    writer.append("\"queried_at_epoch_millis\":").append(request.queriedAtEpochMillis.toString()).append(',')
    sourceTimestampOsmBase?.let { timestamp ->
        writer.append("\"timestamp_osm_base\":").append(jsonString(timestamp)).append(',')
    }
    writer.append("\"raw_sha256\":").append(jsonString(rawSha256)).append("},")
    writer.append("\"processing\":{")
    writer.append("\"scope\":").append(
        jsonString("Building footprints only; relation inner rings are omitted from processed geometry."),
    ).append(',')
    writer.append("\"source_elements\":").append(sourceElementCount.toString()).append(',')
    writer.append("\"features\":").append(features.size.toString()).append(',')
    writer.append("\"vertices\":").append(vertexCount.toString()).append(',')
    writer.append("\"inner_rings_omitted\":").append(omittedInnerRingCount.toString()).append(',')
    writer.append("\"unsupported_elements\":").append(unsupportedElementCount.toString()).append("},")
    writer.append("\"features\":[")
    features.forEachIndexed { index, feature ->
        if (index > 0) writer.append(',')
        writeFeature(writer, feature)
    }
    writer.append("]}")
}

private fun writeFeature(writer: BufferedWriter, feature: BuildingFeature) {
    writer.append("{\"type\":\"Feature\",\"id\":")
        .append(jsonString("${feature.osmType}/${feature.osmId}"))
        .append(",\"properties\":{")
    writer.append("\"osm_type\":").append(jsonString(feature.osmType)).append(',')
    writer.append("\"osm_id\":").append(feature.osmId.toString())
    feature.tags.building?.let { value ->
        writer.append(",\"building\":").append(jsonString(value))
    }
    feature.tags.buildingPart?.let { value ->
        writer.append(",\"building_part\":").append(jsonString(value))
    }
    feature.tags.height?.let { value ->
        writer.append(",\"height\":").append(jsonString(value))
    }
    feature.tags.buildingLevels?.let { value ->
        writer.append(",\"building_levels\":").append(jsonString(value))
    }
    feature.tags.roofHeight?.let { value ->
        writer.append(",\"roof_height\":").append(jsonString(value))
    }
    feature.tags.roofLevels?.let { value ->
        writer.append(",\"roof_levels\":").append(jsonString(value))
    }
    feature.tags.minimumHeight?.let { value ->
        writer.append(",\"min_height\":").append(jsonString(value))
    }
    feature.tags.buildingMinimumLevel?.let { value ->
        writer.append(",\"building_min_level\":").append(jsonString(value))
    }
    feature.tags.roofShape?.let { value ->
        writer.append(",\"roof_shape\":").append(jsonString(value))
    }
    if (feature.osmType == OSM_TYPE_RELATION) {
        writer.append(",\"inner_rings_omitted\":").append(feature.omittedInnerRings.toString())
        writer.append(",\"geometry_limit\":").append(
            jsonString("Inner rings are omitted; this geometry is not area-complete."),
        )
    }
    writer.append("},\"geometry\":{")
    if (feature.osmType == OSM_TYPE_WAY) {
        writer.append("\"type\":\"Polygon\",\"coordinates\":[")
        writeRing(writer, feature.rings.single())
        writer.append(']')
    } else {
        writer.append("\"type\":\"MultiPolygon\",\"coordinates\":[")
        feature.rings.forEachIndexed { index, ring ->
            if (index > 0) writer.append(',')
            writer.append('[')
            writeRing(writer, ring)
            writer.append(']')
        }
        writer.append(']')
    }
    writer.append("}}")
}

private fun writeRing(writer: BufferedWriter, ring: List<Coordinate>) {
    writer.append('[')
    ring.forEachIndexed { index, coordinate ->
        if (index > 0) writer.append(',')
        writer.append('[')
            .append(coordinate.longitude.toString())
            .append(',')
            .append(coordinate.latitude.toString())
            .append(']')
    }
    writer.append(']')
}

private fun parseCrs(geoKeys: List<Int>?, geoAscii: String?): TiffCrs? {
    if (geoKeys == null) {
        val citation = geoAscii?.trimEnd('\u0000', '|')?.takeIf { it.isNotBlank() }
        return citation?.let { TiffCrs(null, it, null) }
    }
    if (geoKeys.size < 4 || geoKeys[0] != 1) {
        throw IOException("The GeoTIFF key directory header is malformed.")
    }
    val keyCount = geoKeys[3]
    val expectedValues = checkedCountAdd(4, keyCount * 4, "The GeoTIFF key count overflowed.")
    if (keyCount < 0 || expectedValues > geoKeys.size) {
        throw IOException("The GeoTIFF key directory is truncated.")
    }
    var epsgCode: Int? = null
    var sourceKey: Int? = null
    var citation: String? = null
    repeat(keyCount) { index ->
        val base = 4 + index * 4
        val keyId = geoKeys[base]
        val location = geoKeys[base + 1]
        val count = geoKeys[base + 2]
        val valueOffset = geoKeys[base + 3]
        if (keyId == PROJECTED_CRS_GEO_KEY || keyId == GEOGRAPHIC_CRS_GEO_KEY) {
            if (location == 0 && count == 1 && valueOffset in 1..65_535 && valueOffset != USER_DEFINED_CRS) {
                if (epsgCode == null || keyId == PROJECTED_CRS_GEO_KEY) {
                    epsgCode = valueOffset
                    sourceKey = keyId
                }
            }
        }
        if ((keyId == PROJECTED_CITATION_GEO_KEY || keyId == GEOGRAPHIC_CITATION_GEO_KEY) &&
            location == TAG_GEO_ASCII_PARAMS && count > 0 && geoAscii != null
        ) {
            val end = valueOffset.toLong() + count.toLong()
            if (valueOffset < 0 || end > geoAscii.length.toLong()) {
                throw IOException("A GeoTIFF citation points outside the ASCII parameter tag.")
            }
            citation = geoAscii.substring(valueOffset, end.toInt())
                .trimEnd('\u0000', '|')
                .takeIf { it.isNotBlank() }
        }
    }
    if (citation == null) {
        citation = geoAscii?.trimEnd('\u0000', '|')?.takeIf { it.isNotBlank() }
    }
    return if (epsgCode == null && citation == null) null else TiffCrs(epsgCode, citation, sourceKey)
}

private fun validatePerBandValues(values: List<Int>, bandCount: Int, label: String) {
    if (values.isNotEmpty() && values.size != 1 && values.size != bandCount) {
        throw IOException("The TIFF $label values do not match the band count.")
    }
}

private fun tiffTypeBytes(type: Int): Int = when (type) {
    TYPE_BYTE, TYPE_ASCII, TYPE_SIGNED_BYTE, TYPE_UNDEFINED -> 1
    TYPE_SHORT, TYPE_SIGNED_SHORT -> 2
    TYPE_LONG, TYPE_SIGNED_LONG, TYPE_FLOAT, TYPE_IFD -> 4
    TYPE_RATIONAL, TYPE_SIGNED_RATIONAL, TYPE_DOUBLE, TYPE_LONG8, TYPE_SIGNED_LONG8, TYPE_IFD8 -> 8
    else -> throw IOException("A TIFF metadata tag uses an unknown field type.")
}

private fun decodeUnsigned(
    bytes: ByteArray,
    offset: Int,
    byteCount: Int,
    order: TiffByteOrder,
): Long {
    if (byteCount !in 1..8 || offset < 0 || offset > bytes.size - byteCount) {
        throw IOException("A TIFF integer value is truncated.")
    }
    val mostSignificantIndex = if (order == TiffByteOrder.BIG_ENDIAN) offset else offset + byteCount - 1
    if (byteCount == 8 && (bytes[mostSignificantIndex].toInt() and 0x80) != 0) {
        throw IOException("A TIFF unsigned integer exceeds the supported 63-bit range.")
    }
    var result = 0L
    if (order == TiffByteOrder.BIG_ENDIAN) {
        repeat(byteCount) { index -> result = (result shl 8) or (bytes[offset + index].toLong() and 0xFFL) }
    } else {
        repeat(byteCount) { index ->
            result = result or ((bytes[offset + index].toLong() and 0xFFL) shl (index * 8))
        }
    }
    return result
}

private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IOException("A TIFF metadata size overflowed.", error)
}

private fun checkedMultiply(left: Long, right: Long): Long = try {
    Math.multiplyExact(left, right)
} catch (error: ArithmeticException) {
    throw IOException("A TIFF metadata size overflowed.", error)
}

private fun checkedCountAdd(left: Int, right: Int, message: String): Int = try {
    Math.addExact(left, right)
} catch (error: ArithmeticException) {
    throw IOException(message, error)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun jsonString(value: String): String = JsonPrimitive(value).toString()

private fun normalizeZero(value: Double): Double = if (value == 0.0) 0.0 else value

private fun String.anyInvalidControl(): Boolean = any { character ->
    character.code < 0x20 && character != '\t' && character != '\r' && character != '\n'
}

private const val CLASSIC_TIFF_MAGIC = 42
private const val BIG_TIFF_MAGIC = 43
private const val MINIMUM_TIFF_BYTES = 8L
private const val MAXIMUM_TIFF_FILE_BYTES = 16L * 1024L * 1024L * 1024L
private const val MAXIMUM_TIFF_IFD_ENTRIES = 65_536
private const val MAXIMUM_TIFF_TAG_BYTES = 8 * 1024 * 1024
private const val MAXIMUM_TIFF_METADATA_BYTES = 32 * 1024 * 1024
private const val MAXIMUM_TIFF_LIST_VALUES = 65_536
private const val MAXIMUM_TIFF_DIMENSION = 10_000_000L
private const val MAXIMUM_TIFF_BANDS = 4_096
private const val MAXIMUM_NO_DATA_CHARACTERS = 256

private const val TYPE_BYTE = 1
private const val TYPE_ASCII = 2
private const val TYPE_SHORT = 3
private const val TYPE_LONG = 4
private const val TYPE_RATIONAL = 5
private const val TYPE_SIGNED_BYTE = 6
private const val TYPE_UNDEFINED = 7
private const val TYPE_SIGNED_SHORT = 8
private const val TYPE_SIGNED_LONG = 9
private const val TYPE_SIGNED_RATIONAL = 10
private const val TYPE_FLOAT = 11
private const val TYPE_DOUBLE = 12
private const val TYPE_IFD = 13
private const val TYPE_LONG8 = 16
private const val TYPE_SIGNED_LONG8 = 17
private const val TYPE_IFD8 = 18

private const val TAG_IMAGE_WIDTH = 256
private const val TAG_IMAGE_LENGTH = 257
private const val TAG_BITS_PER_SAMPLE = 258
private const val TAG_COMPRESSION = 259
private const val TAG_SAMPLES_PER_PIXEL = 277
private const val TAG_SAMPLE_FORMAT = 339
private const val TAG_MODEL_PIXEL_SCALE = 33_550
private const val TAG_MODEL_TIEPOINT = 33_922
private const val TAG_MODEL_TRANSFORMATION = 34_264
private const val TAG_GEO_KEY_DIRECTORY = 34_735
private const val TAG_GEO_ASCII_PARAMS = 34_737
private const val TAG_GDAL_NO_DATA = 42_113
private const val PROJECTED_CRS_GEO_KEY = 3_072
private const val PROJECTED_CITATION_GEO_KEY = 3_073
private const val GEOGRAPHIC_CRS_GEO_KEY = 2_048
private const val GEOGRAPHIC_CITATION_GEO_KEY = 2_049
private const val USER_DEFINED_CRS = 32_767

private val RELEVANT_TIFF_TAGS = setOf(
    TAG_IMAGE_WIDTH,
    TAG_IMAGE_LENGTH,
    TAG_BITS_PER_SAMPLE,
    TAG_COMPRESSION,
    TAG_SAMPLES_PER_PIXEL,
    TAG_SAMPLE_FORMAT,
    TAG_MODEL_PIXEL_SCALE,
    TAG_MODEL_TIEPOINT,
    TAG_MODEL_TRANSFORMATION,
    TAG_GEO_KEY_DIRECTORY,
    TAG_GEO_ASCII_PARAMS,
    TAG_GDAL_NO_DATA,
)
private val INTEGER_FIELD_TYPES = setOf(TYPE_SHORT, TYPE_LONG, TYPE_LONG8)

private const val MAXIMUM_OVERPASS_RAW_BYTES = 16 * 1024 * 1024
private const val MAXIMUM_JSON_DEPTH = 256
private const val MAXIMUM_OVERPASS_ELEMENTS = 200_000
private const val MAXIMUM_BUILDING_FEATURES = 50_000
private const val MAXIMUM_BUILDING_VERTICES = 1_000_000
private const val MAXIMUM_SOURCE_COORDINATES = 2_000_000
private const val MAXIMUM_RELATION_MEMBERS = 50_000
private const val MAXIMUM_OVERPASS_QUERY_CHARACTERS = 256 * 1024
private const val MAXIMUM_GEOJSON_OUTPUT_BYTES = 128 * 1024 * 1024
private const val MAXIMUM_OSM_TAG_CHARACTERS = 1_024
private const val MAXIMUM_OSM_SOURCE_TIMESTAMP_CHARACTERS = 64
private const val STREAM_BUFFER_BYTES = 64 * 1024
private const val OSM_TYPE_WAY = "way"
private const val OSM_TYPE_RELATION = "relation"
private const val UNSUPPORTED_MEMBER_ROLE = "__unsupported__"
private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
private val INACTIVE_OSM_TAG_VALUES = setOf("no", "0", "false")
private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
private val OSM_SOURCE_TIMESTAMP_PATTERN = Regex(
    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$",
)
