package me.rerere.llamacpp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AURA-008. Two halves: the spec parser (pure JVM, no android.util.Log on the path), and proof
 * that the knobs actually move the planner — a mechanism that parses but is never read would pass
 * a test suite like this one otherwise.
 */
class EngineTunablesTest {

    // ContextPlanner.tunables is process-wide state. Without this reset a tuned assertion could
    // quietly decide the result of an unrelated test in the same JVM, which is exactly the kind
    // of order-dependent green this repo has been bitten by before.
    @Before
    @After
    fun resetTunables() {
        ContextPlanner.tunables = EngineTunables.DEFAULT
    }

    // The spec language ---------------------------------------------------------------

    @Test
    fun `an unset property means the shipped defaults`() {
        val (tunables, rejected) = EngineTunables.parse(null)
        assertEquals(EngineTunables.DEFAULT, tunables)
        assertEquals(emptyList<String>(), rejected)
        assertEquals(0, tunables.ctxMax)
        assertEquals(0, tunables.ctxForce)
        assertEquals(0.5, tunables.responseReserve, 1e-9)
        assertEquals(750, tunables.historyHeadroomTokens)
        assertEquals(1200, tunables.systemPromptReserveBytes)
        assertEquals(512, tunables.nBatch)
        assertEquals(512, tunables.nUBatch)
        assertEquals(1.1, tunables.headroomWithMetadata, 1e-9)
        assertEquals(1.2, tunables.headroomWithoutMetadata, 1e-9)
        assertEquals(0.8, tunables.ramSafetyFraction, 1e-9)
        assertEquals(null, tunables.reasoning)
    }

    @Test
    fun `every knob is reachable and separators are interchangeable`() {
        val spec = "ctx_max=8192,response_reserve=0.25 history_headroom=64;n_batch=1024;" +
            "n_ubatch=256,headroom_metadata=1.3;headroom_coarse=1.4 ram_safety=0.65;reasoning=false"
        val (t, rejected) = EngineTunables.parse(spec)
        assertEquals("nothing should be rejected: $rejected", emptyList<String>(), rejected)
        assertEquals(8192, t.ctxMax)
        assertEquals(0.25, t.responseReserve, 1e-9)
        assertEquals(64, t.historyHeadroomTokens)
        assertEquals(1024, t.nBatch)
        assertEquals(256, t.nUBatch)
        assertEquals(1.3, t.headroomWithMetadata, 1e-9)
        assertEquals(1.4, t.headroomWithoutMetadata, 1e-9)
        assertEquals(0.65, t.ramSafetyFraction, 1e-9)
        assertEquals(false, t.reasoning)
    }

    @Test
    fun `reasoning is strict about its words`() {
        // "1"/"0" are rejected on purpose: a bench note saying reasoning=1 that silently meant
        // "unset" would be read as thinking-on and the experiment would be a lie either way, so
        // the safer failure is to name the pair as unrecognised.
        assertEquals(true, EngineTunables.parse("reasoning=true").first.reasoning)
        assertEquals(false, EngineTunables.parse("reasoning=false").first.reasoning)
        assertEquals(null, EngineTunables.parse("reasoning=1").first.reasoning)
        assertEquals(listOf("reasoning=1"), EngineTunables.parse("reasoning=1").second)
    }

    @Test
    fun `unknown and malformed pairs are returned rather than swallowed`() {
        val spec = "n_gpu_layers=99;ctx_max=abc;response_reserve;=7;history_headroom=40"
        val (t, rejected) = EngineTunables.parse(spec)
        // The one well-formed knob still applies — a typo must not void the whole spec.
        assertEquals(40, t.historyHeadroomTokens)
        assertEquals(
            listOf("n_gpu_layers=99", "ctx_max=abc", "response_reserve", "=7"),
            rejected,
        )
    }

    @Test
    fun `echo names the values and flags a tuned run`() {
        val clean = EngineTunables.DEFAULT.echo()
        assertTrue(clean, clean.startsWith("tune: ctx_max=0"))
        assertFalse("an untuned run must not look tuned: $clean", clean.contains("OVERRIDDEN"))

        val tuned = EngineTunables.parse("ctx_force=12288;reasoning=false").first
        val echo = tuned.echo()
        assertTrue(echo, echo.contains("ctx_force=12288"))
        assertTrue(echo, echo.contains("reasoning=false"))
        assertTrue("a tuned run must be greppable: $echo", echo.contains("OVERRIDDEN"))
    }

    // The knobs must actually reach the planner ---------------------------------------

    private fun smallModel(nCtxTrain: Int = 32768) = GgufModelInfo(
        nLayers = 26,
        nEmbd = 2048,
        nHeadKv = 4,
        nEmbdHeadK = 256,
        nEmbdHeadV = 256,
        nVocab = 262144,
        nCtxTrain = nCtxTrain,
        slidingWindow = null,
        weightsBytes = 2_600_000_000L,
    )

    private fun tools(count: Int, bytesEach: Int = 700) =
        (1..count).map { ToolDeclaration("tool_$it", bytesEach) }

    private val plentyOfRam = 12_000_000_000L

    @Test
    fun `ctx_max caps the ladder below what memory would allow`() {
        // Untuned this model plans 32768 (see ContextPlannerTest); the cap must bite.
        assertEquals(32768, ContextPlanner.plan(smallModel(), plentyOfRam, tools(10), 500).nCtx)
        ContextPlanner.tunables = EngineTunables.parse("ctx_max=8192").first
        assertEquals(8192, ContextPlanner.plan(smallModel(), plentyOfRam, tools(10), 500).nCtx)
    }

    @Test
    fun `ctx_force loads an off-ladder size so prefill can be measured at one exact window`() {
        ContextPlanner.tunables = EngineTunables.parse("ctx_force=12288").first
        val plan = ContextPlanner.plan(smallModel(), plentyOfRam, tools(10), 500)
        assertEquals(12288, plan.nCtx)
        // And the input budget follows the forced window, not the nearest rung:
        // (12288 * 0.5) - 750 = 5394 tokens * 4 bytes.
        assertEquals(5394 * ContextPlanner.BYTES_PER_TOKEN, ContextPlanner.inputByteBudget(plan.nCtx))
    }

    @Test
    fun `ctx_force beyond the trained context still loads that size`() {
        // Deliberate: this is how a longer-than-trained window gets measured. The planner is a
        // memory estimator, not a policy guard, and the bench sometimes wants the extrapolation.
        ContextPlanner.tunables = EngineTunables.parse("ctx_force=65536").first
        val plan = ContextPlanner.plan(smallModel(nCtxTrain = 8192), plentyOfRam, tools(2), 500)
        assertEquals(65536, plan.nCtx)
    }

    @Test
    fun `response_reserve and history_headroom move the input budget`() {
        // Defaults: (4096 * 0.5) - 750 = 1298 tokens.
        assertEquals(1298 * 4, ContextPlanner.inputByteBudget(4096))
        ContextPlanner.tunables = EngineTunables.parse("response_reserve=0.25;history_headroom=0").first
        // (4096 * 0.75) - 0 = 3072 tokens: three quarters of the window spendable on input.
        assertEquals(3072 * 4, ContextPlanner.inputByteBudget(4096))
    }

    @Test
    fun `system_prompt_reserve is the same number the mapper's floor reads`() {
        // Raising the charge must change what a persona reserves, which is how the truncation
        // floor in ChatRequestMapper and this planner stay one value rather than two that drift.
        val atDefault = ContextPlanner.replanForRequest(8192, emptyList(), 31_084)
        assertEquals(1200, atDefault.reservedInputBytes)

        ContextPlanner.tunables = EngineTunables.parse("system_prompt_reserve=400").first
        assertEquals(400, ContextPlanner.replanForRequest(8192, emptyList(), 31_084).reservedInputBytes)
        assertEquals(400, ContextPlanner.SYSTEM_PROMPT_RESERVE_BYTES)
    }

    @Test
    fun `ram_safety lowers the plan`() {
        // Same model, same RAM: a 0.05 share cannot afford 32768, so the plan must step down.
        val greedy = ContextPlanner.plan(smallModel(), plentyOfRam, tools(2), 500)
        ContextPlanner.tunables = EngineTunables.parse("ram_safety=0.05").first
        val tight = ContextPlanner.plan(smallModel(), plentyOfRam, tools(2), 500)
        assertTrue("${greedy.nCtx} vs ${tight.nCtx}", tight.nCtx < greedy.nCtx)
    }
}
