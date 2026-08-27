package com.gecesars.atxplan.data.project

import com.gecesars.atxplan.domain.model.PROJECT_CATALOG_SCHEMA_VERSION
import com.gecesars.atxplan.domain.model.ProjectCatalog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal const val MAX_PROJECT_CATALOG_BYTES: Int = 5 * 1024 * 1024
private const val LEGACY_PROJECT_CATALOG_SCHEMA_VERSION = 1
private const val RF_REFERENCE_PROJECT_CATALOG_SCHEMA_VERSION = 2

/**
 * Byte-oriented storage boundary. Implementations must either replace the complete payload or
 * leave the previously committed payload untouched.
 */
internal interface ProjectCatalogStorage {
    fun exists(): Boolean

    @Throws(IOException::class, ProjectCatalogSizeLimitException::class)
    fun read(maxBytes: Int): ByteArray

    @Throws(IOException::class)
    fun writeAtomically(payload: ByteArray)
}

internal class ProjectCatalogSizeLimitException : IOException()

internal data class EncodedProjectCatalog(
    val schemaVersion: Int,
    val jsonElement: JsonElement,
)

/** JSON and strict UTF-8 policy for the on-device project catalog. */
internal class ProjectCatalogCodec(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    @Throws(CharacterCodingException::class, SerializationException::class)
    fun parse(payload: ByteArray): EncodedProjectCatalog {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
        val jsonElement = json.parseToJsonElement(text)
        val jsonObject = jsonElement as? JsonObject
            ?: throw SerializationException("The project catalog root must be a JSON object.")
        val schemaVersion = jsonObject["schemaVersion"]
            ?.let(::parseSchemaVersion)
            ?: LEGACY_PROJECT_CATALOG_SCHEMA_VERSION
        return EncodedProjectCatalog(schemaVersion, jsonElement)
    }

    fun decode(document: EncodedProjectCatalog): ProjectCatalog =
        json.decodeFromJsonElement(ProjectCatalog.serializer(), document.jsonElement)

    fun decode(payload: ByteArray): ProjectCatalog = decode(parse(payload))

    fun encode(catalog: ProjectCatalog): ByteArray =
        json.encodeToString(ProjectCatalog.serializer(), catalog).toByteArray(Charsets.UTF_8)

    private fun parseSchemaVersion(element: JsonElement): Int {
        val version = (element as? JsonPrimitive)?.intOrNull
        if (version == null || version < LEGACY_PROJECT_CATALOG_SCHEMA_VERSION) {
            throw SerializationException("The project catalog schema version is invalid.")
        }
        return version
    }
}

/** Ordered, explicit migrations from every supported durable schema to the current schema. */
internal class ProjectCatalogMigrator {
    /**
     * Archive data is a schema-3 contract. Remove a same-named injected field from older
     * documents before decoding so untrusted schema-1/2 input cannot smuggle archived projects
     * into the current model or fail validation through data that did not exist in that schema.
     */
    fun documentForDecode(document: EncodedProjectCatalog): EncodedProjectCatalog {
        if (document.schemaVersion >= PROJECT_CATALOG_SCHEMA_VERSION) return document
        val jsonObject = document.jsonElement as? JsonObject ?: return document
        return document.copy(
            jsonElement = JsonObject(
                jsonObject.filterKeys { key -> key != ARCHIVED_PROJECTS_FIELD_NAME },
            ),
        )
    }

    fun migrate(catalog: ProjectCatalog, sourceSchemaVersion: Int): ProjectCatalog =
        when (sourceSchemaVersion) {
            LEGACY_PROJECT_CATALOG_SCHEMA_VERSION ->
                migrateVersion2ToVersion3(migrateVersion1ToVersion2(catalog))
            RF_REFERENCE_PROJECT_CATALOG_SCHEMA_VERSION -> migrateVersion2ToVersion3(catalog)
            PROJECT_CATALOG_SCHEMA_VERSION -> catalog
            else -> throw IllegalArgumentException(
                "No catalog migration exists from schema $sourceSchemaVersion.",
            )
        }

    private fun migrateVersion1ToVersion2(catalog: ProjectCatalog): ProjectCatalog =
        catalog.copy(
            schemaVersion = RF_REFERENCE_PROJECT_CATALOG_SCHEMA_VERSION,
            // The v2 serializer supplies empty receivers and null sector network references for
            // v1 payloads. copy() retains every decoded legacy field and those explicit defaults.
            projects = catalog.projects,
            archivedProjects = emptyList(),
        )

    private fun migrateVersion2ToVersion3(catalog: ProjectCatalog): ProjectCatalog =
        catalog.copy(
            schemaVersion = PROJECT_CATALOG_SCHEMA_VERSION,
            // Archive storage did not exist before v3. Never promote an injected legacy field.
            archivedProjects = emptyList(),
        )

    private companion object {
        const val ARCHIVED_PROJECTS_FIELD_NAME = "archivedProjects"
    }
}

/**
 * Coordinates catalog validation, migration, serialization, and storage without Android APIs.
 * One mutex covers the complete load-transform-save transaction across repository instances.
 */
internal class ProjectCatalogPersistence(
    private val storage: ProjectCatalogStorage,
    private val codec: ProjectCatalogCodec,
    private val seedCatalog: () -> ProjectCatalog,
    private val operationMutex: Mutex = Mutex(),
    private val migrator: ProjectCatalogMigrator = ProjectCatalogMigrator(),
) {
    suspend fun loadCatalog(): ProjectCatalog = operationMutex.withLock {
        loadCatalogLocked()
    }

    suspend fun saveCatalog(catalog: ProjectCatalog) = operationMutex.withLock {
        saveCatalogLocked(catalog)
    }

    suspend fun updateCatalog(
        transform: (ProjectCatalog) -> ProjectCatalog,
    ): ProjectCatalog = operationMutex.withLock {
        val latestCatalog = loadCatalogLocked()
        val updatedCatalog = transform(latestCatalog)
        if (updatedCatalog == latestCatalog) return@withLock latestCatalog
        saveCatalogLocked(updatedCatalog)
        updatedCatalog
    }

    private fun loadCatalogLocked(): ProjectCatalog {
        val hasStoredCatalog = try {
            storage.exists()
        } catch (error: Exception) {
            throw ProjectStorageException("The local catalog could not be accessed.", error)
        }

        if (!hasStoredCatalog) {
            return seedCatalog().also(::saveCatalogLocked)
        }

        val payload = try {
            storage.read(MAX_PROJECT_CATALOG_BYTES)
        } catch (error: ProjectCatalogSizeLimitException) {
            throw ProjectStorageException("The local catalog exceeds the safe 5 MB limit.", error)
        } catch (error: Exception) {
            throw ProjectStorageException("The local catalog could not be opened.", error)
        }

        if (payload.size > MAX_PROJECT_CATALOG_BYTES) {
            throw ProjectStorageException("The local catalog exceeds the safe 5 MB limit.")
        }

        val document = try {
            codec.parse(payload)
        } catch (error: CharacterCodingException) {
            throw ProjectStorageException(
                "The local catalog is not valid UTF-8. The original file was preserved.",
                error,
            )
        } catch (error: SerializationException) {
            throw ProjectStorageException(
                "The local catalog could not be parsed. The original file was preserved.",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw ProjectStorageException(
                "The local catalog contains invalid data. The original file was preserved.",
                error,
            )
        }

        if (document.schemaVersion > PROJECT_CATALOG_SCHEMA_VERSION) {
            throw ProjectStorageException(
                "The catalog was created by a newer version of ATX Plan. " +
                    "The original file was preserved.",
            )
        }

        val decodedCatalog = try {
            codec.decode(migrator.documentForDecode(document))
        } catch (error: SerializationException) {
            throw ProjectStorageException(
                "The local catalog could not be parsed. The original file was preserved.",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw ProjectStorageException(
                "The local catalog contains invalid data. The original file was preserved.",
                error,
            )
        }

        val migratedCatalog = try {
            migrator.migrate(decodedCatalog, document.schemaVersion)
        } catch (error: IllegalArgumentException) {
            throw ProjectStorageException(
                "The local catalog schema is not supported. The original file was preserved.",
                error,
            )
        }
        if (migratedCatalog.schemaVersion != document.schemaVersion) {
            saveCatalogLocked(migratedCatalog)
        }
        return migratedCatalog
    }

    private fun saveCatalogLocked(catalog: ProjectCatalog) {
        if (catalog.schemaVersion != PROJECT_CATALOG_SCHEMA_VERSION) {
            throw ProjectStorageException(
                "Only the current catalog schema can be saved. The existing file was preserved.",
            )
        }

        val payload = try {
            codec.encode(catalog)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The catalog could not be encoded. The existing file was preserved.",
                error,
            )
        }

        if (payload.size > MAX_PROJECT_CATALOG_BYTES) {
            throw ProjectStorageException(
                "The catalog exceeds the safe 5 MB limit. The existing file was preserved.",
            )
        }

        try {
            storage.writeAtomically(payload)
        } catch (error: Exception) {
            throw ProjectStorageException(
                "The catalog could not be saved atomically. The existing file was preserved.",
                error,
            )
        }
    }
}
