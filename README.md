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
