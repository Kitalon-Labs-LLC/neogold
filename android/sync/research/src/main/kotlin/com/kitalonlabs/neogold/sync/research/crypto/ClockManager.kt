package com.kitalonlabs.neogold.sync.research.crypto

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class ClockManager(initialOffsetSeconds: Long = 0L) {
    private val offsetSeconds = AtomicLong(initialOffsetSeconds)

    fun getClockOffsetSeconds(): Long = offsetSeconds.get()

    fun updateOffsetFromServerTime(serverTimeString: String) {
        try {
            val serverInstant = Instant.parse(serverTimeString)
            val serverSec = serverInstant.epochSecond
            val deviceSec = System.currentTimeMillis() / 1000
            val newOffset = serverSec - deviceSec
            offsetSeconds.set(newOffset)
        } catch (_: Exception) {
            // Ignore parse errors, retain current offset
        }
    }

    fun setClockOffsetSeconds(offset: Long) {
        offsetSeconds.set(offset)
    }

    fun currentServerAdjustedEpochSeconds(): Long {
        return (System.currentTimeMillis() / 1000) + offsetSeconds.get()
    }
}
