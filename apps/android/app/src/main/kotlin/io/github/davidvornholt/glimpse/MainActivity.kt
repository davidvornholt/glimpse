package io.github.davidvornholt.glimpse

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.davidvornholt.glimpse.features.pairing.PairingFlow
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.ui.theme.GlimpseTheme

class MainActivity : ComponentActivity() {
    private val pairing by viewModels<PairingFlow>()
    private var invalidLink by mutableStateOf(false)
    private var scanRequested by mutableStateOf(false)
    private var cameraRequests by mutableIntStateOf(0)
    private var darkBars: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        systemBars(dark = true)
        super.onCreate(savedInstanceState)
        val sessions = graph.sessions
        splash.setKeepOnScreenCondition { sessions.state.value is SessionState.Loading }
        if (savedInstanceState == null) handle(intent)
        setContent {
            GlimpseTheme {
                GlimpseRoot(
                    graph = graph,
                    requests = LaunchRequests(invalidLink, scanRequested, cameraRequests),
                    pairing = pairing,
                    onInvalidLinkShown = { invalidLink = false },
                    onScanHandled = { scanRequested = false },
                    onDarkSystemBars = ::systemBars,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val link = intent.dataString?.let { PairingLink.parse(it, allowCleartext = BuildConfig.DEBUG) }
                if (link != null) pairing.open(link) else invalidLink = true
            }

            ACTION_SCAN -> {
                scanRequested = true
            }

            ACTION_PHOTO -> {
                cameraRequests++
            }
        }
    }

    /** Light icons over the camera and the viewer; the theme's own elsewhere. */
    private fun systemBars(dark: Boolean) {
        if (darkBars == dark) return
        darkBars = dark
        val style =
            if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    companion object {
        const val ACTION_PHOTO = "io.github.davidvornholt.glimpse.action.PHOTO"
        const val ACTION_SCAN = "io.github.davidvornholt.glimpse.action.SCAN"
    }
}
