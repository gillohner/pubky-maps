package to.pubky.maps.place

import kotlin.math.abs

/** The heights of the place sheet on a phone (Google Maps' and CoMaps' pattern). */
enum class SheetStop {
    /** The header only: name, what it is, open now, the actions; the map stays in view. */
    Peek,

    /** Half the screen: the header and the first sections. */
    Half,

    /** The whole screen, scrolling. */
    Full,
}

/**
 * Where the sheet goes: heights in px for each stop, and the rules for drags and taps. Plain logic,
 * so it is tested without Compose.
 */
data class SheetStops(
    val peek: Float,
    val half: Float,
    val full: Float,
) {
    fun height(stop: SheetStop): Float =
        when (stop) {
            SheetStop.Peek -> peek
            SheetStop.Half -> half.coerceAtLeast(peek)
            SheetStop.Full -> full.coerceAtLeast(half)
        }

    /** A drag keeps the sheet between its lowest and highest stop. */
    fun clamp(height: Float): Float = height.coerceIn(peek, height(SheetStop.Full))

    /**
     * Where a drag that let go at [height] with [velocity] (px/s, positive = upwards) settles: a fling
     * goes to the next stop in its direction, a slow release to the nearest stop.
     */
    fun settle(
        height: Float,
        velocity: Float,
    ): SheetStop {
        val stops = SheetStop.entries
        if (abs(velocity) >= FLING_VELOCITY) {
            return if (velocity > 0) {
                stops.firstOrNull { height(it) > height + 1f } ?: SheetStop.Full
            } else {
                stops.lastOrNull { height(it) < height - 1f } ?: SheetStop.Peek
            }
        }
        return stops.minBy { abs(height(it) - height) }
    }

    /**
     * A tap on the handle: one stop up, and from the top back to half. Where half is no taller than
     * the lowest stop (a short window), the lowest goes to the top, so the tap is never lost.
     */
    fun afterTap(stop: SheetStop): SheetStop =
        when (stop) {
            SheetStop.Peek -> if (height(SheetStop.Half) > peek + 1f) SheetStop.Half else SheetStop.Full
            SheetStop.Half -> SheetStop.Full
            SheetStop.Full -> SheetStop.Half
        }

    companion object {
        /** px/s; about a quick flick on a phone. */
        const val FLING_VELOCITY = 1200f
    }
}
