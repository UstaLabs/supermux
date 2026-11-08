package dev.supermux.webview.wpetest

/** An input the harness made at System.nanoTime [atNs]; the page shows [expectMarker] once it has handled it. */
data class Stimulus(val name: String, val atNs: Long, val expectMarker: Int)

/** A frame WebKit delivered: its sequence number, the page's counter in it, when it reached render_buffer. */
data class Arrival(val seq: Long, val marker: Int, val atNs: Long)

/** A frame Compose drew, by the sequence number of the buffer it showed. */
data class Draw(val seq: Long, val atNs: Long)

/** How long one stimulus took: to the first delivered frame with its marker, and to the first draw of that frame. */
data class Latency(val stimulus: Stimulus, val arrivalMs: Double?, val drawnMs: Double?)

object Timeline {
    fun latencies(stimuli: List<Stimulus>, arrivals: List<Arrival>, draws: List<Draw>): List<Latency> = stimuli.map { s ->
        val arrival = arrivals.firstOrNull { it.atNs >= s.atNs && it.marker == s.expectMarker }
        val draw = arrival?.let { a -> draws.firstOrNull { it.seq >= a.seq && it.atNs >= a.atNs } }
        Latency(s, arrival?.let { (it.atNs - s.atNs) / 1e6 }, draw?.let { (it.atNs - s.atNs) / 1e6 })
    }

    /** Events per second from the first to the last of [timesNs]; NaN for fewer than two. */
    fun rate(timesNs: List<Long>): Double {
        if (timesNs.size < 2) return Double.NaN
        val sorted = timesNs.sorted()
        return (sorted.size - 1) * 1e9 / (sorted.last() - sorted.first())
    }

    fun intervalsMs(timesNs: List<Long>): List<Double> = timesNs.sorted().zipWithNext { a, b -> (b - a) / 1e6 }

    /** Page frames that never reached us: the counter goes up by one per page frame (mod [modulus]). */
    fun missedPageFrames(markers: List<Int>, modulus: Int = 1 shl 16): Long {
        var missed = 0L
        for ((a, b) in markers.zipWithNext()) {
            val step = Math.floorMod(b - a, modulus)
            if (step > 1) missed += step - 1
        }
        return missed
    }
}

object Markers {
    const val SENTINEL: Int = 0x5a

    /** The page's 16-bit counter from a sampled pixel `0xRRGGBB` (red high byte, green low, blue 0x5A); null if it isn't the marker. */
    fun counter(rgb: Long): Int? =
        if (rgb < 0 || (rgb and 0xff) != SENTINEL.toLong()) null else ((rgb shr 8) and 0xffff).toInt()
}
