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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import io.github.davidvornholt.glimpse.shared.api.OriginalType
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
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    server: String,
    feed: PhotoFeed,
    haptics: Haptics,
    appScope: CoroutineScope,
    snackbar: SnackbarHostState,
    cameraActive: Boolean,
    scanRequested: Boolean,
    onScanRequestHandled: () -> Unit,
    hiddenSharedKey: String?,
    viewerPhotoId: String?,
    onOpenPhoto: (origin: String, item: GalleryItem) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val snapshot by library.galleryState.collectAsStateWithLifecycle()
    val waiting by feed.waiting.collectAsStateWithLifecycle()
    val connection by feed.connection.collectAsStateWithLifecycle()
    val permission = rememberCameraPermission()
    val today = LocalDate.now()
    val todays = snapshot.items.filter { it.sentAt.localDate() == today }

    val controller =
        remember {
            LifecycleCameraController(context).apply {
                setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            }
        }
    // Taking a photo before CameraX has started crashes, and a cold start from the shortcut takes a moment.
    var cameraReady by remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        val future = controller.initializationFuture
        future.addListener({ cameraReady = runCatching { future.get() }.isSuccess }, ContextCompat.getMainExecutor(context))
        onDispose {}
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
            newId = library::newId,
            recover = library::recoverNotes,
            reserve = settingsStore::reserveNote,
            release = { settingsStore.finishNote(it, used = false) },
            onPages = { id, pages ->
                appScope.launch {
                    var added = 0
                    try {
                        if (pages.isEmpty()) return@launch
                        val result =
                            library.addImages(
                                pages,
                                PhotoSource.Scan,
                                settingsStore.reservedNote(id),
                                library.newBatch(pages.size, id),
                                server,
                            )
                        added = result.added
                        when {
                            result.isPartial -> show(messages.scanPartial(result.added, result.total))
                            !result.isComplete -> show(messages.scanFailed)
                        }
                    } catch (_: IOException) {
                        show(messages.scanFailed)
                    } finally {
                        settingsStore.finishNote(id, used = added > 0)
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
            val slot = firstSlotIn(stripBounds, density.density, direction)
            flight = Flight(id, frame.asImageBitmap(), viewfinderBounds, slot)
        }
        val record =
            CaptureRecord(
                id,
                takenAt,
                PhotoSource.Camera,
                1,
                1,
                OriginalType.Jpeg,
            )
        appScope.launch {
            try {
                val file = library.beginCapture(record)
                controller.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            appScope.launch {
                                try {
                                    library.addCapture(record, file)
                                } catch (_: IOException) {
                                    show(messages.captureFailed)
                                }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            appScope.launch { library.cancelCapture(id) }
                            if (flight?.id == id) flight = null
                            show(messages.captureFailed)
                        }
                    },
                )
            } catch (_: IOException) {
                library.cancelCapture(id)
                show(messages.captureFailed)
            } catch (_: IllegalStateException) {
                // The activity can rotate while the capture journal is being saved.
                library.cancelCapture(id)
                show(messages.captureFailed)
            }
        }
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
        ViewfinderScrims()
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
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
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
                viewerPhotoId = viewerPhotoId,
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
                    enabled = settings.mode == CaptureMode.Scan || (cameraOn && cameraReady),
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
        // The sheet stays on the photo it opened on, even when another one arrives meanwhile.
        val lastId = remember { todays.firstOrNull()?.id }
        val last = todays.firstOrNull { it.id == lastId }
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

/** Darkens the top and bottom of the preview so the white controls read over a bright scene. */
@Composable
private fun BoxScope.ViewfinderScrims() {
    val shade = Color.Black.copy(alpha = SCRIM_ALPHA)
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .fillMaxHeight(TOP_SCRIM_HEIGHT)
            .background(Brush.verticalGradient(listOf(shade, Color.Transparent))),
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(BOTTOM_SCRIM_HEIGHT)
            .background(Brush.verticalGradient(0f to Color.Transparent, BOTTOM_SCRIM_RAMP to shade, 1f to shade)),
    )
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
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
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
                containerColor = Color.Black.copy(alpha = BUTTON_ALPHA),
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
    val scanPartial: (added: Int, total: Int) -> String,
) {
    companion object {
        fun from(context: Context) =
            CaptureMessages(
                captureFailed = context.getString(R.string.capture_failed),
                scanFailed = context.getString(R.string.scan_failed),
                scanPartial = { added, total -> context.resources.getQuantityString(R.plurals.scan_partial, total, added, total) },
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
private const val BUTTON_ALPHA = 0.4f
private const val SCRIM_ALPHA = 0.5f
private const val TOP_SCRIM_HEIGHT = 0.2f
private const val BOTTOM_SCRIM_HEIGHT = 0.45f
private const val BOTTOM_SCRIM_RAMP = 0.5f
