package com.gecesars.atxplan.domain.model

import kotlinx.serialization.Serializable

/** Latitude in decimal degrees, inclusive of both poles. */
@JvmInline
@Serializable
value class LatitudeDegrees(val value: Double) {
    init {
        require(value.isFinite() && value in -90.0..90.0) {
            "Latitude must be finite and between -90 and 90 degrees."
        }
    }
}

/** Longitude in canonical decimal degrees: [-180, 180). */
@JvmInline
@Serializable
value class LongitudeDegrees(val value: Double) {
    init {
        require(value.isFinite() && value >= -180.0 && value < 180.0) {
            "Longitude must be finite and in the range [-180, 180) degrees."
        }
    }
}

/** Radio frequency in megahertz. */
@JvmInline
@Serializable
value class FrequencyMHz(val value: Double) {
    init {
        require(value.isFinite() && value > 0.0) {
            "Frequency must be finite and greater than zero megahertz."
        }
    }
}

/** Channel or occupied bandwidth in megahertz. */
@JvmInline
@Serializable
value class BandwidthMHz(val value: Double) {
    init {
        require(value.isFinite() && value > 0.0) {
            "Bandwidth must be finite and greater than zero megahertz."
        }
    }
}

/** Absolute logarithmic power in dBm. */
@JvmInline
@Serializable
value class PowerDbm(val value: Double) {
    init {
        require(value.isFinite()) { "Power must be finite." }
    }
}

/** Antenna gain relative to an isotropic radiator, in dBi. */
@JvmInline
@Serializable
value class GainDbi(val value: Double) {
    init {
        require(value.isFinite()) { "Gain must be finite." }
    }
}

/** Non-negative attenuation or receiver degradation, in dB. */
@JvmInline
@Serializable
value class LossDb(val value: Double) {
    init {
        require(value.isFinite() && value >= 0.0) {
            "Loss must be finite and cannot be negative."
        }
    }
}

/** Non-negative path or horizontal distance, in kilometers. */
@JvmInline
@Serializable
value class DistanceKm(val value: Double) {
    init {
        require(value.isFinite() && value >= 0.0) {
            "Distance must be finite and cannot be negative."
        }
    }
}

/** Non-negative height above the referenced surface, in meters. */
@JvmInline
@Serializable
value class HeightM(val value: Double) {
    init {
        require(value.isFinite() && value >= 0.0) {
            "Height must be finite and cannot be negative."
        }
    }
}

/** Bearing clockwise from true north in canonical degrees: [0, 360). */
@JvmInline
@Serializable
value class AzimuthDegrees(val value: Double) {
    init {
        require(value.isFinite() && value >= 0.0 && value < 360.0) {
            "Azimuth must be finite and in the range [0, 360) degrees."
        }
    }
}

/** Signed electrical or mechanical tilt in degrees. */
@JvmInline
@Serializable
value class TiltDegrees(val value: Double) {
    init {
        require(value.isFinite() && value in -90.0..90.0) {
            "Tilt must be finite and between -90 and 90 degrees."
        }
    }
}

/**
 * A typed geographic coordinate whose JSON shape remains compatible with a
 * conventional object containing primitive `latitude` and `longitude` values.
 */
@Serializable
data class GeoCoordinate(
    val latitude: LatitudeDegrees,
    val longitude: LongitudeDegrees,
)
