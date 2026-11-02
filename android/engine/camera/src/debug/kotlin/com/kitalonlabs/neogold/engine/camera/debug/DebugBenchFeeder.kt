package com.kitalonlabs.neogold.engine.camera.debug

import com.kitalonlabs.neogold.engine.camera.host.Still
import com.kitalonlabs.neogold.engine.camera.host.StillSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only bench image and QR feeder for P28 fresh-eyes UX evaluation on emulators
 * without physical bench rigs.
 *
 * Lives strictly in `src/debug/` so it is never compiled into `:app:assembleRelease`
 * or `:app:bundleRelease`. Verified absent from `src/main` by `DebugBenchFeederAbsentFromMainTest`.
 */
class DebugBenchFeeder(
    private val frameFiles: List<File>,
    private val baseSensorTimestampNs: Long = 1_000_000_000L,
    private val frameStepNs: Long = 33_333_333L
) : StillSource {

    private val frameIndex = AtomicInteger(0)

    init {
        require(frameFiles.isNotEmpty()) { "DebugBenchFeeder requires at least one bench image file." }
    }

    override suspend fun takeStill(): Still {
        val idx = frameIndex.getAndIncrement()
        val file = frameFiles[idx % frameFiles.size]
        val bytes = file.readBytes()
        val timestampNs = baseSensorTimestampNs + (idx.toLong() * frameStepNs)
        return Still(jpeg = bytes, sensorTimestampNs = timestampNs)
    }

    companion object {
        /** System property / debug broadcast key used to select a synthetic bench scenario in debug runs. */
        const val PROP_BENCH_SCENARIO = "neogold.debug.bench.scenario"
        const val SCENARIO_NOMINAL = "good_01_nominal"
        const val SCENARIO_GLARE_FAIL = "bad_05_glare"
    }
}

