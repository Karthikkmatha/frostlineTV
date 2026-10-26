@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.frostline.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.ui.CardSizes
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostButtonStyle
import tv.frostline.core.ui.FrostChip
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.SkeletonMediaCard
import tv.frostline.core.ui.TvTextField
import tv.frostline.core.ui.tryFocus

/** A full-width heading (not focusable). */
private fun header(key: String, text: String, color: Color = FrostColors.OnSurface) =
    GridEntry(key, fullSpan = true, focusable = false) { Text(text, style = MaterialTheme.typography.headlineSmall, color = color) }

private fun note(key: String, text: String) =
    GridEntry(key, fullSpan = true, focusable = false) { Text(text, style = MaterialTheme.typography.bodySmall) }

/** Placeholder rows of large list cards while the list loads, so the page has its shape before the data arrives. */
private fun listSkeletons(key: String, n: Int = 4) = (0 until n).map { i ->
    GridEntry("$key:sk$i", fullSpan = true, focusable = false) { SkeletonMediaCard(thumbWidth = WideThumbWidth, thumbHeight = WideThumbHeight) }
}

/** Placeholder box-art cards for the category grid. */
private fun boxartSkeletons(key: String, n: Int) = (0 until n).map { i ->
    GridEntry("$key:sk$i", focusable = false) { SkeletonMediaCard(thumbWidth = CardSizes.BoxartWidth, thumbHeight = CardSizes.BoxartHeight) }
}

private fun retry(key: String, message: String, onRetry: () -> Unit): List<GridEntry> = listOf(
    note("$key:err", message),
    GridEntry("$key:retry", fullSpan = true) { m -> FrostButton("Retry", onRetry, m.padding(vertical = 4.dp)) },
)

@Composable
fun FollowingContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit, onOpenVod: (String) -> Unit) {
    val st by vm.following.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    // Stable callbacks: the entries are rebuilt only when the data changes, never per frame.
    val open by rememberUpdatedState(onOpen)
    val openPage by rememberUpdatedState(onOpenChannelPage)
    val openVod by rememberUpdatedState(onOpenVod)
    // Up from Continue watching goes to the last Live now card, not to the side rail.
    val lastLive = remember { FocusRequester() }
    val uiScope = rememberCoroutineScope()
    val entries = remember(st, resume) {
        buildList<GridEntry> {
            if (st.needsSignIn) {
                add(header("h:live", "Following"))
                add(note("f:signin", "Sign in to see your follows"))
                add(GridEntry("f:settings", fullSpan = true) { m -> FrostButton("Open Settings to sign in", { vm.select(HomeSection.Settings) }, m) })
            } else {
                add(header("h:live", "Live now"))
                when {
                    st.error != null -> addAll(retry("f", st.error!!, vm::loadFollowing))
                    st.live.isEmpty() && st.loading -> { add(note("f:loading", "Loading your follows...")); addAll(listSkeletons("f")) }
                    st.live.isEmpty() -> add(note("f:none", "None of the channels you follow are live."))
                    else -> st.live.forEachIndexed { i, s ->
                        val last = i == st.live.lastIndex
                        add(
                            GridEntry("live:${s.userId}", fullSpan = true) { m ->
                                val card: @Composable () -> Unit = {
                                    WideStreamCard(s, st.avatars[s.userId], m, onLongClick = { vm.onLeaving(); openPage(s.userLogin) }) { vm.onLeaving(); open(s.userLogin) }
                                }
                                if (last) Box(Modifier.focusRequester(lastLive).focusGroup()) { card() } else card()
                            },
                        )
                    }
                }
                if (resume.isNotEmpty()) {
                    add(header("h:resume", "Continue watching"))
                    add(
                        GridEntry("resume:row", fullSpan = true) { m ->
                            val scroller = LocalGridScroller.current
                            // Same start inset as the Live now cards (16 dp), plus room for the focus ring and scale so the first card is not clipped.
                            CompositionLocalProvider(LocalBringIntoViewSpec provides RowPivot) {
                                LazyRow(
                                    m.focusGroup().onPreviewKeyEvent { e ->
                                        if (st.live.isNotEmpty() && e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) {
                                            if (!lastLive.tryFocus()) {
                                                // The last Live card scrolled out of the lazy grid: bring it back, then focus it.
                                                val key = "live:${st.live.last().userId}"
                                                uiScope.launch {
                                                    scroller.scrollTo(key)
                                                    repeat(30) { androidx.compose.runtime.withFrameNanos { }; if (lastLive.tryFocus()) return@launch }
                                                }
                                            }
                                            true
                                        } else false
                                    },
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    items(resume, key = { it.videoId }) { r ->
                                        ResumeCard(r, Modifier.width(220.dp)) { vm.onLeaving(); openVod(r.videoId) }
                                    }
                                }
                            }
                        },
                    )
                }
                if (st.offline.isNotEmpty()) {
                    add(GridEntry("offline:toggle", fullSpan = true) { m -> OfflineToggle("Offline A–Z (${st.offline.size})", st.offlineExpanded, vm::toggleOffline, m) })
                    if (st.offlineExpanded) {
                        st.offline.forEach { c -> add(GridEntry("off:${c.broadcasterId}") { m -> OfflineChip(c, m, onLongClick = { vm.onLeaving(); openPage(c.login) }) { vm.onLeaving(); open(c.login) } }) }
                    }
                }
            }
        }
    }
    FocusGrid(entries, vm.memory("following"), vm.focusSignal)
}

@Composable
private fun OfflineToggle(label: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    FrostButton((if (expanded) "v  " else ">  ") + label, onToggle, modifier.padding(vertical = 6.dp), style = FrostButtonStyle.Secondary)
}

@Composable
fun TopContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit) {
    val st by vm.top.collectAsStateWithLifecycle()
    BackHandler(enabled = st.category != null) { vm.closeCategory() }
    val open by rememberUpdatedState(onOpen)
    val openPage by rememberUpdatedState(onOpenChannelPage)
    val memoryName = when {
        st.category != null -> "top-cat"
        st.tab == TopTab.Categories -> "top-categories"
        else -> "top-streams"
    }
    val entries = remember(st) {
        buildList<GridEntry> {
            fun streamCard(prefix: String, s: HelixStream) = GridEntry("$prefix:${s.id}", fullSpan = true) { m ->
                WideStreamCard(s, st.avatars[s.userId], m, onLongClick = { vm.onLeaving(); openPage(s.userLogin) }) { vm.onLeaving(); open(s.userLogin) }
            }
            if (st.category == null) {
                add(
                    GridEntry("top:tabs", fullSpan = true) { m ->
                        Row(m.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FrostChip("Streams", st.tab == TopTab.Streams, onClick = { vm.setTopTab(TopTab.Streams) })
                            FrostChip("Categories", st.tab == TopTab.Categories, onClick = { vm.setTopTab(TopTab.Categories) })
                        }
                    },
                )
            }
            when {
                st.error != null -> addAll(retry("t", st.error!!, vm::loadTop))
                st.category != null -> {
                    add(header("h:cat", st.category!!.name, FrostColors.Lavender))
                    if (st.categoryStreams.isEmpty()) {
                        add(note("t:loading", if (st.loading) "Loading..." else "Nobody is live in this category."))
                        if (st.loading) addAll(listSkeletons("c"))
                    }
                    st.categoryStreams.forEach { s -> add(streamCard("cs", s)) }
                }
                st.tab == TopTab.Streams -> {
                    if (st.streams.isEmpty() && st.loading) addAll(listSkeletons("ts"))
                    st.streams.forEach { s -> add(streamCard("ts", s)) }
                }
                else -> {
                    if (st.categories.isEmpty() && st.loading) addAll(boxartSkeletons("tc", 12))
                    st.categories.forEach { g -> add(GridEntry("tc:${g.id}") { m -> CategoryCard(g, m) { vm.openCategory(g) } }) }
                }
            }
        }
    }
    FocusGrid(
        entries,
        vm.memory(memoryName),
        vm.focusSignal,
        minCellWidth = if (st.tab == TopTab.Categories && st.category == null) 140.dp else 190.dp,
    )
}

@Composable
fun SearchContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit) {
    val st by vm.search.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpen)
    val openPage by rememberUpdatedState(onOpenChannelPage)
    val entries = remember(st) {
        buildList<GridEntry> {
            add(
                GridEntry("search:field", fullSpan = true) { m ->
                    TvTextField(
                        value = st.query,
                        onValueChange = vm::setQuery,
                        placeholder = "Search channels and categories (press OK to type)",
                        imeAction = ImeAction.Search,
                        onImeAction = vm::searchNow,
                        fieldModifier = m,
                    )
                },
            )
            when {
                st.error != null -> add(note("s:err", st.error!!))
                st.loading -> add(note("s:loading", "Searching..."))
                st.searched && st.channels.isEmpty() && st.categories.isEmpty() -> add(note("s:none", "No results."))
            }
            if (st.channels.isNotEmpty()) {
                add(header("h:channels", "Channels"))
                st.channels.forEach { c ->
                    add(GridEntry("sc:${c.id}", fullSpan = true) { m ->
                        ChannelCard(c, st.avatars[c.id], m.fillMaxWidth(), onLongClick = { vm.onLeaving(); openPage(c.login) }) { vm.onLeaving(); open(c.login) }
                    })
                }
            }
            if (st.categories.isNotEmpty()) {
                add(header("h:categories", "Categories"))
                st.categories.forEach { g -> add(GridEntry("sg:${g.id}") { m -> CategoryCard(g, m) { vm.openCategory(g) } }) }
            }
        }
    }
    FocusGrid(entries, vm.memory("search"), vm.focusSignal)
}
