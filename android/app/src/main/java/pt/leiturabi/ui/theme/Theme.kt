package pt.leiturabi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Brand = Color(0xFF4F8CFF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2447),
    secondary = Color(0xFF4F6178),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color(0xFF06214A),
    primaryContainer = Color(0xFF2A4A7F),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFB7C8DE),
    background = Color(0xFF11151C),
    surface = Color(0xFF171B23),
    surfaceVariant = Color(0xFF262B35),
    onSurfaceVariant = Color(0xFFC4C7CF),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Typography().labelSmall.copy(fontSize = 11.sp),
)

@Composable
fun LeituraBiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // O modo edge-to-edge e ativado na MainActivity; as barras de sistema seguem o tema.
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
