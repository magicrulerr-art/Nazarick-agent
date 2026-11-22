package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import me.rerere.rikkahub.R

/**
 * The tomb master's sprite moods — the app's mascot reaction system (owner request, v0.7:
 * "there are lots of sprites, so we can show more emotions, randomize it or use specific for
 * different actions").
 *
 * Each mood names one animation recipe over the bundled pixel sprites
 * (`drawable-nodpi/ainz_*.png`). Where the mood maps is deliberate, not random:
 *  - [AinzMood.THINK] rides the reasoning chip, [AinzMood.TOOLS] a tool in flight,
 *    [AinzMood.WAIT] an approval prompt (arms crossed — the pose that means "your call"),
 *  - [AinzMood.WALK] is "the master is busy generating", [AinzMood.FAIL] a generation error,
 *  - [AinzMood.IDLE] fronts the empty quick-chat panel and waves on its own timer (see
 *    [rememberAinzIdleMood]) so the panel never sits dead.
 *
 * The randomized parts (which foot leads a gait, the phase of each wobble, when a wave lands)
 * come from [Random.Default] per composition — two mascots on screen never beat in lockstep.
 */
enum class AinzMood {
    IDLE,
    THINK,
    TOOLS,
    WAIT,
    WALK,
    CHEER,
    FAIL,
}

/** Sprite frames per mood. `ainz_waiting` doubles as the arms-crossed WAIT pose — the one
 *  frame that needs no motion to read as "waiting on you". `runl/runr` alternate for WALK
 *  (fast gait) and TOOLS (half-speed, "off and doing"). */
private fun framesFor(mood: AinzMood): List<Int> = when (mood) {
    AinzMood.IDLE -> listOf(R.drawable.ainz_idle)
    AinzMood.THINK -> listOf(R.drawable.ainz_review)
    AinzMood.TOOLS -> listOf(R.drawable.ainz_runl, R.drawable.ainz_runr)
    AinzMood.WAIT -> listOf(R.drawable.ainz_waiting)
    AinzMood.WALK -> listOf(R.drawable.ainz_runl, R.drawable.ainz_runr)
    AinzMood.CHEER -> listOf(R.drawable.ainz_jump)
    AinzMood.FAIL -> listOf(R.drawable.ainz_failed)
}

/** Frame tick in ms for multi-frame gaits; null = single frame, no alternation. */
private fun frameTickMs(mood: AinzMood): Long? = when (mood) {
    AinzMood.WALK -> 160L
    AinzMood.TOOLS -> 320L
    else -> null
}

/**
 * Mini Ainz, drawn from the bundled pixel sprites.
 *
 * Two motion mechanisms, both cheap:
 *  - multi-frame moods (WALK/TOOLS) swap preloaded painters on a timer;
 *  - single-frame moods (IDLE bob, THINK lean, CHEER hop) ride one `graphicsLayer` channel
 *    fed by an infinite sine — recomposition never re-rasterizes the sprite.
 *
 * Sprite proportions (192×208) are kept by [ContentScale.Fit]; pass the height you have room
 * for in [size].
 *
 * @param mood which reaction to show, see [AinzMood]
 * @param animated turn off every animation (static frame) — callers inside a heavy streaming
 *   frame pass false to keep the cost at one draw
 */
@Composable
fun AinzMascot(
    mood: AinzMood,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    animated: Boolean = true,
) {
    val frames = remember(mood) { framesFor(mood) }
    val tick = frameTickMs(mood)

    var frameIndex by remember(mood) {
        // Random lead frame so two mascots of the same mood are never in lockstep.
        mutableIntStateOf(if (frames.size > 1 && animated) Random.nextInt(frames.size) else 0)
    }
    LaunchedEffect(mood, tick, animated) {
        val interval = tick ?: return@LaunchedEffect
        if (!animated) return@LaunchedEffect
        while (true) {
            delay(interval)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    // Sine phase for the wobble moods; random per-composition offset desyncs siblings.
    val phaseOffset = remember { Random.nextFloat() }
    val wobbleMood = animated && mood in setOf(AinzMood.IDLE, AinzMood.THINK, AinzMood.CHEER)
    var wobble by remember { mutableStateOf(phaseOffset) }
    LaunchedEffect(mood, animated) {
        if (!wobbleMood) return@LaunchedEffect
        val periodMs = if (mood == AinzMood.CHEER) 900L else 2600L
        val started = System.currentTimeMillis()
        while (true) {
            val t = (System.currentTimeMillis() - started) / periodMs.toFloat()
            wobble = (t + phaseOffset) % 1f
            delay(32L) // ~30 fps — enough for a sprite this small
        }
    }
    val wave = sin(wobble * 2.0 * PI).toFloat()
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }

    Image(
        painter = painterResource(id = frames[frameIndex]),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(width = size * (192f / 208f), height = size)
            .graphicsLayer {
                when (mood) {
                    // Gentle breathing bob.
                    AinzMood.IDLE -> translationY = wave * px * 0.03f
                    // Thinking lean, side to side.
                    AinzMood.THINK -> rotationZ = wave * 1.8f
                    // Hop: up off the ground and back.
                    AinzMood.CHEER -> translationY = -abs(wave) * px * 0.10f
                    // The two-frame gaits get a tiny bounce synced to the frame swap so the
                    // alternation reads as steps, not a slideshow.
                    AinzMood.WALK, AinzMood.TOOLS ->
                        translationY = if (frameIndex == 0) -px * 0.02f else px * 0.02f
                    AinzMood.WAIT, AinzMood.FAIL -> Unit // static poses
                }
            },
    )
}

/**
 * Idle mood with scheduled waves for quiet surfaces (empty quick-chat): stays [AinzMood.IDLE]
 * and briefly switches to [AinzMood.CHEER] on a randomized timer so the mascot looks alive
 * without demanding attention. The first wave lands 6–14 s after the screen opens, then every
 * 14–28 s; each wave lasts ~2.2 s. (CHEER's hop sprite sells "hello" at a glance; the static
 * hand-raise frame reads poorly under 40 dp.)
 */
@Composable
fun rememberAinzIdleMood(): AinzMood {
    var waving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(6_000L, 14_000L))
            waving = true
            delay(2_200L)
            waving = false
            delay(Random.nextLong(14_000L, 28_000L))
        }
    }
    return if (waving) AinzMood.CHEER else AinzMood.IDLE
}
