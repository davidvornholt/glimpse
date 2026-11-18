package io.github.davidvornholt.glimpse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.davidvornholt.glimpse.shared.ui.theme.GlimpseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { GlimpseTheme { Text("Glimpse") } }
    }

    companion object {
        const val ACTION_PHOTO = "io.github.davidvornholt.glimpse.action.PHOTO"
        const val ACTION_SCAN = "io.github.davidvornholt.glimpse.action.SCAN"
    }
}
