package me.rerere.llamacpp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Global admission ledger tests (AURA-021, loop-contract LC-6/LC-7). The bug class is
 * the 2026-11-12 Spark crash: the chat context and the embedding engine each passed
 * their own MemoryGuard while together double-booking free RAM, and the OS killed the
 * process mid-load. Here the second model must be DECLINED with numbers instead —
 * and only when the ledger is actually full.
 */
class MemoryBudgetTest {

    private val budget get() = NativeAdmission.budget

    @Before
    fun resetLedger() {
        budget.release(NativeAdmission.CHAT_ID)
        budget.release(NativeAdmission.EMBEDDING_ID)
    }

    @Test
    fun `first reservation is granted with the room it left`() {
        val r = budget.tryReserve(NativeAdmission.CHAT_ID, 4_000_000_000L, 12_000_000_000L)
        assertTrue("expected Granted, got $r", r is MemoryBudget.Result.Granted)
        assertEquals(8_000_000_000L, (r as MemoryBudget.Result.Granted).roomAfterBytes)
    }

    @Test
    fun `second holder over the budget is declined and records nothing`() {
        assertTrue(budget.tryReserve(NativeAdmission.CHAT_ID, 9_000_000_000L, 12_000_000_000L) is MemoryBudget.Result.Granted)
        val r = budget.tryReserve(NativeAdmission.EMBEDDING_ID, 3_100_000_000L, 12_000_000_000L)
        assertTrue("expected Declined, got $r", r is MemoryBudget.Result.Declined)
        r as MemoryBudget.Result.Declined
        assertEquals(3_000_000_000L, r.roomBytes)
        assertEquals(3_100_000_000L, r.requiredBytes)
        assertEquals("a declined holder must NOT appear in the ledger", setOf(NativeAdmission.CHAT_ID), budget.outstanding().keys)
    }

    @Test
    fun `release frees the room for the next holder`() {
        budget.tryReserve(NativeAdmission.CHAT_ID, 9_000_000_000L, 12_000_000_000L)
        budget.release(NativeAdmission.CHAT_ID)
        assertTrue(budget.tryReserve(NativeAdmission.EMBEDDING_ID, 3_100_000_000L, 12_000_000_000L) is MemoryBudget.Result.Granted)
    }

    @Test
    fun `same id re-reserving replaces instead of competing with itself`() {
        budget.tryReserve(NativeAdmission.CHAT_ID, 9_000_000_000L, 12_000_000_000L)
        val r = budget.tryReserve(NativeAdmission.CHAT_ID, 8_000_000_000L, 12_000_000_000L)
        assertTrue("reload at a smaller size must be granted", r is MemoryBudget.Result.Granted)
        assertEquals(8_000_000_000L, budget.outstanding()[NativeAdmission.CHAT_ID])
    }

    // ---- embedder-side decline path (LC-7: degrade, never crash the chat) ----

    private class TinyEmbedNative : LlamaCppEmbedNative {
        var freedModels = 0
        var createCalls = 0
        var throwingEmbedInfo = false
        override fun loadModel(path: String) = 11L
        override fun freeModel(handle: Long) { freedModels++ }
        override fun modelInfo(handle: Long) =
            """{"n_layers":12,"n_embd":384,"n_head_kv":2,"n_embd_head_k":64,"n_embd_head_v":64,
               "n_vocab":32000,"n_ctx_train":8192,"sliding_window":0,"weights_bytes":309855456}"""
        override fun createEmbedContext(modelHandle: Long, nCtx: Int, nThreads: Int): Long {
            createCalls++
            return 22L
        }
        override fun freeEmbedContext(handle: Long) {}
        override fun embedModelInfo(handle: Long): String {
            if (throwingEmbedInfo) throw IllegalStateException("unreadable embed info")
            return """{"dim":384,"pooling":"mean","has_encoder":false,"has_decoder":true,"n_ctx_train":8192}"""
        }
        override fun embed(handle: Long, text: String) = FloatArray(384)
    }

    @Test
    fun `loadOrDecline returns info when the ledger has room`() = runBlocking {
        val native = TinyEmbedNative()
        val embedder = LlamaCppEmbedder(native)
        val info = embedder.loadOrDecline("/m/e.gguf", availableRamBytes = Long.MAX_VALUE)
        assertNotNull("roomy budget must load", info)
        assertNull(embedder.declineReason)
        assertEquals(setOf(NativeAdmission.EMBEDDING_ID), budget.outstanding().keys)
    }

    @Test
    fun `loadOrDecline declines without throwing when the chat owns the room`() = runBlocking {
        // Chat already holds nearly everything a 2 GB reading offers.
        val admit = budget.tryReserve(NativeAdmission.CHAT_ID, 1_950_000_000L, 2_000_000_000L)
        assertTrue(admit is MemoryBudget.Result.Granted)
        val native = TinyEmbedNative()
        val embedder = LlamaCppEmbedder(native)
        val info = embedder.loadOrDecline("/m/e.gguf", availableRamBytes = 2_000_000_000L)
        assertNull("tight budget must DECLINE", info)
        assertNotNull("the reason must be readable for the degradation surface", embedder.declineReason)
        assertEquals("the refusal must happen before any embed context is built", 0, native.createCalls)
        assertEquals("refused load must free the model it opened", 1, native.freedModels)
        assertEquals("a declined embedder leaves no reservation", setOf(NativeAdmission.CHAT_ID), budget.outstanding().keys)
    }

    @Test
    fun `unload releases the embedding reservation`() = runBlocking {
        val embedder = LlamaCppEmbedder(TinyEmbedNative())
        embedder.loadOrDecline("/m/e.gguf", availableRamBytes = Long.MAX_VALUE)
        embedder.unload()
        assertTrue(budget.outstanding().isEmpty())
    }

    @Test
    fun `a failed load after reservation frees native memory and the ledger too`() = runBlocking {
        // Cocytus US-004 HOLD 2026-11-14: the decodeEmbedInfo failure window released the
        // context and the model but not the ledger, so EMBEDDING_ID kept paying for bytes the
        // process had already returned, and the NEXT embed load declined against memory that
        // no longer existed. The reservation must leave with the memory it charged for.
        val native = TinyEmbedNative().apply { throwingEmbedInfo = true }
        val embedder = LlamaCppEmbedder(native)
        val thrown = runCatching { embedder.loadOrDecline("/m/e.gguf", availableRamBytes = Long.MAX_VALUE) }
        assertTrue("the failure must propagate: " + thrown.exceptionOrNull(), thrown.isFailure)
        assertTrue("the original cause must survive", thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(1, native.freedModels)
        assertTrue("a failed load must leave no ledger hold", budget.outstanding().isEmpty())
    }
}
