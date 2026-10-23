package tv.frostline.feature.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.frostline.core.twitch.ClipPeriod
import tv.frostline.core.twitch.Counts
import tv.frostline.core.twitch.Durations
import tv.frostline.core.twitch.HelixClip
import tv.frostline.core.twitch.HelixVideo
import tv.frostline.core.twitch.Thumbnails
import tv.frostline.core.twitch.VideoKind
import tv.frostline.core.ui.FocusMemory
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton
import tv.frostline.core.ui.cardScale
import tv.frostline.core.ui.cardShape
import tv.frostline.core.ui.focusRingBorder
import tv.frostline.core.ui.rememberFocusIn
import tv.frostline.core.ui.tryFocus

private val OverscanX = 48.dp
private val OverscanY = 27.dp

/** Test tags. */
object ChannelTags {
    const val Header = "channel_header"
    const val WatchLive = "channel_watch_live"
    const val Status = "channel_status"
    const val TabPrefix = "channel_tab_"
    const val KindPrefix = "channel_kind_"
    const val PeriodPrefix = "channel_period_"
    const val VideoPrefix = "video_"
    const val ClipPrefix = "clip_"
    const val Placeholder = "thumb_placeholder_"
    const val About = "channel_about"
    const val Retry = "channel_retry"
    const val Empty = "channel_empty"
}

class ChannelActions(
    val onWatchLive: () -> Unit,
    val onOpenVod: (String) -> Unit,
    val onOpenClip: (String) -> Unit,
    val onTab: (ChannelTab) -> Unit,
    val onKind: (VideoKind) -> Unit,
    val onPeriod: (ClipPeriod) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
)

@Composable
fun ChannelScreen(
    viewModel: ChannelViewModel,
    onWatchLive: (String) -> Unit,
    onOpenVod: (String) -> Unit,
    onOpenClip: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        ChannelActions(
            onWatchLive = { onWatchLive(viewModel.state.value.login) },
            onOpenVod = onOpenVod,
            onOpenClip = onOpenClip,
            onTab = viewModel::selectTab,
            onKind = viewModel::selectVideoKind,
            onPeriod = viewModel::selectClipPeriod,
            onLoadMore = viewModel::loadMore,
            onRetry = viewModel::retry,
        )
    }
    ChannelContent(state, actions, viewModel.memory)
}

/**
 * Stateless channel page: one lazy grid whose first rows (header, tabs, filters) are focusable and whose
 * cards follow, so Up/Down/Left/Right walk the whole page and the grid scrolls with focus.
 */
@Composable
fun ChannelContent(state: ChannelState, actions: ChannelActions, memory: FocusMemory) {
    val grid = rememberLazyGridState(memory.scrollIndex, memory.scrollOffset)
    DisposableEffect(grid) {
        onDispose {
            memory.scrollIndex = grid.firstVisibleItemIndex
            memory.scrollOffset = grid.firstVisibleItemScrollOffset
        }
    }
    val restore = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val subFocus = remember { FocusRequester() }
    val restoring = memory.key != null
    val ready = !state.loading
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        if (restoring) {
            // Back from a video: the card that was opened gets focus again (its row is composed from the saved scroll).
            repeat(6) {
                withFrameNanos { }
                if (restore.tryFocus()) return@LaunchedEffect
            }
        }
        repeat(6) {
            withFrameNanos { }
            if (firstFocus.tryFocus()) return@LaunchedEffect
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        state = grid,
        modifier = Modifier.fillMaxSize().testTag("channel_grid"),
        contentPadding = PaddingValues(start = 48.dp, end = OverscanX, top = OverscanY, bottom = OverscanY),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { Header(state, actions, firstFocus.takeIf { state.live != null }) }
        if (state.error != null) {
            item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.error, fontSize = 16.sp, color = FrostColors.OnSurface)
                    TvButton("Retry", actions.onRetry, Modifier.testTag(ChannelTags.Retry).focusRequester(if (state.live == null) firstFocus else FocusRequester()))
                }
            }
            return@LazyVerticalGrid
        }
        item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChannelTab.entries.forEach { t ->
                    Chip(
                        t.label, state.tab == t, { actions.onTab(t) },
                        Modifier.testTag(ChannelTags.TabPrefix + t.name)
                            .focusProperties { if (state.tab != ChannelTab.About) down = subFocus }
                            .then(if (t == state.tab) Modifier.focusRequester(tabFocus) else Modifier)
                            .then(if (t == state.tab && state.live == null) Modifier.focusRequester(firstFocus) else Modifier),
                    )
                }
            }
        }
        when (state.tab) {
            ChannelTab.Videos -> {
                item(key = "kinds", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VideoKind.entries.forEach { k -> Chip(k.label, state.videoKind == k, { actions.onKind(k) }, Modifier.testTag(ChannelTags.KindPrefix + k.name).focusProperties { up = tabFocus }.then(if (state.videoKind == k) Modifier.focusRequester(subFocus) else Modifier)) }
                    }
                }
                videoItems(state, actions, memory, restore)
            }
            ChannelTab.Clips -> {
                item(key = "periods", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClipPeriod.entries.forEach { p -> Chip(p.label, state.clipPeriod == p, { actions.onPeriod(p) }, Modifier.testTag(ChannelTags.PeriodPrefix + p.name).focusProperties { up = tabFocus }.then(if (state.clipPeriod == p) Modifier.focusRequester(subFocus) else Modifier)) }
                    }
                }
                clipItems(state, actions, memory, restore)
            }
            ChannelTab.About -> item(key = "about", span = { GridItemSpan(maxLineSpan) }) { About(state) }
        }
    }
}

@Composable
private fun Header(state: ChannelState, actions: ChannelActions, watchLiveFocus: FocusRequester?) {
    Row(
        Modifier.fillMaxWidth().testTag(ChannelTags.Header),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(FrostColors.SurfaceAlt)) {
            val avatar = state.user?.profileImageUrl.orEmpty()
            if (avatar.isNotEmpty()) AsyncImage(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.displayName, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FrostColors.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val live = state.live
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (live != null) FrostColors.Live else FrostColors.Muted))
                Text(
                    if (live != null) "LIVE  ${Counts.compact(live.viewerCount.toLong())} viewers" else if (state.loading) "Loading..." else "Offline",
                    fontSize = 14.sp,
                    color = if (live != null) FrostColors.Live else FrostColors.Muted,
                    modifier = Modifier.testTag(ChannelTags.Status),
                )
            }
            if (live != null) {
                Text(
                    listOf(live.title, live.gameName).filter { it.isNotBlank() }.joinToString("  ·  "),
                    fontSize = 14.sp, color = FrostColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.live != null) {
            TvButton("Watch live", actions.onWatchLive, Modifier.testTag(ChannelTags.WatchLive).then(if (watchLiveFocus != null) Modifier.focusRequester(watchLiveFocus) else Modifier))
        }
    }
}

private fun LazyGridScope.videoItems(state: ChannelState, actions: ChannelActions, memory: FocusMemory, restore: FocusRequester) {
    val list = state.currentVideos
    val total = list.items.size
    items(list.items.size, key = { "v:" + list.items[it].id }) { i ->
        val v = list.items[i]
        val key = "v:${v.id}"
        if (i >= total - PAGE_AHEAD) LaunchedEffect(total) { actions.onLoadMore() }
        VideoCard(
            v, state.user?.offlineImageUrl, state.user?.displayName.orEmpty(),
            Modifier.rememberFocusIn(key, memory).then(if (memory.key == key) Modifier.focusRequester(restore) else Modifier),
        ) { actions.onOpenVod(v.id) }
    }
    footer(list.loading, list.error, list.loaded && list.items.isEmpty(), "Nothing here yet.", actions)
}

private fun LazyGridScope.clipItems(state: ChannelState, actions: ChannelActions, memory: FocusMemory, restore: FocusRequester) {
    val list = state.currentClips
    val total = list.items.size
    items(list.items.size, key = { "c:" + list.items[it].id }) { i ->
        val c = list.items[i]
        val key = "c:${c.id}"
        if (i >= total - PAGE_AHEAD) LaunchedEffect(total) { actions.onLoadMore() }
        ClipCard(c, Modifier.rememberFocusIn(key, memory).then(if (memory.key == key) Modifier.focusRequester(restore) else Modifier)) { actions.onOpenClip(c.id) }
    }
    footer(list.loading, list.error, list.loaded && list.items.isEmpty(), "No clips in this period.", actions)
}

private const val PAGE_AHEAD = 6

private fun LazyGridScope.footer(loading: Boolean, error: String?, empty: Boolean, emptyText: String, actions: ChannelActions) {
    when {
        error != null -> item(key = "footer-error", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, fontSize = 14.sp, color = FrostColors.Muted)
                TvButton("Retry", actions.onRetry, Modifier.testTag(ChannelTags.Retry))
            }
        }
        loading -> item(key = "footer-loading", span = { GridItemSpan(maxLineSpan) }) { Text("Loading...", fontSize = 14.sp, color = FrostColors.Muted) }
        empty -> item(key = "footer-empty", span = { GridItemSpan(maxLineSpan) }) { Text(emptyText, fontSize = 14.sp, color = FrostColors.Muted, modifier = Modifier.testTag(ChannelTags.Empty)) }
    }
}

@Composable
private fun About(state: ChannelState) {
    Column(Modifier.fillMaxWidth().testTag(ChannelTags.About), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val u = state.user
        Text(
            u?.description?.ifBlank { null } ?: "This channel has no description.",
            fontSize = 16.sp, color = FrostColors.OnSurface,
        )
        val facts = buildList {
            u?.broadcasterType?.takeIf { it.isNotBlank() }?.let { add("Channel type" to it.replaceFirstChar { c -> c.uppercase() }) }
            u?.createdAt?.takeIf { it.isNotBlank() }?.let { add("Joined" to Counts.date(it)) }
            state.live?.let {
                it.gameName.takeIf { g -> g.isNotBlank() }?.let { g -> add("Playing" to g) }
                if (it.startedAt.isNotBlank()) add("Live since" to Counts.date(it.startedAt))
            }
        }
        facts.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k, fontSize = 14.sp, color = FrostColors.Muted, modifier = Modifier.size(width = 130.dp, height = 22.dp))
                Text(v, fontSize = 14.sp, color = FrostColors.OnSurface)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = cardShape(RoundedCornerShape(20.dp)),
        border = focusRingBorder(RoundedCornerShape(20.dp)),
        scale = cardScale(1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FrostColors.Primary else FrostColors.Surface,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = if (selected) FrostColors.Primary else FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CardSurface(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = cardShape(),
        border = focusRingBorder(),
        scale = cardScale(1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FrostColors.Surface,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) { content() }
}

/** A thumbnail, or a placeholder when the URL is missing or one of Twitch's 404/processing images. */
@Composable
private fun Thumb(url: String?, id: String, modifier: Modifier = Modifier, label: String = "") {
    if (url == null) {
        Box(modifier.background(FrostColors.SurfaceAlt).testTag(ChannelTags.Placeholder + id), contentAlignment = Alignment.Center) {
            Text(label.ifBlank { "No preview" }, fontSize = 12.sp, color = FrostColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    } else {
        AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text, fontSize = 10.sp, color = FrostColors.OnSurface,
        modifier = modifier.padding(6.dp).background(androidx.compose.ui.graphics.Color(0xCC050507), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A small padlock: this video is restricted (subscriber-only). */
@Composable
private fun LockBadge(modifier: Modifier) {
    androidx.compose.foundation.Canvas(
        modifier.padding(6.dp).background(androidx.compose.ui.graphics.Color(0xCC050507), RoundedCornerShape(4.dp)).padding(4.dp).size(12.dp)
            .semantics { contentDescription = "Restricted video" },
    ) {
        val c = FrostColors.OnSurface
        val w = size.width
        val h = size.height
        // shackle
        drawArc(c, 180f, 180f, false, androidx.compose.ui.geometry.Offset(w * 0.22f, 0f), androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.14f))
        // body
        drawRoundRect(c, androidx.compose.ui.geometry.Offset(0f, h * 0.38f), androidx.compose.ui.geometry.Size(w, h * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
    }
}

@Composable
private fun CardText(title: String, sub: String) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.ifBlank { " " }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(sub, fontSize = 11.sp, color = FrostColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun VideoCard(video: HelixVideo, offlineImage: String?, channelName: String, modifier: Modifier, onClick: () -> Unit) {
    CardSurface(modifier.testTag(ChannelTags.VideoPrefix + video.id), onClick) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                // A live archive has a blank thumbnail: the channel's offline image stands in, else a placeholder.
                Thumb(Thumbnails.videoOrOffline(video.thumbnailUrl, offlineImage, 440, 248), video.id, Modifier.fillMaxSize(), channelName)
                if (video.durationMs > 0) Badge(Durations.clock(video.durationMs), Modifier.align(Alignment.BottomEnd))
                if (video.restricted) LockBadge(Modifier.align(Alignment.TopStart).testTag("lock_${video.id}"))
            }
            CardText(video.title, listOf(Counts.date(video.createdAt), "${Counts.compact(video.viewCount)} views").filter { it.isNotBlank() }.joinToString("  ·  "))
        }
    }
}

@Composable
fun ClipCard(clip: HelixClip, modifier: Modifier, onClick: () -> Unit) {
    CardSurface(modifier.testTag(ChannelTags.ClipPrefix + clip.id), onClick) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Thumb(Thumbnails.clip(clip.thumbnailUrl), clip.id, Modifier.fillMaxSize())
                if (clip.duration > 0) Badge(Durations.clock((clip.duration * 1000).toLong()), Modifier.align(Alignment.BottomEnd))
            }
            CardText(clip.title, listOf("${Counts.compact(clip.viewCount)} views", Counts.date(clip.createdAt)).joinToString("  ·  "))
        }
    }
}
