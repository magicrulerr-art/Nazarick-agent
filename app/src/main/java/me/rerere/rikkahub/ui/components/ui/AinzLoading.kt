package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * The tenant thinking indicator: Ainz running with a mana orb (Mare's dashboard sprite,
 * v0.6 — the donor's rabbit animated-vector got retired with the rest of the bunny).
 *
 * One still frame, brought to life by a 560 ms bob+tilt loop: at the 28–32 dp the chat
 * list and the compact dialog request, a real multi-frame run cycle would be mud anyway,
 * and the offset stays under 2 dp so the row layout never looks jittery. The sprite is
 * colored art, so unlike the rabbit it is NOT tinted by the palette — the house gold and
 * crimson ride along in every theme.
 *
 * The `useAppIconStyleLoadingIndicator` toggle keeps its contract: on = mascot, off =
 * Material's contained indicator (the name stays for datastore-key compatibility).
 */
@Composable
fun AinzLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator =
        LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator

    if (!useAppIconStyleLoadingIndicator) {
        ContainedLoadingIndicator(modifier = modifier)
        return
    }

    val transition = rememberInfiniteTransition(label = "ainz-run")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 560, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )

    Image(
        painter = painterResource(id = R.drawable.ainz_running),
        contentDescription = null, // decorative: the streaming text beside it says what's happening
        contentScale = ContentScale.Fit,
        modifier = modifier
            .offset(y = ((phase - 0.5f) * 2.5f).dp)
            .rotate((phase - 0.5f) * 6f),
    )
}
