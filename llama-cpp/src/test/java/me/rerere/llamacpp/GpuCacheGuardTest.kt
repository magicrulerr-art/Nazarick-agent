package me.rerere.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Adreno OpenCL KV-cache guard (ported from Off Grid AI's production rules in
 * OGAM `src/services/llmHelpers.ts`, 2026-11-08): explicit non-f16 cache types fault
 * context creation on a spread of Adreno devices once layers are offloaded.
 */
class GpuCacheGuardTest {

    @Test fun offloadAlwaysF16() {
        assertEquals(KvCacheType.F16, cacheTypeForOffload(KvCacheType.Q8_0, gpuLayers = 1))
        assertEquals(KvCacheType.F16, cacheTypeForOffload(KvCacheType.Q8_0, gpuLayers = -1))
        assertEquals(KvCacheType.F16, cacheTypeForOffload(KvCacheType.F16, gpuLayers = 999))
    }

    @Test fun cpuPlansPassThroughUntouched() {
        // Production ships gpu_layers=0; the planner's Q8_0 choice must survive there —
        // clamping the CPU path would silently double KV memory for no benefit.
        assertEquals(KvCacheType.Q8_0, cacheTypeForOffload(KvCacheType.Q8_0, gpuLayers = 0))
        assertEquals(KvCacheType.F16, cacheTypeForOffload(KvCacheType.F16, gpuLayers = 0))
    }
}
