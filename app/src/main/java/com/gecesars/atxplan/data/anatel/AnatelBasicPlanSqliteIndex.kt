package com.gecesars.atxplan.data.anatel

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteStatement
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogLimits
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanEntryReport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanImportReport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecordProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanStatus
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.AnatelImportWarning
import com.gecesars.atxplan.domain.anatel.AnatelImportWarningCode
import com.gecesars.atxplan.domain.anatel.AnatelResolvedFrequency
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.Locale

internal class AnatelBasicPlanIndexException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class StoredAnatelIndex(
    val file: File,
    val report: AnatelBasicPlanImportReport,
    val reused: Boolean,
)

internal data class AnatelIndexedQueryResult(
    val records: List<AnatelBasicPlanRecord>,
    val hasMore: Boolean,
)

internal class AnatelBasicPlanSqliteIndexStore(
    private val layout: AnatelBasicPlanCatalogLayout,
    private val parser: AnatelBasicPlanArchiveParser = AnatelBasicPlanArchiveParser(),
) {
    fun buildOrReuse(raw: StoredAnatelRawArchive): StoredAnatelIndex {
        val target = layout.indexFile(raw.provenance.archiveSha256)
        if (target.exists()) {
            return StoredAnatelIndex(
                file = target,
                report = readReport(target, raw.provenance),
                reused = true,
            )
        }

        enforceIndexCapacityBeforeParsing()
        val staging = layout.stagingIndexFile()
        var databaseToClose: SQLiteDatabase? = null
        try {
            val database = SQLiteDatabase.openOrCreateDatabase(staging, null).apply {
                disableWriteAheadLogging()
                setForeignKeyConstraintsEnabled(true)
            }
            databaseToClose = database
            configureIndexBounds(database)
            var insertedRecords = 0L
            lateinit var report: AnatelBasicPlanImportReport
            database.beginTransaction()
            try {
                createSchema(database)
                database.compileStatement(INSERT_RECORD_SQL).use { statement ->
                    raw.file.inputStream().use { input ->
                        report = parser.streamBatches(
                            input = input,
                            provenance = raw.provenance,
                            batchSize = INSERT_BATCH_SIZE,
                        ) { batch ->
                            batch.forEach { record ->
                                insertRecord(statement, record)
                                insertedRecords += 1L
                            }
                        }
                    }
                }
                if (insertedRecords != report.emittedRecordCount) {
                    throw AnatelBasicPlanIndexException(
                        "The staged Anatel index record count does not match the verified parser report.",
                    )
                }
                insertReport(database, report)
                createQueryIndexes(database)
                database.version = AnatelBasicPlanCatalogLayout.INDEX_SCHEMA_VERSION
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            database.close()
            databaseToClose = null
            if (!staging.isFile || staging.length() !in 1..MAX_INDEX_BYTES) {
                throw AnatelBasicPlanIndexException(
                    "The staged Anatel SQLite index is empty or exceeds its byte bound.",
                )
            }
            RandomAccessFile(staging, "rw").use { file -> file.fd.sync() }
            val validatedReport = readReport(staging, raw.provenance)
            if (validatedReport != report) {
                throw AnatelBasicPlanIndexException(
                    "The staged Anatel SQLite index report changed during read-back validation.",
                )
            }
            if (target.exists()) {
                throw AnatelBasicPlanIndexException(
                    "An immutable Anatel index appeared while the staged index was being built.",
                )
            }
            if (!staging.renameTo(target)) {
                throw AnatelBasicPlanIndexException(
                    "The verified Anatel SQLite index could not be promoted atomically.",
                )
            }
            return StoredAnatelIndex(target, validatedReport, reused = false)
        } catch (error: AnatelBasicPlanIndexException) {
            throw error
        } catch (error: Exception) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite index could not be built.", error)
        } finally {
            runCatching { databaseToClose?.close() }
            deleteStagingFamily(staging)
        }
    }

    fun readReport(
        indexFile: File,
        provenance: com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance,
    ): AnatelBasicPlanImportReport {
        if (!indexFile.isFile || indexFile.length() !in 1..MAX_INDEX_BYTES) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite index is unavailable or unbounded.")
        }
        return openReadOnly(indexFile).use { database ->
            try {
                if (database.version != AnatelBasicPlanCatalogLayout.INDEX_SCHEMA_VERSION) {
                    throw AnatelBasicPlanIndexException("The Anatel SQLite index schema is unsupported.")
                }
                requireQuickCheck(database)
                val metadata = readMetadata(database)
                requireMetadata(metadata, META_ARCHIVE_SHA256, provenance.archiveSha256)
                requireMetadata(metadata, META_ARCHIVE_BYTE_COUNT, provenance.archiveByteCount.toString())
                val entryReports = database.rawQuery(
                    "SELECT entry_name, origin, generation_date_raw, generation_date, " +
                        "source_row_count, emitted_record_count FROM entry_reports " +
                        "ORDER BY CASE origin " +
                        "WHEN 'BASIC_PLAN' THEN 0 " +
                        "WHEN 'SECONDARY_CHANNELS' THEN 1 " +
                        "WHEN 'REQUESTS' THEN 2 ELSE 3 END",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                AnatelBasicPlanEntryReport(
                                    entryName = cursor.getString(0),
                                    origin = enumValue(cursor.getString(1), "Anatel entry origin"),
                                    generationDateRaw = cursor.getString(2),
                                    generationDate = cursor.nullableStringAt(3),
                                    sourceRowCount = cursor.getLong(4),
                                    emittedRecordCount = cursor.getLong(5),
                                ),
                            )
                        }
                    }
                }
                val frequencyCounts = AnatelFrequencyOrigin.entries.associateWith { 0L }.toMutableMap()
                database.rawQuery(
                    "SELECT frequency_origin, occurrence_count FROM frequency_counts",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        frequencyCounts[enumValue(cursor.getString(0), "Anatel frequency origin")] =
                            cursor.getLong(1)
                    }
                }
                val warnings = database.rawQuery(
                    "SELECT warning_code, message, occurrence_count FROM import_warnings ORDER BY rowid",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                AnatelImportWarning(
                                    code = enumValue(cursor.getString(0), "Anatel warning code"),
                                    message = cursor.getString(1),
                                    occurrenceCount = cursor.getLong(2),
                                ),
                            )
                        }
                    }
                }
                val report = AnatelBasicPlanImportReport(
                    provenance = provenance,
                    verifiedArchiveSha256 = metadata.getValue(META_ARCHIVE_SHA256),
                    verifiedArchiveByteCount = metadata.getValue(META_ARCHIVE_BYTE_COUNT).toLong(),
                    archiveEntryCount = metadata.getValue(META_ARCHIVE_ENTRY_COUNT).toInt(),
                    ignoredArchiveEntryCount = metadata.getValue(META_IGNORED_ENTRY_COUNT).toInt(),
                    entryReports = entryReports,
                    sourceRowCount = metadata.getValue(META_SOURCE_ROW_COUNT).toLong(),
                    emittedRecordCount = metadata.getValue(META_EMITTED_RECORD_COUNT).toLong(),
                    latestGenerationDate = metadata.getValue(META_LATEST_GENERATION_DATE).ifEmpty { null },
                    frequencyOriginCounts = frequencyCounts,
                    warnings = warnings,
                )
                // A full count detects valid-but-incomplete SQLite tables with gaps in their row IDs.
                val indexedCount = database.rawQuery(
                    "SELECT COUNT(*) FROM records",
                    null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw AnatelBasicPlanIndexException(
                        "The Anatel index count query returned NoData.",
                    )
                    cursor.getLong(0)
                }
                if (indexedCount != report.emittedRecordCount) {
                    throw AnatelBasicPlanIndexException(
                        "The Anatel SQLite row count does not match its import report.",
                    )
                }
                report
            } catch (error: AnatelBasicPlanIndexException) {
                throw error
            } catch (error: Exception) {
                throw AnatelBasicPlanIndexException("The Anatel SQLite index metadata is invalid.", error)
            }
        }
    }

    fun query(
        indexFile: File,
        snapshot: AnatelBasicPlanCatalogSnapshot,
        query: AnatelBasicPlanQuery,
    ): AnatelIndexedQueryResult {
        val conditions = mutableListOf("service = ?")
        val arguments = mutableListOf(query.service.name)
        query.stateCode?.let { value ->
            conditions += "state_code = ?"
            arguments += value.trim().uppercase(Locale.ROOT)
        }
        query.municipality?.let { value ->
            conditions += "(municipality_key = ? OR ibge_municipality_code = ?)"
            val normalized = normalizeSearch(value)
            arguments += normalized
            arguments += value.trim()
        }
        query.channel?.let { value ->
            conditions += "channel = ?"
            arguments += value.toString()
        }
        query.frequencyMHz?.let { range ->
            conditions += "frequency_mhz >= ? AND frequency_mhz <= ?"
            arguments += range.minimum.toString()
            arguments += range.maximum.toString()
        }
        query.text?.let { value ->
            conditions += "search_text LIKE ? ESCAPE '\\'"
            arguments += "%${escapeLike(normalizeSearch(value))}%"
        }
        query.basicPlanId?.let { value ->
            conditions += "basic_plan_key = ?"
            arguments += normalizeSearch(value)
        }
        arguments += (query.pageSize + 1).toString()
        arguments += query.offset.toString()
        val sql = "SELECT $RECORD_COLUMNS FROM records WHERE ${conditions.joinToString(" AND ")} " +
            "ORDER BY state_code, municipality_key, channel, frequency_mhz, origin, " +
            "source_row_number, row_id LIMIT ? OFFSET ?"

        return try {
            openReadOnly(indexFile).use { database ->
                if (database.version != AnatelBasicPlanCatalogLayout.INDEX_SCHEMA_VERSION) {
                    throw AnatelBasicPlanIndexException("The Anatel SQLite index schema is unsupported.")
                }
                val records = database.rawQuery(sql, arguments.toTypedArray()).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toRecord(snapshot))
                    }
                }
                val hasMore = records.size > query.pageSize &&
                    query.offset <= AnatelBasicPlanCatalogLimits.MAX_PAGE_OFFSET - query.pageSize
                AnatelIndexedQueryResult(
                    records = if (hasMore) records.take(query.pageSize) else records,
                    hasMore = hasMore,
                )
            }
        } catch (error: AnatelBasicPlanIndexException) {
            throw error
        } catch (error: Exception) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite query could not be completed.", error)
        }
    }

    private fun configureIndexBounds(database: SQLiteDatabase) {
        database.execSQL("PRAGMA page_size=$SQLITE_PAGE_SIZE")
        database.rawQuery("PRAGMA max_page_count=$MAX_SQLITE_PAGE_COUNT", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getLong(0) > MAX_SQLITE_PAGE_COUNT) {
                throw AnatelBasicPlanIndexException("The Anatel SQLite page-count bound was not applied.")
            }
        }
    }

    /**
     * Reserves the full per-index ceiling before any untrusted XML callback can write SQLite pages.
     * Existing immutable indexes and crash remnants are counted but never deleted here.
     */
    private fun enforceIndexCapacityBeforeParsing() {
        val files = layout.indexDirectory.listFiles().orEmpty().filter(File::isFile)
        val immutableIndexCount = files.count { file -> IMMUTABLE_INDEX_FILE.matches(file.name) }
        if (immutableIndexCount >= MAX_INDEX_COUNT) {
            throw AnatelBasicPlanIndexException(
                "The immutable Anatel SQLite index retention count is full.",
            )
        }
        val familyBytes = files.fold(0L) { total, file ->
            val size = file.length().coerceAtLeast(0L)
            if (total > MAX_TOTAL_INDEX_BYTES - size) Long.MAX_VALUE else total + size
        }
        if (familyBytes == Long.MAX_VALUE || familyBytes > MAX_TOTAL_INDEX_BYTES - MAX_INDEX_BYTES) {
            throw AnatelBasicPlanIndexException(
                "The Anatel SQLite index-family byte limit cannot reserve another bounded index.",
            )
        }
        val usableBytes = layout.indexDirectory.usableSpace
        val requiredUsableBytes = MAX_INDEX_BYTES + MIN_FREE_BYTES_AFTER_INDEX
        if (usableBytes < requiredUsableBytes) {
            throw AnatelBasicPlanIndexException(
                "The device does not have enough usable space for a bounded Anatel index build.",
            )
        }
    }

    private fun requireQuickCheck(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            if (
                cursor.columnCount != 1 ||
                !cursor.moveToFirst() ||
                !cursor.getString(0).equals("ok", ignoreCase = true) ||
                !cursor.isLast
            ) {
                throw AnatelBasicPlanIndexException(
                    "The Anatel SQLite index failed bounded integrity validation.",
                )
            }
        }
    }

    private fun createSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE metadata (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE records (
                row_id INTEGER PRIMARY KEY,
                source_row_id TEXT,
                basic_plan_id TEXT,
                item_number INTEGER,
                origin TEXT NOT NULL,
                service TEXT NOT NULL,
                raw_service TEXT NOT NULL,
                status_raw TEXT NOT NULL,
                channel_raw TEXT NOT NULL,
                channel INTEGER,
                frequency_mhz REAL,
                frequency_origin TEXT NOT NULL,
                source_frequency_raw TEXT NOT NULL,
                frequency_explanation TEXT NOT NULL,
                country_code TEXT,
                state_code TEXT,
                ibge_municipality_code TEXT,
                municipality_name TEXT,
                municipality_key TEXT NOT NULL,
                channel_offset_raw TEXT NOT NULL,
                station_class_raw TEXT NOT NULL,
                character_raw TEXT NOT NULL,
                purpose_raw TEXT NOT NULL,
                entity_name TEXT,
                cnpj_raw TEXT NOT NULL,
                station_category_raw TEXT NOT NULL,
                latitude_degrees REAL,
                longitude_degrees REAL,
                erp_kw REAL,
                antenna_height_meters REAL,
                antenna_limitations_raw TEXT NOT NULL,
                antenna_pattern_dbd_raw TEXT NOT NULL,
                observations_raw TEXT NOT NULL,
                fistel_raw TEXT NOT NULL,
                generator_fistel_raw TEXT NOT NULL,
                dic_raw TEXT NOT NULL,
                entry_name TEXT NOT NULL,
                generation_date TEXT,
                source_row_number INTEGER NOT NULL,
                search_text TEXT NOT NULL,
                basic_plan_key TEXT NOT NULL,
                UNIQUE(origin, source_row_number)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE entry_reports (
                entry_name TEXT PRIMARY KEY NOT NULL,
                origin TEXT NOT NULL,
                generation_date_raw TEXT NOT NULL,
                generation_date TEXT,
                source_row_count INTEGER NOT NULL,
                emitted_record_count INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE frequency_counts (
                frequency_origin TEXT PRIMARY KEY NOT NULL,
                occurrence_count INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE import_warnings (
                warning_code TEXT NOT NULL,
                message TEXT NOT NULL,
                occurrence_count INTEGER NOT NULL,
                UNIQUE(warning_code, message)
            )
            """.trimIndent(),
        )
    }

    private fun createQueryIndexes(database: SQLiteDatabase) {
        database.execSQL("CREATE INDEX records_service_state ON records(service, state_code)")
        database.execSQL(
            "CREATE INDEX records_service_municipality ON records(service, municipality_key)",
        )
        database.execSQL("CREATE INDEX records_service_channel ON records(service, channel)")
        database.execSQL("CREATE INDEX records_service_frequency ON records(service, frequency_mhz)")
        database.execSQL("CREATE INDEX records_service_basic_plan ON records(service, basic_plan_key)")
    }

    private fun insertRecord(
        statement: SQLiteStatement,
        record: AnatelBasicPlanRecord,
    ) {
        statement.clearBindings()
        val binder = StatementBinder(statement)
        binder.string(record.sourceRowId)
        binder.string(record.basicPlanId)
        binder.long(record.itemNumber)
        binder.string(record.origin.name)
        binder.string(record.service.name)
        binder.string(record.rawService)
        binder.string(record.status.rawCode)
        binder.string(record.channelRaw)
        binder.long(record.channel?.toLong())
        binder.double(record.frequency.frequencyMHz)
        binder.string(record.frequency.origin.name)
        binder.string(record.frequency.sourceFrequencyRaw)
        binder.string(record.frequency.explanation)
        binder.string(record.countryCode)
        binder.string(record.stateCode?.uppercase(Locale.ROOT))
        binder.string(record.ibgeMunicipalityCode)
        binder.string(record.municipalityName)
        binder.string(normalizeSearch(record.municipalityName.orEmpty()))
        binder.string(record.channelOffsetRaw)
        binder.string(record.stationClassRaw)
        binder.string(record.characterRaw)
        binder.string(record.purposeRaw)
        binder.string(record.entityName)
        binder.string(record.cnpjRaw)
        binder.string(record.stationCategoryRaw)
        binder.double(record.latitudeDegrees)
        binder.double(record.longitudeDegrees)
        binder.double(record.erpKw)
        binder.double(record.antennaHeightMeters)
        binder.string(record.antennaLimitationsRaw)
        binder.string(record.antennaPatternDbdRaw)
        binder.string(record.observationsRaw)
        binder.string(record.fistelRaw)
        binder.string(record.generatorFistelRaw)
        binder.string(record.dicRaw)
        binder.string(record.provenance.entryName)
        binder.string(record.provenance.generationDate)
        binder.long(record.provenance.sourceRowNumber)
        binder.string(searchText(record))
        binder.string(normalizeSearch(record.basicPlanId.orEmpty()))
        if (binder.boundCount != RECORD_BIND_COUNT) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite record binding count is invalid.")
        }
        if (statement.executeInsert() < 0L) {
            throw AnatelBasicPlanIndexException("An Anatel record could not be inserted into staging.")
        }
    }

    private fun insertReport(
        database: SQLiteDatabase,
        report: AnatelBasicPlanImportReport,
    ) {
        val metadata = linkedMapOf(
            META_ARCHIVE_SHA256 to report.verifiedArchiveSha256,
            META_ARCHIVE_BYTE_COUNT to report.verifiedArchiveByteCount.toString(),
            META_ARCHIVE_ENTRY_COUNT to report.archiveEntryCount.toString(),
            META_IGNORED_ENTRY_COUNT to report.ignoredArchiveEntryCount.toString(),
            META_SOURCE_ROW_COUNT to report.sourceRowCount.toString(),
            META_EMITTED_RECORD_COUNT to report.emittedRecordCount.toString(),
            META_LATEST_GENERATION_DATE to report.latestGenerationDate.orEmpty(),
        )
        database.compileStatement("INSERT INTO metadata(key, value) VALUES(?, ?)").use { statement ->
            metadata.forEach { (key, value) ->
                statement.clearBindings()
                statement.bindString(1, key)
                statement.bindString(2, value)
                statement.executeInsert()
            }
        }
        database.compileStatement(
            "INSERT INTO entry_reports(entry_name, origin, generation_date_raw, generation_date, " +
                "source_row_count, emitted_record_count) VALUES(?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            report.entryReports.forEach { entry ->
                statement.clearBindings()
                statement.bindString(1, entry.entryName)
                statement.bindString(2, entry.origin.name)
                statement.bindString(3, entry.generationDateRaw)
                statement.bindNullableString(4, entry.generationDate)
                statement.bindLong(5, entry.sourceRowCount)
                statement.bindLong(6, entry.emittedRecordCount)
                statement.executeInsert()
            }
        }
        database.compileStatement(
            "INSERT INTO frequency_counts(frequency_origin, occurrence_count) VALUES(?, ?)",
        ).use { statement ->
            AnatelFrequencyOrigin.entries.forEach { origin ->
                statement.clearBindings()
                statement.bindString(1, origin.name)
                statement.bindLong(2, report.frequencyOriginCounts[origin] ?: 0L)
                statement.executeInsert()
            }
        }
        database.compileStatement(
            "INSERT INTO import_warnings(warning_code, message, occurrence_count) VALUES(?, ?, ?)",
        ).use { statement ->
            report.warnings.forEach { warning ->
                statement.clearBindings()
                statement.bindString(1, warning.code.name)
                statement.bindString(2, warning.message)
                statement.bindLong(3, warning.occurrenceCount)
                statement.executeInsert()
            }
        }
    }

    private fun readMetadata(database: SQLiteDatabase): Map<String, String> = database.rawQuery(
        "SELECT key, value FROM metadata",
        null,
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        }
    }.also { metadata ->
        if (!metadata.keys.containsAll(REQUIRED_METADATA_KEYS)) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite index metadata is incomplete.")
        }
    }

    private fun requireMetadata(
        metadata: Map<String, String>,
        key: String,
        expected: String,
    ) {
        if (metadata[key] != expected) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite index metadata does not match its artifact.")
        }
    }

    private fun openReadOnly(file: File): SQLiteDatabase = try {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
    } catch (error: SQLiteException) {
        throw AnatelBasicPlanIndexException("The Anatel SQLite index could not be opened read-only.", error)
    }

    private fun Cursor.toRecord(snapshot: AnatelBasicPlanCatalogSnapshot): AnatelBasicPlanRecord {
        var column = 0
        fun text(): String = getString(column++)
        fun nullableText(): String? = nullableStringAt(column++)
        fun nullableLong(): Long? = nullableLongAt(column++)
        fun nullableInt(): Int? = nullableLong()?.toInt()
        fun nullableDouble(): Double? = nullableDoubleAt(column++)

        val sourceRowId = nullableText()
        val basicPlanId = nullableText()
        val itemNumber = nullableLong()
        val origin: AnatelBasicPlanOrigin = enumValue(text(), "Anatel record origin")
        val service: AnatelBroadcastService = enumValue(text(), "Anatel record service")
        val rawService = text()
        val statusRaw = text()
        val channelRaw = text()
        val channel = nullableInt()
        val frequencyMHz = nullableDouble()
        val frequencyOrigin: AnatelFrequencyOrigin = enumValue(text(), "Anatel record frequency origin")
        val sourceFrequencyRaw = text()
        val frequencyExplanation = text()
        val countryCode = nullableText()
        val stateCode = nullableText()
        val ibgeMunicipalityCode = nullableText()
        val municipalityName = nullableText()
        val channelOffsetRaw = text()
        val stationClassRaw = text()
        val characterRaw = text()
        val purposeRaw = text()
        val entityName = nullableText()
        val cnpjRaw = text()
        val stationCategoryRaw = text()
        val latitudeDegrees = nullableDouble()
        val longitudeDegrees = nullableDouble()
        val erpKw = nullableDouble()
        val antennaHeightMeters = nullableDouble()
        val antennaLimitationsRaw = text()
        val antennaPatternDbdRaw = text()
        val observationsRaw = text()
        val fistelRaw = text()
        val generatorFistelRaw = text()
        val dicRaw = text()
        val entryName = text()
        val generationDate = nullableText()
        val sourceRowNumber = getLong(column++)
        if (column != RECORD_RESULT_COLUMN_COUNT) {
            throw AnatelBasicPlanIndexException("The Anatel SQLite result column count is invalid.")
        }
        return AnatelBasicPlanRecord(
            sourceRowId = sourceRowId,
            basicPlanId = basicPlanId,
            itemNumber = itemNumber,
            origin = origin,
            service = service,
            rawService = rawService,
            status = AnatelBasicPlanStatus(statusRaw),
            channelRaw = channelRaw,
            channel = channel,
            frequency = AnatelResolvedFrequency(
                frequencyMHz = frequencyMHz,
                origin = frequencyOrigin,
                sourceFrequencyRaw = sourceFrequencyRaw,
                explanation = frequencyExplanation,
            ),
            countryCode = countryCode,
            stateCode = stateCode,
            ibgeMunicipalityCode = ibgeMunicipalityCode,
            municipalityName = municipalityName,
            channelOffsetRaw = channelOffsetRaw,
            stationClassRaw = stationClassRaw,
            characterRaw = characterRaw,
            purposeRaw = purposeRaw,
            entityName = entityName,
            cnpjRaw = cnpjRaw,
            stationCategoryRaw = stationCategoryRaw,
            latitudeDegrees = latitudeDegrees,
            longitudeDegrees = longitudeDegrees,
            erpKw = erpKw,
            antennaHeightMeters = antennaHeightMeters,
            antennaLimitationsRaw = antennaLimitationsRaw,
            antennaPatternDbdRaw = antennaPatternDbdRaw,
            observationsRaw = observationsRaw,
            fistelRaw = fistelRaw,
            generatorFistelRaw = generatorFistelRaw,
            dicRaw = dicRaw,
            provenance = AnatelBasicPlanRecordProvenance(
                archive = snapshot.report.provenance,
                entryName = entryName,
                origin = origin,
                generationDate = generationDate,
                sourceRowNumber = sourceRowNumber,
            ),
        )
    }

    private fun deleteStagingFamily(staging: File) {
        listOf(
            staging,
            File("${staging.path}-journal"),
            File("${staging.path}-wal"),
            File("${staging.path}-shm"),
        ).forEach { candidate ->
            if (candidate.exists()) candidate.delete()
        }
    }
}

private class StatementBinder(
    private val statement: SQLiteStatement,
) {
    private var nextIndex = 1
    val boundCount: Int get() = nextIndex - 1

    fun string(value: String?) {
        if (value == null) statement.bindNull(nextIndex) else statement.bindString(nextIndex, value)
        nextIndex += 1
    }

    fun long(value: Long?) {
        if (value == null) statement.bindNull(nextIndex) else statement.bindLong(nextIndex, value)
        nextIndex += 1
    }

    fun double(value: Double?) {
        if (value == null) statement.bindNull(nextIndex) else statement.bindDouble(nextIndex, value)
        nextIndex += 1
    }
}

private fun SQLiteStatement.bindNullableString(
    index: Int,
    value: String?,
) {
    if (value == null) bindNull(index) else bindString(index, value)
}

private fun Cursor.nullableStringAt(index: Int): String? = if (isNull(index)) null else getString(index)

private fun Cursor.nullableLongAt(index: Int): Long? = if (isNull(index)) null else getLong(index)

private fun Cursor.nullableDoubleAt(index: Int): Double? = if (isNull(index)) null else getDouble(index)

private inline fun <reified T : Enum<T>> enumValue(
    value: String,
    label: String,
): T = try {
    enumValueOf<T>(value)
} catch (error: IllegalArgumentException) {
    throw AnatelBasicPlanIndexException("The $label stored in SQLite is invalid.", error)
}

private fun searchText(record: AnatelBasicPlanRecord): String = normalizeSearch(
    listOfNotNull(
        record.sourceRowId,
        record.basicPlanId,
        record.entityName,
        record.countryCode,
        record.stateCode,
        record.ibgeMunicipalityCode,
        record.municipalityName,
        record.channelRaw,
        record.rawService,
        record.stationClassRaw,
        record.stationCategoryRaw,
        record.cnpjRaw,
        record.observationsRaw,
        record.fistelRaw,
        record.generatorFistelRaw,
        record.dicRaw,
    ).joinToString(separator = " "),
)

private fun normalizeSearch(value: String): String {
    val decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
    val withoutMarks = buildString(decomposed.length) {
        decomposed.forEach { character ->
            when (Character.getType(character)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                -> Unit

                else -> append(character)
            }
        }
    }
    return WHITESPACE.replace(withoutMarks.lowercase(Locale.ROOT), " ")
}

private fun escapeLike(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

private const val INSERT_BATCH_SIZE = 1_000
private const val SQLITE_PAGE_SIZE = 4_096
private const val MAX_SQLITE_PAGE_COUNT = 65_536L
private const val MAX_INDEX_BYTES = 4_096L * MAX_SQLITE_PAGE_COUNT
private const val MAX_INDEX_COUNT = 8
private const val MAX_TOTAL_INDEX_BYTES = 768L * 1024L * 1024L
private const val MIN_FREE_BYTES_AFTER_INDEX = 128L * 1024L * 1024L
private const val RECORD_BIND_COUNT = 40
private const val RECORD_RESULT_COLUMN_COUNT = 37
private val WHITESPACE = Regex("\\s+")
private val IMMUTABLE_INDEX_FILE = Regex("basic-plan-[0-9a-f]{64}-v\\d+\\.sqlite")

private const val META_ARCHIVE_SHA256 = "archive_sha256"
private const val META_ARCHIVE_BYTE_COUNT = "archive_byte_count"
private const val META_ARCHIVE_ENTRY_COUNT = "archive_entry_count"
private const val META_IGNORED_ENTRY_COUNT = "ignored_archive_entry_count"
private const val META_SOURCE_ROW_COUNT = "source_row_count"
private const val META_EMITTED_RECORD_COUNT = "emitted_record_count"
private const val META_LATEST_GENERATION_DATE = "latest_generation_date"
private val REQUIRED_METADATA_KEYS = setOf(
    META_ARCHIVE_SHA256,
    META_ARCHIVE_BYTE_COUNT,
    META_ARCHIVE_ENTRY_COUNT,
    META_IGNORED_ENTRY_COUNT,
    META_SOURCE_ROW_COUNT,
    META_EMITTED_RECORD_COUNT,
    META_LATEST_GENERATION_DATE,
)

private val RECORD_COLUMNS = listOf(
    "source_row_id",
    "basic_plan_id",
    "item_number",
    "origin",
    "service",
    "raw_service",
    "status_raw",
    "channel_raw",
    "channel",
    "frequency_mhz",
    "frequency_origin",
    "source_frequency_raw",
    "frequency_explanation",
    "country_code",
    "state_code",
    "ibge_municipality_code",
    "municipality_name",
    "channel_offset_raw",
    "station_class_raw",
    "character_raw",
    "purpose_raw",
    "entity_name",
    "cnpj_raw",
    "station_category_raw",
    "latitude_degrees",
    "longitude_degrees",
    "erp_kw",
    "antenna_height_meters",
    "antenna_limitations_raw",
    "antenna_pattern_dbd_raw",
    "observations_raw",
    "fistel_raw",
    "generator_fistel_raw",
    "dic_raw",
    "entry_name",
    "generation_date",
    "source_row_number",
).joinToString(separator = ", ")

private val INSERT_RECORD_SQL = """
    INSERT INTO records(
        source_row_id, basic_plan_id, item_number, origin, service, raw_service, status_raw,
        channel_raw, channel, frequency_mhz, frequency_origin, source_frequency_raw,
        frequency_explanation, country_code, state_code, ibge_municipality_code,
        municipality_name, municipality_key, channel_offset_raw, station_class_raw,
        character_raw, purpose_raw, entity_name, cnpj_raw, station_category_raw,
        latitude_degrees, longitude_degrees, erp_kw, antenna_height_meters,
        antenna_limitations_raw, antenna_pattern_dbd_raw, observations_raw, fistel_raw,
        generator_fistel_raw, dic_raw, entry_name, generation_date, source_row_number,
        search_text, basic_plan_key
    ) VALUES(
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
    )
""".trimIndent()
