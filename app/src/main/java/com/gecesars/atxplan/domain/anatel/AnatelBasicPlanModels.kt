package com.gecesars.atxplan.domain.anatel

import java.net.URI
import java.util.Locale

object AnatelBasicPlanLimits {
    const val MAX_TEXT_CHARS = 16_384
    const val MAX_WARNING_COUNT = 64
    const val MAX_ARCHIVE_ENTRIES = 32
    const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    const val MAX_ENTRY_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    const val MAX_COMPRESSION_RATIO = 100.0
    const val MAX_SOURCE_ROWS = 1_000_000L
    const val MAX_XML_ATTRIBUTES = 128
    const val MAX_ZIP_ENTRY_NAME_CHARS = 256
}

enum class AnatelLicenseReviewStatus {
    REVIEW_REQUIRED,
    VERIFIED,
}

/** License evidence is explicit; this model does not infer redistribution rights from public access. */
data class AnatelDatasetLicense(
    val identifier: String,
    val title: String,
    val termsUrl: String,
    val attribution: String,
    val reviewStatus: AnatelLicenseReviewStatus,
) {
    init {
        requireBoundedText(identifier, "An Anatel license identifier")
        requireBoundedText(title, "An Anatel license title")
        requireHttpsUrl(termsUrl, "An Anatel license terms URL")
        requireBoundedText(attribution, "An Anatel license attribution")
    }
}

enum class AnatelBasicPlanOrigin(
    val officialArchiveEntryName: String,
) {
    BASIC_PLAN("plano_basicoTVFM.xml"),
    SECONDARY_CHANNELS("secudariosTVFM.xml"),
    REQUESTS("solicitacoesTVFM.xml"),
}

data class AnatelBasicPlanArchiveEntry(
    val name: String,
    val origin: AnatelBasicPlanOrigin,
) {
    init {
        require(name == origin.officialArchiveEntryName) {
            "An Anatel archive entry name must match its declared origin."
        }
    }
}

data class AnatelBasicPlanSourceDescriptor(
    val datasetId: String,
    val title: String,
    val provider: String,
    val landingPageUrl: String,
    val archiveUrl: String,
    val allowedHosts: Set<String>,
    val archiveEntries: List<AnatelBasicPlanArchiveEntry>,
    val license: AnatelDatasetLicense,
) {
    init {
        requireBoundedText(datasetId, "An Anatel dataset ID")
        requireBoundedText(title, "An Anatel dataset title")
        requireBoundedText(provider, "An Anatel dataset provider")
        requireHttpsUrl(landingPageUrl, "An Anatel dataset landing-page URL")
        requireHttpsUrl(archiveUrl, "An Anatel dataset archive URL")
        require(allowedHosts.isNotEmpty() && allowedHosts.size <= 8) {
            "An Anatel source must declare a bounded host allowlist."
        }
        allowedHosts.forEach { host ->
            require(host.isNotBlank() && host == host.lowercase() && '/' !in host) {
                "An Anatel source host allowlist entry is invalid."
            }
        }
        listOf(landingPageUrl, archiveUrl).forEach { url ->
            val host = URI(url).host?.lowercase()
            require(host in allowedHosts) { "An Anatel source URL host is not allowlisted." }
        }
        require(archiveEntries.isNotEmpty() && archiveEntries.size <= 8) {
            "An Anatel source must declare a bounded archive-entry allowlist."
        }
        require(archiveEntries.map(AnatelBasicPlanArchiveEntry::name).distinct().size == archiveEntries.size) {
            "An Anatel source contains duplicate archive-entry declarations."
        }
        require(
            archiveEntries.map(AnatelBasicPlanArchiveEntry::origin).toSet() ==
                AnatelBasicPlanOrigin.entries.toSet(),
        ) { "An Anatel source must declare every supported TV/FM archive origin." }
    }
}

object OfficialAnatelBasicPlanSource {
    const val ARCHIVE_URL = "https://sistemas.anatel.gov.br/se/public/file/b/srd/Canais.zip"
    const val LANDING_PAGE_URL = "https://sistemas.anatel.gov.br/se/public/view/b/srd.php"

    val descriptor = AnatelBasicPlanSourceDescriptor(
        datasetId = "anatel-basic-plan-tv-fm",
        title = "Anatel Basic Plan TV/FM channels",
        provider = "Agência Nacional de Telecomunicações (Anatel)",
        landingPageUrl = LANDING_PAGE_URL,
        archiveUrl = ARCHIVE_URL,
        allowedHosts = setOf("sistemas.anatel.gov.br"),
        archiveEntries = AnatelBasicPlanOrigin.entries.map { origin ->
            AnatelBasicPlanArchiveEntry(origin.officialArchiveEntryName, origin)
        },
        license = AnatelDatasetLicense(
            identifier = "anatel-source-terms-review-required",
            title = "Anatel source terms",
            termsUrl = LANDING_PAGE_URL,
            attribution = "Source: Agência Nacional de Telecomunicações (Anatel).",
            reviewStatus = AnatelLicenseReviewStatus.REVIEW_REQUIRED,
        ),
    )
}

data class AnatelBasicPlanArchiveProvenance(
    val source: AnatelBasicPlanSourceDescriptor = OfficialAnatelBasicPlanSource.descriptor,
    val acquiredAtEpochMillis: Long,
    val archiveSha256: String,
    val archiveByteCount: Long,
    val effectiveArchiveUrl: String = source.archiveUrl,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    init {
        require(acquiredAtEpochMillis >= 0L) { "An Anatel acquisition timestamp cannot be negative." }
        require(archiveSha256.matches(Regex("[0-9a-f]{64}"))) {
            "An Anatel archive SHA-256 must contain 64 lowercase hexadecimal characters."
        }
        require(archiveByteCount in 1..AnatelBasicPlanLimits.MAX_ARCHIVE_BYTES) {
            "An Anatel archive byte count is outside the supported bound."
        }
        requireHttpsUrl(effectiveArchiveUrl, "An effective Anatel archive URL")
        require(URI(effectiveArchiveUrl).host?.lowercase() in source.allowedHosts) {
            "The effective Anatel archive URL host is not allowlisted."
        }
        etag?.let { requireBoundedText(it, "An Anatel archive ETag") }
        lastModified?.let { requireBoundedText(it, "An Anatel archive Last-Modified value") }
    }
}

enum class AnatelBroadcastService {
    FM,
    TELEVISION,
    UNKNOWN,
}

enum class AnatelStatusPresence {
    DECLARED,
    NOT_DECLARED,
}

/** The source status code is preserved without assigning an unsupported regulatory meaning. */
data class AnatelBasicPlanStatus(
    val rawCode: String,
) {
    val normalizedCode: String? = rawCode.trim().uppercase(Locale.ROOT).ifEmpty { null }
    val presence: AnatelStatusPresence = if (normalizedCode == null) {
        AnatelStatusPresence.NOT_DECLARED
    } else {
        AnatelStatusPresence.DECLARED
    }

    init {
        requireBoundedRawText(rawCode, "An Anatel raw status code")
    }
}

enum class AnatelFrequencyOrigin {
    SOURCE_ATTRIBUTE,
    CHANNEL_FALLBACK,
    NO_DATA,
}

data class AnatelResolvedFrequency(
    val frequencyMHz: Double?,
    val origin: AnatelFrequencyOrigin,
    val sourceFrequencyRaw: String,
    val explanation: String,
) {
    init {
        require(
            frequencyMHz == null || frequencyMHz.isFinite() && frequencyMHz > 0.0,
        ) { "An Anatel frequency must be positive and finite when available." }
        require((origin == AnatelFrequencyOrigin.NO_DATA) == (frequencyMHz == null)) {
            "Anatel frequency availability and origin are inconsistent."
        }
        requireBoundedRawText(sourceFrequencyRaw, "An Anatel raw frequency")
        requireBoundedText(explanation, "An Anatel frequency explanation")
    }
}

data class AnatelBasicPlanRecordProvenance(
    val archive: AnatelBasicPlanArchiveProvenance,
    val entryName: String,
    val origin: AnatelBasicPlanOrigin,
    val generationDate: String?,
    val sourceRowNumber: Long,
) {
    init {
        require(entryName == origin.officialArchiveEntryName) {
            "An Anatel record entry does not match its origin."
        }
        require(generationDate == null || isIsoCalendarDate(generationDate)) {
            "An Anatel generation date must use YYYY-MM-DD."
        }
        require(sourceRowNumber > 0L) { "An Anatel source row number must be positive." }
    }
}

data class AnatelBasicPlanRecord(
    val sourceRowId: String?,
    val basicPlanId: String?,
    val itemNumber: Long?,
    val origin: AnatelBasicPlanOrigin,
    val service: AnatelBroadcastService,
    val rawService: String,
    val status: AnatelBasicPlanStatus,
    val channelRaw: String,
    val channel: Int?,
    val frequency: AnatelResolvedFrequency,
    val countryCode: String?,
    val stateCode: String?,
    val ibgeMunicipalityCode: String?,
    val municipalityName: String?,
    val channelOffsetRaw: String,
    val stationClassRaw: String,
    val characterRaw: String,
    val purposeRaw: String,
    val entityName: String?,
    val cnpjRaw: String,
    val stationCategoryRaw: String,
    val latitudeDegrees: Double?,
    val longitudeDegrees: Double?,
    val erpKw: Double?,
    val antennaHeightMeters: Double?,
    val antennaLimitationsRaw: String,
    val antennaPatternDbdRaw: String,
    val observationsRaw: String,
    val fistelRaw: String,
    val generatorFistelRaw: String,
    val dicRaw: String,
    val provenance: AnatelBasicPlanRecordProvenance,
) {
    init {
        sourceRowId?.let { requireBoundedText(it, "An Anatel source row ID") }
        basicPlanId?.let { requireBoundedText(it, "An Anatel Basic Plan ID") }
        require(itemNumber == null || itemNumber > 0L) { "An Anatel item number must be positive." }
        require(origin == provenance.origin) { "An Anatel record origin is inconsistent." }
        requireBoundedRawText(rawService, "An Anatel raw service")
        requireBoundedRawText(channelRaw, "An Anatel raw channel")
        require(channel == null || channel in 1..999) { "An Anatel channel is outside the supported bound." }
        listOf(countryCode, stateCode, ibgeMunicipalityCode, municipalityName, entityName)
            .filterNotNull()
            .forEach { value -> requireBoundedText(value, "An Anatel record text field") }
        listOf(
            channelOffsetRaw,
            stationClassRaw,
            characterRaw,
            purposeRaw,
            cnpjRaw,
            stationCategoryRaw,
            antennaLimitationsRaw,
            antennaPatternDbdRaw,
            observationsRaw,
            fistelRaw,
            generatorFistelRaw,
            dicRaw,
        ).forEach { value -> requireBoundedRawText(value, "An Anatel raw record field") }
        require(latitudeDegrees == null || latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0) {
            "An Anatel latitude must be finite and in [-90, 90] degrees."
        }
        require(longitudeDegrees == null || longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) {
            "An Anatel longitude must be finite and in [-180, 180] degrees."
        }
        require(erpKw == null || erpKw.isFinite() && erpKw >= 0.0) {
            "An Anatel ERP must be finite and non-negative in kilowatts."
        }
        require(antennaHeightMeters == null || antennaHeightMeters.isFinite() && antennaHeightMeters >= 0.0) {
            "An Anatel antenna height must be finite and non-negative in metres."
        }
    }
}

enum class AnatelImportWarningCode {
    IGNORED_ARCHIVE_ENTRY,
    MIXED_GENERATION_DATES,
    NORMALIZED_UNSAFE_SOURCE_TEXT,
    INVALID_GENERATION_DATE,
    UNKNOWN_SERVICE,
    INVALID_SOURCE_FREQUENCY,
    FREQUENCY_NO_DATA,
    INVALID_CHANNEL,
    INVALID_NUMERIC_FIELD,
    MISSING_SOURCE_ROW_ID,
}

data class AnatelImportWarning(
    val code: AnatelImportWarningCode,
    val message: String,
    val occurrenceCount: Long,
) {
    init {
        requireBoundedText(message, "An Anatel import warning")
        require(occurrenceCount > 0L) { "An Anatel warning occurrence count must be positive." }
    }
}

data class AnatelBasicPlanEntryReport(
    val entryName: String,
    val origin: AnatelBasicPlanOrigin,
    val generationDateRaw: String,
    val generationDate: String?,
    val sourceRowCount: Long,
    val emittedRecordCount: Long,
) {
    init {
        require(entryName == origin.officialArchiveEntryName) {
            "An Anatel entry report does not match its origin."
        }
        requireBoundedRawText(generationDateRaw, "An Anatel raw generation date")
        require(generationDate == null || isIsoCalendarDate(generationDate)) {
            "An Anatel entry generation date must use YYYY-MM-DD."
        }
        require(sourceRowCount >= 0L && emittedRecordCount in 0..sourceRowCount) {
            "An Anatel entry report contains inconsistent record counts."
        }
    }
}

data class AnatelBasicPlanImportReport(
    val provenance: AnatelBasicPlanArchiveProvenance,
    val verifiedArchiveSha256: String,
    val verifiedArchiveByteCount: Long,
    val archiveEntryCount: Int,
    val ignoredArchiveEntryCount: Int,
    val entryReports: List<AnatelBasicPlanEntryReport>,
    val sourceRowCount: Long,
    val emittedRecordCount: Long,
    val latestGenerationDate: String?,
    val frequencyOriginCounts: Map<AnatelFrequencyOrigin, Long>,
    val warnings: List<AnatelImportWarning>,
) {
    init {
        require(verifiedArchiveSha256.matches(Regex("[0-9a-f]{64}"))) {
            "A verified Anatel archive hash is invalid."
        }
        require(verifiedArchiveByteCount > 0L && archiveEntryCount >= 0 && ignoredArchiveEntryCount >= 0) {
            "An Anatel import report contains invalid archive counts."
        }
        require(sourceRowCount >= 0L && emittedRecordCount in 0..sourceRowCount) {
            "An Anatel import report contains inconsistent record counts."
        }
        require(latestGenerationDate == null || isIsoCalendarDate(latestGenerationDate)) {
            "An Anatel latest generation date must use YYYY-MM-DD."
        }
        require(warnings.size <= AnatelBasicPlanLimits.MAX_WARNING_COUNT) {
            "An Anatel import report contains too many warning categories."
        }
    }
}

internal fun requireBoundedText(
    value: String,
    label: String,
) {
    require(value.isNotBlank() && value.length <= AnatelBasicPlanLimits.MAX_TEXT_CHARS) {
        "$label must be non-blank and no longer than ${AnatelBasicPlanLimits.MAX_TEXT_CHARS} characters."
    }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters." }
}

internal fun requireBoundedRawText(
    value: String,
    label: String,
) {
    require(value.length <= AnatelBasicPlanLimits.MAX_TEXT_CHARS) {
        "$label exceeds the ${AnatelBasicPlanLimits.MAX_TEXT_CHARS}-character bound."
    }
    require(value.none { character -> character.isISOControl() && character != '\t' }) {
        "$label contains an unsupported control character."
    }
}

internal fun requireHttpsUrl(
    value: String,
    label: String,
) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "$label must be an absolute HTTPS URL."
    }
}

internal fun isIsoCalendarDate(value: String): Boolean {
    val match = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(value) ?: return false
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    if (year !in 1900..2999 || month !in 1..12) return false
    val leap = year % 400 == 0 || year % 4 == 0 && year % 100 != 0
    val daysInMonth = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..daysInMonth
}
