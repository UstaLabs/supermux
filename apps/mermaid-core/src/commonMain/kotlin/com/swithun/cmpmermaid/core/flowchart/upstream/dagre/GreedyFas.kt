package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef
import kotlin.math.max

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/greedy-fas.js
 * src/dagre/data/list.js
 */
internal object GreedyFas {
    fun find(graph: DagreGraph): List<EdgeRef> {
        if (graph.nodeCount() <= 1) return emptyList()

        val entries = linkedMapOf<String, Entry>()
        graph.nodes().forEach { entries[it] = Entry(it) }
        val weights = linkedMapOf<Pair<String, String>, Float>()
        var maxIn = 0f
        var maxOut = 0f
        graph.edges().forEach { edge ->
            val weight = graph.edgeOrThrow(edge).weight
            val key = edge.v to edge.w
            weights[key] = (weights[key] ?: 0f) + weight
            entries.getValue(edge.v).out += weight
            entries.getValue(edge.w).incoming += weight
            maxOut = max(maxOut, entries.getValue(edge.v).out)
            maxIn = max(maxIn, entries.getValue(edge.w).incoming)
        }

        val zeroIndex = maxIn.toInt() + 1
        val buckets = List(maxOut.toInt() + maxIn.toInt() + 3) { mutableListOf<Entry>() }

        fun assign(entry: Entry) {
            entry.bucket?.remove(entry)
            val index = when {
                entry.out == 0f -> 0
                entry.incoming == 0f -> buckets.lastIndex
                else -> (entry.out - entry.incoming).toInt() + zeroIndex
            }
            buckets[index].add(0, entry)
            entry.bucket = buckets[index]
        }

        entries.values.forEach(::assign)
        val removedPairs = mutableListOf<Pair<String, String>>()

        fun remove(entry: Entry, collectPredecessors: Boolean) {
            entry.bucket?.remove(entry)
            val incomingEdges = weights.keys.filter { it.second == entry.id }
            incomingEdges.forEach { key ->
                if (collectPredecessors) removedPairs += key
                entries[key.first]?.let { predecessor ->
                    predecessor.out -= weights.getValue(key)
                    assign(predecessor)
                }
                weights.remove(key)
            }
            val outgoingEdges = weights.keys.filter { it.first == entry.id }
            outgoingEdges.forEach { key ->
                entries[key.second]?.let { successor ->
                    successor.incoming -= weights.getValue(key)
                    assign(successor)
                }
                weights.remove(key)
            }
            entries.remove(entry.id)
        }

        fun dequeue(bucket: MutableList<Entry>): Entry? =
            if (bucket.isEmpty()) null else bucket.removeAt(bucket.lastIndex).also { it.bucket = null }

        while (entries.isNotEmpty()) {
            var entry = dequeue(buckets.first())
            while (entry != null) {
                remove(entry, collectPredecessors = false)
                entry = dequeue(buckets.first())
            }
            entry = dequeue(buckets.last())
            while (entry != null) {
                remove(entry, collectPredecessors = false)
                entry = dequeue(buckets.last())
            }
            if (entries.isNotEmpty()) {
                for (index in buckets.lastIndex - 1 downTo 1) {
                    val candidate = dequeue(buckets[index]) ?: continue
                    remove(candidate, collectPredecessors = true)
                    break
                }
            }
        }

        return removedPairs.flatMap { (from, to) -> graph.outEdges(from, to) }
    }

    private class Entry(
        val id: String,
        var incoming: Float = 0f,
        var out: Float = 0f,
        var bucket: MutableList<Entry>? = null,
    )
}
