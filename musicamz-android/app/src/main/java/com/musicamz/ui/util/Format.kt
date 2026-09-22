package com.musicamz.ui.util

fun formatDuration(durationMs: Long): String {
    val safe = durationMs.coerceAtLeast(0)
    val totalSeconds = safe / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
