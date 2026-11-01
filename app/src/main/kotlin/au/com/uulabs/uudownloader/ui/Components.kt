package au.com.uulabs.uudownloader.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's one card shape: a slightly lifted surface with a hairline edge. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    border: Color = Uu.Hairline,
    background: Color = Uu.Surface,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, border), shape)
    ) {
        content()
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Uu.Faint) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}

/** A small round tile carrying the site's first letter: one material, no colour coding. */
@Composable
fun SiteBadge(label: String, size: Int = 34) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Uu.Text.copy(alpha = 0.06f))
            .border(1.dp, Uu.Text.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.take(1),
            style = MaterialTheme.typography.labelLarge,
            fontSize = (size * 0.4f).sp,
            color = Uu.Text.copy(alpha = 0.82f),
        )
    }
}

/**
 * The primary action. One per screen, and it says exactly what it will do — including when it
 * cannot do it yet, which is why the disabled label is state-specific rather than "Download".
 */
@Composable
fun UuButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(if (enabled) Uu.Accent else Color.Transparent)
            .border(1.dp, if (enabled) Color.Transparent else Uu.Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) Uu.Ink else Uu.Faint,
        )
    }
}

/** Two or three choices, one of which is lit. */
@Composable
fun QualityPills(
    options: List<au.com.uulabs.uudownloader.core.Quality>,
    selected: au.com.uulabs.uudownloader.core.Quality,
    onSelect: (au.com.uulabs.uudownloader.core.Quality) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier, shape = RoundedCornerShape(14.dp), background = Uu.SurfaceHigh) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (isSelected) Uu.Text.copy(alpha = 0.10f) else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) Uu.Text.copy(alpha = 0.30f) else Color.Transparent,
                            RoundedCornerShape(11.dp),
                        )
                        .clickable { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) Uu.Text else Uu.Muted,
                    )
                }
            }
        }
    }
}

/** A thin progress lane. Drawn by hand so its weight matches the hairline borders around it. */
@Composable
fun ProgressLane(progress: Float?, modifier: Modifier = Modifier) {
    val target = (progress ?: 0f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = target, label = "progress")

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Uu.Hairline)
    ) {
        if (progress == null) {
            // Unknown total: a band travelling the lane reads as "working". A bar parked at a
            // third of the way reads as "stuck", which is the opposite of what it means.
            val lane = maxWidth
            val transition = rememberInfiniteTransition(label = "lane")
            val travel by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "travel",
            )
            Box(
                Modifier
                    .width(lane * 0.32f)
                    .fillMaxHeight()
                    .offset(x = lane * (travel * 1.32f - 0.32f))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Uu.Text, Color.Transparent)
                        )
                    )
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Uu.AccentDim, Uu.Text)))
            )
        }
    }
}

@Composable
fun MutedLine(text: String, modifier: Modifier = Modifier, color: Color = Uu.Muted, maxLines: Int = 2) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, color = Uu.Text, modifier = modifier)
}
