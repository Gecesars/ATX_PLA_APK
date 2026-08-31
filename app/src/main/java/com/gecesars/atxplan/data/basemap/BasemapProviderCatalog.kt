package com.gecesars.atxplan.data.basemap

import com.gecesars.atxplan.domain.basemap.MAX_BASEMAP_PROVIDER_COUNT
import com.gecesars.atxplan.domain.basemap.RasterBasemapProvider

/** Fixed provider catalog ported from the desktop application. */
object BasemapProviderCatalog {
    val providers: List<RasterBasemapProvider> = listOf(
        RasterBasemapProvider(
            id = "openstreetmap",
            label = "OpenStreetMap",
            tileUrlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            attributionUrl = "https://www.openstreetmap.org/copyright",
            termsUrl = "https://operations.osmfoundation.org/policies/tiles/",
            usageNotice = "Interactive visible-view loading only. Offline-area and bulk downloads are prohibited.",
            maximumZoom = 19,
        ),
        RasterBasemapProvider(
            id = "opentopomap",
            label = "OpenTopoMap",
            tileUrlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors · SRTM · © OpenTopoMap (CC-BY-SA)",
            attributionUrl = "https://www.opentopomap.org/about",
            termsUrl = "https://www.opentopomap.org/about",
            usageNotice = "Interactive visible-view loading only; service availability is not guaranteed.",
            maximumZoom = 17,
        ),
        RasterBasemapProvider(
            id = "cyclosm",
            label = "CyclOSM",
            tileUrlTemplate = "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors · CyclOSM",
            attributionUrl = "https://www.openstreetmap.org/copyright",
            termsUrl = "https://github.com/cyclosm/cyclosm-cartocss-style",
            usageNotice = "Interactive visible-view loading only; review the provider terms before production use.",
            maximumZoom = 20,
        ),
        RasterBasemapProvider(
            id = "hot-humanitarian",
            label = "Humanitarian HOT",
            tileUrlTemplate = "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors · Humanitarian OpenStreetMap Team",
            attributionUrl = "https://www.openstreetmap.org/copyright",
            termsUrl = "https://www.openstreetmap.fr/usage/",
            usageNotice = "The public service is limited to free, public, non-profit apps with moderate traffic.",
            minimumZoom = 1,
            maximumZoom = 19,
        ),
        RasterBasemapProvider(
            id = "osm-france",
            label = "OpenStreetMap France",
            tileUrlTemplate = "https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors · OpenStreetMap France",
            attributionUrl = "https://www.openstreetmap.org/copyright",
            termsUrl = "https://www.openstreetmap.fr/usage/",
            usageNotice = "The public service is limited to free, public, non-profit apps with moderate traffic.",
            maximumZoom = 20,
        ),
        RasterBasemapProvider(
            id = "osm-germany",
            label = "OpenStreetMap Deutschland",
            tileUrlTemplate = "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors · OpenStreetMap Deutschland",
            attributionUrl = "https://www.openstreetmap.org/copyright",
            termsUrl = "https://openstreetmap.de/germanstyle/",
            usageNotice = "Non-commercial interactive display only. Bulk and offline downloads are prohibited.",
            maximumZoom = 19,
        ),
    )

    const val defaultProviderId = "openstreetmap"

    init {
        require(providers.isNotEmpty() && providers.size <= MAX_BASEMAP_PROVIDER_COUNT) {
            "The basemap catalog must contain 1 to $MAX_BASEMAP_PROVIDER_COUNT providers."
        }
        require(providers.map(RasterBasemapProvider::id).distinct().size == providers.size) {
            "The basemap catalog contains duplicate provider IDs."
        }
        require(providers.any { provider -> provider.id == defaultProviderId }) {
            "The default basemap provider is missing."
        }
    }

    fun provider(providerId: String): RasterBasemapProvider = providers
        .firstOrNull { provider -> provider.id == providerId }
        ?: throw IllegalArgumentException("The basemap provider is not approved.")
}
