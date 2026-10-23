package tv.frostline.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import tv.frostline.core.ui.tryFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.TvButton
import tv.frostline.core.ui.TvTextField
import tv.frostline.core.ui.cardScale
import tv.frostline.core.ui.cardShape
import tv.frostline.core.ui.focusRingBorder
import androidx.compose.foundation.shape.RoundedCornerShape

private fun header(key: String, text: String, color: androidx.compose.ui.graphics.Color = FrostColors.OnSurface) =
    GridEntry(key, fullSpan = true, focusable = false) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(top = 6.dp))
    }

private fun note(key: String, text: String) =
    GridEntry(key, fullSpan = true, focusable = false) {
        Text(text, fontSize = 14.sp, color = FrostColors.Muted)
    }

/** Placeholder cards while a grid loads, so the page has its shape before the data arrives. */
private fun skeletons(key: String, n: Int = 8) = (0 until n).map { i ->
    GridEntry("$key:sk$i", focusable = false) { m -> tv.frostline.core.ui.SkeletonCard(m) }
}

private fun retry(key: String, message: String, onRetry: () -> Unit): List<GridEntry> = listOf(
    note("$key:err", message),
    GridEntry("$key:retry", fullSpan = true) { m -> TvButton("Retry", onRetry, m) },
)

@Composable
fun FollowingContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit, onOpenVod: (String) -> Unit) {
    val st by vm.following.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    // Up from Continue watching goes to the last Live now card, not to the side rail.
    val lastLive = remember { androidx.compose.ui.focus.FocusRequester() }
    val entries = remember(st, resume) {
        buildList {
            if (st.needsSignIn) {
                add(header("h:live", "Following"))
                add(note("f:signin", "Sign in to see your follows"))
                add(GridEntry("f:settings", fullSpan = true) { m -> TvButton("Open Settings to sign in", { vm.select(HomeSection.Settings) }, m) })
            } else {
                add(header("h:live", "Live now"))
                when {
                    st.error != null -> addAll(retry("f", st.error!!, vm::loadFollowing))
                    st.live.isEmpty() && st.loading -> { add(note("f:loading", "Loading your follows...")); addAll(skeletons("f", 4)) }
                    st.live.isEmpty() -> add(note("f:none", "None of the channels you follow are live."))
                    else -> st.live.forEachIndexed { i, s ->
                        val last = i == st.live.lastIndex
                        add(
                            GridEntry("live:${s.userId}", fullSpan = true) { m ->
                                val card: @Composable () -> Unit = {
                                    WideStreamCard(s, st.avatars[s.userId], m, onLongClick = { vm.onLeaving(); onOpenChannelPage(s.userLogin) }) { vm.onLeaving(); onOpen(s.userLogin) }
                                }
                                if (last) androidx.compose.foundation.layout.Box(Modifier.focusRequester(lastLive).focusGroup()) { card() } else card()
                            },
                        )
                    }
                }
                if (resume.isNotEmpty()) {
                    add(header("h:resume", "Continue watching"))
                    add(
                        GridEntry("resume:row", fullSpan = true) { m ->
                            // Same start inset as the Live now cards (16 dp), plus room for the focus ring and scale so the first card is not clipped.
                            LazyRow(
                                m.focusGroup().onPreviewKeyEvent { e ->
                                    if (st.live.isNotEmpty() && e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) { lastLive.tryFocus(); true } else false
                                },
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(resume, key = { it.videoId }) { r ->
                                    ResumeCard(r, Modifier.width(220.dp)) { vm.onLeaving(); onOpenVod(r.videoId) }
                                }
                            }
                        },
                    )
                }
                if (st.offline.isNotEmpty()) {
                    add(
                        GridEntry("offline:toggle", fullSpan = true) { m ->
                            OfflineToggle("Offline A\u2013Z (${st.offline.size})", st.offlineExpanded, vm::toggleOffline, m)
                        },
                    )
                    if (st.offlineExpanded) {
                        st.offline.forEach { c -> add(GridEntry("off:${c.broadcasterId}") { m -> OfflineChip(c, m, onLongClick = { vm.onLeaving(); onOpenChannelPage(c.login) }) { vm.onLeaving(); onOpen(c.login) } }) }
                    }
                }
            }
        }
    }
    FocusGrid(entries, vm.memory("following"), vm.focusSignal)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OfflineToggle(label: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onToggle,
        modifier = modifier.padding(top = 6.dp),
        shape = cardShape(RoundedCornerShape(8.dp)),
        border = focusRingBorder(RoundedCornerShape(8.dp)),
        scale = cardScale(1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FrostColors.Surface,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) {
        Text(
            (if (expanded) "v  " else ">  ") + label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = cardShape(RoundedCornerShape(20.dp)),
        border = focusRingBorder(RoundedCornerShape(20.dp)),
        scale = cardScale(1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FrostColors.Primary else FrostColors.Surface,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.Lavender,
        ),
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
    }
}

@Composable
fun TopContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit) {
    val st by vm.top.collectAsStateWithLifecycle()
    BackHandler(enabled = st.category != null) { vm.closeCategory() }
    val memoryName = when {
        st.category != null -> "top-cat"
        st.tab == TopTab.Categories -> "top-categories"
        else -> "top-streams"
    }
    val entries = remember(st) {
        buildList {
            if (st.category == null) {
                add(
                    GridEntry("top:tabs", fullSpan = true) { m ->
                        Row(m.focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TabChip("Streams", st.tab == TopTab.Streams) { vm.setTopTab(TopTab.Streams) }
                            TabChip("Categories", st.tab == TopTab.Categories) { vm.setTopTab(TopTab.Categories) }
                        }
                    },
                )
            }
            when {
                st.error != null -> addAll(retry("t", st.error!!, vm::loadTop))
                st.category != null -> {
                    add(header("h:cat", st.category!!.name, FrostColors.Lavender))
                    if (st.categoryStreams.isEmpty()) { add(note("t:loading", if (st.loading) "Loading..." else "Nobody is live in this category.")); if (st.loading) addAll(skeletons("c")) }
                    st.categoryStreams.forEach { s -> add(GridEntry("cs:${s.id}") { m -> StreamCard(s, m, onLongClick = { vm.onLeaving(); onOpenChannelPage(s.userLogin) }) { vm.onLeaving(); onOpen(s.userLogin) } }) }
                }
                st.tab == TopTab.Streams -> {
                    if (st.streams.isEmpty() && st.loading) addAll(skeletons("ts"))
                    st.streams.forEach { s -> add(GridEntry("ts:${s.id}") { m -> StreamCard(s, m, onLongClick = { vm.onLeaving(); onOpenChannelPage(s.userLogin) }) { vm.onLeaving(); onOpen(s.userLogin) } }) }
                }
                else -> {
                    if (st.categories.isEmpty() && st.loading) addAll(skeletons("tc", 12))
                    st.categories.forEach { g -> add(GridEntry("tc:${g.id}") { m -> CategoryCard(g, m) { vm.openCategory(g) } }) }
                }
            }
        }
    }
    FocusGrid(entries, vm.memory(memoryName), vm.focusSignal, minCellWidth = if (st.tab == TopTab.Categories && st.category == null) 140.dp else 190.dp)
}

@Composable
fun SearchContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit) {
    val st by vm.search.collectAsStateWithLifecycle()
    val entries = remember(st) {
        buildList {
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
                st.channels.forEach { c -> add(GridEntry("sc:${c.id}") { m -> ChannelCard(c, m, onLongClick = { vm.onLeaving(); onOpenChannelPage(c.login) }) { vm.onLeaving(); onOpen(c.login) } }) }
            }
            if (st.categories.isNotEmpty()) {
                add(header("h:categories", "Categories"))
                st.categories.forEach { g -> add(GridEntry("sg:${g.id}") { m -> CategoryCard(g, m) { vm.openCategory(g) } }) }
            }
        }
    }
    FocusGrid(entries, vm.memory("search"), vm.focusSignal)
}
