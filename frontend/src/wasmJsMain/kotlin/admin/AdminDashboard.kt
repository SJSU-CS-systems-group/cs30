package admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import auth.ApiToken
import auth.syncApiTokenToWindow
import backend.getCurrentAuthHeader
import data.AdminCliTokenInfo
import data.AdminUser
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lockdown.defaultReporterBaseUrl

internal fun confirmAction(message: String): Boolean = js("window.confirm(message)")

@Composable
fun AdminDashboard(admin: AdminUser, onLogout: () -> Unit) {
    val service = remember { HttpAdminBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() } }
    var tokens by remember { mutableStateOf<List<AdminCliTokenInfo>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }

    val clearAndLogout: () -> Unit = {
        ApiToken.value = null
        syncApiTokenToWindow(null)
        onLogout()
    }

    val refreshTokens: suspend () -> Unit = {
        try {
            tokens = service.listCliTokens()
            loadFailed = false
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    LaunchedEffect(Unit) { refreshTokens() }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxWidth(), color = AdminRed, tonalElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Admin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(admin.email, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                }
                OutlinedButton(
                    onClick = {
                        CoroutineScope(Dispatchers.Default).launch {
                            service.logout()
                            clearAndLogout()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Logout")
                }
            }
        }

        Column(modifier = Modifier.padding(24.dp)) {
            AdminCliTokenSection(rawToken = admin.token)

            Spacer(Modifier.height(24.dp))

            Text("CLI Tokens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (loadFailed) {
                Text(
                    "Failed to load tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (tokens.isEmpty()) {
                Text(
                    "No tokens yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Email", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text("Role", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(48.dp))
                            }
                            HorizontalDivider()
                        }
                        items(tokens, key = { it.id }) { info ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(info.email, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                                Text(info.role, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconButton(
                                    onClick = {
                                        if (confirmAction("Delete this token for ${info.email} (${info.role})? This can't be undone.")) {
                                            CoroutineScope(Dispatchers.Default).launch {
                                                if (service.deleteCliToken(info.id)) refreshTokens()
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminCliTokenSection(rawToken: String?) {
    if (rawToken != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "You can only see this token once - save it now. If you lose it, you'll have to reset it.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SelectionContainer {
                Text(
                    text = rawToken,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Select the token above to copy it, it is used for CLI commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your CLI admin token was already generated on a previous login and can't be shown again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                OutlinedButton(
                    onClick = {
                        if (confirmAction("This will invalidate the current admin token - anything still using it will stop working. Continue?")) {
                            window.location.href = "/admin/login?reset=true"
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AdminRed)
                ) {
                    Text("Reset Token")
                }
            }
        }
    }
}