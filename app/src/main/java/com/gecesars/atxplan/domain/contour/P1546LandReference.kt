package com.gecesars.atxplan.domain.contour

import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Compact land-path subset of the P.1546-6 reference tables needed by first-generation FM and
 * digital-TV contours. Values are packaged at 0.01 dB precision and interpolated logarithmically
 * across distance, effective height, and frequency.
 *
 * Source implementation: javaP1546 commit 4d570c2de2d9cb8b27d36b5aefab03c229b5de9d.
 * Only 10% and 50% time, land paths, 100/600/2000 MHz, eight nominal heights, and the 78 official
 * distances are bundled. Unsupported dimensions fail closed.
 */
internal object P1546LandReference {
    const val MIN_EFFECTIVE_HEIGHT_M = 10.0
    const val MAX_EFFECTIVE_HEIGHT_M = 3000.0
    const val UPSTREAM_SOURCE_SHA256 =
        "7ecf708a2d693fbde7a5651184820dbd35f0e7cffa6bbae53d64ef7234128925"
    const val PACKAGED_TABLE_SHA256 =
        "47db8b26cb88efab38d872622a8a08450728dce2b335b365b170b247a999992b"

    val nominalDistancesKm = doubleArrayOf(
        1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0,
        11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0,
        25.0, 30.0, 35.0, 40.0, 45.0, 50.0, 55.0, 60.0, 65.0, 70.0,
        75.0, 80.0, 85.0, 90.0, 95.0, 100.0, 110.0, 120.0, 130.0, 140.0,
        150.0, 160.0, 170.0, 180.0, 190.0, 200.0, 225.0, 250.0, 275.0, 300.0,
        325.0, 350.0, 375.0, 400.0, 425.0, 450.0, 475.0, 500.0, 525.0, 550.0,
        575.0, 600.0, 625.0, 650.0, 675.0, 700.0, 725.0, 750.0, 775.0, 800.0,
        825.0, 850.0, 875.0, 900.0, 925.0, 950.0, 975.0, 1000.0,
    )

    private val nominalFrequenciesMHz = doubleArrayOf(100.0, 600.0, 2000.0)
    private val nominalHeightsM = doubleArrayOf(10.0, 20.0, 37.5, 75.0, 150.0, 300.0, 600.0, 1200.0)
    private val fieldsHundredthDb: ShortArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val bytes = decodeBase64(TABLE_BASE64.filterNot(Char::isWhitespace))
        check(bytes.size == EXPECTED_BYTE_COUNT) {
            "The packaged P.1546 land table has an invalid byte count."
        }
        val tableHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        check(tableHash == PACKAGED_TABLE_SHA256) {
            "The packaged P.1546 land table failed its integrity check."
        }
        ShortArray(bytes.size / 2) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt() and 0xff
            ((high shl 8) or low).toShort()
        }
    }

    fun fieldStrengthDbuvPerM(
        frequencyMHz: Double,
        timePercent: Int,
        effectiveHeightM: Double,
        distanceKm: Double,
        erpKw: Double,
    ): Double {
        require(frequencyMHz.isFinite() && frequencyMHz in 30.0..3000.0) {
            "P.1546 reference frequency must be between 30 and 3000 MHz."
        }
        require(timePercent == 10 || timePercent == 50) {
            "The packaged P.1546 reference supports only 10% or 50% time."
        }
        require(
            effectiveHeightM.isFinite() &&
                effectiveHeightM in MIN_EFFECTIVE_HEIGHT_M..MAX_EFFECTIVE_HEIGHT_M,
        ) {
            "P.1546 effective height must be between 10 and 3000 m."
        }
        require(distanceKm.isFinite() && distanceKm in 1.0..1000.0) {
            "P.1546 reference distance must be between 1 and 1000 km."
        }
        require(erpKw.isFinite() && erpKw > 0.0) {
            "P.1546 ERP must be positive and finite."
        }

        val timeIndex = if (timePercent == 10) 0 else 1
        val frequencyBracket = bracket(nominalFrequenciesMHz, frequencyMHz, extrapolate = true)
        val lowerField = interpolateHeightAndDistance(
            timeIndex = timeIndex,
            frequencyIndex = frequencyBracket.first,
            effectiveHeightM = effectiveHeightM,
            distanceKm = distanceKm,
        )
        val upperField = interpolateHeightAndDistance(
            timeIndex = timeIndex,
            frequencyIndex = frequencyBracket.second,
            effectiveHeightM = effectiveHeightM,
            distanceKm = distanceKm,
        )
        val nominalField = logarithmicInterpolation(
            value = frequencyMHz,
            lowerCoordinate = nominalFrequenciesMHz[frequencyBracket.first],
            upperCoordinate = nominalFrequenciesMHz[frequencyBracket.second],
            lowerValue = lowerField,
            upperValue = upperField,
        )
        val maximumLandField = 106.9 - 20.0 * log10(distanceKm)
        return min(nominalField, maximumLandField) + 10.0 * log10(erpKw)
    }

    private fun interpolateHeightAndDistance(
        timeIndex: Int,
        frequencyIndex: Int,
        effectiveHeightM: Double,
        distanceKm: Double,
    ): Double {
        val heightBracket = bracket(nominalHeightsM, effectiveHeightM, extrapolate = true)
        val lowerHeightField = interpolateDistance(
            timeIndex,
            frequencyIndex,
            heightBracket.first,
            distanceKm,
        )
        val upperHeightField = interpolateDistance(
            timeIndex,
            frequencyIndex,
            heightBracket.second,
            distanceKm,
        )
        return logarithmicInterpolation(
            value = effectiveHeightM,
            lowerCoordinate = nominalHeightsM[heightBracket.first],
            upperCoordinate = nominalHeightsM[heightBracket.second],
            lowerValue = lowerHeightField,
            upperValue = upperHeightField,
        )
    }

    private fun interpolateDistance(
        timeIndex: Int,
        frequencyIndex: Int,
        heightIndex: Int,
        distanceKm: Double,
    ): Double {
        val distanceBracket = bracket(nominalDistancesKm, distanceKm, extrapolate = false)
        return logarithmicInterpolation(
            value = distanceKm,
            lowerCoordinate = nominalDistancesKm[distanceBracket.first],
            upperCoordinate = nominalDistancesKm[distanceBracket.second],
            lowerValue = tableValue(timeIndex, frequencyIndex, distanceBracket.first, heightIndex),
            upperValue = tableValue(timeIndex, frequencyIndex, distanceBracket.second, heightIndex),
        )
    }

    private fun tableValue(
        timeIndex: Int,
        frequencyIndex: Int,
        distanceIndex: Int,
        heightIndex: Int,
    ): Double {
        val index = (
            (
                timeIndex * nominalFrequenciesMHz.size + frequencyIndex
                ) * nominalDistancesKm.size + distanceIndex
            ) * nominalHeightsM.size + heightIndex
        return fieldsHundredthDb[index].toDouble() / 100.0
    }

    private fun bracket(
        coordinates: DoubleArray,
        value: Double,
        extrapolate: Boolean,
    ): Pair<Int, Int> {
        if (value == coordinates.first()) return 0 to 0
        if (value == coordinates.last()) return coordinates.lastIndex to coordinates.lastIndex
        if (value < coordinates.first()) {
            require(extrapolate) { "Reference-table value is below the supported range." }
            return 0 to 1
        }
        if (value > coordinates.last()) {
            require(extrapolate) { "Reference-table value is above the supported range." }
            return coordinates.lastIndex - 1 to coordinates.lastIndex
        }
        val upper = coordinates.indexOfFirst { coordinate -> coordinate > value }
        check(upper > 0) { "Could not bracket the reference-table value." }
        return upper - 1 to upper
    }

    private fun logarithmicInterpolation(
        value: Double,
        lowerCoordinate: Double,
        upperCoordinate: Double,
        lowerValue: Double,
        upperValue: Double,
    ): Double {
        if (lowerCoordinate == upperCoordinate) return lowerValue
        return lowerValue + (upperValue - lowerValue) *
            log10(value / lowerCoordinate) / log10(upperCoordinate / lowerCoordinate)
    }

    private fun decodeBase64(encoded: String): ByteArray {
        require(encoded.length % 4 == 0) { "The packaged table encoding is malformed." }
        val output = ByteArray(encoded.length / 4 * 3)
        var outputIndex = 0
        encoded.chunked(4).forEach { block ->
            val a = base64Value(block[0])
            val b = base64Value(block[1])
            val c = if (block[2] == '=') 0 else base64Value(block[2])
            val d = if (block[3] == '=') 0 else base64Value(block[3])
            val bits = (a shl 18) or (b shl 12) or (c shl 6) or d
            output[outputIndex++] = (bits shr 16).toByte()
            if (block[2] != '=') output[outputIndex++] = (bits shr 8).toByte()
            if (block[3] != '=') output[outputIndex++] = bits.toByte()
        }
        return output.copyOf(outputIndex)
    }

    private fun base64Value(character: Char): Int = when (character) {
        in 'A'..'Z' -> character - 'A'
        in 'a'..'z' -> character - 'a' + 26
        in '0'..'9' -> character - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> throw IllegalArgumentException("The packaged table encoding contains an invalid character.")
    }

    private const val EXPECTED_BYTE_COUNT = 2 * 2 * 3 * 78 * 8
    private const val TABLE_BASE64 = """
JiMCJPgkCyYwJ0goHCmMKVwfdSCYIdkiMySMJZ4mLSf5HEkekh/zIG4i7SMmJcklKBuoHBYekx8pIcMiGSTNJKoZUBvhHHoeJyDaIUgjCSRkGCga1xuMHVAf
GSGcImkjRhchGewauhyVHnIgCiLhIkgWNRgWGvwb7R3fH4khbCJkFV0XURlNG1MdWR8XIQQilBSYFpsYqhrEHN4eryCnIdYT4RXxFxAaPBxqHk4gUiE2EzwV
VxeBGbwb/B31HwUhqBKvFM0W/xhDG5Mdnx+9ICUSKxRLFoQY0hotHU4feiCrEbAT0hUPGGYazRwAHzsgOhE9E18VoBf+GXEctB7/H9AQ0RLyFDUXmhkXHGoe
xh9uEGsSixTPFjkZwBskHpAfERALEikUbhbbGGob3x1dH7oPsBHLExAWgBgXG5sdLB9LDioQMhJrFNwWiRlSHEseNA33DuYQCRNtFR4YEht9HVwMAA7RD9sR
KhTSFt4ZthywCzUN6Q7XEA0TpRW9GO4bJwuNDCMO9A8REpQUuxcjG7cKAAx5DS8PMBGdE8YWVRpaCogL5gyADmgQvRLcFYgZCwofC2QM5g20D/ER+BTCGMYJ
wwrxC1sNEg84ERoUChiHCXAKiQveDIAOjxBQE1MXTgkkCisLbAz5DfQPnxKbFhkJ3gnTCgMMfg1kD/oR4hXlCJsJgQqhCwsN3g5fESgVsghbCTQKRQufDGEO
zRB3FIAIHQnpCe4KOgzsDUQQ2RNOCOAIogmaCtoLfA3CD0AT6QdpCBcJ/AklC60M0A4hEoEH8geRCGUJewrtC/INFxEWB3sHDQjTCNoJOQsjDR4QqAYDB4oH
RAg/CY4KYAw1DzgGiQYHB7gHqAjqCacLWQ7FBQ8GhQYuBxUISwn3CokNUQWVBQQGpgaFB7EITgrEDNwEGgWDBR8G+AYbCKsJBwxmBJ8EBAWbBW0GigcNCVML
8AMlBIUEGAXmBfsGdQimCssC+AJQA9oDngSnBQwHEwmtAdQBJgKqAmcDZgS8BaMHlwC6AAgBhwE/AjgDggRQBoz/rP/2/3IAJgEZAloDFAWK/qj+8P5p/xoA
CQFDAu0Dkv2u/fP9a/4Z/wUAOQHXAqL8vPwA/Xb9Iv4L/zsAzgG4+9H7FPyI/DP9Gf5F/9EA0/rr+i37oPtJ/C79V/7b//L5CfpK+rz6ZPtI/G797P4T+Sr5
avnb+YL6ZfuJ/AL+NvhL+Iv4/Pii+YP6pvsb/Vn3bvet9x34w/ij+cT6Nfx79pD2zvY+9+P3wvji+VD7nPWw9e/1XvYD9+H3//hr+rz00PQO9Xz1Ifb/9hz4
hfna8+7zK/Sa9D31G/Y395749vIK80fztfNZ9DX1Ufa29xHyJPJh8s/ycvNP9Gn1zfYr8T7xe/Ho8YvyZ/OB9OP1RPBX8JTwAfGk8YDymfP59F7vce+t7xrw
vfCY8bHyEPR57ovuyO4179fvsvDL8Snzlu2o7eXtUu707s/v5/BD8rfsyewF7XLtFO7v7gbwYvHc6+7rKuyX7DjtE+4q74XwB+sZ61Xrwetj7D3tVO6u7znq
SuqG6vPqlOtu7IXt3+5y6YTpwOks6s3qp+u+7BfutejG6ALpbukQ6unqAOxY7QHoE+hO6LroW+k16kvro+xY52nnpecR6LLojOmh6vnrPyQRJewl8ib7J9so
aSmnKQQgGyEpImsjwCT/Jd8mQyc9HaUe4h9NIcwiQSRaJdwlFhvFHDEewR9hIf0iQiTcJFYZORvQHIUeQiD9IWUjFSTfF+UZoRt4HVEfKCGvInIjnxa7GJQa
jByAHm8gEiLoIocVsheiGbcbxB3MH4ghbyKQFMUWxhjzGhkdOB8NIQUitBPvFfwXPhp6HK8enCClIe0SLBVCF5QZ5hswHjQgTyE4EnkUlhb2GFkbuB3TH/8g
khHUE/YVYBjTGkUddx+1IPoQPBNgFdIXVBrXHCAfcCBtEK4S1BRMF9kZbhzLHi8g6w8qElAUzRZkGQgcex7yH3EPrhHUE1QW8xikGywetx8ADzoRXhPgFYYY
RBvfHYAflg7MEO8SchUdGOUalB1KHzIOZRCGEggVtxeJGksdFx+TDKwOuxAzE+cV2hjqGyweWAtRDUYPpxFSFFEXqBpXHWIKNwwMDlAQ5xLlFXEZmByfCUwL
/wwiD54RkRQ4GNobAQmGChYMFA5xEFMT/hYUG30I2wlJCyMNYA8qEsYVQRoNCEcJlApLDGYOFRGeFGEZqwfECPQJiQuEDRUQjhNzGFUHUAhkCdsKtwwpD4wS
fBcGB+cH4wg+CvwLUA6YEYAWvQaHB20IrwlSC4kNtBCDFXgGLgcBCC0JtwrRDN4PjRQ3BtoGnQe1CCkKKQwWD68T9wWLBj8HRQimCY0LWw7ZErgFPgbmBt0H
LAn8Cq0NDBJ6BfQFkQZ7B7kIdQoJDUcR/gRmBfAFwwbnB38J3wvWD4ME2wRXBRkGKAejCNUKhw4GBFMEwwR4BXYG2QflCVQNiAPMAzME3QTNBR0HCAk8DAkD
RQOmA0cELAVsBjwIOguJAsACGgO0A5AEwwV8B0sKCAI6ApACJAP5AyIFxwZtCYgBtgEIApcCZgOGBBoGnAgIATMBggENAtcC7gN1BdgHiACwAP0AhAFKAlwD
1wQeB0//cv+5/zoA+AD+AWIDcwUe/j7+gv7//rj/tQAJAvMD+vwX/Vj90/2H/n7/yACVAuH7/Ps8/LT8Zv1Z/pr/UgHU+u76Lfuj+1P8Q/1+/iYA0/ns+Sr6
n/pN+zr8cP0M/9z49Pgx+aX5Uvo9+3D8Af7t9wX4Qfi1+GH5S/p6+wT9Bvce91n3zfd4+GD5jfoR/Cb2PfZ49uv2lfd9+Kj5JvtK9WH1nPUO9rj2n/fI+EP6
cvSI9MP0NfXf9cX27fdk+ZzzsvPs8170CPXt9RT3iPjH8t3yF/OJ8zL0F/U99q738vEI8kLytPJc80H0ZvXW9h3xM/Ft8d7xh/Jr84/0/fVG8FzwlvAH8a/x
k/K38yP1bu+D773vLvDW8Lrx3vJI9JPuqe7i7lPv++/f8ALya/O27cvtBe527h7vAfAk8Yzy1+zs7Cbtlu0+7iHvRPCr8fbrC+xE7LXsXe1A7mLvyfAT6yjr
YevS63rsXe1+7uTvL+pE6n7q7uqW63nsmu0A70zpYema6QrqsuqV67bsG+5p6H7ouOgo6dDpsurT6zjtieee59jnSOjv6NLp8+pX7K3mwub75mvnE+j16Bbq
euvV5erlJOaU5jvnHeg+6aHqBOUZ5VLlwuVq5kznbOjP6TrkT+SI5PjkoOWC5qPnBel5447jyOM45N/kweXh5kTozySzJYomgydvKCQpiSmxKTMgjyGsIvAj
OCVWJgknUCcaHeoeSyDCIT4jnCSJJekltRrZHIAeJyDMIVgjdCTrJMgYHRsBHd0epSBXIpkjJCQtF54ZsxvBHasffyHkIoIjzBVOGIcaxBzTHsQgSSL4IpkU
Ixd3Gd0bER4dIMAhgCKKExYWfhgGG14dhx9FIRcilxInFZkXPRq3HP0e1SC4IbwRTxTFFoAZGBx8Hm4gYSH0EIoTARbNGIEbAh4OIBIhPhDUEkoVJBjwGo0d
tR/JIJYPLBKiFIQXZBocHV8fhCD7DpERCBTsFt0ZrxwOH0Ugag4AEXgTWxZbGUQcvx4JIOQNeRDwEtEV3RjcG3Ie0B9nDfoPcRJQFWQYdRsnHpof8gyDD/kR
2RTuFxEb3h1nH4QMEg+HEWgUfBeuGpUdNR+5CjUNnQ93EogV2xgyHFQeXQm+CxEO2RDlEzwX1xqIHU4IjgrEDHIPbxLKFYEZwxx3B48JowsxDhYRbhRFGPwb
ywa2CKQKCw3TDx8TDhctGz0G+gfACf0LpA7dEdoVWRrHBVYH9AgHC4cNphCnFHoZYwXHBj0IJgp/DH8PehOPGA0FSQaaB1oJjAtqDlQSmBfBBNkFCQejCK4K
Zw05EZoWfgR1BYcG/wfmCXkMKxCZFUAEGwUSBmwHMQmeCy0PlxQHBMkEqQXnBo4I1wo/DpkT0AN+BEoFcAb6ByIKYQ2hEpwDOATyBAMGdQd9CZQMsRFqA/UD
oQSfBfsG5wjXC8sQCAN6Aw0E7QQlBt8HhgofD6YCBQOGA1AEawX+BmUJng1CApQCBwO/A8UEOAZqCEcM3QEkAowCNwMsBIYFjAcVC3YBtAEUArUCnAPiBMQG
AwoMAUQBngE2AhMDSAQNBgsJoADSACcBuQGNArYDYgUpCDIAYACxAD0BCwIpA8EEWQfC/+3/OgDDAIsBoAInBJcGUf95/8P/SQAMARsClAPiBTP+Vv6b/hr/
1f/XADcCRAQU/TP9df3w/ab+n//uAM8C9/sT/FP8y/x+/XL+tf93Ad/6+fo4+677XvxO/Yr+NQDO+eb5JPqZ+kf7NPxq/Qb/w/jb+Bf5jPk5+iT7Vfzl/cD3
2PcT+If4M/kd+kv70vzF9tz2F/eK9zb4HvlK+sr70fXn9SL2lfZA9yf4UfnM+uP0+fQ09ab1UPY392D41vn78xD0S/S99Gf1TfZ09+j4F/Ms82fz2POC9Gj1
jvb/9zbyTPKG8vjyofOH9Kz1GvdZ8W7xqPEa8sPyqPPN9Dn2ffCS8MzwPfHm8cvy7/Na9aHvt+/x72LwC/Hv8RPzffTG7tvuFe+G7y/wE/E38p/z6u3/7Tju
qe5S7zbwWfHB8gztIe1b7cztdO5Y73vw4vEt7EHse+zs7JXtee6b7wLxS+tg65rrC+yz7Jftuu4f8Gnqfeq36ijr0Ou07NbtPO+E6Znp0+lE6uzq0Ovy7Ffu
oOi06O7oX+kH6uvqDexx7bvn0OcK6HroI+kG6ijrjOzY5u3mJ+eY50DoI+lF6qnr+OUN5kfmuOZg50PoZenI6h3lMuVs5dzlheZo54ro7elI5F3kl+QH5bDl
k+a05xfpe+OQ48rjOuTi5Mbl5+ZK6LjizOIG43bjH+QC5STmhuf/4RTiTeK+4mbjSuRr5c3mJiMCJPgkCiYwJ0goHCmMKVwfdSCYIdkiMySMJZ4mLCf5HEke
kh/zIG4i7SMmJcklKBunHBUekx8pIcMiGSTNJKoZUBvgHHoeJyDaIUgjCSRkGCga1xuMHVAfGSGcImkjRhchGewauhyVHnIgCiLhIkgWNRgWGvwb7R3fH4kh
bCJkFV0XURlNG1MdWR8XIQQilBSYFpsYqRrEHN4eryCnIdYT4RXxFxAaPBxqHk4gUiEnEzgVURd+Gbwb/B30HwUhhBKaFLwW9BhAG5Mdnx+9IO0RBhQuFnAY
yhotHU4feiBfEXsTqBXyF1gayhwAHzsg2hD4EioVehfqGWoctB7/H10QfBKxFAgXfxkNHGkexh/nDwcSPxSaFhkZsRshHpAfdw+YEdITMBa1GFgb2R1cHwwP
LhFqE8sVVRgBG5IdKR8/DV8PnhEHFKAWahk9HD8eygvmDSEQihIoFQAY/BplHZYKqQzdDkER3hO7FtIZkhyUCZoLxA0fELYSlRW9GMQbuAiwCssMGQ+oEYUU
uxf8Gv0H4QnrCyoOrRCGE8YWOhpaByoJHwtLDb8PkhLcFXwZzQaFCGMKegzeDqgR+BTCGFIG8Ae1CbULBQ7EEBoUChjkBWgHEwn7CjUN5g8/E1MXgwXrBnwI
SgpuDA8PZxKbFisFeQbvB6MJrws+DpER4hXcBA8GawcFCfoKdA3AECgVkgStBfAGcQhNCrIM8g9sFE4EUQV8BuYHqgn5CyoPsBMPBPwEEAZjBxAJSAtoDvQS
mQNfBEwFdgb4BwIK+Ax/ESwD0gOdBKQFAAfgCKYLExDEAk8DAATqBCUG3gdzCrcOXwLVAm8DQQRhBfgGXgltDfsBXwLoAqcDsAQpBmQIOQyYAe4BaAIXAw4E
bQWBBxsLNAF/Ae0BjwJ2A8AEswYSCtAAEQF2AQ0C6AIgBPQFHQlrAKUAAQGQAWACigNEBToIBgA5AI8AFwHeAfsCnwRoBwf/L/95//T/qwCwASoDlQUJ/in+
bP7e/or/gADdAQIEDv0p/Wb90v14/mP/qwCfAhj8L/xo/NH8cf1V/o//XgEn+zz7c/vY+3X8VP2D/jcAPvpQ+oX66fqD+178hv0l/1v5bPmf+QL6mvpy+5T8
JP5++I74wfgi+bn5jvqt+zH9qPe29+j3Sfjf+LL5zfpI/Nb25PYV93X3Cvjc+PX5aPsI9hb2R/am9jr3C/gi+Y/6PfVL9Xv12vVu9j73U/i8+XX0gvSy9BD1
pPVz9ob37Pit87rz6vNH9Nv0qvW89h745fLy8iLzf/MS9OH08vVS9x3yKvJZ8rfySfMX9Cj1hfZU8WDxj/Ht8X/yTfNd9Lj1iPCU8MTwIfGz8YHykPPq9Lrv
x+/271Pw5fCy8cHyGvTq7vbuJe+C7xTw4fDw8UfzF+4j7lLur+5B7w7wHPFz8kLtTu197drtbO4470bwnPFr7HfspuwC7ZTtYe5u78Twk+ue683rKuy77Ijt
le7q77nqxer06lDr4uuv7LztEO/h6ezpG+p36gnr1evi7DbuCekV6UPpoOkx6v7qCuxe7TToQOhv6MvoXekp6jXriOxk52/nnuf654zoWOlk6rfrmOak5tLm
L+fA54zomenr6tPl3+UO5mrm++bH59ToJuoX5SLlUeWt5T7mC+cX6GjpNCQPJesl8ib7J9soaSmnKa8f7SAFIkwjpCTrJdYmQCe0HFkeqR8aIZ4iHiRIJdcl
cRpkHOodgx8oIc8iKCTVJKIYyBp+HD4eACDGIUUjDCQjF2kZRxstHQof6iCJImcj3RU3GDYaPhw1Hi0g5yHbIsEUKRdAGWcbeR2GH1ohYSLHEzgWYRijGs4c
8B7bIPUh5xJeFZUX7xkxHGgeaCCVIR4SmBTZFkYZnhvqHf8fPSFmEeITKxaoGBUbdB2dH+wgvhA7E4kVExiSGgQdQR+hICQQoBLxFIYXFhqaHOseXCCUDxAS
YxQBF58ZNRyZHhsgDw+JEd4TghYtGdMbSh7dH5MOCxFfEwgWvxh1G/8dox8fDpQQ6BKUFVQYGRu2HWwfsg0kEHcSJRXtF8Aabx03H0wNuQ8LErsUiRdpGiod
BR+aC/ANMRDcErsVzBjiGyEeSgqADKgOQBEeFEoXqBpXHTwJTQtXDdQPoxLaFXEZmBxdCEYKLgyKDkMReBQ4GNoboQdhCSYLXg37DyMT/hYUG/8Glwg3CkoM
xw7dEcYVQRpxBuEHXwlOC6kNpxCSFGEZ8QU+B5sIZgqfDIIPZhNzGH0FqAbnB5EJqQtuDkMSfBcSBSAGQwfNCMYKaw0sEYAWrgShBasGGQj0CXoMIRCDFVAE
KgUfBnMHMgmZCyMPhhT2A7sEnAXZBn4IyAozDo4TnwNSBCEFSQbXBwUKUA2aEksD7gOtBMMFPAdQCXoMrhH5Ao4DPwRFBaoGpgixC8gQWwLXAnIDXAShBXEH
QAoWD8IBLAK1AokDswReBvYIhA0sAYgBAwLFAtoDZwXOBxMMmgDqAFoBDgIRA4UEwgbACgoAUQC3AGEBVQK0A80FiQl9/7z/GwC7AKQB8gLsBGsI8v4q/4T/
HAD8ADsCGgRjB2n+nP7x/oP/WgCNAVYDbQbi/RL+Yv7v/r//6ACdAokFXv2K/df9X/4q/0oA7gGzBB38QfyI/An9yP3X/lsA0QLp+gn7S/vG+378gv3u/ioB
wvnf+R76lfpI+0P8nv2u/6f4wvj/+HP5IvoW+2X8VP6X97D36/dd+An5+fk/+xX9kfap9uL2U/f99+n4J/rp+5P1qvXj9VL2+vbk9xz5z/qd9LP06/RZ9QD2
5/Yb+MH5rfPC8/rzZ/QN9fL1I/e/+MLy1/IO83rzH/QE9TH2xffc8fHxJ/KT8jfzGvRF9dL2+vAO8UTxr/FT8jXzXvTl9RrwLvBj8M7wcvFT8nvz/fQ971Dv
hu/w75PwdPGa8hj0Yu517qruFe+375fwvPE284jtm+3Q7Tru3O687+DwV/Kw7MLs9+xh7QPu4u4F8Hrx2Ovr6yDsiuwr7QruLO+f8ALrFOtJ67PrVOwz7VTu
xe8s6j/qc+rd6n7rXOx97ezuWOlq6Z7pCOqp6ofrp+wV7oTolujK6DTp1emy6tLrP+2x58Pn+Odh6ALp3+n/6mrs4Oby5ibnj+cw6A3pLOqW6xDmIuZW5r/m
YOc96FvpxepB5VPlh+Xw5ZHmbueM6PXpdeSH5LvkJOXE5aHmv+cn6arjvOPw41nk+uTX5fTmW+jj4vXiKeOR4zLkD+Us5pLnHuIw4mTizeJt40nkZ+XN5lzh
buGi4Qviq+KH46XkCuae4LDg5OBN4e3hyeLm40vlzySzJYomgydvKCQpiSmxKTMgjyGsIu0jNCVUJggnTycaHeoeSyDAITkjmCSHJekltRrZHIAeJyDHIVMj
cSTqJMMYHRsBHd0eoiBRIpYjIyQhF54ZsxvBHasfeiHgIoEjuxVOGIcaxBzTHsEgRSL3IoIUIxd3Gd0bER4cIL0hfyJtExYWfhgGG14dhx9CIRUidBIkFZkX
PRq3HP0e1CC2IZIRRhTFFoAZGBx8Hm0gYCHEEHsTARbNGIEbAh4OIBEhBxC/EkoVJBjwGo0dtR/IIFgPERKfFIQXZBocHV8fhCC1Dm4R/xPsFt0ZrxwOH0Ug
Hg7WEGgTWxZbGUQcvx4JIJANRxDaEtEV3RjcG3Ie0B8KDcAPUxJOFWQYdRsnHpofjQxAD9MR0BTuFxEb3h1nHxcMxw5ZEVcUfBeuGpUdNR8dCrwMQQ88EnMV
2xgyHFQekAgUC4QNcBCnEywX1xqIHVEHsAkEDNcOBBKYFYEZwxxLBn8IrwpjDX0QFxQwGPwbcgV2B30JCwwLD6IS4hYtG7sEjQZpCMsKrA05EZUVUxofBL8F
bwelCWAM2g9KFGwZmAMIBY4GlggqC4kOARN4GCMDZwTEBaAHCgpJDb0ReRe6AtcDEAXDBgIJHAyCEHEWXQJXA24E+wUSCAQLUQ9jFQgC4wLeA0gFOAcBCi0O
UxS6AXoCWwOnBHQGEwkZDUMTcAEaAuUCFQTCBToIFQw1EisBwQF5ApIDIQVzByILLBHpAG4BFgIZA48EvgY/CikQagDVAGIBRAKOA4AFqQg9DvD/SADBAIkB
sgJyBEsHegx4/8H/LADhAO8BhwMbBuAKAf8//5//RQA/AbYCEAVvCYn+v/4W/7H/mwD4ASIEIggR/kD+kf4i/wAASQFKA/YGmP3C/Q3+mP5r/6QAhALmBR79
RP2L/RD+3P4HAMwB7gSk/Mb8Cv2K/VD+cP8eAQkEKfxJ/Ir8B/3H/d7+egA1A/f6EftO+8T7e/yC/fv+WwHI+eD5GfqL+jz7OPyb/bz/n/i1+O34XPkJ+v76
UfxH/n/3k/fJ9zf44fjR+Rr78fxo9nv2sPYc98T3svjy+bP7WvVt9aH1DPaz9p332fiI+lX0aPSb9Ab1q/WU9sv3bvlZ82vzn/MI9K30lPXI9mL4ZfJ28qry
E/O38530z/Vh93fxifG88STyyPKu8930avaQ8KHw0/A88d/xxPLy83r1rO+97/DvWPD78N/xDPOQ9M3u3e4Q73jvG/D/8Cryq/Pw7QDuM+6b7j3vIfBM8cry
Fe0l7Vftv+1i7kXvb/Ds8TrsS+x97OXsh+1q7pTvDvFg63Hro+sL7K3skO257jLwhuqX6snqMevT67Xs3u1W76zpvOnu6Vbq+Ora6wPteu7R6OHoE+l76R3q
/+on7J3t9ecF6Dfon+hA6SPqSuvA7BjnKedb58LnZOhG6W7q4us85kzmfubm5ofnaeiR6QXrYOVw5aLlCuar5o3ntOgo6oXkluTH5C/l0eWy5tnnTemt473j
7+NW5Pjk2eUB53Po1+Ln4hnjgeMi5ATlK+ad5wbiFuJI4q/iUeMy5Fnly+Y64Urhe+Hj4YTiZuON5P7ldOCE4LbgHeG/4aDix+M45bXfxt/331/gAOHi4Qjj
eeQA3xDfQd+p30rgLOFS4sPj
"""
}
