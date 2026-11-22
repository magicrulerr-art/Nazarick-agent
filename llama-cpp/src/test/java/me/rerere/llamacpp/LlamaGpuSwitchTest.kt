package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner-facing GPU switch (v0.6 "Theme overhaul" ticket): the provider pane's
 *  "Try GPU acceleration" toggle flows through [resolveGpuLayers] at every model load.
 *  Contract: the switch is the owner's authority (off = byte-identical pre-v0.6 CPU path);
 *  inside GPU mode the bench prop refines; unset prop offloads everything. */
class LlamaGpuSwitchTest {

    @Test fun switchOffPinsCpuPath() {
        // force-CPU wins over any bench value - the escape hatch must be absolute.
        assertEquals(0, resolveGpuLayers(forceCpu = true, propOverride = 0))
        assertEquals(0, resolveGpuLayers(forceCpu = true, propOverride = -1))
        assertEquals(0, resolveGpuLayers(forceCpu = true, propOverride = 17))
    }

    @Test fun switchOnUnsetPropOffloadsEverything() {
        assertEquals(-1, resolveGpuLayers(forceCpu = false, propOverride = 0))
    }

    @Test fun switchOnBenchPropRefines() {
        // A/B lanes keep working: explicit -1 or a pinned layer count beats the all-offload
        // default while the switch is on.
        assertEquals(-1, resolveGpuLayers(forceCpu = false, propOverride = -1))
        assertEquals(9, resolveGpuLayers(forceCpu = false, propOverride = 9))
    }
}
