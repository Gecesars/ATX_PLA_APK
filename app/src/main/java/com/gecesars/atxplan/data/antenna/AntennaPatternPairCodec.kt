package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.antenna.PatternCoordinateFrame
import com.gecesars.atxplan.domain.antenna.PatternCutAvailability
import com.gecesars.atxplan.domain.antenna.PatternCutPlane
import com.gecesars.atxplan.domain.antenna.PatternOrigin
import com.gecesars.atxplan.domain.antenna.PatternProvenance
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max

data class AntennaPatternPairSource(
    val displayName: String,
    val payload: ByteArray,
)

data class PairedAntennaPatternImport(
    val pattern: CanonicalAntennaPattern,
    val metadata: AntennaPatternFileMetadata,
    val warnings: List<String>,
    val sourceBundle: ByteArray,
    val sourceBundleSha256: String,
    val sourceBundleFileName: String,
    val sourceFormatLabel: String,
    val componentDisplayNames: List<String>,
)

/**
 * Joins exactly one independently decoded HRP and one independently decoded VRP.
 *
 * The original source bytes are retained in a deterministic, stored-entry ZIP. The manifest is
 * intentionally small and contains the exact component hashes, formats, planes, and byte counts.
 * No isotropic compatibility placeholder may enter this path.
 */
object AntennaPatternPairCodec {
    fun parsePair(
        sources: List<AntennaPatternPairSource>,
        prnValueConventionOverride: PrnValueConventionOverride? = null,
    ): PairedAntennaPatternImport {
        if (sources.size != REQUIRED_SOURCE_COUNT) {
            throw AntennaPatternCodecException(
                "HRP/VRP pairing requires exactly two source files.",
            )
        }
        val rawByteCount = sources.sumOf { source -> source.payload.size.toLong() }
        if (rawByteCount <= 0L || rawByteCount > MAX_PAIR_SOURCE_BYTES) {
            throw AntennaPatternCodecException(
                "The two antenna source files exceed the bounded 15 MiB pairing limit.",
            )
        }
        val sanitized = sources.map(::sanitizeSource)
        val decoded = sanitized.map { source ->
            val result = try {
                AntennaPatternFileCodecs.decode(source.payload, source.displayName)
            } catch (error: PrnValueConventionRequiredException) {
                val selectedConvention = prnValueConventionOverride ?: throw error
                AntennaPatternFileCodecs.decode(
                    payload = source.payload,
                    sourceLabel = source.displayName,
                    prnValueConventionOverride = selectedConvention,
                )
            }
            if (result.pattern != null || result.cuts.size != 1) {
                throw AntennaPatternCodecException(
                    "${source.displayName} does not contain exactly one independent HRP or VRP cut; " +
                        "import complete two-cut formats separately.",
                )
            }
            val cut = result.cuts.single()
            if (cut.availability != PatternCutAvailability.AVAILABLE) {
                throw AntennaPatternCodecException(
                    "${source.displayName} does not contain an explicitly available antenna cut.",
                )
            }
            DecodedPairSource(source, result)
        }
        val horizontal = decoded.singleOrNull { source ->
            source.result.cuts.single().plane == PatternCutPlane.HORIZONTAL
        } ?: throw AntennaPatternCodecException(
            "The selected files do not provide exactly one HRP and one VRP; an HRP is missing or duplicated.",
        )
        val vertical = decoded.singleOrNull { source ->
            source.result.cuts.single().plane == PatternCutPlane.VERTICAL
        } ?: throw AntennaPatternCodecException(
            "The selected files do not provide exactly one HRP and one VRP; a VRP is missing or duplicated.",
        )
        if (horizontal === vertical) {
            throw AntennaPatternCodecException(
                "The selected files do not provide distinct HRP and VRP cuts.",
            )
        }

        val mergeWarnings = mutableListOf<String>()
        val nominalFrequencyHz = mergeCompatibleValue(
            label = "nominal frequency",
            horizontalValue = horizontal.result.metadata.nominalFrequencyHz,
            verticalValue = vertical.result.metadata.nominalFrequencyHz,
            absoluteTolerance = 1.0,
            relativeTolerance = 1.0e-9,
            units = "Hz",
            warnings = mergeWarnings,
        )
        val declaredGainDbi = mergeCompatibleValue(
            label = "declared peak gain",
            horizontalValue = horizontal.result.metadata.declaredGainDbi,
            verticalValue = vertical.result.metadata.declaredGainDbi,
            absoluteTolerance = 0.01,
            relativeTolerance = 0.0,
            units = "dBi",
            warnings = mergeWarnings,
        )
        val beamTiltDegrees = mergeCompatibleValue(
            label = "beam tilt",
            horizontalValue = horizontal.result.metadata.beamTiltDegrees,
            verticalValue = vertical.result.metadata.beamTiltDegrees,
            absoluteTolerance = 0.001,
            relativeTolerance = 0.0,
            units = "degrees",
            warnings = mergeWarnings,
        )

        val ordered = listOf(horizontal, vertical)
        val bundle = createSourceBundle(ordered)
        if (bundle.size > AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
            throw AntennaPatternCodecException(
                "The preserved HRP/VRP source bundle exceeds the 16 MiB artifact limit.",
            )
        }
        val bundleSha256 = sha256(bundle)
        val pairName = pairedPatternName(horizontal.source.displayName, vertical.source.displayName)
        val sourceFormatLabel =
            "Paired ${horizontal.result.detectedFormat.displayName} + " +
                vertical.result.detectedFormat.displayName
        val componentWarnings = ordered.flatMap { source ->
            source.result.warnings.map { warning ->
                "${source.source.displayName}: $warning".take(MAX_PROVENANCE_TEXT_CHARACTERS)
            }
        }
        val warnings = (componentWarnings + mergeWarnings + PAIR_SOURCE_WARNING)
            .distinct()
            .take(MAX_PAIR_WARNINGS)
        val provenance = PatternProvenance(
            origin = PatternOrigin.IMPORTED,
            sourceLabel = "${horizontal.source.displayName} + ${vertical.source.displayName}"
                .take(MAX_PROVENANCE_TEXT_CHARACTERS),
            sourceFormat = sourceFormatLabel,
            sourceSha256 = bundleSha256,
            coordinateFrame = PatternCoordinateFrame.APERTURE_XY_BORESIGHT_Z,
            sourceCoordinateFrame = PatternCoordinateFrame.SOURCE_RELATIVE_UNSPECIFIED,
            engineId = "atx-plan-android-hrp-vrp-pair-v1",
            warnings = warnings,
            limitations = listOf(
                "The paired model contains one separable HRP and one separable VRP, not a full 3D pattern.",
            ),
        )
        val pattern = CanonicalAntennaPattern(
            id = "paired-${bundleSha256.take(16)}",
            name = pairName,
            horizontalCut = horizontal.result.cuts.single(),
            verticalCut = vertical.result.cuts.single(),
            provenance = provenance,
            nominalFrequencyHz = nominalFrequencyHz,
        )
        return PairedAntennaPatternImport(
            pattern = pattern,
            metadata = AntennaPatternFileMetadata(
                nominalFrequencyHz = nominalFrequencyHz,
                declaredGainDbi = declaredGainDbi,
                verticalCutAzimuthDegrees = vertical.result.metadata.verticalCutAzimuthDegrees,
                beamTiltDegrees = beamTiltDegrees,
            ),
            warnings = warnings,
            sourceBundle = bundle,
            sourceBundleSha256 = bundleSha256,
            sourceBundleFileName = "${pairName.safeFileStem()}.atx-antenna-sources.zip",
            sourceFormatLabel = sourceFormatLabel,
            componentDisplayNames = ordered.map { source -> source.source.displayName },
        )
    }

    /** Read-only helper used by tests and future provenance inspectors. */
    fun inspectBundleEntries(bundle: ByteArray): Map<String, ByteArray> {
        if (bundle.size > AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
            throw AntennaPatternCodecException("The antenna source bundle exceeds the 16 MiB limit.")
        }
        val entries = linkedMapOf<String, ByteArray>()
        var totalUncompressedBytes = 0
        ZipInputStream(bundle.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entries.size >= MAX_BUNDLE_ENTRIES ||
                    entry.isDirectory ||
                    entry.name.isBlank() ||
                    entry.name.startsWith('/') ||
                    entry.name.startsWith('\\') ||
                    entry.name.split('/', '\\').any { segment -> segment == ".." } ||
                    entry.name in entries
                ) {
                    throw AntennaPatternCodecException("The antenna source bundle has an invalid entry table.")
                }
                val payload = input.readBytesBounded(
                    AntennaPatternCodecLimits.MAX_INPUT_BYTES - totalUncompressedBytes,
                )
                totalUncompressedBytes += payload.size
                entries[entry.name] = payload
                input.closeEntry()
            }
        }
        if (entries.size != MAX_BUNDLE_ENTRIES ||
            "manifest.json" !in entries ||
            entries.keys.count { name -> name.startsWith("horizontal/") } != 1 ||
            entries.keys.count { name -> name.startsWith("vertical/") } != 1
        ) {
            throw AntennaPatternCodecException(
                "The antenna source bundle must contain one manifest, one HRP, and one VRP entry.",
            )
        }
        return entries
    }

    private fun createSourceBundle(ordered: List<DecodedPairSource>): ByteArray {
        val manifest = buildManifest(ordered).toByteArray(Charsets.UTF_8)
        val entries = listOf("manifest.json" to manifest) + ordered.map { source ->
            val directory = when (source.result.cuts.single().plane) {
                PatternCutPlane.HORIZONTAL -> "horizontal"
                PatternCutPlane.VERTICAL -> "vertical"
            }
            "$directory/${source.source.displayName}" to source.source.payload
        }
        val output = ByteArrayOutputStream()
        val centralEntries = mutableListOf<DeterministicZipEntry>()
        entries.forEach { (name, payload) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(payload) }.value
            val localOffset = output.size().toLong()
            output.writeLittleEndianInt(ZIP_LOCAL_FILE_HEADER_SIGNATURE)
            output.writeLittleEndianShort(ZIP_VERSION_NEEDED)
            output.writeLittleEndianShort(ZIP_UTF8_FLAG)
            output.writeLittleEndianShort(ZIP_STORED_METHOD)
            output.writeLittleEndianShort(ZIP_FIXED_DOS_TIME)
            output.writeLittleEndianShort(ZIP_FIXED_DOS_DATE)
            output.writeLittleEndianInt(crc)
            output.writeLittleEndianInt(payload.size.toLong())
            output.writeLittleEndianInt(payload.size.toLong())
            output.writeLittleEndianShort(nameBytes.size)
            output.writeLittleEndianShort(0)
            output.write(nameBytes)
            output.write(payload)
            centralEntries += DeterministicZipEntry(nameBytes, payload.size, crc, localOffset)
        }
        val centralOffset = output.size().toLong()
        centralEntries.forEach { entry ->
            output.writeLittleEndianInt(ZIP_CENTRAL_DIRECTORY_SIGNATURE)
            output.writeLittleEndianShort(ZIP_VERSION_NEEDED)
            output.writeLittleEndianShort(ZIP_VERSION_NEEDED)
            output.writeLittleEndianShort(ZIP_UTF8_FLAG)
            output.writeLittleEndianShort(ZIP_STORED_METHOD)
            output.writeLittleEndianShort(ZIP_FIXED_DOS_TIME)
            output.writeLittleEndianShort(ZIP_FIXED_DOS_DATE)
            output.writeLittleEndianInt(entry.crc32)
            output.writeLittleEndianInt(entry.payloadSize.toLong())
            output.writeLittleEndianInt(entry.payloadSize.toLong())
            output.writeLittleEndianShort(entry.nameBytes.size)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianInt(0)
            output.writeLittleEndianInt(entry.localOffset)
            output.write(entry.nameBytes)
        }
        val centralSize = output.size().toLong() - centralOffset
        output.writeLittleEndianInt(ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(centralEntries.size)
        output.writeLittleEndianShort(centralEntries.size)
        output.writeLittleEndianInt(centralSize)
        output.writeLittleEndianInt(centralOffset)
        output.writeLittleEndianShort(0)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        require(value in 0..0xffff) { "A deterministic ZIP short value is out of range." }
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Long) {
        require(value in 0L..0xffff_ffffL) { "A deterministic ZIP integer value is out of range." }
        repeat(4) { byteIndex -> write(((value ushr (byteIndex * 8)) and 0xffL).toInt()) }
    }

    private fun buildManifest(ordered: List<DecodedPairSource>): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"kind\": \"ATX_PLAN_HRP_VRP_SOURCE_PAIR\",\n")
        append("  \"entries\": [\n")
        ordered.forEachIndexed { index, source ->
            val plane = source.result.cuts.single().plane.name
            append("    {\"plane\": \"")
            append(plane)
            append("\", \"fileName\": \"")
            append(source.source.displayName.jsonEscaped())
            append("\", \"format\": \"")
            append(source.result.detectedFormat.displayName.jsonEscaped())
            append("\", \"byteCount\": ")
            append(source.source.payload.size)
            append(", \"sha256\": \"")
            append(source.result.sourceSha256)
            append("\"}")
            if (index != ordered.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private fun sanitizeSource(source: AntennaPatternPairSource): AntennaPatternPairSource {
        if (source.payload.isEmpty() || source.payload.size > AntennaPatternCodecLimits.MAX_INPUT_BYTES) {
            throw AntennaPatternCodecException(
                "Each paired antenna source must contain between 1 byte and 16 MiB.",
            )
        }
        val candidateName = source.displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .filterNot(Char::isISOControl)
            .map { character ->
                if (character.isPortableSourceFileNameCharacter()) character else '_'
            }
            .joinToString(separator = "")
            .take(MAX_SOURCE_NAME_CHARACTERS)
            .trimEnd(' ', '.')
            .ifBlank { "antenna-pattern.dat" }
        val portableStem = candidateName.substringBefore('.').uppercase(Locale.ROOT)
        val safeName = if (portableStem in WINDOWS_RESERVED_FILE_STEMS) {
            "_$candidateName".take(MAX_SOURCE_NAME_CHARACTERS).trimEnd(' ', '.')
        } else {
            candidateName
        }
        return source.copy(displayName = safeName, payload = source.payload.copyOf())
    }

    private fun mergeCompatibleValue(
        label: String,
        horizontalValue: Double?,
        verticalValue: Double?,
        absoluteTolerance: Double,
        relativeTolerance: Double,
        units: String,
        warnings: MutableList<String>,
    ): Double? {
        if (horizontalValue == null) return verticalValue
        if (verticalValue == null) return horizontalValue
        val tolerance = max(
            absoluteTolerance,
            max(abs(horizontalValue), abs(verticalValue)) * relativeTolerance,
        )
        val difference = abs(horizontalValue - verticalValue)
        if (difference > tolerance) {
            throw AntennaPatternCodecException(
                "The HRP and VRP $label values conflict: $horizontalValue $units versus " +
                    "$verticalValue $units.",
            )
        }
        if (difference > 0.0) {
            warnings +=
                "The HRP and VRP $label values differ by $difference $units within the declared " +
                    "pairing tolerance; the HRP value was retained."
        }
        return horizontalValue
    }

    private fun pairedPatternName(horizontalName: String, verticalName: String): String {
        val horizontalStem = horizontalName.substringBeforeLast('.', horizontalName).trim()
        val verticalStem = verticalName.substringBeforeLast('.', verticalName).trim()
        return if (horizontalStem.equals(verticalStem, ignoreCase = true)) {
            horizontalStem.take(MAX_PATTERN_NAME_CHARACTERS)
        } else {
            "${horizontalStem.take(76)} + ${verticalStem.take(76)}"
                .take(MAX_PATTERN_NAME_CHARACTERS)
        }.ifBlank { "Paired HRP and VRP" }
    }

    private fun String.safeFileStem(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "paired-antenna-pattern" }
        .take(80)

    private fun String.jsonEscaped(): String = buildString(length) {
        for (character in this@jsonEscaped) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun java.io.InputStream.readBytesBounded(maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maximumBytes) {
                throw AntennaPatternCodecException("An antenna source bundle entry exceeds the limit.")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private data class DecodedPairSource(
        val source: AntennaPatternPairSource,
        val result: AntennaPatternImportResult,
    )

    private data class DeterministicZipEntry(
        val nameBytes: ByteArray,
        val payloadSize: Int,
        val crc32: Long,
        val localOffset: Long,
    )

    private const val REQUIRED_SOURCE_COUNT = 2
    const val MAX_PAIR_SOURCE_BYTES = 15L * 1024L * 1024L
    private const val MAX_SOURCE_NAME_CHARACTERS = 160
    private const val MAX_PATTERN_NAME_CHARACTERS = 160
    private const val MAX_PROVENANCE_TEXT_CHARACTERS = 240
    private const val MAX_PAIR_WARNINGS = 100
    private const val MAX_BUNDLE_ENTRIES = 3
    private const val ZIP_LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
    private const val ZIP_CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
    private const val ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
    private const val ZIP_VERSION_NEEDED = 20
    private const val ZIP_UTF8_FLAG = 0x0800
    private const val ZIP_STORED_METHOD = 0
    private const val ZIP_FIXED_DOS_TIME = 0
    private const val ZIP_FIXED_DOS_DATE = 0x0021

    private val WINDOWS_RESERVED_FILE_STEMS = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        (1..9).forEach { index ->
            add("COM$index")
            add("LPT$index")
        }
    }
    private const val PAIR_SOURCE_WARNING =
        "The immutable source artifact is a deterministic ZIP containing both original cut files " +
            "and a hash manifest."
}

private fun Char.isPortableSourceFileNameCharacter(): Boolean =
    this in 'a'..'z' ||
        this in 'A'..'Z' ||
        this in '0'..'9' ||
        this == ' ' ||
        this == '.' ||
        this == '_' ||
        this == '-'
