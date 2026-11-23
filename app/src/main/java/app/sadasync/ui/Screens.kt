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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import app.sadasync.data.Prefs
import app.sadasync.sync.CaptureProjectionActivity
import app.sadasync.sync.EngineUi
import app.sadasync.sync.EnvelopeStore
import app.sadasync.sync.SyncBrain
import app.sadasync.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private data class NavItem(val label: String, val icon: SadaIcon)

private val TABS = listOf(
    NavItem("الرئيسية", SadaIcon.Home),
    NavItem("المكتبة", SadaIcon.Library),
    NavItem("الإعدادات", SadaIcon.Settings),
    NavItem("التشخيص", SadaIcon.Pulse),
    NavItem("الدعم", SadaIcon.Support),
)

@Composable
fun MainScreen() {
    var tab by rememberSaveable { mutableStateOf(0) }
    val navReqs = remember { List(TABS.size) { FocusRequester() } }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { navReqs[0].requestFocus() }
    }

    Row(Modifier.fillMaxSize()) {
        // القائمة الجانبية: ضغطة واحدة لأعلى/أسفل = تبويب واحد بالضبط
        Column(
            Modifier
                .fillMaxHeight()
                .width(280.dp)
                .background(Surf1.copy(alpha = 0.85f))
                .padding(horizontal = 18.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.padding(start = 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SIcon(SadaIcon.Wave, tint = Accent, iconSize = 52.dp)
                Text("صدى", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = TextMain)
            }
            TABS.forEachIndexed { i, t ->
                FocusBox(
                    onClick = { tab = i },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(navReqs[i])
                        .focusProperties {
                            up = if (i > 0) navReqs[i - 1] else FocusRequester.Cancel
                            down = if (i < TABS.size - 1) navReqs[i + 1] else FocusRequester.Cancel
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = if (i == tab) Accent.copy(alpha = 0.22f) else Color.Transparent,
                    focusedColor = Surf3,
                ) { focused ->
                    // الصفحة تتبدّل عند الوقوف على التبويب (بعد لحظة قصيرة حتى لا تُثقل الحركة السريعة)
                    LaunchedEffect(focused) {
                        if (focused) {
                            delay(120)
                            tab = i
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SIcon(t.icon, tint = if (i == tab) Accent else TextMain, iconSize = 36.dp)
                        Text(
                            t.label,
                            fontSize = 23.sp,
                            fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Medium,
                            color = if (i == tab) AccentHi else TextMain,
                        )
                    }
                }
            }
        }
        // الصفحة: التنقل داخلها عادي، وللرجوع إلى القائمة اضغط السهم نحو اليمين
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 28.dp)) {
            when (tab) {
                0 -> HomeTab()
                1 -> LibraryTab()
                2 -> SettingsTab()
                3 -> DiagnosticsTab()
                else -> SupportTab()
            }
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

/** بلاطة حالة: أيقونة ملوّنة + عنوان + وصف قصير + زر إجراء عند الحاجة. */
@Composable
private fun StatusTile(
    icon: SadaIcon,
    title: String,
    state: String,
    ok: Boolean?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = when (ok) {
        true -> Good
        false -> Bad
        null -> Muted
    }
    SadaCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(60.dp).background(c.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { SIcon(icon, tint = c, iconSize = 34.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text(state, color = c, fontSize = 17.sp)
            }
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SIcon(if (ok) SadaIcon.Check else SadaIcon.Cross, tint = if (ok) Good else Bad, iconSize = 28.dp)
        Text(label, Modifier.weight(1f), fontSize = 19.sp)
        if (!ok) action?.invoke()
    }
}

@Composable
private fun HomeTab() {
    val ctx = LocalContext.current
    val app = App.instance
    val ui by app.player.ui.collectAsState()
    val captureOn by CaptureHolder.active.collectAsState()
    val eng by app.autoSync.ui.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val a11yOn = remember(tick) { isAccessibilityEnabled(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader(SadaIcon.Home, "الرئيسية", "تعليق عربي متزامن تلقائيًا مع صورة القناة")

        // بطاقة المزامنة الرئيسية
        SyncCard()

        // حالة الأذونات والتحكم
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusTile(
                SadaIcon.Settings, "لوحة التحكم فوق القناة",
                if (a11yOn) "مفعّلة" else "غير مفعّلة", a11yOn, Modifier.weight(1f),
            ) {
                if (!a11yOn) TvButton("فتح إعدادات الوصول", primary = true) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                StatusTile(
                    SadaIcon.Pulse, "التقاط صوت القناة",
                    if (captureOn) "يعمل" else "يحتاج إذنًا", captureOn, Modifier.weight(1f),
                ) {
                    if (!captureOn) TvButton("السماح", primary = true) {
                        ctx.startActivity(Intent(ctx, CaptureProjectionActivity::class.java))
                    }
                }
            }
            StatusTile(
                SadaIcon.Sync, "التحكم بالريسيفر",
                eng.control, null, Modifier.weight(1f),
            )
        }

        // التشغيل الآن
        SadaCard(Modifier.fillMaxWidth()) {
            Text("التشغيل الآن", style = MaterialTheme.typography.titleMedium, color = Muted)
            Spacer(Modifier.height(6.dp))
            Text(
                ui.source?.name ?: "لم تختر مصدرًا بعد (من المكتبة)",
                style = MaterialTheme.typography.titleLarge,
            )
            if (ui.status.isNotEmpty()) Text(ui.status, color = Muted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    if (ui.playing) "إيقاف" else "تشغيل",
                    primary = true,
                    icon = if (ui.playing) SadaIcon.Stop else SadaIcon.Play,
                ) { app.player.toggle() }
                TvButton("0.5", icon = SadaIcon.Minus) { app.player.nudgeDelay(-500) }
                Pill("تأخير صوتك ${fmtDelay(ui.delayMs)} ث", Accent)
                TvButton("0.5", icon = SadaIcon.Plus) { app.player.nudgeDelay(500) }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "لإظهار اللوحة فوق القناة اضغط زر ${keyName(app.prefs.triggerKey)} في الريموت مرتين.",
                color = Muted, fontSize = 17.sp,
            )
        }
    }
}

private fun phaseLabel(e: EngineUi): String = when {
    !e.enabled -> "المزامنة متوقفة"
    e.brain.chanSilent || e.brain.extSilent -> "لا يوجد صوت"
    e.brain.phase == SyncBrain.Phase.LOCKED -> "متزامن"
    e.brain.phase == SyncBrain.Phase.APPLYING -> "جارٍ التطبيق"
    e.brain.phase == SyncBrain.Phase.SEARCHING -> "جارٍ المطابقة"
    else -> "يستمع للقناة"
}

private fun phaseColor(e: EngineUi): Color = when {
    !e.enabled -> Muted
    e.brain.chanSilent || e.brain.extSilent -> Bad
    e.brain.phase == SyncBrain.Phase.LOCKED -> Good
    else -> Gold
}

/** بطاقة حالة المزامنة التلقائية المستمرة. */
@Composable
private fun SyncCard() {
    val app = App.instance
    val eng by app.autoSync.ui.collectAsState()
    val info = eng.brain
    val c = phaseColor(eng)
    SadaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(
                Modifier.size(110.dp).background(c.copy(alpha = 0.16f), CircleShape).border(3.dp, c, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SIcon(
                    if (eng.brain.phase == SyncBrain.Phase.LOCKED && eng.enabled) SadaIcon.Check else SadaIcon.Sync,
                    tint = c, iconSize = 60.dp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("المزامنة التلقائية", color = Muted, fontSize = 19.sp)
                Text(phaseLabel(eng), color = c, style = MaterialTheme.typography.headlineMedium)
                Text(info.text, fontSize = 19.sp)
                info.offsetMs?.let {
                    Text(
                        "الفرق المقاس: ${String.format(Locale.US, "%+.2f", it / 1000.0)} ث",
                        color = Muted, fontSize = 18.sp,
                    )
                }
                if (eng.lastAction.isNotEmpty()) Text("آخر إجراء: ${eng.lastAction}", color = Muted, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                if (eng.enabled) "إيقاف المزامنة" else "تشغيل المزامنة",
                primary = true,
                icon = if (eng.enabled) SadaIcon.Stop else SadaIcon.Play,
            ) { if (eng.enabled) app.autoSync.stop() else app.autoSync.start() }
            TvButton("مزامنة الآن", icon = SadaIcon.Refresh) { app.autoSync.resync() }
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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(SadaIcon.Library, "المكتبة", "الصوتيات الخارجية (تعليق عربي)")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("إضافة رابط", primary = true, icon = SadaIcon.Plus) { showAdd = true }
            TvButton("من الفلاشة", icon = SadaIcon.Folder) { picker.launch(arrayOf("*/*")) }
            TvButton("تحديث الرسمية", icon = SadaIcon.Download) {
                scope.launch { msg = if (app.sources.refreshOfficial()) "تم التحديث" else "تعذّر التحديث" }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Accent, fontWeight = FontWeight.Bold)

        if (sources.isEmpty()) {
            SadaCard(Modifier.fillMaxWidth()) {
                Text("لا توجد صوتيات بعد. أضف رابطًا أو ملف M3U / CFG من الفلاشة.", color = Muted)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sources, key = { it.id }) { s ->
                val current = ui.source?.id == s.id
                val playingNow = current && ui.playing
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FocusBox(
                        onClick = { if (playingNow) app.player.stop() else app.player.play(s) },
                        modifier = Modifier.weight(1f),
                        color = if (current) Accent.copy(alpha = 0.16f) else Surf1,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                Modifier.size(52.dp).background(if (playingNow) Accent else Surf3, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                SIcon(
                                    if (playingNow) SadaIcon.Stop else SadaIcon.Play,
                                    tint = if (playingNow) OnAccent else TextMain, iconSize = 30.dp,
                                )
                            }
                            Text(
                                s.name, Modifier.weight(1f),
                                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                                color = if (current) AccentHi else TextMain,
                            )
                            if (s.official) Pill("رسمي", Gold)
                            if (playingNow) Pill("يعمل", Good)
                        }
                    }
                    if (!s.official) TvButton("حذف", icon = SadaIcon.Trash) { app.sources.remove(s.id) }
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
        containerColor = Surf1,
        titleContentColor = TextMain,
        textContentColor = TextMain,
        title = { Text("إضافة رابط", fontWeight = FontWeight.Bold) },
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
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Accent, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun SettingsTab() {
    val app = App.instance
    val prefs = app.prefs
    val ui by app.player.ui.collectAsState()
    var trigger by remember { mutableIntStateOf(prefs.triggerKey) }
    var autoResync by remember { mutableStateOf(prefs.autoResyncOnChannelChange) }
    var autoSync by remember { mutableStateOf(prefs.autoSyncEnabled) }
    var fromMic by remember { mutableStateOf(prefs.captureFromMic) }
    var mode by remember { mutableIntStateOf(prefs.controlMode) }
    var pauseKey by remember { mutableIntStateOf(prefs.pauseKey) }
    var resumeKey by remember { mutableIntStateOf(prefs.resumeKey) }
    var calib by remember { mutableIntStateOf(prefs.calibMs) }
    // 0 = لا شيء، 1 = زر فتح اللوحة، 2 = زر الإيقاف المؤقت، 3 = زر الاستئناف
    var learning by remember { mutableIntStateOf(0) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(SadaIcon.Settings, "الإعدادات")

        SectionLabel("المزامنة التلقائية")
        ToggleRow("المزامنة التلقائية المستمرة", autoSync, "تقيس الفرق وتؤخر الصورة وحدها") {
            autoSync = it
            if (it) app.autoSync.start() else app.autoSync.stop()
        }
        ToggleRow("إعادة القياس بعد تغيير القناة", autoResync) {
            autoResync = it
            prefs.autoResyncOnChannelChange = it
        }
        ToggleRow("التقاط صوت القناة من الميكروفون", fromMic, "بدل صوت النظام (احتياطي)") {
            fromMic = it
            prefs.captureFromMic = it
        }

        SectionLabel("التحكم بالريسيفر (تأخير الصورة)")
        val modeName = when (mode) {
            Prefs.CONTROL_ROOT -> "root"
            Prefs.CONTROL_ADB -> "ADB"
            Prefs.CONTROL_MEDIA -> "مفتاح وسائط"
            else -> "تلقائي"
        }
        ValueRow("الطريقة", modeName, "تلقائي: root ثم ADB ثم مفتاح وسائط. اضغط للتغيير") {
            mode = (mode + 1) % 4
            prefs.controlMode = mode
        }
        ValueRow("زر الإيقاف المؤقت", keyName(pauseKey), "الزر المرسل للريسيفر. اضغط ثم اختر زرًا") { learning = 2 }
        ValueRow("زر الاستئناف", keyName(resumeKey), "الافتراضي PLAY_PAUSE لكليهما (مبدّل)") { learning = 3 }

        FocusBox({ }, Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("معايرة دقيقة", fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                    Text("إن بقي فرق ثابت بعد المزامنة، عدّل القيمة", color = Muted, fontSize = 16.sp)
                }
                Pill(String.format(Locale.US, "%+d م.ث", calib), Accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("20", icon = SadaIcon.Minus) { calib -= 20; prefs.calibMs = calib }
            TvButton("20", icon = SadaIcon.Plus) { calib += 20; prefs.calibMs = calib }
            TvButton("تصفير", icon = SadaIcon.Refresh) { calib = 0; prefs.calibMs = 0 }
        }

        SectionLabel("اللوحة والريموت")
        ValueRow("زر فتح اللوحة", keyName(trigger), "ضغطتان سريعتان. اضغط ثم اختر زرًا") { learning = 1 }
        ValueRow("التوقيت الحالي لصوتك", "${fmtDelay(ui.delayMs)} ث", "اضغط للتصفير") { app.player.setDelay(0) }
        Spacer(Modifier.height(24.dp))
    }

    if (learning != 0) {
        KeyLearnDialog(
            onKey = {
                when (learning) {
                    1 -> { prefs.triggerKey = it; trigger = it }
                    2 -> { prefs.pauseKey = it; pauseKey = it }
                    else -> { prefs.resumeKey = it; resumeKey = it }
                }
                learning = 0
            },
            onDismiss = { learning = 0 },
        )
    }
}

// ---------------------------------------------------------------- التشخيص

/** يجرّب فتح شاشات الإعدادات بالترتيب؛ بعض واجهات الريسيفرات تحذف بعضها. يعيد true عند أول نجاح. */
private fun openFirst(ctx: Context, intents: List<Intent>): Boolean {
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) {
        }
    }
    return false
}

private fun comp(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

private fun aboutIntents() = listOf(
    Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
    comp("com.android.tv.settings", "com.android.tv.settings.about.AboutActivity"),
    comp("com.android.settings", "com.android.settings.Settings\$DeviceInfoSettingsActivity"),
    Intent(Settings.ACTION_SETTINGS),
)

private fun developerIntents() = listOf(
    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
    comp("com.android.tv.settings", "com.android.tv.settings.system.development.DevelopmentActivity"),
    comp("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
    comp("com.android.settings", "com.android.settings.DevelopmentSettings"),
)

@Composable
private fun DiagnosticsTab() {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val eng by app.autoSync.ui.collectAsState()
    val ui by app.player.ui.collectAsState()
    var chanLvl by remember { mutableIntStateOf(0) }
    var extLvl by remember { mutableIntStateOf(0) }
    var log by remember { mutableStateOf("") }
    val testMsg by app.autoSync.testResult.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.nanoTime()
            val n = 2 * EnvelopeStore.BUCKETS_PER_SEC
            val from = now - n * EnvelopeStore.BUCKET_NS
            chanLvl = app.autoSync.chanEnv.slice(from, n).max().toInt()
            extLvl = app.player.refEnvelope.slice(from, n).max().toInt()
            log = AppLog.dump().lines().takeLast(14).joinToString("\n")
            delay(500)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(SadaIcon.Pulse, "التشخيص", "إن لم تعمل المزامنة، هنا تعرف أي جزء ناقص")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusTile(
                SadaIcon.Library, "صوتك (التعليق)",
                if (extLvl >= 5) "يعمل — المستوى $extLvl" else if (ui.playing) "يعمل بلا إشارة" else "شغّل مصدرًا من المكتبة",
                extLvl >= 5, Modifier.weight(1f),
            )
            StatusTile(
                SadaIcon.Pulse, "صوت القناة",
                if (chanLvl >= 5) "يصل — المستوى $chanLvl"
                else if (!eng.capturing) eng.capture
                else "الالتقاط يعمل لكن لا يصله صوت. غالبًا يمنعه الجهاز؛ جرّب الميكروفون من الإعدادات",
                chanLvl >= 5, Modifier.weight(1f),
            )
        }
        SadaCard(Modifier.fillMaxWidth()) {
            Text("الالتقاط: ${eng.capture}", fontSize = 18.sp)
            Text("التحكم بالريسيفر: ${eng.control}", fontSize = 18.sp)
        }

        SadaCard(Modifier.fillMaxWidth()) {
            var adbInfo by remember { mutableStateOf("…") }
            LaunchedEffect(Unit) {
                adbInfo = withContext(Dispatchers.IO) { app.autoSync.control.adbInfo() }
            }
            Text("حالة ADB في الريسيفر", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(adbInfo, color = Muted, fontSize = 17.sp)
        }

        SadaCard(Modifier.fillMaxWidth()) {
            val granting by app.autoSync.control.granting.collectAsState()
            val grantRes by app.autoSync.control.grantResult.collectAsState()
            Text("طلب وصول الريسيفر", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "يبحث تلقائيًا عن منفذ التصحيح في الريسيفر (بدون تحديد منفذ) ويطلب موافقتك. عند ظهور نافذة على الشاشة اختر «السماح دائمًا».",
                color = Muted, fontSize = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            TvButton(if (granting) "جارٍ البحث…" else "طلب وصول الريسيفر", primary = true, icon = SadaIcon.Settings) {
                app.autoSync.control.startGrant()
            }
            if (grantRes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(grantRes, color = Muted, fontSize = 16.sp)
            }
        }

        SadaCard(Modifier.fillMaxWidth()) {
            val ctx = LocalContext.current
            var devMsg by remember { mutableStateOf("") }
            Text("تفعيل خيارات المطوّر", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "1) اضغط «فتح حول الجهاز» ثم اضغط 7 مرات متتالية على «رقم البناء / Build number / رقم الإصدار» حتى تظهر رسالة «أنت الآن مطوّر».\n" +
                    "2) ارجع واضغط «فتح خيارات المطوّر» ثم فعّل «تصحيح USB» و«التصحيح اللاسلكي».",
                color = Muted, fontSize = 17.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("فتح حول الجهاز", icon = SadaIcon.Settings) {
                    devMsg = if (openFirst(ctx, aboutIntents())) ""
                    else "تعذّر فتح الإعدادات من التطبيق. افتحها من الريسيفر يدويًا"
                }
                TvButton("فتح خيارات المطوّر", primary = true, icon = SadaIcon.Settings) {
                    devMsg = if (openFirst(ctx, developerIntents())) ""
                    else "خيارات المطوّر غير مفعّلة بعد (نفّذ الخطوة 1) أو لا تدعمها واجهة الريسيفر"
                }
            }
            if (devMsg.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(devMsg, color = Gold, fontWeight = FontWeight.Bold)
            }
        }

        SadaCard(Modifier.fillMaxWidth()) {
            Text("اختبار التحكم بالريسيفر", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "شغّل القناة، ثم اضغط الزر هنا واخرج فورًا إلى القناة (لديك 15 ثانية، تكفي لعجلة التحميل). سيُرسل إيقافًا مؤقتًا ثم يستأنف بعد 5 ثوانٍ. إن تجمّدت الصورة فالتحكم يعمل. " +
                    "لطريقة ADB: فعّل خيارات المطوّر ثم \"تصحيح ADB عبر الشبكة\"، ووافق على المفتاح عند ظهور النافذة.",
                color = Muted, fontSize = 17.sp,
            )
            Spacer(Modifier.height(14.dp))
            // لا نعطّل الزر أثناء الاختبار: تعطيله يُفقده التركيز فيقفز إلى القائمة الجانبية وتتغير الصفحة
            TvButton("اختبار الإيقاف المؤقت", primary = true, icon = SadaIcon.Play) {
                app.autoSync.runPauseTest()
            }
            if (testMsg.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(testMsg, color = AccentHi, fontWeight = FontWeight.Bold)
            }
        }

        SadaCard(Modifier.fillMaxWidth()) {
            Text("آخر السجلات", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(log.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Spacer(Modifier.height(24.dp))
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
        Surface(shape = RoundedCornerShape(24.dp), color = Surf1) {
            Box(
                Modifier
                    .padding(40.dp)
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
                Text(
                    "اضغط الآن على الزر الذي تريده في الريموت\n(زر الرجوع للإلغاء)",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMain,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- الدعم والتحديث

private val PROBLEMS = listOf("لا يتزامن الصوت", "لا يوجد صوت", "التطبيق يتوقف", "أخرى")

@Composable
private fun SupportTab() {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var problem by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val wa = BuildConfig.SUPPORT_WHATSAPP
    val tg = BuildConfig.SUPPORT_TELEGRAM

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(SadaIcon.Support, "الدعم والتحديث")

        // تواصل مباشر: امسح الرمز بهاتفك
        if (wa.isNotEmpty() || tg.isNotEmpty()) {
            SadaCard(Modifier.fillMaxWidth()) {
                Text("تواصل معنا", style = MaterialTheme.typography.titleMedium)
                Text("امسح الرمز بكاميرا هاتفك لفتح المحادثة", color = Muted, fontSize = 17.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (wa.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            QrCode(wa, boxSize = 190.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("واتساب", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (tg.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            QrCode(tg, boxSize = 190.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("تيليغرام", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (BuildConfig.SUPPORT_PHONE.isNotEmpty()) {
                        Column {
                            Text("أو اتصل / راسل على", color = Muted)
                            Text(
                                BuildConfig.SUPPORT_PHONE,
                                fontSize = 30.sp, fontWeight = FontWeight.Bold, color = AccentHi,
                            )
                        }
                    }
                }
            }
        }

        // نموذج بلاغ سريع
        SadaCard(Modifier.fillMaxWidth()) {
            Text("أرسل مشكلتك", style = MaterialTheme.typography.titleMedium)
            Text("نرفق معها تلقائيًا سجل التطبيق ومعرّف جهازك", color = Muted, fontSize = 17.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PROBLEMS.forEachIndexed { i, label ->
                    TvButton(label, primary = i == problem) { problem = i }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("تفاصيل إضافية (اختياري)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TvButton("إرسال", primary = true, icon = SadaIcon.Support) {
                scope.launch {
                    val text = PROBLEMS[problem] + if (note.isNotBlank()) " — ${note.trim()}" else ""
                    msg = if (SupportReporter.send(app.prefs, text)) {
                        note = ""
                        "وصلنا بلاغك، شكرًا"
                    } else {
                        "تعذّر الإرسال. تأكد من الإنترنت أو تواصل معنا مباشرة"
                    }
                }
            }
        }

        SadaCard(Modifier.fillMaxWidth()) {
            Text("الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text("معرّف الجهاز: ${app.prefs.deviceId.take(8)}", color = Muted)
            Spacer(Modifier.height(12.dp))
            TvButton("التحقق من التحديث", primary = true, icon = SadaIcon.Refresh) {
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
                Spacer(Modifier.height(12.dp))
                if (u.notes.isNotEmpty()) Text(u.notes)
                Spacer(Modifier.height(8.dp))
                TvButton("تنزيل وتثبيت ${u.versionName}", primary = true, icon = SadaIcon.Download) {
                    UpdateChecker.downloadAndInstall(ctx, u)
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = Accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
    }
}
