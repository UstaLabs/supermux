package com.swithun.cmpmermaid.core.venn.upstream.fmin

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Kotlin translation of the fmin 0.0.4 routines embedded by
 * @upsetjs/venn.js 2.0.0: bisect, nelderMead, and conjugateGradient.
 */
internal object Fmin {
    data class Result(
        val x: DoubleArray,
        val fx: Double,
    )

    fun bisect(
        function: (Double) -> Double,
        lower: Double,
        upper: Double,
        maxIterations: Int = 100,
        tolerance: Double = 1e-10,
    ): Double {
        var a = lower
        var delta = upper - lower
        val functionAtA = function(a)
        val functionAtB = function(upper)
        if (functionAtA == 0.0) {
            return a
        }
        if (functionAtB == 0.0) {
            return upper
        }
        if (functionAtA * functionAtB > 0.0) {
            return Double.NaN
        }
        repeat(maxIterations) {
            delta /= 2.0
            val middle = a + delta
            val functionAtMiddle = function(middle)
            if (functionAtMiddle * functionAtA >= 0.0) {
                a = middle
            }
            if (abs(delta) < tolerance || functionAtMiddle == 0.0) {
                return middle
            }
        }
        return a + delta
    }

    fun nelderMead(
        function: (DoubleArray) -> Double,
        initial: DoubleArray,
        maxIterations: Int = initial.size * 200,
        nonZeroDelta: Double = 1.05,
        zeroDelta: Double = 0.001,
        minErrorDelta: Double = 1e-6,
        minTolerance: Double = 1e-5,
        rho: Double = 1.0,
        chi: Double = 2.0,
        psi: Double = -0.5,
        sigma: Double = 0.5,
    ): Result {
        val dimensions = initial.size
        val simplex = MutableList(dimensions + 1) { index ->
            val point = initial.copyOf()
            if (index > 0) {
                val coordinate = index - 1
                point[coordinate] = if (point[coordinate] != 0.0) {
                    point[coordinate] * nonZeroDelta
                } else {
                    zeroDelta
                }
            }
            SimplexPoint(
                coordinates = point,
                fx = function(point),
                id = index,
            )
        }
        val centroid = initial.copyOf()
        val reflected = initial.copyOf()
        val contracted = initial.copyOf()
        val expanded = initial.copyOf()

        fun updateWorst(value: DoubleArray, fx: Double) {
            value.copyInto(simplex[dimensions].coordinates)
            simplex[dimensions].fx = fx
        }

        repeat(maxIterations) {
            simplex.sortBy(SimplexPoint::fx)
            var maxDifference = 0.0
            for (index in 0 until dimensions) {
                maxDifference = max(
                    maxDifference,
                    abs(simplex[0].coordinates[index] - simplex[1].coordinates[index]),
                )
            }
            if (
                abs(simplex[0].fx - simplex[dimensions].fx) < minErrorDelta &&
                maxDifference < minTolerance
            ) {
                return Result(simplex[0].coordinates.copyOf(), simplex[0].fx)
            }

            for (coordinate in 0 until dimensions) {
                centroid[coordinate] = 0.0
                for (point in 0 until dimensions) {
                    centroid[coordinate] += simplex[point].coordinates[coordinate]
                }
                centroid[coordinate] =
                    centroid[coordinate] / dimensions.toDouble()
            }

            val worst = simplex[dimensions]
            weightedSum(reflected, 1.0 + rho, centroid, -rho, worst.coordinates)
            val reflectedFx = function(reflected)
            if (reflectedFx < simplex[0].fx) {
                weightedSum(expanded, 1.0 + chi, centroid, -chi, worst.coordinates)
                val expandedFx = function(expanded)
                if (expandedFx < reflectedFx) {
                    updateWorst(expanded, expandedFx)
                } else {
                    updateWorst(reflected, reflectedFx)
                }
            } else if (reflectedFx >= simplex[dimensions - 1].fx) {
                var shouldReduce = false
                if (reflectedFx > worst.fx) {
                    weightedSum(contracted, 1.0 + psi, centroid, -psi, worst.coordinates)
                    val contractedFx = function(contracted)
                    if (contractedFx < worst.fx) {
                        updateWorst(contracted, contractedFx)
                    } else {
                        shouldReduce = true
                    }
                } else {
                    weightedSum(
                        contracted,
                        1.0 - psi * rho,
                        centroid,
                        psi * rho,
                        worst.coordinates,
                    )
                    val contractedFx = function(contracted)
                    if (contractedFx < reflectedFx) {
                        updateWorst(contracted, contractedFx)
                    } else {
                        shouldReduce = true
                    }
                }
                if (shouldReduce) {
                    if (sigma >= 1.0) {
                        return@repeat
                    }
                    for (index in 1 until simplex.size) {
                        weightedSum(
                            simplex[index].coordinates,
                            1.0 - sigma,
                            simplex[0].coordinates,
                            sigma,
                            simplex[index].coordinates,
                        )
                        simplex[index].fx = function(simplex[index].coordinates)
                    }
                }
            } else {
                updateWorst(reflected, reflectedFx)
            }
        }
        simplex.sortBy(SimplexPoint::fx)
        return Result(simplex[0].coordinates.copyOf(), simplex[0].fx)
    }

    fun conjugateGradient(
        function: (DoubleArray, DoubleArray) -> Double,
        initial: DoubleArray,
        maxIterations: Int = initial.size * 20,
    ): Result {
        var current = GradientPoint(
            x = initial.copyOf(),
            fx = 0.0,
            gradient = initial.copyOf(),
        )
        var next = GradientPoint(
            x = initial.copyOf(),
            fx = 0.0,
            gradient = initial.copyOf(),
        )
        val gradientDelta = initial.copyOf()
        val direction = initial.copyOf()
        var step = 1.0

        current.fx = function(current.x, current.gradient)
        scale(direction, current.gradient, -1.0)
        repeat(maxIterations) {
            step = wolfeLineSearch(function, direction, current, next, step)
            if (step == 0.0) {
                scale(direction, current.gradient, -1.0)
            } else {
                weightedSum(gradientDelta, 1.0, next.gradient, -1.0, current.gradient)
                val delta = dot(current.gradient, current.gradient)
                val beta = max(0.0, dot(gradientDelta, next.gradient) / delta)
                weightedSum(direction, beta, direction, -1.0, next.gradient)
                val temporary = current
                current = next
                next = temporary
            }
            if (norm2(current.gradient) <= 1e-5) {
                return Result(current.x.copyOf(), current.fx)
            }
        }
        return Result(current.x.copyOf(), current.fx)
    }

    private fun wolfeLineSearch(
        function: (DoubleArray, DoubleArray) -> Double,
        direction: DoubleArray,
        current: GradientPoint,
        next: GradientPoint,
        initialStep: Double,
        c1: Double = 1e-6,
        c2: Double = 0.1,
    ): Double {
        val phi0 = current.fx
        val phiPrime0 = dot(current.gradient, direction)
        var phi = phi0
        var oldPhi = phi0
        var phiPrime: Double
        var lowerStep = 0.0
        var step = initialStep.takeUnless { it == 0.0 } ?: 1.0

        fun zoom(
            initialLower: Double,
            initialUpper: Double,
            initialLowerPhi: Double,
        ): Double {
            var lower = initialLower
            var upper = initialUpper
            var lowerPhi = initialLowerPhi
            repeat(16) {
                step = (lower + upper) / 2.0
                weightedSum(next.x, 1.0, current.x, step, direction)
                phi = function(next.x, next.gradient)
                next.fx = phi
                phiPrime = dot(next.gradient, direction)
                if (phi > phi0 + c1 * step * phiPrime0 || phi >= lowerPhi) {
                    upper = step
                } else {
                    if (abs(phiPrime) <= -c2 * phiPrime0) {
                        return step
                    }
                    if (phiPrime * (upper - lower) >= 0.0) {
                        upper = lower
                    }
                    lower = step
                    lowerPhi = phi
                }
            }
            return 0.0
        }

        repeat(10) { iteration ->
            weightedSum(next.x, 1.0, current.x, step, direction)
            phi = function(next.x, next.gradient)
            next.fx = phi
            phiPrime = dot(next.gradient, direction)
            if (
                phi > phi0 + c1 * step * phiPrime0 ||
                (iteration > 0 && phi >= oldPhi)
            ) {
                return zoom(lowerStep, step, oldPhi)
            }
            if (abs(phiPrime) <= -c2 * phiPrime0) {
                return step
            }
            if (phiPrime >= 0.0) {
                return zoom(step, lowerStep, phi)
            }
            oldPhi = phi
            lowerStep = step
            step *= 2.0
        }
        return step
    }

    private fun dot(
        left: DoubleArray,
        right: DoubleArray,
    ): Double {
        var result = 0.0
        left.indices.forEach { index -> result += left[index] * right[index] }
        return result
    }

    private fun norm2(value: DoubleArray): Double = sqrt(dot(value, value))

    private fun scale(
        result: DoubleArray,
        value: DoubleArray,
        factor: Double,
    ) {
        value.indices.forEach { index -> result[index] = value[index] * factor }
    }

    private fun weightedSum(
        result: DoubleArray,
        firstWeight: Double,
        first: DoubleArray,
        secondWeight: Double,
        second: DoubleArray,
    ) {
        result.indices.forEach { index ->
            result[index] = firstWeight * first[index] + secondWeight * second[index]
        }
    }

    private data class SimplexPoint(
        val coordinates: DoubleArray,
        var fx: Double,
        val id: Int,
    )

    private data class GradientPoint(
        val x: DoubleArray,
        var fx: Double,
        val gradient: DoubleArray,
    )
}
