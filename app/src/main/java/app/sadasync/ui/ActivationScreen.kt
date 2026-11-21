package app.sadasync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.sadasync.App
import app.sadasync.data.InviteRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** شاشة رمز الدعوة: لوحة أرقام تعمل بالريموت، والرمز صالح لجهاز واحد (يتحقق الخادم). */
@Composable
fun ActivationScreen(onActivated: () -> Unit) {
    val app = App.instance
    val repo = remember { InviteRepository(app.prefs) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(200)
        runCatching { first.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("رمز الدعوة", style = MaterialTheme.typography.headlineLarge, color = Accent)
        Text("اكتب الرمز المكوّن من 6 أرقام الذي وصلك", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            (0 until 6).joinToString("  ") { if (it < code.length) "●" else "○" },
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(16.dp))

        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.forEach { d ->
                    TvButton(
                        d,
                        Modifier.width(110.dp).then(if (d == "1") Modifier.focusRequester(first) else Modifier),
                    ) {
                        if (code.length < 6) code += d
                        error = null
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("مسح", Modifier.width(110.dp)) { code = "" }
            TvButton("0", Modifier.width(110.dp)) { if (code.length < 6) code += "0" }
            TvButton("⌫", Modifier.width(110.dp)) { code = code.dropLast(1) }
        }
        Spacer(Modifier.height(16.dp))
        TvButton("تفعيل", primary = true, enabled = code.length == 6 && !busy) {
            busy = true
            scope.launch {
                val r = repo.activate(code)
                busy = false
                r.onSuccess { onActivated() }
                    .onFailure {
                        error = it.message ?: "فشل التفعيل"
                        code = ""
                    }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF6B6B)) }
    }
}
