package io.github.davidvornholt.glimpse.features.pairing

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.haptics.Haptics
import io.github.davidvornholt.glimpse.shared.network.LocalNetwork
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.ui.CameraPermissionPrompt
import io.github.davidvornholt.glimpse.shared.ui.rememberCameraPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URI

private sealed interface Step {
    data object Welcome : Step

    data object Scanning : Step

    data object Pasting : Step

    data class Confirming(
        val link: PairingLink,
    ) : Step

    data class Claiming(
        val link: PairingLink,
    ) : Step

    data class Failed(
        val link: PairingLink,
        val failure: PairingFailure,
    ) : Step

    data class Done(
        val session: Session,
    ) : Step
}

/**
 * Pairs the phone with a Glimpse server: scan the QR code from `glimpse
 * pair` or paste its link, name the phone, and it's ready.
 */
@Composable
fun PairingScreen(
    claimer: PairingClaimer,
    haptics: Haptics,
    scope: CoroutineScope,
    current: Session?,
    removedFrom: String?,
    pendingLink: PairingLink?,
    onLinkHandled: () -> Unit,
    allowCleartext: Boolean,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf<Step>(Step.Welcome) }
    val linkHandled by rememberUpdatedState(onLinkHandled)
    LaunchedEffect(pendingLink) {
        if (pendingLink != null) {
            step = Step.Confirming(pendingLink)
            linkHandled()
        }
    }
    val canGoBack = step != Step.Welcome && step !is Step.Claiming && step !is Step.Done
    BackHandler(enabled = canGoBack) { step = Step.Welcome }
    BackHandler(enabled = step == Step.Welcome && onCancel != null) { onCancel?.invoke() }

    val runClaim: suspend (PairingLink, String) -> Unit = { link, name ->
        step =
            try {
                Step.Done(claimer.claim(link, name))
            } catch (error: ApiException) {
                Step.Failed(link, PairingClaimer.failureOf(error))
            } catch (error: IOException) {
                Step.Failed(link, PairingClaimer.failureOf(error))
            }
    }
    val context = LocalContext.current
    var waitingForPermission by remember { mutableStateOf<Pair<PairingLink, String>?>(null) }
    val localNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val (link, name) = waitingForPermission ?: return@rememberLauncherForActivityResult
            waitingForPermission = null
            if (granted) {
                scope.launch { runClaim(link, name) }
            } else {
                step = Step.Failed(link, PairingFailure.LocalNetworkDenied)
            }
        }
    val claim: (PairingLink, String) -> Unit = { link, name ->
        step = Step.Claiming(link)
        scope.launch {
            if (!LocalNetwork.isAllowed(context) && LocalNetwork.isLocal(link.host)) {
                waitingForPermission = link to name
                localNetwork.launch(LocalNetwork.PERMISSION)
            } else {
                runClaim(link, name)
            }
        }
    }

    val fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scaleInSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(fadeInSpec) + scaleIn(scaleInSpec, initialScale = ENTER_SCALE)).togetherWith(fadeOut(fadeOutSpec))
            },
            label = "pairing",
        ) { shown ->
            when (shown) {
                Step.Welcome -> {
                    Welcome(
                        removedFrom = removedFrom,
                        onScan = { step = Step.Scanning },
                        onPaste = { step = Step.Pasting },
                        onCancel = onCancel,
                    )
                }

                Step.Scanning -> {
                    Scanning(
                        allowCleartext = allowCleartext,
                        haptics = haptics,
                        onLink = { step = Step.Confirming(it) },
                        onPaste = { step = Step.Pasting },
                        onClose = { step = Step.Welcome },
                    )
                }

                Step.Pasting -> {
                    Pasting(
                        allowCleartext = allowCleartext,
                        onLink = { step = Step.Confirming(it) },
                        onCancel = { step = Step.Welcome },
                    )
                }

                is Step.Confirming -> {
                    Confirming(
                        link = shown.link,
                        current = current,
                        onPair = { name -> claim(shown.link, name) },
                        onCancel = { if (onCancel != null && pendingLink == null) onCancel() else step = Step.Welcome },
                    )
                }

                is Step.Claiming -> {
                    Claiming(shown.link)
                }

                is Step.Failed -> {
                    Failed(
                        step = shown,
                        onRetry = {
                            // A used-up code needs a new one; otherwise the same link may work now.
                            val needsNewCode = shown.failure == PairingFailure.CodeExpired || shown.failure == PairingFailure.TooManyTries
                            step = if (needsNewCode) Step.Welcome else Step.Confirming(shown.link)
                        },
                        onCancel = onCancel,
                    )
                }

                is Step.Done -> {
                    Done(shown.session, haptics, onDone)
                }
            }
        }
    }
}

@Composable
private fun Page(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Welcome(
    removedFrom: String?,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Page {
        MorphingHero(Icons.Rounded.QrCodeScanner, MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf)
        Title(stringResource(R.string.pairing_title))
        Body(stringResource(R.string.pairing_body))
        if (removedFrom != null) {
            Notice(Icons.Rounded.LinkOff, stringResource(R.string.pairing_removed, hostOf(removedFrom)))
        }
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.pairing_scan))
        }
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pairing_paste))
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun Scanning(
    allowCleartext: Boolean,
    haptics: Haptics,
    onLink: (PairingLink) -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    val permission = rememberCameraPermission()
    var wrongCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wrongCode) {
        if (wrongCode != null) {
            delay(HINT_MILLIS)
            wrongCode = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (permission.granted) {
            var found by remember { mutableStateOf(false) }
            QrScanner(onCode = { value ->
                if (found) return@QrScanner
                val link = PairingLink.parse(value, allowCleartext)
                if (link != null) {
                    found = true
                    haptics.success(view)
                    onLink(link)
                } else if (wrongCode != value) {
                    haptics.reject(view)
                    wrongCode = value
                }
            })
        } else {
            Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
                CameraPermissionPrompt(
                    state = permission,
                    title = stringResource(R.string.pairing_camera_title),
                    body = stringResource(R.string.pairing_camera_body),
                )
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = if (wrongCode != null) MaterialTheme.colorScheme.errorContainer else Color.Black.copy(alpha = SCRIM_ALPHA),
                contentColor = if (wrongCode != null) MaterialTheme.colorScheme.onErrorContainer else Color.White,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    stringResource(if (wrongCode != null) R.string.pairing_not_glimpse else R.string.pairing_scan_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            TextButton(onClick = onPaste) { Text(stringResource(R.string.pairing_paste), color = Color.White) }
        }
        FilledIconButton(
            onClick = onClose,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = SCRIM_ALPHA),
                    contentColor = Color.White,
                ),
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp).size(48.dp),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
        }
    }
}

@Composable
private fun Pasting(
    allowCleartext: Boolean,
    onLink: (PairingLink) -> Unit,
    onCancel: () -> Unit,
) {
    val text = rememberTextFieldState()
    var invalid by remember { mutableStateOf(false) }
    val submit = {
        val link = PairingLink.parse(text.text.toString().trim(), allowCleartext)
        if (link != null) onLink(link) else invalid = true
    }
    Page {
        Title(stringResource(R.string.pairing_paste_title))
        Body(stringResource(R.string.pairing_paste_body))
        OutlinedTextField(
            state = text,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.pairing_link_label)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = invalid,
            supportingText = { if (invalid) Text(stringResource(R.string.pairing_link_invalid)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            onKeyboardAction = { submit() },
        )
        LaunchedEffect(text.text) { invalid = false }
        Button(onClick = submit, enabled = text.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pairing_continue))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun Confirming(
    link: PairingLink,
    current: Session?,
    onPair: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val suggested = remember { current?.deviceName ?: suggestedDeviceName(context) }
    val name = rememberTextFieldState(suggested)
    val trimmed = name.text.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= MAX_NAME
    Page {
        MorphingHero(Icons.Rounded.QrCodeScanner, MaterialShapes.Pill, MaterialShapes.Cookie6Sided)
        Title(stringResource(R.string.pairing_confirm_title, link.host))
        Body(stringResource(R.string.pairing_confirm_body))
        OutlinedTextField(
            state = name,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.pairing_device_name)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(MAX_NAME),
            isError = !valid,
            supportingText = {
                Text(stringResource(if (valid) R.string.pairing_device_name_hint else R.string.pairing_device_name_invalid))
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            onKeyboardAction = { if (valid) onPair(trimmed.toString()) },
        )
        if (current != null) {
            val message =
                if (current.server == link.server) {
                    stringResource(R.string.pairing_again, link.host)
                } else {
                    stringResource(R.string.pairing_replace, hostOf(current.server))
                }
            Notice(Icons.Rounded.WarningAmber, message)
        }
        Button(onClick = { onPair(trimmed.toString()) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pairing_pair))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun Claiming(link: PairingLink) {
    Page {
        LoadingIndicator(Modifier.size(96.dp))
        Text(
            stringResource(R.string.pairing_claiming, link.host),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun Failed(
    step: Step.Failed,
    onRetry: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val message =
        when (step.failure) {
            PairingFailure.CodeExpired -> stringResource(R.string.pairing_failed_expired)
            PairingFailure.TooManyTries -> stringResource(R.string.pairing_failed_tries)
            PairingFailure.Rejected -> stringResource(R.string.pairing_failed_rejected)
            PairingFailure.Unreachable -> stringResource(R.string.pairing_failed_unreachable, step.link.host)
            PairingFailure.LocalNetworkDenied -> stringResource(R.string.pairing_failed_local_network, step.link.host)
            PairingFailure.Unknown -> stringResource(R.string.pairing_failed_unknown)
        }
    Page {
        MorphingHero(
            Icons.Rounded.LinkOff,
            MaterialShapes.Gem,
            MaterialShapes.Diamond,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
        )
        Title(stringResource(R.string.pairing_failed_title))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pairing_try_again)) }
        if (step.failure == PairingFailure.LocalNetworkDenied) {
            val context = LocalContext.current
            OutlinedButton(onClick = { LocalNetwork.openSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.pairing_open_settings))
            }
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun Done(
    session: Session,
    haptics: Haptics,
    onDone: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    // TalkBack users get a button instead of a screen that moves on by itself.
    val explores = remember { context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled }
    LaunchedEffect(Unit) {
        haptics.success(view)
        if (!explores) {
            delay(DONE_MILLIS)
            done()
        }
    }
    Page {
        Box(contentAlignment = Alignment.Center) {
            BurstIn()
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(72.dp),
            )
        }
        Title(stringResource(R.string.pairing_done, hostOf(session.server)))
        Body(stringResource(R.string.pairing_done_body))
        if (explores) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pairing_continue)) }
        }
    }
}

/** A circle that bursts into a star as the pairing completes. */
@Composable
private fun BurstIn() {
    val color = MaterialTheme.colorScheme.primary
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.SoftBurst) }
    val path = remember { Path() }
    Canvas(Modifier.size(HERO_SIZE).clearAndSetSemantics {}) {
        path.rewind()
        morph.toPath(appear.value.coerceIn(0f, 1f), path)
        val side = size.minDimension * appear.value
        rotate(degrees = BURST_TURN * (1f - appear.value)) {
            translate((size.width - side) / 2, (size.height - side) / 2) {
                scale(side, side, pivot = Offset.Zero) { drawPath(path, color) }
            }
        }
    }
}

/** A shape that slowly turns and morphs behind an icon, for the pairing steps. */
@Composable
private fun MorphingHero(
    icon: ImageVector,
    from: RoundedPolygon,
    to: RoundedPolygon,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val transition = rememberInfiniteTransition(label = "hero")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(MORPH_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "morph",
    )
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(TURN_MILLIS, easing = LinearEasing)),
        label = "turn",
    )
    val morph = remember(from, to) { Morph(from, to) }
    val path = remember { Path() }
    Box(Modifier.size(HERO_SIZE).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            path.rewind()
            morph.toPath(progress, path)
            val side = size.minDimension
            rotate(degrees = turn) {
                scale(side, side, pivot = Offset.Zero) { drawPath(path, container) }
            }
        }
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun Notice(
    icon: ImageVector,
    text: String,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun hostOf(server: String): String = runCatching { URI(server).host }.getOrNull() ?: server

private const val HINT_MILLIS = 2500L
private const val DONE_MILLIS = 1600L
private const val MORPH_MILLIS = 2400
private const val TURN_MILLIS = 16000
private const val FULL_TURN = 360f
private const val BURST_TURN = -90f
private const val ENTER_SCALE = 0.92f
private const val SCRIM_ALPHA = 0.6f
private val HERO_SIZE = 160.dp
