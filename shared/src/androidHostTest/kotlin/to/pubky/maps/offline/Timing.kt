package to.pubky.maps.offline

import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * The fastest of 10 runs of [block], after 3 runs that are not counted.
 *
 * A time bound in a host test is about the code's cost, not the machine's: on a shared CI runner a
 * single run can wait for the CPU, a garbage collection or the JIT (new code runs interpreted at
 * first), so one measurement fails now and then on code that is fast. Code that is slow (a query
 * that misses its index) is slow in every run, so the fastest run still fails its bound.
 */
internal fun fastestOf(block: () -> Unit): Duration {
    repeat(3) { block() }
    return (1..10).minOf { measureTime(block) }
}

/**
 * The fastest run of each of [a] and [b], as [fastestOf] takes it, with the two run in turns so that
 * both see the same state of the machine (a compiled path, a busy CPU) when their times are compared.
 */
internal fun fastestOfEach(
    a: () -> Unit,
    b: () -> Unit,
): Pair<Duration, Duration> {
    repeat(3) {
        a()
        b()
    }
    val rounds = (1..10).map { measureTime(a) to measureTime(b) }
    return rounds.minOf { it.first } to rounds.minOf { it.second }
}
