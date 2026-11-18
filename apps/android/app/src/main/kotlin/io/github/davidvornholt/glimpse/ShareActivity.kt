package io.github.davidvornholt.glimpse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davidvornholt.glimpse.features.share.ShareSheet
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.ui.theme.GlimpseTheme

/** The share target: a sheet over the app the images come from. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val images = sharedImages(intent)
        if (images.isEmpty()) {
            finish()
            return
        }
        val graph = graph
        setContent {
            GlimpseTheme {
                val session by graph.sessions.state.collectAsStateWithLifecycle()
                if (session !is SessionState.Loading) {
                    ShareSheet(
                        images = images,
                        paired = session is SessionState.Paired,
                        haptics = graph.haptics,
                        send = { uris, note -> graph.library.addImages(uris, PhotoSource.Share, note) },
                        onOpenApp = {
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                        onFinish = ::finish,
                    )
                }
            }
        }
    }

    private fun sharedImages(intent: Intent): List<Uri> {
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                }

                else -> {
                    emptyList()
                }
            }
        val clipped = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        return (uris.ifEmpty { clipped }).distinct().take(MAX_IMAGES)
    }

    private companion object {
        const val MAX_IMAGES = 20
    }
}
