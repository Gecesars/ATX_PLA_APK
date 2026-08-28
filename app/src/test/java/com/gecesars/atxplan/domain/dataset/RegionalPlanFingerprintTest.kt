package com.gecesars.atxplan.domain.dataset

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalPlanFingerprintTest {
    @Test
    fun `canonical semantic and Android execution payloads match shared golden fixtures`() {
        val canonical = RegionalPlanFingerprint.canonicalize(buildingPlan())

        assertEquals(canonicalFixture("fixtures/regional_plan_semantic_v1.json"), RegionalPlanFingerprint.semanticCanonicalJson(canonical))
        assertEquals(canonicalFixture("fixtures/regional_plan_execution_v1.json"), RegionalPlanFingerprint.executionCanonicalJson(canonical))
        val expected = textFixture("fixtures/regional_plan_fingerprints_v1.txt").lineSequence().associate { line ->
            val (key, value) = line.split('=', limit = 2)
            key to value
        }
        assertEquals(expected.getValue("semantic_sha256"), RegionalPlanFingerprint.semantic(canonical))
        assertEquals(expected.getValue("execution_sha256"), RegionalPlanFingerprint.calculate(canonical))
    }

    @Test
    fun `collection order reason and sub microdegree noise do not change either fingerprint`() {
        val bounds = RegionalBounds(-46.7000004, -23.6000004, -46.5999996, -23.4999996)
        val first = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = bounds,
                selections = linkedSetOf(
                    RegionalDatasetSelection.ESA_WORLDCOVER_2021,
                    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                ),
                reason = "  São Paulo corridor  ",
            ),
        )
        val second = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.7, -23.6, -46.6, -23.5),
                selections = linkedSetOf(
                    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                    RegionalDatasetSelection.ESA_WORLDCOVER_2021,
                ),
                reason = "Different desktop wording",
            ),
        ).let { plan -> plan.copy(artifacts = plan.artifacts.reversed()) }

        assertEquals(RegionalPlanFingerprint.semantic(first), RegionalPlanFingerprint.semantic(second))
        assertEquals(RegionalPlanFingerprint.calculate(first), RegionalPlanFingerprint.calculate(second))
        assertNotEquals(
            RegionalPlanFingerprint.canonicalJson(RegionalPlanFingerprint.canonicalize(first)),
            RegionalPlanFingerprint.canonicalJson(RegionalPlanFingerprint.canonicalize(second)),
        )
    }

    @Test
    fun `negative zero is normalized before query path and fingerprint creation`() {
        val plan = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-0.0, -0.0, 0.002, 0.002),
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
                reason = "negative zero normalization",
            ),
        )
        val artifact = plan.artifacts.single()
        val canonical = RegionalPlanFingerprint.canonicalize(plan)

        assertEquals(0.0, plan.request.bounds.west, 0.0)
        assertEquals(0.0, plan.request.bounds.south, 0.0)
        assertFalse(artifact.requestBody.orEmpty().contains("-0.000000"))
        assertTrue(artifact.relativePath.contains("N00000000_E000000000"))
        assertEquals(0L, canonical.requestBounds.westE6)
        assertEquals(0L, canonical.requestBounds.southE6)
    }

    @Test
    fun `canonical payloads and hashes are independent of the process locale`() {
        val canonical = RegionalPlanFingerprint.canonicalize(buildingPlan())
        val expectedSemantic = RegionalPlanFingerprint.semanticCanonicalJson(canonical)
        val expectedExecution = RegionalPlanFingerprint.executionCanonicalJson(canonical)
        val expectedHashes = RegionalPlanFingerprint.semantic(canonical) to RegionalPlanFingerprint.calculate(canonical)
        val originalLocale = Locale.getDefault()

        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(expectedSemantic, RegionalPlanFingerprint.semanticCanonicalJson(canonical))
            assertEquals(expectedExecution, RegionalPlanFingerprint.executionCanonicalJson(canonical))
            assertEquals(expectedHashes, RegionalPlanFingerprint.semantic(canonical) to RegionalPlanFingerprint.calculate(canonical))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `semantic identity excludes Android routing while execution identity binds it`() {
        val canonical = RegionalPlanFingerprint.canonicalize(buildingPlan())
        val changedRoute = canonical.copy(
            artifacts = canonical.artifacts.map { artifact ->
                artifact.copy(routePolicyVersion = artifact.routePolicyVersion + 1)
            },
        )
        val changedLicense = canonical.copy(
            licenseSnapshots = canonical.licenseSnapshots.map { license ->
                license.copy(attribution = "${license.attribution} / reviewed variant")
            },
        )
        val changedCatalog = canonical.copy(
            catalogRevision = canonical.catalogRevision + 1,
            artifacts = canonical.artifacts.map { artifact ->
                artifact.copy(catalogRevision = artifact.catalogRevision + 1)
            },
        )
        val changedCacheAge = canonical.copy(
            artifacts = canonical.artifacts.map { artifact ->
                artifact.copy(maximumCacheAgeMillis = artifact.maximumCacheAgeMillis?.plus(1L))
            },
        )

        listOf(changedRoute, changedLicense, changedCatalog, changedCacheAge).forEach { changed ->
            assertEquals(RegionalPlanFingerprint.semantic(canonical), RegionalPlanFingerprint.semantic(changed))
            assertNotEquals(RegionalPlanFingerprint.calculate(canonical), RegionalPlanFingerprint.calculate(changed))
            assertFalse(RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(changed))
        }
    }

    @Test
    fun `logical query and bounds change semantic identity while HTTP controls and refresh are execution only`() {
        val basePlan = buildingPlan()
        val canonical = RegionalPlanFingerprint.canonicalize(basePlan)
        val changedBodyText = canonical.artifacts.single().requestBody.orEmpty() + " "
        val changedBody = canonical.copy(
            artifacts = listOf(
                canonical.artifacts.single().copy(
                    requestBody = changedBodyText,
                    requestBodySha256 = sha256(changedBodyText),
                ),
            ),
        )
        val changedBounds = canonical.copy(
            requestBounds = canonical.requestBounds.copy(eastE6 = canonical.requestBounds.eastE6 + 1L),
        )
        val changedLogicalQuery = canonical.copy(
            artifacts = canonical.artifacts.map { artifact ->
                artifact.copy(queryVersion = "osm-building-and-part-ways-bbox-v2")
            },
        )
        val forced = RegionalPlanFingerprint.canonicalize(
            RegionalDatasetPlanner().plan(basePlan.request.copy(liveSnapshotRefresh = true)),
        )

        assertEquals(RegionalPlanFingerprint.semantic(canonical), RegionalPlanFingerprint.semantic(changedBody))
        assertNotEquals(RegionalPlanFingerprint.calculate(canonical), RegionalPlanFingerprint.calculate(changedBody))
        assertNotEquals(RegionalPlanFingerprint.semantic(canonical), RegionalPlanFingerprint.semantic(changedBounds))
        assertNotEquals(RegionalPlanFingerprint.semantic(canonical), RegionalPlanFingerprint.semantic(changedLogicalQuery))
        assertEquals(RegionalPlanFingerprint.semantic(canonical), RegionalPlanFingerprint.semantic(forced))
        assertNotEquals(RegionalPlanFingerprint.calculate(canonical), RegionalPlanFingerprint.calculate(forced))
        assertTrue(RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(canonical))
        assertTrue(RegionalPlanFingerprint.isCompatibleWithCurrentCatalog(forced))
    }

    private fun buildingPlan(): RegionalDownloadPlan = RegionalDatasetPlanner().plan(
        RegionalDatasetRequest(
            bounds = RegionalBounds(-46.656, -23.562, -46.654, -23.560),
            selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
            reason = "São Paulo building snapshot",
        ),
    )

    private fun canonicalFixture(path: String): String = textFixture(path).also { payload ->
        require('\r' !in payload && '\n' !in payload && payload.lastOrNull()?.isWhitespace() == false) {
            "A canonical JSON fixture must contain one exact compact payload line."
        }
    }

    private fun textFixture(path: String): String {
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }
        require(bytes.size >= 2 && bytes.last() == '\n'.code.toByte() && bytes[bytes.lastIndex - 1] != '\r'.code.toByte()) {
            "A shared text fixture must use exactly one final LF convention."
        }
        val payload = bytes.copyOf(bytes.size - 1)
        require(!(payload.size >= 3 && payload[0] == 0xef.toByte() && payload[1] == 0xbb.toByte() && payload[2] == 0xbf.toByte())) {
            "A shared text fixture cannot contain a UTF-8 BOM."
        }
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
