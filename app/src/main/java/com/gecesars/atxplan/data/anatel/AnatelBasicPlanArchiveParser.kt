package com.gecesars.atxplan.data.anatel

import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanEntryReport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanImportReport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanLimits
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecordProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanStatus
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelChannelFrequencyResolver
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.AnatelImportWarning
import com.gecesars.atxplan.domain.anatel.AnatelImportWarningCode
import com.gecesars.atxplan.domain.anatel.isIsoCalendarDate
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.EntityResolver2
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.FilterReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

data class AnatelBasicPlanArchiveParserOptions(
    val maxArchiveEntries: Int = AnatelBasicPlanLimits.MAX_ARCHIVE_ENTRIES,
    val maxArchiveBytes: Long = AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES,
    val maxEntryUncompressedBytes: Long = AnatelBasicPlanLimits.MAX_ENTRY_UNCOMPRESSED_BYTES,
    val maxTotalUncompressedBytes: Long = AnatelBasicPlanLimits.MAX_TOTAL_UNCOMPRESSED_BYTES,
    val maxCompressionRatio: Double = AnatelBasicPlanLimits.MAX_COMPRESSION_RATIO,
    val maxSourceRows: Long = AnatelBasicPlanLimits.MAX_SOURCE_ROWS,
    val maxXmlAttributes: Int = AnatelBasicPlanLimits.MAX_XML_ATTRIBUTES,
    val maxAttributeValueChars: Int = AnatelBasicPlanLimits.MAX_TEXT_CHARS,
    val maxRowAttributeChars: Int = 64 * 1024,
) {
    init {
        require(maxArchiveEntries in 1..1_000) { "The Anatel archive-entry bound is invalid." }
        require(maxArchiveBytes > 0L) { "The Anatel archive-byte bound must be positive." }
        require(maxEntryUncompressedBytes > 0L && maxTotalUncompressedBytes >= maxEntryUncompressedBytes) {
            "The Anatel expanded-byte bounds are inconsistent."
        }
        require(maxCompressionRatio.isFinite() && maxCompressionRatio in 1.0..10_000.0) {
            "The Anatel compression-ratio bound is invalid."
        }
        require(maxSourceRows > 0L) { "The Anatel source-row bound must be positive." }
        require(maxXmlAttributes in 1..1_024) { "The Anatel XML attribute bound is invalid." }
        require(maxAttributeValueChars in 1..AnatelBasicPlanLimits.MAX_TEXT_CHARS) {
            "The Anatel XML attribute-value bound is invalid."
        }
        require(maxRowAttributeChars >= maxAttributeValueChars) {
            "The Anatel XML row-character bound is invalid."
        }
    }
}

open class AnatelBasicPlanParseException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class AnatelBasicPlanSecurityException(
    message: String,
    cause: Throwable? = null,
) : AnatelBasicPlanParseException(message, cause)

class AnatelBasicPlanFormatException(
    message: String,
    cause: Throwable? = null,
) : AnatelBasicPlanParseException(message, cause)

/**
 * Bounded one-pass parser for the official Anatel `Canais.zip` artifact.
 *
 * Records are emitted before the final ZIP hash can be verified in a one-pass stream. A durable
 * consumer must therefore write callbacks into a transaction or staging area and commit only
 * after this method returns its verified report.
 */
class AnatelBasicPlanArchiveParser(
    private val options: AnatelBasicPlanArchiveParserOptions =
        AnatelBasicPlanArchiveParserOptions(),
) {
    fun streamRecords(
        input: InputStream,
        provenance: AnatelBasicPlanArchiveProvenance,
        onRecord: (AnatelBasicPlanRecord) -> Unit,
    ): AnatelBasicPlanImportReport {
        if (provenance.archiveByteCount > options.maxArchiveBytes) {
            throw AnatelBasicPlanSecurityException(
                "The declared Anatel archive size exceeds the configured parser bound.",
            )
        }
        val archiveCounter = BoundedArchiveInputStream(
            delegate = NonClosingInputStream(input),
            maxBytes = options.maxArchiveBytes,
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val digestInput = DigestInputStream(archiveCounter, digest)
        val zipInput = ZipInputStream(digestInput)
        val expandedBudget = ExpandedArchiveBudget(options.maxTotalUncompressedBytes)
        val rowBudget = SourceRowBudget(options.maxSourceRows)
        val warningCollector = WarningCollector()
        val originByName = provenance.source.archiveEntries.associate { entry ->
            entry.name to entry.origin
        }
        val seenEntryNames = linkedSetOf<String>()
        val parsedEntryNames = linkedSetOf<String>()
        val entryReports = mutableListOf<AnatelBasicPlanEntryReport>()
        val frequencyCounts = AnatelFrequencyOrigin.entries.associateWith { 0L }.toMutableMap()
        var archiveEntryCount = 0
        var ignoredEntryCount = 0
        var declaredExpandedBytes = 0L

        try {
            while (true) {
                val entry = try {
                    zipInput.nextEntry
                } catch (error: ZipException) {
                    throw AnatelBasicPlanFormatException("The Anatel ZIP structure is invalid.", error)
                } ?: break
                archiveEntryCount += 1
                if (archiveEntryCount > options.maxArchiveEntries) {
                    throw AnatelBasicPlanSecurityException(
                        "The Anatel archive contains more than ${options.maxArchiveEntries} entries.",
                    )
                }
                val entryName = validateZipEntryName(entry)
                val duplicateKey = entryName.lowercase(Locale.ROOT)
                if (!seenEntryNames.add(duplicateKey)) {
                    throw AnatelBasicPlanSecurityException(
                        "The Anatel archive contains a duplicate entry name: $entryName.",
                    )
                }
                validateDeclaredEntryBounds(entry)
                if (!entry.isDirectory && entry.size >= 0L) {
                    if (declaredExpandedBytes > options.maxTotalUncompressedBytes - entry.size) {
                        throw AnatelBasicPlanSecurityException(
                            "The declared expanded Anatel archive size exceeds the configured bound.",
                        )
                    }
                    declaredExpandedBytes += entry.size
                }

                if (entry.isDirectory) {
                    zipInput.closeEntry()
                    continue
                }
                val entryStream = BoundedZipEntryInputStream(
                    delegate = zipInput,
                    archiveCounter = archiveCounter,
                    expandedBudget = expandedBudget,
                    entryStartArchiveBytes = archiveCounter.bytesRead,
                    maxEntryBytes = options.maxEntryUncompressedBytes,
                    maxCompressionRatio = options.maxCompressionRatio,
                )
                val origin = originByName[entryName]
                if (origin == null) {
                    ignoredEntryCount += 1
                    entryStream.drain()
                } else {
                    if (!parsedEntryNames.add(entryName)) {
                        throw AnatelBasicPlanSecurityException(
                            "The Anatel TV/FM allowlist contains a duplicate parsed entry.",
                        )
                    }
                    entryReports += parseXmlEntry(
                        entryName = entryName,
                        origin = origin,
                        input = entryStream,
                        archiveProvenance = provenance,
                        rowBudget = rowBudget,
                        warningCollector = warningCollector,
                        frequencyCounts = frequencyCounts,
                        onRecord = onRecord,
                    )
                    entryStream.drain()
                }
                validateActualCompressionRatio(entry, entryStream.entryBytes)
                zipInput.closeEntry()
            }

            // ZipInputStream stops at the central directory. Drain the underlying digest stream so
            // the integrity check covers the complete artifact, including its central directory.
            val drainBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (digestInput.read(drainBuffer) >= 0) {
                // The bounded archive stream accounts for every drained byte.
            }
        } catch (error: AnatelBasicPlanParseException) {
            throw error
        } catch (error: ZipException) {
            throw AnatelBasicPlanFormatException("The Anatel ZIP entry could not be decoded.", error)
        } finally {
            runCatching { zipInput.close() }
        }

        val actualArchiveBytes = archiveCounter.bytesRead
        val actualArchiveSha256 = digest.digest().toHex()
        if (actualArchiveBytes != provenance.archiveByteCount) {
            throw AnatelBasicPlanSecurityException(
                "The streamed Anatel archive byte count does not match its provenance.",
            )
        }
        if (actualArchiveSha256 != provenance.archiveSha256) {
            throw AnatelBasicPlanSecurityException(
                "The streamed Anatel archive SHA-256 does not match its provenance.",
            )
        }
        val missingEntries = originByName.keys - parsedEntryNames
        if (missingEntries.isNotEmpty()) {
            throw AnatelBasicPlanFormatException(
                "The Anatel archive is missing required TV/FM entries: ${missingEntries.sorted().joinToString()}.",
            )
        }
        if (ignoredEntryCount > 0) {
            warningCollector.add(
                code = AnatelImportWarningCode.IGNORED_ARCHIVE_ENTRY,
                message = "Non-TV/FM archive entries were ignored after bounded streaming validation.",
                count = ignoredEntryCount.toLong(),
            )
        }
        val generationDates = entryReports.mapNotNull(AnatelBasicPlanEntryReport::generationDate)
            .distinct()
        if (generationDates.size > 1) {
            warningCollector.add(
                code = AnatelImportWarningCode.MIXED_GENERATION_DATES,
                message = "The official TV/FM XML entries declare different generation dates.",
            )
        }
        val sourceRows = entryReports.sumOf(AnatelBasicPlanEntryReport::sourceRowCount)
        val emittedRecords = entryReports.sumOf(AnatelBasicPlanEntryReport::emittedRecordCount)
        return AnatelBasicPlanImportReport(
            provenance = provenance,
            verifiedArchiveSha256 = actualArchiveSha256,
            verifiedArchiveByteCount = actualArchiveBytes,
            archiveEntryCount = archiveEntryCount,
            ignoredArchiveEntryCount = ignoredEntryCount,
            entryReports = entryReports.toList(),
            sourceRowCount = sourceRows,
            emittedRecordCount = emittedRecords,
            latestGenerationDate = generationDates.maxOrNull(),
            frequencyOriginCounts = frequencyCounts.toMap(),
            warnings = warningCollector.toReports(),
        )
    }

    fun streamBatches(
        input: InputStream,
        provenance: AnatelBasicPlanArchiveProvenance,
        batchSize: Int = 500,
        onBatch: (List<AnatelBasicPlanRecord>) -> Unit,
    ): AnatelBasicPlanImportReport {
        require(batchSize in 1..10_000) { "An Anatel callback batch size must be in [1, 10000]." }
        val batch = ArrayList<AnatelBasicPlanRecord>(batchSize)
        val report = streamRecords(input, provenance) { record ->
            batch += record
            if (batch.size == batchSize) {
                onBatch(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) onBatch(batch.toList())
        return report
    }

    private fun parseXmlEntry(
        entryName: String,
        origin: AnatelBasicPlanOrigin,
        input: InputStream,
        archiveProvenance: AnatelBasicPlanArchiveProvenance,
        rowBudget: SourceRowBudget,
        warningCollector: WarningCollector,
        frequencyCounts: MutableMap<AnatelFrequencyOrigin, Long>,
        onRecord: (AnatelBasicPlanRecord) -> Unit,
    ): AnatelBasicPlanEntryReport {
        val handler = BasicPlanXmlHandler(
            entryName = entryName,
            origin = origin,
            archiveProvenance = archiveProvenance,
            options = options,
            rowBudget = rowBudget,
            warningCollector = warningCollector,
            frequencyCounts = frequencyCounts,
            onRecord = onRecord,
        )
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                runCatching { isXIncludeAware = false }
            }
            val reader = factory.newSAXParser().xmlReader
            setSecurityFeature(reader, XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setSecurityFeature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true)
            setSecurityFeature(reader, "http://xml.org/sax/features/external-general-entities", false)
            setSecurityFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false)
            setSecurityFeature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            runCatching {
                reader.setProperty(
                    "http://javax.xml.XMLConstants/property/accessExternalDTD",
                    "",
                )
            }
            runCatching {
                reader.setProperty(
                    "http://javax.xml.XMLConstants/property/accessExternalSchema",
                    "",
                )
            }
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val strictReader = ForbiddenXmlMarkupReader(
                InputStreamReader(NonClosingInputStream(input), decoder),
            )
            reader.parse(
                InputSource(strictReader).apply {
                    encoding = "UTF-8"
                    systemId = "urn:atx-plan:anatel:$entryName"
                },
            )
        } catch (error: AnatelBasicPlanSecurityException) {
            throw error
        } catch (error: SAXException) {
            findCause<AnatelBasicPlanSecurityException>(error)?.let { throw it }
            throw AnatelBasicPlanFormatException(
                "The Anatel XML entry $entryName is malformed or violates the secure XML contract.",
                error,
            )
        } catch (error: ParserConfigurationException) {
            throw AnatelBasicPlanFormatException("A secure SAX parser is unavailable.", error)
        } catch (error: IOException) {
            findCause<AnatelBasicPlanSecurityException>(error)?.let { throw it }
            throw AnatelBasicPlanFormatException(
                "The Anatel XML entry $entryName is not valid bounded UTF-8 XML.",
                error,
            )
        }
        return handler.report()
    }

    private fun validateZipEntryName(entry: ZipEntry): String {
        val name = entry.name
        if (name.isBlank() || name.length > AnatelBasicPlanLimits.MAX_ZIP_ENTRY_NAME_CHARS) {
            throw AnatelBasicPlanSecurityException("The Anatel ZIP contains an invalid entry name.")
        }
        if (
            name.startsWith('/') || name.startsWith('\\') || '\\' in name ||
            name.matches(Regex("^[A-Za-z]:.*")) || name.any(Char::isISOControl)
        ) {
            throw AnatelBasicPlanSecurityException("The Anatel ZIP contains an unsafe entry path: $name.")
        }
        val path = if (entry.isDirectory) name.removeSuffix("/") else name
        val segments = path.split('/')
        if (segments.any { segment -> segment.isEmpty() || segment == "." || segment == ".." }) {
            throw AnatelBasicPlanSecurityException("The Anatel ZIP contains a traversal path: $name.")
        }
        return name
    }

    private fun validateDeclaredEntryBounds(entry: ZipEntry) {
        if (entry.size > options.maxEntryUncompressedBytes) {
            throw AnatelBasicPlanSecurityException(
                "An Anatel ZIP entry exceeds the configured expanded-size bound.",
            )
        }
        if (entry.compressedSize > options.maxArchiveBytes) {
            throw AnatelBasicPlanSecurityException(
                "An Anatel ZIP entry exceeds the configured compressed-size bound.",
            )
        }
        if (entry.size > 0L && entry.compressedSize == 0L) {
            throw AnatelBasicPlanSecurityException("An Anatel ZIP entry declares an invalid compression ratio.")
        }
        if (entry.size > COMPRESSION_RATIO_GRACE_BYTES && entry.compressedSize > 0L) {
            val ratio = entry.size.toDouble() / entry.compressedSize.toDouble()
            if (ratio > options.maxCompressionRatio) {
                throw AnatelBasicPlanSecurityException(
                    "An Anatel ZIP entry exceeds the configured compression-ratio bound.",
                )
            }
        }
    }

    private fun validateActualCompressionRatio(
        entry: ZipEntry,
        expandedBytes: Long,
    ) {
        if (expandedBytes <= COMPRESSION_RATIO_GRACE_BYTES) return
        val compressedBytes = entry.compressedSize
        if (compressedBytes <= 0L || expandedBytes.toDouble() / compressedBytes > options.maxCompressionRatio) {
            throw AnatelBasicPlanSecurityException(
                "An Anatel ZIP entry exceeds the verified compression-ratio bound.",
            )
        }
    }

    private companion object {
        const val COMPRESSION_RATIO_GRACE_BYTES = 1L * 1024L * 1024L
    }
}

private class BasicPlanXmlHandler(
    private val entryName: String,
    private val origin: AnatelBasicPlanOrigin,
    private val archiveProvenance: AnatelBasicPlanArchiveProvenance,
    private val options: AnatelBasicPlanArchiveParserOptions,
    private val rowBudget: SourceRowBudget,
    private val warningCollector: WarningCollector,
    private val frequencyCounts: MutableMap<AnatelFrequencyOrigin, Long>,
    private val onRecord: (AnatelBasicPlanRecord) -> Unit,
) : DefaultHandler(), EntityResolver2 {
    private var depth = 0
    private var rootSeen = false
    private var insideRow = false
    private var generationDateRaw = ""
    private var generationDate: String? = null
    private var sourceRowCount = 0L
    private var emittedRecordCount = 0L

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String,
        attributes: Attributes,
    ) {
        depth += 1
        if (depth == 1) {
            if (rootSeen || qName != "plano_basico") {
                failFormat("The Anatel XML root must be plano_basico.")
            }
            rootSeen = true
            validateAttributes(attributes)
            generationDateRaw = attributes.getValue("data_geracao") ?: ""
            generationDate = generationDateRaw.trim().takeIf(::isIsoCalendarDate)
            if (generationDateRaw.isNotBlank() && generationDate == null) {
                warningCollector.add(
                    code = AnatelImportWarningCode.INVALID_GENERATION_DATE,
                    message = "An Anatel XML entry contains an invalid generation date.",
                )
            }
            return
        }
        if (depth != 2 || qName != "row" || insideRow) {
            failFormat("The Anatel XML entry contains an unexpected nested element.")
        }
        insideRow = true
        validateAttributes(attributes)
        rowBudget.increment()
        sourceRowCount += 1
        val record = recordFrom(attributes)
        onRecord(record)
        emittedRecordCount += 1
    }

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String,
    ) {
        if (depth == 2 && qName == "row") insideRow = false
        depth -= 1
        if (depth < 0) failFormat("The Anatel XML element depth is invalid.")
    }

    override fun characters(
        ch: CharArray,
        start: Int,
        length: Int,
    ) {
        if (insideRow && (start until start + length).any { index -> !ch[index].isWhitespace() }) {
            failFormat("An Anatel row must store its source values as attributes.")
        }
    }

    override fun endDocument() {
        if (!rootSeen || depth != 0 || insideRow) {
            failFormat("The Anatel XML document is incomplete.")
        }
    }

    override fun warning(error: SAXParseException) {
        failFormat("The Anatel XML parser reported a warning at a source location.", error)
    }

    override fun error(error: SAXParseException) {
        failFormat("The Anatel XML parser reported invalid source data.", error)
    }

    override fun fatalError(error: SAXParseException) {
        failFormat("The Anatel XML parser reported a fatal source error.", error)
    }

    override fun getExternalSubset(
        name: String?,
        baseURI: String?,
    ): InputSource? = null

    override fun resolveEntity(
        name: String?,
        publicId: String?,
        baseURI: String?,
        systemId: String?,
    ): InputSource = rejectExternalEntity()

    override fun resolveEntity(
        publicId: String?,
        systemId: String?,
    ): InputSource = rejectExternalEntity()

    fun report(): AnatelBasicPlanEntryReport = AnatelBasicPlanEntryReport(
        entryName = entryName,
        origin = origin,
        generationDateRaw = generationDateRaw,
        generationDate = generationDate,
        sourceRowCount = sourceRowCount,
        emittedRecordCount = emittedRecordCount,
    )

    private fun recordFrom(attributes: Attributes): AnatelBasicPlanRecord {
        val normalizedAttributes = mutableMapOf<String, String>()
        fun raw(name: String): String = normalizedAttributes.getOrPut(name) {
            val normalized = normalizeDerivedRecordAttribute(attributes.getValue(name) ?: "")
            if (normalized.replacementCount > 0L) {
                warningCollector.add(
                    code = AnatelImportWarningCode.NORMALIZED_UNSAFE_SOURCE_TEXT,
                    message = UNSAFE_SOURCE_TEXT_WARNING,
                    count = normalized.replacementCount,
                )
            }
            normalized.value
        }
        fun optional(name: String): String? = raw(name).trim().ifEmpty { null }

        val rawService = raw("Servico")
        val channelRaw = raw("Canal")
        val sourceFrequencyRaw = raw("Frequencia")
        val service = OfficialServiceCodeRulesV1.classify(
            rawService = rawService,
            sourceFrequencyRaw = sourceFrequencyRaw,
            channelRaw = channelRaw,
        )
        if (service == AnatelBroadcastService.UNKNOWN) {
            warningCollector.add(
                code = AnatelImportWarningCode.UNKNOWN_SERVICE,
                message = "An Anatel row is not classified by the official service-code rules v1.",
            )
        }
        val channel = AnatelChannelFrequencyResolver.parseChannel(channelRaw)
        if (channelRaw.isNotBlank() && channel == null) {
            warningCollector.add(
                code = AnatelImportWarningCode.INVALID_CHANNEL,
                message = "An Anatel row contains an invalid integer channel value.",
            )
        }
        val frequency = AnatelChannelFrequencyResolver.resolve(
            service = service,
            sourceFrequencyRaw = sourceFrequencyRaw,
            channelRaw = channelRaw,
        )
        if (sourceFrequencyRaw.isNotBlank() && frequency.origin != AnatelFrequencyOrigin.SOURCE_ATTRIBUTE) {
            warningCollector.add(
                code = AnatelImportWarningCode.INVALID_SOURCE_FREQUENCY,
                message = "An Anatel row contains an invalid source frequency attribute.",
            )
        }
        if (frequency.origin == AnatelFrequencyOrigin.NO_DATA) {
            warningCollector.add(
                code = AnatelImportWarningCode.FREQUENCY_NO_DATA,
                message = "An Anatel row has no usable source frequency or channel fallback.",
            )
        }
        frequencyCounts[frequency.origin] = frequencyCounts.getValue(frequency.origin) + 1L
        val sourceRowId = optional("id")
        if (sourceRowId == null) {
            warningCollector.add(
                code = AnatelImportWarningCode.MISSING_SOURCE_ROW_ID,
                message = "An Anatel row does not declare its source row ID.",
            )
        }
        return try {
            AnatelBasicPlanRecord(
                sourceRowId = sourceRowId,
                basicPlanId = optional("IdtPlanoBasico"),
                itemNumber = parsePositiveLong(raw("item"), "item"),
                origin = origin,
                service = service,
                rawService = rawService,
                status = AnatelBasicPlanStatus(raw("Status")),
                channelRaw = channelRaw,
                channel = channel,
                frequency = frequency,
                countryCode = optional("Pais"),
                stateCode = optional("UF"),
                ibgeMunicipalityCode = optional("CodMunicipio"),
                municipalityName = optional("Municipio"),
                channelOffsetRaw = raw("Decalagem"),
                stationClassRaw = raw("Classe"),
                characterRaw = raw("Carater"),
                purposeRaw = raw("Finalidade"),
                entityName = optional("Entidade"),
                cnpjRaw = raw("CNPJ"),
                stationCategoryRaw = raw("categoriaEstacao"),
                latitudeDegrees = parseBoundedDouble(raw("Latitude"), -90.0, 90.0, "latitude"),
                longitudeDegrees = parseBoundedDouble(raw("Longitude"), -180.0, 180.0, "longitude"),
                erpKw = parseBoundedDouble(raw("ERP"), 0.0, Double.MAX_VALUE, "ERP"),
                antennaHeightMeters = parseBoundedDouble(
                    raw("Altura"),
                    0.0,
                    Double.MAX_VALUE,
                    "antenna height",
                ),
                antennaLimitationsRaw = raw("Limitacoes"),
                antennaPatternDbdRaw = raw("PadraoAntena_dBd"),
                observationsRaw = raw("Observacoes"),
                fistelRaw = raw("Fistel"),
                generatorFistelRaw = raw("FistelGeradora"),
                dicRaw = raw("dic"),
                provenance = AnatelBasicPlanRecordProvenance(
                    archive = archiveProvenance,
                    entryName = entryName,
                    origin = origin,
                    generationDate = generationDate,
                    sourceRowNumber = sourceRowCount,
                ),
            )
        } catch (error: IllegalArgumentException) {
            failFormat("An Anatel row violates the bounded record contract.", error)
        }
    }

    private fun validateAttributes(attributes: Attributes) {
        if (attributes.length > options.maxXmlAttributes) {
            throwSecurity("An Anatel XML element contains too many attributes.")
        }
        var totalCharacters = 0L
        for (index in 0 until attributes.length) {
            val name = attributes.getQName(index)
            val value = attributes.getValue(index)
            if (name.isBlank() || name.length > 128 || name.any(Char::isISOControl)) {
                throwSecurity("An Anatel XML attribute name is invalid.")
            }
            if (value.length > options.maxAttributeValueChars) {
                throwSecurity("An Anatel XML attribute value exceeds the configured bound.")
            }
            totalCharacters += value.length.toLong()
            if (totalCharacters > options.maxRowAttributeChars) {
                throwSecurity("An Anatel XML row exceeds the configured attribute-character bound.")
            }
        }
    }

    private fun parsePositiveLong(
        raw: String,
        label: String,
    ): Long? {
        if (raw.isBlank()) return null
        return raw.trim().toLongOrNull()?.takeIf { value -> value > 0L } ?: run {
            warningCollector.add(
                code = AnatelImportWarningCode.INVALID_NUMERIC_FIELD,
                message = "An Anatel row contains an invalid $label value.",
            )
            null
        }
    }

    private fun parseBoundedDouble(
        raw: String,
        minimum: Double,
        maximum: Double,
        label: String,
    ): Double? {
        if (raw.isBlank()) return null
        return raw.trim().toDoubleOrNull()
            ?.takeIf { value -> value.isFinite() && value in minimum..maximum }
            ?: run {
                warningCollector.add(
                    code = AnatelImportWarningCode.INVALID_NUMERIC_FIELD,
                    message = "An Anatel row contains an invalid $label value.",
                )
                null
            }
    }

    private fun rejectExternalEntity(): InputSource = throw SAXException(
        "External entities are forbidden in Anatel XML input.",
        AnatelBasicPlanSecurityException("External entities are forbidden in Anatel XML input."),
    )

    private fun throwSecurity(message: String): Nothing = throw SAXException(
        message,
        AnatelBasicPlanSecurityException(message),
    )

    private fun failFormat(
        message: String,
        cause: Exception? = null,
    ): Nothing = if (cause == null) {
        throw SAXException(message)
    } else {
        throw SAXException(message, cause)
    }
}

private object OfficialServiceCodeRulesV1 {
    private val fmCodes = setOf("FM", "RTRFM")
    private val televisionCodes = setOf("TV", "RTV", "RTVD", "PBTVD", "GTVD", "TVA")

    fun classify(
        rawService: String,
        sourceFrequencyRaw: String,
        channelRaw: String,
    ): AnatelBroadcastService = when (rawService.trim().uppercase(Locale.ROOT)) {
        in fmCodes -> AnatelBroadcastService.FM
        in televisionCodes -> AnatelBroadcastService.TELEVISION
        "ECRD" -> AnatelChannelFrequencyResolver.serviceFromExactChannelEvidence(
            sourceFrequencyRaw = sourceFrequencyRaw,
            channelRaw = channelRaw,
        )

        else -> AnatelBroadcastService.UNKNOWN
    }
}

private data class DerivedAttributeNormalization(
    val value: String,
    val replacementCount: Long,
)

/** Raw ZIP bytes remain untouched; only bounded record values emitted by the parser are normalized. */
private fun normalizeDerivedRecordAttribute(source: String): DerivedAttributeNormalization {
    if (source.isEmpty()) return DerivedAttributeNormalization(source, replacementCount = 0L)
    val normalized = StringBuilder(source.length)
    var replacementCount = 0L
    var compactingUnsafeWhitespace = false
    var offset = 0
    while (offset < source.length) {
        val codePoint = source.codePointAt(offset)
        val isUnsafe = Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.FORMAT.toInt()
        if (isUnsafe) {
            replacementCount += 1L
            if (Character.isWhitespace(codePoint) || codePoint == NEXT_LINE_CODE_POINT) {
                if (normalized.isEmpty() || normalized.last() != ASCII_SPACE) normalized.append(ASCII_SPACE)
                compactingUnsafeWhitespace = true
            } else {
                normalized.append(REPLACEMENT_CHARACTER)
                compactingUnsafeWhitespace = false
            }
        } else if (
            compactingUnsafeWhitespace &&
            codePoint == ASCII_SPACE.code &&
            normalized.isNotEmpty() &&
            normalized.last() == ASCII_SPACE
        ) {
            // Keep one ASCII separator for the unsafe whitespace run and its adjacent source space.
        } else {
            normalized.appendCodePoint(codePoint)
            if (codePoint != ASCII_SPACE.code) compactingUnsafeWhitespace = false
        }
        offset += Character.charCount(codePoint)
    }
    return DerivedAttributeNormalization(normalized.toString(), replacementCount)
}

private class WarningCollector {
    private val counts = linkedMapOf<Pair<AnatelImportWarningCode, String>, Long>()

    fun add(
        code: AnatelImportWarningCode,
        message: String,
        count: Long = 1L,
    ) {
        val key = code to message
        val previous = counts[key] ?: 0L
        if (
            count !in 1..AnatelBasicPlanLimits.MAX_TOTAL_UNCOMPRESSED_BYTES ||
            previous > AnatelBasicPlanLimits.MAX_TOTAL_UNCOMPRESSED_BYTES - count
        ) {
            throw AnatelBasicPlanSecurityException("An Anatel import warning count exceeds its bound.")
        }
        counts[key] = previous + count
        if (counts.size > AnatelBasicPlanLimits.MAX_WARNING_COUNT) {
            throw AnatelBasicPlanSecurityException(
                "The Anatel import produced too many distinct warning categories.",
            )
        }
    }

    fun toReports(): List<AnatelImportWarning> = counts.map { (key, count) ->
        AnatelImportWarning(key.first, key.second, count)
    }
}

private const val UNSAFE_SOURCE_TEXT_WARNING =
    "Unsafe source text code points were normalized only in derived Anatel record attributes."
private const val NEXT_LINE_CODE_POINT = 0x85
private const val ASCII_SPACE = ' '
private const val REPLACEMENT_CHARACTER = '\uFFFD'

private class SourceRowBudget(
    private val maximum: Long,
) {
    var count: Long = 0L
        private set

    fun increment() {
        count += 1L
        if (count > maximum) {
            throw AnatelBasicPlanSecurityException(
                "The Anatel XML source-row count exceeds the configured bound.",
            )
        }
    }
}

private class ExpandedArchiveBudget(
    private val maximum: Long,
) {
    var bytesRead: Long = 0L
        private set

    fun add(count: Int) {
        if (count <= 0) return
        if (bytesRead > maximum - count) {
            throw AnatelBasicPlanSecurityException(
                "The expanded Anatel archive exceeds the configured total-size bound.",
            )
        }
        bytesRead += count
    }
}

private class BoundedArchiveInputStream(
    delegate: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(delegate) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) account(count)
        return count
    }

    private fun account(count: Int) {
        if (bytesRead > maxBytes - count) {
            throw AnatelBasicPlanSecurityException(
                "The streamed Anatel archive exceeds the configured compressed-size bound.",
            )
        }
        bytesRead += count
    }
}

private class BoundedZipEntryInputStream(
    delegate: InputStream,
    private val archiveCounter: BoundedArchiveInputStream,
    private val expandedBudget: ExpandedArchiveBudget,
    private val entryStartArchiveBytes: Long,
    private val maxEntryBytes: Long,
    private val maxCompressionRatio: Double,
) : FilterInputStream(delegate) {
    var entryBytes: Long = 0L
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) account(count)
        return count
    }

    override fun close() {
        // The ZIP stream owns the underlying archive; an XML reader cannot close it.
    }

    fun drain() {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (read(buffer) >= 0) {
            // Bounds are enforced by read().
        }
    }

    private fun account(count: Int) {
        if (entryBytes > maxEntryBytes - count) {
            throw AnatelBasicPlanSecurityException(
                "An expanded Anatel ZIP entry exceeds the configured size bound.",
            )
        }
        entryBytes += count
        expandedBudget.add(count)
        if (entryBytes > COMPRESSION_RATIO_GRACE_BYTES) {
            val compressedBytes = maxOf(1L, archiveCounter.bytesRead - entryStartArchiveBytes)
            if (entryBytes.toDouble() / compressedBytes.toDouble() > maxCompressionRatio) {
                throw AnatelBasicPlanSecurityException(
                    "An Anatel ZIP entry exceeds the progressive compression-ratio bound.",
                )
            }
        }
    }

    private companion object {
        const val COMPRESSION_RATIO_GRACE_BYTES = 1L * 1024L * 1024L
    }
}

private class NonClosingInputStream(
    delegate: InputStream,
) : FilterInputStream(delegate) {
    override fun close() {
        // Ownership remains with the caller.
    }
}

private class ForbiddenXmlMarkupReader(
    delegate: Reader,
) : FilterReader(delegate) {
    private var doctypeMatch = 0
    private var entityMatch = 0

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) inspect(value.toChar())
        return value
    }

    override fun read(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) {
            for (index in offset until offset + count) inspect(buffer[index])
        }
        return count
    }

    override fun close() {
        // The archive parser owns the entry stream.
    }

    private fun inspect(character: Char) {
        val uppercase = character.uppercaseChar()
        doctypeMatch = advance(FORBIDDEN_DOCTYPE, doctypeMatch, uppercase)
        entityMatch = advance(FORBIDDEN_ENTITY, entityMatch, uppercase)
        if (doctypeMatch == FORBIDDEN_DOCTYPE.length || entityMatch == FORBIDDEN_ENTITY.length) {
            throw AnatelBasicPlanSecurityException(
                "DOCTYPE and entity declarations are forbidden in Anatel XML input.",
            )
        }
    }

    private fun advance(
        token: String,
        matched: Int,
        character: Char,
    ): Int = when {
        character == token[matched] -> matched + 1
        character == token[0] -> 1
        else -> 0
    }

    private companion object {
        const val FORBIDDEN_DOCTYPE = "<!DOCTYPE"
        const val FORBIDDEN_ENTITY = "<!ENTITY"
    }
}

private fun setSecurityFeature(
    reader: org.xml.sax.XMLReader,
    feature: String,
    enabled: Boolean,
) {
    try {
        reader.setFeature(feature, enabled)
    } catch (_: SAXNotRecognizedException) {
        // The streaming token guard and rejecting entity resolver remain authoritative.
    } catch (_: SAXNotSupportedException) {
        // The streaming token guard and rejecting entity resolver remain authoritative.
    }
}

private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
    var current: Throwable? = error
    repeat(16) {
        if (current is T) return current as T
        current = current?.cause
    }
    return null
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    val value = byte.toInt() and 0xff
    HEX[value ushr 4].toString() + HEX[value and 0x0f]
}

private const val HEX = "0123456789abcdef"
