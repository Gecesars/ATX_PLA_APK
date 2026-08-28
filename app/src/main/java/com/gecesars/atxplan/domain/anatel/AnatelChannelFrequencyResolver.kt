package com.gecesars.atxplan.domain.anatel

import kotlin.math.abs

/**
 * Resolves the planning frequency without replacing an exact source value.
 *
 * A positive finite source frequency attribute always wins, even when it differs from the nominal
 * channel centre. The fallback is used only when that attribute is absent or invalid:
 *
 * - FM channels 141–197 and 200–300: `87.9 + 0.2 * (channel - 200)` MHz.
 * - TV channels 2–4: 57, 63 and 69 MHz.
 * - TV channels 5–6: 79 and 85 MHz.
 * - TV channels 7–13: `177 + 6 * (channel - 7)` MHz.
 * - TV channels 14–69: `473 + 6 * (channel - 14)` MHz.
 *
 * These values are channel-centre recovery metadata, not a declaration that every historical
 * channel remains assignable under current regulation.
 */
object AnatelChannelFrequencyResolver {
    private const val MAX_REASONABLE_FREQUENCY_MHZ = 1_000_000.0

    fun resolve(
        service: AnatelBroadcastService,
        sourceFrequencyRaw: String,
        channelRaw: String,
    ): AnatelResolvedFrequency {
        requireBoundedRawText(sourceFrequencyRaw, "An Anatel raw source frequency")
        requireBoundedRawText(channelRaw, "An Anatel raw channel")
        val sourceFrequency = sourceFrequencyRaw.trim().toDoubleOrNull()
            ?.takeIf { value ->
                value.isFinite() && value > 0.0 && value <= MAX_REASONABLE_FREQUENCY_MHZ
            }
        if (sourceFrequency != null) {
            return AnatelResolvedFrequency(
                frequencyMHz = sourceFrequency,
                origin = AnatelFrequencyOrigin.SOURCE_ATTRIBUTE,
                sourceFrequencyRaw = sourceFrequencyRaw,
                explanation = "The exact positive finite source frequency attribute was used.",
            )
        }

        val channel = parseChannel(channelRaw)
        val fallback = channel?.let { channelNumber ->
            when (service) {
                AnatelBroadcastService.FM -> fmCenterFrequencyMHz(channelNumber)
                AnatelBroadcastService.TELEVISION -> tvCenterFrequencyMHz(channelNumber)
                AnatelBroadcastService.UNKNOWN -> null
            }
        }
        return if (fallback != null) {
            AnatelResolvedFrequency(
                frequencyMHz = fallback,
                origin = AnatelFrequencyOrigin.CHANNEL_FALLBACK,
                sourceFrequencyRaw = sourceFrequencyRaw,
                explanation =
                    "The source frequency was absent or invalid; the documented channel-centre fallback was used.",
            )
        } else {
            AnatelResolvedFrequency(
                frequencyMHz = null,
                origin = AnatelFrequencyOrigin.NO_DATA,
                sourceFrequencyRaw = sourceFrequencyRaw,
                explanation =
                    "No positive source frequency or supported integer channel fallback is available.",
            )
        }
    }

    fun parseChannel(channelRaw: String): Int? {
        requireBoundedRawText(channelRaw, "An Anatel raw channel")
        val trimmed = channelRaw.trim()
        if (!trimmed.matches(Regex("\\d{1,3}(?:\\.0+)?"))) return null
        val numeric = trimmed.toDoubleOrNull() ?: return null
        val integer = numeric.toInt()
        return integer.takeIf { value -> abs(numeric - value.toDouble()) <= 1.0e-9 && value in 1..999 }
    }

    /** Resolves the mixed ECRD code only when source frequency and channel agree exactly. */
    internal fun serviceFromExactChannelEvidence(
        sourceFrequencyRaw: String,
        channelRaw: String,
    ): AnatelBroadcastService {
        requireBoundedRawText(sourceFrequencyRaw, "An Anatel raw source frequency")
        requireBoundedRawText(channelRaw, "An Anatel raw channel")
        val sourceFrequency = sourceFrequencyRaw.trim().toDoubleOrNull()
            ?.takeIf { value ->
                value.isFinite() && value > 0.0 && value <= MAX_REASONABLE_FREQUENCY_MHZ
            } ?: return AnatelBroadcastService.UNKNOWN
        val channel = parseChannel(channelRaw) ?: return AnatelBroadcastService.UNKNOWN
        val evidence = listOf(
            AnatelBroadcastService.FM to fmCenterFrequencyMHz(channel),
            AnatelBroadcastService.TELEVISION to tvCenterFrequencyMHz(channel),
        ).filter { (_, expectedFrequency) ->
            expectedFrequency != null && abs(sourceFrequency - expectedFrequency) <= EVIDENCE_TOLERANCE_MHZ
        }
        return evidence.singleOrNull()?.first ?: AnatelBroadcastService.UNKNOWN
    }

    private fun fmCenterFrequencyMHz(channel: Int): Double? =
        channel.takeIf { value -> value in 141..197 || value in 200..300 }
            ?.let { value -> 87.9 + (value - 200) * 0.2 }

    private fun tvCenterFrequencyMHz(channel: Int): Double? = when (channel) {
        2 -> 57.0
        3 -> 63.0
        4 -> 69.0
        5 -> 79.0
        6 -> 85.0
        in 7..13 -> 177.0 + (channel - 7) * 6.0
        in 14..69 -> 473.0 + (channel - 14) * 6.0
        else -> null
    }

    private const val EVIDENCE_TOLERANCE_MHZ = 1.0e-6
}
