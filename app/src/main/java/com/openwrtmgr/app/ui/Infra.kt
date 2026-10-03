package com.openwrtmgr.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.RouterException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Space tab screens keep free so the last item scrolls above the floating nav bar. */
val NavBarSpace = 24.dp

val LocalRouter = staticCompositionLocalOf<Router> { error("No router") }
val LocalUi = staticCompositionLocalOf<UiHost> { error("No UiHost") }

// ---------------------------------------------------------------- data loading

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

fun Throwable.friendly(): String = when (this) {
    is RouterException -> message ?: "Router error"
    else -> message ?: javaClass.simpleName
}

/** One fetch, survives recomposition/rotation, refreshable. Errors keep the last good data. */
class DataVM<T>(private val fetch: suspend () -> T) : ViewModel() {
    val state = MutableStateFlow<Load<T>>(Load.Loading)
    var refreshing by mutableStateOf(false); private set
    var lastError by mutableStateOf<String?>(null)

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        refreshing = true
        try {
            state.value = Load.Ok(fetch())
            lastError = null
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            if (state.value is Load.Ok) lastError = e.friendly() else state.value = Load.Err(e.friendly())
        }
        refreshing = false
    }
}

@Composable
fun <T> rememberData(key: String, fetch: suspend Router.() -> T): DataVM<T> {
    val router = LocalRouter.current
    return viewModel(key = "${router.profile.id}:$key") { DataVM { router.fetch() } }
}

// ---------------------------------------------------------------- global UI host: snackbar + busy overlay

class UiHost(val scope: CoroutineScope) {
    val snack = SnackbarHostState()
    var busy by mutableStateOf<String?>(null)

    fun toast(msg: String) { scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } }

    /**
     * Run a router action with a blocking "working" overlay; errors become a snackbar.
     * [done] runs on success (usually a refresh).
     */
    fun run(label: String, success: String? = null, done: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy != null) return
        scope.launch {
            busy = label
            try {
                block(); success?.let(::toast); done()
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { toast(e.friendly()) }
            busy = null
        }
    }
}

@Composable
fun rememberUiHost(): UiHost { val s = rememberCoroutineScope(); return remember { UiHost(s) } }

@Composable
fun UiHostLayer(ui: UiHost, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUi provides ui) {
        Box(Modifier.fillMaxSize().background(Ops.bg)) {
            content()
            SnackbarHost(ui.snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) { d ->
                Row(
                    Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Ops.text)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) { Text(d.visuals.message, color = Ops.bg, fontSize = 14.sp) }
            }
            AnimatedVisibility(ui.busy != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(250, delayMillis = 60))) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xB3000000))
                        .clickable(remember { MutableInteractionSource() }, null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.animateEnterExit(
                            enter = scaleIn(tween(320, easing = EmphasizedDecel), initialScale = 0.88f) + fadeIn(tween(200)),
                            exit = scaleOut(tween(200, easing = EmphasizedAccel), targetScale = 0.94f) + fadeOut(tween(160)),
                        ).clip(RoundedCornerShape(20.dp)).background(Ops.raised)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Ops.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(ui.busy.orEmpty(), color = Ops.text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- screen scaffolds

/** Top-level tab screen: large title, pull-to-refresh, lazy content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> TabScreen(
    title: String,
    data: DataVM<T>,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.(T) -> Unit,
) {
    val state by data.state.collectAsStateWithLifecycle()
    ErrorToast(data)
    PullToRefreshBox(data.refreshing && state is Load.Ok, onRefresh = { data.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = NavBarSpace),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(title, subtitle, actions) }
            when (val s = state) {
                is Load.Loading -> item { LoadingBlock() }
                is Load.Err -> item { ErrorBlock(s.message) { data.refresh() } }
                is Load.Ok -> content(s.data)
            }
        }
    }
}

@Composable
fun Header(title: String, subtitle: String?, actions: @Composable () -> Unit = {}, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Ops.text) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = Ops.text, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = Ops.muted, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Pushed detail screen: back arrow + same scrolling/refresh model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DetailScreen(
    title: String,
    data: DataVM<T>,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    content: LazyListScope.(T) -> Unit,
) {
    val state by data.state.collectAsStateWithLifecycle()
    ErrorToast(data)
    PullToRefreshBox(data.refreshing && state is Load.Ok, onRefresh = { data.refresh() }, modifier = Modifier.fillMaxSize().background(Ops.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(title, subtitle, actions, onBack) }
            when (val s = state) {
                is Load.Loading -> item { LoadingBlock() }
                is Load.Err -> item { ErrorBlock(s.message) { data.refresh() } }
                is Load.Ok -> content(s.data)
            }
        }
    }
}

/** Skeleton cards with a soft light sweeping across them while the first load is in flight. */
@Composable
fun LoadingBlock() {
    val sweep by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val shine = Ops.raised
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Box(
                Modifier.fillMaxWidth().height(if (it == 0) 140.dp else 84.dp).clip(RoundedCornerShape(24.dp)).background(Ops.panel)
                    .drawWithCache {
                        val band = size.width * 0.6f
                        val x = sweep * size.width
                        val brush = Brush.linearGradient(listOf(Color.Transparent, shine, Color.Transparent), Offset(x - band, 0f), Offset(x, size.height))
                        onDrawBehind { drawRect(brush) }
                    },
            )
        }
    }
}

@Composable
fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Ops.bad)
            Spacer(Modifier.width(10.dp))
            Text("Couldn't load", color = Ops.text, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(message, color = Ops.muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        GhostButton("Retry", onClick = onRetry)
    }
}

@Composable
fun EmptyNote(text: String) = Text(text, color = Ops.faint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))

fun LazyListScope.section(title: String, trailing: (@Composable () -> Unit)? = null) = item(key = "section:$title") {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Ops.text, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------- dialogs & sheets

/** Bottom-sheet editor with a pinned Save button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    title: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)?,
    saveLabel: String = "Save & apply",
    saveEnabled: Boolean = true,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ops.panel, contentColor = Ops.text, scrimColor = Color(0x99000000),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = Ops.bad) }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            if (onSave != null) {
                Spacer(Modifier.height(16.dp))
                PrimaryButton(saveLabel, Modifier.fillMaxWidth(), enabled = saveEnabled, onClick = onSave)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun Confirm(
    title: String,
    message: String,
    confirm: String,
    danger: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Ops.panel, titleContentColor = Ops.text, textContentColor = Ops.muted,
    title = { Text(title) }, text = { Text(message) },
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = if (danger) Ops.bad else Ops.accent, fontWeight = FontWeight.SemiBold) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ops.muted) } },
)

/** Note shown in edit sheets that change live network config. */
@Composable
fun RollbackNote() = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ops.accent.copy(alpha = 0.10f)).padding(14.dp),
) {
    Text(
        "Applied with automatic rollback: if this change cuts the app off, the router reverts it within ${Router.ROLLBACK_SECONDS}s.",
        color = Ops.muted, fontSize = 12.sp,
    )
}

// ---------------------------------------------------------------- formatting

fun bytes(b: Long?): String {
    if (b == null) return "—"
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$b B" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, u[i])
}

/** Bytes/s → "12.4 Mbps" style, which is how people think about links. */
fun rate(bytesPerSec: Float): Pair<String, String> {
    val bits = bytesPerSec * 8
    return when {
        bits >= 1e9 -> String.format(Locale.US, "%.2f", bits / 1e9) to "Gbps"
        bits >= 1e6 -> String.format(Locale.US, if (bits >= 1e8) "%.0f" else "%.1f", bits / 1e6) to "Mbps"
        bits >= 1e3 -> String.format(Locale.US, "%.0f", bits / 1e3) to "Kbps"
        else -> String.format(Locale.US, "%.0f", bits) to "bps"
    }
}

fun rateText(bytesPerSec: Float) = rate(bytesPerSec).let { "${it.first} ${it.second}" }

/** nlbwmon period start "2026-10-01" → "Oct 1, 2026" in the phone's locale. */
fun periodStart(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)

fun duration(sec: Long): String {
    val d = sec / 86400; val h = sec % 86400 / 3600; val m = sec % 3600 / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${sec}s"
    }
}

fun kbps(k: Long?): String = when {
    k == null -> "—"
    k >= 1000 -> String.format(Locale.US, "%.0f Mbps", k / 1000.0)
    else -> "$k Kbps"
}

/** Surfaces a refresh failure (when old data is still shown) as a snackbar, once. */
@Composable
fun ErrorToast(data: DataVM<*>) {
    val ui = LocalUi.current
    androidx.compose.runtime.LaunchedEffect(data.lastError) { data.lastError?.let { ui.toast(it); data.lastError = null } }
}
