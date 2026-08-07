package admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import data.AdminUser
import theme.CS30Theme
import theme.AppTheme

enum class AdminScreen { Login, Dashboard }

@Composable
fun AdminApp(initialAdmin: AdminUser? = null) {
    var screen by remember { mutableStateOf(if (initialAdmin != null) AdminScreen.Dashboard else AdminScreen.Login) }
    var admin by remember { mutableStateOf(initialAdmin) }
    var theme by remember { mutableStateOf(AppTheme.LIGHT) }

    CS30Theme(theme = theme) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                AdminScreen.Login -> AdminLoginScreen(
                    onLoginSuccess = { user ->
                        admin = user
                        screen = AdminScreen.Dashboard
                    }
                )
                AdminScreen.Dashboard -> AdminDashboard(
                    admin = admin!!,
                    onLogout = {
                        admin = null
                        screen = AdminScreen.Login
                    }
                )
            }
        }
    }
}
