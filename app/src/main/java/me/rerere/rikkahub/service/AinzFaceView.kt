package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The tomb master's face as the floating ball (v0.8, owner request 2026-11-10: "the floating
 * ball... let's make it our mini Ainz... maybe just the face instead of the whole sprite,
 * and hook the emotions to it").
 *
 * The ball's canvas is the sprite **head**, not the chibi: the bundled `ainz_*.png` frames are
 * 192×208 with a big centred skull (~56% of the width across the horns, centred at ~21% of the
 * height), so drawing a source rect of the head scales and centres it for free on every frame —
 * one geometry rule, no per-sprite tuning, and the crown tips clip at the circle exactly like a
 * portrait medallion.
 *
 * Mood mapping rides [AinzFaceMood] (face crops of the very sprites the in-app mascot uses):
 *  - **working** — THINK (studying the problem) with the orbiting light dot kept from the old
 *    halo, so the "agent is busy" signal the pill users rely on survives;
 *  - **idle** — the IDLE skull breathing on its own timer (eyes catch the light),
 *  - **emote** — a one-shot CHEER (won turn) or FAIL (lost turn) that self-expires;
 *  - a **user-picked image** still replaces the face entirely ([setIcon]) — the pick is an
 *    explicit choice and outranks the default art.
 *
 * Every bitmap is an immutable decoded resource, so drawing never allocates; the animation
 * mutates only floats. Like [HaloBallView], the caller only sizes the view.
 */
internal class AinzFaceView(context: Context) : View(context) {

    companion object {
        /** Head source rect over the 192×208 sprites, as fractions (see class doc). */
        const val HEAD_X_FRACTION = 0.22f
        const val HEAD_Y_FRACTION = -0.08f
        const val HEAD_W_FRACTION = 0.56f
        const val HEAD_H_FRACTION = 0.50f

        /** Bloom around the medallion, out of 255. */
        const val BLOOM_ALPHA = 96

        /** One-shot emote length. Long enough to read, short enough not to nag. */
        const val EMOTE_MS = 2600L

        /** Idle is a slow cycle between rest and a glance up, like [rememberAinzIdleMood]. */
        const val IDLE_WAVE_PERIOD_MS = 5200L
        const val IDLE_GLITCH_PROBABILITY = 0.006f

        const val TWO_PI = (2.0 * PI).toFloat()
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val src = Rect()
    private val dst = Rect()

    /** Accent for the working orbit and the medallion bloom (theme primary / user pick). */
    var accentColor: Int = 0xFF6750A4.toInt()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // ---- Frames ----

    private val frames: Map<AinzFaceMood, Bitmap> = AinzFaceSprite.frames(context)
    private var icon: Bitmap? = null

    /** A user-picked bitmap replaces the master's face; null restores the default. */
    fun setIcon(bitmap: Bitmap?) {
        if (icon === bitmap) return
        icon = bitmap
        invalidate()
    }

    // ---- State ----

    private var working = false
    private var emoteUntilMs = 0L
    private var emoteMood: AinzFaceMood? = null

    /** The orbit is on while a turn runs; the face becomes THINK while it does. */
    fun setWorking(value: Boolean) {
        if (working == value) return
        working = value
        if (value) startLoop() else stopLoop()
        invalidate()
    }

    /** One-shot emote; null or expiry falls back to idle/working. Called on main. */
    fun playEmote(mood: AinzFaceMood?) {
        emoteMood = mood
        emoteUntilMs = if (mood == null) 0L else System.currentTimeMillis() + EMOTE_MS
        if (mood != null) startLoop()
        invalidate()
    }

    /** Which frame to draw right now. Pure given (emoteMood, now, working, idleTick). */
    fun moodForNow(nowMs: Long): AinzFaceMood = when {
        emoteMood != null && nowMs < emoteUntilMs -> emoteMood!!
        working -> AinzFaceMood.THINK
        else -> AinzFaceMood.IDLE
    }

    // ---- Animation clock (same Choreographer pattern as HaloBallView) ----

    private var looping = false
    private var startedMs = 0L
    private var idleTick = 0f
    private var orbitAngle = 0f
    private var lastFrameMs = 0.0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val nowMs = frameTimeNanos / 1_000_000.0
            val now = System.currentTimeMillis()
            if (lastFrameMs == 0.0) lastFrameMs = nowMs
            if (startedMs == 0L) startedMs = now
            val dt = ((nowMs - lastFrameMs) / 1000.0).coerceIn(0.0, 0.05).toFloat()
            lastFrameMs = nowMs

            val elapsed = now - startedMs
            idleTick = (elapsed % IDLE_WAVE_PERIOD_MS) / IDLE_WAVE_PERIOD_MS.toFloat()
            orbitAngle = (orbitAngle + dt * HaloBallMotion.omega(elapsed / 1000f)) % TWO_PI

            val emoteLive = emoteMood != null && now < emoteUntilMs
            if (!emoteLive && !working) stopLoop()
            invalidate()
            if (looping) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping || !isAttachedToWindow) return
        looping = true
        lastFrameMs = 0.0
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (working || (emoteMood != null && System.currentTimeMillis() < emoteUntilMs)) startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f

        val mood = moodForNow(System.currentTimeMillis())
        val frame = icon ?: frames[mood] ?: frames[AinzFaceMood.IDLE]

        // Bloom first, so the medallion sits on top of its own glow.
        fillPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                withAlpha(accentColor, 0),
                withAlpha(accentColor, BLOOM_ALPHA),
                withAlpha(accentColor, 0),
            ),
            floatArrayOf(0.72f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        if (frame != null && !frame.isRecycled) {
            clip.reset()
            clip.addCircle(cx, cy, r * 0.94f, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            // Frames arrive pre-cropped to the head (see AinzFaceSprite.frames), so the whole
            // bitmap fills the medallion; the user-picked icon centre-crops like the old ball.
            val side = min(frame.width, frame.height)
            src.set(
                (frame.width - side) / 2,
                (frame.height - side) / 2,
                (frame.width - side) / 2 + side,
                (frame.height - side) / 2 + side,
            )
            dst.set(0, 0, w.toInt(), h.toInt())
            // Idle gets a micro bob and the working orbit a lean, both via draw offset —
            // cheaper than matrix math here and invisible at 56 dp.
            val bob = when {
                mood == AinzFaceMood.IDLE -> sin(idleTick * TWO_PI) * r * 0.02f
                mood == AinzFaceMood.CHEER -> -abs(sin(idleTick * PI.toFloat() * 2f)) * r * 0.06f
                else -> 0f
            }
            canvas.translate(0f, bob)
            canvas.drawBitmap(frame, src, dst, bitmapPaint)
            canvas.restore()
        }

        // Working orbit — the pill-era signal, kept: a grey track plus the comet dot.
        if (working && icon == null) {
            ringPaint.color = withAlpha(HaloBallView.TRACK_GREY, HaloBallView.TRACK_ALPHA)
            ringPaint.strokeWidth = r * HaloBallView.STROKE_FRACTION * 0.8f
            canvas.drawCircle(cx, cy, r * HaloBallView.RING_RADIUS_FRACTION, ringPaint)
            val rr = r * HaloBallView.RING_RADIUS_FRACTION
            val dx = cx + rr * cos(orbitAngle)
            val dy = cy + rr * sin(orbitAngle)
            fillPaint.shader = RadialGradient(
                dx, dy, r * 0.3f,
                intArrayOf(withAlpha(Color.WHITE, 255), withAlpha(accentColor, 110), withAlpha(accentColor, 0)),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(dx, dy, r * 0.3f, fillPaint)
            fillPaint.shader = null
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
