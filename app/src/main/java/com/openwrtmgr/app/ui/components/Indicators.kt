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
import androidx.compose.ui.unit.dp
import com.openwrtmgr.app.ui.theme.LocalStatusColors

/**
 * A colored dot pairs with a text label everywhere in the app (Up/Down, Running/Stopped,
 * Active/Disabled) so status never depends on color alone, and the same dot+label pattern reads
 * consistently across every screen. Colors come from [LocalStatusColors] so dark mode gets real
 * contrast instead of a fixed hex value picked for a light background.
 */
@Composable
fun StatusDot(active: Boolean, label: String, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(8.dp)
                .background(if (active) colors.success else colors.neutral, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Same dot+label pattern for a third "warning" state (e.g. upgradable, degraded). */
@Composable
fun WarningDot(label: String, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.padding(end = 6.dp).size(8.dp).background(colors.warning, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
