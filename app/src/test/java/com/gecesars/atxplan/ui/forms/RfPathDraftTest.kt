package com.gecesars.atxplan.ui.forms

import com.gecesars.atxplan.domain.model.RadioSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfPathDraftTest {
    @Test
    fun `draft creates a unit-safe complete RF path command`() {
        val command = RfPathDraft(
            radioSystem = RadioSystem.FWA,
            frequencyMHz = "3,625",
            bandwidthMHz = "40.0",
            siteLatitude = "-23.550520123",
            siteLongitude = "-46.633310987",
            sectorAzimuthDegrees = "359.999",
            receiverSensitivityDbm = "-104.5",
        ).toCommand("project-1").getOrThrow()

        assertEquals("project-1", command.projectId)
        assertEquals(RadioSystem.FWA, command.network.system)
        assertEquals(3.625, command.network.downlinkFrequencyMHz.value, 0.0)
        assertEquals(40.0, command.network.bandwidthMHz.value, 0.0)
        assertEquals(-23.550520123, command.site.location.latitude.value, 0.0)
        assertEquals(-46.633310987, command.site.location.longitude.value, 0.0)
        assertEquals(359.999, command.sector.azimuthDegrees.value, 0.0)
        assertEquals(-104.5, command.receiver.sensitivityDbm.value, 0.0)
    }

    @Test
    fun `draft reports the field that is not a decimal number`() {
        val result = RfPathDraft(receiverLatitude = "north").toCommand("project-1")

        assertTrue(result.isFailure)
        assertEquals(
            "Receiver latitude must be a decimal number.",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `draft enforces canonical coordinate and loss boundaries`() {
        val invalidLongitude = RfPathDraft(receiverLongitude = "180").toCommand("project-1")
        val invalidLoss = RfPathDraft(feederLossDb = "-0.1").toCommand("project-1")

        assertTrue(invalidLongitude.isFailure)
        assertTrue(invalidLoss.isFailure)
    }
}
