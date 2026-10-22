package tv.frostline.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import coil3.compose.AsyncImage
import tv.frostline.core.ui.FrostColors

/** A built message line: AnnotatedString plus its inline image slots. Created once per (message, size, timestamps). */
private class Rendered(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

private fun render(m: ChatMessage, fontSp: Int, timestamps: Boolean): Rendered {
    val emoteH = (fontSp * 1.6f)
    val badgeH = fontSp * 1.25f
    val inline = HashMap<String, InlineTextContent>(m.parts.size + m.badges.size)
    val name = Color(m.colorArgb)
    val text = buildAnnotatedString {
        if (timestamps) withStyle(SpanStyle(color = FrostColors.Muted)) { append(m.time); append("  ") }
        m.badges.forEachIndexed { i, b ->
            val id = "b$i"
            inline[id] = InlineTextContent(Placeholder(badgeH.sp, badgeH.sp, PlaceholderVerticalAlign.TextCenter)) {
                AsyncImage(b.url, b.set, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            appendInlineContent(id, "[${b.set}]")
            append(" ")
        }
        withStyle(SpanStyle(color = name, fontWeight = FontWeight.Bold)) { append(m.displayName) }
        append(if (m.action) " " else ": ")
        val body = if (m.action) SpanStyle(color = name, fontStyle = FontStyle.Italic) else SpanStyle()
        withStyle(body) {
            var n = 0
            for (p in m.parts) when (p) {
                is Part.Text -> append(p.text)
                is Part.EmoteRef -> {
                    val e = p.emote
                    val id = "e${n++}"
                    val w = (emoteH * e.aspect).coerceIn(emoteH * 0.5f, emoteH * 4f)
                    inline[id] = InlineTextContent(Placeholder(w.sp, emoteH.sp, PlaceholderVerticalAlign.TextCenter)) {
                        EmoteImage(e.url, e.code, Modifier.fillMaxSize())
                    }
                    appendInlineContent(id, e.code)
                }
            }
        }
    }
    return Rendered(text, inline)
}

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
                modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).testTag("sys_line"),
                style = TextStyle(color = color, fontSize = fontSp.sp, fontStyle = FontStyle.Italic, lineHeight = (fontSp * 1.35f).sp),
            )
        }
    }
}

@Composable
private fun MessageLine(m: ChatMessage, fontSp: Int, timestamps: Boolean, modifier: Modifier) {
    val r = remember(m.key, fontSp, timestamps) { render(m, fontSp, timestamps) }
    val bg = when {
        m.highlight -> Modifier.background(FrostColors.SurfaceFocused, RoundedCornerShape(6.dp))
        m.mine -> Modifier.background(FrostColors.SurfaceAlt, RoundedCornerShape(6.dp))
        else -> Modifier
    }
    Column(modifier.fillMaxWidth().then(bg).padding(horizontal = 8.dp, vertical = 3.dp)) {
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
        BasicText(
            r.text,
            inlineContent = r.inline,
            style = TextStyle(color = FrostColors.OnSurface, fontSize = fontSp.sp, lineHeight = (fontSp * 1.6f).sp),
        )
    }
}
