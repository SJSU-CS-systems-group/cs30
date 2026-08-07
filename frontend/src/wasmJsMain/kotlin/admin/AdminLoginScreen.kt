package admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.AdminUser
import kotlinx.browser.window

internal val AdminRed = Color(0xFFC62828)

@Composable
fun AdminLoginScreen(onLoginSuccess: (AdminUser) -> Unit) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Check for error in URL params
    LaunchedEffect(Unit) {
        val search = window.location.search.trimStart('?')
        if (search.isNotBlank()) {
            val params = search.split("&").mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()

            when (params["error"]) {
                "not_admin" -> errorMessage = "This account is not authorized as an admin."
                "auth_failed" -> errorMessage = "Authentication failed. Please try again."
                "no_code" -> errorMessage = "Login was cancelled."
            }

            // Clear URL params
            window.history.replaceState(null, "", window.location.pathname)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "CS30",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = AdminRed
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Admin",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(Modifier.height(32.dp))

                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorMessage!!,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                Button(
                    onClick = { window.location.href = "/admin/login" },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AdminRed
                    )
                ) {
                    Text("Login with Google")
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Sign in with your SJSU account",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
