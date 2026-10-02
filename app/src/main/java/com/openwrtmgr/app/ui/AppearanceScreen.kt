package com.openwrtmgr.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/** The user's theme choice ("system" or a palette id), persisted in plain prefs — it isn't secret. */
object ThemePref {
    var choice by mutableStateOf(Themes.SYSTEM)
        private set

    fun load(context: Context) {
        choice = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme2", Themes.SYSTEM) ?: Themes.SYSTEM
    }

    fun set(context: Context, id: String) {
        choice = id
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("theme2", id).apply()
    }
}

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().background(Ops.bg),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("Appearance", "Theme for the whole app", onBack = onBack) }
        item {
            ThemeCard("Follow system", "Night when your phone is dark, Daylight otherwise", ThemePref.choice == Themes.SYSTEM, listOf(Themes.night, Themes.daylight)) {
                ThemePref.set(ctx, Themes.SYSTEM)
            }
        }
        Themes.all.chunked(2).forEach { pair ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { p ->
                        Box(Modifier.weight(1f)) {
                            ThemeCard(p.label, if (p.dark) "Dark" else "Light", ThemePref.choice == p.id, listOf(p)) { ThemePref.set(ctx, p.id) }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A swatch card that previews the palette itself (not the current theme), so choices are honest. */
@Composable
private fun ThemeCard(title: String, subtitle: String, selected: Boolean, palettes: List<Palette>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ops.panel)
            .border(if (selected) 2.dp else 1.dp, if (selected) Ops.accent else Ops.line, shape)
            .clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            palettes.forEach { p -> Preview(p, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ops.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = Ops.muted, fontSize = 12.sp)
            }
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Ops.accent))
        }
    }
}

/** Miniature console: background, a panel, a metric bar in accent/violet and status dots. */
@Composable
private fun Preview(p: Palette, modifier: Modifier) {
    Column(modifier.height(84.dp).clip(RoundedCornerShape(10.dp)).background(p.bg).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(8.dp)) {
        Box(Modifier.width(36.dp).height(6.dp).clip(CircleShape).background(p.text))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(6.dp)).background(p.panel).border(1.dp, p.line, RoundedCornerShape(6.dp)).padding(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(p.ok, p.warn, p.bad).forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(it)) }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(CircleShape).background(p.accent))
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth(0.5f).height(4.dp).clip(CircleShape).background(p.violet))
        }
    }
}

