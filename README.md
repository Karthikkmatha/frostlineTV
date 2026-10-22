# FrostlineTV

A native Android TV client for Twitch, built with Kotlin, Jetpack Compose for TV and Media3.

Status: in development (private).

## Chat (`:feature:chat`)

Live Twitch chat beside the player. Twitch IRC over WebSocket (`wss://irc-ws.chat.twitch.tv:443`, own OkHttp client):
anonymous `justinfan` for guests, the stored OAuth token when signed in (sending needs a sign-in). Tags and
commands capabilities, PING/PONG, reconnect with 1 s to 30 s backoff, a silence watchdog, JOIN/PART on channel change.
Emotes: Twitch (from tags), 7TV, BTTV and FFZ, global plus channel, cached per channel. Badges come from Helix.
The backlog is capped at 200 lines; the message list is the only lazy list in chat (newest-first, `reverseLayout`,
stable keys). Settings: Show chat, panel width, font size, timestamps, 7TV/BTTV/FFZ.

### Focus model (remote only)

Two zones, one focus at a time: PLAYER (the bare player and its controls) and CHAT (panel plus overlays).

- PLAYER to CHAT: Right on the bare player, or Right on the controls' play/pause, gear or Chat button, calls
  `ChatController.enter()` and focuses the message list (controls hide).
- CHAT to PLAYER: Left from the left-most element of a row (Menu, list, text field, Emotes), or Back from the panel,
  calls `ChatActions.leave()`; the player puts focus back on its root with controls hidden.
- Inside CHAT, top to bottom: `Menu`, message list, text field, `Emotes` / `Send`. On the list, Up/Down scroll history
  (Down at the live edge moves on to the field, Up at the oldest line moves to Menu), OK jumps back to live (or, already
  live, opens the field). OK on the field opens the keyboard.
- Overlays (chat menu, emote picker) trap focus: every outward edge is closed; OK picks, Back or Close dismisses and
  focus returns to the button that opened them. Hiding or resizing the panel never recreates the video `SurfaceView`
  (the video box keeps its place in the layout; only its weight changes).
- While focus is in CHAT the player's controls neither auto-hide nor take focus back (`focusLocked`).

Implemented with `FocusRequester`s (`ChatFocus`), `focusProperties` and key handlers; covered by
`ChatFocusTest` (chat side) and `ChatEntryTest` (player side).

Stage 4 additions:

- The chat text field never opens the keyboard on focus. Focus alone only highlights it; OK (a press that began on the
  field) starts editing, and leaving the field ends it. Down from the field with the keyboard closed goes to Emotes.
- VOD and clip playback use the same player. With controls hidden, Left/Right do not seek; seeking is done with the
  -10 s / +30 s buttons, whose held or repeated presses accumulate into one "+MM:SS" counter and are applied once after
  about 600 ms idle. Resume is offered ("Resume from MM:SS / Start over") past 30 s and before 95%.
- Chat replay (VODs) uses the same panel and focus model as live chat, labelled "Chat replay", with sending disabled.
- Channel page: tabs row, then the kind or period chips, then the grid. Back from the player returns to the card that
  opened it (and from a channel to the card in its grid).
- Animated emotes run on one shared 12-15 fps clock and stop when off screen, hidden or paused; Settings can turn them
  into still frames. Settings also has a UI frame-rate overlay toggle.


## Channel points, rewards, predictions and polls (Stage 5)

Signed-in only, and only with a linked web session; for guests none of it exists (no button, no banner, no requests).
GQL sits behind `EngagementApi` (`:core:twitch-internal`); Hermes carries `community-points-user-v1.<userId>`,
`predictions-channel-v1.<channelId>` and `polls.<channelId>`. Each part fails on its own and quietly (its UI is hidden);
the debug overlay shows only the HTTP status, exception class or GQL message. Nothing that costs real money is offered:
rewards that look like Bits, cheers or gifted subs are filtered out, redeems are always `pricingType: POINTS`, and poll
Bits settings are never read.

- Header: balance with the channel's points name and icon. The bonus chest is claimed automatically (Settings > Channel
  points, default on) with a short "+50" toast. The balance follows Hermes; a GQL poll covers a quiet or broken socket.
- Rewards sheet: title, cost and icon; unaffordable, cooling-down, paused and out-of-stock rewards stay visible, inert,
  with the reason. OK on a reward opens a confirm step (text field for rewards that need input; OK opens the keyboard),
  then the result is shown.
- Prediction banner: title, outcomes with percentages and point totals, time left; OK opens a sheet to pick an outcome,
  an amount (10 / 100 / 1k / Max presets, +/- steppers, never above the balance) and confirm. Locked and resolved states
  show, with won/lost/refunded for the viewer.
- Poll banner: same; the vote sheet offers the free vote and, if the poll has it, an extra vote paid with channel points.
  Results stay for 15 s after the poll ends.

### Focus model additions

```
[Menu] [Rewards]        Left/Right between them; Left from Menu -> player; Down -> first banner, else the list
[prediction banner]     Up -> the header button that was last focused; Down -> poll banner, else list; Left -> player
[poll banner]           Up -> prediction banner, else the header; Down -> list
[ message list ]        Up at the oldest line -> last banner, else Menu
```

- Banners sit above the list and never take focus when they appear. If the focused banner goes away, focus moves to the list.
- Rewards, prediction and poll sheets trap focus (`exit` is cancelled; every edge is closed). Back steps out of a confirm or
  result step first, then closes the sheet; focus returns to the element that opened it (Rewards button, or the banner;
  the list if the banner is gone).
- Covered by `EngagementFocusTest` (header to Rewards, Rewards sheet to redeem confirm, banner to prediction sheet, Back).
