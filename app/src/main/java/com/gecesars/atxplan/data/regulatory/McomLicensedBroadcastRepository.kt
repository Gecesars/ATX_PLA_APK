package com.gecesars.atxplan.data.regulatory

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import com.gecesars.atxplan.data.dataset.AllowlistedHttpsRegionalHttpTransport
import com.gecesars.atxplan.domain.contour.BroadcastService
import com.gecesars.atxplan.domain.contour.BroadcastTechnology
import com.gecesars.atxplan.domain.contour.LicensedBroadcastBaselineSnapshot
import com.gecesars.atxplan.domain.contour.LicensedBroadcastLocationBasis
import com.gecesars.atxplan.domain.contour.LicensedBroadcastRole
import com.gecesars.atxplan.domain.contour.LicensedBroadcastStation
import com.gecesars.atxplan.domain.model.GeoPoint
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.cos

internal class McomLicensedBroadcastRepository(
    private val root: File,
    private val artifactStore: VerifiedRemoteArtifactStore = VerifiedRemoteArtifactStore(
        root = root,
        transport = AllowlistedHttpsRegionalHttpTransport(
            allowedHosts = setOf(MCOM_DOWNLOAD_HOST),
            readTimeoutMillis = 120_000,
        ),
    ),
) {
    fun prepareAndQuery(
        service: BroadcastService,
        channel: Int,
        center: GeoPoint,
        maximumDistanceKm: Double,
        onProgress: (RegulatoryArtifactProgress) -> Unit = {},
    ): LicensedBroadcastBaselineSnapshot {
        require(channel in 1..999 && maximumDistanceKm.isFinite() && maximumDistanceKm in 1.0..1_000.0) {
            "The licensed baseline query is outside its supported bounds."
        }
        val artifact = artifactStore.acquire(
            key = "mcom-licensed-broadcast-current",
            url = MCOM_LICENSED_BROADCAST_URL,
            extension = "csv",
            maximumBytes = MAXIMUM_MCOM_CSV_BYTES,
            progressLabel = "MCom/Mosaico licensed broadcast snapshot",
            onProgress = onProgress,
        )
        onProgress(
            RegulatoryArtifactProgress(
                RegulatoryArtifactPhase.PROCESSING,
                "Licensed broadcast index",
                artifact.byteCount,
                artifact.byteCount,
            ),
        )
        val index = prepareIndex(artifact.file, artifact.sha256)
        val metadata = readMetadata(index, artifact.sha256)
        val stations = queryStations(index, service, channel, center, maximumDistanceKm)
        val unlocated = queryUnlocatedSameChannelCount(index, service, channel)
        return LicensedBroadcastBaselineSnapshot(
            stations = stations,
            sourceUrl = artifact.sourceUrl,
            sourcePageUrl = MCOM_SOURCE_PAGE_URL,
            sourceSha256 = artifact.sha256,
            sourceByteCount = artifact.byteCount,
            sourceEtag = artifact.etag,
            sourceLastModified = artifact.lastModified,
            generatedOn = metadata.getValue("generated_on"),
            referenceDate = metadata.getValue("reference_date"),
            sourceRowCount = metadata.getValue("source_row_count").toLong(),
            rejectedRowCount = metadata.getValue("rejected_row_count").toLong(),
            unlocatedSameChannelStationCount = unlocated,
        )
    }

    private fun prepareIndex(source: File, sourceSha256: String): File {
        val directory = File(root, "mcom-licensed-broadcast-index")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Private licensed-broadcast index storage could not be created.")
        }
        val index = File(directory, "$sourceSha256.sqlite")
        if (index.isFile && validateIndex(index, sourceSha256)) return index
        val staging = File.createTempFile(".licensed-", ".sqlite", directory)
        try {
            buildIndex(source, sourceSha256, staging)
            if (!validateIndex(staging, sourceSha256)) {
                throw IOException("The staged licensed-broadcast index failed validation.")
            }
            Os.rename(staging.absolutePath, index.absolutePath)
        } finally {
            staging.delete()
        }
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && INDEX_FILE.matches(file.name) && file.name != index.name
        }.forEach(File::delete)
        return index
    }

    private fun buildIndex(source: File, sourceSha256: String, destination: File) {
        SQLiteDatabase.openOrCreateDatabase(destination, null).use { database ->
            database.execSQL("PRAGMA application_id = $DATABASE_APPLICATION_ID")
            database.execSQL("PRAGMA user_version = $DATABASE_SCHEMA")
            database.execSQL("PRAGMA journal_mode = DELETE")
            database.execSQL("PRAGMA synchronous = FULL")
            database.execSQL(
                "CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
            )
            database.execSQL(
                "CREATE TABLE station(" +
                    "source_id TEXT PRIMARY KEY NOT NULL, basic_plan_id TEXT, service_code INTEGER NOT NULL, " +
                    "raw_service TEXT NOT NULL, technology INTEGER NOT NULL, role INTEGER NOT NULL, " +
                    "channel INTEGER NOT NULL, frequency_mhz REAL NOT NULL, latitude REAL NOT NULL, " +
                    "longitude REAL NOT NULL, municipality_code TEXT, municipality_name TEXT, " +
                    "state_abbreviation TEXT NOT NULL, licensee TEXT, license_id TEXT, licensed_on TEXT, " +
                    "station_class TEXT NOT NULL, erp_kw REAL, antenna_height_agl_m REAL, " +
                    "raw_status TEXT NOT NULL, location_basis INTEGER NOT NULL)",
            )
            database.execSQL(
                "CREATE TABLE unlocated_station(source_id TEXT PRIMARY KEY NOT NULL, " +
                    "raw_service TEXT NOT NULL, channel INTEGER NOT NULL)",
            )
            var sourceRows = 0L
            var rejectedRows = 0L
            var generatedOn: String? = null
            var referenceDate: String? = null
            database.beginTransaction()
            try {
                FileInputStream(source).use { input ->
                    val reader = BoundedDelimitedReader(
                        BufferedReader(InputStreamReader(input, MCOM_CHARSET), 128 * 1024),
                    )
                    val header = reader.readRow()
                        ?: throw IOException("The MCom licensed-broadcast CSV is empty.")
                    if (header.size !in 1..MAXIMUM_CSV_COLUMNS || header.distinct().size != header.size) {
                        throw IOException("The MCom licensed-broadcast CSV header is invalid.")
                    }
                    val columns = header.withIndex().associate { (index, name) -> name to index }
                    if (!columns.keys.containsAll(REQUIRED_COLUMNS)) {
                        val missing = REQUIRED_COLUMNS - columns.keys
                        throw IOException("The MCom licensed-broadcast CSV is missing columns: ${missing.sorted().joinToString()}.")
                    }
                    while (true) {
                        val row = reader.readRow() ?: break
                        sourceRows += 1L
                        if (sourceRows > MAXIMUM_SOURCE_ROWS) {
                            throw IOException("The MCom licensed-broadcast CSV exceeds its row safety bound.")
                        }
                        if (row.size != header.size) {
                            rejectedRows += 1L
                            continue
                        }
                        val sourceGenerated = row.value(columns, "dt_geracao")
                        val sourceReference = row.value(columns, "dt_referencia")
                        generatedOn = mergeDate(generatedOn, sourceGenerated, "generation")
                        referenceDate = mergeDate(referenceDate, sourceReference, "reference")
                        val normalized = normalizeLicensedRow(row, columns)
                        if (normalized == null) {
                            unlocatedLicensedRowOrNull(row, columns)?.let { unresolved ->
                                database.execSQL(
                                    "INSERT OR REPLACE INTO unlocated_station VALUES(?,?,?)",
                                    arrayOf<Any?>(unresolved.sourceId, unresolved.rawService, unresolved.channel),
                                )
                            }
                            rejectedRows += 1L
                            continue
                        }
                        database.execSQL(
                            "INSERT OR REPLACE INTO station VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            normalized.toSqlArguments(),
                        )
                    }
                }
                val acceptedRows = database.rawQuery("SELECT count(*) FROM station", null).use { cursor ->
                    if (!cursor.moveToFirst()) throw IOException("The licensed station count is unavailable.")
                    cursor.getLong(0)
                }
                if (acceptedRows <= 0L || acceptedRows > sourceRows) {
                    throw IOException("The MCom licensed-broadcast normalization produced an invalid station count.")
                }
                val metadata = mapOf(
                    "source_sha256" to sourceSha256,
                    "source_row_count" to sourceRows.toString(),
                    "accepted_row_count" to acceptedRows.toString(),
                    "rejected_row_count" to rejectedRows.toString(),
                    "generated_on" to (generatedOn ?: throw IOException("The MCom source generation date is absent.")),
                    "reference_date" to (referenceDate ?: throw IOException("The MCom source reference date is absent.")),
                    "source_url" to MCOM_LICENSED_BROADCAST_URL,
                    "normalizer" to NORMALIZER_ID,
                )
                metadata.toSortedMap().forEach { (key, value) ->
                    database.execSQL("INSERT INTO metadata(key,value) VALUES(?,?)", arrayOf(key, value))
                }
                database.execSQL("CREATE INDEX station_service_channel_idx ON station(raw_service, channel)")
                database.execSQL("CREATE INDEX station_location_idx ON station(latitude, longitude)")
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            database.execSQL("VACUUM")
        }
    }

    private fun normalizeLicensedRow(
        row: List<String>,
        columns: Map<String, Int>,
    ): IndexedLicensedStation? {
        val rawService = row.value(columns, "SiglaServico").uppercase(Locale.ROOT)
        val classification = classifyService(rawService) ?: return null
        val sourceId = row.value(columns, "_id").takeIf(String::isNotBlank) ?: return null
        val serviceCode = row.value(columns, "NumServico").toIntOrNull() ?: return null
        val channel = row.value(columns, "canalizacao_NumCanal").sourceInt() ?: return null
        if (channel !in 1..999) return null
        val frequency = row.value(columns, "licenca_frequency").sourceDouble()
            ?: row.value(columns, "frequency").sourceDouble()
            ?: return null
        if (frequency <= 0.0) return null
        val licenseId = row.value(columns, "licenca_license_id").ifBlank {
            row.value(columns, "licenca_estacao_NumLicenca")
        }.takeIf(String::isNotBlank)
        val licensedOn = row.value(columns, "licenca_estacao_DataLicenciamento")
            .takeIf(ISO_DATE::matches)
        val licenseStatus = row.value(columns, "sitarwebStatus").uppercase(Locale.ROOT)
        if (licenseId == null && licensedOn == null && licenseStatus != "L") return null
        val licensedPoint = row.sourcePoint(
            columns,
            "licenca_loctx_coordinates_0",
            "licenca_loctx_coordinates_1",
        )
        val point = licensedPoint
            ?: row.sourcePoint(
                columns,
                "licenca_srd_planobasico_MedLongitudeDecimal",
                "licenca_srd_planobasico_MedLatitudeDecimal",
            )
            ?: row.sourcePoint(columns, "locpb_coordinates_0", "locpb_coordinates_1")
            ?: row.sourcePoint(
                columns,
                "municipio_MedLongitudeDecimal",
                "municipio_MedLatitudeDecimal",
            )
            ?: return null
        val municipalityCode = listOf(
            "licenca_endereco_estacao_CodMunicipio",
            "licenca_endereco_estacaoprincipal_CodMunicipio",
            "licenca_srd_planobasico_CodMunicipio",
            "municipio_CodMunicipio",
        ).firstNotNullOfOrNull { column ->
            row.value(columns, column).takeIf { it.matches(SEVEN_DIGIT_CODE) }
        }
        val erp = row.value(columns, "licenca_erp").sourceDouble()
            ?: row.value(columns, "erp").sourceDouble()
        val height = row.value(columns, "licenca_antena_principal_MedHCI").sourceDouble()
        return IndexedLicensedStation(
            sourceId = sourceId.take(MAXIMUM_ID_CHARS),
            basicPlanId = row.value(columns, "IdtPlanoBasico").takeIf(String::isNotBlank)?.take(MAXIMUM_ID_CHARS),
            serviceCode = serviceCode,
            rawService = rawService,
            technology = classification.first,
            role = classification.second,
            channel = channel,
            frequencyMHz = frequency,
            location = point,
            municipalityCode = municipalityCode,
            municipalityName = row.value(columns, "licenca_endereco_estacao_NomeMunicipio")
                .ifBlank { row.value(columns, "licenca_endereco_estacaoprincipal_NomeMunicipio") }
                .takeUnless { it == "-" }.orEmpty()
                .ifBlank { row.value(columns, "licenca_srd_planobasico_NomeMunicipio") }
                .ifBlank { row.value(columns, "NomeMunicipio") }
                .takeIf(String::isNotBlank)?.take(MAXIMUM_TEXT_CHARS),
            stateAbbreviation = row.value(columns, "SiglaUF").uppercase(Locale.ROOT)
                .takeIf { it.matches(STATE_CODE) } ?: return null,
            licensee = row.value(columns, "licensee").takeIf(String::isNotBlank)?.take(MAXIMUM_TEXT_CHARS),
            licenseId = licenseId?.take(MAXIMUM_ID_CHARS),
            licensedOn = licensedOn,
            stationClass = row.value(columns, "licenca_stnClass")
                .ifBlank { row.value(columns, "stnClass") }.take(MAXIMUM_CLASS_CHARS),
            erpKw = erp?.takeIf { it.isFinite() && it > 0.0 },
            antennaHeightAglM = height?.takeIf { it.isFinite() && it > 0.0 },
            rawStatus = listOf(
                "sitarweb=$licenseStatus",
                "license=${row.value(columns, "sitarwebLicenca")}",
                "plan=${row.value(columns, "Status_state")}",
            ).joinToString(";").take(MAXIMUM_STATUS_CHARS),
            locationBasis = if (licensedPoint == null) {
                LicensedBroadcastLocationBasis.BASIC_PLAN_DISCOVERY_ONLY
            } else {
                LicensedBroadcastLocationBasis.LICENSED_COORDINATES
            },
        )
    }

    private fun queryStations(
        index: File,
        service: BroadcastService,
        channel: Int,
        center: GeoPoint,
        maximumDistanceKm: Double,
    ): List<LicensedBroadcastStation> {
        val latitudeDelta = maximumDistanceKm / 110.574
        val longitudeScale = (111.320 * cos(Math.toRadians(center.latitude))).coerceAtLeast(1.0)
        val longitudeDelta = maximumDistanceKm / longitudeScale
        val serviceNames = when (service) {
            BroadcastService.FM -> listOf("FM")
            BroadcastService.DIGITAL_TV -> listOf("GTVD", "RTVD")
        }
        val placeholders = serviceNames.joinToString(",") { "?" }
        val args = (serviceNames + listOf(
            (channel - 1).coerceAtLeast(1).toString(),
            (channel + 1).coerceAtMost(999).toString(),
            (center.latitude - latitudeDelta).coerceAtLeast(-90.0).toString(),
            (center.latitude + latitudeDelta).coerceAtMost(90.0).toString(),
            (center.longitude - longitudeDelta).coerceAtLeast(-180.0).toString(),
            (center.longitude + longitudeDelta).coerceAtMost(180.0).toString(),
        )).toTypedArray()
        return openReadOnly(index).use { database ->
            database.rawQuery(
                "SELECT source_id,basic_plan_id,service_code,raw_service,technology,role,channel," +
                    "frequency_mhz,latitude,longitude,municipality_code,municipality_name," +
                    "state_abbreviation,licensee,license_id,licensed_on,station_class,erp_kw," +
                    "antenna_height_agl_m,raw_status,location_basis FROM station " +
                    "WHERE raw_service IN ($placeholders) AND channel BETWEEN ? AND ? " +
                    "AND latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ? ORDER BY source_id",
                args,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (size >= MAXIMUM_QUERY_STATIONS) {
                            throw IOException("The licensed baseline query exceeds its station safety bound.")
                        }
                        val station = LicensedBroadcastStation(
                            sourceId = cursor.getString(0),
                            basicPlanId = cursor.nullableString(1),
                            serviceCode = cursor.getInt(2),
                            rawService = cursor.getString(3),
                            technology = BroadcastTechnology.entries[cursor.getInt(4)],
                            role = LicensedBroadcastRole.entries[cursor.getInt(5)],
                            channel = cursor.getInt(6),
                            frequencyMHz = cursor.getDouble(7),
                            location = GeoPoint(cursor.getDouble(8), cursor.getDouble(9)),
                            municipalityCode = cursor.nullableString(10),
                            municipalityName = cursor.nullableString(11),
                            stateAbbreviation = cursor.getString(12),
                            licensee = cursor.nullableString(13),
                            licenseId = cursor.nullableString(14),
                            licensedOn = cursor.nullableString(15),
                            stationClassRaw = cursor.getString(16),
                            erpKw = cursor.nullableDouble(17),
                            antennaHeightAglM = cursor.nullableDouble(18),
                            horizontalPattern = null,
                            rawStatus = cursor.getString(19),
                            locationBasis = LicensedBroadcastLocationBasis.entries[cursor.getInt(20)],
                        )
                        if (greatCircleDistanceKm(center, station.location) <= maximumDistanceKm) add(station)
                    }
                }
            }
        }
    }

    private fun queryUnlocatedSameChannelCount(
        index: File,
        service: BroadcastService,
        channel: Int,
    ): Int {
        val serviceNames = when (service) {
            BroadcastService.FM -> listOf("FM")
            BroadcastService.DIGITAL_TV -> listOf("GTVD", "RTVD")
        }
        val placeholders = serviceNames.joinToString(",") { "?" }
        val args = (serviceNames + listOf(
            (channel - 1).coerceAtLeast(1).toString(),
            (channel + 1).coerceAtMost(999).toString(),
        )).toTypedArray()
        return openReadOnly(index).use { database ->
            database.rawQuery(
                "SELECT count(*) FROM unlocated_station WHERE raw_service IN ($placeholders) " +
                    "AND channel BETWEEN ? AND ?",
                args,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw IOException("The unlocated licensed-station count is unavailable.")
                }
                cursor.getInt(0)
            }
        }
    }

    private fun validateIndex(file: File, sourceSha256: String): Boolean = runCatching {
        openReadOnly(file).use { database ->
            val applicationId = database.rawQuery("PRAGMA application_id", null).use { cursor ->
                if (!cursor.moveToFirst()) -1 else cursor.getInt(0)
            }
            val userVersion = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (!cursor.moveToFirst()) -1 else cursor.getInt(0)
            }
            val quickCheck = database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
            applicationId == DATABASE_APPLICATION_ID && userVersion == DATABASE_SCHEMA &&
                quickCheck == "ok" && readMetadata(database)["source_sha256"] == sourceSha256 &&
                readMetadata(database)["normalizer"] == NORMALIZER_ID
        }
    }.getOrDefault(false)

    private fun readMetadata(file: File, sourceSha256: String): Map<String, String> =
        openReadOnly(file).use { database ->
            readMetadata(database).also { metadata ->
                if (metadata["source_sha256"] != sourceSha256 || metadata["normalizer"] != NORMALIZER_ID) {
                    throw IOException("The licensed-broadcast index provenance is inconsistent.")
                }
            }
        }

    private fun readMetadata(database: SQLiteDatabase): Map<String, String> =
        database.rawQuery("SELECT key,value FROM metadata ORDER BY key", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    if (size >= MAXIMUM_METADATA_ENTRIES) throw IOException("Licensed metadata is unbounded.")
                    put(cursor.getString(0), cursor.getString(1))
                }
            }
        }

    private fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )
}

private data class IndexedLicensedStation(
    val sourceId: String,
    val basicPlanId: String?,
    val serviceCode: Int,
    val rawService: String,
    val technology: BroadcastTechnology,
    val role: LicensedBroadcastRole,
    val channel: Int,
    val frequencyMHz: Double,
    val location: GeoPoint,
    val municipalityCode: String?,
    val municipalityName: String?,
    val stateAbbreviation: String,
    val licensee: String?,
    val licenseId: String?,
    val licensedOn: String?,
    val stationClass: String,
    val erpKw: Double?,
    val antennaHeightAglM: Double?,
    val rawStatus: String,
    val locationBasis: LicensedBroadcastLocationBasis,
) {
    fun toSqlArguments(): Array<Any?> = arrayOf(
        sourceId, basicPlanId, serviceCode, rawService, technology.ordinal, role.ordinal, channel,
        frequencyMHz, location.latitude, location.longitude, municipalityCode, municipalityName,
        stateAbbreviation, licensee, licenseId, licensedOn, stationClass, erpKw,
        antennaHeightAglM, rawStatus, locationBasis.ordinal,
    )
}

private data class UnlocatedLicensedRow(
    val sourceId: String,
    val rawService: String,
    val channel: Int,
)

private fun unlocatedLicensedRowOrNull(
    row: List<String>,
    columns: Map<String, Int>,
): UnlocatedLicensedRow? {
    val rawService = row.value(columns, "SiglaServico").uppercase(Locale.ROOT)
    if (classifyService(rawService) == null) return null
    val sourceId = row.value(columns, "_id").takeIf(String::isNotBlank) ?: return null
    val channel = row.value(columns, "canalizacao_NumCanal").sourceInt()?.takeIf { it in 1..999 }
        ?: return null
    if (!row.hasLicensedEvidence(columns)) return null
    val hasDiscoveryPoint = listOf(
        row.sourcePoint(columns, "licenca_loctx_coordinates_0", "licenca_loctx_coordinates_1"),
        row.sourcePoint(
            columns,
            "licenca_srd_planobasico_MedLongitudeDecimal",
            "licenca_srd_planobasico_MedLatitudeDecimal",
        ),
        row.sourcePoint(columns, "locpb_coordinates_0", "locpb_coordinates_1"),
        row.sourcePoint(columns, "municipio_MedLongitudeDecimal", "municipio_MedLatitudeDecimal"),
    ).any { it != null }
    return if (hasDiscoveryPoint) null else UnlocatedLicensedRow(sourceId, rawService, channel)
}

private fun List<String>.hasLicensedEvidence(columns: Map<String, Int>): Boolean {
    val licenseId = value(columns, "licenca_license_id").ifBlank {
        value(columns, "licenca_estacao_NumLicenca")
    }
    val licensedOn = value(columns, "licenca_estacao_DataLicenciamento")
    val status = value(columns, "sitarwebStatus").uppercase(Locale.ROOT)
    return licenseId.isNotBlank() || ISO_DATE.matches(licensedOn) || status == "L"
}

private fun List<String>.sourcePoint(
    columns: Map<String, Int>,
    longitudeColumn: String,
    latitudeColumn: String,
): GeoPoint? {
    val longitude = value(columns, longitudeColumn).sourceDouble() ?: return null
    val latitude = value(columns, latitudeColumn).sourceDouble() ?: return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
}

/** RFC 4180-style quoted-field reader with semicolon delimiters and explicit row/field bounds. */
internal class BoundedDelimitedReader(
    private val reader: BufferedReader,
) {
    private var reachedEnd = false

    fun readRow(): List<String>? {
        if (reachedEnd) return null
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var rowChars = 0
        while (true) {
            val code = reader.read()
            if (code < 0) {
                reachedEnd = true
                if (quoted) throw IOException("The MCom CSV ended inside a quoted field.")
                if (fields.isEmpty() && field.isEmpty()) return null
                fields += field.toString()
                return fields
            }
            val character = code.toChar()
            rowChars += 1
            if (rowChars > MAXIMUM_CSV_ROW_CHARS) throw IOException("An MCom CSV row exceeds its safety bound.")
            when {
                quoted && character == '"' -> {
                    reader.mark(1)
                    val next = reader.read()
                    if (next == '"'.code) {
                        field.append('"')
                        rowChars += 1
                    } else {
                        quoted = false
                        afterQuote = true
                        if (next >= 0) reader.reset() else reachedEnd = true
                    }
                }
                quoted -> field.append(character)
                afterQuote && character == ';' -> {
                    fields += field.toString()
                    field.setLength(0)
                    afterQuote = false
                }
                afterQuote && character == '\r' -> Unit
                afterQuote && character == '\n' -> return fields + field.toString()
                afterQuote -> throw IOException("The MCom CSV contains text after a closing quote.")
                character == '"' && field.isEmpty() -> quoted = true
                character == ';' -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                character == '\r' -> Unit
                character == '\n' -> return fields + field.toString()
                else -> field.append(character)
            }
            if (field.length > MAXIMUM_CSV_FIELD_CHARS) {
                throw IOException("An MCom CSV field exceeds its safety bound.")
            }
            if (fields.size > MAXIMUM_CSV_COLUMNS) {
                throw IOException("An MCom CSV row exceeds its column safety bound.")
            }
        }
    }
}

private fun classifyService(raw: String): Pair<BroadcastTechnology, LicensedBroadcastRole>? = when (raw) {
    "FM" -> BroadcastTechnology.ANALOG to LicensedBroadcastRole.FM_STATION
    "GTVD" -> BroadcastTechnology.DIGITAL to LicensedBroadcastRole.GENERATOR
    "RTVD" -> BroadcastTechnology.DIGITAL to LicensedBroadcastRole.RETRANSMITTER
    else -> null
}

private fun List<String>.value(columns: Map<String, Int>, name: String): String =
    get(columns.getValue(name)).trim()

private fun String.sourceDouble(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)

private fun String.sourceInt(): Int? = sourceDouble()?.takeIf { it % 1.0 == 0.0 }?.toInt()

private fun mergeDate(existing: String?, candidate: String, label: String): String? {
    if (candidate.isBlank()) return existing
    if (!ISO_DATE.matches(candidate)) throw IOException("The MCom $label date is invalid.")
    if (existing != null && existing != candidate) {
        throw IOException("The MCom CSV contains mixed $label dates.")
    }
    return candidate
}

private fun android.database.Cursor.nullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

private fun android.database.Cursor.nullableDouble(index: Int): Double? =
    if (isNull(index)) null else getDouble(index)

private fun greatCircleDistanceKm(start: GeoPoint, end: GeoPoint): Double {
    val lat1 = Math.toRadians(start.latitude)
    val lat2 = Math.toRadians(end.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(end.longitude - start.longitude)
    val a = kotlin.math.sin(dLat / 2.0) * kotlin.math.sin(dLat / 2.0) +
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
        kotlin.math.sin(dLon / 2.0) * kotlin.math.sin(dLon / 2.0)
    return 2.0 * EARTH_RADIUS_KM * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
}

private const val MCOM_DOWNLOAD_HOST = "s3.mcom.gov.br"
private const val MCOM_LICENSED_BROADCAST_URL =
    "https://s3.mcom.gov.br/radcom/SCR_DADOS_RADIODIFUSAO_TV_GTVD_RTV_RTVD_FM_OM.csv"
private const val MCOM_SOURCE_PAGE_URL =
    "https://www.gov.br/mcom/pt-br/acesso-a-informacao/dados-abertos/bases-abertas"
private const val MAXIMUM_MCOM_CSV_BYTES = 256L * 1024L * 1024L
private const val DATABASE_APPLICATION_ID = 0x4154584c
private const val DATABASE_SCHEMA = 4
private const val NORMALIZER_ID = "atx-mcom-fm-and-digital-tv-licensed-v4"
private const val MAXIMUM_SOURCE_ROWS = 250_000L
private const val MAXIMUM_CSV_COLUMNS = 1_024
private const val MAXIMUM_CSV_ROW_CHARS = 2 * 1024 * 1024
private const val MAXIMUM_CSV_FIELD_CHARS = 256 * 1024
private const val MAXIMUM_ID_CHARS = 160
private const val MAXIMUM_TEXT_CHARS = 500
private const val MAXIMUM_CLASS_CHARS = 40
private const val MAXIMUM_STATUS_CHARS = 240
private const val MAXIMUM_QUERY_STATIONS = 100_000
private const val MAXIMUM_METADATA_ENTRIES = 64
private const val EARTH_RADIUS_KM = 6_371.0088
private val MCOM_CHARSET: Charset = Charsets.ISO_8859_1
private val ISO_DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
private val SEVEN_DIGIT_CODE = Regex("^[0-9]{7}$")
private val STATE_CODE = Regex("^[A-Z]{2}$")
private val INDEX_FILE = Regex("^[0-9a-f]{64}\\.sqlite$")
private val REQUIRED_COLUMNS = setOf(
    "_id", "IdtPlanoBasico", "NumServico", "SiglaServico", "SiglaUF",
    "canalizacao_NumCanal", "frequency", "NomeMunicipio", "licensee", "stnClass",
    "sitarwebStatus", "sitarwebLicenca", "Status_state", "licenca_frequency", "licenca_stnClass",
    "licenca_erp", "erp", "licenca_license_id", "licenca_loctx_coordinates_0",
    "licenca_loctx_coordinates_1", "licenca_antena_principal_MedHCI",
    "licenca_estacao_NumLicenca", "licenca_estacao_DataLicenciamento",
    "licenca_endereco_estacao_CodMunicipio", "licenca_endereco_estacao_NomeMunicipio",
    "licenca_endereco_estacaoprincipal_CodMunicipio", "licenca_endereco_estacaoprincipal_NomeMunicipio",
    "licenca_srd_planobasico_CodMunicipio", "licenca_srd_planobasico_NomeMunicipio",
    "licenca_srd_planobasico_MedLongitudeDecimal", "licenca_srd_planobasico_MedLatitudeDecimal",
    "locpb_coordinates_0", "locpb_coordinates_1",
    "municipio_CodMunicipio", "municipio_MedLongitudeDecimal", "municipio_MedLatitudeDecimal",
    "dt_geracao", "dt_referencia",
)
