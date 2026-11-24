package com.streamone.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamone.app.engine.Post

// ---------------------------------------------------------------------------
// Vega theme (mirrors vega-app: primary tomato #FF6347 on pure black,
// surfaces #171717, text #F2F2F2, secondary text #C4C4C4).
// ---------------------------------------------------------------------------

val Tomato = Color(0xFFFF6347)
val VegaBlack = Color(0xFF000000)
val VegaSurface = Color(0xFF171717)
val VegaSurfaceHigh = Color(0xFF232323)
val VegaText = Color(0xFFF2F2F2)
val VegaSubText = Color(0xFFC4C4C4)
val VegaBorder = Color(0xFF262626)

@Composable
fun StreamOneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tomato,
            onPrimary = Color.White,
            background = VegaBlack,
            onBackground = VegaText,
            surface = VegaSurface,
            onSurface = VegaText,
            surfaceVariant = VegaSurfaceHigh,
            onSurfaceVariant = VegaSubText
        ),
        content = content
    )
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
fun VegaButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.animatedPressScale(interactionSource),
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tomato,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.animatedPressScale(interactionSource),
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.22f),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Text(text = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Tomato)
    }
}

/** Vega-style dark error card with a retry action. */
@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = VegaSurface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Something went wrong",
                color = VegaText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                color = VegaSubText,
                fontSize = 13.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(14.dp))
                VegaButton(text = "Retry", onClick = onRetry)
            }
        }
    }
}

/** Shimmering dark block used by skeleton loading states (Vega style). */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmer"
    )
    // A lighter band sweeping left → right across the base surface colour.
    val brush = Brush.linearGradient(
        colors = listOf(VegaSurfaceHigh, Color(0xFF383838), VegaSurfaceHigh),
        start = Offset(x = -500f + shift * 1600f, y = 0f),
        end = Offset(x = -100f + shift * 1600f, y = 0f)
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(brush)
    )
}

/** One skeleton poster row: title bar + three poster blocks. */
@Composable
fun SkeletonRow() {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        SkeletonBlock(modifier = Modifier.width(120.dp).height(16.dp), corner = 6.dp)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                SkeletonBlock(
                    modifier = Modifier
                        .width(112.dp)
                        .aspectRatio(2f / 3f),
                    corner = 12.dp
                )
            }
        }
    }
}

/** 2:3 poster card with rounded corners and the title underneath (Vega rows). */
@Composable
fun PosterCard(
    post: Post,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier = modifier.pressClickable(pressedScale = 0.96f) { onClick() }) {
        AsyncImage(
            model = post.image,
            contentDescription = post.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(VegaSurface)
        )
        Text(
            text = post.title,
            color = VegaText,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        val tag = post.tag
        if (!tag.isNullOrBlank()) {
            Text(
                text = tag,
                color = VegaSubText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Small pill chip used for quality / type / provider tags. */
@Composable
fun MetaChip(text: String, accent: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) Tomato else VegaSurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = if (accent) Color.White else VegaSubText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** Round translucent icon button used over artwork (back / bookmark). */
@Composable
fun CircleIconButton(
    icon: VegaIconName,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    iconFilled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .pressClickable(pressedScale = 0.92f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        VegaIcon(name = icon, tint = tint, size = 22.dp, filled = iconFilled)
    }
}

/** Header used by sub-screens (Providers / Extensions / About). */
@Composable
fun SubHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleIconButton(icon = VegaIconName.Back, onClick = onBack)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            color = VegaText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** Player time label: m:ss, or h:mm:ss for long content. */
fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
