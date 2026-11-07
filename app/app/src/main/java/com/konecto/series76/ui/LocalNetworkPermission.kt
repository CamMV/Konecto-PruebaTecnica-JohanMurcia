package com.konecto.series76.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Android 17 (API 37) "local network protection": the backend usually lives on the LAN or on the emulator host. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_SDK = 37

/** Asks once for local-network access when the OS requires it (no-op on Android 16 and older). */
@Composable
fun RequestLocalNetworkAccess() {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK) return
    val context = LocalContext.current
    // Saved across recreation so the dialog doesn't pop up again on every configuration change.
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!asked && ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            asked = true
            launcher.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
}
