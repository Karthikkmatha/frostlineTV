package tv.frostline.feature.chat

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import tv.frostline.core.ui.FrostColors

/** One picture inside a chat line: a badge or an emote, drawn from the emote engine's atlas by [url]. */
@Immutable
class InlineImage(val url: String, val description: String, val isEmote: Boolean)

/**
 * A chat line ready to measure and draw: the annotated text with one placeholder character per inline picture, the
 * placeholders themselves (same order as [images]), and the text a screen reader gets. Pure data, built off the main
 * thread by [LineBuilder]; the UI only measures it (cached by line id and width) and draws it.
 */
@Immutable
class PreparedLine(
    val text: AnnotatedString,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val images: List<InlineImage>,
    val spoken: String,
    val fontSp: Int,
    val timestamps: Boolean,
)

object LineBuilder {
    /** Stands in the text for an inline picture; the placeholder's size replaces it in the layout. */
    const val OBJECT = "￼"

    fun prepare(m: ChatMessage, fontSp: Int, timestamps: Boolean): PreparedLine {
        val emoteH = fontSp * 1.6f
        val badgeH = fontSp * 1.25f
        val images = ArrayList<InlineImage>(m.badges.size + m.parts.size)
        val placeholders = ArrayList<AnnotatedString.Range<Placeholder>>(m.badges.size + m.parts.size)
        val spoken = StringBuilder(64)
        val name = Color(m.colorArgb)
        val text = buildAnnotatedString {
            fun slot(w: Float, h: Float, image: InlineImage) {
                val at = length
                append(OBJECT)
                placeholders += AnnotatedString.Range(Placeholder(w.sp, h.sp, PlaceholderVerticalAlign.TextCenter), at, at + 1)
                images += image
            }
            if (timestamps) {
                withStyle(SpanStyle(color = FrostColors.Muted)) { append(m.time); append("  ") }
                spoken.append(m.time).append(' ')
            }
            for (b in m.badges) {
                slot(badgeH, badgeH, InlineImage(b.url, b.set, isEmote = false))
                append(" ")
            }
            withStyle(SpanStyle(color = name, fontWeight = FontWeight.Bold)) { append(m.displayName) }
            spoken.append(m.displayName)
            append(if (m.action) " " else ": ")
            spoken.append(if (m.action) " " else ": ")
            val body = if (m.action) SpanStyle(color = name, fontStyle = FontStyle.Italic) else SpanStyle()
            withStyle(body) {
                for (p in m.parts) when (p) {
                    is Part.Text -> { append(p.text); spoken.append(p.text) }
                    is Part.EmoteRef -> {
                        val e = p.emote
                        val w = (emoteH * e.aspect).coerceIn(emoteH * 0.5f, emoteH * 4f)
                        slot(w, emoteH, InlineImage(e.url, e.code, isEmote = true))
                        spoken.append(e.code)
                    }
                }
            }
        }
        return PreparedLine(text, placeholders, images, spoken.toString(), fontSp, timestamps)
    }
}
