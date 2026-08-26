package com.openwrtmgr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Section 33 — "Use color-independent status indicators." A colored dot pairs with a text label
 * everywhere in the app (Up/Down, Running/Stopped, Active/Disabled) so status never depends on
 * color alone, and the same dot+label pattern reads consistently across every screen.
 */
@Composable
fun StatusDot(active: Boolean, label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(8.dp)
                .background(if (active) StatusColors.on else StatusColors.off, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

object StatusColors {
    val on = Color(0xFF2E7D32)
    val off = Color(0xFF9E9E9E)
    val warning = Color(0xFFEF6C00)
    val error = Color(0xFFC62828)
}
