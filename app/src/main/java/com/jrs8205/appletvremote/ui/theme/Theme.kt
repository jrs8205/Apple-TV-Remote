package com.jrs8205.appletvremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colours of the physical remote: pale aluminium body, near-black click pad and buttons. */
object RemoteColors {
    val AluminiumLight = Color(0xFFE3E4E6)
    val AluminiumDark = Color(0xFF2C2C2E)
    val PadLight = Color(0xFF1C1C1E)
    val PadDark = Color(0xFF0E0E10)
    val ButtonLight = Color(0xFF2C2C2E)
    val ButtonDark = Color(0xFF3A3A3C)
    val OnButton = Color(0xFFF2F2F7)
    val Accent = Color(0xFF0A84FF)
}

/** Follows the system light/dark setting and Android's dynamic colours when available. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dark -> runCatching { dynamicDarkColorScheme(context) }.getOrDefault(darkColorScheme(primary = RemoteColors.Accent))
        else -> runCatching { dynamicLightColorScheme(context) }.getOrDefault(lightColorScheme(primary = RemoteColors.Accent))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
