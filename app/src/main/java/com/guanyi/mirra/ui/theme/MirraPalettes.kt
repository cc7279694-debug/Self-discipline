package com.guanyi.mirra.ui.theme

import androidx.compose.ui.graphics.Color

object MirraPalettes {
    val blue = MirraColors(
        isDark = false,
        background = Color(0xFFF2F4F7), surface = Color(0xFFF7F8FA), surfaceRaised = Color(0xFFFAFBFC),
        surfacePressed = Color(0xFFE9EDF2), textPrimary = Color(0xFF181A1D), textSecondary = Color(0xFF626873),
        textTertiary = Color(0xFF686E78), accent = Color(0xFF6598E8), accentStrong = Color(0xFF386FBE),
        accentSoft = Color(0xFFDCE9FA), onAccent = Color.White, divider = Color(0xFFE1E5EA),
        positive = Color(0xFF2E6F4E), onPositive = Color.White, warning = Color(0xFF7A5500), onWarning = Color.White,
        danger = Color(0xFFB3261E), onDanger = Color.White, scrim = Color(0x66000000),
        mediaBackdrop = Color(0xFF08090A), onMediaBackdrop = Color.White,
    )

    val mono = blue.copy(
        background = Color(0xFFF3F3F1), surface = Color(0xFFF8F8F6), surfaceRaised = Color(0xFFFAFAF8),
        surfacePressed = Color(0xFFE7E7E4), textPrimary = Color(0xFF171717), textSecondary = Color(0xFF626262),
        textTertiary = Color(0xFF6B6B6B), accent = Color(0xFF292929), accentStrong = Color(0xFF151515),
        accentSoft = Color(0xFFE4E4E2), divider = Color(0xFFDEDEDB),
    )

    val night = blue.copy(
        isDark = true,
        background = Color(0xFF121416), surface = Color(0xFF191C20), surfaceRaised = Color(0xFF1E2226),
        surfacePressed = Color(0xFF242A30), textPrimary = Color(0xFFF2F3F4), textSecondary = Color(0xFFA6ABB2),
        textTertiary = Color(0xFF80868F), accent = Color(0xFF729DE2), accentStrong = Color(0xFF84ACEC),
        accentSoft = Color(0xFF26364E), onAccent = Color(0xFF121416), divider = Color(0xFF292D32),
        positive = Color(0xFF7FC9A0), onPositive = Color(0xFF121416), warning = Color(0xFFE8B75D),
        onWarning = Color(0xFF121416), danger = Color(0xFFFFB4AB), onDanger = Color(0xFF121416),
        scrim = Color(0x99000000), mediaBackdrop = Color.Black, onMediaBackdrop = Color.White,
    )
}
