package tv.frostline.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.frostline.core.data.ResumeEntity
import tv.frostline.core.twitch.Durations
import tv.frostline.core.twitch.FollowedChannel
import tv.frostline.core.twitch.HelixGame
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.SearchChannel
import tv.frostline.core.twitch.Thumbnails
import tv.frostline.core.twitch.sized
import tv.frostline.core.ui.AvatarCircle
import tv.frostline.core.ui.CardSizes
import tv.frostline.core.ui.FrostCard
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostMediaCard
import tv.frostline.core.ui.LiveOverlay
import tv.frostline.core.ui.SizedImage
import tv.frostline.core.ui.ThumbBadge
import tv.frostline.core.ui.rememberPreviewBucket

fun formatViewers(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> "%.1fK".format(n / 1000.0)
    else -> n.toString()
}

/** Small lock shown on a card whose stream Twitch refused to play here (learned when playback hit it). */
@Composable
private fun LockBadge(login: String, modifier: Modifier) {
    val locked by tv.frostline.core.ui.RestrictedStreams.logins.collectAsStateWithLifecycle()
    if (login.lowercase() in locked) ThumbBadge("RESTRICTED", modifier.padding(8.dp).testTag("lock_badge"))
}

/** A live stream: 16:9 preview with the LIVE pill and viewer count, then streamer, title, category and viewers. */
@Composable
fun StreamCard(stream: HelixStream, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val bucket by rememberPreviewBucket()
    FrostMediaCard(
        onClick = onClick,
        modifier = modifier.testTag("stream_card"),
        onLongClick = onLongClick,
        title = stream.userName,
        subtitle = stream.title,
        meta = listOf(stream.gameName, "${formatViewers(stream.viewerCount)} viewers").filter { it.isNotBlank() }.joinToString("  ·  "),
    ) {
        val px = 2
        SizedImage(
            Thumbnails.livePreview(stream.thumbnailUrl, 280 * px, 158 * px, bucket) ?: stream.thumbnailUrl.sized(280 * px, 158 * px),
            CardSizes.StreamWidth, CardSizes.StreamThumbHeight, Modifier.fillMaxSize(),
        )
        LiveOverlay(formatViewers(stream.viewerCount), Modifier.align(Alignment.TopStart))
        LockBadge(stream.userLogin, Modifier.align(Alignment.TopEnd))
    }
}

/** A category: 2:3 box art with the name below. */
@Composable
fun CategoryCard(game: HelixGame, modifier: Modifier, onClick: () -> Unit) {
    FrostMediaCard(
        onClick = onClick, modifier = modifier,
        thumbWidth = CardSizes.BoxartWidth, thumbHeight = CardSizes.BoxartHeight,
        title = game.name,
    ) {
        SizedImage(game.boxArtUrl.sized(280, 420), CardSizes.BoxartWidth, CardSizes.BoxartHeight, Modifier.fillMaxSize())
    }
}

/** A search result channel: the avatar on a card, LIVE pill when on air. */
@Composable
fun ChannelCard(channel: SearchChannel, avatarUrl: String?, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FrostMediaCard(
        onClick = onClick, modifier = modifier, onLongClick = onLongClick,
        title = channel.displayName.ifBlank { channel.login },
        subtitle = if (channel.isLive) channel.gameName else "Offline",
    ) {
        AvatarCircle(avatarUrl?.takeIf { it.isNotEmpty() } ?: channel.thumbnailUrl, 88.dp, Modifier.align(Alignment.Center))
        if (channel.isLive) ThumbBadge("LIVE", Modifier.align(Alignment.TopStart).padding(8.dp), background = FrostColors.Live, color = FrostColors.OnLive, bold = true)
    }
}

@Composable
fun OfflineChip(channel: FollowedChannel, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    FrostCard(onClick, modifier.width(180.dp).height(40.dp), onLongClick = onLongClick) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(channel.displayName, style = MaterialTheme.typography.bodyLarge, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Continue watching: the past broadcast's thumbnail with a progress bar. */
@Composable
fun ResumeCard(item: ResumeEntity, modifier: Modifier, onClick: () -> Unit) {
    FrostMediaCard(
        onClick = onClick, modifier = modifier,
        title = item.channel,
        subtitle = item.title,
        meta = "${Durations.clock(item.position)} of ${Durations.clock(item.duration)}",
    ) {
        if (item.thumbnail.isNotBlank()) SizedImage(item.thumbnail, CardSizes.StreamWidth, CardSizes.StreamThumbHeight, Modifier.fillMaxSize())
        val fraction = if (item.duration > 0) (item.position.toFloat() / item.duration).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(FrostColors.SurfaceAlt))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(4.dp).background(FrostColors.Primary))
    }
}

/** Thumbnail of a large list card: 680x380 px on a 1080p screen (340x191 dp), 16:9. */
internal val WideThumbWidth = 340.dp
internal val WideThumbHeight = 191.dp

/** Focus scale of a large list card: the default 1.05 would push its wide edges into the list margin. */
internal const val WideCardFocusScale = 1.025f

/**
 * Large list card (Live now, Top and category streams, search): a 16:9 thumbnail on the left; on the right the avatar,
 * streamer, title, category and a red-dot viewer count.
 */
@Composable
fun WideStreamCard(stream: HelixStream, avatarUrl: String?, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val bucket by rememberPreviewBucket()
    FrostCard(onClick, modifier.fillMaxWidth().testTag("stream_card"), onLongClick = onLongClick, focusedScale = WideCardFocusScale) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(WideThumbWidth).height(WideThumbHeight)) {
                SizedImage(
                    Thumbnails.livePreview(stream.thumbnailUrl, 680, 383, bucket) ?: stream.thumbnailUrl.sized(680, 383),
                    WideThumbWidth, WideThumbHeight, Modifier.fillMaxSize(),
                )
                LockBadge(stream.userLogin, Modifier.align(Alignment.TopEnd))
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarCircle(avatarUrl, 36.dp)
                    Text(stream.userName, style = MaterialTheme.typography.titleMedium, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(stream.title.ifBlank { " " }, style = MaterialTheme.typography.bodyLarge, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stream.gameName.ifBlank { " " }, style = MaterialTheme.typography.bodyMedium, color = FrostColors.Lavender, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(FrostColors.Live, CircleShape))
                    Text("${formatViewers(stream.viewerCount)} viewers", style = MaterialTheme.typography.bodyMedium, color = FrostColors.Live, maxLines = 1)
                }
            }
        }
    }
}
