package app.sadasync.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل بسيط في الذاكرة (آخر 300 سطر) يُرسل مع بلاغ الدعم. */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun d(msg: String) {
        Log.d("Sada", msg)
        lines.addLast("${fmt.format(Date())} $msg")
        if (lines.size > 300) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
