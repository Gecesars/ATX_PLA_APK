package com.gecesars.atxplan.data.dataset

import android.content.Context
import android.content.res.AssetManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import android.system.Os
import com.gecesars.atxplan.domain.dataset.IbgeDatasetDescriptor
import com.gecesars.atxplan.domain.dataset.IbgeDatasetException
import com.gecesars.atxplan.domain.dataset.IbgeDatasetFailure
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationPhase
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationProgress
import com.gecesars.atxplan.domain.dataset.IbgeDatasetRepository
import com.gecesars.atxplan.domain.dataset.IbgeCensusSectorAttribute
import com.gecesars.atxplan.domain.dataset.IbgeMunicipalitySummary
import com.gecesars.atxplan.domain.dataset.MAX_MUNICIPALITY_QUERY_LENGTH
import com.gecesars.atxplan.domain.dataset.MAX_MUNICIPALITY_RESULT_LIMIT
import com.gecesars.atxplan.domain.dataset.normalizeIbgeMunicipalitySearch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BundledIbgeDatasetRepository private constructor(
    private val assets: AssetManager,
    private val installRoot: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val availableBytes: (File) -> Long,
) : IbgeDatasetRepository {
    constructor(context: Context) : this(
        assets = context.applicationContext.assets,
        installRoot = File(context.applicationContext.noBackupFilesDir, INSTALL_DIRECTORY),
        ioDispatcher = Dispatchers.IO,
        availableBytes = { directory -> StatFs(directory.absolutePath).availableBytes },
    )

    internal constructor(
        assets: AssetManager,
        installRoot: File,
        ioDispatcher: CoroutineDispatcher,
        availableBytes: (File) -> Long,
        @Suppress("UNUSED_PARAMETER") testAccess: Unit = Unit,
    ) : this(assets, installRoot, ioDispatcher, availableBytes)

    private val operationMutex = Mutex()

    @Volatile
    private var preparedDataset: PreparedDataset? = null

    override suspend fun prepare(
        onProgress: (IbgeDatasetPreparationProgress) -> Unit,
    ): IbgeDatasetDescriptor = withContext(ioDispatcher) {
        operationMutex.withLock {
            onProgress(
                IbgeDatasetPreparationProgress(
                    phase = IbgeDatasetPreparationPhase.CHECKING,
                ),
            )
            val manifest = readManifest()
            preparedDataset = null
            ensureInstallDirectory()
            val database = targetDatabase(manifest)
            val ready = if (verifyFile(
                    file = database,
                    expectedBytes = manifest.databaseByteCount,
                    expectedSha256 = manifest.databaseSha256,
                )
            ) {
                onProgress(
                    IbgeDatasetPreparationProgress(
                        phase = IbgeDatasetPreparationPhase.VALIDATING,
                        completedBytes = manifest.databaseByteCount,
                        totalBytes = manifest.databaseByteCount,
                    ),
                )
                validateDatabase(database, manifest)
                database
            } else {
                deleteReclaimableDatabases()
                installDatabase(manifest, database, onProgress)
            }
            deleteSupersededDatabases(ready)
            val prepared = PreparedDataset(
                manifest = manifest,
                database = ready,
                descriptor = manifest.toDescriptor(),
            )
            preparedDataset = prepared
            prepared.descriptor
        }
    }

    override suspend fun searchMunicipalities(
        query: String,
        limit: Int,
    ): List<IbgeMunicipalitySummary> {
        require(query.length <= MAX_MUNICIPALITY_QUERY_LENGTH) {
            "The municipality query exceeds $MAX_MUNICIPALITY_QUERY_LENGTH characters."
        }
        require(limit in 1..MAX_MUNICIPALITY_RESULT_LIMIT) {
            "The municipality result limit must be between 1 and $MAX_MUNICIPALITY_RESULT_LIMIT."
        }
        val prepared = preparedDataset ?: run {
            prepare()
            preparedDataset ?: throw IbgeDatasetException(
                failure = IbgeDatasetFailure.QUERY_FAILED,
                message = "The IBGE dataset did not become ready for queries.",
            )
        }
        return withContext(ioDispatcher) {
            try {
                openReadOnly(prepared.database).use { database ->
                    val normalized = normalizeIbgeMunicipalitySearch(query)
                    val cursor = if (normalized.isEmpty()) {
                        database.rawQuery(
                            "$MUNICIPALITY_SELECT ORDER BY m.population_total DESC, " +
                                "m.name COLLATE NOCASE, m.code LIMIT ?",
                            arrayOf(limit.toString()),
                        )
                    } else {
                        val escaped = escapeLike(normalized)
                        val prefix = "$escaped%"
                        val contains = "%$escaped%"
                        val rawCode = query.trim()
                        database.rawQuery(
                            "$MUNICIPALITY_SELECT " +
                                "WHERE m.search_name LIKE ? ESCAPE '\\' " +
                                "OR m.code LIKE ? ESCAPE '\\' " +
                                "ORDER BY CASE " +
                                "WHEN m.code = ? THEN 0 " +
                                "WHEN m.search_name = ? THEN 1 " +
                                "WHEN m.search_name LIKE ? ESCAPE '\\' THEN 2 " +
                                "ELSE 3 END, m.name COLLATE NOCASE, m.state_code, m.code LIMIT ?",
                            arrayOf(
                                contains,
                                "${escapeLike(rawCode)}%",
                                rawCode,
                                normalized,
                                prefix,
                                limit.toString(),
                            ),
                        )
                    }
                    cursor.use(::readMunicipalities)
                }
            } catch (error: IbgeDatasetException) {
                throw error
            } catch (error: Exception) {
                throw IbgeDatasetException(
                    failure = IbgeDatasetFailure.QUERY_FAILED,
                    message = "The offline IBGE municipality query could not be completed.",
                    cause = error,
                )
            }
        }
    }

    suspend fun municipalityByCode(code: String): IbgeMunicipalitySummary? {
        require(code.length == 7 && code.all(Char::isDigit)) {
            "An IBGE municipality code must contain seven digits."
        }
        val prepared = requirePreparedDataset()
        return withContext(ioDispatcher) {
            try {
                openReadOnly(prepared.database).use { database ->
                    database.rawQuery(
                        "$MUNICIPALITY_SELECT WHERE m.code = ? LIMIT 1",
                        arrayOf(code),
                    ).use { cursor -> readMunicipalities(cursor).singleOrNull() }
                }
            } catch (error: Exception) {
                throw IbgeDatasetException(
                    failure = IbgeDatasetFailure.QUERY_FAILED,
                    message = "The offline IBGE municipality lookup could not be completed.",
                    cause = error,
                )
            }
        }
    }

    suspend fun urbanSectorAttributes(municipalityCode: String): List<IbgeCensusSectorAttribute> {
        require(municipalityCode.length == 7 && municipalityCode.all(Char::isDigit)) {
            "An IBGE municipality code must contain seven digits."
        }
        val prepared = requirePreparedDataset()
        return withContext(ioDispatcher) {
            try {
                openReadOnly(prepared.database).use { database ->
                    database.rawQuery(
                        "SELECT sector_code, municipality_code, situation_code, area_km2, population " +
                            "FROM sector WHERE municipality_code = ? AND situation_code = 1 " +
                            "ORDER BY sector_code",
                        arrayOf(municipalityCode),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                if (size >= MAXIMUM_MUNICIPALITY_SECTORS) {
                                    throw IOException("The IBGE municipality sector query exceeds its safety bound.")
                                }
                                add(
                                    IbgeCensusSectorAttribute(
                                        sectorCode = cursor.getString(0),
                                        municipalityCode = cursor.getString(1),
                                        situationCode = cursor.getInt(2),
                                        areaKm2 = cursor.getDouble(3),
                                        residentPopulation = cursor.getLong(4),
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (error: IbgeDatasetException) {
                throw error
            } catch (error: Exception) {
                throw IbgeDatasetException(
                    failure = IbgeDatasetFailure.QUERY_FAILED,
                    message = "The offline IBGE urban-sector query could not be completed.",
                    cause = error,
                )
            }
        }
    }

    private suspend fun requirePreparedDataset(): PreparedDataset = preparedDataset ?: run {
        prepare()
        preparedDataset ?: throw IbgeDatasetException(
            failure = IbgeDatasetFailure.QUERY_FAILED,
            message = "The IBGE dataset did not become ready for queries.",
        )
    }

    private fun readManifest(): IbgeAssetManifest {
        val payload = try {
            assets.open(MANIFEST_ASSET, AssetManager.ACCESS_STREAMING).use { input ->
                input.readBounded(MAX_MANIFEST_BYTES)
            }
        } catch (error: IOException) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.EMBEDDED_ASSET_MISSING,
                message = "The embedded IBGE manifest is missing or unreadable.",
                cause = error,
            )
        }
        val manifest = try {
            MANIFEST_JSON.decodeFromString<IbgeAssetManifest>(decodeStrictUtf8(payload))
        } catch (error: Exception) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INVALID_MANIFEST,
                message = "The embedded IBGE manifest is invalid.",
                cause = error,
            )
        }
        try {
            manifest.validate()
        } catch (error: IllegalArgumentException) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INVALID_MANIFEST,
                message = "The embedded IBGE manifest failed validation.",
                cause = error,
            )
        }
        return manifest
    }

    private fun ensureInstallDirectory() {
        if (!installRoot.isDirectory && !installRoot.mkdirs()) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INSTALLATION_FAILED,
                message = "Private storage for the embedded IBGE dataset could not be created.",
            )
        }
        installRoot.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile && file.name.startsWith(STAGING_PREFIX) && file.name.endsWith(STAGING_SUFFIX)
            }
            .forEach(File::delete)
    }

    private fun targetDatabase(manifest: IbgeAssetManifest): File {
        require(SHA256_PATTERN.matches(manifest.databaseSha256))
        return File(installRoot, "${manifest.databaseSha256}.sqlite")
    }

    private fun deleteSupersededDatabases(current: File) {
        installRoot.listFiles()
            .orEmpty()
            .filter { file ->
                file.isFile &&
                    file.name != current.name &&
                    INSTALLED_DATABASE_FILE_PATTERN.matches(file.name)
            }
            .forEach(File::delete)
    }

    private fun deleteReclaimableDatabases() {
        installRoot.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && INSTALLED_DATABASE_FILE_PATTERN.matches(file.name) }
            .forEach(File::delete)
    }

    private fun installDatabase(
        manifest: IbgeAssetManifest,
        database: File,
        onProgress: (IbgeDatasetPreparationProgress) -> Unit,
    ): File {
        val requiredBytes = manifest.databaseByteCount + INSTALL_SAFETY_BYTES
        val freeBytes = try {
            availableBytes(installRoot)
        } catch (error: Exception) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INSTALLATION_FAILED,
                message = "Available private storage could not be measured.",
                cause = error,
            )
        }
        if (freeBytes < requiredBytes) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INSUFFICIENT_STORAGE,
                message = "The embedded IBGE dataset needs at least " +
                    "${formatStorageBytes(requiredBytes)} of free private storage.",
            )
        }
        val staging = File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, installRoot)
        try {
            extractAsset(manifest, staging, onProgress)
            onProgress(
                IbgeDatasetPreparationProgress(
                    phase = IbgeDatasetPreparationPhase.VALIDATING,
                    completedBytes = manifest.databaseByteCount,
                    totalBytes = manifest.databaseByteCount,
                ),
            )
            validateDatabase(staging, manifest)
            try {
                Os.rename(staging.absolutePath, database.absolutePath)
            } catch (error: Exception) {
                if (!verifyFile(database, manifest.databaseByteCount, manifest.databaseSha256)) {
                    throw error
                }
            }
            if (!verifyFile(database, manifest.databaseByteCount, manifest.databaseSha256)) {
                throw IbgeDatasetException(
                    failure = IbgeDatasetFailure.INTEGRITY_CHECK_FAILED,
                    message = "The installed IBGE database failed its final integrity check.",
                )
            }
            return database
        } catch (error: IbgeDatasetException) {
            throw error
        } catch (error: Exception) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INSTALLATION_FAILED,
                message = "The embedded IBGE database could not be installed safely.",
                cause = error,
            )
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun extractAsset(
        manifest: IbgeAssetManifest,
        staging: File,
        onProgress: (IbgeDatasetPreparationProgress) -> Unit,
    ) {
        val compressedDigest = MessageDigest.getInstance("SHA-256")
        val databaseDigest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var lastReported = -PROGRESS_REPORT_BYTES
        try {
            assets.open(
                "$ASSET_DIRECTORY/${manifest.compressedAssetFile}",
                AssetManager.ACCESS_STREAMING,
            ).use { assetInput ->
                val counted = CountingInputStream(assetInput)
                val checkedCompressed = DigestInputStream(counted, compressedDigest)
                GZIPInputStream(checkedCompressed, IO_BUFFER_BYTES).use { compressedInput ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(IO_BUFFER_BYTES)
                        while (true) {
                            val read = compressedInput.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > manifest.databaseByteCount) {
                                throw IbgeDatasetException(
                                    failure = IbgeDatasetFailure.INTEGRITY_CHECK_FAILED,
                                    message = "The embedded IBGE database exceeds its declared size.",
                                )
                            }
                            databaseDigest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            if (written - lastReported >= PROGRESS_REPORT_BYTES) {
                                lastReported = written
                                onProgress(
                                    IbgeDatasetPreparationProgress(
                                        phase = IbgeDatasetPreparationPhase.INSTALLING,
                                        completedBytes = written,
                                        totalBytes = manifest.databaseByteCount,
                                    ),
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (counted.count != manifest.compressedByteCount) {
                    throw IbgeDatasetException(
                        failure = IbgeDatasetFailure.INTEGRITY_CHECK_FAILED,
                        message = "The embedded IBGE package size does not match its manifest.",
                    )
                }
            }
        } catch (error: IbgeDatasetException) {
            throw error
        } catch (error: Exception) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INTEGRITY_CHECK_FAILED,
                message = "The embedded IBGE package could not be decompressed or verified.",
                cause = error,
            )
        }
        if (
            written != manifest.databaseByteCount ||
            databaseDigest.digest().toHex() != manifest.databaseSha256 ||
            compressedDigest.digest().toHex() != manifest.compressedSha256
        ) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INTEGRITY_CHECK_FAILED,
                message = "The embedded IBGE package failed its SHA-256 verification.",
            )
        }
        onProgress(
            IbgeDatasetPreparationProgress(
                phase = IbgeDatasetPreparationPhase.INSTALLING,
                completedBytes = written,
                totalBytes = manifest.databaseByteCount,
            ),
        )
    }

    private fun validateDatabase(file: File, manifest: IbgeAssetManifest) {
        try {
            openReadOnly(file).use { database ->
                requireDatabaseValue(
                    database.querySingleLong("PRAGMA application_id"),
                    manifest.databaseApplicationId.toLong(),
                    "application ID",
                )
                requireDatabaseValue(
                    database.querySingleLong("PRAGMA user_version"),
                    manifest.databaseSchema.toLong(),
                    "schema version",
                )
                val quickCheck = database.querySingleString("PRAGMA quick_check(1)")
                if (quickCheck != "ok") {
                    throw IOException("SQLite quick_check did not return ok.")
                }
                val metadata = database.readMetadata()
                requireMetadata(metadata, "dataset_id", manifest.datasetId)
                requireMetadata(metadata, "database_schema", manifest.databaseSchema.toString())
                requireMetadata(metadata, "source_archive_sha256", manifest.sourceArchiveSha256)
                requireMetadata(metadata, "source_index_sha256", manifest.sourceIndexSha256)
                requireMetadata(metadata, "source_crs", manifest.sourceCrs)
                requireMetadata(metadata, "geometry_included", manifest.geometryIncluded.toString())
                requireMetadata(metadata, "population_field", manifest.populationField)
                requireMetadata(
                    metadata,
                    "population_missing_sector_count",
                    manifest.missingPopulationSectorCount.toString(),
                )
                requireMetadata(metadata, "population_total", manifest.populationTotal.toString())
                requireMetadata(metadata, "sector_count", manifest.sectorCount.toString())
                requireMetadata(metadata, "municipality_count", manifest.municipalityCount.toString())
                requireMetadata(
                    metadata,
                    "unassigned_sector_count",
                    manifest.unassignedSectorCount.toString(),
                )
                if (metadata.containsKey("source_root")) {
                    throw IOException("The derived database exposes a build-machine source path.")
                }
                requireDatabaseValue(
                    database.querySingleLong("SELECT count(*) FROM sector"),
                    manifest.sectorCount.toLong(),
                    "sector count",
                )
                requireDatabaseValue(
                    database.querySingleLong("SELECT count(*) FROM sector_bounds"),
                    manifest.sectorCount.toLong(),
                    "sector bounds count",
                )
                requireDatabaseValue(
                    database.querySingleLong("SELECT count(*) FROM municipality"),
                    manifest.municipalityCount.toLong(),
                    "municipality count",
                )
                requireDatabaseValue(
                    database.querySingleLong("SELECT count(*) FROM sector WHERE population IS NULL"),
                    manifest.missingPopulationSectorCount.toLong(),
                    "missing population sector count",
                )
                requireDatabaseValue(
                    database.querySingleLong(
                        "SELECT count(*) FROM sector WHERE municipality_code IS NULL",
                    ),
                    manifest.unassignedSectorCount.toLong(),
                    "unassigned sector count",
                )
                requireDatabaseValue(
                    database.querySingleLong("SELECT coalesce(sum(population_total), 0) FROM municipality"),
                    manifest.populationTotal,
                    "municipality population total",
                )
                requireDatabaseValue(
                    database.querySingleLong("SELECT coalesce(sum(population), 0) FROM sector"),
                    manifest.populationTotal,
                    "sector population total",
                )
                val sample = database.querySingleString(
                    "SELECT name FROM municipality WHERE code = '3550308'",
                )
                if (sample != "São Paulo") {
                    throw IOException("The known municipality sample is missing or invalid.")
                }
            }
        } catch (error: Exception) {
            throw IbgeDatasetException(
                failure = IbgeDatasetFailure.INCOMPATIBLE_DATABASE,
                message = "The installed IBGE database is incompatible or corrupt.",
                cause = error,
            )
        }
    }

    private fun verifyFile(
        file: File,
        expectedBytes: Long,
        expectedSha256: String,
    ): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex() == expectedSha256
    }

    private fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )
}

@Serializable
internal data class IbgeAssetManifest(
    val manifestSchema: Int,
    val databaseSchema: Int,
    val databaseApplicationId: Int,
    val datasetId: String,
    val datasetTitle: String,
    val provider: String,
    val censusYear: Int,
    val sourceCrs: String,
    val sourceCrsName: String,
    val sourceUrl: String,
    val sourcePageUrl: String,
    val sourceAccessedOn: String,
    val sourceArchiveName: String,
    val sourceArchiveBytes: Long,
    val sourceArchiveSha256: String,
    val sourceIndexSha256: String,
    val sourceSignature: String,
    val attribution: String,
    val licenseStatus: String,
    val geometryIncluded: Boolean,
    val sectorBoundsDescription: String,
    val populationField: String,
    val sectorCount: Int,
    val municipalityCount: Int,
    val unassignedSectorCount: Int,
    val missingPopulationSectorCount: Int,
    val populationTotal: Long,
    val compressedAssetFile: String,
    val compressedByteCount: Long,
    val compressedSha256: String,
    val databaseByteCount: Long,
    val databaseSha256: String,
    val transformerVersion: String,
) {
    fun validate() {
        require(manifestSchema == SUPPORTED_MANIFEST_SCHEMA)
        require(databaseSchema == SUPPORTED_DATABASE_SCHEMA)
        require(databaseApplicationId == EXPECTED_DATABASE_APPLICATION_ID)
        require(datasetId == EXPECTED_DATASET_ID)
        require(datasetTitle == EXPECTED_DATASET_TITLE)
        require(provider == EXPECTED_PROVIDER)
        require(censusYear == EXPECTED_CENSUS_YEAR)
        require(sourceCrs == EXPECTED_SOURCE_CRS && sourceCrsName == EXPECTED_SOURCE_CRS_NAME)
        require(sourceUrl == EXPECTED_SOURCE_URL && sourcePageUrl == EXPECTED_SOURCE_PAGE_URL)
        require(sourceAccessedOn == EXPECTED_SOURCE_ACCESSED_ON)
        require(sourceArchiveName == EXPECTED_SOURCE_ARCHIVE_NAME)
        require(sourceArchiveName == sourceUrl.substringAfterLast('/'))
        require(sourceArchiveBytes == EXPECTED_SOURCE_ARCHIVE_BYTES)
        require(sourceArchiveSha256 == EXPECTED_SOURCE_ARCHIVE_SHA256)
        require(sourceIndexSha256 == EXPECTED_SOURCE_INDEX_SHA256)
        require(sourceSignature == EXPECTED_SOURCE_SIGNATURE)
        require(attribution == EXPECTED_ATTRIBUTION)
        require(licenseStatus == EXPECTED_LICENSE_STATUS)
        require(!geometryIncluded)
        require(sectorBoundsDescription == EXPECTED_SECTOR_BOUNDS_DESCRIPTION)
        require(populationField == EXPECTED_POPULATION_FIELD)
        require(sectorCount == EXPECTED_SECTOR_COUNT)
        require(municipalityCount == EXPECTED_MUNICIPALITY_COUNT)
        require(unassignedSectorCount == EXPECTED_UNASSIGNED_SECTOR_COUNT)
        require(missingPopulationSectorCount == EXPECTED_MISSING_POPULATION_SECTOR_COUNT)
        require(populationTotal == EXPECTED_POPULATION_TOTAL)
        require(compressedAssetFile == EXPECTED_COMPRESSED_ASSET_FILE)
        require(compressedByteCount == EXPECTED_COMPRESSED_BYTES)
        require(databaseByteCount == EXPECTED_DATABASE_BYTES)
        require(compressedSha256 == EXPECTED_COMPRESSED_SHA256)
        require(databaseSha256 == EXPECTED_DATABASE_SHA256)
        require(transformerVersion == EXPECTED_TRANSFORMER_VERSION)
    }

    fun toDescriptor(): IbgeDatasetDescriptor = IbgeDatasetDescriptor(
        datasetId = datasetId,
        title = datasetTitle,
        provider = provider,
        censusYear = censusYear,
        sourceCrs = sourceCrs,
        sourceCrsName = sourceCrsName,
        sourceUrl = sourceUrl,
        sourcePageUrl = sourcePageUrl,
        sourceAccessedOn = sourceAccessedOn,
        attribution = attribution,
        licenseStatus = licenseStatus,
        geometryIncluded = geometryIncluded,
        sectorBoundsDescription = sectorBoundsDescription,
        populationField = populationField,
        sectorCount = sectorCount,
        municipalityCount = municipalityCount,
        unassignedSectorCount = unassignedSectorCount,
        missingPopulationSectorCount = missingPopulationSectorCount,
        populationTotal = populationTotal,
        compressedByteCount = compressedByteCount,
        installedByteCount = databaseByteCount,
        databaseSha256 = databaseSha256,
    )
}

private data class PreparedDataset(
    val manifest: IbgeAssetManifest,
    val database: File,
    val descriptor: IbgeDatasetDescriptor,
)

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0L
        private set

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) count++
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { read ->
            if (read > 0) count += read
        }
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) throw IOException("The embedded manifest exceeds its size limit.")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeStrictUtf8(payload: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(java.nio.ByteBuffer.wrap(payload))
    .toString()

private fun SQLiteDatabase.querySingleLong(sql: String): Long = rawQuery(sql, null).use { cursor ->
    if (!cursor.moveToFirst() || cursor.columnCount != 1 || !cursor.isLast) {
        throw IOException("The IBGE database returned an invalid scalar result.")
    }
    cursor.getLong(0)
}

private fun SQLiteDatabase.querySingleString(sql: String): String = rawQuery(sql, null).use { cursor ->
    if (!cursor.moveToFirst() || cursor.columnCount != 1 || !cursor.isLast) {
        throw IOException("The IBGE database returned an invalid scalar result.")
    }
    cursor.getString(0)
}

private fun SQLiteDatabase.readMetadata(): Map<String, String> =
    rawQuery("SELECT key, value FROM metadata ORDER BY key", null).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                if (size >= MAX_METADATA_ENTRIES) {
                    throw IOException("The IBGE metadata table exceeds its entry limit.")
                }
                val key = cursor.getString(0)
                val value = cursor.getString(1)
                if (key.length > MAX_METADATA_KEY_LENGTH || value.length > MAX_METADATA_VALUE_LENGTH) {
                    throw IOException("The IBGE metadata table exceeds its text limits.")
                }
                if (put(key, value) != null) {
                    throw IOException("The IBGE metadata table contains a duplicate key.")
                }
            }
        }
    }

private fun requireMetadata(metadata: Map<String, String>, key: String, expected: String) {
    if (metadata[key] != expected) throw IOException("IBGE metadata '$key' does not match the manifest.")
}

private fun requireDatabaseValue(actual: Long, expected: Long, label: String) {
    if (actual != expected) throw IOException("The IBGE database $label is inconsistent.")
}

private fun readMunicipalities(cursor: Cursor): List<IbgeMunicipalitySummary> = buildList {
    while (cursor.moveToNext()) {
        add(
            IbgeMunicipalitySummary(
                code = cursor.getString(0),
                stateCode = cursor.getString(1),
                stateAbbreviation = cursor.getString(2),
                stateName = cursor.getString(3),
                name = cursor.getString(4),
                sectorCount = cursor.getInt(5),
                urbanSectorCount = cursor.getInt(6),
                ruralSectorCount = cursor.getInt(7),
                unspecifiedSectorCount = cursor.getInt(8),
                missingPopulationSectorCount = cursor.getInt(9),
                populationTotal = cursor.getLong(10),
                urbanPopulation = cursor.getLong(11),
                ruralPopulation = cursor.getLong(12),
                unspecifiedPopulation = cursor.getLong(13),
                areaTotalKm2 = cursor.getDouble(14),
                urbanAreaKm2 = cursor.getDouble(15),
                ruralAreaKm2 = cursor.getDouble(16),
                unspecifiedAreaKm2 = cursor.getDouble(17),
                west = cursor.getDouble(18),
                south = cursor.getDouble(19),
                east = cursor.getDouble(20),
                north = cursor.getDouble(21),
            ),
        )
    }
}

private fun escapeLike(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes.toDouble() / 1024.0)
    else -> "$bytes bytes"
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> String.format(Locale.ROOT, "%02x", byte) }

private const val MUNICIPALITY_SELECT =
    "SELECT m.code, m.state_code, s.abbreviation, s.name, m.name, " +
        "m.sector_count, m.urban_sector_count, m.rural_sector_count, " +
        "m.unspecified_sector_count, m.missing_population_sector_count, " +
        "m.population_total, m.urban_population, m.rural_population, " +
        "m.unspecified_population, m.area_total_km2, m.urban_area_km2, " +
        "m.rural_area_km2, m.unspecified_area_km2, m.west, m.south, m.east, m.north " +
        "FROM municipality AS m JOIN state AS s ON s.code = m.state_code "

private const val ASSET_DIRECTORY = "datasets/ibge"
private const val MANIFEST_ASSET = "$ASSET_DIRECTORY/manifest.json"
private const val INSTALL_DIRECTORY = "datasets/ibge"
private const val STAGING_PREFIX = "ibge-"
private const val STAGING_SUFFIX = ".part"
private const val IO_BUFFER_BYTES = 64 * 1024
private const val PROGRESS_REPORT_BYTES = 1024L * 1024L
private const val INSTALL_SAFETY_BYTES = 16L * 1024L * 1024L
private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_METADATA_ENTRIES = 128
private const val MAX_METADATA_KEY_LENGTH = 128
private const val MAX_METADATA_VALUE_LENGTH = 4 * 1024
private const val MAXIMUM_MUNICIPALITY_SECTORS = 100_000
private const val SUPPORTED_MANIFEST_SCHEMA = 1
private const val SUPPORTED_DATABASE_SCHEMA = 1
private const val EXPECTED_DATABASE_APPLICATION_ID = 0x41545849
private const val EXPECTED_DATASET_ID = "ibge-census-sectors-2022-brazil"
private const val EXPECTED_DATASET_TITLE = "IBGE 2022 Census Sector Index — Brazil"
private const val EXPECTED_PROVIDER = "Instituto Brasileiro de Geografia e Estatística (IBGE)"
private const val EXPECTED_CENSUS_YEAR = 2022
private const val EXPECTED_SOURCE_CRS = "EPSG:4674"
private const val EXPECTED_SOURCE_CRS_NAME = "SIRGAS 2000 geographic"
private const val EXPECTED_SOURCE_URL = "https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/malhas_de_setores_censitarios__divisoes_intramunicipais/censo_2022/setores/shp/BR/BR_setores_CD2022.zip"
private const val EXPECTED_SOURCE_PAGE_URL = "https://www.ibge.gov.br/geociencias/organizacao-do-territorio/malhas-territoriais/26565-malhas-de-setores-censitarios-divisoes-intramunicipais.html"
private const val EXPECTED_SOURCE_ACCESSED_ON = "2026-08-27"
private const val EXPECTED_SOURCE_ARCHIVE_NAME = "BR_setores_CD2022.zip"
private const val EXPECTED_SOURCE_ARCHIVE_BYTES = 784_726_998L
private const val EXPECTED_SOURCE_ARCHIVE_SHA256 =
    "2674870a37718df4418f93dcca7d6931783f7b03f59562de82c7402324350750"
private const val EXPECTED_SOURCE_INDEX_SHA256 =
    "fe8b789027d54de02de5fd1ddac7c77325657ee09721672008cb6227009a91a7"
private const val EXPECTED_SOURCE_SIGNATURE =
    "01751dfb92b0b37a5b73f874b0f8a6e79165ab4242dd1a77e0cfc3526d2f2141"
private const val EXPECTED_ATTRIBUTION =
    "Source: IBGE — 2022 Census Sector Mesh and sector aggregates."
private const val EXPECTED_LICENSE_STATUS =
    "Public IBGE download; the source archive contains no machine-readable license. " +
        "Review the applicable IBGE terms before public redistribution."
private const val EXPECTED_SECTOR_BOUNDS_DESCRIPTION =
    "Portable SQLite table with sector bounding boxes; no spatial extension or polygon geometry"
private const val EXPECTED_POPULATION_FIELD = "v0001"
private const val EXPECTED_SECTOR_COUNT = 468_099
private const val EXPECTED_MUNICIPALITY_COUNT = 5_570
private const val EXPECTED_UNASSIGNED_SECTOR_COUNT = 2
private const val EXPECTED_MISSING_POPULATION_SECTOR_COUNT = 0
private const val EXPECTED_POPULATION_TOTAL = 203_080_756L
private const val EXPECTED_COMPRESSED_BYTES = 22_133_986L
private const val EXPECTED_COMPRESSED_SHA256 =
    "0769c067211bb872871064e80ed2f2cf2a0d042b3f9c1f236517852d2b301112"
private const val EXPECTED_COMPRESSED_ASSET_FILE =
    "ibge-census-sectors-2022-$EXPECTED_COMPRESSED_SHA256.ibgedata"
private const val EXPECTED_DATABASE_BYTES = 70_926_336L
private const val EXPECTED_DATABASE_SHA256 =
    "fd116b30b8d95abd7203ec5f013f820ea6bbd33022d2f979de7b8892f925d22b"
private const val EXPECTED_TRANSFORMER_VERSION = "1"

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val INSTALLED_DATABASE_FILE_PATTERN = Regex("^[0-9a-f]{64}\\.sqlite$")
private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = false
}
