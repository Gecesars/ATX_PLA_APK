package com.gecesars.atxplan.data.basemap

import com.gecesars.atxplan.domain.basemap.MAX_BASEMAP_PROVIDER_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasemapProviderCatalogTest {
    @Test
    fun `desktop parity catalog is bounded unique https and attributed`() {
        val providers = BasemapProviderCatalog.providers

        assertEquals(6, providers.size)
        assertTrue(providers.size <= MAX_BASEMAP_PROVIDER_COUNT)
        assertEquals(providers.size, providers.map { provider -> provider.id }.distinct().size)
        assertTrue(providers.all { provider -> provider.tileUrlTemplate.startsWith("https://") })
        assertTrue(providers.all { provider -> provider.attribution.isNotBlank() })
        assertTrue(providers.all { provider -> provider.termsUrl.startsWith("https://") })
        assertEquals("openstreetmap", BasemapProviderCatalog.defaultProviderId)
    }
}
