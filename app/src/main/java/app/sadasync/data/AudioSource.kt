package app.sadasync.data

import org.json.JSONObject
import java.util.UUID

data class AudioSource(
    val id: String,
    val name: String,
    val url: String,
    val official: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)

    companion object {
        fun idFor(url: String): String = UUID.nameUUIDFromBytes(url.toByteArray()).toString()

        fun fromJson(o: JSONObject): AudioSource {
            val url = o.getString("url")
            return AudioSource(
                id = o.optString("id").ifEmpty { idFor(url) },
                name = o.optString("name").ifEmpty { url },
                url = url,
            )
        }
    }
}

/** تصنيف الصوتيات إلى مجموعات في القائمة المنسدلة، والصوتية الفارغة المدمجة. */
object SourceGroups {
    const val SILENCE_ID = "builtin-silence"
    const val SILENCE_URL = "sada://silence"
    const val OTHER = "أخرى"
    val named = listOf("صوتيات أنيس", "فوكس", "صوتيات القرآن الكريم")

    fun silence() = AudioSource(SILENCE_ID, "صوتية فارغة", SILENCE_URL, official = true)

    fun of(s: AudioSource): String {
        val n = s.name.lowercase()
        return when {
            n.contains("أنيس") || n.contains("انيس") || n.contains("anis") -> named[0]
            n.contains("فوكس") || n.contains("fox") -> named[1]
            n.contains("قرآن") || n.contains("قران") || n.contains("quran") || n.contains("koran") || n.contains("تلاوة") -> named[2]
            else -> OTHER
        }
    }
}
