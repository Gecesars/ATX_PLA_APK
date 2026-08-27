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
private const val ARCHIVE_PROJECT_CATALOG_SCHEMA_VERSION = 3

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
    private val legacyJson: Json = projectCatalogJson(ignoreUnknownKeys = true),
    private val currentJson: Json = projectCatalogJson(ignoreUnknownKeys = false),
) {
    @Throws(CharacterCodingException::class, SerializationException::class)
    fun parse(payload: ByteArray): EncodedProjectCatalog {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
        val jsonElement = legacyJson.parseToJsonElement(text)
        val jsonObject = jsonElement as? JsonObject
            ?: throw SerializationException("The project catalog root must be a JSON object.")
        val schemaVersion = jsonObject["schemaVersion"]
            ?.let(::parseSchemaVersion)
            ?: LEGACY_PROJECT_CATALOG_SCHEMA_VERSION
        return EncodedProjectCatalog(schemaVersion, jsonElement)
    }

    fun decode(document: EncodedProjectCatalog): ProjectCatalog {
        val decoder = if (document.schemaVersion >= PROJECT_CATALOG_SCHEMA_VERSION) {
            currentJson
        } else {
            legacyJson
        }
        return decoder.decodeFromJsonElement(ProjectCatalog.serializer(), document.jsonElement)
    }

    fun decode(payload: ByteArray): ProjectCatalog = decode(parse(payload))

    fun encode(catalog: ProjectCatalog): ByteArray =
        currentJson.encodeToString(ProjectCatalog.serializer(), catalog).toByteArray(Charsets.UTF_8)

    private fun parseSchemaVersion(element: JsonElement): Int {
        val version = (element as? JsonPrimitive)?.intOrNull
        if (version == null || version < LEGACY_PROJECT_CATALOG_SCHEMA_VERSION) {
            throw SerializationException("The project catalog schema version is invalid.")
        }
        return version
    }
}

private fun projectCatalogJson(ignoreUnknownKeys: Boolean) = Json {
    prettyPrint = true
    encodeDefaults = true
    this.ignoreUnknownKeys = ignoreUnknownKeys
    explicitNulls = false
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
        val sanitizedRoot = if (document.schemaVersion < ARCHIVE_PROJECT_CATALOG_SCHEMA_VERSION) {
            jsonObject.filterKeys { key -> key != ARCHIVED_PROJECTS_FIELD_NAME }
        } else {
            jsonObject
        }
        return document.copy(
            jsonElement = if (document.schemaVersion < PROJECT_CATALOG_SCHEMA_VERSION) {
                sanitizePreVersion4Root(
                    root = JsonObject(sanitizedRoot),
                    sourceSchemaVersion = document.schemaVersion,
                )
            } else {
                JsonObject(sanitizedRoot)
            },
        )
    }

    fun migrate(catalog: ProjectCatalog, sourceSchemaVersion: Int): ProjectCatalog =
        when (sourceSchemaVersion) {
            LEGACY_PROJECT_CATALOG_SCHEMA_VERSION ->
                migrateVersion3ToVersion4(
                    migrateVersion2ToVersion3(migrateVersion1ToVersion2(catalog)),
                )
            RF_REFERENCE_PROJECT_CATALOG_SCHEMA_VERSION ->
                migrateVersion3ToVersion4(migrateVersion2ToVersion3(catalog))
            ARCHIVE_PROJECT_CATALOG_SCHEMA_VERSION -> migrateVersion3ToVersion4(catalog)
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
            schemaVersion = ARCHIVE_PROJECT_CATALOG_SCHEMA_VERSION,
            // Archive storage did not exist before v3. Never promote an injected legacy field.
            archivedProjects = emptyList(),
        )

    private fun migrateVersion3ToVersion4(catalog: ProjectCatalog): ProjectCatalog =
        catalog.copy(schemaVersion = PROJECT_CATALOG_SCHEMA_VERSION)

    private fun sanitizePreVersion4Root(
        root: JsonObject,
        sourceSchemaVersion: Int,
    ): JsonObject = JsonObject(
        root.mapValues { (key, value) ->
            when (key) {
                PROJECTS_FIELD_NAME -> sanitizeProjectArray(value, sourceSchemaVersion)
                ARCHIVED_PROJECTS_FIELD_NAME -> sanitizeArchiveArray(value, sourceSchemaVersion)
                else -> value
            }
        },
    )

    private fun sanitizeProjectArray(
        value: JsonElement,
        sourceSchemaVersion: Int,
    ): JsonElement =
        (value as? kotlinx.serialization.json.JsonArray)?.let { projects ->
            kotlinx.serialization.json.JsonArray(
                projects.map { project -> sanitizeProject(project, sourceSchemaVersion) },
            )
        } ?: value

    private fun sanitizeArchiveArray(
        value: JsonElement,
        sourceSchemaVersion: Int,
    ): JsonElement =
        (value as? kotlinx.serialization.json.JsonArray)?.let { archives ->
            kotlinx.serialization.json.JsonArray(
                archives.map { archiveElement ->
                    val archive = archiveElement as? JsonObject ?: return@map archiveElement
                    JsonObject(
                        archive.mapValues { (key, nestedValue) ->
                            if (key == ARCHIVED_PROJECT_FIELD_NAME) {
                                sanitizeProject(nestedValue, sourceSchemaVersion)
                            } else {
                                nestedValue
                            }
                        },
                    )
                },
            )
        } ?: value

    private fun sanitizeProject(
        value: JsonElement,
        sourceSchemaVersion: Int,
    ): JsonElement {
        val project = value as? JsonObject ?: return value
        val fieldsToRemove = if (sourceSchemaVersion < RF_REFERENCE_PROJECT_CATALOG_SCHEMA_VERSION) {
            VERSION_4_PROJECT_FIELDS + VERSION_2_PROJECT_FIELDS
        } else {
            VERSION_4_PROJECT_FIELDS
        }
        val base = project.filterKeys { it !in fieldsToRemove }
        return JsonObject(
            base.mapValues { (key, nestedValue) ->
                when (key) {
                    NETWORKS_FIELD_NAME -> sanitizeObjectArray(nestedValue, VERSION_4_NETWORK_FIELDS)
                    SITES_FIELD_NAME -> sanitizeSites(nestedValue, sourceSchemaVersion)
                    RECEIVERS_FIELD_NAME -> sanitizeObjectArray(
                        nestedValue,
                        VERSION_4_RECEIVER_FIELDS,
                    )
                    else -> nestedValue
                }
            },
        )
    }

    private fun sanitizeSites(
        value: JsonElement,
        sourceSchemaVersion: Int,
    ): JsonElement =
        (value as? kotlinx.serialization.json.JsonArray)?.let { sites ->
            kotlinx.serialization.json.JsonArray(
                sites.map { siteElement ->
                    val site = siteElement as? JsonObject ?: return@map siteElement
                    val base = site.filterKeys { it !in VERSION_4_SITE_FIELDS }
                    JsonObject(
                        base.mapValues { (key, nestedValue) ->
                            if (key == SECTORS_FIELD_NAME) {
                                val fieldsToRemove = if (
                                    sourceSchemaVersion < RF_REFERENCE_PROJECT_CATALOG_SCHEMA_VERSION
                                ) {
                                    VERSION_4_SECTOR_FIELDS + VERSION_2_SECTOR_FIELDS
                                } else {
                                    VERSION_4_SECTOR_FIELDS
                                }
                                sanitizeObjectArray(nestedValue, fieldsToRemove)
                            } else {
                                nestedValue
                            }
                        },
                    )
                },
            )
        } ?: value

    private fun sanitizeObjectArray(
        value: JsonElement,
        removedFields: Set<String>,
    ): JsonElement = (value as? kotlinx.serialization.json.JsonArray)?.let { elements ->
        kotlinx.serialization.json.JsonArray(
            elements.map { element ->
                (element as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key !in removedFields }) }
                    ?: element
            },
        )
    } ?: value

    private companion object {
        const val ARCHIVED_PROJECTS_FIELD_NAME = "archivedProjects"
        const val ARCHIVED_PROJECT_FIELD_NAME = "project"
        const val PROJECTS_FIELD_NAME = "projects"
        const val NETWORKS_FIELD_NAME = "networks"
        const val SITES_FIELD_NAME = "sites"
        const val SECTORS_FIELD_NAME = "sectors"
        const val RECEIVERS_FIELD_NAME = "receivers"

        val VERSION_4_PROJECT_FIELDS = setOf(
            "antennaPatterns",
            "gisLayers",
            "studyScenarios",
            "activeStudyScenarioId",
            "coverageSnapshots",
            "regulatoryStudies",
            "artifacts",
            "importProvenance",
        )
        val VERSION_2_PROJECT_FIELDS = setOf("receivers")
        val VERSION_4_NETWORK_FIELDS = setOf(
            "active",
            "uplinkFrequencyMHz",
            "duplexMode",
            "downlinkThresholdDbm",
            "uplinkThresholdDbm",
            "channelPlan",
            "technologyProfile",
            "legacyParametersJson",
        )
        val VERSION_4_SITE_FIELDS = setOf("towerHeightM")
        val VERSION_4_SECTOR_FIELDS = setOf(
            "transmitAntennaPatternId",
            "receiveAntennaPatternId",
            "receiveAntennaHeightM",
            "receiveAntennaGainDbi",
            "receiveSystemLossDb",
            "cableType",
            "cableLengthM",
            "equipmentModel",
            "mimoIndex",
            "simulcastDelayMicros",
            "legacyParametersJson",
        )
        val VERSION_2_SECTOR_FIELDS = setOf("networkId")
        val VERSION_4_RECEIVER_FIELDS = setOf("equipmentModel", "networkProfiles")
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
