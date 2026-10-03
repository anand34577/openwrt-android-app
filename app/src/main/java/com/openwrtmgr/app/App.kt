package com.openwrtmgr.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.openwrtmgr.app.data.Profiles
import com.openwrtmgr.app.data.Router
import com.openwrtmgr.app.data.Session
import com.openwrtmgr.app.ui.*
import kotlinx.serialization.Serializable

class OpenWrtApp : Application() {
    lateinit var profiles: Profiles; private set
    lateinit var session: Session; private set
    override fun onCreate() {
        super.onCreate()
        com.openwrtmgr.app.data.Ubus.debugLog = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        com.openwrtmgr.app.ui.ThemePref.load(this)
        profiles = Profiles(this)
        session = Session(profiles)
    }
}

@Serializable object Routers
@Serializable object Appearance
@Serializable data class Home(val id: String)
/** Every pushed screen inside a router: [page] picks the screen, [arg] is its parameter (a MAC, an ifname...). */
@Serializable data class Page(val id: String, val page: String, val arg: String = "")

/** Navigation from anywhere inside a router: open(page, arg). */
val LocalNav = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OpenWrtApp
        setContent {
            val palette = Themes.resolve(ThemePref.choice, androidx.compose.foundation.isSystemInDarkTheme())
            Ops.palette = palette
            LaunchedEffect(palette) {
                // Light themes need dark status-bar icons and vice versa.
                val bar = if (palette.dark) SystemBarStyle.dark(palette.bg.toArgb()) else SystemBarStyle.light(palette.bg.toArgb(), palette.text.toArgb())
                enableEdgeToEdge(bar, bar)
            }
            OpsTheme {
                val ui = rememberUiHost()
                UiHostLayer(ui) { AppNav(app) }
            }
        }
    }
}

@Composable
private fun AppNav(app: OpenWrtApp) {
    val nav = rememberNavController()
    // "Open at launch": jump into that router once per app start; Back still leads to the list.
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoOpened) { autoOpened = true; app.profiles.autoOpen?.let { nav.navigate(Home(it)) } }
    }
    // Shared-axis X: the new screen slides in a short way from the side it comes from while the
    // old one drifts the other way and fades, so depth reads clearly without a full-width swipe.
    NavHost(
        nav, startDestination = Routers, modifier = Modifier.fillMaxSize().background(Ops.bg),
        enterTransition = { slideInHorizontally(tween(420, easing = EmphasizedDecel)) { it / 5 } + fadeIn(tween(260, delayMillis = 60)) },
        exitTransition = { slideOutHorizontally(tween(420, easing = EmphasizedDecel)) { -it / 10 } + fadeOut(tween(160)) },
        popEnterTransition = { slideInHorizontally(tween(420, easing = EmphasizedDecel)) { -it / 10 } + fadeIn(tween(260, delayMillis = 60)) },
        popExitTransition = { slideOutHorizontally(tween(320, easing = EmphasizedAccel)) { it / 5 } + fadeOut(tween(200)) },
    ) {
        composable<Routers> {
            RoutersScreen(app.profiles, app.session, onOpen = { id -> nav.navigate(Home(id)) }, onAppearance = { nav.navigate(Appearance) })
        }
        composable<Appearance> { AppearanceScreen(onBack = { nav.popBackStack() }) }
        composable<Home> { entry ->
            val id = entry.toRoute<Home>().id
            RouterGate(app.session, id, onExit = { nav.popBackStack() }) {
                CompositionLocalProvider(LocalNav provides { page, arg -> nav.navigate(Page(id, page, arg)) }) {
                    HomeTabs(
                        onSwitchRouter = { app.session.disconnect(); nav.popBackStack() },
                        onSignOut = {
                            // Forget the saved password and auto-open: next visit asks to sign in.
                            app.profiles.get(id)?.let { app.profiles.save(it.copy(password = "")) }
                            if (app.profiles.autoOpen == id) app.profiles.autoOpen = null
                            app.session.disconnect(forget = true); nav.popBackStack()
                        },
                    )
                }
            }
        }
        composable<Page> { entry ->
            val p = entry.toRoute<Page>()
            val back: () -> Unit = { nav.popBackStack() }
            RouterGate(app.session, p.id, onExit = back) {
                CompositionLocalProvider(LocalNav provides { page, arg -> nav.navigate(Page(p.id, page, arg)) }) {
                    when (p.page) {
                        "device" -> DeviceScreen(p.arg, back)
                        "scan" -> ScanScreen(p.arg, back)
                        "firewall" -> FirewallScreen(back)
                        "dhcp" -> DhcpDnsScreen(back)
                        "routes" -> RoutesScreen(back)
                        "services" -> ServicesScreen(back)
                        "packages" -> PackagesScreen(back)
                        "logs" -> LogsScreen(back)
                        "processes" -> ProcessesScreen(back)
                        "maintenance" -> MaintenanceScreen(back)
                        "settings" -> SettingsScreen(back)
                        "diagnostics" -> DiagnosticsScreen(back)
                        "uci" -> UciScreen(p.arg, back)
                        "conns" -> ConnectionsScreen(p.arg, back)
                        "storage" -> StorageScreen(back)
                        "console" -> ConsoleScreen(back)
                        "ssh" -> SshScreen(back)
                        "rclocal" -> FileEditorScreen("Startup script", "/etc/rc.local", back)
                        "fwuser" -> FileEditorScreen("Custom firewall rules", "/etc/firewall.user", back, reload = "firewall")
                        "cron" -> CronScreen(back)
                        "leds" -> LedsScreen(back)
                        "addons" -> AddonsScreen(back)
                        "appearance" -> AppearanceScreen(back)
                        else -> back()
                    }
                }
            }
        }
    }
}

/** Connects (or reuses the live session) before showing anything router-scoped. */
@Composable
private fun RouterGate(session: Session, id: String, onExit: () -> Unit, content: @Composable () -> Unit) {
    var router by remember(id) { mutableStateOf<Router?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var askPassword by remember(id) { mutableStateOf(session.needsPassword(id)) }
    LaunchedEffect(id, attempt, askPassword) {
        if (askPassword) return@LaunchedEffect
        error = null
        runCatching { session.connect(id) }.onSuccess { router = it }.onFailure {
            error = it.friendly()
            if (session.needsPassword(id)) askPassword = true // typed password was wrong: ask again
        }
    }
    val phase = when { router != null -> 3; askPassword -> 1; error != null -> 2; else -> 0 }
    AnimatedContent(
        phase, label = "gate",
        transitionSpec = { (fadeIn(tween(300, delayMillis = 60)) + scaleIn(tween(360, delayMillis = 60, easing = EmphasizedDecel), initialScale = 0.96f)).togetherWith(fadeOut(tween(120))) },
    ) { p ->
    val r = router
    if (p == 3 && r != null) {
        CompositionLocalProvider(LocalRouter provides r, content = content)
        return@AnimatedContent
    }
    Box(Modifier.fillMaxSize().background(Ops.bg).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (p == 1) {
                val profile = (androidx.compose.ui.platform.LocalContext.current.applicationContext as OpenWrtApp).profiles.get(id)
                var pw by remember { mutableStateOf("") }
                var rememberPw by remember { mutableStateOf(false) }
                Caps("Sign in", color = Ops.accent)
                Text(profile?.name ?: "Router", color = Ops.text, fontSize = 22.sp)
                MonoText("${profile?.username}@${profile?.host}", color = Ops.muted, size = 12)
                error?.let { Text(it, color = Ops.bad, fontSize = 13.sp, textAlign = TextAlign.Center) }
                Field(pw, { pw = it }, "Password", password = true)
                SwitchRow("Remember password", rememberPw, "Stay signed in on this phone") { rememberPw = it }
                PrimaryButton("Connect", Modifier.fillMaxWidth(), enabled = pw.isNotEmpty()) {
                    session.providePassword(id, pw, rememberPw); error = null; askPassword = false
                }
                GhostButton("Back to routers", onClick = onExit)
            } else if (p == 0) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Ops.accent, strokeWidth = 2.dp)
                Caps("Connecting")
            } else {
                Caps("Connection failed", color = Ops.bad)
                Text(error.orEmpty(), color = Ops.muted, textAlign = TextAlign.Center, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                PrimaryButton("Retry", Modifier.fillMaxWidth()) { attempt++ }
                GhostButton("Back to routers", onClick = onExit)
            }
        }
    }
    }
}

/** Material fade-through, nudged toward the tab you tapped so the bar and the page agree. */
private fun tabTransition(from: Int, to: Int): ContentTransform {
    val dir = if (to > from) 1 else -1
    return (fadeIn(tween(240, delayMillis = 70)) + scaleIn(tween(300, delayMillis = 70, easing = EmphasizedDecel), initialScale = 0.97f) +
        slideInHorizontally(tween(300, delayMillis = 70, easing = EmphasizedDecel)) { dir * it / 14 })
        .togetherWith(fadeOut(tween(90)) + slideOutHorizontally(tween(160, easing = EmphasizedAccel)) { -dir * it / 24 })
}

private data class TabDef(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: androidx.compose.ui.graphics.Color)

private val tabs = listOf(
    TabDef("Overview", Icons.Rounded.Insights, Tints.blue),
    TabDef("Devices", Icons.Rounded.Devices, Tints.teal),
    TabDef("Wi-Fi", Icons.Rounded.Wifi, Tints.violet),
    TabDef("Network", Icons.Rounded.Hub, Tints.orange),
    TabDef("System", Icons.Rounded.Tune, Tints.pink),
)

@Composable
private fun HomeTabs(onSwitchRouter: () -> Unit, onSignOut: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Ops.bg)) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(tab, label = "tab", transitionSpec = { tabTransition(initialState, targetState) }) { t ->
                when (t) {
                    0 -> OverviewScreen(onSwitchRouter)
                    1 -> DevicesScreen()
                    2 -> WifiScreen()
                    3 -> NetworkScreen()
                    else -> SystemScreen(onSwitchRouter, onSignOut)
                }
            }
        }
        NavigationBar(containerColor = Ops.panel, tonalElevation = 0.dp) {
            tabs.forEachIndexed { i, t ->
                NavigationBarItem(
                    selected = tab == i, onClick = { tab = i },
                    icon = { Icon(t.icon, t.label) },
                    label = { Text(t.label, fontSize = 12.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = t.tint, selectedTextColor = t.tint, indicatorColor = t.tint.copy(alpha = 0.16f),
                        unselectedIconColor = Ops.muted, unselectedTextColor = Ops.muted,
                    ),
                )
            }
        }
    }
}
