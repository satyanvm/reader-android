package io.github.satyanvm.reader.model

/**
 * Hybrid logical clock, in epoch milliseconds.
 *
 * Every value it hands out is strictly greater than any value it handed out before or observed
 * from another device. That keeps "most recent reading activity wins" correct even when a
 * device's wall clock is behind: once this device has seen a position from another device, its
 * next save always orders after it.
 */
class HybridClock(
    private val wallClock: () -> Long = System::currentTimeMillis,
    initial: Long = 0L,
) {
    private var last: Long = initial

    @Synchronized
    fun now(): Long {
        last = maxOf(wallClock(), last + 1)
        return last
    }

    @Synchronized
    fun observe(remote: Long) {
        if (remote > last) last = remote
    }

    @Synchronized
    fun peek(): Long = last
}
