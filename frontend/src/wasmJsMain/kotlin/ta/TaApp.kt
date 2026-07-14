package ta

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import data.TaUser
import theme.CS30Theme
import theme.AppTheme

enum class TaScreen { Login, Dashboard }

@Composable
fun TaApp(initialTa: TaUser? = null) {
    var screen by remember { mutableStateOf(if (initialTa != null) TaScreen.Dashboard else TaScreen.Login) }
    var ta by remember { mutableStateOf(initialTa) }
    var theme by remember { mutableStateOf(AppTheme.LIGHT) }

    CS30Theme(theme = theme) {
        Box(Modifier.fillMaxSize()) {
            when (screen) {
                TaScreen.Login -> TaLoginScreen(
                    onLoginSuccess = { user ->
                        ta = user
                        screen = TaScreen.Dashboard
                    }
                )
                TaScreen.Dashboard -> TaDashboard(
                    ta = ta!!,
                    onLogout = {
                        ta = null
                        screen = TaScreen.Login
                    }
                )
            }
        }
    }
}
