package com.gecesars.atxplan.domain.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RfCalculatorTest {
    @Test
    fun `fspl at 900 MHz over 10 km matches physical baseline`() {
        val loss = RfCalculator.freeSpacePathLossDb(frequencyMHz = 900.0, distanceKm = 10.0)

        assertEquals(111.5326, loss, 0.0001)
    }

    @Test
    fun `link budget keeps every gain and loss sign explicit`() {
        val result = RfCalculator.linkBudget(
            LinkBudgetInput(
                frequencyMHz = 900.0,
                distanceKm = 10.0,
                transmitPowerDbm = 43.0,
                transmitAntennaGainDbi = 15.0,
                transmitLossDb = 2.0,
                receiveAntennaGainDbi = 0.0,
                receiveLossDb = 0.0,
                additionalPathLossDb = 0.0,
                receiverSensitivityDbm = -95.0,
                bandwidthMHz = 10.0,
                receiverNoiseFigureDb = 6.0,
            ),
        )

        assertEquals(56.0, result.eirpDbm, 1e-9)
        assertEquals(-55.5326, result.receivedPowerDbm, 0.0001)
        assertEquals(39.4674, result.fadeMarginDb, 0.0001)
        assertEquals(-98.0, result.noiseFloorDbm, 1e-9)
        assertEquals(42.4674, result.signalToNoiseDb, 0.0001)
        assertEquals(28.8575, result.firstFresnelMidpointRadiusM, 0.0001)
    }

    @Test
    fun `link budget carries explicit production calculation provenance`() {
        val result = RfCalculator.linkBudget(
            LinkBudgetInput(
                frequencyMHz = 900.0,
                distanceKm = 10.0,
                transmitPowerDbm = 43.0,
                transmitAntennaGainDbi = 15.0,
                transmitLossDb = 2.0,
                receiveAntennaGainDbi = 0.0,
                receiveLossDb = 0.0,
                additionalPathLossDb = 0.0,
                receiverSensitivityDbm = -95.0,
                bandwidthMHz = 10.0,
                receiverNoiseFigureDb = 6.0,
            ),
        )

        assertEquals(RfCalculator.PROVENANCE, result.provenance)
        assertEquals("itu-r-p525-fspl", result.provenance.modelId)
        assertEquals("P.525/FSPL", result.provenance.modelLabel)
        assertEquals(LinkBudgetExecutionMode.LOCAL, result.provenance.executionMode)
        assertEquals("No external datasets", result.provenance.dataProvenance)
    }

    @Test
    fun `invalid physical inputs are rejected instead of coerced`() {
        assertThrows(IllegalArgumentException::class.java) {
            RfCalculator.freeSpacePathLossDb(frequencyMHz = 900.0, distanceKm = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RfCalculator.firstFresnelRadiusM(
                frequencyMHz = 900.0,
                totalDistanceKm = 10.0,
                pathFraction = 1.0,
            )
        }
    }

    @Test
    fun `thermal noise uses bandwidth in hertz and receiver noise figure`() {
        assertEquals(
            -118.0,
            RfCalculator.thermalNoiseFloorDbm(
                bandwidthHz = 10_000.0,
                receiverNoiseFigureDb = 16.0,
            ),
            1e-9,
        )
    }
}
