package com.gecesars.atxplan.domain.dataset

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Versioned integrity identity for one committed [RegionalInventoryRecord].
 *
 * The fingerprint payload is separate from the persisted inventory schema. Version 1 wraps the
 * complete record without deriving or omitting fields, recursively sorts JSON object keys, keeps
 * array order, encodes UTF-8 without insignificant whitespace, and hashes the exact bytes with
 * SHA-256. The result is suitable for [RegionalJobArtifactOutcomeV1.inventoryEntrySha256].
 */
object RegionalInventoryEntryFingerprint {
    const val VERSION: Int = 1

    /** Returns the frozen version-1 canonical JSON payload used as the hash input. */
    fun canonicalJson(record: RegionalInventoryRecord): String = canonicalJsonElement(
        INVENTORY_ENTRY_JSON.encodeToJsonElement(
            RegionalInventoryEntryFingerprintPayloadV1.serializer(),
            RegionalInventoryEntryFingerprintPayloadV1(record = record),
        ),
    )

    /** Returns a lowercase SHA-256 digest of [canonicalJson]. */
    fun calculate(record: RegionalInventoryRecord): String = MessageDigest.getInstance(SHA_256)
        .digest(canonicalJson(record).toByteArray(StandardCharsets.UTF_8))
        .toLowerHex()

    /**
     * Verifies an outcome digest without throwing for malformed or differently cased input.
     */
    fun matches(record: RegionalInventoryRecord, expectedSha256: String): Boolean {
        if (!LOWERCASE_SHA256.matches(expectedSha256)) return false
        return MessageDigest.isEqual(
            calculate(record).toByteArray(StandardCharsets.US_ASCII),
            expectedSha256.toByteArray(StandardCharsets.US_ASCII),
        )
    }
}

@Serializable
private data class RegionalInventoryEntryFingerprintPayloadV1(
    val fingerprintVersion: Int = RegionalInventoryEntryFingerprint.VERSION,
    val record: RegionalInventoryRecord,
)

private fun canonicalJsonElement(element: JsonElement): String = buildString {
    appendCanonicalJson(element)
}

private fun StringBuilder.appendCanonicalJson(element: JsonElement) {
    when (element) {
        JsonNull -> append("null")
        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, child ->
                if (index > 0) append(',')
                appendCanonicalJson(child)
            }
            append(']')
        }

        is JsonObject -> {
            append('{')
            element.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append(INVENTORY_ENTRY_JSON.encodeToString(String.serializer(), entry.key))
                append(':')
                appendCanonicalJson(entry.value)
            }
            append('}')
        }

        is JsonPrimitive -> if (element.isString) {
            append(INVENTORY_ENTRY_JSON.encodeToString(String.serializer(), element.content))
        } else {
            append(element.content)
        }
    }
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        append(LOWER_HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(LOWER_HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val SHA_256 = "SHA-256"
private const val LOWER_HEX_DIGITS = "0123456789abcdef"
private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")

private val INVENTORY_ENTRY_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = false
}
