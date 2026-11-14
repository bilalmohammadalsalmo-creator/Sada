package app.sadasync.data

/**
 * يقرأ قوائم M3U / M3U8 (مع #EXTINF) وملفات CFG/TXT البسيطة بصيغة:
 *   اسم=الرابط    أو    اسم|الرابط    أو رابط وحده في كل سطر.
 */
object PlaylistParser {
    private val named = Regex("^(.+?)\\s*[=|]\\s*((?:https?|content|file)://.+)$")

    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var pendingName: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) ->
                    pendingName = line.substringAfterLast(',', "").trim().ifEmpty { null }
                line.startsWith("#") || line.startsWith(";") -> Unit
                isUrl(line) -> {
                    out += (pendingName ?: nameFromUrl(line)) to line
                    pendingName = null
                }
                else -> named.find(line)?.let { out += it.groupValues[1].trim() to it.groupValues[2].trim() }
            }
        }
        return out
    }

    fun isUrl(s: String) = s.startsWith("http://", true) || s.startsWith("https://", true) ||
        s.startsWith("content://", true) || s.startsWith("file://", true)

    fun nameFromUrl(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { url }

    /** هل الرابط/الاسم يشير إلى قائمة (وليس بثًا مباشرًا)؟ ملاحظة: m3u8 يُعامل كبث HLS. */
    fun looksLikeList(nameOrUrl: String): Boolean {
        val ext = nameOrUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
        return ext in setOf("m3u", "cfg", "txt", "list")
    }
}
