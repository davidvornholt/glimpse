package io.github.davidvornholt.glimpse.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.photos.Connection
import io.github.davidvornholt.glimpse.shared.settings.Session
import java.net.URI

/** The server, this phone's name, the gallery copy, and unpairing. */
@Composable
fun SettingsScreen(
    session: Session,
    connection: Connection,
    keepCopyInGallery: Boolean,
    versionName: String,
    versionCode: Int,
    onKeepCopyChange: (Boolean) -> Unit,
    onPairAgain: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var confirmUnpair by remember { mutableStateOf(false) }
    val host = runCatching { URI(session.server).host }.getOrNull() ?: session.server
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            Section(stringResource(R.string.settings_server))
            InfoRow(
                icon = if (connection == Connection.Offline) Icons.Rounded.CloudOff else Icons.Rounded.Cloud,
                headline = host,
                supporting =
                    stringResource(
                        when (connection) {
                            Connection.Live -> R.string.settings_connected
                            Connection.Connecting -> R.string.settings_connecting
                            Connection.Offline -> R.string.settings_offline
                        },
                    ),
            )
            InfoRow(Icons.Rounded.PhoneAndroid, session.deviceName, stringResource(R.string.settings_device_name))
            InfoRow(
                icon = Icons.Rounded.QrCodeScanner,
                headline = stringResource(R.string.settings_pair_again),
                supporting = stringResource(R.string.settings_pair_again_body),
                onClick = onPairAgain,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(stringResource(R.string.settings_photos))
            ListItem(
                checked = keepCopyInGallery,
                onCheckedChange = onKeepCopyChange,
                supportingContent = { Text(stringResource(R.string.settings_keep_copy_body)) },
                leadingContent = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                trailingContent = { Switch(checked = keepCopyInGallery, onCheckedChange = null) },
            ) {
                Text(stringResource(R.string.settings_keep_copy))
            }
            InfoRow(Icons.Rounded.Schedule, stringResource(R.string.settings_retention), stringResource(R.string.settings_retention_body))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                onClick = { confirmUnpair = true },
                supportingContent = { Text(stringResource(R.string.settings_unpair_body, host)) },
                leadingContent = { Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            ) {
                Text(stringResource(R.string.settings_unpair), color = MaterialTheme.colorScheme.error)
            }
            InfoRow(
                Icons.Rounded.Info,
                stringResource(R.string.settings_version),
                stringResource(R.string.settings_version_value, versionName, versionCode),
            )
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_unpair_title)) },
            text = { Text(stringResource(R.string.settings_unpair_text, host)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    onUnpair()
                }) { Text(stringResource(R.string.settings_unpair_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)).semantics { heading() },
    )
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    headline: String,
    supporting: String,
    onClick: (() -> Unit)? = null,
) {
    val leading: @Composable () -> Unit = { Icon(icon, contentDescription = null) }
    val support: @Composable () -> Unit = { Text(supporting) }
    if (onClick != null) {
        ListItem(onClick = onClick, leadingContent = leading, supportingContent = support) { Text(headline) }
    } else {
        ListItem(leadingContent = leading, supportingContent = support) { Text(headline) }
    }
}
