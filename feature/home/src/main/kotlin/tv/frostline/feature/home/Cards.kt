package tv.frostline.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.frostline.core.data.ResumeEntity
import tv.frostline.core.twitch.FollowedChannel
import tv.frostline.core.twitch.HelixGame
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.twitch.SearchChannel
import tv.frostline.core.twitch.sized
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.cardScale
import tv.frostline.core.ui.cardShape
import tv.frostline.core.ui.focusRingBorder

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CardSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    focusedScale: Float = 1.04f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = cardShape(),
        border = focusRingBorder(),
        scale = cardScale(focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FrostColors.Surface,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) { content() }
}

@Composable
private fun Line(text: String, size: Int, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    Text(
        text,
        fontSize = size.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

fun formatViewers(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> "%.1fK".format(n / 1000.0)
    else -> n.toString()
}

@Composable
fun StreamCard(stream: HelixStream, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    CardSurface(onClick, modifier, onLongClick = onLongClick) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = stream.thumbnailUrl.sized(440, 248),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "${formatViewers(stream.viewerCount)} viewers",
                    fontSize = 10.sp,
                    color = FrostColors.Background,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(FrostColors.Live.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(stream.userName, 13, FrostColors.OnSurface, bold = true)
                Line(stream.title.ifBlank { " " }, 11, FrostColors.Muted)
                Line(stream.gameName.ifBlank { " " }, 11, FrostColors.Lavender)
            }
        }
    }
}

@Composable
fun CategoryCard(game: HelixGame, modifier: Modifier, onClick: () -> Unit) {
    CardSurface(onClick, modifier) {
        Column {
            AsyncImage(
                model = game.boxArtUrl.sized(285, 380),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
            )
            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { Line(game.name, 12, FrostColors.OnSurface, bold = true) }
        }
    }
}

@Composable
fun ChannelCard(channel: SearchChannel, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    CardSurface(onClick, modifier, onLongClick = onLongClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(
                model = channel.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(channel.displayName.ifBlank { channel.login }, 13, FrostColors.OnSurface, bold = true)
                Line(if (channel.isLive) "LIVE  ${channel.gameName}" else "Offline", 11, if (channel.isLive) FrostColors.Live else FrostColors.Muted)
            }
        }
    }
}

@Composable
fun OfflineChip(channel: FollowedChannel, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    CardSurface(onClick, modifier.fillMaxWidth().height(40.dp), onLongClick = onLongClick) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Line(channel.displayName, 13, FrostColors.OnSurface)
        }
    }
}

@Composable
fun ResumeCard(item: ResumeEntity, modifier: Modifier, onClick: () -> Unit) {
    CardSurface(onClick, modifier) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                if (item.thumbnail.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(FrostColors.SurfaceAlt))
                }
                val fraction = if (item.duration > 0) (item.position.toFloat() / item.duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(FrostColors.SurfaceAlt))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(4.dp).background(FrostColors.Primary))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Line(item.channel, 13, FrostColors.OnSurface, bold = true)
                Line(item.title.ifBlank { " " }, 11, FrostColors.Muted)
                Line(
                    "${tv.frostline.core.twitch.Durations.clock(item.position)} of ${tv.frostline.core.twitch.Durations.clock(item.duration)}",
                    11, FrostColors.Lavender,
                )
            }
        }
    }
}

/** Following page card: 16:9 thumbnail (~340 dp) on the left, channel details on the right. */
@Composable
fun WideStreamCard(stream: HelixStream, avatarUrl: String?, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    CardSurface(onClick, modifier.fillMaxWidth(), focusedScale = 1f, onLongClick = onLongClick) {
        Row(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = stream.thumbnailUrl.sized(680, 383),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(340.dp).aspectRatio(16f / 9f),
            )
            Column(
                Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(FrostColors.SurfaceAlt)) {
                        if (!avatarUrl.isNullOrEmpty()) {
                            AsyncImage(avatarUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Line(stream.userName, 18, FrostColors.OnSurface, bold = true)
                }
                Line(stream.title.ifBlank { " " }, 15, FrostColors.OnSurface)
                Line(stream.gameName.ifBlank { " " }, 14, FrostColors.Lavender)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(FrostColors.Live))
                    Line("${formatViewers(stream.viewerCount)} viewers", 14, FrostColors.Live)
                }
            }
        }
    }
}
