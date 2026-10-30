package com.kitalonlabs.neogold.engine.camera.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pairs a photo with how the camera took it.
 *
 * CameraX hands over the photo and the camera's capture result separately, in either order, and both carry the sensor
 * timestamp of the frame. The camera session records each still capture's result here by that timestamp, and the burst
 * controller waits for the one that belongs to the photo it just received (FR-11, TC-CAP-15). This replaces reading
 * CameraX's internal result holder, which lint reports as a restricted API (BUG-2026-032).
 *
 * @param capacity how many unclaimed results are kept; the oldest go first, so a result nobody asks for cannot pile up
 */
class CaptureResultRegistry<T : Any>(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lock = Any()
    private val unclaimed = LinkedHashMap<Long, T>()
    private val waiting = HashMap<Long, CompletableDeferred<T>>()

    /** Called by the camera session when a still capture finishes. Safe to call from any thread. */
    fun record(sensorTimestampNs: Long, result: T) {
        synchronized(lock) {
            val waiter = waiting.remove(sensorTimestampNs)
            if (waiter != null) {
                waiter.complete(result)
            } else {
                unclaimed[sensorTimestampNs] = result
                while (unclaimed.size > capacity) unclaimed.remove(unclaimed.keys.first())
            }
        }
    }

    /**
     * The result recorded for [sensorTimestampNs], waiting up to [timeoutMs] for it to arrive; null if it does not, in
     * which case the caller records the photo without capture metadata rather than guessing.
     */
    suspend fun await(sensorTimestampNs: Long, timeoutMs: Long): T? {
        val waiter: CompletableDeferred<T>
        synchronized(lock) {
            unclaimed.remove(sensorTimestampNs)?.let { return it }
            waiter = CompletableDeferred<T>().also { waiting[sensorTimestampNs] = it }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            synchronized(lock) { waiting.remove(sensorTimestampNs) }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
