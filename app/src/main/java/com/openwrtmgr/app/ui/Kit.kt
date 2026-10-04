package com.openwrtmgr.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.IconButton

/** One complete colour set. [dark] decides status-bar icon colour and M3 base scheme. */
data class Palette(
    val id: String, val label: String, val dark: Boolean,
    val bg: Color, val panel: Color, val raised: Color, val line: Color, val lineStrong: Color,
    val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val violet: Color, val ok: Color, val warn: Color, val bad: Color, val blue: Color,
)

object Themes {
    // Ferry's palette: warm paper light, near-black dark, blue/violet brand.
    val daylight = Palette("daylight", "Daylight", false,
        Color(0xFFF5F4F0), Color(0xFFFFFFFF), Color(0xFFEAE8E2), Color(0xFFE4E2DC), Color(0xFFD3D0C8),
        Color(0xFF18181B), Color(0xFF6B6B74), Color(0xFF9A9AA3),
        Color(0xFF2456F5), Color(0xFF7A5CFF), Color(0xFF0F7B4F), Color(0xFF9A5B00), Color(0xFFC0262D), Color(0xFF1D8FD6))
    val night = Palette("night", "Night", true,
        Color(0xFF0E0F11), Color(0xFF17181B), Color(0xFF222328), Color(0xFF2A2B31), Color(0xFF383A41),
        Color(0xFFECECEF), Color(0xFF9C9CA6), Color(0xFF6A6B75),
        Color(0xFF7090FF), Color(0xFFA18BFF), Color(0xFF4CCF93), Color(0xFFF0B44C), Color(0xFFFF7B7F), Color(0xFF6CCBFF))
    val console = Palette("console", "Console", true,
        Color(0xFF080B10), Color(0xFF0F141B), Color(0xFF151C25), Color(0xFF1E2833), Color(0xFF2B3746),
        Color(0xFFE6EDF3), Color(0xFF8D9AAB), Color(0xFF566375),
        Color(0xFF22D3EE), Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF60A5FA))
    val amoled = Palette("amoled", "AMOLED Black", true,
        Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF141414), Color(0xFF1C1C1C), Color(0xFF2A2A2A),
        Color(0xFFF2F2F2), Color(0xFF9A9A9A), Color(0xFF5E5E5E),
        Color(0xFF00E5FF), Color(0xFFB388FF), Color(0xFF00E676), Color(0xFFFFD740), Color(0xFFFF5252), Color(0xFF448AFF))
    val midnight = Palette("midnight", "Midnight", true,
        Color(0xFF0B1020), Color(0xFF111833), Color(0xFF18213F), Color(0xFF232E52), Color(0xFF34406A),
        Color(0xFFE4E9FF), Color(0xFF94A0C8), Color(0xFF5D6890),
        Color(0xFF7AA2FF), Color(0xFFC792EA), Color(0xFF5EEAD4), Color(0xFFFFCB6B), Color(0xFFFF7A90), Color(0xFF82AAFF))
    val nord = Palette("nord", "Nord", true,
        Color(0xFF242933), Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFF434C5E), Color(0xFF4C566A),
        Color(0xFFECEFF4), Color(0xFFB0BACB), Color(0xFF7B879C),
        Color(0xFF88C0D0), Color(0xFFB48EAD), Color(0xFFA3BE8C), Color(0xFFEBCB8B), Color(0xFFBF616A), Color(0xFF81A1C1))
    val dracula = Palette("dracula", "Dracula", true,
        Color(0xFF1E1F29), Color(0xFF282A36), Color(0xFF313442), Color(0xFF3B3E50), Color(0xFF4A4E66),
        Color(0xFFF8F8F2), Color(0xFFB4B6C8), Color(0xFF6E7290),
        Color(0xFFFF79C6), Color(0xFFBD93F9), Color(0xFF50FA7B), Color(0xFFF1FA8C), Color(0xFFFF5555), Color(0xFF8BE9FD))
    val matrix = Palette("matrix", "Matrix", true,
        Color(0xFF020803), Color(0xFF071209), Color(0xFF0C1A0F), Color(0xFF123018), Color(0xFF1B4423),
        Color(0xFFC8FFD0), Color(0xFF6FBF7E), Color(0xFF3E7348),
        Color(0xFF39FF6A), Color(0xFF9DFFB0), Color(0xFF39FF6A), Color(0xFFE6FF4D), Color(0xFFFF5F56), Color(0xFF4DFFC3))
    val light = Palette("light", "Light", false,
        Color(0xFFF4F6F9), Color(0xFFFFFFFF), Color(0xFFEDF1F5), Color(0xFFDDE3EA), Color(0xFFC6CFD9),
        Color(0xFF0F1720), Color(0xFF51606F), Color(0xFF8592A0),
        Color(0xFF0891B2), Color(0xFF7C3AED), Color(0xFF059669), Color(0xFFB45309), Color(0xFFDC2626), Color(0xFF2563EB))
    val solarized = Palette("solarized", "Solarized Light", false,
        Color(0xFFFDF6E3), Color(0xFFFFFBEF), Color(0xFFEEE8D5), Color(0xFFE2DBC3), Color(0xFFCFC6A8),
        Color(0xFF073642), Color(0xFF586E75), Color(0xFF93A1A1),
        Color(0xFF268BD2), Color(0xFF6C71C4), Color(0xFF859900), Color(0xFFB58900), Color(0xFFDC322F), Color(0xFF2AA198))

    val all = listOf(daylight, night, console, amoled, midnight, nord, dracula, matrix, light, solarized)
    const val SYSTEM = "system"

    /** "system" follows the phone's dark/light setting: Night by dark, Daylight by day. */
    fun resolve(id: String, systemDark: Boolean) = all.firstOrNull { it.id == id } ?: if (systemDark) night else daylight
}

/** Active palette. Backed by Compose state, so switching theme recolours every screen instantly. */
object Ops {
    var palette by mutableStateOf(Themes.console)
    val bg get() = palette.bg
    val panel get() = palette.panel
    val raised get() = palette.raised
    val line get() = palette.line
    val lineStrong get() = palette.lineStrong
    val text get() = palette.text
    val muted get() = palette.muted
    val faint get() = palette.faint
    val accent get() = palette.accent   // primary, RX
    val violet get() = palette.violet   // TX
    val ok get() = palette.ok
    val warn get() = palette.warn
    val bad get() = palette.bad
    val blue get() = palette.blue
    val onAccent get() = onAccentFor(palette)
}

/** Vivid, theme-independent feature colours for icon tiles. */
object Tints {
    val blue = Color(0xFF3B6CFF); val violet = Color(0xFF7C5CFF); val teal = Color(0xFF14B8A6); val orange = Color(0xFFF97316)
    val pink = Color(0xFFEC4899); val green = Color(0xFF22A06B); val amber = Color(0xFFF2A100); val red = Color(0xFFEF4444)
    val cyan = Color(0xFF0EA5E9); val slate = Color(0xFF64748B)
    private val cycle = listOf(blue, violet, teal, orange, pink, green, amber, red, cyan)
    fun pick(key: String) = cycle[kotlin.math.abs(key.hashCode()) % cycle.size]
}

/** Text/icon colour with the better contrast on [Palette.accent]: white or the theme's own darkest ink. */
fun onAccentFor(p: Palette): Color {
    val dark = if (p.dark) p.bg else p.text
    fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
    return if (contrast(p.accent, Color.White) >= contrast(p.accent, dark)) Color.White else dark
}

/** Hero gradient built from the current theme. Dark themes sink the accents into the background so white text stays readable. */
val BrandGradient: Brush get() {
    val p = Ops.palette
    val a = if (p.dark) lerp(p.accent, p.bg, 0.55f) else p.accent
    val b = if (p.dark) lerp(p.violet, p.bg, 0.55f) else p.violet
    return Brush.linearGradient(listOf(a, lerp(a, b, 0.5f), b))
}

val Mono = FontFamily.Monospace

private fun schemeFor(p: Palette): ColorScheme {
    val onAccent = onAccentFor(p)
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent, onPrimary = onAccent,
        primaryContainer = p.accent.copy(alpha = 0.18f).compositeOver(p.panel), onPrimaryContainer = p.text,
        secondary = p.violet, onSecondary = onAccent,
        secondaryContainer = p.violet.copy(alpha = 0.18f).compositeOver(p.panel), onSecondaryContainer = p.text,
        tertiary = p.ok, onTertiary = onAccent,
        error = p.bad, onError = onAccent, errorContainer = p.bad.copy(alpha = 0.18f).compositeOver(p.panel), onErrorContainer = p.text,
        background = p.bg, onBackground = p.text,
        surface = p.bg, onSurface = p.text,
        surfaceVariant = p.raised, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.panel, surfaceContainer = p.panel,
        surfaceContainerHigh = p.raised, surfaceContainerHighest = p.lineStrong,
        // Every remaining M3 slot defaults to the stock purple/grey scheme, which shows up as washed-out
        // tints on dark themes (elevation overlay uses surfaceTint = primary).
        surfaceTint = Color.Transparent, surfaceDim = p.bg, surfaceBright = p.raised,
        tertiaryContainer = p.ok.copy(alpha = 0.18f).compositeOver(p.panel), onTertiaryContainer = p.text,
        outline = p.lineStrong, outlineVariant = p.line,
        inverseSurface = p.text, inverseOnSurface = p.bg, inversePrimary = p.accent,
        scrim = Color(0xCC000000),
    )
}
private val type = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, fontSize = 30.sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp, fontSize = 12.sp),
    )
}

@Composable
fun OpsTheme(content: @Composable () -> Unit) {
    val p = Ops.palette
    MaterialTheme(colorScheme = remember(p) { schemeFor(p) }, typography = type, content = content)
}

// ---------------------------------------------------------------- motion

/** Material 3 emphasized curves: quick start, long gentle settle. */
val EmphasizedDecel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Clickable that sinks slightly under the finger and springs back on release. */
fun Modifier.pressClick(onClick: () -> Unit, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp), scaleTo: Float = 0.97f): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) scaleTo else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick)
}

/**
 * Fades and lifts a card in the first time it appears. Cards lower on screen start a little
 * later, so a fresh screen fills in top to bottom. rememberSaveable keeps lazy-list items
 * from replaying it when scrolled back into view or when returning from a pushed screen.
 */
fun Modifier.enterOnce(): Modifier = composed {
    var seen by rememberSaveable { mutableStateOf(false) }
    if (seen) return@composed this
    val progress = remember { Animatable(0f) }
    var y by remember { mutableStateOf<Float?>(null) }
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val lift = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(y != null) {
        val top = y ?: return@LaunchedEffect
        kotlinx.coroutines.delay((top / screenPx).coerceIn(0f, 1f).times(220).toLong())
        progress.animateTo(1f, tween(520, easing = EmphasizedDecel))
        seen = true
    }
    onGloballyPositioned { if (y == null) y = it.positionInWindow().y }
        .graphicsLayer { alpha = progress.value; translationY = (1 - progress.value) * lift }
}

// ---------------------------------------------------------------- building blocks

private val PanelShape = RoundedCornerShape(22.dp)

/** The basic container: a bordered dark panel with an optional mono-caps header. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .enterOnce()
            .then(if (onClick != null) Modifier.pressClick(onClick, PanelShape, 0.98f) else Modifier)
            .fillMaxWidth()
            .clip(PanelShape)
            .background(Ops.panel)
            .border(1.dp, Ops.line.copy(alpha = 0.6f), PanelShape)
            .padding(padding)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Text(title, Modifier.weight(1f), color = Ops.text, style = MaterialTheme.typography.titleMedium, maxLines = 1) else Spacer(Modifier.weight(1f))
                action?.invoke(this)
            }
        }
        content()
    }
}

/** Uppercase, tracked label: panel headers and metric captions. */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = Ops.muted) =
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = Ops.text, size: Int = 13, weight: FontWeight = FontWeight.Normal, maxLines: Int = 1) =
    Text(text, modifier, fontFamily = Mono, fontSize = size.sp, color = color, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** Big number + unit, the console's primary readout. */
@Composable
fun Metric(label: String, value: String, unit: String = "", color: Color = Ops.text, sub: String? = null, modifier: Modifier = Modifier,
           labelColor: Color = Ops.muted, unitColor: Color = Ops.muted) {
    Column(modifier) {
        Caps(label, color = labelColor)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // Live readouts roll up or down to their new value instead of flicking.
            AnimatedContent(
                value, label = "metric",
                transitionSpec = {
                    val up = (targetState.toDoubleOrNull() ?: 0.0) >= (initialState.toDoubleOrNull() ?: 0.0)
                    (slideInVertically(tween(320, easing = EmphasizedDecel)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(220)))
                        .togetherWith(slideOutVertically(tween(220, easing = EmphasizedAccel)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(160)))
                },
            ) { v -> Text(v, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = color, letterSpacing = (-0.8).sp) }
            if (unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = unitColor, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (sub != null) Text(sub, fontSize = 12.sp, color = Ops.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Status dot; [pulse] adds a slow halo for "live" states. */
@Composable
fun Dot(color: Color, pulse: Boolean = false, size: Dp = 8.dp) {
    val halo = if (pulse) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "halo").value
    } else 0f
    Box(Modifier.size(size * 2), contentAlignment = Alignment.Center) {
        if (pulse) Box(Modifier.size(size * (1 + halo)).clip(CircleShape).background(color.copy(alpha = 0.35f * (1 - halo))))
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

@Composable
fun Tag(text: String, color: Color = Ops.muted, modifier: Modifier = Modifier) {
    Text(
        text, modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
    )
}

/** Thin usage bar with animated fill; turns amber/red as it fills. */
@Composable
fun UsageBar(pct: Float, modifier: Modifier = Modifier, color: Color = usageColor(pct)) {
    val anim by animateFloatAsState(pct.coerceIn(0f, 100f) / 100f, tween(900, easing = EmphasizedDecel), label = "bar")
    val tint by animateColorAsState(color, tween(400), label = "barColor")
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Ops.raised)) {
        Box(Modifier.fillMaxWidth(anim).height(8.dp).clip(CircleShape).background(tint))
    }
}

fun usageColor(pct: Float) = when { pct >= 90 -> Ops.bad; pct >= 75 -> Ops.warn; else -> Ops.accent }

/** Label-left / value-right row. */
@Composable
fun KV(label: String, value: String, mono: Boolean = false, valueColor: Color = Ops.text, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ops.muted, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
        Text(
            value, color = valueColor, fontSize = 13.sp, fontFamily = if (mono) Mono else FontFamily.Default,
            modifier = Modifier.weight(0.58f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ops.line.copy(alpha = 0.6f)))

/** Coloured icon tile: a soft gradient of [tint] with a white glyph. */
@Composable
fun IconTile(icon: ImageVector, tint: Color = Ops.accent, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f))
            .background(Brush.linearGradient(listOf(tint, if (Ops.palette.dark) lerp(tint, Color.Black, 0.28f) else tint.copy(alpha = 0.72f).compositeOver(Color.White)))),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.54f)) }
}

/** Gradient circle avatar, colour derived from [key] so each device keeps its own. */
@Composable
fun Avatar(icon: ImageVector, key: String, size: Dp = 44.dp, dim: Boolean = false) {
    val palette = listOf(
        Color(0xFF2456F5) to Color(0xFF6B8CFF), Color(0xFF7A3DF0) to Color(0xFFB08BFF), Color(0xFF0E9F8E) to Color(0xFF46D3B8),
        Color(0xFFE0612B) to Color(0xFFFFA36B), Color(0xFFD1356B) to Color(0xFFFF7FA8), Color(0xFF1D8FD6) to Color(0xFF6CCBFF),
        Color(0xFF3F9E3B) to Color(0xFF8FD67A), Color(0xFF9A6A12) to Color(0xFFE6B84E),
    )
    val (a, b) = palette[kotlin.math.abs(key.hashCode()) % palette.size]
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(a, if (Ops.palette.dark) lerp(a, Color.Black, 0.28f) else b))).alpha(if (dim) 0.45f else 1f), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = Color.White)
    }
}

/** Settings-style card: rows separated by inset hairlines. */
@Composable
fun GroupCard(rows: List<@Composable () -> Unit>) {
    Column(Modifier.enterOnce().fillMaxWidth().clip(PanelShape).background(Ops.panel).border(1.dp, Ops.line.copy(alpha = 0.6f), PanelShape)) {
        rows.forEachIndexed { i, row ->
            Box(Modifier.padding(horizontal = 12.dp)) { row() }
            if (i < rows.lastIndex) Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(1.dp).background(Ops.line.copy(alpha = 0.6f)))
        }
    }
}

/** Standard list row: icon tile, title + subtitle, trailing slot. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    subtitleMono: Boolean = false,
    avatarKey: String? = null,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.pressClick(onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { if (avatarKey != null) Avatar(icon, avatarKey, 44.dp, dim) else IconTile(icon, iconTint.takeOrElse { Tints.pick(title) }); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ops.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(
                subtitle, color = Ops.muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@Composable
fun OpsSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) = Switch(
    checked = checked, onCheckedChange = onChange, enabled = enabled,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Ops.onAccent, checkedTrackColor = Ops.accent, checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = Ops.muted, uncheckedTrackColor = Ops.raised, uncheckedBorderColor = Ops.lineStrong,
    ),
)

/** Switch row for settings sheets. */
@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ops.text, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = Ops.muted, fontSize = 12.sp)
        }
        OpsSwitch(checked, onChange)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    mono: Boolean = false,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Ops.faint, fontFamily = if (mono) Mono else FontFamily.Default) } },
        textStyle = TextStyle(fontFamily = if (mono) Mono else FontFamily.Default, fontSize = 15.sp, color = Ops.text),
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        trailingIcon = if (password) {
            { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show", tint = Ops.muted) } }
        } else null,
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Ops.accent, unfocusedBorderColor = Ops.lineStrong,
            focusedContainerColor = Ops.raised, unfocusedContainerColor = Ops.raised,
            focusedLabelColor = Ops.accent, unfocusedLabelColor = Ops.muted, cursorColor = Ops.accent,
        ),
    )
}

/** Segmented single-choice chips, for small enums (proto, target, band...). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    // A single pill glides between options rather than each option fading its own background.
    BoxWithConstraints(modifier.fillMaxWidth().clip(CircleShape).background(Ops.raised).padding(4.dp)) {
        val gap = 3.dp
        val w = (maxWidth - gap * (options.size - 1)) / options.size.coerceAtLeast(1)
        val index = options.indexOfFirst { it.first == selected }
        val x by animateDpAsState((w + gap) * index.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "pill")
        if (index >= 0) Box(Modifier.offset(x = x).width(w).height(36.dp).clip(CircleShape).background(Ops.accent.copy(alpha = 0.2f)))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { (v, label) ->
                val on = v == selected
                val tint by animateColorAsState(if (on) Ops.accent else Ops.muted, tween(250), label = "segText")
                Box(
                    Modifier.weight(1f).height(36.dp).clip(CircleShape).clickable { onSelect(v) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = tint, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1) }
            }
        }
    }
}

/** Wrapping chip picker for longer option lists (zones, networks, channels). */
@Composable
fun <T> ChipPicker(options: List<Pair<T, String>>, isSelected: (T) -> Boolean, onToggle: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            val on = isSelected(v)
            val bg by animateColorAsState(if (on) Ops.accent.copy(alpha = 0.2f) else Ops.raised, tween(220), label = "chipBg")
            val fg by animateColorAsState(if (on) Ops.accent else Ops.muted, tween(220), label = "chipFg")
            Text(
                label,
                Modifier.pressClick({ onToggle(v) }, CircleShape, 0.94f)
                    .background(bg).padding(horizontal = 14.dp, vertical = 8.dp),
                color = fg, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun GhostButton(text: String, icon: ImageVector? = null, color: Color = Ops.accent, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(
        onClick, modifier, enabled = enabled, shape = CircleShape,
        border = null,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = color.copy(alpha = 0.14f), contentColor = color, disabledContainerColor = color.copy(alpha = 0.06f)),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Ops.accent, onClick: () -> Unit) =
    androidx.compose.material3.Button(
        onClick, modifier.height(52.dp), enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ops.onAccent, disabledContainerColor = Ops.raised, disabledContentColor = Ops.faint),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }

// ---------------------------------------------------------------- charts

/**
 * Live dual-series area chart (RX cyan / TX violet) with a faint grid, auto-scaled to the
 * series max. Values are bytes/s; the newest sample is on the right.
 */
@Composable
fun TrafficChart(rx: List<Float>, tx: List<Float>, modifier: Modifier = Modifier, capacity: Int = 60,
                 rxColor: Color = Ops.accent, txColor: Color = Ops.violet, gridColor: Color = Ops.line) {
    // Each new sample enters from the right edge and the graph glides left over the poll
    // interval, so the chart scrolls continuously instead of jumping every two seconds.
    val shift = remember { Animatable(0f) }
    LaunchedEffect(rx) {
        if (rx.size < 2) return@LaunchedEffect
        shift.snapTo(1f)
        shift.animateTo(0f, tween(1900, easing = LinearEasing))
    }
    val peak by animateFloatAsState((rx + tx).maxOrNull()?.takeIf { it > 0f } ?: 1f, tween(800, easing = FastOutSlowInEasing), label = "peak")
    Canvas(modifier.fillMaxWidth().height(120.dp).clipToBounds()) {
        val max = peak
        val step = size.width / (capacity - 1).coerceAtLeast(1)
        val slide = shift.value * step
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f, pathEffect = dash)
        }
        fun series(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val x0 = size.width - (values.size - 1) * step + slide
            val line = Path()
            values.forEachIndexed { i, v ->
                val x = x0 + i * step
                val y = size.height - (v / max) * size.height * 0.92f
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            val fill = Path().apply { addPath(line); lineTo(size.width + slide, size.height); lineTo(x0, size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        series(tx, txColor)
        series(rx, rxColor)
    }
}

/** Single-series sparkline for small tiles (CPU, memory). */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier, max: Float? = null) {
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        if (values.size < 2) return@Canvas
        val m = max ?: values.max().coerceAtLeast(0.0001f)
        val step = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val y = size.height - (v / m).coerceIn(0f, 1f) * size.height
            if (i == 0) line.moveTo(0f, y) else line.lineTo(i * step, y)
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(line, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Signal strength as 4 bars from dBm. */
@Composable
fun SignalBars(dbm: Int?, modifier: Modifier = Modifier) {
    val level = when { dbm == null -> 0; dbm >= -55 -> 4; dbm >= -65 -> 3; dbm >= -75 -> 2; else -> 1 }
    val color = when (level) { 4, 3 -> Ops.ok; 2 -> Ops.warn; else -> Ops.bad }
    Row(modifier.height(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) Box(Modifier.width(3.dp).height((3 + i * 2.75f).dp).clip(RoundedCornerShape(1.dp)).background(if (i <= level) color else Ops.lineStrong))
    }
}

/** No-op kept so call sites stay valid; the grid made hero cards look busy. */
fun Modifier.gridBackdrop(): Modifier = this
