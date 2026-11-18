package io.github.davidvornholt.glimpse.shared.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.davidvornholt.glimpse.R

/** Whether the app may use the camera, and how to ask. */
@Stable
class CameraPermissionState internal constructor(
    granted: Boolean,
) {
    var granted by mutableStateOf(granted)
        internal set

    /** Android won't show the dialog again, so asking means opening the app's settings. */
    var blocked by mutableStateOf(false)
        internal set

    internal var launch: () -> Unit = {}

    fun request() = launch()
}

private fun Context.hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Asks for the camera once on first use, and again whenever [CameraPermissionState.request] runs. */
@Composable
fun rememberCameraPermission(): CameraPermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember { CameraPermissionState(context.hasCamera()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state.granted = granted
            asked = true
            state.blocked = !granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
        }
    state.launch = {
        if (state.blocked) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    // Coming back from the settings, the answer may have changed.
    LifecycleResumeEffect(state) {
        state.granted = context.hasCamera()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        if (!state.granted && !asked) launcher.launch(Manifest.permission.CAMERA)
    }
    return state
}

/** What shows in place of the viewfinder until the app may use the camera. */
@Composable
fun CameraPermissionPrompt(
    state: CameraPermissionState,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Button(onClick = state::request) {
                Text(stringResource(if (state.blocked) R.string.camera_open_settings else R.string.camera_allow))
            }
        }
    }
}
