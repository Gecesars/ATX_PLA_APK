package com.gecesars.atxplan.domain.contour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P526DeygoutAssisTest {
    @Test
    fun matchesDesktopReferenceForSingleRoundedObstacle() {
        val result = P526DeygoutAssis.calculate(
            distancesM = listOf(0.0, 1_000.0, 2_000.0),
            effectiveHeightsM = listOf(100.0, 180.0, 100.0),
            frequencyMHz = 641.0,
        )

        assertEquals(132.01284911578972, result.lossDb, 1e-9)
        assertEquals(1, result.obstacles.size)
        assertEquals(1, result.obstacles.single().profileIndex)
        assertEquals(101.78719709017133, result.obstacles.single().roundedCorrectionDb, 1e-9)
    }

    @Test
    fun matchesDesktopReferenceForTwoDeygoutSubpaths() {
        val result = P526DeygoutAssis.calculate(
            distancesM = listOf(0.0, 1_000.0, 2_000.0, 3_000.0, 4_000.0),
            effectiveHeightsM = listOf(100.0, 170.0, 90.0, 160.0, 100.0),
            frequencyMHz = 641.0,
        )

        assertEquals(144.00066985865192, result.lossDb, 1e-9)
        assertEquals(listOf(1, 3), result.obstacles.map(P526AssisObstacle::profileIndex))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun lineOfSightProfileHasNoRegulatoryObstacle() {
        val result = P526DeygoutAssis.calculate(
            distancesM = listOf(0.0, 1_000.0, 2_000.0),
            effectiveHeightsM = listOf(100.0, 90.0, 100.0),
            frequencyMHz = 641.0,
        )

        assertEquals(0.0, result.lossDb, 0.0)
        assertTrue(result.obstacles.isEmpty())
    }
}
