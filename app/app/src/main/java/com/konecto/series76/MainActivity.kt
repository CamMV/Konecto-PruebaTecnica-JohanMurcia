package com.konecto.series76

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.konecto.series76.ui.RequestLocalNetworkAccess
import com.konecto.series76.ui.RootViewModel
import com.konecto.series76.ui.navigation.KonectoNavHost
import com.konecto.series76.ui.navigation.Route
import com.konecto.series76.ui.theme.KonectoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val root: RootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // The system splash (K on purple) stays until we know whether there is a saved session.
        splash.setKeepOnScreenCondition { root.start.value == RootViewModel.Start.LOADING }
        setContent {
            KonectoTheme {
                RequestLocalNetworkAccess()
                val start by root.start.collectAsStateWithLifecycle()
                if (start != RootViewModel.Start.LOADING) {
                    KonectoNavHost(
                        startDestination = if (start == RootViewModel.Start.LOGGED_IN) Route.Chats else Route.Login,
                        forcedLogout = root.forcedLogout,
                    )
                }
            }
        }
    }
}
