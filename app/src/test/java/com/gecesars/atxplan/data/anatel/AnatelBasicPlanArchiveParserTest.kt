package com.gecesars.atxplan.data.anatel

import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanArchiveProvenance
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanOrigin
import com.gecesars.atxplan.domain.anatel.AnatelBasicPlanRecord
import com.gecesars.atxplan.domain.anatel.AnatelBroadcastService
import com.gecesars.atxplan.domain.anatel.AnatelFrequencyOrigin
import com.gecesars.atxplan.domain.anatel.AnatelImportWarningCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AnatelBasicPlanArchiveParserTest {
    @Test
    fun streamsAllowlistedTvFmRecordsInBoundedBatchesWithVerifiedProvenance() {
        val archive = fixtureArchive(
            "plano_basicoAM.xml" to "<ignored>AM data is outside the TV/FM allowlist.</ignored>".toByteArray(),
        )
        val batches = mutableListOf<List<AnatelBasicPlanRecord>>()

        val report = AnatelBasicPlanArchiveParser().streamBatches(
            input = ByteArrayInputStream(archive),
            provenance = provenanceFor(archive),
            batchSize = 2,
            onBatch = batches::add,
        )

        assertEquals(listOf(2, 2), batches.map { batch -> batch.size })
        val records = batches.flatten()
        assertEquals(4, records.size)
        val exactFm = records.single { record -> record.sourceRowId == "pb-fm-source" }
        assertEquals(AnatelBroadcastService.FM, exactFm.service)
        assertEquals(99.55, exactFm.frequency.frequencyMHz!!, 0.0)
        assertEquals(AnatelFrequencyOrigin.SOURCE_ATTRIBUTE, exactFm.frequency.origin)
        assertEquals("PB_ACTIVE_RAW", exactFm.status.rawCode)
        assertEquals("az=90|erp=5.0", exactFm.antennaLimitationsRaw)
        assertEquals("0|0|-3|-6", exactFm.antennaPatternDbdRaw)

        val fallbackTv = records.single { record -> record.sourceRowId == "pb-tv-fallback" }
        assertEquals(473.0, fallbackTv.frequency.frequencyMHz!!, 0.0)
        assertEquals(AnatelFrequencyOrigin.CHANNEL_FALLBACK, fallbackTv.frequency.origin)
        val request = records.single { record -> record.origin == AnatelBasicPlanOrigin.REQUESTS }
        assertEquals(88.1, request.frequency.frequencyMHz!!, 1.0e-9)
        assertEquals("REQUEST_RAW_UNMAPPED", request.status.rawCode)
        assertEquals("keep this exact raw antenna limitation", request.antennaLimitationsRaw)
        assertEquals("keep this exact raw antenna pattern", request.antennaPatternDbdRaw)
        assertEquals("solicitacoesTVFM.xml", request.provenance.entryName)
        assertEquals("2026-07-21", request.provenance.generationDate)

        assertEquals(4L, report.sourceRowCount)
        assertEquals(4L, report.emittedRecordCount)
        assertEquals(4, report.archiveEntryCount)
        assertEquals(1, report.ignoredArchiveEntryCount)
        assertEquals("2026-07-21", report.latestGenerationDate)
        assertEquals(2L, report.frequencyOriginCounts.getValue(AnatelFrequencyOrigin.SOURCE_ATTRIBUTE))
        assertEquals(2L, report.frequencyOriginCounts.getValue(AnatelFrequencyOrigin.CHANNEL_FALLBACK))
        assertEquals(0L, report.frequencyOriginCounts.getValue(AnatelFrequencyOrigin.NO_DATA))
        assertEquals(archive.size.toLong(), report.verifiedArchiveByteCount)
        assertEquals(sha256(archive), report.verifiedArchiveSha256)
        assertEquals(3, report.entryReports.size)
        assertEquals(
            setOf(
                AnatelImportWarningCode.IGNORED_ARCHIVE_ENTRY,
                AnatelImportWarningCode.MIXED_GENERATION_DATES,
                AnatelImportWarningCode.INVALID_SOURCE_FREQUENCY,
            ),
            report.warnings.map { warning -> warning.code }.toSet(),
        )
    }

    @Test
    fun normalizesUnsafeDerivedTextWithoutChangingRawArchiveEvidence() {
        val archive = archiveWithBasicRows(
            """
            <row id="normalized-row" Servico="FM" Canal="258" Frequencia="99.5"
                Entidade="Alpha&#x96;Beta" Status="ACTIVE&#x202E;FAKE"
                Observacoes="First&#13;&#10; Second&#9;Third"/>
            """.trimIndent(),
        )
        val originalHash = sha256(archive)
        val records = mutableListOf<AnatelBasicPlanRecord>()

        val report = AnatelBasicPlanArchiveParser().streamRecords(
            ByteArrayInputStream(archive),
            provenanceFor(archive),
            records::add,
        )

        assertEquals(originalHash, sha256(archive))
        val record = records.single()
        assertEquals("Alpha\uFFFDBeta", record.entityName)
        assertEquals("ACTIVE\uFFFDFAKE", record.status.rawCode)
        assertEquals("First Second Third", record.observationsRaw)
        val warning = report.warnings.single { item ->
            item.code == AnatelImportWarningCode.NORMALIZED_UNSAFE_SOURCE_TEXT
        }
        assertEquals(5L, warning.occurrenceCount)
        assertEquals(
            "Unsafe source text code points were normalized only in derived Anatel record attributes.",
            warning.message,
        )
    }

    @Test
    fun classifiesOnlyVersionedOfficialServiceCodesAndEvidenceBackedEcrd() {
        val rows = buildString {
            listOf("FM", "RTRFM").forEachIndexed { index, code ->
                append("<row id=\"fm-$index\" Servico=\"").append(code)
                    .append("\" Canal=\"258\" Frequencia=\"99.5\"/>")
            }
            listOf("TV", "RTV", "RTVD", "PBTVD", "GTVD", "TVA").forEachIndexed { index, code ->
                append("<row id=\"tv-$index\" Servico=\"").append(code)
                    .append("\" Canal=\"14\" Frequencia=\"473\"/>")
            }
            append("<row id=\"ecrd-fm\" Servico=\"ECRD\" Canal=\"234\" Frequencia=\"94.7\"/>")
            append("<row id=\"ecrd-tv\" Servico=\"ECRD\" Canal=\"14\" Frequencia=\"473\"/>")
            append("<row id=\"not-fm\" Servico=\"NOT_FM\" Canal=\"258\" Frequencia=\"\"/>")
            append("<row id=\"not-tv\" Servico=\"NOT_TV\" Canal=\"14\" Frequencia=\"\"/>")
            append("<row id=\"tvd\" Servico=\"TVD\" Canal=\"14\" Frequencia=\"473\"/>")
            append("<row id=\"ecrd-conflict\" Servico=\"ECRD\" Canal=\"234\" Frequencia=\"473\"/>")
            append("<row id=\"om\" Servico=\"OM\" Canal=\"1\" Frequencia=\"1\"/>")
        }
        val archive = archiveWithBasicRows(rows)
        val records = mutableListOf<AnatelBasicPlanRecord>()

        val report = AnatelBasicPlanArchiveParser().streamRecords(
            ByteArrayInputStream(archive),
            provenanceFor(archive),
            records::add,
        )
        val services = records.associate { record -> record.sourceRowId to record.service }

        assertEquals(AnatelBroadcastService.FM, services.getValue("fm-0"))
        assertEquals(AnatelBroadcastService.FM, services.getValue("fm-1"))
        (0 until 6).forEach { index ->
            assertEquals(AnatelBroadcastService.TELEVISION, services.getValue("tv-$index"))
        }
        assertEquals(AnatelBroadcastService.FM, services.getValue("ecrd-fm"))
        assertEquals(AnatelBroadcastService.TELEVISION, services.getValue("ecrd-tv"))
        listOf("not-fm", "not-tv", "tvd", "ecrd-conflict", "om").forEach { id ->
            assertEquals(AnatelBroadcastService.UNKNOWN, services.getValue(id))
        }
        val warning = report.warnings.single { item -> item.code == AnatelImportWarningCode.UNKNOWN_SERVICE }
        assertEquals(5L, warning.occurrenceCount)
        assertTrue(warning.message.contains("service-code rules v1"))
    }

    @Test
    fun rejectsZipTraversalBeforeParsingXml() {
        val archive = zipOf("../plano_basicoTVFM.xml" to safeEmptyXml())

        expectFailure<AnatelBasicPlanSecurityException> {
            parse(archive)
        }
    }

    @Test
    fun rejectsCaseInsensitiveDuplicateEntryNames() {
        val archive = zipOf(
            "plano_basicoTVFM.xml" to safeEmptyXml(),
            "PLANO_BASICOTVFM.XML" to safeEmptyXml(),
        )

        expectFailure<AnatelBasicPlanSecurityException> {
            parse(archive)
        }
    }

    @Test
    fun rejectsExpandedEntryBeyondConfiguredBombBound() {
        val archive = fixtureArchive()
        val parser = AnatelBasicPlanArchiveParser(
            AnatelBasicPlanArchiveParserOptions(
                maxEntryUncompressedBytes = 256,
                maxTotalUncompressedBytes = 4_096,
            ),
        )

        expectFailure<AnatelBasicPlanSecurityException> {
            parser.streamRecords(ByteArrayInputStream(archive), provenanceFor(archive)) {}
        }
    }

    @Test
    fun rejectsDoctypeAndExternalEntityDeclarations() {
        val malicious = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plano_basico [<!ENTITY secret SYSTEM "file:///not-readable">]>
            <plano_basico data_geracao="2026-07-20"><row id="&secret;"/></plano_basico>
        """.trimIndent().toByteArray()
        val archive = zipOf(
            "plano_basicoTVFM.xml" to malicious,
            "secudariosTVFM.xml" to safeEmptyXml(),
            "solicitacoesTVFM.xml" to safeEmptyXml(),
        )

        expectFailure<AnatelBasicPlanSecurityException> {
            parse(archive)
        }
    }

    @Test
    fun rejectsArchiveWhoseBytesDoNotMatchDeclaredProvenance() {
        val archive = fixtureArchive()
        val wrongHash = "0".repeat(64)
        val provenance = provenanceFor(archive).copy(archiveSha256 = wrongHash)

        expectFailure<AnatelBasicPlanSecurityException> {
            AnatelBasicPlanArchiveParser().streamRecords(
                ByteArrayInputStream(archive),
                provenance,
            ) {}
        }
    }

    private fun parse(archive: ByteArray) {
        AnatelBasicPlanArchiveParser().streamRecords(
            input = ByteArrayInputStream(archive),
            provenance = provenanceFor(archive),
            onRecord = {},
        )
    }

    private fun fixtureArchive(vararg additional: Pair<String, ByteArray>): ByteArray = zipOf(
        "plano_basicoTVFM.xml" to fixture("plano_basicoTVFM.xml"),
        "secudariosTVFM.xml" to fixture("secudariosTVFM.xml"),
        "solicitacoesTVFM.xml" to fixture("solicitacoesTVFM.xml"),
        *additional,
    )

    private fun archiveWithBasicRows(rows: String): ByteArray = zipOf(
        "plano_basicoTVFM.xml" to buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<plano_basico data_geracao=\"2026-08-28\">\n")
            append(rows).append('\n')
            append("</plano_basico>")
        }.toByteArray(Charsets.UTF_8),
        "secudariosTVFM.xml" to safeEmptyXml(),
        "solicitacoesTVFM.xml" to safeEmptyXml(),
    )

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/anatel/$name"),
    ) { "Missing Anatel XML fixture: $name" }.use { input -> input.readBytes() }

    private fun safeEmptyXml(): ByteArray =
        "<plano_basico data_geracao=\"2026-07-20\"></plano_basico>".toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun provenanceFor(archive: ByteArray): AnatelBasicPlanArchiveProvenance =
        AnatelBasicPlanArchiveProvenance(
            acquiredAtEpochMillis = 1_787_524_800_000L,
            archiveSha256 = sha256(archive),
            archiveByteCount = archive.size.toLong(),
            etag = "fixture-etag",
            lastModified = "Tue, 28 Jul 2026 12:00:00 GMT",
        )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue(
                "Expected ${T::class.java.simpleName}, but got ${error::class.java.simpleName}",
                error is T,
            )
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName} to be thrown.")
    }
}
