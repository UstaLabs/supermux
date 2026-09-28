package com.swithun.cmpmermaid.core.venn.upstream.vennjs

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.venn.upstream.fmin.Fmin
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class VennLayoutArea(
    val data: VennData,
    val text: VennPoint,
    val circles: List<VennCircle>,
    val arcs: List<VennArc>,
)

/**
 * Kotlin translation of @upsetjs/venn.js 2.0.0:
 * src/layout.js and the geometry-only portions of src/diagram.js.
 */
internal object VennLayoutEngine {
    fun layout(
        source: List<VennData>,
        width: Double,
        height: Double,
        padding: Double,
    ): GMResult<List<VennLayoutArea>, MermaidError> {
        if (!width.isFinite() || width <= 0.0 || !height.isFinite() || height <= 0.0) {
            return layoutError("width and height must be positive finite numbers")
        }
        if (!padding.isFinite() || padding < 0.0) {
            return layoutError("padding must be a non-negative finite number")
        }
        val zeroSets = source
            .filter { area -> area.sets.size == 1 && area.size == 0.0 }
            .mapTo(linkedSetOf()) { area -> area.sets.first() }
        val data = source.filterNot { area -> area.sets.any(zeroSets::contains) }
        data.firstOrNull { area -> !area.size.isFinite() || area.size < 0.0 }?.let { area ->
            return layoutError(
                "area '${area.sets.joinToString(",")}' must have a non-negative finite size",
            )
        }
        data.firstOrNull { area -> area.sets.size == 1 && area.size <= 0.0 }?.let { area ->
            return layoutError("set '${area.sets.first()}' must have a positive size")
        }
        if (data.isEmpty()) {
            return GMResult.Ok(emptyList())
        }

        var solution = venn(data)
        if (solution.values.any { circle -> !circle.isFinite() }) {
            return layoutError("venn.js optimization produced non-finite circle geometry")
        }
        solution = normalizeSolution(solution)
        solution = scaleSolution(solution, width, height, padding)
        if (solution.values.any { circle -> !circle.isFinite() }) {
            return layoutError("venn.js scaling produced non-finite circle geometry")
        }
        val textCentres = computeTextCentres(solution, data)
        return GMResult.Ok(
            data.map { area ->
                val circles = area.sets.mapNotNull(solution::get)
                VennLayoutArea(
                    data = area,
                    text = textCentres[area.sets] ?: VennPoint(0.0, -1000.0),
                    circles = circles,
                    arcs = VennGeometry.intersectionArea(circles).arcs,
                )
            },
        )
    }

    fun ensurePairwiseSubsets(subsets: List<VennData>): List<VennData> {
        val existingKeys = subsets
            .mapTo(linkedSetOf()) { subset -> subset.sets.sorted().joinToString("|") }
        val individualSetSizes = subsets
            .filter { subset -> subset.sets.size == 1 }
            .associate { subset -> subset.sets.first() to subset.size }
        val synthetic = mutableListOf<VennData>()
        subsets.forEach { subset ->
            if (subset.sets.size < 3) {
                return@forEach
            }
            val members = subset.sets.sorted()
            for (first in 0 until members.lastIndex) {
                for (second in first + 1 until members.size) {
                    val pair = listOf(members[first], members[second])
                    val key = pair.joinToString("|")
                    if (existingKeys.add(key)) {
                        val firstSize = individualSetSizes[pair[0]]
                        val secondSize = individualSetSizes[pair[1]]
                        val size = if (firstSize != null && secondSize != null) {
                            min(firstSize, secondSize) / 4.0
                        } else {
                            2.5
                        }
                        synthetic += VennData(sets = pair, size = size, label = "")
                    }
                }
            }
        }
        return if (synthetic.isEmpty()) subsets else subsets + synthetic
    }

    fun distanceFromIntersectArea(
        firstRadius: Double,
        secondRadius: Double,
        overlap: Double,
    ): Double {
        if (min(firstRadius, secondRadius).pow(2) * PI <= overlap + SMALL) {
            return abs(firstRadius - secondRadius)
        }
        return Fmin.bisect(
            function = { distance ->
                VennGeometry.circleOverlap(firstRadius, secondRadius, distance) - overlap
            },
            lower = 0.0,
            upper = firstRadius + secondRadius,
        )
    }

    fun lossFunction(
        circles: Map<String, VennCircle>,
        overlaps: List<VennData>,
    ): Double {
        var output = 0.0
        overlaps.forEach { area ->
            if (area.sets.size == 1) {
                return@forEach
            }
            val selected = area.sets.mapNotNull(circles::get)
            val overlap = if (selected.size == 2) {
                VennGeometry.circleOverlap(
                    selected[0].radius,
                    selected[1].radius,
                    VennGeometry.distance(selected[0], selected[1]),
                )
            } else {
                VennGeometry.intersectionArea(selected).area
            }
            output += (overlap - area.size) * (overlap - area.size)
        }
        return output
    }

    private fun venn(sets: List<VennData>): LinkedHashMap<String, VennCircle> {
        val areas = addMissingAreas(sets)
        val circles = bestInitialLayout(areas)
        val setIds = circles.keys.toList()
        val initial = DoubleArray(setIds.size * 2)
        setIds.forEachIndexed { index, setId ->
            val circle = circles.getValue(setId)
            initial[index * 2] = circle.x
            initial[index * 2 + 1] = circle.y
        }
        val solution = Fmin.nelderMead(
            function = { values ->
                val current = linkedMapOf<String, VennCircle>()
                setIds.forEachIndexed { index, setId ->
                    current[setId] = VennCircle(
                        x = values[index * 2],
                        y = values[index * 2 + 1],
                        radius = circles.getValue(setId).radius,
                        setId = setId,
                    )
                }
                lossFunction(current, areas)
            },
            initial = initial,
            maxIterations = 500,
        )
        setIds.forEachIndexed { index, setId ->
            circles.getValue(setId).x = solution.x[index * 2]
            circles.getValue(setId).y = solution.x[index * 2 + 1]
        }
        return circles
    }

    private fun addMissingAreas(areas: List<VennData>): List<VennData> {
        val result = areas.toMutableList()
        val ids = mutableListOf<String>()
        val pairs = linkedSetOf<String>()
        result.forEach { area ->
            when (area.sets.size) {
                1 -> ids += area.sets.first()
                2 -> {
                    pairs += area.sets.joinToString(";")
                    pairs += area.sets.reversed().joinToString(";")
                }
            }
        }
        ids.sort()
        for (first in 0 until ids.lastIndex) {
            for (second in first + 1 until ids.size) {
                val pair = listOf(ids[first], ids[second])
                if (pair.joinToString(";") !in pairs) {
                    result += VennData(sets = pair, size = 0.0)
                }
            }
        }
        return result
    }

    private fun bestInitialLayout(areas: List<VennData>): LinkedHashMap<String, VennCircle> {
        var initial = greedyLayout(areas)
        if (areas.size >= 8) {
            val constrained = constrainedMdsLayout(areas)
            if (
                constrained.isNotEmpty() &&
                lossFunction(constrained, areas) + 1e-8 < lossFunction(initial, areas)
            ) {
                initial = constrained
            }
        }
        return initial
    }

    private fun constrainedMdsLayout(
        areas: List<VennData>,
    ): LinkedHashMap<String, VennCircle> {
        val sets = areas.filter { area -> area.sets.size == 1 }
        if (sets.isEmpty()) {
            return linkedMapOf()
        }
        val setIds = sets.mapIndexed { index, area -> area.sets.first() to index }.toMap()
        var (distances, constraints) = getDistanceMatrices(areas, sets, setIds)
        val norm = sqrt(distances.sumOf { row -> row.sumOf { value -> value * value } }) /
            distances.size
        if (!norm.isFinite() || norm == 0.0) {
            return linkedMapOf()
        }
        distances = Array(distances.size) { row ->
            DoubleArray(distances[row].size) { column ->
                distances[row][column] / norm
            }
        }
        val random = DeterministicRandom(seedFor(areas))
        var best: Fmin.Result? = null
        repeat(CONSTRAINED_MDS_RESTARTS) {
            val initial = DoubleArray(distances.size * 2) { random.nextDouble() }
            val current = Fmin.conjugateGradient(
                function = { coordinates, gradient ->
                    constrainedMdsGradient(coordinates, gradient, distances, constraints)
                },
                initial = initial,
            )
            val previous = best
            if (previous == null || current.fx < previous.fx) {
                best = current
            }
        }
        val positions = best?.x ?: return linkedMapOf()
        return linkedMapOf<String, VennCircle>().apply {
            sets.forEachIndexed { index, area ->
                val setId = area.sets.first()
                put(
                    setId,
                    VennCircle(
                        x = positions[index * 2] * norm,
                        y = positions[index * 2 + 1] * norm,
                        radius = sqrt(area.size / PI),
                        setId = setId,
                        size = area.size,
                    ),
                )
            }
        }
    }

    private fun getDistanceMatrices(
        areas: List<VennData>,
        sets: List<VennData>,
        setIds: Map<String, Int>,
    ): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val distances = Array(sets.size) { DoubleArray(sets.size) }
        val constraints = Array(sets.size) { DoubleArray(sets.size) }
        areas.filter { area -> area.sets.size == 2 }.forEach { current ->
            val left = setIds[current.sets[0]] ?: return@forEach
            val right = setIds[current.sets[1]] ?: return@forEach
            val firstRadius = sqrt(sets[left].size / PI)
            val secondRadius = sqrt(sets[right].size / PI)
            val distance = distanceFromIntersectArea(
                firstRadius,
                secondRadius,
                current.size,
            )
            distances[left][right] = distance
            distances[right][left] = distance
            val constraint = when {
                current.size + SMALL >= min(sets[left].size, sets[right].size) -> 1.0
                current.size <= SMALL -> -1.0
                else -> 0.0
            }
            constraints[left][right] = constraint
            constraints[right][left] = constraint
        }
        return distances to constraints
    }

    private fun constrainedMdsGradient(
        coordinates: DoubleArray,
        gradient: DoubleArray,
        distances: Array<DoubleArray>,
        constraints: Array<DoubleArray>,
    ): Double {
        gradient.fill(0.0)
        var loss = 0.0
        for (first in distances.indices) {
            val firstX = coordinates[first * 2]
            val firstY = coordinates[first * 2 + 1]
            for (second in first + 1 until distances.size) {
                val secondX = coordinates[second * 2]
                val secondY = coordinates[second * 2 + 1]
                val desired = distances[first][second]
                val constraint = constraints[first][second]
                val squaredDistance =
                    (secondX - firstX).pow(2) + (secondY - firstY).pow(2)
                val distance = sqrt(squaredDistance)
                val delta = squaredDistance - desired * desired
                if (
                    (constraint > 0.0 && distance <= desired) ||
                    (constraint < 0.0 && distance >= desired)
                ) {
                    continue
                }
                loss += 2.0 * delta * delta
                gradient[first * 2] += 4.0 * delta * (firstX - secondX)
                gradient[first * 2 + 1] += 4.0 * delta * (firstY - secondY)
                gradient[second * 2] += 4.0 * delta * (secondX - firstX)
                gradient[second * 2 + 1] += 4.0 * delta * (secondY - firstY)
            }
        }
        return loss
    }

    private fun greedyLayout(areas: List<VennData>): LinkedHashMap<String, VennCircle> {
        val circles = linkedMapOf<String, VennCircle>()
        val setOverlaps = linkedMapOf<String, MutableList<SetOverlap>>()
        areas.forEach { area ->
            if (area.sets.size == 1) {
                val setId = area.sets.first()
                circles[setId] = VennCircle(
                    x = 1e10,
                    y = 1e10,
                    radius = sqrt(area.size / PI),
                    setId = setId,
                    size = area.size,
                )
                setOverlaps[setId] = mutableListOf()
            }
        }
        val pairwise = areas.filter { area -> area.sets.size == 2 }
        pairwise.forEach { current ->
            val left = current.sets[0]
            val right = current.sets[1]
            val leftCircle = circles[left] ?: return@forEach
            val rightCircle = circles[right] ?: return@forEach
            val weight = if (
                current.size + SMALL >= min(leftCircle.size, rightCircle.size)
            ) {
                0.0
            } else {
                1.0
            }
            setOverlaps.getValue(left) += SetOverlap(right, current.size, weight)
            setOverlaps.getValue(right) += SetOverlap(left, current.size, weight)
        }
        val mostOverlapped = setOverlaps.map { (setId, overlaps) ->
            RankedSet(
                setId = setId,
                size = overlaps.sumOf { overlap -> overlap.size * overlap.weight },
            )
        }.sortedByDescending(RankedSet::size)
        if (mostOverlapped.isEmpty()) {
            return circles
        }
        val positioned = linkedSetOf<String>()
        fun position(
            point: VennPoint,
            setId: String,
        ) {
            circles.getValue(setId).x = point.x
            circles.getValue(setId).y = point.y
            positioned += setId
        }
        position(VennPoint(0.0, 0.0), mostOverlapped.first().setId)

        mostOverlapped.drop(1).forEach { ranked ->
            val setId = ranked.setId
            val overlaps = setOverlaps.getValue(setId)
                .filter { overlap -> overlap.setId in positioned }
                .sortedByDescending(SetOverlap::size)
            val set = circles.getValue(setId)
            val points = mutableListOf<VennPoint>()
            overlaps.forEachIndexed { index, overlap ->
                val first = circles.getValue(overlap.setId)
                val firstDistance = distanceFromIntersectArea(
                    set.radius,
                    first.radius,
                    overlap.size,
                )
                points += VennPoint(first.x + firstDistance, first.y)
                points += VennPoint(first.x - firstDistance, first.y)
                points += VennPoint(first.x, first.y + firstDistance)
                points += VennPoint(first.x, first.y - firstDistance)
                overlaps.drop(index + 1).forEach { nextOverlap ->
                    val second = circles.getValue(nextOverlap.setId)
                    val secondDistance = distanceFromIntersectArea(
                        set.radius,
                        second.radius,
                        nextOverlap.size,
                    )
                    points += VennGeometry.circleCircleIntersection(
                        VennCircle(first.x, first.y, firstDistance, first.setId),
                        VennCircle(second.x, second.y, secondDistance, second.setId),
                    )
                }
            }
            var bestLoss = 1e50
            var bestPoint = points.firstOrNull() ?: VennPoint(0.0, 0.0)
            points.forEach { point ->
                set.x = point.x
                set.y = point.y
                val localLoss = lossFunction(circles, pairwise)
                if (localLoss < bestLoss) {
                    bestLoss = localLoss
                    bestPoint = point
                }
            }
            position(bestPoint, setId)
        }
        return circles
    }

    private fun normalizeSolution(
        source: Map<String, VennCircle>,
        orientation: Double = PI / 2.0,
    ): LinkedHashMap<String, VennCircle> {
        val circles = source.values.map { circle -> circle.copy() }.toMutableList()
        if (circles.isEmpty()) {
            return linkedMapOf()
        }
        val clusters = disjointClusters(circles).map { cluster ->
            orientateCircles(cluster, orientation)
            Cluster(cluster, boundingBox(cluster))
        }.sortedByDescending { cluster -> cluster.bounds.area }
        val output = clusters.first().circles.toMutableList()
        var returnBounds = clusters.first().bounds
        val spacing = returnBounds.width / 50.0

        fun addCluster(
            cluster: Cluster?,
            right: Boolean,
            bottom: Boolean,
        ) {
            if (cluster == null) {
                return
            }
            val bounds = cluster.bounds
            var xOffset = if (right) {
                returnBounds.maxX - bounds.minX + spacing
            } else {
                returnBounds.maxX - bounds.maxX
            }
            if (!right) {
                val centering = bounds.width / 2.0 - returnBounds.width / 2.0
                if (centering < 0.0) {
                    xOffset += centering
                }
            }
            var yOffset = if (bottom) {
                returnBounds.maxY - bounds.minY + spacing
            } else {
                returnBounds.maxY - bounds.maxY
            }
            if (!bottom) {
                val centering = bounds.height / 2.0 - returnBounds.height / 2.0
                if (centering < 0.0) {
                    yOffset += centering
                }
            }
            cluster.circles.forEach { circle ->
                circle.x += xOffset
                circle.y += yOffset
                output += circle
            }
        }

        var index = 1
        while (index < clusters.size) {
            addCluster(clusters.getOrNull(index), right = true, bottom = false)
            addCluster(clusters.getOrNull(index + 1), right = false, bottom = true)
            addCluster(clusters.getOrNull(index + 2), right = true, bottom = true)
            index += 3
            returnBounds = boundingBox(output)
        }
        return output.associateByTo(linkedMapOf(), VennCircle::setId)
    }

    private fun orientateCircles(
        circles: MutableList<VennCircle>,
        orientation: Double,
    ) {
        circles.sortByDescending(VennCircle::radius)
        circles.firstOrNull()?.let { largest ->
            val largestX = largest.x
            val largestY = largest.y
            circles.forEach { circle ->
                circle.x -= largestX
                circle.y -= largestY
            }
        }
        if (circles.size == 2) {
            val distance = VennGeometry.distance(circles[0], circles[1])
            if (distance < abs(circles[1].radius - circles[0].radius)) {
                circles[1].x = circles[0].x + circles[0].radius - circles[1].radius - SMALL
                circles[1].y = circles[0].y
            }
        }
        if (circles.size > 1) {
            val rotation = atan2(circles[1].x, circles[1].y) - orientation
            val cosine = cos(rotation)
            val sine = sin(rotation)
            circles.forEach { circle ->
                val x = circle.x
                val y = circle.y
                circle.x = cosine * x - sine * y
                circle.y = sine * x + cosine * y
            }
        }
        if (circles.size > 2) {
            var angle = atan2(circles[2].x, circles[2].y) - orientation
            while (angle < 0.0) {
                angle += 2.0 * PI
            }
            while (angle > 2.0 * PI) {
                angle -= 2.0 * PI
            }
            if (angle > PI) {
                val slope = circles[1].y / (SMALL + circles[1].x)
                circles.forEach { circle ->
                    val distance = (circle.x + slope * circle.y) / (1.0 + slope * slope)
                    circle.x = 2.0 * distance - circle.x
                    circle.y = 2.0 * distance * slope - circle.y
                }
            }
        }
    }

    private fun disjointClusters(circles: List<VennCircle>): List<MutableList<VennCircle>> {
        val parents = IntArray(circles.size) { index -> index }
        fun find(index: Int): Int {
            var current = index
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }
        fun union(
            first: Int,
            second: Int,
        ) {
            parents[find(first)] = find(second)
        }
        for (first in 0 until circles.lastIndex) {
            for (second in first + 1 until circles.size) {
                if (
                    VennGeometry.distance(circles[first], circles[second]) + SMALL <
                    circles[first].radius + circles[second].radius
                ) {
                    union(second, first)
                }
            }
        }
        val clusters = linkedMapOf<Int, MutableList<VennCircle>>()
        circles.indices.forEach { index ->
            clusters.getOrPut(find(index)) { mutableListOf() } += circles[index]
        }
        return clusters.values.toList()
    }

    private fun scaleSolution(
        source: Map<String, VennCircle>,
        originalWidth: Double,
        originalHeight: Double,
        padding: Double,
    ): LinkedHashMap<String, VennCircle> {
        val circles = source.values.toList()
        val width = originalWidth - 2.0 * padding
        val height = originalHeight - 2.0 * padding
        val bounds = boundingBox(circles)
        if (bounds.width == 0.0 || bounds.height == 0.0) {
            return source.mapValuesTo(linkedMapOf()) { (_, circle) -> circle.copy() }
        }
        val scaling = min(width / bounds.width, height / bounds.height)
        val xOffset = (width - bounds.width * scaling) / 2.0
        val yOffset = (height - bounds.height * scaling) / 2.0
        return circles.associateTo(linkedMapOf()) { circle ->
            circle.setId to VennCircle(
                radius = scaling * circle.radius,
                x = padding + xOffset + (circle.x - bounds.minX) * scaling,
                y = padding + yOffset + (circle.y - bounds.minY) * scaling,
                setId = circle.setId,
                size = circle.size,
            )
        }
    }

    private fun computeTextCentres(
        circles: Map<String, VennCircle>,
        areas: List<VennData>,
    ): Map<List<String>, VennPoint> {
        val result = linkedMapOf<List<String>, VennPoint>()
        val overlapping = getOverlappingCircles(circles)
        areas.forEach { item ->
            val areaIds = item.sets.toSet()
            val excluded = linkedSetOf<String>()
            item.sets.forEach { setId ->
                overlapping[setId].orEmpty().forEach(excluded::add)
            }
            val interior = circles.filterKeys(areaIds::contains).values.toList()
            val exterior = circles
                .filterKeys { setId -> setId !in areaIds && setId !in excluded }
                .values
                .toList()
            result[item.sets] = computeTextCentre(interior, exterior)
        }
        return result
    }

    private fun computeTextCentre(
        interior: List<VennCircle>,
        exterior: List<VennCircle>,
    ): VennPoint {
        if (interior.isEmpty()) {
            return VennPoint(0.0, -1000.0)
        }
        val points = buildList {
            interior.forEach { circle ->
                add(VennPoint(circle.x, circle.y))
                add(VennPoint(circle.x + circle.radius / 2.0, circle.y))
                add(VennPoint(circle.x - circle.radius / 2.0, circle.y))
                add(VennPoint(circle.x, circle.y + circle.radius / 2.0))
                add(VennPoint(circle.x, circle.y - circle.radius / 2.0))
            }
        }
        var initial = points.first()
        var margin = circleMargin(initial, interior, exterior)
        points.drop(1).forEach { point ->
            val candidateMargin = circleMargin(point, interior, exterior)
            if (candidateMargin >= margin) {
                initial = point
                margin = candidateMargin
            }
        }
        val solution = Fmin.nelderMead(
            function = { point ->
                -circleMargin(VennPoint(point[0], point[1]), interior, exterior)
            },
            initial = doubleArrayOf(initial.x, initial.y),
            maxIterations = 500,
            minErrorDelta = 1e-10,
            minTolerance = 1e-10,
        ).x
        val result = VennPoint(solution[0], solution[1])
        val valid = interior.all { circle ->
            VennGeometry.distance(circle, result) <= circle.radius
        } && exterior.all { circle ->
            VennGeometry.distance(circle, result) >= circle.radius
        }
        if (valid) {
            return result
        }
        if (interior.size == 1) {
            return VennPoint(interior[0].x, interior[0].y)
        }
        val arcs = VennGeometry.intersectionArea(interior).arcs
        return when {
            arcs.isEmpty() -> VennPoint(0.0, -1000.0)
            arcs.size == 1 -> VennPoint(arcs[0].circle.x, arcs[0].circle.y)
            exterior.isNotEmpty() -> computeTextCentre(interior, emptyList())
            else -> VennGeometry.getCenter(arcs.map(VennArc::p1))
        }
    }

    private fun circleMargin(
        current: VennPoint,
        interior: List<VennCircle>,
        exterior: List<VennCircle>,
    ): Double {
        var margin = interior.first().radius -
            VennGeometry.distance(interior.first(), current)
        interior.drop(1).forEach { circle ->
            val candidate = circle.radius - VennGeometry.distance(circle, current)
            if (candidate <= margin) {
                margin = candidate
            }
        }
        exterior.forEach { circle ->
            val candidate = VennGeometry.distance(circle, current) - circle.radius
            if (candidate <= margin) {
                margin = candidate
            }
        }
        return margin
    }

    private fun getOverlappingCircles(
        circles: Map<String, VennCircle>,
    ): Map<String, List<String>> {
        val ids = circles.keys.toList()
        val overlapping = ids.associateWithTo(linkedMapOf()) { mutableListOf<String>() }
        for (first in 0 until ids.lastIndex) {
            val firstId = ids[first]
            val firstCircle = circles.getValue(firstId)
            for (second in first + 1 until ids.size) {
                val secondId = ids[second]
                val secondCircle = circles.getValue(secondId)
                val distance = VennGeometry.distance(firstCircle, secondCircle)
                if (distance + secondCircle.radius <= firstCircle.radius + SMALL) {
                    overlapping.getValue(secondId) += firstId
                } else if (distance + firstCircle.radius <= secondCircle.radius + SMALL) {
                    overlapping.getValue(firstId) += secondId
                }
            }
        }
        return overlapping
    }

    private fun boundingBox(circles: List<VennCircle>): Bounds {
        val minX = circles.minOf { circle -> circle.x - circle.radius }
        val maxX = circles.maxOf { circle -> circle.x + circle.radius }
        val minY = circles.minOf { circle -> circle.y - circle.radius }
        val maxY = circles.maxOf { circle -> circle.y + circle.radius }
        return Bounds(minX, maxX, minY, maxY)
    }

    private fun VennCircle.isFinite(): Boolean =
        x.isFinite() && y.isFinite() && radius.isFinite() && radius >= 0.0

    private fun seedFor(areas: List<VennData>): Long {
        var hash = FNV_OFFSET
        areas.forEach { area ->
            area.sets.forEach { setId ->
                setId.forEach { character ->
                    hash = (hash xor character.code.toLong()) * FNV_PRIME
                }
            }
            hash = (hash xor area.size.toBits()) * FNV_PRIME
        }
        return hash
    }

    private fun <T> layoutError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Layout("Mermaid Venn $message"))

    private data class SetOverlap(
        val setId: String,
        val size: Double,
        val weight: Double,
    )

    private data class RankedSet(
        val setId: String,
        val size: Double,
    )

    private data class Bounds(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    ) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
        val area: Double get() = width * height
    }

    private data class Cluster(
        val circles: MutableList<VennCircle>,
        val bounds: Bounds,
    )

    private class DeterministicRandom(seed: Long) {
        private var state = seed.takeUnless { it == 0L } ?: GOLDEN_RATIO

        fun nextDouble(): Double {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            val positive = state ushr 11
            return positive.toDouble() / (1L shl 53).toDouble()
        }
    }

    private const val SMALL = 1e-10
    private const val CONSTRAINED_MDS_RESTARTS = 10
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
    private const val GOLDEN_RATIO = -7046029254386353131L
}
