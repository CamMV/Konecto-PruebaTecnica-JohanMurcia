package com.konecto.series76.ui.components

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/** Max width of the reading column (conversation, debug) on wide windows. */
val ContentMaxWidth = 720.dp

/** True when the window is wider than tall (phone in landscape): screens switch to their two-column layout. */
@Composable
fun isLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width > size.height
}

/**
 * Keeps content out of the system bars, the display cutout and the keyboard on every side. In landscape the
 * navigation bar and the camera cutout sit on the left/right, which statusBars/navigationBars padding alone misses.
 */
fun Modifier.screenInsets(): Modifier = safeDrawingPadding()
