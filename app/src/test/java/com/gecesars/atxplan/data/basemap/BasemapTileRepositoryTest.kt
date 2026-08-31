package com.gecesars.atxplan.data.basemap

import com.gecesars.atxplan.data.dataset.RegionalHttpResponse
import com.gecesars.atxplan.data.dataset.RegionalHttpTransport
import com.gecesars.atxplan.domain.basemap.RasterBasemapProvider
import com.gecesars.atxplan.domain.geo.GeographicCamera
import com.gecesars.atxplan.domain.geo.ViewportSizePx
import com.gecesars.atxplan.domain.model.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class BasemapTileRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `visible tiles are bounded validated cached and reused`() = runTest {
        var requestCount = 0
        val tileBytes = ByteArray(64) { index -> index.toByte() }.apply {
            this[0] = 0x89.toByte()
            this[1] = 0x50
            this[2] = 0x4e
            this[3] = 0x47
        }
        val transport = RegionalHttpTransport {
            requestCount += 1
            RegionalHttpResponse(
                statusCode = 200,
                finalUrl = it.url,
                contentLength = tileBytes.size.toLong(),
                contentRange = null,
                etag = null,
                lastModified = null,
                body = ByteArrayInputStream(tileBytes),
                closeAction = {},
            )
        }
        val repository = FileBasemapTileRepository(
            cacheRoot = temporaryFolder.newFolder("tiles"),
            transportFactory = { transport },
            validator = BasemapTileFileValidator { file ->
                file.isFile && file.length() == tileBytes.size.toLong() &&
                    file.inputStream().use { input -> input.read() == 0x89 }
            },
        )
        val provider = testProvider()
        val camera = GeographicCamera(GeoPoint(-23.55052, -46.633308), zoom = 10.0)
        val viewport = ViewportSizePx(320.0, 240.0)

        val first = repository.loadVisibleTiles(provider, camera, viewport, 256.0)
        val requestsAfterFirstLoad = requestCount
        val second = repository.loadVisibleTiles(provider, camera, viewport, 256.0)

        assertTrue(first.tiles.isNotEmpty())
        assertTrue(first.tiles.size <= 48)
        assertEquals(first.tiles.size, first.downloadedCount)
        assertEquals(0, first.failureCount)
        assertEquals(requestsAfterFirstLoad, requestCount)
        assertEquals(second.tiles.size, second.reusedCount)
        assertEquals(0, second.downloadedCount)
        assertTrue(second.tiles.all { tile -> tile.absolutePath.contains("test-provider") })
    }

    private fun testProvider() = RasterBasemapProvider(
        id = "test-provider",
        label = "Test Provider",
        tileUrlTemplate = "https://tiles.example.test/{z}/{x}/{y}.png",
        attribution = "Test attribution",
        attributionUrl = "https://tiles.example.test/attribution",
        termsUrl = "https://tiles.example.test/terms",
        usageNotice = "Interactive viewport tests only.",
        maximumZoom = 19,
    )
}
