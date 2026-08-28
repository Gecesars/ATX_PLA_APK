package com.gecesars.atxplan.domain.dataset

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalInventoryEntryFingerprintTest {
    @Test
    fun `version 1 canonical payload and SHA-256 match the golden contract`() {
        val record = fixtureRecord()

        assertEquals(GOLDEN_CANONICAL_JSON, RegionalInventoryEntryFingerprint.canonicalJson(record))
        assertEquals(GOLDEN_SHA256, RegionalInventoryEntryFingerprint.calculate(record))
        assertTrue(RegionalInventoryEntryFingerprint.matches(record, GOLDEN_SHA256))
        assertFalse(RegionalInventoryEntryFingerprint.matches(record, GOLDEN_SHA256.uppercase(Locale.ROOT)))
        assertFalse(RegionalInventoryEntryFingerprint.matches(record, "not-a-sha256"))
    }

    @Test
    fun `canonical payload and digest are independent of the process locale`() {
        val record = fixtureRecord()
        val expectedJson = RegionalInventoryEntryFingerprint.canonicalJson(record)
        val expectedSha256 = RegionalInventoryEntryFingerprint.calculate(record)
        val originalLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(expectedJson, RegionalInventoryEntryFingerprint.canonicalJson(record))
            assertEquals(expectedSha256, RegionalInventoryEntryFingerprint.calculate(record))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `top-level and nested record mutations change the digest`() {
        val record = fixtureRecord()
        val baseline = RegionalInventoryEntryFingerprint.calculate(record)
        val mutations = listOf(
            record.copy(relativePath = "land-cover/fixture/N03W047.tif"),
            record.copy(requestedUrl = "https://example.test/data/N03W047.tif"),
            record.copy(effectiveUrl = null),
            record.copy(acquiredAt = "2026-08-27T14:15:17.000Z"),
            record.copy(status = RegionalTransferStatus.EXISTING),
            record.copy(bytes = record.bytes?.plus(1L)),
            record.copy(sha256 = "f".repeat(64)),
            record.copy(etag = "\"fixture-etag-2\""),
            record.copy(lastModified = "Thu, 28 Aug 2026 14:15:16 GMT"),
            record.copy(checkedAt = "2026-08-27T14:16:01.000Z"),
            record.copy(bounds = record.bounds.copy(east = -47.5)),
            record.copy(processingState = RegionalProcessingState.PROCESSING),
            record.copy(processedOutput = record.processedOutput?.copy(recordCount = 8L)),
            record.copy(notes = "A changed inventory note."),
            record.copy(error = "A bounded fixture problem."),
            record.copy(
                sourceSnapshot = record.sourceSnapshot.copy(
                    provenance = "Changed fixture provenance.",
                ),
            ),
            record.copy(
                sourceSnapshot = record.sourceSnapshot.copy(
                    license = record.sourceSnapshot.license.copy(
                        attribution = "Changed fixture attribution.",
                    ),
                ),
            ),
        )

        mutations.forEach { mutation ->
            assertNotEquals(baseline, RegionalInventoryEntryFingerprint.calculate(mutation))
            assertFalse(RegionalInventoryEntryFingerprint.matches(mutation, baseline))
        }
    }

    private fun fixtureRecord(): RegionalInventoryRecord {
        val sourceSnapshot = RegionalSourceSnapshot(
            datasetId = "fixture-worldcover-2021",
            datasetFamily = "esa-worldcover",
            datasetRelease = "2021",
            catalogRevision = 1,
            title = "Fixture WorldCover",
            provider = "Fixture Provider",
            dataType = RegionalDataType.LAND_COVER,
            fileFormat = RegionalFileFormat.COG_GEOTIFF,
            version = "1.0",
            sourceCrs = "EPSG:4326",
            nominalResolutionMeters = 10.0,
            sourceUrl = "https://example.test/datasets/worldcover",
            license = RegionalDatasetLicense(
                id = "cc-by-4-0",
                title = "Creative Commons Attribution 4.0",
                url = "https://example.test/licenses/cc-by-4-0",
                attribution = "Fixture Provider 2021.",
                acceptanceRequired = true,
            ),
            provenance = "Fixture source provenance.",
            limitations = "Fixture categorical data; not an RF-loss model.",
            queryVersion = "tile-v1",
            normalizerVersion = "cog-v1",
            routeId = "fixture-route",
            routePolicyVersion = 1,
            snapshotPolicy = RegionalSnapshotPolicy.IMMUTABLE_RELEASE,
            maximumCacheAgeMillis = null,
        )
        return RegionalInventoryRecord(
            datasetId = sourceSnapshot.datasetId,
            relativePath = "land-cover/fixture/N03W048.tif",
            requestedUrl = "https://example.test/data/N03W048.tif",
            effectiveUrl = "https://example.test/data/N03W048.tif?mirror=1",
            routeId = sourceSnapshot.routeId,
            routePolicyVersion = sourceSnapshot.routePolicyVersion,
            acquiredAt = "2026-08-27T14:15:16.000Z",
            sourceSnapshot = sourceSnapshot,
            status = RegionalTransferStatus.READY,
            bytes = 1_234_567L,
            sha256 = "0123456789abcdef".repeat(4),
            etag = "\"fixture-etag\"",
            lastModified = "Wed, 27 Aug 2026 14:15:16 GMT",
            checkedAt = "2026-08-27T14:16:00.000Z",
            bounds = RegionalBounds(
                west = -48.25,
                south = -3.5,
                east = -47.75,
                north = -3.0,
            ),
            processingState = RegionalProcessingState.READY,
            processedOutput = RegionalProcessedOutput(
                relativePath = "indexes/N03W048.metadata.json",
                format = "application/json",
                bytes = 321L,
                sha256 = "abcdef0123456789".repeat(4),
                recordCount = 7L,
                notes = "Fixture metadata index.",
            ),
            notes = "Verified fixture.",
            error = null,
        )
    }

    private companion object {
        const val GOLDEN_CANONICAL_JSON = """{"fingerprintVersion":1,"record":{"acquiredAt":"2026-08-27T14:15:16.000Z","bounds":{"east":-47.75,"north":-3.0,"south":-3.5,"west":-48.25},"bytes":1234567,"checkedAt":"2026-08-27T14:16:00.000Z","datasetId":"fixture-worldcover-2021","effectiveUrl":"https://example.test/data/N03W048.tif?mirror=1","error":null,"etag":"\"fixture-etag\"","lastModified":"Wed, 27 Aug 2026 14:15:16 GMT","notes":"Verified fixture.","processedOutput":{"bytes":321,"format":"application/json","notes":"Fixture metadata index.","recordCount":7,"relativePath":"indexes/N03W048.metadata.json","sha256":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"},"processingState":"READY","relativePath":"land-cover/fixture/N03W048.tif","requestedUrl":"https://example.test/data/N03W048.tif","routeId":"fixture-route","routePolicyVersion":1,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","sourceSnapshot":{"catalogRevision":1,"dataType":"LAND_COVER","datasetFamily":"esa-worldcover","datasetId":"fixture-worldcover-2021","datasetRelease":"2021","fileFormat":"COG_GEOTIFF","license":{"acceptanceRequired":true,"attribution":"Fixture Provider 2021.","id":"cc-by-4-0","title":"Creative Commons Attribution 4.0","url":"https://example.test/licenses/cc-by-4-0"},"limitations":"Fixture categorical data; not an RF-loss model.","maximumCacheAgeMillis":null,"nominalResolutionMeters":10.0,"normalizerVersion":"cog-v1","provenance":"Fixture source provenance.","provider":"Fixture Provider","queryVersion":"tile-v1","routeId":"fixture-route","routePolicyVersion":1,"snapshotPolicy":"IMMUTABLE_RELEASE","sourceCrs":"EPSG:4326","sourceUrl":"https://example.test/datasets/worldcover","title":"Fixture WorldCover","version":"1.0"},"status":"READY"}}"""
        const val GOLDEN_SHA256 = "f8f16f647f23fdf65574b0056d34c519eb8867c41f014b0174f8751d772e5762"
    }
}
