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
