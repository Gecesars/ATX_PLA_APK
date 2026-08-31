package com.gecesars.atxplan.domain.contour

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class P526AssisObstacle(
    val profileIndex: Int,
    val distanceM: Double,
    val effectiveHeightM: Double,
    val referenceLineHeightM: Double,
    val clearanceM: Double,
    val fresnelParameter: Double,
    val knifeEdgeLossDb: Double,
    val radiusOfCurvatureM: Double?,
    val roundedCorrectionDb: Double,
) {
    val lossDb: Double
        get() = knifeEdgeLossDb + roundedCorrectionDb
}

data class P526AssisResult(
    val lossDb: Double,
    val obstacles: List<P526AssisObstacle>,
    val warnings: List<String>,
)

/** ITU-R P.526-15 rounded-obstacle correction with Deygout–Assis, at most three obstacles. */
object P526DeygoutAssis {
    const val MODEL_ID = "atx.diffraction.p526-15-deygout-assis-1971.v1"
    const val MAXIMUM_OBSTACLES = 3
    private const val SPEED_OF_LIGHT_M_PER_S = 299_792_458.0

    fun calculate(
        distancesM: List<Double>,
        effectiveHeightsM: List<Double>,
        frequencyMHz: Double,
        maximumObstacles: Int = MAXIMUM_OBSTACLES,
    ): P526AssisResult {
        require(distancesM.size == effectiveHeightsM.size && distancesM.size >= 2) {
            "P.526 plus Assis requires at least two corresponding profile nodes."
        }
        require((distancesM + effectiveHeightsM).all(Double::isFinite)) {
            "P.526 plus Assis profile nodes must be finite."
        }
        require(kotlin.math.abs(distancesM.first()) <= 1e-9) {
            "The first P.526 plus Assis profile node must be at the origin."
        }
        require(distancesM.zipWithNext().all { (previous, current) -> current > previous }) {
            "P.526 plus Assis profile distances must be strictly increasing."
        }
        require(frequencyMHz.isFinite() && frequencyMHz > 0.0) {
            "P.526 plus Assis frequency must be positive and finite."
        }
        require(maximumObstacles in 1..128) {
            "The P.526 plus Assis obstacle limit must be between 1 and 128."
        }
        if (distancesM.size < 3) return P526AssisResult(0.0, emptyList(), emptyList())

        val wavelengthM = SPEED_OF_LIGHT_M_PER_S / (frequencyMHz * 1_000_000.0)
        val pending = ArrayDeque<Pair<Int, Int>>().apply {
            addLast(0 to distancesM.lastIndex)
        }
        val obstacles = mutableListOf<P526AssisObstacle>()
        val warnings = mutableListOf<String>()
        var truncated = false
        while (pending.isNotEmpty() && obstacles.size < maximumObstacles) {
            val (start, end) = pending.removeLast()
            if (end - start < 2) continue
            val edge = dominantObstacle(distancesM, effectiveHeightsM, wavelengthM, start, end)
                ?: continue
            if (edge.clearanceM <= 0.0) continue
            val index = edge.profileIndex
            val radiusM = localRadiusM(
                distancesM = distancesM,
                heightsM = effectiveHeightsM,
                index = index,
                fresnelParameter = edge.fresnelParameter,
            )
            if (radiusM == null) {
                warnings += "Obstacle at profile node $index has no resolvable convex curvature and was treated as a knife edge."
            }
            val correctionDb = radiusM?.let { radius ->
                roundedObstacleCorrectionDb(
                    radiusOfCurvatureM = radius,
                    clearanceM = edge.clearanceM,
                    distance1M = distancesM[index] - distancesM[start],
                    distance2M = distancesM[end] - distancesM[index],
                    wavelengthM = wavelengthM,
                )
            } ?: 0.0
            obstacles += edge.copy(
                radiusOfCurvatureM = radiusM,
                roundedCorrectionDb = correctionDb,
            )
            if (obstacles.size >= maximumObstacles) {
                truncated = pending.isNotEmpty() || index - start >= 2 || end - index >= 2
                break
            }
            pending.addLast(index to end)
            pending.addLast(start to index)
        }
        if (truncated) {
            warnings += "Deygout–Assis was limited to the $maximumObstacles dominant regulatory obstacles."
        }
        val ordered = obstacles.sortedBy(P526AssisObstacle::distanceM)
        return P526AssisResult(
            lossDb = ordered.sumOf(P526AssisObstacle::lossDb),
            obstacles = ordered,
            warnings = warnings.distinct(),
        )
    }

    fun knifeEdgeLossDb(fresnelParameter: Double): Double {
        require(fresnelParameter.isFinite()) { "The Fresnel parameter must be finite." }
        if (fresnelParameter <= -0.78) return 0.0
        return 6.9 + 20.0 * log10(
            sqrt((fresnelParameter - 0.1).pow(2.0) + 1.0) + fresnelParameter - 0.1,
        )
    }

    fun roundedObstacleCorrectionDb(
        radiusOfCurvatureM: Double,
        clearanceM: Double,
        distance1M: Double,
        distance2M: Double,
        wavelengthM: Double,
    ): Double {
        require(
            listOf(
                radiusOfCurvatureM,
                clearanceM,
                distance1M,
                distance2M,
                wavelengthM,
            ).all(Double::isFinite),
        ) { "Rounded-obstacle parameters must be finite." }
        require(
            radiusOfCurvatureM > 0.0 && distance1M > 0.0 &&
                distance2M > 0.0 && wavelengthM > 0.0,
        ) { "Rounded-obstacle radius, distances, and wavelength must be positive." }
        if (clearanceM <= 0.0) return 0.0
        val normalizedRadius = (Math.PI * radiusOfCurvatureM / wavelengthM).pow(1.0 / 3.0)
        val m = radiusOfCurvatureM * (distance1M + distance2M) /
            (distance1M * distance2M) / normalizedRadius
        val n = clearanceM / radiusOfCurvatureM * normalizedRadius.pow(2.0)
        var correctionDb = 7.2 * sqrt(m) - (2.0 - 12.5 * n) * m
        correctionDb += 3.6 * m.pow(1.5) - 0.8 * m.pow(2.0)
        if (m * n > 4.0) {
            correctionDb += -6.0 - 20.0 * log10(m * n) + 4.5 * n * m
        }
        return max(0.0, correctionDb)
    }

    private fun dominantObstacle(
        distancesM: List<Double>,
        heightsM: List<Double>,
        wavelengthM: Double,
        start: Int,
        end: Int,
    ): P526AssisObstacle? {
        val segmentM = distancesM[end] - distancesM[start]
        if (segmentM <= 0.0 || end - start < 2) return null
        var dominant: P526AssisObstacle? = null
        for (index in start + 1 until end) {
            val distanceFromStartM = distancesM[index] - distancesM[start]
            val distanceToEndM = distancesM[end] - distancesM[index]
            val lineHeightM = heightsM[start] +
                (heightsM[end] - heightsM[start]) * distanceFromStartM / segmentM
            val clearanceM = heightsM[index] - lineHeightM
            val fresnelParameter = clearanceM * sqrt(
                2.0 * segmentM /
                    (wavelengthM * distanceFromStartM * distanceToEndM),
            )
            val candidate = P526AssisObstacle(
                profileIndex = index,
                distanceM = distancesM[index],
                effectiveHeightM = heightsM[index],
                referenceLineHeightM = lineHeightM,
                clearanceM = clearanceM,
                fresnelParameter = fresnelParameter,
                knifeEdgeLossDb = knifeEdgeLossDb(fresnelParameter),
                radiusOfCurvatureM = null,
                roundedCorrectionDb = 0.0,
            )
            if (dominant == null || candidate.fresnelParameter > dominant.fresnelParameter) {
                dominant = candidate
            }
        }
        return dominant
    }

    private fun localRadiusM(
        distancesM: List<Double>,
        heightsM: List<Double>,
        index: Int,
        fresnelParameter: Double,
    ): Double? {
        if (index <= 0 || index >= distancesM.lastIndex || fresnelParameter <= 0.0) return null
        val leftSpacingM = distancesM[index] - distancesM[index - 1]
        val rightSpacingM = distancesM[index + 1] - distancesM[index]
        val slopeChange = (heightsM[index] - heightsM[index - 1]) / leftSpacingM +
            (heightsM[index] - heightsM[index + 1]) / rightSpacingM
        if (slopeChange <= 0.0) return null
        val smoothing = (1.0 - exp(-4.0 * fresnelParameter)).pow(3.0)
        val radiusM = (distancesM[index + 1] - distancesM[index - 1]) /
            slopeChange * smoothing
        return radiusM.takeIf { it.isFinite() && it > 0.0 }
    }
}
