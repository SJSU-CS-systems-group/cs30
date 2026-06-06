package editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import theme.AppTheme

@Composable
fun TogglePanelButton(isOpen: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = if (isOpen) "Collapse problem panel" else "Expand problem panel",
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
fun SettingsDropdown(currentTheme: AppTheme, onThemeChange: (AppTheme) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Theme settings",
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            if (theme == currentTheme) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(theme.displayName)
                        }
                    },
                    onClick = { onThemeChange(theme); expanded = false }
                )
            }
        }
    }
}

@Composable
fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Close output panel",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
