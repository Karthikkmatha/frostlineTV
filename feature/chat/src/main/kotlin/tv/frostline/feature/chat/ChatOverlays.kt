package tv.frostline.feature.chat

import tv.frostline.core.data.ChatOptions
import androidx.tv.material3.MaterialTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.frostline.core.data.ChatSettings
import tv.frostline.core.ui.FrostColors
import tv.frostline.core.ui.FrostButton
import tv.frostline.core.ui.FrostButtonStyle
import tv.frostline.core.ui.FrostChip
import tv.frostline.core.ui.FrostListItem
import tv.frostline.core.ui.FrostSurface
import tv.frostline.core.ui.FrostSheetSurface
import tv.frostline.core.ui.FrostSheetTitle
import tv.frostline.core.ui.FrostTokens

/** One row of the chat menu: label on the left, current value on the right. */
@Composable
private fun MenuRow(label: String, value: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FrostListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        minHeight = FrostTokens.SheetRowHeight,
        trailing = value?.let { v -> { Text(v, style = MaterialTheme.typography.bodySmall, color = FrostColors.Lavender, maxLines = 1) } },
    ) { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** Swallows horizontal moves so focus cannot leave an overlay sideways. */
private fun Modifier.trapHorizontal(): Modifier = onPreviewKeyEvent {
    it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.DirectionRight)
}

/**
 * The chat menu: hide chat, resize the panel, font size, timestamps, jump to live, close. Items stay open after
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
    FrostSheetSurface(
        modifier
            .onFocusChanged { c.overlayFocused = it.hasFocus }
            .focusGroup()
            .trapHorizontal()
            .testTag("chat_menu"),
    ) {
        FrostSheetTitle("Chat")
        // Seven 52 dp rows fit a 540 dp screen without scrolling; the scroll is only a safety net for large font scales.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MenuRow(
            "Resize panel", "${settings.widthPct}%",
            { c.menuOpen = false; c.startResize(settings.widthPct) },
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
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FrostChip(label, selected, onClick, modifier)
}

private val pickerTabs = EmoteProvider.entries

/**
 * Emote picker: tabs (Twitch / 7TV / BTTV / FFZ + Close) over a fixed-column grid. Picking inserts the
 * emote code into the draft and keeps the picker open. Every edge is closed with Cancel so focus can only
 * leave through Back / Close.
 */
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

    FrostSheetSurface(
        modifier
            .onFocusChanged { c.overlayFocused = it.hasFocus }
            .focusGroup()
            .testTag("emote_picker"),
    ) {
        FrostSheetTitle("Emotes")
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
            FrostButton(
                "Close", { c.pickerOpen = false },
                style = FrostButtonStyle.Secondary,
                modifier = Modifier.focusProperties { up = stop; right = stop; if (emotes.isEmpty()) down = stop }.testTag("emote_close"),
            )
        }
        BoxWithConstraints(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            val cols = (maxWidth / 52.dp).toInt().coerceAtLeast(3)
            if (emotes.isEmpty()) {
                Text(
                    if (tab == EmoteProvider.Twitch && data.twitchLoading) "Loading your emotes..." else "No emotes here yet",
                    style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, modifier = Modifier.align(Alignment.Center).testTag("emote_empty"),
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
                        FrostSurface(
                            onClick = {
                                c.insertEmote(e.code)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .size(48.dp)
                                .onFocusChanged { if (it.isFocused) focusedCode = e.code }
                                .focusProperties {
                                    if (col == 0) left = stop
                                    if (col == cols - 1 || index == emotes.size - 1) right = stop
                                    if (row == rows - 1 || (row == rows - 2 && col >= lastRowCount)) down = stop
                                }
                                .testTag("emote_${e.code}"),
                        ) {
                            EmoteImage(e.url, e.code, Modifier.fillMaxSize().padding(4.dp), priority = EmoteEngine.PRIO_VISIBLE)
                        }
                    }
                }
            }
        }
        Text(
            if (focusedCode.isNotEmpty()) focusedCode else "OK adds the emote to your message. Back closes.",
            style = MaterialTheme.typography.bodySmall, color = FrostColors.Muted, maxLines = 1, modifier = Modifier.testTag("emote_caption"),
        )
        if (c.draft.isNotEmpty()) {
            Text("Message: ${c.draft}", style = MaterialTheme.typography.bodySmall, color = FrostColors.Lavender, maxLines = 1, modifier = Modifier.testTag("emote_draft"))
        }
    }
}
