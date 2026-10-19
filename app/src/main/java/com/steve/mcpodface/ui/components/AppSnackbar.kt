package com.steve.mcpodface.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app's single snackbar host, drawn by AppShell above the mini player and tab bar. Screens
 * show their messages here instead of hosting their own (a host at the bottom of a screen sits
 * behind the floating chrome).
 */
val LocalAppSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Shows [message], first dismissing whatever is on screen so messages never pile up in the queue.
 * Never indefinite: [duration] is Short or Long.
 */
suspend fun SnackbarHostState.showReplacing(
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult {
    currentSnackbarData?.dismiss()
    return showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = if (duration == SnackbarDuration.Indefinite) SnackbarDuration.Long else duration,
    )
}
