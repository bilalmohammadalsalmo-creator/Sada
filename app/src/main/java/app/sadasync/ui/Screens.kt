package app.sadasync.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.sadasync.App
import app.sadasync.BuildConfig
import app.sadasync.data.SupportReporter
import app.sadasync.data.UpdateChecker
import app.sadasync.data.UpdateInfo
import app.sadasync.overlay.PanelAccessibilityService
import app.sadasync.sync.CaptureHolder
import app.sadasync.sync.CaptureProjectionActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val TABS = listOf("الرئيسية", "المكتبة", "الإعدادات", "الدعم")

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("صدى", style = MaterialTheme.typography.headlineMedium, color = Accent)
            Spacer(Modifier.width(24.dp))
            TABS.forEachIndexed { i, t -> TvButton(t, primary = i == tab) { tab = i } }
        }
        Spacer(Modifier.height(20.dp))
        when (tab) {
            0 -> HomeTab()
            1 -> LibraryTab()
            2 -> SettingsTab()
            else -> SupportTab()
        }
    }
}

// ---------------------------------------------------------------- الرئيسية

private fun isAccessibilityEnabled(ctx: Context): Boolean {
    val cn = ComponentName(ctx, PanelAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabled.split(':').any {
        it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
    }
}

private fun keyName(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

private fun fmtDelay(ms: Int) = String.format(Locale.US, "%+.1f", ms / 1000.0)

@Composable
private fun StatusRow(label: String, ok: Boolean, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (ok) "✓" else "✗", color = if (ok) Color(0xFF5BD97B) else Color(0xFFFF6B6B))
        Text(label, Modifier.weight(1f))
        if (!ok) action?.invoke()
    }
}

@Composable
private fun HomeTab() {
    val ctx = LocalContext.current
    val app = App.instance
    val ui by app.player.ui.collectAsState()
    val captureOn by CaptureHolder.active.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val a11yOn = remember(tick) { isAccessibilityEnabled(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الحالة", style = MaterialTheme.typography.titleLarge)
        StatusRow("الجهاز مفعّل برمز الدعوة", true)
        StatusRow("خدمة الوصول (اللوحة فوق القناة)", a11yOn) {
            TvButton("فتح إعدادات الوصول", primary = true) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            StatusRow("المزامنة التلقائية (التقاط صوت القناة)", captureOn) {
                TvButton("السماح") { ctx.startActivity(Intent(ctx, CaptureProjectionActivity::class.java)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "كيف تستخدمه: شغّل القناة ثم اضغط زر ${keyName(app.prefs.triggerKey)} في الريموت مرتين " +
                "لتظهر اللوحة فوق القناة.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(12.dp))
        Text("التشغيل الآن", style = MaterialTheme.typography.titleLarge)
        Text(ui.source?.name ?: "لم تختر مصدرًا بعد (من المكتبة)")
        if (ui.status.isNotEmpty() || ui.syncStatus.isNotEmpty()) {
            Text(listOf(ui.status, ui.syncStatus).filter { it.isNotEmpty() }.joinToString(" • "))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton(if (ui.playing) "إيقاف" else "تشغيل", primary = true) { app.player.toggle() }
            TvButton("−0.5") { app.player.nudgeDelay(-500) }
            Text("التوقيت ${fmtDelay(ui.delayMs)} ث")
            TvButton("+0.5") { app.player.nudgeDelay(500) }
            TvButton("مزامنة تلقائية") { app.autoSync.start() }
        }
    }
}

// ---------------------------------------------------------------- المكتبة

@Composable
private fun LibraryTab() {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by app.sources.all.collectAsState()
    val ui by app.player.ui.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                val n = app.sources.addFromLocal(uri)
                msg = if (n > 0) "أُضيف $n عنصر" else "لم يُضف شيء"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("إضافة من رابط", primary = true) { showAdd = true }
            TvButton("إضافة من الفلاشة أو التنزيلات") { picker.launch(arrayOf("*/*")) }
            TvButton("تحديث القوائم الرسمية") {
                scope.launch { msg = if (app.sources.refreshOfficial()) "تم التحديث" else "تعذّر التحديث" }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Accent)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.id }) { s ->
                val current = ui.source?.id == s.id
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        (if (current) "▶ " else "") + s.name + if (s.official) "  (رسمي)" else "",
                        Modifier.weight(1f),
                        color = if (current) Accent else Color.Unspecified,
                    )
                    TvButton(if (current && ui.playing) "إيقاف" else "تشغيل", primary = true) {
                        if (current && ui.playing) app.player.stop() else app.player.play(s)
                    }
                    if (!s.official) TvButton("حذف") { app.sources.remove(s.id) }
                }
            }
        }
    }

    if (showAdd) {
        AddUrlDialog(
            onDismiss = { showAdd = false },
            onAdd = { url ->
                showAdd = false
                scope.launch {
                    msg = try {
                        val n = app.sources.addFromUrl(url)
                        if (n > 0) "أُضيف $n عنصر" else "لم يُضف شيء"
                    } catch (e: Exception) {
                        "تعذّرت الإضافة: ${e.message}"
                    }
                }
            },
        )
    }
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة رابط") },
        text = {
            // الروابط تُكتب من اليسار لليمين
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("رابط صوت أو قائمة M3U / CFG") },
                )
            }
        },
        confirmButton = { TvButton("إضافة", primary = true) { if (url.isNotBlank()) onAdd(url.trim()) } },
        dismissButton = { TvButton("إلغاء", onClick = onDismiss) },
    )
}

// ---------------------------------------------------------------- الإعدادات

@Composable
private fun SettingsTab() {
    val app = App.instance
    val ui by app.player.ui.collectAsState()
    var trigger by remember { mutableIntStateOf(app.prefs.triggerKey) }
    var autoResync by remember { mutableStateOf(app.prefs.autoResyncOnChannelChange) }
    var learning by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("زر فتح اللوحة (ضغطتان سريعتان): ${keyName(trigger)}", style = MaterialTheme.typography.bodyLarge)
        TvButton("تغيير الزر") { learning = true }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = autoResync, onCheckedChange = {
                autoResync = it
                app.prefs.autoResyncOnChannelChange = it
            })
            Text("إعادة المزامنة تلقائيًا بعد تغيير القناة")
        }

        Text("التوقيت الحالي: ${fmtDelay(ui.delayMs)} ث")
        TvButton("تصفير التوقيت") { app.player.setDelay(0) }
    }

    if (learning) {
        KeyLearnDialog(
            onKey = {
                app.prefs.triggerKey = it
                trigger = it
                learning = false
            },
            onDismiss = { learning = false },
        )
    }
}

@Composable
private fun KeyLearnDialog(onKey: (Int) -> Unit, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { fr.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Box(
                Modifier
                    .padding(32.dp)
                    .focusRequester(fr)
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown) {
                            val code = e.nativeKeyEvent.keyCode
                            if (code == KeyEvent.KEYCODE_BACK) onDismiss() else onKey(code)
                            true
                        } else false
                    }
            ) {
                Text("اضغط الآن على الزر الذي تريده في الريموت\n(زر الرجوع للإلغاء)")
            }
        }
    }
}

// ---------------------------------------------------------------- الدعم والتحديث

@Composable
private fun SupportTab() {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الإصدار ${BuildConfig.VERSION_NAME}  •  معرّف الجهاز ${app.prefs.deviceId.take(8)}")
        Text("بوت الدعم: ${BuildConfig.SUPPORT_BOT}")

        TvButton("التحقق من التحديث", primary = true) {
            scope.launch {
                msg = "جارٍ التحقق…"
                try {
                    val info = UpdateChecker.check()
                    update = info
                    msg = if (info == null) "أنت على آخر إصدار" else "يوجد إصدار جديد ${info.versionName}"
                } catch (e: Exception) {
                    msg = "تعذّر التحقق: ${e.message}"
                }
            }
        }
        update?.let { u ->
            if (u.notes.isNotEmpty()) Text(u.notes)
            TvButton("تنزيل وتثبيت ${u.versionName}") { UpdateChecker.downloadAndInstall(ctx, u) }
        }

        TvButton("إرسال بلاغ للدعم (يرسل سجل التطبيق)") {
            scope.launch {
                msg = if (SupportReporter.send(app.prefs)) "أُرسل البلاغ" else "تعذّر إرسال البلاغ"
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Accent)
    }
}
