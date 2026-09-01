package com.gecesars.atxplan.data.regulatory

import android.database.sqlite.SQLiteDatabase
import com.gecesars.atxplan.data.dataset.AllowlistedHttpsRegionalHttpTransport
import com.gecesars.atxplan.data.dataset.BundledIbgeDatasetRepository
import com.gecesars.atxplan.domain.contour.RegulatoryCensusGeometrySnapshot
import com.gecesars.atxplan.domain.contour.RegulatoryCensusPolygon
import com.gecesars.atxplan.domain.contour.RegulatoryCensusRing
import com.gecesars.atxplan.domain.contour.RegulatoryCensusSector
import com.gecesars.atxplan.domain.contour.RegulatoryMunicipalityContext
import com.gecesars.atxplan.domain.dataset.IbgeCensusSectorAttribute
import com.gecesars.atxplan.domain.model.GeoPoint
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class IbgeCensusGeometryRepository(
    private val root: File,
    private val ibgeAttributes: BundledIbgeDatasetRepository,
    private val artifactStore: VerifiedRemoteArtifactStore = VerifiedRemoteArtifactStore(
        root = root,
        transport = AllowlistedHttpsRegionalHttpTransport(
            allowedHosts = setOf(IBGE_DOWNLOAD_HOST),
            readTimeoutMillis = 120_000,
        ),
    ),
) {
    suspend fun prepareMunicipality(
        municipality: RegulatoryMunicipalityContext,
        transmitter: GeoPoint,
        onProgress: (RegulatoryArtifactProgress) -> Unit = {},
    ): RegulatoryCensusGeometrySnapshot {
        val officialMunicipality = ibgeAttributes.municipalityByCode(municipality.ibgeCode)
            ?: throw IOException("The selected municipality is absent from the verified offline IBGE index.")
        if (
            officialMunicipality.name != municipality.name ||
            officialMunicipality.stateAbbreviation != municipality.stateAbbreviation
        ) {
            throw IOException("The selected municipality identity conflicts with the verified offline IBGE index.")
        }
        val state = municipality.stateAbbreviation
        require(STATE_ABBREVIATION.matches(state)) { "The IBGE state abbreviation is invalid." }
        val sourceUrl = "$IBGE_GPKG_BASE_URL/$state/${state}_setores_CD2022.gpkg"
        val artifact = artifactStore.acquire(
            key = "ibge-census-geometry-2022-${state.lowercase()}",
            url = sourceUrl,
            extension = "gpkg",
            maximumBytes = MAXIMUM_STATE_GPKG_BYTES,
            progressLabel = "IBGE $state census-sector geometry",
            onProgress = onProgress,
        )
        onProgress(
            RegulatoryArtifactProgress(
                RegulatoryArtifactPhase.PROCESSING,
                "IBGE ${municipality.name} urban sectors",
                artifact.byteCount,
                artifact.byteCount,
            ),
        )
        val attributes = ibgeAttributes.urbanSectorAttributes(municipality.ibgeCode)
            .associateBy(IbgeCensusSectorAttribute::sectorCode)
        if (attributes.isEmpty()) {
            throw IOException("The selected municipality has no urban sectors in the verified IBGE index.")
        }
        val geometry = readMunicipalityGeometry(artifact.file, municipality, attributes, transmitter)
        return RegulatoryCensusGeometrySnapshot(
            municipality = municipality,
            sectors = geometry.sectors,
            transmitterInsideMunicipality = geometry.transmitterInsideMunicipality,
            sourceUrl = artifact.sourceUrl,
            sourcePageUrl = IBGE_SOURCE_PAGE_URL,
            sourceSha256 = artifact.sha256,
            sourceByteCount = artifact.byteCount,
            sourceEtag = artifact.etag,
            sourceLastModified = artifact.lastModified,
        )
    }

    private fun readMunicipalityGeometry(
        file: File,
        municipality: RegulatoryMunicipalityContext,
        attributes: Map<String, IbgeCensusSectorAttribute>,
        transmitter: GeoPoint,
    ): MunicipalityGeometryRead {
        validateSqliteHeader(file)
        val table = "${municipality.stateAbbreviation}_setores_CD2022"
        val result = ArrayList<RegulatoryCensusSector>(attributes.size)
        val foundCodes = hashSetOf<String>()
        var transmitterInsideMunicipality = false
        openReadOnly(file).use { database ->
            validateGeoPackage(database, table)
            transmitterInsideMunicipality = database.rawQuery(
                "SELECT s.geom FROM $table s JOIN rtree_${table}_geom r ON s.ROWID = r.id " +
                    "WHERE s.CD_MUN = ? AND r.minx <= ? AND r.maxx >= ? " +
                    "AND r.miny <= ? AND r.maxy >= ?",
                arrayOf(
                    municipality.ibgeCode,
                    transmitter.longitude.toString(),
                    transmitter.longitude.toString(),
                    transmitter.latitude.toString(),
                    transmitter.latitude.toString(),
                ),
            ).use { cursor ->
                var contains = false
                var candidates = 0
                while (cursor.moveToNext()) {
                    candidates += 1
                    if (candidates > MAXIMUM_POINT_CANDIDATES) {
                        throw IOException("The IBGE municipality point query exceeded its safety bound.")
                    }
                    if (geometryContains(parseGeoPackageGeometry(cursor.getBlob(0)), transmitter)) {
                        contains = true
                        break
                    }
                }
                contains
            }
            database.rawQuery(
                "SELECT CD_SETOR, AREA_KM2, geom FROM $table " +
                    "WHERE CD_MUN = ? AND CD_SIT = '1' ORDER BY CD_SETOR",
                arrayOf(municipality.ibgeCode),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (result.size >= MAXIMUM_MUNICIPALITY_SECTORS) {
                        throw IOException("The IBGE municipality geometry exceeds its sector safety bound.")
                    }
                    val code = cursor.getString(0)
                    val attribute = attributes[code]
                        ?: throw IOException("IBGE geometry sector $code has no matching population attribute.")
                    if (!foundCodes.add(code)) throw IOException("The IBGE GeoPackage repeats sector $code.")
                    val sourceArea = cursor.getDouble(1)
                    if (!sourceArea.isFinite() || sourceArea < 0.0 ||
                        kotlin.math.abs(sourceArea - attribute.areaKm2) > AREA_TOLERANCE_KM2
                    ) {
                        throw IOException("IBGE sector $code has inconsistent area attributes.")
                    }
                    result += RegulatoryCensusSector(
                        sectorCode = code,
                        areaKm2 = attribute.areaKm2,
                        residentPopulation = attribute.residentPopulation,
                        polygons = parseGeoPackageGeometry(cursor.getBlob(2)),
                    )
                }
            }
        }
        val missing = attributes.keys - foundCodes
        if (missing.isNotEmpty()) {
            throw IOException("The IBGE GeoPackage is missing ${missing.size} indexed urban sector geometries.")
        }
        if (result.size != attributes.size) {
            throw IOException("The IBGE geometry and population sector counts are inconsistent.")
        }
        return MunicipalityGeometryRead(result, transmitterInsideMunicipality)
    }

    private fun validateGeoPackage(database: SQLiteDatabase, table: String) {
        val quickCheck = database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.columnCount != 1) null else cursor.getString(0)
        }
        if (quickCheck != "ok") throw IOException("The downloaded IBGE GeoPackage failed SQLite quick_check.")
        val content = database.rawQuery(
            "SELECT data_type, srs_id FROM gpkg_contents WHERE table_name = ?",
            arrayOf(table),
        ).use { cursor ->
            if (!cursor.moveToFirst() || !cursor.isLast) null else cursor.getString(0) to cursor.getInt(1)
        }
        if (content != "features" to SIRGAS_2000_SRS_ID) {
            throw IOException("The downloaded IBGE GeoPackage has an unexpected feature-table contract.")
        }
        val geometry = database.rawQuery(
            "SELECT column_name, geometry_type_name, srs_id, z, m FROM gpkg_geometry_columns " +
                "WHERE table_name = ?",
            arrayOf(table),
        ).use { cursor ->
            if (!cursor.moveToFirst() || !cursor.isLast) null else listOf(
                cursor.getString(0), cursor.getString(1), cursor.getInt(2).toString(),
                cursor.getInt(3).toString(), cursor.getInt(4).toString(),
            )
        }
        if (geometry != listOf("geom", "POLYGON", "$SIRGAS_2000_SRS_ID", "0", "0")) {
            throw IOException("The downloaded IBGE GeoPackage geometry metadata is unsupported.")
        }
        val requiredColumns = database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if (!requiredColumns.containsAll(setOf("CD_SETOR", "CD_MUN", "CD_SIT", "AREA_KM2", "geom"))) {
            throw IOException("The downloaded IBGE GeoPackage is missing required columns.")
        }
    }

    private fun validateSqliteHeader(file: File) {
        if (!file.isFile || file.length() !in MINIMUM_GPKG_BYTES..MAXIMUM_STATE_GPKG_BYTES) {
            throw IOException("The downloaded IBGE GeoPackage is outside its approved byte bound.")
        }
        val header = ByteArray(SQLITE_HEADER.size)
        FileInputStream(file).use { input ->
            if (input.read(header) != header.size || !header.contentEquals(SQLITE_HEADER)) {
                throw IOException("The downloaded IBGE artifact is not a SQLite GeoPackage.")
            }
        }
    }

    private fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )
}

private data class MunicipalityGeometryRead(
    val sectors: List<RegulatoryCensusSector>,
    val transmitterInsideMunicipality: Boolean,
)

private fun geometryContains(polygons: List<RegulatoryCensusPolygon>, point: GeoPoint): Boolean =
    polygons.any { polygon ->
        pointInClosedRing(point, polygon.rings.first().points) &&
            polygon.rings.drop(1).none { ring -> pointInClosedRing(point, ring.points) }
    }

private fun pointInClosedRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
    var inside = false
    var previous = ring.last()
    ring.forEach { current ->
        val crosses = (current.latitude > point.latitude) != (previous.latitude > point.latitude) &&
            point.longitude < (previous.longitude - current.longitude) *
            (point.latitude - current.latitude) /
            (previous.latitude - current.latitude) + current.longitude
        if (crosses) inside = !inside
        previous = current
    }
    return inside
}

internal fun parseGeoPackageGeometry(blob: ByteArray): List<RegulatoryCensusPolygon> {
    if (blob.size !in MINIMUM_GEOMETRY_BYTES..MAXIMUM_GEOMETRY_BYTES) {
        throw IOException("An IBGE GeoPackage geometry exceeds its byte safety bound.")
    }
    if (blob[0] != 'G'.code.toByte() || blob[1] != 'P'.code.toByte() || blob[2].toInt() != 0) {
        throw IOException("An IBGE feature has an invalid GeoPackage geometry header.")
    }
    val flags = blob[3].toInt() and 0xff
    val littleEndian = flags and 0x01 != 0
    val envelopeCode = flags ushr 1 and 0x07
    val empty = flags and 0x10 != 0
    if (empty) throw IOException("An IBGE urban sector contains empty geometry.")
    val envelopeBytes = when (envelopeCode) {
        0 -> 0
        1 -> 32
        2, 3 -> 48
        4 -> 64
        else -> throw IOException("An IBGE GeoPackage geometry has a reserved envelope code.")
    }
    val headerOrder = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
    val header = ByteBuffer.wrap(blob, 4, 4).order(headerOrder)
    if (header.int != SIRGAS_2000_SRS_ID) {
        throw IOException("An IBGE geometry does not declare SIRGAS 2000.")
    }
    val wkbOffset = 8 + envelopeBytes
    if (wkbOffset >= blob.size) throw IOException("An IBGE GeoPackage geometry is truncated.")
    return WkbPolygonReader(blob, wkbOffset).readGeometry()
}

private class WkbPolygonReader(
    private val bytes: ByteArray,
    start: Int,
) {
    private var cursor = start
    private var coordinateCount = 0L

    fun readGeometry(): List<RegulatoryCensusPolygon> {
        val geometry = readGeometryInternal()
        if (cursor != bytes.size) throw IOException("An IBGE WKB geometry contains trailing bytes.")
        return geometry
    }

    private fun readGeometryInternal(): List<RegulatoryCensusPolygon> {
        val order = when (readUnsignedByte()) {
            0 -> ByteOrder.BIG_ENDIAN
            1 -> ByteOrder.LITTLE_ENDIAN
            else -> throw IOException("An IBGE WKB geometry has an invalid byte-order marker.")
        }
        val rawType = readUnsignedInt(order)
        if (rawType and EWKB_FLAG_MASK != 0L) {
            throw IOException("The IBGE GeoPackage unexpectedly uses EWKB flags.")
        }
        val dimensions = (rawType / 1_000L).toInt()
        val baseType = (rawType % 1_000L).toInt()
        if (dimensions != 0) throw IOException("The IBGE geometry unexpectedly contains Z or M coordinates.")
        return when (baseType) {
            WKB_POLYGON -> listOf(readPolygon(order))
            WKB_MULTI_POLYGON -> {
                val count = readCount(order, MAXIMUM_GEOMETRY_POLYGONS, "polygon")
                buildList(count) {
                    repeat(count) {
                        val nested = readGeometryInternal()
                        if (nested.size != 1) throw IOException("An IBGE multipolygon contains a non-polygon member.")
                        add(nested.single())
                    }
                }
            }
            else -> throw IOException("The IBGE geometry type $baseType is unsupported.")
        }
    }

    private fun readPolygon(order: ByteOrder): RegulatoryCensusPolygon {
        val ringCount = readCount(order, MAXIMUM_GEOMETRY_RINGS, "ring")
        if (ringCount == 0) throw IOException("An IBGE polygon contains no rings.")
        val rings = buildList(ringCount) {
            repeat(ringCount) {
                val pointCount = readCount(order, MAXIMUM_GEOMETRY_POINTS, "point")
                if (pointCount < 4) throw IOException("An IBGE polygon ring contains fewer than four points.")
                coordinateCount += pointCount
                if (coordinateCount > MAXIMUM_GEOMETRY_POINTS) {
                    throw IOException("An IBGE feature exceeds its coordinate safety bound.")
                }
                val points = List(pointCount) {
                    val longitude = readDouble(order)
                    val latitude = readDouble(order)
                    try {
                        GeoPoint(latitude, longitude)
                    } catch (error: IllegalArgumentException) {
                        throw IOException("An IBGE polygon contains an invalid coordinate.", error)
                    }
                }
                if (points.first() != points.last()) throw IOException("An IBGE polygon ring is not closed.")
                add(RegulatoryCensusRing(points))
            }
        }
        return RegulatoryCensusPolygon(rings)
    }

    private fun readCount(order: ByteOrder, maximum: Int, label: String): Int {
        val value = readUnsignedInt(order)
        if (value !in 0L..maximum.toLong()) {
            throw IOException("An IBGE WKB $label count exceeds its safety bound.")
        }
        return value.toInt()
    }

    private fun readUnsignedByte(): Int {
        requireRemaining(1)
        return bytes[cursor++].toInt() and 0xff
    }

    private fun readUnsignedInt(order: ByteOrder): Long {
        requireRemaining(4)
        val value = ByteBuffer.wrap(bytes, cursor, 4).order(order).int.toLong() and 0xffff_ffffL
        cursor += 4
        return value
    }

    private fun readDouble(order: ByteOrder): Double {
        requireRemaining(8)
        val value = ByteBuffer.wrap(bytes, cursor, 8).order(order).double
        cursor += 8
        if (!value.isFinite()) throw IOException("An IBGE WKB coordinate is non-finite.")
        return value
    }

    private fun requireRemaining(count: Int) {
        if (count < 0 || cursor < 0 || cursor > bytes.size || count > bytes.size - cursor) {
            throw IOException("An IBGE WKB geometry is truncated.")
        }
    }
}

private const val IBGE_DOWNLOAD_HOST = "geoftp.ibge.gov.br"
private const val IBGE_GPKG_BASE_URL =
    "https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/" +
        "malhas_de_setores_censitarios__divisoes_intramunicipais/censo_2022/setores/gpkg/UF"
private const val IBGE_SOURCE_PAGE_URL =
    "https://www.ibge.gov.br/geociencias/organizacao-do-territorio/malhas-territoriais/" +
        "26565-malhas-de-setores-censitarios-divisoes-intramunicipais.html"
private const val SIRGAS_2000_SRS_ID = 4_674
private const val MAXIMUM_STATE_GPKG_BYTES = 384L * 1024L * 1024L
private const val MINIMUM_GPKG_BYTES = 4_096L
private const val MAXIMUM_MUNICIPALITY_SECTORS = 100_000
private const val MAXIMUM_POINT_CANDIDATES = 10_000
private const val AREA_TOLERANCE_KM2 = 1e-9
private const val MINIMUM_GEOMETRY_BYTES = 13
private const val MAXIMUM_GEOMETRY_BYTES = 64 * 1024 * 1024
private const val MAXIMUM_GEOMETRY_POLYGONS = 10_000
private const val MAXIMUM_GEOMETRY_RINGS = 10_000
private const val MAXIMUM_GEOMETRY_POINTS = 2_000_000
private const val WKB_POLYGON = 3
private const val WKB_MULTI_POLYGON = 6
private const val EWKB_FLAG_MASK = 0xe000_0000L
private val STATE_ABBREVIATION = Regex("^[A-Z]{2}$")
private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
