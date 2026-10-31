package me.rerere.llamacpp

import android.util.Log

/**
 * Engine and context parameters that change while a model is being broken in.
 *
 * Everything here was a `private const` in [ContextPlanner], which made a single number cost a
 * CI build plus an install — fifteen minutes to learn what one logcat line could have said.
 * Only n_gpu_layers was ever live (read per load through SystemProperties reflection in
 * LlamaCppProvider); this extends that idiom rather than replacing it, because "setprop,
 * force-stop, rerun" is the only loop fast enough for what is left on this model: prefill
 * latency at the context the planner now picks, and later the projector's pixel budget.
 *
 * One property, `debug.moxw.tune`, holding `key=value` pairs split on ';' or ',':
 *
 *   adb shell setprop debug.moxw.tune "ctx_force=8192;response_reserve=0.35;n_ubatch=1024"
 *
 * One property rather than one per knob because setprop values are flat strings either way, and
 * a single echo line then records the whole engine configuration for the run. Anything unset
 * keeps its shipped default, so a device with no property behaves exactly as before this class.
 *
 * Fractional knobs are `Double`, matching the arithmetic they replaced (`1.0 - RESPONSE_RESERVE`
 * and friends) so wiring them in changes no types anywhere else.
 */
data class EngineTunables(
    /** Ceiling on the ladder. 0 = no ceiling beyond what the model and RAM allow. */
    val ctxMax: Int = 0,
    /** Bypass the ladder and load at exactly this size. 0 = choose from the ladder. */
    val ctxForce: Int = 0,
    /** Fraction of the context reserved for the response. */
    val responseReserve: Double = 0.5,
    /** Tokens kept free for conversation history growth. */
    val historyHeadroomTokens: Int = 750,
    /** What a system prompt is charged against the input budget. */
    val systemPromptReserveBytes: Int = 1200,
    val nBatch: Int = 512,
    val nUBatch: Int = 512,
    /** Multiplier over the computed total, for allocator overhead. */
    val headroomWithMetadata: Double = 1.1,
    /** Coarse whole-file multiplier used when GGUF metadata is untrustworthy. */
    val headroomWithoutMetadata: Double = 1.2,
    /** Never plan to use more than this share of what the device has free. */
    val ramSafetyFraction: Double = 0.8,
    /**
     * null = leave `enable_thinking` out of the request entirely, which in this build means
     * **thinking on**: llama.cpp's `llama_chat_inputs` defaults the flag to true and always binds
     * it, so a template's own `enable_thinking | default(...)` never actually applies to us —
     * worth knowing, because LFM3-Omni-2B ships `default(false)` and we have been overriding it
     * to true all along (`llama_jni.cpp:328` only reads the field when present).
     *
     * `false` is therefore the interesting direction: the template emits an empty thinking block
     * and answers straight away, which is the prefill/latency experiment this knob exists to run.
     * Templates differ in what they default to — VL-2B ships `default(true)`, Omni-2B
     * `default(false)` (read from each GGUF's tokenizer.chat_template, 2026-10-30) — which is why
     * the value belongs to the bench rather than to the code.
     */
    val reasoning: Boolean? = null,
) {
    companion object {
        const val PROPERTY = "debug.moxw.tune"
        private const val TAG = "moxw-tune"

        /** Exactly what the planner did before this class existed. */
        val DEFAULT = EngineTunables()

        /**
         * Parse a spec into knobs plus every pair that was not understood.
         *
         * Pure on purpose — no `android.util.Log` in here, so it runs on a plain JVM without
         * `unitTests.isReturnDefaultValues`. Logging is [fromSystemProperties]' job. A typo that
         * silently changes nothing is the failure mode that eats a whole device session, which is
         * why unrecognised and malformed pairs come back in the open instead of being swallowed.
         */
        fun parse(spec: String?): Pair<EngineTunables, List<String>> {
            var t = DEFAULT
            val rejected = mutableListOf<String>()
            for (raw in spec.orEmpty().split(';', ',', ' ')) {
                val pair = raw.trim()
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) {
                    rejected += pair
                    continue
                }
                val key = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                val i = value.toIntOrNull()
                val d = value.toDoubleOrNull()
                when (key) {
                    "ctx_max" -> if (i != null) t = t.copy(ctxMax = i) else rejected += pair
                    "ctx_force" -> if (i != null) t = t.copy(ctxForce = i) else rejected += pair
                    "response_reserve" -> if (d != null) t = t.copy(responseReserve = d) else rejected += pair
                    "history_headroom" -> if (i != null) t = t.copy(historyHeadroomTokens = i) else rejected += pair
                    "system_prompt_reserve" -> if (i != null) t = t.copy(systemPromptReserveBytes = i) else rejected += pair
                    "n_batch" -> if (i != null) t = t.copy(nBatch = i) else rejected += pair
                    "n_ubatch" -> if (i != null) t = t.copy(nUBatch = i) else rejected += pair
                    "headroom_metadata" -> if (d != null) t = t.copy(headroomWithMetadata = d) else rejected += pair
                    "headroom_coarse" -> if (d != null) t = t.copy(headroomWithoutMetadata = d) else rejected += pair
                    "ram_safety" -> if (d != null) t = t.copy(ramSafetyFraction = d) else rejected += pair
                    "reasoning" -> {
                        val b = value.toBooleanStrictOrNull()
                        if (b != null) t = t.copy(reasoning = b) else rejected += pair
                    }
                    else -> rejected += pair
                }
            }
            return t to rejected
        }

        /**
         * Read the property. Reflection because `android.os.SystemProperties` is `@hide` — the
         * same route `LlamaCpp.gpuLayersOverride()` takes, and off-device it degrades to the
         * defaults, which is what keeps the JVM unit tests honest about the shipped numbers.
         */
        fun fromSystemProperties(): EngineTunables {
            val spec = try {
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java)
                (get.invoke(null, PROPERTY) as? String).orEmpty()
            } catch (e: Throwable) {
                "" // No SystemProperties: unit tests, or a host without Android.
            }
            val (tunables, rejected) = parse(spec.ifBlank { null })
            Log.i(TAG, tunables.echo())
            rejected.forEach { Log.w(TAG, "unrecognised or malformed '$it' — ignored") }
            return tunables
        }
    }

    /**
     * One line naming every effective value, so a run is reproducible from its own logcat. The
     * "(OVERRIDDEN)" suffix is separate on purpose: the shipped defaults are a legitimate answer,
     * and grepping that word is how a bench run gets proven clean or dirty.
     */
    fun echo(): String {
        val s = buildString {
            append("tune: ctx_max=").append(ctxMax)
            append(" ctx_force=").append(ctxForce)
            append(" response_reserve=").append(responseReserve)
            append(" history_headroom=").append(historyHeadroomTokens)
            append(" system_prompt_reserve=").append(systemPromptReserveBytes)
            append(" n_batch=").append(nBatch).append(" n_ubatch=").append(nUBatch)
            append(" headroom=").append(headroomWithMetadata).append('/').append(headroomWithoutMetadata)
            append(" ram_safety=").append(ramSafetyFraction)
            append(" reasoning=").append(reasoning ?: "template")
        }
        return if (this == DEFAULT) s else "$s   (OVERRIDDEN)"
    }
}
