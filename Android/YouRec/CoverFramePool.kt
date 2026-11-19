package yourec

import kotlin.random.Random

/// Sample the editor's picture clock in chronological bands, skipping empty
/// timeline gaps. The pool stays small even for a multi-hour project.
class CoverFramePool(picture: List<EditInterval>, scope: List<EditInterval>, frameDuration: EditTime = EditTime.seconds(1.0 / 30),
                     private val random: Random = Random.Default) {
    private class Span(val first: Long, val count: Int)
    private val step = maxOf(1L, frameDuration.ticks)
    private val spans: List<Span>
    val count: Int

    init {
        val pictures = merged(picture); val scopes = merged(scope)
        val found = ArrayList<Span>(); var i = 0; var j = 0
        while (i < pictures.size && j < scopes.size) {
            val start = maxOf(pictures[i].start, scopes[j].start); val end = minOf(pictures[i].end, scopes[j].end)
            if (start < end) {
                val grid = (start.ticks + step - 1) / step * step
                val first = if (grid < end.ticks) grid else start.ticks
                found.add(Span(first, 1 + ((end.ticks - 1 - first) / step).toInt()))
            }
            if (pictures[i].end < scopes[j].end) i += 1 else j += 1
        }
        spans = found
        count = found.sumOf { it.count }
    }

    private fun merged(ranges: List<EditInterval>): List<EditInterval> {
        val result = ArrayList<EditInterval>()
        for (range in ranges.sortedBy { it.start }) {
            if (range.isEmpty) continue
            val last = result.lastOrNull()
            if (last != null && range.start <= last.end) result[result.size - 1] = EditInterval(last.start, maxOf(last.end, range.end))
            else result.add(range)
        }
        return result
    }

    /// Up to nine choices, one from each band. Asked again, each band offers a different frame.
    fun picks(previous: List<Int>): List<Int> {
        val bands = minOf(count, 9)
        return (0 until bands).map { band ->
            val start = band * count / bands; val end = (band + 1) * count / bands
            if (end - start <= 1 || band !in previous.indices) random.nextInt(start, end)
            else random.nextInt(start, end - 1).let { if (it < previous[band]) it else it + 1 }
        }
    }

    fun randomAlternative(current: Int): Int {
        if (count <= 1) return current
        val other = random.nextInt(0, count - 1)
        return if (other < current) other else other + 1
    }

    fun time(index: Int): EditTime {
        var offset = index
        for (span in spans) {
            if (offset < span.count) return EditTime.ticks(span.first + offset.toLong() * step)
            offset -= span.count
        }
        throw IndexOutOfBoundsException("Cover index outside the sampled range")
    }
}
