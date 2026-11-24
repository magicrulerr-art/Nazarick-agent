package me.rerere.rikkahub.runtime

import java.io.IOException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * US-005 (z7 send-stall, mission-20261122-102000) — the wedge, in one line: a local model that
 * cannot be seated inside the device memory budget is a DETERMINISTIC failure, yet the stream
 * retry policy replayed it five times (750..3750 ms, 11.3 s total) before giving up, and the
 * only message the user ever got auto-dismissed five seconds after that. evidence\us004\
 * root_cause.md names this as RC-2: "deterministic failure retried AND never surfaced".
 *
 * These tests fail on 151a01e6d (the retry policy said "retry" for all four refusals below) and
 * pass with the capacity classifier wired into [shouldRetryGenerationStreamFailure].
 */
class GenerationLoopCapacityFailFastTest {

    /** Stand-in for me.rerere.llamacpp.ModelTooLargeException, which :runtime cannot import
     *  (no dependency on :llama-cpp); the classifier matches it by simple name, exactly as the
     *  real throw sites in LlamaCppRuntime.load produce it. */
    private class ModelTooLargeException(message: String) : RuntimeException(message)

    private fun shouldRetry(failure: Throwable, attempt: Long = 0, maxRetries: Int = 5): Boolean =
        shouldRetryGenerationStreamFailure(
            failure = failure,
            retryAttempt = attempt,
            maxRetries = maxRetries,
            receivedMeaningfulOutput = false,
        )

    @Test
    fun `does not replay the budget-ledger refusal that stalled z7`() {
        val z7 = ModelTooLargeException(
            "The local model budget cannot seat this model right now: only 2838MB is unreserved " +
                "of the 2838MB free while this model needs 3543MB (held: {}). " +
                "Unload the other local model first.",
        )
        assertFalse(
            "a capacity refusal with nothing else held will fail identically on every attempt",
            shouldRetry(z7),
        )
    }

    @Test
    fun `refuses even when the capacity failure arrives wrapped by the provider`() {
        val wrapped = RuntimeException(
            "stream failed",
            ModelTooLargeException(
                "The local model budget cannot seat this model right now: only 2757MB is " +
                    "unreserved of the 2757MB free while this model needs 3543MB (held: {}).",
            ),
        )
        assertFalse(shouldRetry(wrapped))
    }

    @Test
    fun `does not replay the MemoryGuard refusal`() {
        assertFalse(
            shouldRetry(
                IllegalStateException(
                    "This model needs about 3543MB free but only 2838MB is available. " +
                        "Close other apps or pick a smaller model.",
                )
            )
        )
    }

    @Test
    fun `does not replay the unreadable-file-size refusal`() {
        assertFalse(
            shouldRetry(
                ModelTooLargeException(
                    "This model's file size could not be read, so it cannot be safely loaded."
                )
            )
        )
    }

    @Test
    fun `a transport failure is still retried`() {
        assertTrue(shouldRetry(IOException("Software caused connection abort")))
    }

    @Test
    fun `a capacity refusal on one turn does not stop a later server error from retrying`() {
        assertTrue(shouldRetry(HttpException("upstream unavailable", statusCode = 503)))
    }

    @Test
    fun `an ordinary local processing failure is still retried`() {
        assertTrue(
            shouldRetry(IllegalStateException("response body could not be decoded"))
        )
    }
}
