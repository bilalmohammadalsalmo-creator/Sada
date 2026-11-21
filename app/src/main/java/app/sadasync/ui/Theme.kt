package app.sadasync.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------ لوحة الألوان (هادئة وراكزة)
val Bg0 = Color(0xFF08101C)
val Bg1 = Color(0xFF0E1A2D)
val Surf1 = Color(0xFF121E33)
val Surf2 = Color(0xFF1A2842)
val Surf3 = Color(0xFF263A5C)
val Line = Color(0xFF26385A)
val Accent = Color(0xFF2EC4B6)
val AccentHi = Color(0xFF5EEAD4)
val OnAccent = Color(0xFF05201D)
val Gold = Color(0xFFF5B942)
val Good = Color(0xFF4ADE80)
val Bad = Color(0xFFFB7185)
val Muted = Color(0xFF93A4C0)
val TextMain = Color(0xFFEAF0FA)

private val SadaTypography = Typography(
    headlineLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp),
    bodyMedium = TextStyle(fontSize = 18.sp),
    bodySmall = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun SadaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = Bg0,
            surface = Surf1,
            onBackground = TextMain,
            onSurface = TextMain,
        ),
        typography = SadaTypography,
    ) {
        // الواجهة عربية: نفرض الاتجاه من اليمين لليسار مهما كانت لغة الجهاز
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(color = TextMain)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Bg1, Bg0)))
                ) {
                    content()
                }
            }
        }
    }
}

// ------------------------------------------------------------ عنصر قابل للتركيز بالريموت

/** صندوق يكبر قليلًا ويضيء إطاره عند التركيز؛ أساس كل الأزرار والصفوف. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    color: Color = Surf2,
    focusedColor: Color = Surf3,
    content: @Composable BoxScope.(Boolean) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(140), label = "focusScale")
    val bg by animateColorAsState(if (focused) focusedColor else color, tween(140), label = "focusBg")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (enabled) 1f else 0.45f)
            .background(bg, shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) AccentHi else Line, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

/** زر كبير مع أيقونة اختيارية. */
@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    icon: SadaIcon? = null,
    onClick: () -> Unit,
) {
    val fg = if (primary) OnAccent else TextMain
    FocusBox(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (primary) Accent else Surf2,
        focusedColor = if (primary) AccentHi else Surf3,
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) SIcon(icon, tint = fg, iconSize = 26.dp)
            Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
    }
}

/** بطاقة ثابتة (غير قابلة للتركيز). */
@Composable
fun SadaCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .background(Surf1, RoundedCornerShape(24.dp))
            .border(1.dp, Line, RoundedCornerShape(24.dp))
            .padding(24.dp),
    ) { content() }
}

/** حبّة حالة صغيرة ملوّنة. */
@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.18f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** صف مفتاح تشغيل/إيقاف كبير. */
@Composable
fun ToggleRow(label: String, checked: Boolean, desc: String? = null, onChange: (Boolean) -> Unit) {
    FocusBox({ onChange(!checked) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                if (desc != null) Text(desc, color = Muted, fontSize = 16.sp)
            }
            Box(
                Modifier
                    .size(width = 68.dp, height = 38.dp)
                    .background(if (checked) Accent else Surf3, CircleShape)
                    .padding(4.dp)
            ) {
                Box(
                    Modifier
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .size(30.dp)
                        .background(if (checked) OnAccent else Muted, CircleShape)
                )
            }
        }
    }
}

/** صف "اسم + قيمة" يُضغط لتغييرها. */
@Composable
fun ValueRow(label: String, value: String, desc: String? = null, onClick: () -> Unit) {
    FocusBox(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                if (desc != null) Text(desc, color = Muted, fontSize = 16.sp)
            }
            Pill(value, Accent)
        }
    }
}

/** عنوان صفحة مع أيقونة كبيرة وسطر شرح. */
@Composable
fun PageHeader(icon: SadaIcon, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(64.dp).background(Accent.copy(alpha = 0.16f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { SIcon(icon, tint = Accent, iconSize = 38.dp) }
        Column {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 18.sp)
        }
    }
}

// ------------------------------------------------------------ الأيقونات (مرسومة بالكود، بلا مكتبات)

enum class SadaIcon { Home, Library, Settings, Pulse, Support, Play, Stop, Plus, Minus, Folder, Refresh, Trash, Check, Cross, Wave, Download, Sync }

@Composable
fun SIcon(icon: SadaIcon, modifier: Modifier = Modifier, tint: Color = TextMain, iconSize: Dp = 32.dp) {
    Canvas(modifier.size(iconSize)) {
        val w = this.size.width
        val st = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * w, y * w)
        fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false): Path {
            val p = Path()
            pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x * w, y * w) else p.lineTo(x * w, y * w) }
            if (close) p.close()
            return p
        }
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, strokeWidth = st.width, cap = StrokeCap.Round)
        when (icon) {
            SadaIcon.Home -> {
                drawPath(poly(0.12f to 0.5f, 0.5f to 0.16f, 0.88f to 0.5f), tint, style = st)
                drawPath(poly(0.22f to 0.45f, 0.22f to 0.84f, 0.78f to 0.84f, 0.78f to 0.45f), tint, style = st)
                drawPath(poly(0.42f to 0.84f, 0.42f to 0.62f, 0.58f to 0.62f, 0.58f to 0.84f), tint, style = st)
            }
            SadaIcon.Library -> {
                drawCircle(tint, w * 0.12f, o(0.3f, 0.76f))
                drawCircle(tint, w * 0.12f, o(0.7f, 0.66f))
                line(o(0.41f, 0.76f), o(0.41f, 0.24f))
                line(o(0.81f, 0.66f), o(0.81f, 0.14f))
                line(o(0.41f, 0.24f), o(0.81f, 0.14f))
            }
            SadaIcon.Settings -> {
                line(o(0.12f, 0.25f), o(0.88f, 0.25f))
                line(o(0.12f, 0.5f), o(0.88f, 0.5f))
                line(o(0.12f, 0.75f), o(0.88f, 0.75f))
                drawCircle(tint, w * 0.1f, o(0.34f, 0.25f))
                drawCircle(tint, w * 0.1f, o(0.68f, 0.5f))
                drawCircle(tint, w * 0.1f, o(0.46f, 0.75f))
            }
            SadaIcon.Pulse -> drawPath(
                poly(0.08f to 0.5f, 0.3f to 0.5f, 0.4f to 0.22f, 0.55f to 0.8f, 0.66f to 0.5f, 0.92f to 0.5f),
                tint, style = st,
            )
            SadaIcon.Support -> {
                drawCircle(tint, w * 0.38f, o(0.5f, 0.5f), style = st)
                drawCircle(tint, w * 0.15f, o(0.5f, 0.5f), style = st)
                line(o(0.23f, 0.23f), o(0.39f, 0.39f))
                line(o(0.77f, 0.23f), o(0.61f, 0.39f))
                line(o(0.23f, 0.77f), o(0.39f, 0.61f))
                line(o(0.77f, 0.77f), o(0.61f, 0.61f))
            }
            SadaIcon.Play -> drawPath(poly(0.3f to 0.18f, 0.3f to 0.82f, 0.82f to 0.5f, close = true), tint)
            SadaIcon.Stop -> drawRect(tint, o(0.25f, 0.25f), Size(w * 0.5f, w * 0.5f))
            SadaIcon.Plus -> {
                line(o(0.5f, 0.2f), o(0.5f, 0.8f))
                line(o(0.2f, 0.5f), o(0.8f, 0.5f))
            }
            SadaIcon.Minus -> line(o(0.2f, 0.5f), o(0.8f, 0.5f))
            SadaIcon.Folder -> drawPath(
                poly(0.1f to 0.26f, 0.4f to 0.26f, 0.48f to 0.38f, 0.9f to 0.38f, 0.9f to 0.78f, 0.1f to 0.78f, close = true),
                tint, style = st,
            )
            SadaIcon.Refresh, SadaIcon.Sync -> {
                drawArc(tint, 40f, 280f, false, o(0.18f, 0.18f), Size(w * 0.64f, w * 0.64f), style = st)
                drawPath(poly(0.62f to 0.08f, 0.62f to 0.36f, 0.9f to 0.22f, close = true), tint)
            }
            SadaIcon.Trash -> {
                line(o(0.2f, 0.3f), o(0.8f, 0.3f))
                drawPath(poly(0.4f to 0.3f, 0.4f to 0.18f, 0.6f to 0.18f, 0.6f to 0.3f), tint, style = st)
                drawPath(poly(0.28f to 0.3f, 0.33f to 0.86f, 0.67f to 0.86f, 0.72f to 0.3f), tint, style = st)
            }
            SadaIcon.Check -> drawPath(poly(0.2f to 0.52f, 0.42f to 0.72f, 0.8f to 0.3f), tint, style = st)
            SadaIcon.Cross -> {
                line(o(0.26f, 0.26f), o(0.74f, 0.74f))
                line(o(0.74f, 0.26f), o(0.26f, 0.74f))
            }
            SadaIcon.Wave -> {
                val xs = floatArrayOf(0.14f, 0.3f, 0.46f, 0.62f, 0.78f, 0.9f)
                val hs = floatArrayOf(0.14f, 0.3f, 0.44f, 0.34f, 0.2f, 0.1f)
                xs.forEachIndexed { i, x -> line(o(x, 0.5f - hs[i]), o(x, 0.5f + hs[i])) }
            }
            SadaIcon.Download -> {
                line(o(0.5f, 0.15f), o(0.5f, 0.66f))
                drawPath(poly(0.28f to 0.46f, 0.5f to 0.68f, 0.72f to 0.46f), tint, style = st)
                line(o(0.2f, 0.84f), o(0.8f, 0.84f))
            }
        }
    }
}
