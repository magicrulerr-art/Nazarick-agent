package me.rerere.ai.provider.providers.nazarick

/**
 * One app-side chat turn against a Nazarick instance, as far as US-007 is concerned.
 *
 * The provider is the only place that knows a turn exists, and the background reply poller is the
 * thing that has to learn about it - the AC gates the poller on "a nazarick turn is pending".
 * Everything the poller needs to make its own authenticated GET travels here, so the app module
 * never has to re-resolve provider settings behind the poller's back.
 *
 * @param baseUrl   instance root the turn was POSTed to.
 * @param token     bearer token in use for this turn ("" = unauth loopback instance).
 * @param agentId   selected agent ("" = no `X-Agent-Id` header, per [nazarickRequest]).
 * @param sessionId the **namespaced** wire id `app-<device>-<chat>` - exactly what
 *                  `/api/console/push-messages?session_id=` takes, since the server keys pending
 *                  messages by the session that produced them.
 * @param chatId    the raw app conversation id the turn came from, used by the notification tap
 *                  intent to open that chat; null when the provider never received one.
 */
data class NazarickTurn(
    val baseUrl: String,
    val token: String,
    val agentId: String,
    val sessionId: String,
    val chatId: String?,
)

/**
 * Turn-lifecycle seam installed on [NazarickProvider] by the app (US-007).
 *
 * Callbacks fire on whatever thread the chat flow runs on (the provider stream is `flowOn` IO), so
 * an implementation must be thread-safe and must never block - it is called from inside the chat
 * stream's start and `finally`.
 */
interface NazarickTurnObserver {
    /** The turn reached the wire: a run is now in flight for [NazarickTurn.sessionId]. */
    fun onTurnStarted(turn: NazarickTurn)

    /**
     * The turn is no longer in flight - completed, faulted, or stopped by the user. A session that
     * never started is a no-op by contract.
     */
    fun onTurnFinished(sessionId: String)
}
