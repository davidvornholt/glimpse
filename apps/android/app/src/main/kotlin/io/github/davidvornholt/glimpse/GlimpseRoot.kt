package io.github.davidvornholt.glimpse

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.davidvornholt.glimpse.features.capture.CaptureScreen
import io.github.davidvornholt.glimpse.features.capture.FILMSTRIP
import io.github.davidvornholt.glimpse.features.pairing.PairingClaimer
import io.github.davidvornholt.glimpse.features.pairing.PairingFlow
import io.github.davidvornholt.glimpse.features.pairing.PairingScreen
import io.github.davidvornholt.glimpse.features.pairing.Step
import io.github.davidvornholt.glimpse.features.photos.INBOX
import io.github.davidvornholt.glimpse.features.photos.INBOX_PEEK_HEIGHT
import io.github.davidvornholt.glimpse.features.photos.InboxSheet
import io.github.davidvornholt.glimpse.features.photos.PhotoActions
import io.github.davidvornholt.glimpse.features.photos.PhotoViewer
import io.github.davidvornholt.glimpse.features.settings.SettingsScreen
import io.github.davidvornholt.glimpse.shared.network.LocalNetwork
import io.github.davidvornholt.glimpse.shared.photos.Connection
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.settings.CaptureMode
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.ui.LocalSharedTransitionScope
import io.github.davidvornholt.glimpse.shared.ui.LocalViewerSettled
import io.github.davidvornholt.glimpse.shared.ui.backGesture
import io.github.davidvornholt.glimpse.shared.ui.localDate
import io.github.davidvornholt.glimpse.shared.ui.rememberPredictiveBack
import io.github.davidvornholt.glimpse.shared.ui.sharedPhotoKey
import io.github.davidvornholt.glimpse.shared.ui.theme.GlimpseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate

/** What the activity hands the screens: a link it couldn't read and the shortcuts that opened it. */
class LaunchRequests(
    val invalidLink: Boolean,
    val scan: Boolean,
    /** Counts the times a shortcut or the tile asked for the camera. */
    val camera: Int,
)

/** Pairing until the phone is paired, then the camera with the inbox and viewer over it. */
@Composable
internal fun GlimpseRoot(
    graph: AppGraph,
    requests: LaunchRequests,
    pairing: PairingFlow,
    onInvalidLinkShown: () -> Unit,
    onScanHandled: () -> Unit,
    onDarkSystemBars: (Boolean) -> Unit,
) {
    val session by graph.sessions.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pairingOpen by rememberSaveable { mutableStateOf(false) }
    var viewerOpen by remember { mutableStateOf(false) }
    val invalidMessage = stringResource(R.string.pairing_link_invalid)
    LaunchedEffect(session) { if (session is SessionState.Unpaired) pairingOpen = true }
    LaunchedEffect(requests.invalidLink) {
        if (requests.invalidLink) {
            onInvalidLinkShown()
            snackbar.showSnackbar(invalidMessage)
        }
    }
    val claimer = remember(graph) { PairingClaimer(graph::anonymousApi, graph.sessions, graph.library) }
    val paired = session as? SessionState.Paired

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            // The splash screen stays up until the session is known.
            session is SessionState.Loading -> {}

            pairingOpen || paired == null || pairing.step != Step.Welcome -> {
                LaunchedEffect(Unit) { onDarkSystemBars(false) }
                PairingScreen(
                    claimer = claimer,
                    haptics = graph.haptics,
                    flow = pairing,
                    current = paired?.session,
                    removedFrom = (session as? SessionState.Unpaired)?.removedFrom,
                    allowCleartext = BuildConfig.DEBUG,
                    onDone = { pairingOpen = false },
                    onCancel = if (paired != null) ({ pairingOpen = false }) else null,
                )
            }

            else -> {
                PairedContent(
                    graph = graph,
                    session = paired.session,
                    snackbar = snackbar,
                    requests = requests,
                    onScanHandled = onScanHandled,
                    onPairAgain = { pairingOpen = true },
                    onViewerOpenChange = { viewerOpen = it },
                    onDarkSystemBars = onDarkSystemBars,
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(bottom = if (viewerOpen) VIEWER_SNACKBAR_LIFT else INBOX_PEEK_HEIGHT + 8.dp),
        )
    }
}

@Composable
private fun PairedContent(
    graph: AppGraph,
    session: Session,
    snackbar: SnackbarHostState,
    requests: LaunchRequests,
    onScanHandled: () -> Unit,
    onPairAgain: () -> Unit,
    onViewerOpenChange: (Boolean) -> Unit,
    onDarkSystemBars: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by graph.library.galleryState.collectAsStateWithLifecycle()
    val items = snapshot.items
    val connection by graph.feed.connection.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val accessibility = LocalAccessibilityManager.current
    val undoMillis = accessibility?.calculateRecommendedTimeoutMillis(10000L, containsText = true, containsControls = true) ?: 10000L
    val actions =
        remember(graph, undoMillis) { PhotoActions(context.applicationContext, graph.library, graph.uiScope, snackbar, undoMillis) }
    val pending by graph.library.undo.pending
        .collectAsStateWithLifecycle()
    val deletedMessage = stringResource(R.string.viewer_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(pending) {
        val item = pending ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(deletedMessage, undoLabel, duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) graph.uiScope.launch { graph.library.undo.undo(item.id) }
    }
    val scaffold = rememberBottomSheetScaffoldState()
    val sheet = scaffold.bottomSheetState
    var viewerOrigin by rememberSaveable { mutableStateOf(FILMSTRIP) }
    var viewerId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val unpairFailed = stringResource(R.string.unpair_clear_failed)
    val viewerOpenChange by rememberUpdatedState(onViewerOpenChange)
    LaunchedEffect(viewerOpen) { viewerOpenChange(viewerOpen) }

    // Pairing asked for local network access, but a revoke or a restore to a new phone takes it away again,
    // and without it every upload to a server on the home network just times out.
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(session.server) {
        val host = session.server.toUri().host ?: return@LaunchedEffect
        if (!LocalNetwork.isAllowed(context) && LocalNetwork.isLocal(host)) localNetwork.launch(LocalNetwork.PERMISSION)
    }

    // A shortcut or the tile asked for the camera: put it in front, ready for a photo even if scanning was last.
    LaunchedEffect(requests.camera) {
        if (requests.camera > 0) {
            viewerOpen = false
            settingsOpen = false
            graph.settings.setMode(CaptureMode.Photo)
            sheet.partialExpand()
        }
    }

    val sheetExpanded = sheet.currentValue == SheetValue.Expanded
    val hiddenKey = viewerId?.takeIf { viewerOpen }?.let { sharedPhotoKey(viewerOrigin, it) }
    val viewerShown = updateTransition(viewerOpen && viewerId != null, label = "viewer")
    val viewerSettled = viewerShown.currentState && viewerShown.targetState
    val coveredPhotoId = viewerId.takeIf { viewerSettled }
    val today = LocalDate.now()
    val todays = items.filter { it.sentAt.localDate() == today }
    val open: (String, GalleryItem) -> Unit = { origin, item ->
        viewerOrigin = origin
        viewerId = item.id
        viewerOpen = true
    }
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(connection == Connection.Live) {
        if (connection == Connection.Live) {
            offline = false
        } else {
            delay(OFFLINE_AFTER_MILLIS)
            offline = true
        }
    }

    // The camera and the viewer are always dark; the sheet and settings follow the theme.
    val darkBars = viewerOpen || (!settingsOpen && !sheetExpanded)
    LaunchedEffect(darkBars) { onDarkSystemBars(darkBars) }
    BackHandler(enabled = sheetExpanded && !viewerOpen && !settingsOpen) { scope.launch { sheet.partialExpand() } }

    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalViewerSettled provides viewerSettled) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val navigationBar = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
                val peek = INBOX_PEEK_HEIGHT + navigationBar
                val travel = with(density) { (maxHeight * SHEET_HEIGHT - peek).toPx() }
                val fullHeight = with(density) { maxHeight.toPx() }
                val expandedFraction by remember(travel, fullHeight) {
                    derivedStateOf {
                        val offset = runCatching { sheet.requireOffset() }.getOrNull() ?: return@derivedStateOf 0f
                        val collapsedOffset = fullHeight - with(density) { peek.toPx() }
                        ((collapsedOffset - offset) / travel).coerceIn(0f, 1f)
                    }
                }
                val sheetColor = lerp(CAMERA_SHEET, MaterialTheme.colorScheme.surfaceContainerLow, expandedFraction)
                BottomSheetScaffold(
                    scaffoldState = scaffold,
                    sheetPeekHeight = peek,
                    sheetDragHandle = null,
                    sheetContainerColor = sheetColor,
                    sheetShadowElevation = 0.dp,
                    sheetContent = {
                        InboxSheet(
                            items = items,
                            offline = offline,
                            expanded = sheetExpanded,
                            expandedFraction = { expandedFraction },
                            viewerPhotoId = coveredPhotoId.takeIf { viewerOrigin == INBOX },
                            hiddenKey = hiddenKey,
                            onToggle = { scope.launch { if (sheetExpanded) sheet.partialExpand() else sheet.expand() } },
                            onOpen = { open(INBOX, it) },
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT),
                        )
                    },
                ) {
                    GlimpseTheme(darkTheme = true) {
                        CaptureScreen(
                            library = graph.library,
                            settingsStore = graph.settings,
                            pairing = session.pairing,
                            feed = graph.feed,
                            haptics = graph.haptics,
                            appScope = graph.uiScope,
                            snackbar = snackbar,
                            cameraActive = !sheetExpanded && !viewerOpen && !settingsOpen,
                            scanRequested = requests.scan,
                            onScanRequestHandled = onScanHandled,
                            hiddenSharedKey = hiddenKey,
                            viewerPhotoId = coveredPhotoId.takeIf { viewerOrigin == FILMSTRIP },
                            onOpenPhoto = open,
                            onOpenInbox = { scope.launch { sheet.expand() } },
                            onOpenSettings = { settingsOpen = true },
                            contentPadding = PaddingValues(bottom = peek),
                        )
                    }
                }
                GlimpseTheme(darkTheme = true) {
                    PhotoViewer(
                        shown = viewerShown,
                        loaded = snapshot.loadedFor == session.server,
                        items = if (viewerOrigin == FILMSTRIP) todays else items,
                        origin = viewerOrigin,
                        currentId = viewerId,
                        onPageChange = { viewerId = it },
                        onClose = { viewerOpen = false },
                        actions = actions,
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        settingsOpen,
        enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 3 } + fadeIn(),
        exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 3 } + fadeOut(),
    ) {
        val back by rememberPredictiveBack(enabled = settingsOpen) { settingsOpen = false }
        SettingsScreen(
            session = session,
            connection = connection,
            keepCopyInGallery = settings.keepCopyInGallery,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            onKeepCopyChange = { keep -> scope.launch { graph.settings.setKeepCopyInGallery(keep) } },
            onPairAgain = {
                settingsOpen = false
                onPairAgain()
            },
            onUnpair = {
                settingsOpen = false
                graph.uiScope.launch {
                    try {
                        graph.library.changePairing(null)
                    } catch (_: IOException) {
                        snackbar.showSnackbar(unpairFailed)
                    }
                }
            },
            onBack = { settingsOpen = false },
            modifier = Modifier.backGesture({ back }),
        )
    }
}

/** The sheet's color while it peeks over the camera. */
private val CAMERA_SHEET = Color(0xFF151515)
private const val SHEET_HEIGHT = 0.92f
private const val OFFLINE_AFTER_MILLIS = 3000L
private val VIEWER_SNACKBAR_LIFT = 96.dp
