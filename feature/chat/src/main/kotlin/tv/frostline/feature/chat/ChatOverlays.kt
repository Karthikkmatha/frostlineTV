package tv.frostline.feature.chat

import tv.frostline.core.data.ChatOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.cardScale
import tv.frostline.core.ui.cardShape
import tv.frostline.core.ui.focusRingBorder

/** One row of the chat menu: label on the left, current value on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MenuRow(label: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = cardShape(RoundedCornerShape(8.dp)),
        border = focusRingBorder(RoundedCornerShape(8.dp)),
        scale = cardScale(1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FrostColors.SurfaceAlt,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (value != null) Text(value, fontSize = 13.sp, color = FrostColors.Lavender)
        }
    }
}

/** Swallows horizontal moves so focus cannot leave an overlay sideways. */
private fun Modifier.trapHorizontal(): Modifier = onPreviewKeyEvent {
    it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.DirectionRight)
}

/**
 * The chat menu: hide chat, cycle width and font size, timestamps, jump to live, close. Items stay open after
 * a change so the effect shows in the panel next to it. Back or "Close" returns focus to the Menu button.
 */
@Composable
internal fun ChatMenu(
    settings: ChatSettings,
    c: ChatController,
    actions: ChatActions,
    onJumpToLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = c.focus
    val first = f.menuFirst
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val stop = FocusRequester.Cancel
    Column(
        modifier
            .background(FrostColors.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .onFocusChanged { c.overlayFocused = it.hasFocus }
            .focusGroup()
            .trapHorizontal()
            .testTag("chat_menu"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Chat", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = FrostColors.OnSurface)
        MenuRow(
            "Width", ChatOptions.widthNames[settings.widthIndex.coerceIn(0, 3)],
            { actions.update { it.copy(widthIndex = ChatOptions.nextIndex(it.widthIndex, ChatOptions.widthFractions.size)) } },
            Modifier.focusRequester(first).focusProperties { up = stop }.testTag("chat_menu_width"),
        )
        MenuRow(
            "Font size", ChatOptions.fontNames[settings.fontIndex.coerceIn(0, 3)],
            { actions.update { it.copy(fontIndex = ChatOptions.nextIndex(it.fontIndex, ChatOptions.fontSizesSp.size)) } },
            Modifier.testTag("chat_menu_font"),
        )
        MenuRow(
            "Timestamps", if (settings.timestamps) "On" else "Off",
            { actions.update { it.copy(timestamps = !it.timestamps) } },
            Modifier.testTag("chat_menu_time"),
        )
        MenuRow(
            "Animated emotes", if (settings.animatedEmotes) "On" else "Off (still)",
            { actions.update { it.copy(animatedEmotes = !it.animatedEmotes) } },
            Modifier.testTag("chat_menu_anim"),
        )
        MenuRow("Jump to live", null, { onJumpToLive(); c.menuOpen = false }, Modifier.testTag("chat_menu_live"))
        MenuRow("Hide chat", null, { c.menuOpen = false; actions.hide() }, Modifier.testTag("chat_menu_hide"))
        MenuRow("Close", null, { c.menuOpen = false }, Modifier.focusProperties { down = stop }.testTag("chat_menu_close"))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = cardShape(RoundedCornerShape(16.dp)),
        border = focusRingBorder(RoundedCornerShape(16.dp)),
        scale = cardScale(1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FrostColors.Primary else FrostColors.SurfaceAlt,
            contentColor = FrostColors.OnSurface,
            focusedContainerColor = if (selected) FrostColors.Primary else FrostColors.SurfaceFocused,
            focusedContentColor = FrostColors.OnSurface,
        ),
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = if (selected) FrostColors.Ice else FrostColors.OnSurface)
    }
}

private val pickerTabs = EmoteProvider.entries

/**
 * Emote picker: tabs (Twitch / 7TV / BTTV / FFZ + Close) over a fixed-column grid. Picking inserts the
 * emote code into the draft and keeps the picker open. Every edge is closed with Cancel so focus can only
 * leave through Back / Close.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EmotePicker(
    data: PickerData,
    c: ChatController,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(EmoteProvider.Twitch) }
    var focusedCode by remember { mutableStateOf("") }
    val f = c.focus
    val stop = FocusRequester.Cancel
    LaunchedEffect(Unit) { runCatching { f.pickerFirstTab.requestFocus() } }
    val emotes = data.tab(tab)
    val tabReqs = remember { List(pickerTabs.size + 1) { FocusRequester() } }

    Column(
        modifier
            .background(FrostColors.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .onFocusChanged { c.overlayFocused = it.hasFocus }
            .focusGroup()
            .testTag("emote_picker"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            pickerTabs.forEachIndexed { i, p ->
                TabChip(
                    p.label, p == tab, { tab = p },
                    Modifier
                        .focusRequester(if (i == 0) f.pickerFirstTab else tabReqs[i])
                        .focusProperties {
                            up = stop
                            if (emotes.isEmpty()) down = stop
                            if (i == 0) left = stop
                        }
                        .testTag("emote_tab_${p.name}"),
                )
            }
            Box(Modifier.weight(1f))
            TabChip(
                "Close", false, { c.pickerOpen = false },
                Modifier.focusProperties { up = stop; right = stop; if (emotes.isEmpty()) down = stop }.testTag("emote_close"),
            )
        }
        BoxWithConstraints(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            val cols = (maxWidth / 52.dp).toInt().coerceAtLeast(3)
            if (emotes.isEmpty()) {
                Text(
                    if (tab == EmoteProvider.Twitch && data.twitchLoading) "Loading your emotes..." else "No emotes here yet",
                    fontSize = 13.sp, color = FrostColors.Muted, modifier = Modifier.align(Alignment.Center).testTag("emote_empty"),
                )
            } else {
                val rows = (emotes.size + cols - 1) / cols
                val lastRowCount = emotes.size - (rows - 1) * cols
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize().testTag("emote_grid"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(emotes, key = { _, e -> e.provider.name + e.id + e.code }) { index, e ->
                        val col = index % cols
                        val row = index / cols
                        Surface(
                            onClick = {
                                c.insertEmote(e.code)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .onFocusChanged { if (it.isFocused) focusedCode = e.code }
                                .focusProperties {
                                    if (col == 0) left = stop
                                    if (col == cols - 1 || index == emotes.size - 1) right = stop
                                    if (row == rows - 1 || (row == rows - 2 && col >= lastRowCount)) down = stop
                                }
                                .testTag("emote_${e.code}"),
                            shape = cardShape(RoundedCornerShape(8.dp)),
                            border = focusRingBorder(RoundedCornerShape(8.dp)),
                            scale = cardScale(1f),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = FrostColors.SurfaceAlt,
                                focusedContainerColor = FrostColors.SurfaceFocused,
                            ),
                        ) {
                            EmoteImage(e.url, e.code, Modifier.fillMaxSize().padding(4.dp))
                        }
                    }
                }
            }
        }
        Text(
            if (focusedCode.isNotEmpty()) focusedCode else "OK adds the emote to your message. Back closes.",
            fontSize = 12.sp, color = FrostColors.Muted, maxLines = 1, modifier = Modifier.testTag("emote_caption"),
        )
        if (c.draft.isNotEmpty()) {
            Text("Message: ${c.draft}", fontSize = 12.sp, color = FrostColors.Lavender, maxLines = 1, modifier = Modifier.testTag("emote_draft"))
        }
    }
}
