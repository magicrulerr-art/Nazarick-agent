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
