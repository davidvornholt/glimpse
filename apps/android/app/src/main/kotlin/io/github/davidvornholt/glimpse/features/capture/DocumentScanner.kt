package io.github.davidvornholt.glimpse.features.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Opens Google's document scanner, which finds the page edges, flattens the
 * page and cleans it up. Gives the scanned pages, in order.
 */
@Composable
fun rememberDocumentScanner(
    onPages: (List<Uri>) -> Unit,
    onFailure: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val pages by rememberUpdatedState(onPages)
    val failed by rememberUpdatedState(onFailure)
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
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val uris =
                GmsDocumentScanningResult
                    .fromActivityResultIntent(result.data)
                    ?.pages
                    ?.map { it.imageUri }
                    .orEmpty()
            if (uris.isNotEmpty()) pages(uris)
        }
    return remember(scanner, launcher) {
        {
            val activity = context.findActivity()
            if (activity == null) {
                failed()
            } else {
                scanner
                    .getStartScanIntent(activity)
                    .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { failed() }
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
