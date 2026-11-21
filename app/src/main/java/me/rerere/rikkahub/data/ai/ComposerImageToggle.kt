package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.model.BrainPolicy

/**
 * US-011r AC1: pure state helpers behind the composer Edge|Tomb image-destination
 * toggle. No Compose, no Android, no side effects — the composer owns only the
 * selected [ImageDestination]; both the first value shown and whether Tomb is even
 * selectable are decided here, so the rules stay unit-testable and identical to
 * [ImageRouter].
 */

/**
 * The destination the composer pre-selects when an image is attached: the router
 * decision with no user override yet, so the assistant's [BrainPolicy] plus whether
 * a tomb endpoint is [tombConfigured] choose the default.
 */
fun initialChoice(policy: BrainPolicy, tombConfigured: Boolean): ImageDestination =
    ImageRouter.decide(policy, null, tombConfigured)

/**
 * Whether the Tomb option may be chosen at all. Under `BrainPolicy.EDGE` the image
 * must never silently leave the device — same absolute rule as
 * `ImageRouter.decide` step 1 — so the toggle renders Tomb disabled rather than
 * letting the user pick a value the router would ignore.
 */
fun tombEnabled(policy: BrainPolicy): Boolean = policy != BrainPolicy.EDGE
