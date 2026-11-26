package app.sadasync.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.sadasync.BuildConfig
import app.sadasync.util.AppLog
import app.sadasync.util.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

class SourceRepository(private val ctx: Context, private val prefs: Prefs) {
    private val _all = MutableStateFlow<List<AudioSource>>(emptyList())
    val all: StateFlow<List<AudioSource>> = _all

    init {
        reload()
    }

    fun find(id: String?): AudioSource? = id?.let { i -> _all.value.firstOrNull { it.id == i } }

    private fun reload() {
        val official = parse(prefs.officialCacheJson)
            .ifEmpty { parse(ctx.assets.open("official_sources.json").bufferedReader().use { it.readText() }) }
            .map { it.copy(official = true) }
        // الصوتية الفارغة المدمجة أولًا، ونحذف رابط المثال التجريبي القديم
        _all.value = listOf(SourceGroups.silence()) +
            official.filter { !it.url.contains("example.com") } +
            parse(prefs.userSourcesJson)
    }

    private fun parse(json: String): List<AudioSource> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { AudioSource.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    /** يحدّث القائمة الرسمية من الخادم. */
    suspend fun refreshOfficial(): Boolean = try {
        val text = Http.get("${BuildConfig.API_BASE}/sources.json")
        if (parse(text).isEmpty()) {
            false
        } else {
            prefs.officialCacheJson = text
            reload()
            true
        }
    } catch (e: Exception) {
        AppLog.d("refreshOfficial failed: $e")
        false
    }

    private fun saveUser(list: List<AudioSource>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.userSourcesJson = arr.toString()
        reload()
    }

    private fun userSources() = _all.value.filter { !it.official }

    fun addAll(items: List<Pair<String, String>>): Int {
        val existing = userSources().toMutableList()
        var added = 0
        for ((name, url) in items) {
            val id = AudioSource.idFor(url)
            if (existing.none { it.id == id }) {
                existing += AudioSource(id, name, url)
                added++
            }
        }
        if (added > 0) saveUser(existing)
        return added
    }

    fun remove(id: String) = saveUser(userSources().filter { it.id != id })

    /** يضيف رابطًا: إن كان قائمة (m3u/cfg/txt) تُستورد عناصرها، وإلا يُضاف كبث واحد. */
    suspend fun addFromUrl(url: String): Int {
        val clean = url.trim()
        if (!PlaylistParser.isUrl(clean)) return 0
        if (PlaylistParser.looksLikeList(clean)) {
            val items = PlaylistParser.parse(Http.get(clean))
            if (items.isNotEmpty()) return addAll(items)
        }
        return addAll(listOf(PlaylistParser.nameFromUrl(clean) to clean))
    }

    /** يضيف ملفًا من الفلاشة/التنزيلات (Uri من OpenDocument). */
    suspend fun addFromLocal(uri: Uri): Int = withContext(Dispatchers.IO) {
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "ملف"
        if (PlaylistParser.looksLikeList(name)) {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val items = PlaylistParser.parse(text)
            if (items.isNotEmpty()) return@withContext addAll(items)
        }
        addAll(listOf(name.substringBeforeLast('.') to uri.toString()))
    }
}
