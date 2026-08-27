package com.gecesars.atxplan.data.project

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal class ProjectCatalogIndexCodec(
    private val json: Json = strictProjectStoreJson(),
) {
    fun isIndex(payload: ByteArray): Boolean = try {
        val root = json.parseToJsonElement(decodeStrictUtf8(payload)) as? JsonObject
        root?.get("format")?.jsonPrimitive?.content == PROJECT_STORE_FORMAT
    } catch (_: Exception) {
        false
    }

    /**
     * Detects a project-store control payload that must not be interpreted as a legacy catalog.
     * Unknown formats and damaged indexes fail closed instead of being decoded with schema-1
     * defaults and replacing the source bytes with an empty catalog.
     */
    fun hasProjectStoreDiscriminator(payload: ByteArray): Boolean = try {
        val root = json.parseToJsonElement(decodeStrictUtf8(payload)) as? JsonObject
        root != null && PROJECT_STORE_DISCRIMINATOR_FIELDS.any(root::containsKey)
    } catch (_: Exception) {
        false
    }

    @Throws(CharacterCodingException::class, SerializationException::class)
    fun decode(payload: ByteArray): ProjectCatalogIndex {
        val root = parseObject(json, payload, "project index")
        requirePersistedFields(root, PROJECT_INDEX_REQUIRED_FIELDS, "project index")
        return json.decodeFromJsonElement(ProjectCatalogIndex.serializer(), root)
    }

    fun encode(index: ProjectCatalogIndex): ByteArray =
        json.encodeToString(ProjectCatalogIndex.serializer(), index).toByteArray(Charsets.UTF_8)
}

private val PROJECT_STORE_DISCRIMINATOR_FIELDS = setOf(
    "format",
    "storeSchemaVersion",
    "projectSchemaVersion",
)

internal class ProjectDocumentCodec(
    private val json: Json = strictProjectStoreJson(),
) {
    @Throws(CharacterCodingException::class, SerializationException::class)
    fun decode(payload: ByteArray): ProjectDocument {
        val root = parseObject(json, payload, "project document")
        requirePersistedFields(root, PROJECT_DOCUMENT_REQUIRED_FIELDS, "project document")
        return json.decodeFromJsonElement(ProjectDocument.serializer(), root)
    }

    fun encode(document: ProjectDocument): ByteArray =
        json.encodeToString(ProjectDocument.serializer(), document).toByteArray(Charsets.UTF_8)
}

internal fun sha256Hex(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(payload)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

@Throws(CharacterCodingException::class)
private fun decodeStrictUtf8(payload: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(payload))
    .toString()

private fun parseObject(json: Json, payload: ByteArray, label: String): JsonObject =
    json.parseToJsonElement(decodeStrictUtf8(payload)) as? JsonObject
        ?: throw SerializationException("The $label root must be a JSON object.")

private fun requirePersistedFields(
    root: JsonObject,
    requiredFields: Set<String>,
    label: String,
) {
    val missingFields = requiredFields.filterNot(root::containsKey)
    if (missingFields.isNotEmpty()) {
        throw SerializationException(
            "The $label is missing required fields: ${missingFields.joinToString()}.",
        )
    }
}

private val PROJECT_INDEX_REQUIRED_FIELDS = setOf(
    "format",
    "storeSchemaVersion",
    "projectSchemaVersion",
)

private val PROJECT_DOCUMENT_REQUIRED_FIELDS = setOf(
    "documentSchemaVersion",
    "projectSchemaVersion",
)

private fun strictProjectStoreJson() = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
