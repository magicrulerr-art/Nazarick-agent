package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.AinzMood

/**
 * Face crops of the mascot sprites for the floating ball (v0.8).
 *
 * The mood vocabulary is [AinzMood] — the same enum the in-app mascot uses, so the turn
 * outcome plumbed through [LoopHostBridge.turnFinished] means the same thing on the ball as
 * on the chat screen. The ball only ever shows a subset: IDLE, THINK (working), CHEER, FAIL.
 * [AinzFaceMood] is that subset as its own type so [AinzFaceView] cannot be handed a mood it
 * has no frame for (a WALK face would be a leg).
 */
internal enum class AinzFaceMood {
    IDLE,
    THINK,
    CHEER,
    FAIL;

    companion object {
        /** The ball's face for an in-app mascot mood. WALK/TOOLS/WAIT all read as "busy" → THINK. */
        fun of(mood: AinzMood): AinzFaceMood = when (mood) {
            AinzMood.CHEER -> CHEER
            AinzMood.FAIL -> FAIL
            AinzMood.IDLE -> IDLE
            else -> THINK
        }
    }
}

internal object AinzFaceSprite {

    /** Sprite drawable per ball mood — the head crops of the mascot's own frames. */
    private fun drawableFor(mood: AinzFaceMood): Int = when (mood) {
        AinzFaceMood.IDLE -> R.drawable.ainz_idle
        AinzFaceMood.THINK -> R.drawable.ainz_review
        AinzFaceMood.CHEER -> R.drawable.ainz_jump
        AinzFaceMood.FAIL -> R.drawable.ainz_failed
    }

    /**
     * Decode every face frame once per view, downsampled to roughly ball size (same
     * sample-size trick the picked-image icon uses in FloatingBallService.loadIcon).
     * Returns an empty map on any decode failure — the view degrades to bloom-only
     * rather than crashing the overlay.
     */
    fun frames(context: Context): Map<AinzFaceMood, Bitmap> {
        val target = (56 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return AinzFaceMood.entries.mapNotNull { mood ->
            runCatching {
                val res = drawableFor(mood)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(context.resources, res, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
                    sample *= 2
                }
                val full = BitmapFactory.decodeResource(
                    context.resources, res, BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return@runCatching null
                // Crop once, at load, so onDraw only ever scales the head rect of a small bitmap.
                val w = full.width
                val h = full.height
                val left = (w * AinzFaceView.HEAD_X_FRACTION).toInt().coerceIn(0, w - 1)
                val top = (h * AinzFaceView.HEAD_Y_FRACTION).toInt().coerceIn(0, h - 1)
                val right = (w * (AinzFaceView.HEAD_X_FRACTION + AinzFaceView.HEAD_W_FRACTION)).toInt().coerceIn(left + 1, w)
                val bottom = (h * (AinzFaceView.HEAD_Y_FRACTION + AinzFaceView.HEAD_H_FRACTION)).toInt().coerceIn(top + 1, h)
                val head = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                if (head !== full) full.recycle()
                mood to head
            }.getOrNull()
        }.toMap()
    }
}
