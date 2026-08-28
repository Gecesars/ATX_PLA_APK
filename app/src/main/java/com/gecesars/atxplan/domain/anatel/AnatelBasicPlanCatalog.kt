package com.gecesars.atxplan.domain.anatel

object AnatelBasicPlanCatalogLimits {
    const val DEFAULT_PAGE_SIZE = 50
    const val MAX_PAGE_SIZE = 200
    const val MAX_PAGE_OFFSET = 1_000_000
    const val MAX_FILTER_TEXT_CHARS = 256
}

data class AnatelFrequencyRangeMHz(
    val minimum: Double,
    val maximum: Double,
) {
    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum > 0.0 && maximum >= minimum) {
            "An Anatel frequency filter requires a finite positive increasing range."
        }
        require(maximum <= 1_000_000.0) {
            "An Anatel frequency filter exceeds the supported range."
        }
    }
}

/**
 * A bounded read-only catalog query. Service is mandatory so a request cannot accidentally scan
 * every supported broadcast record. Every other field only reduces that service partition.
 */
data class AnatelBasicPlanQuery(
    val service: AnatelBroadcastService,
    val stateCode: String? = null,
    /** Exact municipality name or exact IBGE municipality code. */
    val municipality: String? = null,
    val channel: Int? = null,
    val frequencyMHz: AnatelFrequencyRangeMHz? = null,
    /** Accent-insensitive substring search across bounded descriptive catalog fields. */
    val text: String? = null,
    val basicPlanId: String? = null,
    val pageSize: Int = AnatelBasicPlanCatalogLimits.DEFAULT_PAGE_SIZE,
    val offset: Int = 0,
) {
    init {
        require(service != AnatelBroadcastService.UNKNOWN) {
            "An Anatel catalog query requires FM or television service."
        }
        stateCode?.let { value ->
            require(value.trim().matches(Regex("[A-Za-z]{2}"))) {
                "An Anatel state filter must contain a two-letter code."
            }
        }
        municipality?.let { requireQueryText(it, "An Anatel municipality filter") }
        require(channel == null || channel in 1..999) {
            "An Anatel channel filter is outside the supported range."
        }
        text?.let { value ->
            requireQueryText(value, "An Anatel text filter")
            require(value.trim().length >= 2) {
                "An Anatel text filter must contain at least two characters."
            }
        }
        basicPlanId?.let { requireQueryText(it, "An Anatel Basic Plan ID filter") }
        require(pageSize in 1..AnatelBasicPlanCatalogLimits.MAX_PAGE_SIZE) {
            "An Anatel catalog page size is outside the supported bound."
        }
        require(offset in 0..AnatelBasicPlanCatalogLimits.MAX_PAGE_OFFSET) {
            "An Anatel catalog page offset is outside the supported bound."
        }
    }
}

enum class AnatelBasicPlanCatalogAvailability {
    NO_DATA,
    READY,
}

enum class AnatelBasicPlanNoDataReason {
    NOT_ACQUIRED,
    CURRENT_POINTER_INVALID,
    RAW_ARCHIVE_UNAVAILABLE,
    INDEX_UNAVAILABLE,
    INDEX_INCOMPATIBLE,
}

data class AnatelBasicPlanCatalogSnapshot(
    val report: AnatelBasicPlanImportReport,
    val rawArchiveArtifactName: String,
    val indexArtifactName: String,
    val indexedAtEpochMillis: Long,
) {
    init {
        requireArtifactName(rawArchiveArtifactName, "An Anatel raw archive artifact name")
        requireArtifactName(indexArtifactName, "An Anatel index artifact name")
        require(indexedAtEpochMillis >= 0L) {
            "An Anatel catalog indexing timestamp cannot be negative."
        }
    }
}

data class AnatelBasicPlanCatalogStatus(
    val availability: AnatelBasicPlanCatalogAvailability,
    val snapshot: AnatelBasicPlanCatalogSnapshot? = null,
    val noDataReason: AnatelBasicPlanNoDataReason? = null,
) {
    init {
        require(
            availability == AnatelBasicPlanCatalogAvailability.READY &&
                snapshot != null &&
                noDataReason == null ||
                availability == AnatelBasicPlanCatalogAvailability.NO_DATA &&
                snapshot == null &&
                noDataReason != null,
        ) { "An Anatel catalog status must explicitly describe ready data or NoData." }
    }

    companion object {
        fun ready(snapshot: AnatelBasicPlanCatalogSnapshot) = AnatelBasicPlanCatalogStatus(
            availability = AnatelBasicPlanCatalogAvailability.READY,
            snapshot = snapshot,
        )

        fun noData(reason: AnatelBasicPlanNoDataReason) = AnatelBasicPlanCatalogStatus(
            availability = AnatelBasicPlanCatalogAvailability.NO_DATA,
            noDataReason = reason,
        )
    }
}

data class AnatelBasicPlanQueryPage(
    val status: AnatelBasicPlanCatalogStatus,
    val records: List<AnatelBasicPlanRecord>,
    val offset: Int,
    val pageSize: Int,
    val hasMore: Boolean,
) {
    init {
        require(offset in 0..AnatelBasicPlanCatalogLimits.MAX_PAGE_OFFSET) {
            "An Anatel result offset is outside the supported bound."
        }
        require(pageSize in 1..AnatelBasicPlanCatalogLimits.MAX_PAGE_SIZE) {
            "An Anatel result page size is outside the supported bound."
        }
        require(records.size <= pageSize) { "An Anatel result exceeds its declared page size." }
        require(
            status.availability == AnatelBasicPlanCatalogAvailability.READY ||
                records.isEmpty() && !hasMore,
        ) { "An Anatel NoData result cannot contain catalog records." }
        require(!hasMore || records.size == pageSize) {
            "An Anatel result can continue only after a full page."
        }
        require(
            !hasMore || offset <= AnatelBasicPlanCatalogLimits.MAX_PAGE_OFFSET - records.size,
        ) { "An Anatel result continuation would exceed the supported page offset." }
    }

    val nextOffset: Int?
        get() = if (hasMore) offset + records.size else null
}

data class AnatelBasicPlanRefreshResult(
    val snapshot: AnatelBasicPlanCatalogSnapshot,
    val reusedRawArchive: Boolean,
    val reusedIndex: Boolean,
)

/** Blocking storage boundary. Call [refresh] on an IO dispatcher. */
interface AnatelBasicPlanCatalog {
    /** Downloads and indexes only when explicitly invoked by the caller. */
    fun refresh(): AnatelBasicPlanRefreshResult

    fun status(): AnatelBasicPlanCatalogStatus

    fun query(query: AnatelBasicPlanQuery): AnatelBasicPlanQueryPage
}

private fun requireQueryText(
    value: String,
    label: String,
) {
    require(value.isNotBlank() && value.length <= AnatelBasicPlanCatalogLimits.MAX_FILTER_TEXT_CHARS) {
        "$label must be non-blank and bounded."
    }
    require(value.none(Char::isISOControl)) { "$label cannot contain control characters." }
    require(value.any(Char::isLetterOrDigit)) { "$label must contain a letter or digit." }
}

private fun requireArtifactName(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value.length <= 160 &&
            value.none(Char::isISOControl) &&
            '/' !in value &&
            '\\' !in value &&
            value !in setOf(".", ".."),
    ) { "$label is invalid." }
}
