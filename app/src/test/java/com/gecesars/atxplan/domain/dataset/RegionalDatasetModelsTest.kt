package com.gecesars.atxplan.domain.dataset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionalDatasetModelsTest {
    @Test
    fun `planner resolves exact Copernicus and WorldCover tiles for Sao Paulo`() {
        val request = RegionalDatasetRequest(
            bounds = RegionalBounds(-46.70, -23.62, -46.45, -23.40),
            selections = setOf(
                RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                RegionalDatasetSelection.ESA_WORLDCOVER_2021,
            ),
            reason = "São Paulo link corridor",
        )

        val plan = RegionalDatasetPlanner().plan(request)

        assertEquals(2, plan.artifacts.size)
        assertEquals(
            "https://copernicus-dem-30m.s3.eu-central-1.amazonaws.com/" +
                "Copernicus_DSM_COG_10_S24_00_W047_00_DEM/" +
                "Copernicus_DSM_COG_10_S24_00_W047_00_DEM.tif",
            plan.artifacts[0].url,
        )
        assertEquals(
            "elevation/copernicus-dem-glo30/S24_00_W047_00_DEM.tif",
            plan.artifacts[0].relativePath,
        )
        assertEquals(
            "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/" +
                "ESA_WorldCover_10m_2021_v200_S24W048_Map.tif",
            plan.artifacts[1].url,
        )
        assertEquals(
            "land-cover/esa-worldcover-2021/S24W048_Map.tif",
            plan.artifacts[1].relativePath,
        )
        assertEquals(215_000_000L, plan.estimatedBytes)
        assertEquals(
            listOf("cc-by-4.0-esa-worldcover", "copernicus-dem-license"),
            plan.licenses.map(RegionalDatasetLicense::id),
        )
        assertEquals(RegionalDataType.DIGITAL_SURFACE_MODEL, plan.artifacts.first().source.dataType)
        assertTrue(plan.artifacts.first().source.limitations.contains("not a bare-earth DTM"))
        assertEquals("copernicus-dem-glo30", plan.artifacts.first().source.datasetFamily)
        assertEquals("2021", plan.artifacts.first().source.datasetRelease)
        assertEquals(REGIONAL_DATASET_CATALOG_REVISION, plan.artifacts.first().source.catalogRevision)
        assertEquals("copernicus-glo30-tile-v1", plan.artifacts.first().source.queryVersion)
        assertEquals("atx-tiff-metadata-v1", plan.artifacts.first().source.normalizerVersion)
        assertEquals("copernicus-dem-aws-eu-central-1", plan.artifacts.first().source.routeId)
        assertEquals(RegionalSnapshotPolicy.IMMUTABLE_RELEASE, plan.artifacts.first().source.snapshotPolicy)
    }

    @Test
    fun `half-open bounds do not fetch a tile at the exact east or north edge`() {
        val plan = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(west = -47.0, south = -24.0, east = -46.0, north = -23.0),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        )

        assertEquals(1, plan.artifacts.size)
        assertEquals(-24, plan.artifacts.single().south)
        assertEquals(-47, plan.artifacts.single().west)
        assertTrue(plan.request.bounds.contains(-23.5, -46.5))
        assertFalse(plan.request.bounds.contains(-23.0, -46.5))
        assertFalse(plan.request.bounds.contains(-23.5, -46.0))
    }

    @Test
    fun `planner quantizes bounds before raster tile selection`() {
        val noisy = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(
                    west = -47.0000004,
                    south = -24.0000004,
                    east = -46.5000004,
                    north = -23.5000004,
                ),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        )
        val aligned = RegionalDatasetPlanner().plan(
            noisy.request.copy(bounds = RegionalBounds(-47.0, -24.0, -46.5, -23.5)),
        )

        assertEquals(RegionalBounds(-47.0, -24.0, -46.5, -23.5), noisy.request.bounds)
        assertEquals(listOf(-24 to -47), noisy.artifacts.map { it.south to it.west })
        assertEquals(aligned, noisy)
        assertEquals(RegionalPlanFingerprint.calculate(aligned), RegionalPlanFingerprint.calculate(noisy))
    }

    @Test
    fun `bounds reject antimeridian crossing and non-finite coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionalBounds(west = 179.0, south = -1.0, east = -179.0, north = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalBounds(west = Double.NaN, south = -1.0, east = 1.0, north = 1.0)
        }
    }

    @Test
    fun `regional request enforces bounded dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionalDatasetRequest(
                bounds = RegionalBounds(-48.0, -24.0, -46.5, -23.5),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            )
        }
    }

    @Test
    fun `experimental buildings use one deterministic bounded Overpass request`() {
        val plan = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.6500, -23.5700, -46.6250, -23.5450),
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
                reason = "Local building context",
            ),
        )
        val artifact = plan.artifacts.single()

        assertFalse(plan.request.liveSnapshotRefresh)
        assertEquals(RegionalHttpMethod.POST, artifact.httpMethod)
        assertEquals("https://lambert.openstreetmap.de/api/interpreter", artifact.url)
        assertEquals(
            "buildings/openstreetmap-overpass/S23570000_W046650000_S23545000_W046625000.json",
            artifact.relativePath,
        )
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", artifact.contentType)
        assertEquals(
            "data=%5Bout%3Ajson%5D%5Btimeout%3A25%5D%5Bmaxsize%3A16777216%5D%3B" +
                "%28way%5B%22building%22%5D%28-23.570000%2C-46.650000%2C-23.545000%2C-46.625000%29%3B" +
                "way%5B%22building%3Apart%22%5D%28-23.570000%2C-46.650000%2C-23.545000%2C-46.625000%29%3B%29%3Bout+geom%3B",
            artifact.requestBody,
        )
        assertTrue(artifact.source.limitations.contains("building and building-part ways only"))
        assertEquals("osm-building-and-part-ways-bbox-v1", artifact.source.queryVersion)
        assertEquals("atx-osm-building-geojson-v1", artifact.source.normalizerVersion)
        assertEquals("osm-overpass-lambert", artifact.source.routeId)
        assertEquals(1, artifact.source.routePolicyVersion)
        assertEquals(RegionalSnapshotPolicy.LIVE_SNAPSHOT_BOUNDED_CACHE, artifact.source.snapshotPolicy)
        assertEquals(OSM_BUILDINGS_CACHE_MAX_AGE_MILLIS, artifact.source.maximumCacheAgeMillis)
        assertEquals(
            RegionalArtifactCachePolicy.LIVE_SNAPSHOT_REUSE_WITHIN_MAX_AGE,
            artifact.cachePolicy,
        )
        assertTrue(artifact.estimatedBytes <= OVERPASS_MAX_RESPONSE_BYTES)
    }

    @Test
    fun `live snapshot refresh is explicit while static releases remain immutable`() {
        val bounds = RegionalBounds(-46.6500, -23.5700, -46.6250, -23.5450)
        val reusable = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = bounds,
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
            ),
        ).artifacts.single()
        val refreshed = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = bounds,
                selections = setOf(
                    RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                    RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL,
                ),
                liveSnapshotRefresh = true,
            ),
        )

        assertTrue(refreshed.request.liveSnapshotRefresh)
        assertEquals(
            RegionalArtifactCachePolicy.IMMUTABLE_RELEASE,
            refreshed.artifacts.first { it.source.selection == RegionalDatasetSelection.COPERNICUS_GLO_30_DSM }.cachePolicy,
        )
        assertEquals(
            RegionalArtifactCachePolicy.LIVE_SNAPSHOT_FORCE_REFRESH,
            refreshed.artifacts.first { it.source.selection == RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL }.cachePolicy,
        )
        val refreshedLive = refreshed.artifacts.first {
            it.source.selection == RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL
        }
        assertEquals(reusable.relativePath, refreshedLive.relativePath)
        assertEquals(reusable.url, refreshedLive.url)
        assertEquals(reusable.requestBody, refreshedLive.requestBody)
        assertThrows(IllegalArgumentException::class.java) {
            RegionalDatasetRequest(
                bounds = bounds,
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
                liveSnapshotRefresh = true,
            )
        }
    }

    @Test
    fun `source snapshots preserve historical wording without current catalog equality`() {
        val current = RegionalDatasetCatalog.osmBuildingsExperimental.toSourceSnapshot()
        val historical = current.copy(
            catalogRevision = 1,
            license = current.license.copy(attribution = "Historical OpenStreetMap attribution wording"),
            provenance = "Historical acquisition-time provenance retained by schema 2.",
        )
        val record = RegionalInventoryRecord(
            datasetId = historical.datasetId,
            relativePath = "buildings/openstreetmap-overpass/historical.json",
            requestedUrl = "https://retired.example.org/api/interpreter",
            effectiveUrl = "https://retired.example.org/api/interpreter?mirror=1",
            routeId = historical.routeId,
            routePolicyVersion = historical.routePolicyVersion,
            acquiredAt = "2026-08-27T12:00:00.000Z",
            sourceSnapshot = historical,
            status = RegionalTransferStatus.READY,
            bytes = 42L,
            sha256 = "a".repeat(64),
            checkedAt = "2026-08-27T12:00:01.000Z",
            bounds = RegionalBounds(-46.6500, -23.5700, -46.6250, -23.5450),
            processingState = RegionalProcessingState.READY,
        )

        assertEquals(2, REGIONAL_INVENTORY_SCHEMA_VERSION)
        assertEquals("Historical OpenStreetMap attribution wording", record.attribution)
        assertEquals("https://retired.example.org/api/interpreter", record.requestedUrl)
        assertEquals(1, record.sourceSnapshot.catalogRevision)
        assertEquals(RegionalDataType.BUILDING_FOOTPRINTS, record.sourceSnapshot.dataType)
        assertEquals(RegionalFileFormat.OVERPASS_JSON, record.sourceSnapshot.fileFormat)
    }

    @Test
    fun `source snapshot validation is structural and bounded`() {
        val current = RegionalDatasetCatalog.copernicusGlo30Dsm.toSourceSnapshot()

        assertThrows(IllegalArgumentException::class.java) {
            current.copy(sourceUrl = "http://example.org/source")
        }
        assertThrows(IllegalArgumentException::class.java) {
            current.copy(provenance = "x".repeat(2_001))
        }
    }

    @Test
    fun `experimental buildings reject broad spans and areas`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.70, -23.60, -46.64, -23.56),
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalDatasetRequest(
                bounds = RegionalBounds(-0.025, -0.025, 0.025, 0.025),
                selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
            )
        }
    }

    @Test
    fun `planner rejects an estimated batch above the mobile budget`() {
        val request = RegionalDatasetRequest(
            bounds = RegionalBounds(-47.01, -24.01, -46.01, -23.01),
            selections = setOf(
                RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                RegionalDatasetSelection.ESA_WORLDCOVER_2021,
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            RegionalDatasetPlanner().plan(request)
        }

        assertTrue(error.message.orEmpty().contains("above the"))
        assertEquals(384L * 1024L * 1024L, DEFAULT_MAXIMUM_BATCH_BYTES)
    }

    @Test
    fun `artifact construction rejects an arbitrary provider URL`() {
        val bounds = RegionalBounds(-46.7, -23.6, -46.5, -23.4)

        assertThrows(IllegalArgumentException::class.java) {
            RegionalArtifact(
                source = RegionalDatasetCatalog.copernicusGlo30Dsm,
                requestBounds = bounds,
                coverageBounds = RegionalBounds(-47.0, -24.0, -46.0, -23.0),
                south = -24,
                west = -47,
                url = "https://example.org/untrusted.tif",
                relativePath = "elevation/copernicus-dem-glo30/S24_00_W047_00_DEM.tif",
                estimatedBytes = 65_000_000L,
            )
        }
    }

    @Test
    fun `ready results require integrity metadata`() {
        val artifact = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.7, -23.6, -46.5, -23.4),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        ).artifacts.single()

        assertThrows(IllegalArgumentException::class.java) {
            RegionalArtifactResult(artifact = artifact, status = RegionalTransferStatus.READY)
        }
        val result = RegionalArtifactResult(
            artifact = artifact,
            status = RegionalTransferStatus.READY,
            bytes = 42L,
            sha256 = "a".repeat(64),
        )
        assertTrue(RegionalDownloadResult(listOf(result)).isSuccessful)
        assertEquals(artifact.url, result.requestedUrl)
        assertEquals(artifact.source.routeId, result.routeId)
        assertEquals(artifact.source.routePolicyVersion, result.routePolicyVersion)
        assertEquals(artifact.source.datasetId, result.sourceSnapshot.datasetId)
    }

    @Test
    fun `schema 2 results and inventory reject a cross origin effective URL`() {
        val artifact = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.7, -23.6, -46.5, -23.4),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        ).artifacts.single()
        val crossOriginUrl = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/forged.tif"

        assertEquals(2, REGIONAL_INVENTORY_SCHEMA_VERSION)
        assertThrows(IllegalArgumentException::class.java) {
            RegionalArtifactResult(
                artifact = artifact,
                status = RegionalTransferStatus.READY,
                effectiveUrl = crossOriginUrl,
                acquiredAt = "2026-08-27T12:00:00.000Z",
                bytes = 42L,
                sha256 = "a".repeat(64),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionalInventoryRecord(
                datasetId = artifact.source.datasetId,
                relativePath = artifact.relativePath,
                requestedUrl = artifact.url,
                effectiveUrl = crossOriginUrl,
                routeId = artifact.source.routeId,
                routePolicyVersion = artifact.source.routePolicyVersion,
                acquiredAt = "2026-08-27T12:00:00.000Z",
                sourceSnapshot = artifact.source.toSourceSnapshot(),
                status = RegionalTransferStatus.READY,
                bytes = 42L,
                sha256 = "a".repeat(64),
                checkedAt = "2026-08-27T12:00:01.000Z",
                bounds = artifact.requestBounds,
                processingState = RegionalProcessingState.READY,
            )
        }
    }

    @Test
    fun `regional result requires a UTC millisecond acquisition timestamp`() {
        val artifact = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.7, -23.6, -46.5, -23.4),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        ).artifacts.single()

        assertThrows(IllegalArgumentException::class.java) {
            RegionalArtifactResult(
                artifact = artifact,
                status = RegionalTransferStatus.READY,
                effectiveUrl = artifact.url,
                acquiredAt = "2026-08-27T12:00:00Z",
                bytes = 42L,
                sha256 = "a".repeat(64),
            )
        }
    }

    @Test
    fun `inventory record requires UTC millisecond acquisition and check timestamps`() {
        val artifact = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.7, -23.6, -46.5, -23.4),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        ).artifacts.single()
        val valid = RegionalInventoryRecord(
            datasetId = artifact.source.datasetId,
            relativePath = artifact.relativePath,
            requestedUrl = artifact.url,
            effectiveUrl = artifact.url,
            routeId = artifact.source.routeId,
            routePolicyVersion = artifact.source.routePolicyVersion,
            acquiredAt = "2026-08-27T12:00:00.000Z",
            sourceSnapshot = artifact.source.toSourceSnapshot(),
            status = RegionalTransferStatus.READY,
            bytes = 42L,
            sha256 = "a".repeat(64),
            checkedAt = "2026-08-27T12:00:01.000Z",
            bounds = artifact.requestBounds,
            processingState = RegionalProcessingState.READY,
        )

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(acquiredAt = "2026-08-27T12:00:00+00:00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(checkedAt = "2026-08-27T12:00:01Z")
        }
    }

    @Test
    fun `inventory requires a UTC millisecond update timestamp`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegionalInventory(updatedAt = "2026-08-27T12:00:00Z")
        }
    }

    @Test
    fun `an unpublished optional raster tile does not invent data or fail the whole batch`() {
        val artifact = RegionalDatasetPlanner().plan(
            RegionalDatasetRequest(
                bounds = RegionalBounds(-46.7, -23.6, -46.5, -23.4),
                selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            ),
        ).artifacts.single()

        val result = RegionalDownloadResult(
            listOf(
                RegionalArtifactResult(
                    artifact = artifact,
                    status = RegionalTransferStatus.NOT_FOUND,
                    notes = "The provider does not publish this optional tile.",
                ),
            ),
        )

        assertTrue(result.isSuccessful)
        assertEquals(0, result.readyCount)
    }
}
