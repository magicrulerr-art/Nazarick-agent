package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy

/**
 * Where an image attached to a chat turn is actually processed.
 *
 *  - [EDGE]: the image never leaves the device — processed locally.
 *  - [TOMB]: the image is uploaded to the remote brain ("the tomb").
 */
enum class ImageDestination {
    EDGE,
    TOMB,
}

/**
 * US-011: pure, side-effect-free decision of the destination for images on a
 * chat turn, given the account [BrainPolicy], an explicit per-request
 * [override], and whether a tomb endpoint is even [tombConfigured].
 *
 * Rules, in order:
 *  1. `policy == EDGE` is absolute: the image never silently leaves the
 *     device, so an [override] of [ImageDestination.TOMB] is ignored.
 *  2. Otherwise an explicit [override] wins.
 *  3. Otherwise `policy in (TOMB, AUTO)` and a tomb is configured →
 *     [ImageDestination.TOMB].
 *  4. Otherwise → [ImageDestination.EDGE].
 *
 * ## US-011 wiring status: outcome (b) — pure rule shipped, end-to-end send BLOCKED
 *
 * This pure decision layer is committed and unit-tested, but the image is not yet
 * threaded through to the tomb at the composer send. The blocking seam is the wire
 * contract of the chat turn, which carries **text only** and has **no image field and
 * no upload endpoint** — so no single image part can reach the nazarick request without
 * a server-contract change, well beyond the ≤40-line budget for outcome (a):
 *
 *  - `ai/.../nazarick/NazarickChat.kt:56` — `nazarickChatRequestBody(input: String,
 *    sessionId: String)` builds the `POST /api/console/chat` body as exactly
 *    `{input, stream, session_id}`. `input` is a bare `String`; there is no image /
 *    attachments field to carry a part.
 *  - `ai/.../nazarick/NazarickProvider.kt:100` — `val input = nazarickLastUserText(messages)`
 *    is the *only* thing read from the message list; image parts are never inspected.
 *  - `ai/.../nazarick/NazarickChat.kt:69` — `nazarickLastUserText` renders the last user
 *    turn via `toText()`, so an image (or image-only) turn degrades to blank text.
 *  - `ai/.../nazarick/NazarickProvider.kt:228` — `generateImage` throws
 *    "Nazarick image generation is not wired (US-004 ships chat only)", and
 *    `NazarickClient.kt` exposes no upload endpoint (only version / auth-verify /
 *    agents / chat / stop).
 *
 * The destination decision itself belongs at the composer send, next to the existing
 * brain-policy→tomb resolution in `PreferencesStore.kt:1613` (`resolveChatModel`, which
 * already picks the enabled `ProviderSetting.Nazarick` for TOMB/AUTO — the natural
 * `tombConfigured` signal). Unblocking needs a ratified image field on the console chat
 * body (or an upload endpoint + reference id) before [ImageRouter] can be wired with no
 * UI changes; until then this stays the pure layer only.
 */
object ImageRouter {

    fun decide(
        policy: BrainPolicy,
        override: ImageDestination?,
        tombConfigured: Boolean,
    ): ImageDestination {
        // 1. EDGE policy: never silently leaves the device, override ignored.
        if (policy == BrainPolicy.EDGE) return ImageDestination.EDGE

        // 2. Explicit per-request override wins over the policy default.
        if (override != null) return override

        // 3. Policy wants the tomb (or AUTO toward it) and a tomb exists.
        val tombRequested =
            policy == BrainPolicy.TOMB || policy == BrainPolicy.AUTO
        if (tombRequested && tombConfigured) return ImageDestination.TOMB

        // 4. Fallback: keep the image on the device.
        return ImageDestination.EDGE
    }
}
