// author: kodeholic (powered by Claude)
// 0xLENS Compose 테마 — Material3 다크 테마 기반
package com.oxlens.demo.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val OxLensColorScheme = darkColorScheme(
    primary = BrandRust,
    secondary = BrandCyan,
    background = BrandDark,
    surface = BrandSurface,
    onPrimary = TextPrimary,
    onSecondary = BrandDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = StatusRed,
    onError = TextPrimary,
)

val MonoFamily = FontFamily.Monospace

val OxLensTypography = Typography(
    // Header 로고 텍스트
    titleLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        color = TextPrimary,
    ),
    // 뱃지, 버튼 라벨
    labelMedium = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
    // 상태 텍스트
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        color = TextMuted,
    ),
    // 타일 이름
    bodySmall = TextStyle(
        fontSize = 11.sp,
        color = TextSecondary,
    ),
)

@Composable
fun OxLensTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BrandSurface.toArgb()
            window.navigationBarColor = BrandDark.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = OxLensColorScheme,
        typography = OxLensTypography,
        content = content,
    )
}
