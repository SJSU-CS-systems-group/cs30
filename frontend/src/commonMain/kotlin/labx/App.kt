package labx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import labx.data.Student
import labx.editor.CodeEditorScreen
import labx.login.LoginScreen
import labx.theme.CS30Theme

enum class Screen { Login, Editor }

@Composable
fun App(initialStudent: Student? = null) {
    var student by remember { mutableStateOf(initialStudent) }
    var screen by remember { mutableStateOf(if (initialStudent != null) Screen.Editor else Screen.Login) }

    CS30Theme {
        when (screen) {
            Screen.Login -> LoginScreen(
                onLoginSuccess = { s ->
                    student = s
                    screen = Screen.Editor
                }
            )
            Screen.Editor -> CodeEditorScreen(
                student = student!!,
                onLogout = {
                    student = null
                    screen = Screen.Login
                }
            )
        }
    }
}
