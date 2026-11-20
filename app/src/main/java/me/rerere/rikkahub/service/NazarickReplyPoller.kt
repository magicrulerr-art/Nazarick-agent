package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.provider.providers.nazarick.NazarickClient
import me.rerere.ai.provider.providers.nazarick.NazarickPushMessage
import me.rerere.ai.provider.providers.nazarick.NazarickPushOutcome
import me.rerere.ai.provider.providers.nazarick.NazarickTurn
import me.rerere.ai.provider.providers.nazarick.NazarickTurnObserver
import me.rerere.ai.provider.providers.nazarick.PushReplyDedupe
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "NazarickReplyPoller"

/**
 * US-007 - background reply notifications for a Nazarick turn.
 *
 * The problem: a Tomb agent turn can outlive the user's patience. They send, put the phone away,
 * and the answer streams into a coroutine nobody is looking at - or, if the process is stopped
 * before the stream lands, into the instance's push store, where it sits until the 60 s prune.
 * This poller watches that store while the app is backgrounded and posts a notification.
 *
 * Gates (the AC): it runs only while a nazarick turn is in flight **and** the app is out of the
 * foreground, and it GETs `/api/console/push-messages?session_id=<namespaced id>` no more often
 * than every [POLL_INTERVAL_MS]. It stops on any of the four named reasons - reply received, app
 * foregrounded, 401, turn finished (which is what a user Stop looks like from here) - and nothing
 * notifies twice for the same content, because [PushReplyDedupe] sits in front of the post.
 *
 * Why the turn has to be *told* to the poller instead of inferred: the server consumes a session's
 * pending messages on read (take semantics, see [NazarickPushMessage]), so polling "just in case"
 * would eat messages the console UI is about to render. Only the provider knows a run is in flight,
 * so it reports the turn through [NazarickTurnObserver] and this class stays honest about the window.
 *
 * Notification idiom: the app's own [sendNotification] DSL on the channel RikkaHubApp already
 * creates for completed chats, with a tap intent into [RouteActivity] (this fork has no
 * MainActivity - RouteActivity is the launcher and takes the `conversationId` extra, exactly the way
 * ChatService's own chat-completed notification does).
 */
class NazarickReplyPoller(
    private val context: Context,
    private val client: NazarickClient,
    private val scope: CoroutineScope,
    private val dedupe: PushReplyDedupe = PushReplyDedupe(),
) : NazarickTurnObserver {

    /** Turns currently in flight, keyed by the namespaced wire session id. */
    private val activeTurns = ConcurrentHashMap<String, NazarickTurn>()

    /** One poll loop per session id, so two chats in two background conversations stay separate. */
    private val pollJobs = ConcurrentHashMap<String, Job>()

    /**
     * Fresh PendingIntent request code per posted notification. Copy of the NotificationTool.kt
     * lesson: a stable code plus FLAG_UPDATE_CURRENT lets an older reply's intent swallow a newer
     * one, and a tap then opens the wrong chat.
     */
    private val requestCodes = AtomicInteger(REQUEST_CODE_BASE)

    /**
     * Foreground gate, the same ProcessLifecycleOwner signal ChatService uses for isForeground:
     * ON_START means the user is back looking at the chat (a notification would be redundant and a
     * consume would steal the console's own render), ON_STOP means a backgrounded turn may need it.
     */
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> stopAll("foregrounded")
            Lifecycle.Event.ON_STOP -> activeTurns.values.forEach { startPolling(it) }
            else -> Unit
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onTurnStarted(turn: NazarickTurn) {
        activeTurns[turn.sessionId] = turn
        // A turn that begins while already backgrounded (headless send, chat opened then locked)
        // starts polling straight away; a foreground one waits for ON_STOP.
        startPolling(turn)
    }

    override fun onTurnFinished(sessionId: String) {
        activeTurns.remove(sessionId)
        pollJobs.remove(sessionId)?.cancel()
    }

    /** Drop every loop - used when the app comes back to the foreground, and by tests. */
    fun stopAll(reason: String) {
        if (pollJobs.isNotEmpty()) {
            Log.d(TAG, "stopping ${pollJobs.size} poll loop(s): $reason")
        }
        pollJobs.values.forEach { it.cancel() }
        pollJobs.clear()
    }

    /** Remove the lifecycle hook (the poller is a process singleton, so this is teardown-only). */
    fun shutdown() {
        stopAll("shutdown")
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
    }

    private fun startPolling(turn: NazarickTurn) {
        if (isForeground()) return
        pollJobs.compute(turn.sessionId) { _, existing ->
            if (existing?.isActive == true) existing else scope.launch { poll(turn) }
        }
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * The loop. Delay first: the chat POST has just gone out, so an immediate GET would consume
     * nothing but clock, and "interval >= 5s" is the AC's floor for the instance's load.
     */
    private suspend fun poll(turn: NazarickTurn) {
        val self = currentCoroutineContext()[Job]
        try {
            while (currentCoroutineContext().isActive) {
                delay(POLL_INTERVAL_MS)
                if (isForeground()) return
                // Turn over (completed mid-poll, faulted, or the user hit Stop) -> nobody is
                // waiting on this session any more.
                if (!activeTurns.containsKey(turn.sessionId)) return

                val outcome = runCatching {
                    client.getPushMessages(
                        baseUrl = turn.baseUrl,
                        token = turn.token,
                        agentId = turn.agentId,
                        sessionId = turn.sessionId,
                    )
                }.getOrElse {
                    Log.w(TAG, "push poll threw, keeping the turn open", it)
                    NazarickPushOutcome.NetworkError
                }

                when (outcome) {
                    is NazarickPushOutcome.Reply -> {
                        val fresh = outcome.messages.filter { dedupe.shouldNotify(it) }
                        if (fresh.isNotEmpty()) {
                            fresh.forEach { postReplyNotification(it, turn) }
                            return  // AC: reply received -> stop watching this turn
                        }
                        // Empty tick: nothing pending, keep waiting for the agent.
                    }

                    NazarickPushOutcome.Unauthorized -> {
                        Log.w(TAG, "push poll got 401 for ${turn.sessionId}, stopping")
                        return
                    }

                    // NetworkError / NotFound / HttpError / Malformed: transient or inert, and a
                    // retry is honest here - the store is still holding the message either way.
                    else -> Unit
                }
            }
        } finally {
            // Drop only our own map entry: a newer turn for the same session may have installed its
            // own loop already, and that one has to stay reachable so ON_START can cancel it.
            if (self != null) pollJobs.remove(turn.sessionId, self) else pollJobs.remove(turn.sessionId)
        }
    }

    private fun postReplyNotification(message: NazarickPushMessage, turn: NazarickTurn) {
        val text = message.text.trim().take(MAX_TEXT_CHARS)
        // One notification slot per conversation: a newer reply replaces the unread older one
        // instead of stacking a wall over the same chat (ChatService's own chat-completed
        // notification does the same with a fixed id).
        val notificationId = (turn.chatId ?: turn.sessionId).hashCode()
        val posted = context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = notificationId,
        ) {
            title = context.getString(R.string.app_name)
            content = text
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = buildChatPendingIntent(turn)
        }
        if (!posted) {
            // POST_NOTIFICATIONS not granted: the dedupe already recorded this content, so the user
            // loses the buzz and not the message - it is still in the chat when they return.
            Log.w(TAG, "notification not posted for ${turn.sessionId} (permission or channel off)")
        }
    }

    /** Tap opens the chat, same intent shape as ChatService's chat-completed notification. */
    private fun buildChatPendingIntent(turn: NazarickTurn): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            turn.chatId?.takeIf { it.isNotBlank() }?.let { putExtra("conversationId", it) }
        }
        return PendingIntent.getActivity(
            context,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** AC: interval >= 5s. One GPU-side instance should not be polled harder than this. */
        const val POLL_INTERVAL_MS = 5_000L

        /** Notification bodies are previews, not transcripts. */
        private const val MAX_TEXT_CHARS = 300

        /** Kept clear of the other PendingIntent codes in this process (NotificationTool starts at 0). */
        private const val REQUEST_CODE_BASE = 4700
    }
}
