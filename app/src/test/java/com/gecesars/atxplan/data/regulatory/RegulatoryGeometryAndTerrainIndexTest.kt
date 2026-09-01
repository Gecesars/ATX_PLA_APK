package com.gecesars.atxplan.data.regulatory

import com.gecesars.atxplan.domain.dataset.RegionalBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RegulatoryGeometryAndTerrainIndexTest {
    @Test
    fun resolvesAnademMgrsZoneAndLatitudeBandDeterministically() {
        assertEquals("23K", anademTileKey(-23.55052, -46.633308))
        assertEquals(
            listOf("22K", "23K"),
            anademTileKeys(RegionalBounds(-48.1, -24.0, -45.9, -22.0)),
        )
    }

    @Test
    fun parsesBoundedLittleEndianGeoPackagePolygon() {
        val points = listOf(
            -47.0 to -24.0,
            -46.0 to -24.0,
            -46.0 to -23.0,
            -47.0 to -23.0,
            -47.0 to -24.0,
        )
        val blob = ByteBuffer.allocate(8 + 1 + 4 + 4 + 4 + points.size * 16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put('G'.code.toByte())
            .put('P'.code.toByte())
            .put(0)
            .put(1)
            .putInt(4_674)
            .put(1)
            .putInt(3)
            .putInt(1)
            .putInt(points.size)
            .apply { points.forEach { (x, y) -> putDouble(x).putDouble(y) } }
            .array()

        val polygons = parseGeoPackageGeometry(blob)

        assertEquals(1, polygons.size)
        assertEquals(1, polygons.single().rings.size)
        assertEquals(points.size, polygons.single().rings.single().points.size)
        assertTrue(polygons.single().rings.single().points.first() == polygons.single().rings.single().points.last())
    }
}
