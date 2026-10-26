package tv.frostline.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Card sizes of the Google TV style rows. */
object CardSizes {
    val StreamWidth: Dp = 280.dp
    val StreamThumbHeight: Dp = 158.dp
    /** Cards of the channel page grid (three per row inside the safe area). */
    val ChannelWidth: Dp = 270.dp
    val ChannelThumbHeight: Dp = 152.dp
    val BoxartWidth: Dp = 140.dp
    val BoxartHeight: Dp = 210.dp
}

/**
 * The one media card (streams, videos, clips, resume items, categories): a thumbnail of exactly [thumbWidth] x
 * [thumbHeight] dp, then the title, a second line and a muted metadata line below it. Unfocused it is transparent
 * (no border); focused it gets the focus treatment. [thumb] receives the thumbnail box (draw the image and any
 * badge in it).
 */
@Composable
fun FrostMediaCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = CardSizes.StreamWidth,
    thumbHeight: Dp = CardSizes.StreamThumbHeight,
    title: String,
    subtitle: String? = null,
    meta: String? = null,
    onLongClick: (() -> Unit)? = null,
    titleBold: Boolean = true,
    thumb: @Composable BoxScope.() -> Unit,
) {
    FrostCard(
        onClick = onClick,
        modifier = modifier.width(thumbWidth),
        onLongClick = onLongClick,
        containerColor = Color.Transparent,
    ) {
        Box(Modifier.width(thumbWidth).height(thumbHeight).clip(FrostTokens.CardShape).background(FrostColors.SurfaceAlt), content = thumb)
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title.ifBlank { " " }, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (titleBold) FontWeight.Medium else FontWeight.Normal,
                color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta.ifBlank { " " }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small label over a thumbnail corner (LIVE, viewers, duration, RESTRICTED). */
@Composable
fun ThumbBadge(text: String, modifier: Modifier = Modifier, background: Color = FrostColors.Plate, color: Color = FrostColors.OnSurface, bold: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
        modifier = modifier.background(background, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The LIVE pill and the viewer count, top-start of a stream thumbnail. */
@Composable
fun LiveOverlay(viewers: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ThumbBadge("LIVE", background = FrostColors.Live, color = FrostColors.OnLive, bold = true)
        ThumbBadge(viewers)
    }
}

/** A static placeholder shaped like a media card (no animation). */
@Composable
fun SkeletonMediaCard(modifier: Modifier = Modifier, thumbWidth: Dp = CardSizes.StreamWidth, thumbHeight: Dp = CardSizes.StreamThumbHeight) {
    Column(modifier.width(thumbWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(thumbWidth).height(thumbHeight).background(FrostColors.SurfaceAlt, FrostTokens.CardShape))
        Box(Modifier.padding(horizontal = 8.dp).width(thumbWidth * 0.6f).height(12.dp).background(FrostColors.SurfaceAlt, RoundedCornerShape(6.dp)))
        Box(Modifier.padding(horizontal = 8.dp).width(thumbWidth * 0.4f).height(10.dp).background(FrostColors.SurfaceAlt, RoundedCornerShape(5.dp)))
    }
}

/** A circular channel avatar with a placeholder fill. */
@Composable
fun AvatarCircle(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(size).height(size).clip(CircleShape).background(FrostColors.SurfaceAlt)) {
        if (!url.isNullOrEmpty()) SizedImage(url, size, size, Modifier.matchParentSize())
    }
}

/**
 * Surface of a side-panel sheet: #1B1726 at 96 %, 16 dp corner on the inner (left) edge, 24 dp padding. It slides in
 * from the right edge with one 200 ms tween (drawn in the graphics layer, so nothing recomposes); no other motion.
 */
@Composable
fun FrostSheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(200, easing = LinearOutSlowInEasing)) }
    Column(
        modifier
            .graphicsLayer { translationX = (1f - progress.value) * size.width }
            .background(FrostColors.Sheet, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Title of a side-panel sheet. */
@Composable
fun FrostSheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = FrostColors.OnSurface, modifier = modifier)
}
