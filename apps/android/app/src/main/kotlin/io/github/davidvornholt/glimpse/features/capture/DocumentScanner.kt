package io.github.davidvornholt.glimpse.features.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opens Google's document scanner, which finds the page edges, flattens the
 * page and cleans it up. Gives the scanned pages, in order.
 */
@Composable
fun rememberDocumentScanner(
    newId: () -> String,
    recover: suspend (String?) -> Unit,
    reserve: suspend (String) -> Unit,
    release: suspend (String) -> Unit,
    onPages: (String, List<Uri>) -> Unit,
    onFailure: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val pages by rememberUpdatedState(onPages)
    val failed by rememberUpdatedState(onFailure)
    val scope = rememberCoroutineScope()
    var launchedId by rememberSaveable { mutableStateOf<String?>(null) }
    // A pending activity result can be delivered as soon as the launcher registers.
    val restoredScannerId = remember { launchedId }
    LaunchedEffect(Unit) { recover(restoredScannerId) }
    var preparing by remember { mutableStateOf(false) }
    val scanner =
        remember {
            GmsDocumentScanning.getClient(
                GmsDocumentScannerOptions
                    .Builder()
                    .setGalleryImportAllowed(false)
                    .setPageLimit(PAGE_LIMIT)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build(),
            )
        }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val id = launchedId ?: return@rememberLauncherForActivityResult
            launchedId = null
            val uris =
                GmsDocumentScanningResult
                    .fromActivityResultIntent(result.data)
                    ?.pages
                    ?.map { it.imageUri }
                    .orEmpty()
            pages(id, if (result.resultCode == Activity.RESULT_OK) uris else emptyList())
        }
    return start@{
        if (launchedId != null || preparing) return@start
        val activity = context.findActivity()
        if (activity == null) {
            failed()
            return@start
        }
        val id = newId()
        preparing = true
        scope.launch {
            try {
                reserve(id)
                val sender =
                    suspendCancellableCoroutine { continuation ->
                        scanner
                            .getStartScanIntent(activity)
                            .addOnSuccessListener { continuation.resume(it) }
                            .addOnFailureListener { continuation.resumeWithException(it) }
                            .addOnCanceledListener { continuation.cancel() }
                    }
                launcher.launch(IntentSenderRequest.Builder(sender).build())
                launchedId = id
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed()
            } finally {
                preparing = false
                if (launchedId != id) withContext(NonCancellable) { release(id) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val PAGE_LIMIT = 20
