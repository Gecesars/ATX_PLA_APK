package com.gecesars.atxplan.data.anatel

import android.content.Context
import com.gecesars.atxplan.data.dataset.AllowlistedHttpsRegionalHttpTransport
import com.gecesars.atxplan.data.dataset.RegionalHttpRequest
import com.gecesars.atxplan.data.dataset.RegionalHttpRequestMethod
import com.gecesars.atxplan.data.dataset.RegionalHttpTransport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalog
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogSnapshot
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogStatus
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQueryPage
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRefreshResult
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanSourceDescriptor
import com.gecesars.atxplan.domain.anatel.OfficialAnatelBasicPlanSource
import java.io.File
import java.io.IOException

class AnatelBasicPlanCatalogException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Android storage/query core for the official TV/FM Basic Plan catalog.
 *
 * Refresh is caller-driven and blocking. It never opens or mutates a project. A verified raw ZIP
 * is retained immutably even when parsing or indexing later fails, while the prior current pointer
 * remains authoritative until a closed staged SQLite index has been validated.
 */
class AndroidAnatelBasicPlanCatalog(
    rootDirectory: File,
    private val source: AnatelBasicPlanSourceDescriptor = OfficialAnatelBasicPlanSource.descriptor,
    private val transport: RegionalHttpTransport = AllowlistedHttpsRegionalHttpTransport(
        allowedHosts = source.allowedHosts,
    ),
    parser: AnatelBasicPlanArchiveParser = AnatelBasicPlanArchiveParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AnatelBasicPlanCatalog {
    private val layout = AnatelBasicPlanCatalogLayout(rootDirectory)
    private val rawStore = ImmutableAnatelRawArchiveStore(layout)
    private val indexStore = AnatelBasicPlanSqliteIndexStore(layout, parser)
    private val pointerStore = AtomicAnatelCurrentPointerStore(layout)

    constructor(
        context: Context,
        source: AnatelBasicPlanSourceDescriptor = OfficialAnatelBasicPlanSource.descriptor,
    ) : this(
        rootDirectory = File(context.applicationContext.filesDir, DEFAULT_CATALOG_DIRECTORY),
        source = source,
        transport = AllowlistedHttpsRegionalHttpTransport(allowedHosts = source.allowedHosts),
    )

    override fun refresh(): AnatelBasicPlanRefreshResult = synchronized(PROCESS_LOCK) {
        try {
            val response = transport.execute(
                RegionalHttpRequest(
                    url = source.archiveUrl,
                    method = RegionalHttpRequestMethod.GET,
                ),
            )
            val raw = response.use { opened ->
                if (opened.statusCode != HTTP_OK) {
                    throw AnatelBasicPlanCatalogException(
                        "The Anatel provider returned HTTP ${opened.statusCode}; no catalog data was changed.",
                    )
                }
                rawStore.store(
                    input = opened.body,
                    declaredContentLength = opened.contentLength,
                ) { sha256, byteCount ->
                    AnatelBasicPlanArchiveProvenance(
                        source = source,
                        acquiredAtEpochMillis = checkedClock("acquisition"),
                        archiveSha256 = sha256,
                        archiveByteCount = byteCount,
                        effectiveArchiveUrl = opened.finalUrl,
                        etag = opened.etag,
                        lastModified = opened.lastModified,
                    )
                }
            }
            val index = indexStore.buildOrReuse(raw)
            val indexedAt = checkedClock("indexing")
            val pointer = AnatelCurrentPointer(
                archiveSha256 = raw.provenance.archiveSha256,
                rawArchiveFileName = raw.file.name,
                rawProvenanceFileName = raw.provenanceFile.name,
                indexFileName = index.file.name,
                indexedAtEpochMillis = indexedAt,
            )
            // This is the sole visibility switch. AtomicFile preserves the prior valid pointer if
            // writing fails, so staged/new artifacts cannot become a partial current catalog.
            pointerStore.write(pointer)
            AnatelBasicPlanRefreshResult(
                snapshot = AnatelBasicPlanCatalogSnapshot(
                    report = index.report,
                    rawArchiveArtifactName = raw.file.name,
                    indexArtifactName = index.file.name,
                    indexedAtEpochMillis = indexedAt,
                ),
                reusedRawArchive = raw.reused,
                reusedIndex = index.reused,
            )
        } catch (error: AnatelBasicPlanCatalogException) {
            throw error
        } catch (error: Exception) {
            throw AnatelBasicPlanCatalogException(
                "The on-demand Anatel catalog refresh failed; the prior current catalog was preserved.",
                error,
            )
        }
    }

    override fun status(): AnatelBasicPlanCatalogStatus = synchronized(PROCESS_LOCK) {
        loadStatus()
    }

    override fun query(query: AnatelBasicPlanQuery): AnatelBasicPlanQueryPage = synchronized(PROCESS_LOCK) {
        val status = loadStatus()
        val snapshot = status.snapshot
        if (snapshot == null) {
            return@synchronized AnatelBasicPlanQueryPage(
                status = status,
                records = emptyList(),
                offset = query.offset,
                pageSize = query.pageSize,
                hasMore = false,
            )
        }
        val pointer = (pointerStore.read() as? AnatelCurrentPointerRead.Present)?.pointer
            ?: return@synchronized noDataPage(query, AnatelBasicPlanNoDataReason.CURRENT_POINTER_INVALID)
        val indexFile = File(layout.indexDirectory, pointer.indexFileName)
        val result = try {
            indexStore.query(indexFile, snapshot, query)
        } catch (_: AnatelBasicPlanIndexException) {
            return@synchronized noDataPage(query, AnatelBasicPlanNoDataReason.INDEX_INCOMPATIBLE)
        }
        AnatelBasicPlanQueryPage(
            status = status,
            records = result.records,
            offset = query.offset,
            pageSize = query.pageSize,
            hasMore = result.hasMore,
        )
    }

    private fun loadStatus(): AnatelBasicPlanCatalogStatus {
        val pointer = when (val read = pointerStore.read()) {
            AnatelCurrentPointerRead.Missing -> return AnatelBasicPlanCatalogStatus.noData(
                AnatelBasicPlanNoDataReason.NOT_ACQUIRED,
            )

            AnatelCurrentPointerRead.Invalid -> return AnatelBasicPlanCatalogStatus.noData(
                AnatelBasicPlanNoDataReason.CURRENT_POINTER_INVALID,
            )

            is AnatelCurrentPointerRead.Present -> read.pointer
        }
        val raw = rawStore.load(pointer) ?: return AnatelBasicPlanCatalogStatus.noData(
            AnatelBasicPlanNoDataReason.RAW_ARCHIVE_UNAVAILABLE,
        )
        val indexFile = File(layout.indexDirectory, pointer.indexFileName)
        if (!indexFile.isFile) {
            return AnatelBasicPlanCatalogStatus.noData(AnatelBasicPlanNoDataReason.INDEX_UNAVAILABLE)
        }
        val report = try {
            indexStore.readReport(indexFile, raw.provenance)
        } catch (_: AnatelBasicPlanIndexException) {
            return AnatelBasicPlanCatalogStatus.noData(AnatelBasicPlanNoDataReason.INDEX_INCOMPATIBLE)
        }
        return AnatelBasicPlanCatalogStatus.ready(
            AnatelBasicPlanCatalogSnapshot(
                report = report,
                rawArchiveArtifactName = raw.file.name,
                indexArtifactName = indexFile.name,
                indexedAtEpochMillis = pointer.indexedAtEpochMillis,
            ),
        )
    }

    private fun noDataPage(
        query: AnatelBasicPlanQuery,
        reason: AnatelBasicPlanNoDataReason,
    ) = AnatelBasicPlanQueryPage(
        status = AnatelBasicPlanCatalogStatus.noData(reason),
        records = emptyList(),
        offset = query.offset,
        pageSize = query.pageSize,
        hasMore = false,
    )

    private fun checkedClock(operation: String): Long = clock().also { value ->
        if (value < 0L) {
            throw AnatelBasicPlanCatalogException(
                "The Anatel $operation clock returned an invalid timestamp.",
            )
        }
    }

    private companion object {
        /** One monitor protects the shared catalog directory across every repository instance. */
        val PROCESS_LOCK = Any()
        const val HTTP_OK = 200
        const val DEFAULT_CATALOG_DIRECTORY = "catalogs/anatel-basic-plan-v1"
    }
}
