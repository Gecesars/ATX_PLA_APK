package com.gecesars.atxplan.domain.anatel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnatelChannelFrequencyResolverTest {
    @Test
    fun exactSourceFrequencyAlwaysWinsOverNominalChannelCentre() {
        val resolved = AnatelChannelFrequencyResolver.resolve(
            service = AnatelBroadcastService.FM,
            sourceFrequencyRaw = "99.55",
            channelRaw = "258",
        )

        assertEquals(99.55, resolved.frequencyMHz!!, 0.0)
        assertEquals(AnatelFrequencyOrigin.SOURCE_ATTRIBUTE, resolved.origin)
        assertEquals("99.55", resolved.sourceFrequencyRaw)
    }

    @Test
    fun supportedFmAndTelevisionChannelsUseDocumentedFallbackCentres() {
        val cases = listOf(
            Triple(AnatelBroadcastService.FM, "141", 76.1),
            Triple(AnatelBroadcastService.FM, "201.0", 88.1),
            Triple(AnatelBroadcastService.TELEVISION, "2", 57.0),
            Triple(AnatelBroadcastService.TELEVISION, "6", 85.0),
            Triple(AnatelBroadcastService.TELEVISION, "7", 177.0),
            Triple(AnatelBroadcastService.TELEVISION, "13", 213.0),
            Triple(AnatelBroadcastService.TELEVISION, "14", 473.0),
            Triple(AnatelBroadcastService.TELEVISION, "69", 803.0),
        )

        cases.forEach { (service, channel, expectedMHz) ->
            val resolved = AnatelChannelFrequencyResolver.resolve(service, "", channel)
            assertEquals(expectedMHz, resolved.frequencyMHz!!, 1.0e-9)
            assertEquals(AnatelFrequencyOrigin.CHANNEL_FALLBACK, resolved.origin)
        }
    }

    @Test
    fun unsupportedOrUnknownChannelProducesExplicitNoData() {
        val resolved = AnatelChannelFrequencyResolver.resolve(
            service = AnatelBroadcastService.UNKNOWN,
            sourceFrequencyRaw = "invalid",
            channelRaw = "201",
        )

        assertNull(resolved.frequencyMHz)
        assertEquals(AnatelFrequencyOrigin.NO_DATA, resolved.origin)
        assertTrue(resolved.explanation.contains("No positive source frequency"))
    }

    @Test
    fun officialDescriptorKeepsEntryTypoAndLicenseReviewExplicit() {
        val descriptor = OfficialAnatelBasicPlanSource.descriptor

        assertEquals(
            setOf(
                "plano_basicoTVFM.xml",
                "secudariosTVFM.xml",
                "solicitacoesTVFM.xml",
            ),
            descriptor.archiveEntries.map { entry -> entry.name }.toSet(),
        )
        assertEquals(AnatelLicenseReviewStatus.REVIEW_REQUIRED, descriptor.license.reviewStatus)
        assertEquals("sistemas.anatel.gov.br", descriptor.allowedHosts.single())
    }
}
