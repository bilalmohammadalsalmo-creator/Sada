package app.sadasync.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {
    suspend fun get(url: String, timeoutMs: Int = 8000): String = request(url, null, timeoutMs)

    suspend fun postJson(url: String, body: String, timeoutMs: Int = 8000): String =
        request(url, body, timeoutMs)

    private suspend fun request(url: String, body: String?, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.instanceFollowRedirects = true
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code >= 400) throw IOException("HTTP $code")
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                c.disconnect()
            }
        }
}
