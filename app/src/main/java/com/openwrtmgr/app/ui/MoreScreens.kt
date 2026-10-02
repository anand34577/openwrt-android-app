package com.openwrtmgr.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openwrtmgr.app.data.bool
import com.openwrtmgr.app.data.obj
import com.openwrtmgr.app.data.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hand text to any app (Messages, Drive, email...) via the Android share sheet. */
fun shareText(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, title))
}

@Composable
fun ShareButton(title: String, text: () -> String) {
    val ctx = LocalContext.current
    IconButton(onClick = { shareText(ctx, title, text()) }) { Icon(Icons.Rounded.Share, "Share", tint = Ops.muted) }
}

// ======================================================================== Connections

@Composable
fun ConnectionsScreen(ip: String, onBack: () -> Unit) {
    val data = rememberData("conns") { connections() }
    var q by remember { mutableStateOf(ip) }
    var proto by remember { mutableStateOf("all") }
    LaunchedEffect(data) { while (true) { delay(5000); data.refresh() } }
    DetailScreen("Connections", data, onBack, subtitle = "conntrack · bytes since each connection began") { list ->
        val shown = list.filter { c ->
            (proto == "all" || c.proto == proto) && (q.isBlank() || listOf(c.src, c.dst, c.dport, c.sport).any { it.contains(q.trim()) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f), padding = PaddingValues(14.dp)) { Metric("Tracked", "${list.size}") }
                Panel(Modifier.weight(1f), padding = PaddingValues(14.dp)) { Metric("Shown", "${shown.size}", sub = bytes(shown.sumOf { it.bytes })) }
            }
            Spacer(Modifier.height(8.dp))
            Field(q, { q = it }, "Filter by IP or port", mono = true)
            Spacer(Modifier.height(8.dp))
            Segmented(listOf("all" to "All", "tcp" to "TCP", "udp" to "UDP", "icmp" to "ICMP"), proto, { proto = it })
        }
        // Top talkers: which local hosts move the most bytes right now.
        item {
            val top = shown.groupBy { it.src }.mapValues { (_, v) -> v.sumOf { it.bytes } }.entries.sortedByDescending { it.value }.take(5)
            if (top.isNotEmpty()) Panel(title = "Top sources") {
                val max = top.first().value.coerceAtLeast(1)
                top.forEach { (src, b) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row { MonoText(src, Modifier.weight(1f), size = 12); MonoText(bytes(b), color = Ops.muted, size = 12) }
                        Spacer(Modifier.height(4.dp)); UsageBar(b * 100f / max, color = Ops.accent)
                    }
                }
            }
        }
        items(shown.take(300)) { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(c.proto, if (c.proto == "tcp") Ops.accent else if (c.proto == "udp") Ops.violet else Ops.muted)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MonoText("${c.src}${if (c.sport.isNotEmpty()) ":${c.sport}" else ""}", size = 12)
                    MonoText("→ ${c.dst}${if (c.dport.isNotEmpty()) ":${c.dport}" else ""}", color = Ops.muted, size = 12)
                }
                MonoText(bytes(c.bytes), color = Ops.faint, size = 11)
            }
        }
        if (shown.isEmpty()) item { EmptyNote("No matching connections.") }
    }
}

// ======================================================================== Storage

@Composable
fun StorageScreen(onBack: () -> Unit) {
    val data = rememberData("mounts") { mounts() }
    DetailScreen("Storage", data, onBack) { list ->
        items(list, key = { it.mount }) { m ->
            Panel {
                Row { MonoText(m.mount, Modifier.weight(1f), size = 14, weight = FontWeight.Medium); MonoText(m.device, color = Ops.faint, size = 11) }
                Spacer(Modifier.height(8.dp))
                val used = m.size - m.free
                // squashfs /rom is always "full" by design: not a warning
                if (m.mount == "/rom") UsageBar(100f, color = Ops.faint) else UsageBar(used * 100f / m.size)
                Spacer(Modifier.height(6.dp))
                MonoText("${bytes(used)} used · ${bytes(m.free)} free of ${bytes(m.size)}", color = Ops.muted, size = 12)
            }
        }
    }
}

// ======================================================================== API console

@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val data = rememberData("objects") { ubusObjects() }
    val r = LocalRouter.current
    val scope = rememberCoroutineScope()
    var obj by remember { mutableStateOf("system") }
    var method by remember { mutableStateOf("board") }
    var argsText by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    DetailScreen("API console", data, onBack, subtitle = "ubus call <object> <method> <args>", actions = { ShareButton("ubus $obj $method") { out } }) { objects ->
        item {
            Panel {
                Field(obj, { obj = it.trim() }, "Object", mono = true)
                Spacer(Modifier.height(8.dp))
                Field(method, { method = it.trim() }, "Method", mono = true)
                Spacer(Modifier.height(8.dp))
                Field(argsText, { argsText = it }, "Arguments (JSON)", mono = true, singleLine = false, placeholder = "{\"config\":\"network\"}")
                Spacer(Modifier.height(12.dp))
                GhostButton("Call", Icons.Rounded.PlayArrow, enabled = !running && obj.isNotBlank() && method.isNotBlank()) {
                    running = true
                    scope.launch {
                        out = try { r.rawCall(obj, method, argsText) } catch (e: Throwable) { "Error: ${e.friendly()}" }
                        running = false
                    }
                }
            }
        }
        item {
            Panel(title = if (running) "Calling…" else "Reply") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MonoText(out.ifBlank { "—" }, color = if (out.startsWith("Error")) Ops.bad else Ops.text, size = 11, maxLines = 2000)
                }
            }
        }
        item {
            Panel(title = "Objects visible to this session (${objects.size})") {
                ChipPicker(objects.map { it to it }, { it == obj }, { obj = it })
            }
        }
    }
}

// ======================================================================== SSH

private data class SshState(val section: String?, val port: String, val passwordAuth: Boolean, val rootPasswordAuth: Boolean, val iface: String, val keys: String?)

@Composable
fun SshScreen(onBack: () -> Unit) {
    val data = rememberData("ssh") {
        val d = uci("dropbear").entries.firstOrNull { it.value.obj()[".type"].str() == "dropbear" }
        val o = d?.value?.obj()
        SshState(
            d?.key, o?.get("Port").str() ?: "22", o?.get("PasswordAuth").bool() ?: true, o?.get("RootPasswordAuth").bool() ?: true,
            o?.get("Interface").str().orEmpty(), runCatching { readFile("/etc/dropbear/authorized_keys") }.getOrNull(),
        )
    }
    val r = LocalRouter.current
    val ui = LocalUi.current
    DetailScreen("SSH access", data, onBack, subtitle = "dropbear") { s ->
        item {
            var port by remember(s) { mutableStateOf(s.port) }
            var pw by remember(s) { mutableStateOf(s.passwordAuth) }
            var rootPw by remember(s) { mutableStateOf(s.rootPasswordAuth) }
            var iface by remember(s) { mutableStateOf(s.iface) }
            Panel(title = "Server") {
                if (s.section == null) { EmptyNote("Dropbear isn't configured on this router."); return@Panel }
                Field(port, { port = it.filter(Char::isDigit) }, "Port", mono = true, keyboard = KeyboardType.Number)
                Spacer(Modifier.height(8.dp))
                Field(iface, { iface = it.trim() }, "Listen on interface", mono = true, supporting = "e.g. lan. Empty = all interfaces (including WAN if the firewall allows)")
                SwitchRow("Password login", pw) { pw = it }
                SwitchRow("Root password login", rootPw, "Turn off once you've added a key") { rootPw = it }
                Spacer(Modifier.height(8.dp))
                GhostButton("Save", Icons.Rounded.Save, enabled = port.toIntOrNull() in 1..65535) {
                    ui.run("Applying SSH settings…", "SSH settings saved", data::refresh) {
                        r.saveUci("dropbear", s.section, "dropbear", mapOf(
                            "Port" to port, "PasswordAuth" to if (pw) "on" else "off", "RootPasswordAuth" to if (rootPw) "on" else "off", "Interface" to iface.ifBlank { null },
                        ))
                    }
                }
            }
        }
        item {
            var keys by remember(s) { mutableStateOf(s.keys.orEmpty()) }
            Panel(title = "Authorized keys") {
                if (s.keys == null) { Text("This router's ACL doesn't allow reading /etc/dropbear/authorized_keys.", color = Ops.muted, fontSize = 13.sp); return@Panel }
                Text("One public key per line (ssh-ed25519 AAAA… user@host).", color = Ops.muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Field(keys, { keys = it }, "authorized_keys", mono = true, singleLine = false, modifier = Modifier.heightIn(min = 120.dp))
                Spacer(Modifier.height(8.dp))
                GhostButton("Save keys", Icons.Rounded.Save) {
                    ui.run("Saving keys…", "Keys saved", data::refresh) {
                        r.writeFile("/etc/dropbear/authorized_keys", keys.trim().lines().filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"), 384)
                    }
                }
            }
        }
    }
}

// ======================================================================== small file editor

@Composable
fun FileEditorScreen(title: String, path: String, onBack: () -> Unit, reload: String? = null) {
    val data = rememberData("file:$path") { readFile(path) }
    val r = LocalRouter.current
    val ui = LocalUi.current
    DetailScreen(title, data, onBack, subtitle = path) { text ->
        item {
            var t by remember(text) { mutableStateOf(text) }
            Panel {
                Field(t, { t = it }, path.substringAfterLast('/'), mono = true, singleLine = false, modifier = Modifier.heightIn(min = 240.dp))
                Spacer(Modifier.height(10.dp))
                if (path.endsWith("rc.local")) Text("Keep `exit 0` as the last line of rc.local.", color = Ops.faint, fontSize = 12.sp)
                else if (reload != null) Text("Saving reloads the $reload service.", color = Ops.faint, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                GhostButton("Save", Icons.Rounded.Save) { ui.run("Saving…", "Saved", data::refresh) { r.writeFile(path, t.trimEnd() + "\n", 493); reload?.let { r.service(it, "reload") } } } // 0755
            }
        }
    }
}
