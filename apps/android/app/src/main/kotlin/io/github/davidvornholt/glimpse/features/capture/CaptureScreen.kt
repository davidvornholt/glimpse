package io.github.davidvornholt.glimpse.features.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ZoomState
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.haptics.Haptics
import io.github.davidvornholt.glimpse.shared.photos.Connection
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.photos.PhotoFeed
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import io.github.davidvornholt.glimpse.shared.settings.CaptureMode
import io.github.davidvornholt.glimpse.shared.settings.FlashSetting
import io.github.davidvornholt.glimpse.shared.settings.SettingsStore
import io.github.davidvornholt.glimpse.shared.ui.CameraPermissionPrompt
import io.github.davidvornholt.glimpse.shared.ui.localDate
import io.github.davidvornholt.glimpse.shared.ui.rememberCameraPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The screen the app opens on: the viewfinder, the shutter, today's photos
 * and what the agents are up to.
 */
@Composable
fun CaptureScreen(
    library: PhotoLibrary,
    settingsStore: SettingsStore,
    feed: PhotoFeed,
    haptics: Haptics,
    appScope: CoroutineScope,
    snackbar: SnackbarHostState,
    cameraActive: Boolean,
    scanRequested: Boolean,
    onScanRequestHandled: () -> Unit,
    hiddenSharedKey: String?,
    onOpenPhoto: (origin: String, item: GalleryItem) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val items by library.items.collectAsStateWithLifecycle()
    val waiting by feed.waiting.collectAsStateWithLifecycle()
    val connection by feed.connection.collectAsStateWithLifecycle()
    val permission = rememberCameraPermission()
    val today = LocalDate.now()
    val todays = items.filter { it.sentAt.localDate() == today }

    val controller =
        remember {
            LifecycleCameraController(context).apply {
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            }
        }
    LaunchedEffect(settings.flash) { controller.imageCaptureFlashMode = settings.flash.toCameraX() }
    var zoom by remember { mutableFloatStateOf(1f) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = Observer<ZoomState> { zoom = it.zoomRatio }
        controller.zoomState.observe(lifecycleOwner, observer)
        onDispose { controller.zoomState.removeObserver(observer) }
    }
    LaunchedEffect(feed) { feed.viewed.collect { haptics.light(view) } }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewfinderBounds by remember { mutableStateOf(Rect.Zero) }
    var stripBounds by remember { mutableStateOf(Rect.Zero) }
    var flight by remember { mutableStateOf<Flight?>(null) }
    var noteSheetOpen by remember { mutableStateOf(false) }
    val stripState = rememberLazyListState()

    val messages = CaptureMessages.from(context)
    val show: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val setMode: (CaptureMode) -> Unit = { mode -> scope.launch { settingsStore.setMode(mode) } }

    val scan =
        rememberDocumentScanner(
            onPages = { pages ->
                appScope.launch {
                    try {
                        library.addImages(pages, PhotoSource.Scan, settingsStore.takePendingNote())
                    } catch (_: IOException) {
                        snackbar.showSnackbar(messages.scanFailed)
                    }
                }
            },
            onFailure = { show(messages.scannerUnavailable) },
        )
    val currentScan by rememberUpdatedState(scan)
    LaunchedEffect(scanRequested) {
        if (scanRequested) {
            settingsStore.setMode(CaptureMode.Scan)
            currentScan()
            onScanRequestHandled()
        }
    }

    fun takePhoto() {
        val preview = previewView ?: return
        haptics.shutter(view)
        val id = library.newId()
        val takenAt = Instant.now()
        val frame = preview.bitmap?.scaledTo(FRAME_WIDTH)
        library.showPending(id, PhotoSource.Camera, frame)
        scope.launch { stripState.scrollToItem(0) }
        if (frame != null && stripBounds != Rect.Zero) {
            val slot = firstSlotIn(stripBounds.left, stripBounds.center.y, density.density)
            flight = Flight(id, frame.asImageBitmap(), viewfinderBounds, slot)
        }
        val file = File(context.cacheDir, "capture-$id.jpg")
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    appScope.launch {
                        try {
                            library.addCapture(id, file, takenAt)
                        } catch (_: IOException) {
                            file.delete()
                            snackbar.showSnackbar(messages.captureFailed)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    library.dropPending(id)
                    file.delete()
                    if (flight?.id == id) flight = null
                    show(messages.captureFailed)
                }
            },
        )
    }

    GlimpseCameraSurface(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val cameraOn = permission.granted && cameraActive
        Viewfinder(
            controller = controller,
            active = cameraOn,
            onPreviewView = { previewView = it },
            onSwipe = { forward ->
                val next = if (forward) CaptureMode.Scan else CaptureMode.Photo
                if (next != settings.mode) {
                    haptics.tick(view)
                    setMode(next)
                }
            },
            modifier = Modifier.fillMaxSize().onGloballyPositioned { viewfinderBounds = it.boundsInRoot().translate(-origin) },
        )
        if (!permission.granted && settings.mode == CaptureMode.Photo) {
            CameraPermissionPrompt(
                state = permission,
                title = stringResource(R.string.camera_permission_title),
                body = stringResource(R.string.camera_permission_body),
                modifier = Modifier.background(Color.Black),
            )
        }

        TopControls(
            flash = settings.flash,
            showFlash = settings.mode == CaptureMode.Photo && permission.granted,
            waiting = waiting,
            connection = connection,
            onFlash = { scope.launch { settingsStore.setFlash(settings.flash.next()) } },
            onSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ZoomChip(zoom = zoom, visible = cameraOn && abs(zoom - 1f) > ZOOM_EPSILON, onReset = { controller.setZoomRatio(1f) })
            AnimatedVisibility(settings.pendingNote != null, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                PendingNotePill(
                    note = settings.pendingNote.orEmpty(),
                    onEdit = { noteSheetOpen = true },
                    onDiscard = { scope.launch { settingsStore.setPendingNote(null) } },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Filmstrip(
                items = todays,
                state = stripState,
                hiddenKey = hiddenSharedKey,
                flyingId = flight?.id,
                onOpen = { onOpenPhoto(FILMSTRIP, it) },
                modifier = Modifier.onGloballyPositioned { stripBounds = it.boundsInRoot().translate(-origin) },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraButton(Icons.Rounded.PhotoLibrary, stringResource(R.string.capture_inbox), onOpenInbox, large = true)
                Shutter(
                    mode = settings.mode,
                    onClick = { if (settings.mode == CaptureMode.Scan) scan() else takePhoto() },
                    enabled = settings.mode == CaptureMode.Scan || cameraOn,
                )
                CameraButton(Icons.Rounded.EditNote, stringResource(R.string.capture_note), { noteSheetOpen = true }, large = true)
            }
            ModeSwitch(mode = settings.mode, onSelect = setMode, haptics = haptics)
        }

        flight?.let { current ->
            CaptureFlight(current, onLanded = { if (flight?.id == current.id) flight = null })
        }
    }

    if (noteSheetOpen) {
        val last = todays.firstOrNull()
        CaptureNoteSheet(
            pendingNote = settings.pendingNote,
            last = last,
            onDismiss = { noteSheetOpen = false },
            onSave = { target, note ->
                noteSheetOpen = false
                when (target) {
                    NoteTarget.Next -> {
                        scope.launch { settingsStore.setPendingNote(note) }
                    }

                    NoteTarget.Last -> {
                        if (last != null) {
                            appScope.launch {
                                try {
                                    library.setNote(last.id, note)
                                } catch (_: ApiException) {
                                    snackbar.showSnackbar(messages.noteFailed)
                                } catch (_: IOException) {
                                    snackbar.showSnackbar(messages.noteFailed)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun GlimpseCameraSurface(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color.Black), content = content)
}

@Composable
private fun TopControls(
    flash: FlashSetting,
    showFlash: Boolean,
    waiting: Int,
    connection: Connection,
    onFlash: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offline by remember { mutableStateOf(false) }
    val live = connection == Connection.Live
    LaunchedEffect(live) {
        if (live) {
            offline = false
        } else {
            delay(OFFLINE_AFTER_MILLIS)
            offline = true
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CameraButton(Icons.Rounded.Settings, stringResource(R.string.capture_settings), onSettings, Modifier.align(Alignment.CenterStart))
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WaitingChip(waiting)
            OfflineChip(offline)
        }
        AnimatedVisibility(showFlash, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) {
            CameraButton(flash.icon(), stringResource(flash.label()), onFlash)
        }
    }
}

@Composable
private fun ZoomChip(
    zoom: Float,
    visible: Boolean,
    onReset: () -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        val label = stringResource(R.string.capture_zoom, zoom)
        val description = stringResource(R.string.capture_zoom_reset, zoom)
        Surface(
            onClick = onReset,
            shape = CircleShape,
            color = Color.Black.copy(alpha = CHIP_ALPHA),
            contentColor = Color.White,
            modifier = Modifier.height(48.dp).semantics { contentDescription = description },
        ) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CameraButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(if (large) 56.dp else 48.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = BUTTON_ALPHA),
                contentColor = Color.White,
            ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** Messages the camera shows after the composable that asked may be gone. */
private class CaptureMessages(
    val captureFailed: String,
    val scanFailed: String,
    val scannerUnavailable: String,
    val noteFailed: String,
) {
    companion object {
        fun from(context: Context) =
            CaptureMessages(
                captureFailed = context.getString(R.string.capture_failed),
                scanFailed = context.getString(R.string.scan_failed),
                scannerUnavailable = context.getString(R.string.scanner_unavailable),
                noteFailed = context.getString(R.string.note_save_failed),
            )
    }
}

private fun FlashSetting.next() = FlashSetting.entries[(ordinal + 1) % FlashSetting.entries.size]

private fun FlashSetting.toCameraX() =
    when (this) {
        FlashSetting.Off -> ImageCapture.FLASH_MODE_OFF
        FlashSetting.Auto -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.On -> ImageCapture.FLASH_MODE_ON
    }

private fun FlashSetting.icon() =
    when (this) {
        FlashSetting.Off -> Icons.Rounded.FlashOff
        FlashSetting.Auto -> Icons.Rounded.FlashAuto
        FlashSetting.On -> Icons.Rounded.FlashOn
    }

private fun FlashSetting.label() =
    when (this) {
        FlashSetting.Off -> R.string.capture_flash_off
        FlashSetting.Auto -> R.string.capture_flash_auto
        FlashSetting.On -> R.string.capture_flash_on
    }

/** A smaller copy of the viewfinder frame, enough for the flight and the first thumbnail. */
private fun Bitmap.scaledTo(maxWidth: Int): Bitmap {
    if (width <= maxWidth) return this
    val scaled = scale(maxWidth, (height * maxWidth.toFloat() / width).roundToInt().coerceAtLeast(1))
    if (scaled !== this) recycle()
    return scaled
}

private const val FRAME_WIDTH = 540
private const val ZOOM_EPSILON = 0.05f
private const val OFFLINE_AFTER_MILLIS = 3000L
private const val CHIP_ALPHA = 0.55f
private const val BUTTON_ALPHA = 0.16f
