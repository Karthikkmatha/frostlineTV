package tv.frostline.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.frostline.core.ui.FrostColors

/**
 * Measured text layouts of chat lines, kept by line id and width so scrolling, recomposition and the 15 fps emote clock never
 * lay a line out twice. One slot per id (its last width, size and timestamp setting); least recently used ids go first.
 */
@Stable
class LineLayoutCache(private val measurer: TextMeasurer, private val capacity: Int = 400) {
    private class Slot(val width: Int, val fontSp: Int, val timestamps: Boolean, val prepared: PreparedLine, val layout: TextLayoutResult)

    private val slots = object : LinkedHashMap<String, Slot>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>?) = size > capacity
    }

    val size: Int get() = synchronized(slots) { slots.size }

    fun layout(id: String, prep: PreparedLine, width: Int, style: TextStyle): TextLayoutResult {
        synchronized(slots) {
            val s = slots[id]
            if (s != null && s.width == width && s.prepared === prep) return s.layout
        }
        val result = measurer.measure(
            text = prep.text,
            style = style,
            overflow = TextOverflow.Clip,
            softWrap = true,
            placeholders = prep.placeholders,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width),
        )
        synchronized(slots) { slots[id] = Slot(width, prep.fontSp, prep.timestamps, prep, result) }
        return result
    }
}

/** The panel's layout cache (null outside a chat panel: a line then makes its own). */
val LocalLineLayouts = staticCompositionLocalOf<LineLayoutCache?> { null }

@Composable
fun ChatLineItem(line: ChatLine, fontSp: Int, timestamps: Boolean, modifier: Modifier = Modifier) {
    when (line) {
        is ChatMessage -> MessageLine(line, fontSp, timestamps, modifier)
        is SystemLine -> {
            val color = when (line.kind) {
                ChatEvent.System.Kind.Raid, ChatEvent.System.Kind.Sub -> FrostColors.Ice
                ChatEvent.System.Kind.Moderation -> FrostColors.Muted
                else -> FrostColors.Lavender
            }
            BasicText(
                (if (timestamps) line.time + "  " else "") + line.text,
                modifier.fillMaxWidth().noticeBar().padding(horizontal = 8.dp, vertical = 2.dp).testTag("sys_line"),
                style = TextStyle(color = color, fontSize = fontSp.sp, fontStyle = FontStyle.Italic, lineHeight = (fontSp * 1.35f).sp),
            )
        }
    }
}

/** A 3 dp muted bar on the left edge of a system notice, with the text indented past it. */
private fun Modifier.noticeBar(): Modifier = this
    .drawBehind { drawRect(FrostColors.Muted, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
    .padding(start = 6.dp)

@Composable
private fun MessageLine(m: ChatMessage, fontSp: Int, timestamps: Boolean, modifier: Modifier) {
    val prep = remember(m, fontSp, timestamps) { m.prepared(fontSp, timestamps) } // already built by the view model; cheap
    val bg = when {
        m.highlight -> Modifier.background(FrostColors.SurfaceFocused, RoundedCornerShape(6.dp))
        m.mentionLogin != null -> Modifier.background(FrostColors.Violet.copy(alpha = 0.15f), RoundedCornerShape(6.dp)) // someone @mentioned you
        m.mine -> Modifier.background(FrostColors.SurfaceAlt, RoundedCornerShape(6.dp))
        else -> Modifier
    }
    // Sub, raid and announcement lines (they carry a header) get the muted bar on the left, like system notices.
    val bar = if (m.header != null) Modifier.noticeBar() else Modifier
    Column(modifier.fillMaxWidth().then(bg).then(bar).padding(horizontal = 8.dp, vertical = 2.dp)) {
        m.header?.let {
            BasicText(it, style = TextStyle(color = FrostColors.Lavender, fontSize = (fontSp - 2).sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        m.reply?.let {
            BasicText(
                "Replying to @${it.displayName}: ${it.body}",
                style = TextStyle(color = FrostColors.Muted, fontSize = (fontSp - 2).sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ChatTextLine(m.key, m.seq, prep, fontSp)
    }
}

/** Mutable hand-over between the measure and draw phases of one line (not Compose state: both run in the same frame). */
private class LayoutHolder { var layout: TextLayoutResult? = null }

/**
 * One chat line drawn as text plus pictures. The text is measured once (cached by id and width) with a placeholder per
 * badge or emote; the pictures are then drawn straight into those rectangles from the shared atlas. No child composable
 * per emote, no semantics node per emote, nothing allocated per frame while drawing. Only a line with a running
 * animation reads the emote clock (and gets its own layer), so a clock tick redraws those lines and nothing else.
 */
@Composable
internal fun ChatTextLine(id: String, seq: Long, prep: PreparedLine, fontSp: Int, modifier: Modifier = Modifier) {
    val local = LocalLineLayouts.current
    val ownMeasurer = if (local == null) rememberTextMeasurer(cacheSize = 8) else null
    val cache = local ?: remember(ownMeasurer) { LineLayoutCache(ownMeasurer!!) }
    val style = remember(fontSp) { TextStyle(color = FrostColors.OnSurface, fontSize = fontSp.sp, lineHeight = (fontSp * 1.6f).sp) }
    val engine = EmoteEngine.current
    val clock = LocalEmoteClock.current
    val gate = LocalEmoteGate.current
    val entries: Array<EmoteEntry>? = remember(prep, engine) {
        engine?.let { e -> Array(prep.images.size) { i -> e.entry(prep.images[i].url) } }
    }
    // On screen while composed: these emotes are decoded first and never evicted (a busy chat used to push visible ones out).
    DisposableEffect(entries, engine) {
        if (engine != null && entries != null) for (e in entries) engine.markVisible(e)
        onDispose { if (engine != null && entries != null) for (e in entries) engine.markHidden(e) }
    }
    LaunchedEffect(entries, clock != null, engine?.active) {
        if (engine != null && entries != null) {
            for (i in entries.indices) {
                engine.ensureStill(entries[i], EmoteEngine.PRIO_SCREEN)
                if (prep.images[i].isEmote && clock != null && engine.active && engine.mode != EmoteMode.Fast) engine.ensureAnimation(entries[i], EmoteEngine.PRIO_HOT)
            }
        }
    }
    // Composition reads every picture's version, so a decode that lands recomposes this line (and re-evaluates anyStrip).
    var anyStrip = false
    if (entries != null) for (e in entries) { e.version; if (e.strip != null) anyStrip = true }
    DisposableEffect(clock, anyStrip) {
        if (clock != null && anyStrip) {
            clock.subscribe()
            onDispose { clock.unsubscribe() }
        } else {
            onDispose { }
        }
    }
    val holder = remember { LayoutHolder() }
    Layout(
        modifier = modifier
            .semantics { text = AnnotatedString(prep.spoken) }
            .drawBehind {
                val tl = holder.layout ?: return@drawBehind
                drawText(tl)
                if (entries == null) return@drawBehind
                val rects = tl.placeholderRects
                for (i in rects.indices) {
                    val r = rects[i] ?: continue
                    if (i >= entries.size) break
                    val e = entries[i]
                    e.version // draw-phase read: a decode that lands redraws this line without recomposing
                    drawEmoteEntry(e, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt(), clock, prep.images[i].isEmote && clock != null && gate.allows(seq), engine)
                }
            },
    ) { _, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val tl = cache.layout(id, prep, width.coerceAtLeast(1), style)
        holder.layout = tl
        layout(width.coerceAtLeast(tl.size.width), tl.size.height) {}
    }
}
