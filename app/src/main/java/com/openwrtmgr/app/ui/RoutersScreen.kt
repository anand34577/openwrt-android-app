package com.openwrtmgr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openwrtmgr.app.data.Profiles
import com.openwrtmgr.app.data.RouterProfile
import com.openwrtmgr.app.data.Session
import com.openwrtmgr.app.data.gatewayAddress
import java.text.DateFormat
import java.util.Date

@Composable
fun RoutersScreen(profiles: Profiles, session: Session, onOpen: (String) -> Unit, onAppearance: () -> Unit = {}) {
    val list by profiles.all.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RouterProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Ops.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Router, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("OpenWrt", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ops.text)
                        Text("Manager", color = Ops.muted, fontSize = 13.sp)
                    }
                    IconButton(onClick = onAppearance) { Icon(Icons.Rounded.Palette, "Appearance", tint = Ops.muted) }
                }
            }
            item {
                Text("Your routers,\nin your pocket.", fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Ops.text, letterSpacing = (-0.8).sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }
            if (list.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(Icons.Rounded.Router, size = 88.dp)
                    Spacer(Modifier.height(24.dp))
                    Text("No routers yet", color = Ops.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Add your OpenWrt router with its LuCI login to monitor and manage it from here.", color = Ops.muted, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            items(list, key = { it.id }) { p ->
                Panel(onClick = { onOpen(p.id) }, padding = PaddingValues(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Router, size = 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Ops.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${p.username}@${p.baseUrl.substringAfter("://")}", color = Ops.muted, fontSize = 13.sp, maxLines = 1)
                            if (p.lastUsed > 0) Text("Last used ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.lastUsed))}", color = Ops.faint, fontSize = 12.sp)
                        }
                        if (p.https) Tag("HTTPS", Ops.ok)
                        IconButton(onClick = { editing = p }) { Icon(Icons.Rounded.Edit, "Edit", tint = Ops.muted) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            containerColor = Ops.accent, contentColor = Ops.onAccent, shape = RoundedCornerShape(20.dp),
            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add router", fontWeight = FontWeight.SemiBold) },
        )
    }

    if (adding || editing != null) {
        RouterForm(
            initial = editing ?: RouterProfile(name = "", host = gatewayAddress(context) ?: "192.168.1.1"),
            isNew = editing == null,
            session = session,
            onDismiss = { adding = false; editing = null },
            autoOpen = editing?.let { profiles.autoOpen == it.id } ?: false,
            onSave = { p, password, rememberPw, auto ->
                // Connection details changed: drop any live session so the next open re-authenticates.
                session.disconnect(forget = true)
                profiles.save(p.copy(password = if (rememberPw) password else ""))
                session.providePassword(p.id, password, rememberPw)
                if (auto) profiles.autoOpen = p.id else if (profiles.autoOpen == p.id) profiles.autoOpen = null
                adding = false; editing = null
                onOpen(p.id)
            },
            onDelete = editing?.let { e -> { session.disconnect(forget = true); if (profiles.autoOpen == e.id) profiles.autoOpen = null; profiles.delete(e.id); editing = null } },
        )
    }
}

@Composable
private fun RouterForm(
    initial: RouterProfile, isNew: Boolean, session: Session, autoOpen: Boolean,
    onDismiss: () -> Unit, onSave: (RouterProfile, String, Boolean, Boolean) -> Unit, onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var https by remember { mutableStateOf(initial.https) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    var rememberPw by remember { mutableStateOf(isNew || initial.password.isNotEmpty()) }
    var auto by remember { mutableStateOf(autoOpen) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    val ui = LocalUi.current

    fun build(): RouterProfile {
        // Saving is the explicit "trust this router" step: the HTTPS cert is re-pinned on next connect.
        return initial.copy(
            name = name.trim().ifBlank { host.trim() }, host = host.trim(), https = https,
            port = port.toIntOrNull() ?: if (https) 443 else 80,
            username = user.trim().ifBlank { "root" }, password = pass, certSha256 = null,
        )
    }

    EditSheet(
        title = if (isNew) "Add router" else "Edit router",
        onDismiss = onDismiss,
        saveLabel = "Test & save",
        saveEnabled = host.isNotBlank() && pass.isNotEmpty(),
        onDelete = onDelete?.let { { askDelete = true } },
        onSave = {
            val p = build()
            ui.run("Connecting to ${p.host}…") {
                val board = session.test(p)
                status = true to "${board.model} · ${board.release}"
                onSave(p.copy(name = name.trim().ifBlank { board.hostname }), pass, rememberPw, auto)
            }
        },
    ) {
        Field(host, { host = it; status = null }, "Address", placeholder = "192.168.1.1", mono = true, keyboard = KeyboardType.Uri,
            supporting = "IP or hostname of LuCI. Detected gateway is pre-filled.")
        Segmented(listOf(false to "HTTP", true to "HTTPS"), https, { https = it; port = if (it) "443" else "80" })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(port, { port = it.filter(Char::isDigit) }, "Port", Modifier.weight(0.35f), mono = true, keyboard = KeyboardType.Number)
            Field(user, { user = it }, "Username", Modifier.weight(0.65f), mono = true)
        }
        Field(pass, { pass = it }, "Password", password = true,
            supporting = if (!isNew && initial.password.isEmpty()) "Not saved on this phone. Enter it to test the connection." else null)
        Field(name, { name = it }, "Name (optional)", placeholder = "Uses the router's hostname")
        SwitchRow("Remember password", rememberPw, "Stored encrypted with Android Keystore. Off = asked every time you connect.") { rememberPw = it }
        SwitchRow("Open at launch", auto, "Skip this list and connect to this router when the app starts") { auto = it }
        status?.let { (ok, msg) -> Text(msg, color = if (ok) Ops.ok else Ops.bad, fontSize = 13.sp) }
    }

    if (askDelete) Confirm("Remove router?", "Removes ${initial.name} and its saved password from this phone. Nothing changes on the router.", "Remove", onDismiss = { askDelete = false }) { onDelete?.invoke() }
}
