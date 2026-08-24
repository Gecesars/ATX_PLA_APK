package com.gecesars.atxplan.domain.rf

import kotlin.math.log10
import kotlin.math.sqrt

data class LinkBudgetInput(
    val frequencyMHz: Double,
    val distanceKm: Double,
    val transmitPowerDbm: Double,
    val transmitAntennaGainDbi: Double,
    val transmitLossDb: Double,
    val receiveAntennaGainDbi: Double,
    val receiveLossDb: Double,
    val additionalPathLossDb: Double,
    val receiverSensitivityDbm: Double,
    val bandwidthMHz: Double,
    val receiverNoiseFigureDb: Double,
)

data class LinkBudgetResult(
    val freeSpacePathLossDb: Double,
    val eirpDbm: Double,
    val receivedPowerDbm: Double,
    val fadeMarginDb: Double,
    val firstFresnelMidpointRadiusM: Double,
    val noiseFloorDbm: Double,
    val signalToNoiseDb: Double,
)

object RfCalculator {
    private const val SPEED_OF_LIGHT_M_PER_S = 299_792_458.0
    private const val FSPL_KM_MHZ_CONSTANT_DB = 32.447783
    private const val THERMAL_NOISE_DENSITY_DBM_HZ = -174.0

    fun linkBudget(input: LinkBudgetInput): LinkBudgetResult {
        validate(input)
        val fsplDb = freeSpacePathLossDb(input.frequencyMHz, input.distanceKm)
        val eirpDbm = input.transmitPowerDbm - input.transmitLossDb + input.transmitAntennaGainDbi
        val receivedPowerDbm = eirpDbm - fsplDb - input.additionalPathLossDb +
            input.receiveAntennaGainDbi - input.receiveLossDb
        val noiseFloorDbm = thermalNoiseFloorDbm(
            bandwidthHz = input.bandwidthMHz * 1_000_000.0,
            receiverNoiseFigureDb = input.receiverNoiseFigureDb,
        )
        return LinkBudgetResult(
            freeSpacePathLossDb = fsplDb,
            eirpDbm = eirpDbm,
            receivedPowerDbm = receivedPowerDbm,
            fadeMarginDb = receivedPowerDbm - input.receiverSensitivityDbm,
            firstFresnelMidpointRadiusM = firstFresnelRadiusM(
                frequencyMHz = input.frequencyMHz,
                totalDistanceKm = input.distanceKm,
                pathFraction = 0.5,
            ),
            noiseFloorDbm = noiseFloorDbm,
            signalToNoiseDb = receivedPowerDbm - noiseFloorDbm,
        )
    }

    fun freeSpacePathLossDb(frequencyMHz: Double, distanceKm: Double): Double {
        requirePositiveFinite("frequency", frequencyMHz)
        requirePositiveFinite("distance", distanceKm)
        return FSPL_KM_MHZ_CONSTANT_DB + 20.0 * log10(frequencyMHz) + 20.0 * log10(distanceKm)
    }

    fun firstFresnelRadiusM(
        frequencyMHz: Double,
        totalDistanceKm: Double,
        pathFraction: Double,
    ): Double {
        requirePositiveFinite("frequency", frequencyMHz)
        requirePositiveFinite("distance", totalDistanceKm)
        require(pathFraction.isFinite() && pathFraction > 0.0 && pathFraction < 1.0) {
            "The path position must be between 0 and 1."
        }
        val wavelengthM = SPEED_OF_LIGHT_M_PER_S / (frequencyMHz * 1_000_000.0)
        val totalDistanceM = totalDistanceKm * 1_000.0
        val firstLegM = totalDistanceM * pathFraction
        val secondLegM = totalDistanceM - firstLegM
        return sqrt(wavelengthM * firstLegM * secondLegM / totalDistanceM)
    }

    fun thermalNoiseFloorDbm(bandwidthHz: Double, receiverNoiseFigureDb: Double): Double {
        requirePositiveFinite("bandwidth", bandwidthHz)
        require(receiverNoiseFigureDb.isFinite() && receiverNoiseFigureDb >= 0.0) {
            "The noise figure must be finite and nonnegative."
        }
        return THERMAL_NOISE_DENSITY_DBM_HZ + 10.0 * log10(bandwidthHz) + receiverNoiseFigureDb
    }

    private fun validate(input: LinkBudgetInput) {
        requirePositiveFinite("frequency", input.frequencyMHz)
        require(input.frequencyMHz <= 100_000.0) { "The specified frequency is outside the operating limit." }
        requirePositiveFinite("distance", input.distanceKm)
        require(input.distanceKm <= 50_000.0) { "The specified distance is outside the operating limit." }
        requirePositiveFinite("bandwidth", input.bandwidthMHz)
        listOf(
            input.transmitPowerDbm,
            input.transmitAntennaGainDbi,
            input.transmitLossDb,
            input.receiveAntennaGainDbi,
            input.receiveLossDb,
            input.additionalPathLossDb,
            input.receiverSensitivityDbm,
            input.receiverNoiseFigureDb,
        ).forEach { require(it.isFinite()) { "All RF parameters must be finite." } }
        require(input.transmitLossDb >= 0.0 && input.receiveLossDb >= 0.0) {
            "Transmit and receive system losses cannot be negative."
        }
        require(input.additionalPathLossDb >= 0.0) { "Additional path loss cannot be negative." }
        require(input.receiverNoiseFigureDb >= 0.0) { "The noise figure cannot be negative." }
    }

    private fun requirePositiveFinite(label: String, value: Double) {
        require(value.isFinite() && value > 0.0) { "The $label must be positive and finite." }
    }
}
