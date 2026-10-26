package tv.frostline.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.frostline.core.twitch.HelixStream
import tv.frostline.core.ui.CardSizes
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostButtonStyle
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.SkeletonMediaCard
import tv.frostline.core.ui.TvTextField

private fun single(key: String, focusable: Boolean = false, content: @Composable (Modifier) -> Unit): RowBlock =
    SingleBlock(GridEntry(key, fullSpan = true, focusable = focusable, content = content))

private fun header(key: String, text: String, color: androidx.compose.ui.graphics.Color = FrostColors.OnSurface): RowBlock =
    single(key) { Text(text, style = MaterialTheme.typography.headlineSmall, color = color) }

private fun note(key: String, text: String): RowBlock =
    single(key) { Text(text, style = MaterialTheme.typography.bodySmall) }

/** Static placeholder cards while a row loads, so the page has its shape before the data arrives. */
private fun skeletons(key: String, n: Int = 5, boxart: Boolean = false) = (0 until n).map { i ->
    GridEntry("$key:sk$i", focusable = false) { _ ->
        if (boxart) SkeletonMediaCard(thumbWidth = CardSizes.BoxartWidth, thumbHeight = CardSizes.BoxartHeight) else SkeletonMediaCard()
    }
}

private fun retry(key: String, message: String, onRetry: () -> Unit): List<RowBlock> = listOf(
    note("$key:err", message),
    single("$key:retry", focusable = true) { m -> FrostButton("Retry", onRetry, m.padding(vertical = 4.dp)) },
)

@Composable
fun FollowingContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit, onOpenVod: (String) -> Unit) {
    val st by vm.following.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    // Stable callbacks: the blocks list is rebuilt only when the data changes, never per frame.
    val open by rememberUpdatedState(onOpen)
    val openPage by rememberUpdatedState(onOpenChannelPage)
    val openVod by rememberUpdatedState(onOpenVod)
    val blocks = remember(st, resume) {
        buildList<RowBlock> {
            if (st.needsSignIn) {
                add(header("h:live", "Following"))
                add(note("f:signin", "Sign in to see your follows"))
                add(single("f:settings", focusable = true) { m -> FrostButton("Open Settings to sign in", { vm.select(HomeSection.Settings) }, m) })
            } else {
                when {
                    st.error != null -> addAll(retry("f", st.error!!, vm::loadFollowing))
                    st.live.isEmpty() && st.loading -> add(CardRowBlock("live", "Live now", skeletons("f")))
                    st.live.isEmpty() -> { add(header("h:live", "Live now")); add(note("f:none", "None of the channels you follow are live.")) }
                    else -> add(
                        CardRowBlock(
                            "live", "Live now",
                            st.live.map { s ->
                                GridEntry("live:${s.userId}") { m ->
                                    StreamCard(s, m, onLongClick = { vm.onLeaving(); openPage(s.userLogin) }) { vm.onLeaving(); open(s.userLogin) }
                                }
                            },
                        ),
                    )
                }
                if (resume.isNotEmpty()) {
                    add(
                        CardRowBlock(
                            "resume", "Continue watching",
                            resume.map { r -> GridEntry("resume:${r.videoId}") { m -> ResumeCard(r, m) { vm.onLeaving(); openVod(r.videoId) } } },
                        ),
                    )
                }
                if (st.offline.isNotEmpty()) {
                    add(single("offline:toggle", focusable = true) { m -> OfflineToggle("Offline A\u2013Z (${st.offline.size})", st.offlineExpanded, vm::toggleOffline, m) })
                    if (st.offlineExpanded) {
                        add(
                            CardRowBlock(
                                "offline", "Offline",
                                st.offline.map { c -> GridEntry("off:${c.broadcasterId}") { m -> OfflineChip(c, m, onLongClick = { vm.onLeaving(); openPage(c.login) }) { vm.onLeaving(); open(c.login) } } },
                            ),
                        )
                    }
                }
            }
        }
    }
    FocusRows(blocks, vm.memory("following"), vm.focusSignal)
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
    val memoryName = if (st.category != null) "top-cat" else "top"
    val blocks = remember(st) {
        buildList<RowBlock> {
            fun streamCard(prefix: String, s: HelixStream) = GridEntry("$prefix:${s.id}") { m ->
                StreamCard(s, m, onLongClick = { vm.onLeaving(); openPage(s.userLogin) }) { vm.onLeaving(); open(s.userLogin) }
            }
            when {
                st.error != null -> addAll(retry("t", st.error!!, vm::loadTop))
                st.category != null -> {
                    add(header("h:cat", st.category!!.name, FrostColors.Lavender))
                    when {
                        st.categoryStreams.isEmpty() && st.loading -> add(CardRowBlock("cs", "Live now", skeletons("c")))
                        st.categoryStreams.isEmpty() -> add(note("t:none", "Nobody is live in this category."))
                        else -> add(CardRowBlock("cs", "Live now", st.categoryStreams.map { streamCard("cs", it) }))
                    }
                }
                else -> {
                    add(
                        CardRowBlock(
                            "ts", "Top streams",
                            if (st.streams.isEmpty() && st.loading) skeletons("ts") else st.streams.map { streamCard("ts", it) },
                        ),
                    )
                    add(
                        CardRowBlock(
                            "tc", "Top categories",
                            if (st.categories.isEmpty() && st.loading) skeletons("tc", 8, boxart = true)
                            else st.categories.map { g -> GridEntry("tc:${g.id}") { m -> CategoryCard(g, m) { vm.openCategory(g) } } },
                        ),
                    )
                }
            }
        }
    }
    FocusRows(blocks, vm.memory(memoryName), vm.focusSignal)
}

@Composable
fun SearchContent(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenChannelPage: (String) -> Unit) {
    val st by vm.search.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpen)
    val openPage by rememberUpdatedState(onOpenChannelPage)
    val blocks = remember(st) {
        buildList<RowBlock> {
            add(
                single("search:field", focusable = true) { m ->
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
                add(
                    CardRowBlock(
                        "channels", "Channels",
                        st.channels.map { c -> GridEntry("sc:${c.id}") { m -> ChannelCard(c, m, onLongClick = { vm.onLeaving(); openPage(c.login) }) { vm.onLeaving(); open(c.login) } } },
                    ),
                )
            }
            if (st.categories.isNotEmpty()) {
                add(
                    CardRowBlock(
                        "categories", "Categories",
                        st.categories.map { g -> GridEntry("sg:${g.id}") { m -> CategoryCard(g, m) { vm.openCategory(g) } } },
                    ),
                )
            }
        }
    }
    FocusRows(blocks, vm.memory("search"), vm.focusSignal)
}
