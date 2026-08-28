package com.gecesars.atxplan.data.anatel

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gecesars.atxplan.data.dataset.RegionalHttpRequest
import com.gecesars.atxplan.data.dataset.RegionalHttpRequestMethod
import com.gecesars.atxplan.data.dataset.RegionalHttpResponse
import com.gecesars.atxplan.data.dataset.RegionalHttpTransport
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanCatalogAvailability
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanNoDataReason
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanQuery
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyRangeMHz
import com.gecesars.atxplan.domain.anatel.OfficialAnatelBasicPlanSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AndroidAnatelBasicPlanCatalogTest {
    @Test
    fun xmlFixtureDeclarationStartsAtByteZeroOnAndroid() {
        val payload = xml("<row/>", date = "2026-08-20")

        assertEquals("<?xml", payload.copyOfRange(0, 5).toString(Charsets.US_ASCII))
    }

    @Test
    fun onDemandRefreshPreservesRawProvenanceAndSupportsReducingQueries() = withRepository { root, transport, repository ->
        val initial = repository.status()
        assertEquals(AnatelBasicPlanCatalogAvailability.NO_DATA, initial.availability)
        assertEquals(AnatelBasicPlanNoDataReason.NOT_ACQUIRED, initial.noDataReason)

        val first = repository.refresh()

        assertFalse(first.reusedRawArchive)
        assertFalse(first.reusedIndex)
        assertEquals(4L, first.snapshot.report.emittedRecordCount)
        assertEquals(4L, first.snapshot.report.sourceRowCount)
        assertTrue(File(root, "raw/${first.snapshot.rawArchiveArtifactName}").isFile)
        assertTrue(File(root, "indexes/${first.snapshot.indexArtifactName}").isFile)

        val exactFm = repository.query(
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                stateCode = "sp",
                municipality = "Sao Paulo",
                channel = 258,
                frequencyMHz = AnatelFrequencyRangeMHz(99.55, 99.55),
                text = "example broadcaster",
                basicPlanId = "pb-1001",
                pageSize = 10,
            ),
        )
        assertEquals(AnatelBasicPlanCatalogAvailability.READY, exactFm.status.availability)
        assertEquals(listOf("pb-fm-source"), exactFm.records.map { record -> record.sourceRowId })
        assertEquals(
            first.snapshot.report.provenance.archiveSha256,
            exactFm.records.single().provenance.archive.archiveSha256,
        )

        val tvByMunicipalityCode = repository.query(
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.TELEVISION,
                municipality = "3304557",
                pageSize = 10,
            ),
        )
        assertEquals(listOf("pb-tv"), tvByMunicipalityCode.records.map { record -> record.sourceRowId })

        val firstFmPage = repository.query(
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                pageSize = 1,
            ),
        )
        assertTrue(firstFmPage.hasMore)
        assertEquals(1, firstFmPage.nextOffset)
        val secondFmPage = repository.query(
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.FM,
                pageSize = 1,
                offset = requireNotNull(firstFmPage.nextOffset),
            ),
        )
        assertFalse(secondFmPage.hasMore)
        assertNotEquals(firstFmPage.records.single().sourceRowId, secondFmPage.records.single().sourceRowId)

        val noMatches = repository.query(
            AnatelBasicPlanQuery(
                service = AnatelBroadcastService.TELEVISION,
                frequencyMHz = AnatelFrequencyRangeMHz(900.0, 901.0),
            ),
        )
        assertEquals(AnatelBasicPlanCatalogAvailability.READY, noMatches.status.availability)
        assertTrue(noMatches.records.isEmpty())

        val firstAcquisition = first.snapshot.report.provenance.acquiredAtEpochMillis
        val repeated = repository.refresh()
        assertTrue(repeated.reusedRawArchive)
        assertTrue(repeated.reusedIndex)
        assertEquals(firstAcquisition, repeated.snapshot.report.provenance.acquiredAtEpochMillis)
        assertEquals(2, transport.requestCount)
        assertEquals(1, root.rawArchives().size)
        assertEquals(1, root.indexes().size)
    }

    @Test
    fun failedStagedParseRetainsNewRawBytesButKeepsPriorCurrentIndex() = withRepository { root, transport, repository ->
        val first = repository.refresh()
        val originalHash = first.snapshot.report.provenance.archiveSha256
        transport.archive = archive(includeRequests = false, changedEntity = "Changed source")

        expectFailure<AnatelBasicPlanCatalogException> { repository.refresh() }

        val status = repository.status()
        assertEquals(AnatelBasicPlanCatalogAvailability.READY, status.availability)
        assertEquals(originalHash, status.snapshot!!.report.provenance.archiveSha256)
        assertEquals(2, root.rawArchives().size)
        assertEquals(1, root.indexes().size)
        assertTrue(
            root.resolve("indexes").listFiles().orEmpty().none { file ->
                file.name.startsWith(".staging-") || file.name.endsWith("-journal")
            },
        )
    }

    @Test
    fun currentRawArchiveIsRehashedWhenCatalogIsReopened() = withRepository { root, _, repository ->
        val first = repository.refresh()
        val raw = File(root, "raw/${first.snapshot.rawArchiveArtifactName}")
        RandomAccessFile(raw, "rw").use { file ->
            val firstByte = file.read()
            assertTrue(firstByte >= 0)
            file.seek(0L)
            file.write(firstByte xor 0x01)
        }

        val status = repository.status()

        assertEquals(AnatelBasicPlanCatalogAvailability.NO_DATA, status.availability)
        assertEquals(AnatelBasicPlanNoDataReason.RAW_ARCHIVE_UNAVAILABLE, status.noDataReason)
    }

    @Test
    fun missingMiddleIndexRowFailsFullReopenCountValidation() = withRepository { root, _, repository ->
        val first = repository.refresh()
        val index = File(root, "indexes/${first.snapshot.indexArtifactName}")
        val database = SQLiteDatabase.openDatabase(index.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            assertEquals(1, database.delete("records", "row_id = ?", arrayOf("2")))
        } finally {
            database.close()
        }

        val status = repository.status()

        assertEquals(AnatelBasicPlanCatalogAvailability.NO_DATA, status.availability)
        assertEquals(AnatelBasicPlanNoDataReason.INDEX_INCOMPATIBLE, status.noDataReason)
    }

    @Test
    fun catalogMonitorSerializesDifferentRepositoryInstancesInThisProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "anatel-catalog-lock-test-${UUID.randomUUID()}")
        val blockingTransport = BlockingArchiveTransport(archive())
        val first = AndroidAnatelBasicPlanCatalog(rootDirectory = root, transport = blockingTransport)
        val second = AndroidAnatelBasicPlanCatalog(
            rootDirectory = root,
            transport = MutableArchiveTransport(archive()),
        )
        val refreshFailure = AtomicReference<Throwable?>(null)
        val statusFailure = AtomicReference<Throwable?>(null)
        val statusFinished = CountDownLatch(1)
        var refreshThread: Thread? = null
        var statusThread: Thread? = null
        try {
            val startedRefresh = thread(name = "anatel-refresh-lock-test") {
                try {
                    first.refresh()
                } catch (error: Throwable) {
                    refreshFailure.set(error)
                }
            }
            refreshThread = startedRefresh
            assertTrue(blockingTransport.started.await(5, TimeUnit.SECONDS))
            val startedStatus = thread(name = "anatel-status-lock-test") {
                try {
                    second.status()
                } catch (error: Throwable) {
                    statusFailure.set(error)
                } finally {
                    statusFinished.countDown()
                }
            }
            statusThread = startedStatus

            assertFalse(statusFinished.await(1, TimeUnit.SECONDS))
            blockingTransport.release.countDown()
            startedRefresh.join(10_000L)
            startedStatus.join(10_000L)

            assertFalse(startedRefresh.isAlive)
            assertFalse(startedStatus.isAlive)
            assertNull(refreshFailure.get())
            assertNull(statusFailure.get())
        } finally {
            blockingTransport.release.countDown()
            refreshThread?.join(10_000L)
            statusThread?.join(10_000L)
            root.deleteRecursively()
        }
    }

    private fun withRepository(
        block: (File, MutableArchiveTransport, AndroidAnatelBasicPlanCatalog) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "anatel-catalog-test-${UUID.randomUUID()}")
        val transport = MutableArchiveTransport(archive())
        val ticks = AtomicLong(1_800_000_000_000L)
        val repository = AndroidAnatelBasicPlanCatalog(
            rootDirectory = root,
            transport = transport,
            clock = { ticks.getAndIncrement() },
        )
        try {
            block(root, transport, repository)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archive(
        includeRequests: Boolean = true,
        changedEntity: String = "Example applicant",
    ): ByteArray {
        val entries = mutableListOf(
            "plano_basicoTVFM.xml" to xml(
                """
                <row item="1" id="pb-fm-source" IdtPlanoBasico="PB-1001" Pais="BR" UF="SP" CodMunicipio="3550308" Municipio="S${'\u00e3'}o Paulo" Canal="258" Frequencia="99.55" Servico="FM" Status="ACTIVE_RAW" Entidade="Example Broadcaster" ERP="10.5" Altura="120" Observacoes="Primary FM record"/>
                <row item="2" id="pb-tv" IdtPlanoBasico="PB-1002" Pais="BR" UF="RJ" CodMunicipio="3304557" Municipio="Rio de Janeiro" Canal="14" Frequencia="" Servico="TV" Status="TV_RAW" Entidade="Example Television" ERP="25" Altura="80"/>
                """.trimIndent(),
                date = "2026-08-20",
            ),
            "secudariosTVFM.xml" to xml(
                """
                <row item="3" id="secondary-tv" IdtPlanoBasico="PB-2001" Pais="BR" UF="MG" CodMunicipio="3106200" Municipio="Belo Horizonte" Canal="22" Frequencia="521" Servico="RTV" Status="SECONDARY_RAW" Entidade="Example Relay"/>
                """.trimIndent(),
                date = "2026-08-20",
            ),
        )
        if (includeRequests) {
            entries += "solicitacoesTVFM.xml" to xml(
                """
                <row item="4" id="request-fm" IdtPlanoBasico="PB-3001" Pais="BR" UF="RS" CodMunicipio="4314902" Municipio="Porto Alegre" Canal="201" Frequencia="" Servico="FM" Status="REQUEST_RAW" Entidade="$changedEntity"/>
                """.trimIndent(),
                date = "2026-08-21",
            )
        }
        return zip(entries)
    }

    private fun xml(rows: String, date: String): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<plano_basico data_geracao=\"").append(date).append("\">\n")
        append(rows).append('\n')
        append("</plano_basico>")
    }.toByteArray(Charsets.UTF_8)

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            entries.forEach { (name, payload) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(payload)
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun File.rawArchives(): List<File> = resolve("raw").listFiles().orEmpty()
        .filter { file -> file.name.matches(Regex("canais-[0-9a-f]{64}\\.zip")) }

    private fun File.indexes(): List<File> = resolve("indexes").listFiles().orEmpty()
        .filter { file -> file.name.matches(Regex("basic-plan-[0-9a-f]{64}-v1\\.sqlite")) }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError(
                "Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}.",
                error,
            )
        }
        throw AssertionError("Expected ${T::class.java.simpleName}.")
    }
}

private class MutableArchiveTransport(
    var archive: ByteArray,
) : RegionalHttpTransport {
    var requestCount: Int = 0
        private set

    override fun execute(request: RegionalHttpRequest): RegionalHttpResponse {
        check(request.method == RegionalHttpRequestMethod.GET)
        check(request.url == OfficialAnatelBasicPlanSource.ARCHIVE_URL)
        requestCount += 1
        val responseBytes = archive.copyOf()
        return RegionalHttpResponse(
            statusCode = 200,
            finalUrl = OfficialAnatelBasicPlanSource.ARCHIVE_URL,
            contentLength = responseBytes.size.toLong(),
            contentRange = null,
            etag = "fixture-etag-$requestCount",
            lastModified = "Fri, 28 Aug 2026 12:00:00 GMT",
            body = ByteArrayInputStream(responseBytes),
            closeAction = {},
        )
    }
}

private class BlockingArchiveTransport(
    private val archive: ByteArray,
) : RegionalHttpTransport {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun execute(request: RegionalHttpRequest): RegionalHttpResponse {
        check(request.method == RegionalHttpRequestMethod.GET)
        started.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "The catalog lock test did not release its download." }
        val responseBytes = archive.copyOf()
        return RegionalHttpResponse(
            statusCode = 200,
            finalUrl = OfficialAnatelBasicPlanSource.ARCHIVE_URL,
            contentLength = responseBytes.size.toLong(),
            contentRange = null,
            etag = "blocking-fixture-etag",
            lastModified = "Fri, 28 Aug 2026 12:00:00 GMT",
            body = ByteArrayInputStream(responseBytes),
            closeAction = {},
        )
    }
}
