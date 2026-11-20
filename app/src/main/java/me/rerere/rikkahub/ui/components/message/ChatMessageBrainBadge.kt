package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.chat.brainBadgeLabel

/**
 * US-009r: the chip naming the brain that answered a single message, from the `brainKind`
 * recorded on that message at finalize time — not from the model selected now, which is what
 * the top bar reads.
 *
 * Draws nothing when there is no brain recorded, which covers every message persisted before
 * this card: old history gains no badge (AC3). The wording comes from the same table as the
 * top bar's badge, so a bubble and the bar can never call the same brain different things.
 */
@Composable
fun BrainBadgeChip(stored: String, modifier: Modifier = Modifier) {
    val label = brainBadgeLabel(stored)
    if (label.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
