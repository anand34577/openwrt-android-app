package com.openwrtmgr.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.openwrtmgr.app.data.Board

@Composable
fun SystemScreen(onSwitchRouter: () -> Unit, onSignOut: () -> Unit) {
    val data = rememberData("board") { board() }
    val nav = LocalNav()
    val r = LocalRouter.current
    val ui = LocalUi.current
    var reboot by remember { mutableStateOf(false) }

    TabScreen<Board>("System", data, subtitle = r.profile.baseUrl.substringAfter("://")) { b ->
        item {
            Panel(title = "Router") {
                KV("Hostname", b.hostname)
                KV("Model", b.model)
                KV("Firmware", b.release)
                KV("Kernel", b.kernel)
            }
        }
        section("Configure")
        item {
            GroupCard(listOf(
                { ListRow("General settings", "Hostname, time zone, clock, password, scheduled reboot", Icons.Rounded.Settings, Tints.blue, onClick = { nav("settings", "") }) },
                { ListRow("Services", "Start, stop and autostart init scripts", Icons.Rounded.Apps, Tints.violet, onClick = { nav("services", "") }) },
                { ListRow("Packages", "Install, update and remove software (apk)", Icons.Rounded.Inventory2, Tints.orange, onClick = { nav("packages", "") }) },
                { ListRow("Backup & firmware", "Backup, restore, flash firmware, reset", Icons.Rounded.Backup, Tints.teal, onClick = { nav("maintenance", "") }) },
                { ListRow("SSH access", "Dropbear settings and authorized keys", Icons.Rounded.Key, Tints.amber, onClick = { nav("ssh", "") }) },
                { ListRow("Scheduled tasks", "Cron jobs: reboots, Wi-Fi reloads, your own scripts", Icons.Rounded.Schedule, Tints.cyan, onClick = { nav("cron", "") }) },
                { ListRow("LEDs", "What each light on the router shows", Icons.Rounded.Lightbulb, Tints.amber, onClick = { nav("leds", "") }) },
                { ListRow("Add-ons", "DDNS, SQM, UPnP, VPN, ad blocking and more", Icons.Rounded.Extension, Tints.pink, onClick = { nav("addons", "") }) },
                { ListRow("Startup script", "/etc/rc.local, runs at every boot", Icons.Rounded.PlayCircle, Tints.green, onClick = { nav("rclocal", "") }) },
            ))
        }
        section("Inspect")
        item {
            GroupCard(listOf(
                { ListRow("System log", "logread and kernel log", Icons.Rounded.ReceiptLong, Tints.slate, onClick = { nav("logs", "") }) },
                { ListRow("Processes", "What's running, CPU and memory", Icons.Rounded.Memory, Tints.red, onClick = { nav("processes", "") }) },
                { ListRow("Connections", "Live connection tracking: who talks to what", Icons.Rounded.SwapCalls, Tints.cyan, onClick = { nav("conns", "") }) },
                { ListRow("Storage", "Mount points and free space", Icons.Rounded.Storage, Tints.teal, onClick = { nav("storage", "") }) },
                { ListRow("Diagnostics", "Ping, traceroute and DNS lookup from the router", Icons.Rounded.NetworkCheck, Tints.green, onClick = { nav("diagnostics", "") }) },
                { ListRow("UCI editor", "Every config file, raw", Icons.Rounded.Code, Tints.violet, onClick = { nav("uci", "") }) },
                { ListRow("API console", "Call any ubus method directly", Icons.Rounded.Terminal, Tints.pink, onClick = { nav("console", "") }) },
            ))
        }
        section("Session")
        item {
            GroupCard(listOf(
                { ListRow("Reboot router", null, Icons.Rounded.PowerSettingsNew, Tints.orange, onClick = { reboot = true }) },
                { ListRow("Appearance", "Night, Daylight, AMOLED, Nord, Dracula and more", Icons.Rounded.Palette, Tints.pink, onClick = { nav("appearance", "") }) },
                { ListRow("Switch router", r.profile.name, Icons.Rounded.SwapHoriz, Tints.blue, onClick = onSwitchRouter) },
                { ListRow("Sign out", "Forget the saved password on this phone", Icons.AutoMirrored.Rounded.Logout, Tints.red, onClick = onSignOut) },
            ))
        }
    }
    if (reboot) Confirm("Reboot router?", "Every device loses connectivity for a minute or two.", "Reboot", onDismiss = { reboot = false }) {
        ui.run("Rebooting…", "Reboot started. Back in 1–2 minutes.") { r.reboot() }
    }
}
