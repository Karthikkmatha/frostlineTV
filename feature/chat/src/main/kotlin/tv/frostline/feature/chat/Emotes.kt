package tv.frostline.feature.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class EmoteProvider(val label: String) { Twitch("Twitch"), SevenTv("7TV"), Bttv("BTTV"), Ffz("FFZ") }

/** An emote definition. Shared (never copied) between every message that uses it. */
class Emote(
    val id: String,
    val code: String,
    val url: String,
    val provider: EmoteProvider,
    /** width / height of the image, used to size the inline placeholder. */
    val aspect: Float = 1f,
    val zeroWidth: Boolean = false,
) {
    override fun toString() = "Emote($provider:$code)"
}

object TwitchEmotes {
    fun url(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"
}

/** Emotes of one channel (channel + global) per provider, ready for lookup and for the picker. */
class EmoteSets(
    val sevenTv: List<Emote> = emptyList(),
    val bttv: List<Emote> = emptyList(),
    val ffz: List<Emote> = emptyList(),
) {
    /** Lookup by code with precedence FFZ < BTTV < 7TV (later put wins) and channel emotes before globals within a provider. */
    fun index(use7tv: Boolean = true, useBttv: Boolean = true, useFfz: Boolean = true): Map<String, Emote> {
        val m = HashMap<String, Emote>(sevenTv.size + bttv.size + ffz.size)
        if (useFfz) for (e in ffz.asReversed()) m[e.code] = e
        if (useBttv) for (e in bttv.asReversed()) m[e.code] = e
        if (use7tv) for (e in sevenTv.asReversed()) m[e.code] = e
        return m
    }

    companion object { val Empty = EmoteSets() }
}

/** Parsers for the third-party emote APIs. Each tolerates missing fields and returns what it can. */
object EmoteParsers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    private fun arr(text: String): JsonArray? = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
    private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int(): Int? = (this as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

    /** 7TV v3: `emote-sets/global` -> {emotes:[...]}, `users/twitch/{id}` -> {emote_set:{emotes:[...]}}. */
    fun sevenTv(text: String): List<Emote> {
        val root = obj(text) ?: return emptyList()
        val list = (root["emote_set"] as? JsonObject)?.get("emotes") ?: root["emotes"]
        val emotes = (list as? JsonArray) ?: return emptyList()
        return emotes.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val name = o["name"].str() ?: return@mapNotNull null
            val data = o["data"] as? JsonObject
            val host = data?.get("host") as? JsonObject
            val base = host?.get("url").str() ?: "//cdn.7tv.app/emote/$id"
            val files = host?.get("files") as? JsonArray
            // Prefer the 2x webp file; its width/height give the aspect ratio.
            val file = files?.firstOrNull { (it as? JsonObject)?.get("name").str() == "2x.webp" } as? JsonObject
            val w = file?.get("width").int()
            val h = file?.get("height").int()
            val flags = o["flags"].int() ?: 0
            val dataFlags = data?.get("flags").int() ?: 0
            Emote(
                id = id,
                code = name,
                url = (if (base.startsWith("//")) "https:$base" else base) + "/2x.webp",
                provider = EmoteProvider.SevenTv,
                aspect = if (w != null && h != null && h > 0) w.toFloat() / h else 1f,
                zeroWidth = (flags and 1) != 0 || (dataFlags and 256) != 0,
            )
        }
    }

    /** BTTV v3: global = array; channel = {channelEmotes:[], sharedEmotes:[]}. */
    fun bttv(text: String): List<Emote> {
        val items = arr(text)?.toList() ?: obj(text)?.let { root ->
            (root["channelEmotes"] as? JsonArray).orEmpty() + (root["sharedEmotes"] as? JsonArray).orEmpty()
        } ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val code = o["code"].str() ?: return@mapNotNull null
            val w = o["width"].int()
            val h = o["height"].int()
            Emote(
                id, code, "https://cdn.betterttv.net/emote/$id/2x.webp", EmoteProvider.Bttv,
                aspect = if (w != null && h != null && h > 0) w.toFloat() / h else 1f,
            )
        }
    }

    /** FFZ: `set/global` and `room/id/{id}` both carry {sets:{"<id>":{emoticons:[...]}}}. */
    fun ffz(text: String): List<Emote> {
        val root = obj(text) ?: return emptyList()
        val sets = root["sets"] as? JsonObject ?: return emptyList()
        // Global: only the default sets; room: the one set. When neither is stated take them all.
        val wanted = (root["default_sets"] as? JsonArray)?.mapNotNull { it.str() }?.toSet()
        val out = ArrayList<Emote>()
        for ((setId, set) in sets) {
            if (wanted != null && wanted.isNotEmpty() && setId !in wanted) continue
            val emoticons = (set as? JsonObject)?.get("emoticons") as? JsonArray ?: continue
            for (el in emoticons) {
                val o = el as? JsonObject ?: continue
                val id = o["id"].str() ?: continue
                val name = o["name"].str() ?: continue
                val urls = o["urls"] as? JsonObject ?: continue
                val u = urls["2"].str() ?: urls["1"].str() ?: urls["4"].str() ?: continue
                val w = o["width"].int()
                val h = o["height"].int()
                out += Emote(
                    id, name, if (u.startsWith("//")) "https:$u" else u, EmoteProvider.Ffz,
                    aspect = if (w != null && h != null && h > 0) w.toFloat() / h else 1f,
                )
            }
        }
        return out
    }
}
