package theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import cs30.frontend.generated.resources.RobotoMono_Regular
import cs30.frontend.generated.resources.Res
import org.jetbrains.compose.resources.Font

@Composable
actual fun getCodeFont(): FontFamily = FontFamily(Font(Res.font.RobotoMono_Regular))