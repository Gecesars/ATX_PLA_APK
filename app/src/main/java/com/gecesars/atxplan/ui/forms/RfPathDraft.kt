package com.gecesars.atxplan.ui.forms

import com.gecesars.atxplan.domain.application.AddRfPathCommand
import com.gecesars.atxplan.domain.application.NewReceiver
import com.gecesars.atxplan.domain.application.NewRfNetwork
import com.gecesars.atxplan.domain.application.NewTransmitterSector
import com.gecesars.atxplan.domain.application.NewTransmitterSite
import com.gecesars.atxplan.domain.model.AzimuthDegrees
import com.gecesars.atxplan.domain.model.BandwidthMHz
import com.gecesars.atxplan.domain.model.FrequencyMHz
import com.gecesars.atxplan.domain.model.GainDbi
import com.gecesars.atxplan.domain.model.GeoCoordinate
import com.gecesars.atxplan.domain.model.HeightM
import com.gecesars.atxplan.domain.model.LatitudeDegrees
import com.gecesars.atxplan.domain.model.LongitudeDegrees
import com.gecesars.atxplan.domain.model.LossDb
import com.gecesars.atxplan.domain.model.PowerDbm
import com.gecesars.atxplan.domain.model.RadioSystem
import com.gecesars.atxplan.domain.model.TiltDegrees

/** Saveable text-field values for the complete transmitter-to-receiver path editor. */
data class RfPathDraft(
    val networkName: String = "Primary Network",
    val radioSystem: RadioSystem = RadioSystem.GENERIC,
    val frequencyMHz: String = "900",
    val bandwidthMHz: String = "10",
    val siteName: String = "Transmitter Site",
    val siteLatitude: String = "-23.55052",
    val siteLongitude: String = "-46.63331",
    val siteNotes: String = "",
    val sectorName: String = "Sector A",
    val sectorActive: Boolean = true,
    val sectorAzimuthDegrees: String = "0",
    val sectorTiltDegrees: String = "0",
    val sectorHeightM: String = "30",
    val transmitPowerDbm: String = "43",
    val transmitGainDbi: String = "15",
    val feederLossDb: String = "2",
    val receiverName: String = "Receiver A",
    val receiverLatitude: String = "-23.56000",
    val receiverLongitude: String = "-46.65000",
    val receiverHeightM: String = "6",
    val receiverGainDbi: String = "0",
    val receiverSystemLossDb: String = "0",
    val receiverSensitivityDbm: String = "-95",
    val receiverNoiseFigureDb: String = "6",
    val receiverAzimuthDegrees: String = "0",
    val receiverTiltDegrees: String = "0",
    val receiverNotes: String = "",
) {
    fun toCommand(projectId: String): Result<AddRfPathCommand> = runCatching {
        AddRfPathCommand(
            projectId = projectId,
            network = NewRfNetwork(
                name = networkName,
                system = radioSystem,
                downlinkFrequencyMHz = FrequencyMHz(frequencyMHz.decimal("Frequency")),
                bandwidthMHz = BandwidthMHz(bandwidthMHz.decimal("Bandwidth")),
            ),
            site = NewTransmitterSite(
                name = siteName,
                location = GeoCoordinate(
                    latitude = LatitudeDegrees(siteLatitude.decimal("Site latitude")),
                    longitude = LongitudeDegrees(siteLongitude.decimal("Site longitude")),
                ),
                notes = siteNotes,
            ),
            sector = NewTransmitterSector(
                name = sectorName,
                active = sectorActive,
                azimuthDegrees = AzimuthDegrees(
                    sectorAzimuthDegrees.decimal("Sector azimuth"),
                ),
                electricalTiltDegrees = TiltDegrees(
                    sectorTiltDegrees.decimal("Sector electrical tilt"),
                ),
                antennaHeightM = HeightM(sectorHeightM.decimal("Sector antenna height")),
                transmitPowerDbm = PowerDbm(transmitPowerDbm.decimal("Transmit power")),
                antennaGainDbi = GainDbi(transmitGainDbi.decimal("Transmit antenna gain")),
                feederLossDb = LossDb(feederLossDb.decimal("Feeder loss")),
            ),
            receiver = NewReceiver(
                name = receiverName,
                location = GeoCoordinate(
                    latitude = LatitudeDegrees(receiverLatitude.decimal("Receiver latitude")),
                    longitude = LongitudeDegrees(receiverLongitude.decimal("Receiver longitude")),
                ),
                antennaHeightM = HeightM(receiverHeightM.decimal("Receiver antenna height")),
                antennaGainDbi = GainDbi(receiverGainDbi.decimal("Receiver antenna gain")),
                systemLossDb = LossDb(receiverSystemLossDb.decimal("Receiver system loss")),
                sensitivityDbm = PowerDbm(
                    receiverSensitivityDbm.decimal("Receiver sensitivity"),
                ),
                noiseFigureDb = LossDb(receiverNoiseFigureDb.decimal("Receiver noise figure")),
                azimuthDegrees = AzimuthDegrees(
                    receiverAzimuthDegrees.decimal("Receiver azimuth"),
                ),
                electricalTiltDegrees = TiltDegrees(
                    receiverTiltDegrees.decimal("Receiver electrical tilt"),
                ),
                notes = receiverNotes,
            ),
        )
    }
}

private fun String.decimal(label: String): Double =
    trim().replace(',', '.').toDoubleOrNull()
        ?: throw IllegalArgumentException("$label must be a decimal number.")
