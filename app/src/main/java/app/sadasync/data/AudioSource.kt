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
