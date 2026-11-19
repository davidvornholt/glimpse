package io.github.davidvornholt.glimpse.features.pairing

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The longest name the server accepts for a phone. */
internal const val MAX_NAME = 64

/**
 * The name to suggest when pairing: the one set under Settings → About phone,
 * which apps can read without a permission, or else the phone's model.
 */
internal fun suggestedDeviceName(context: Context): String =
    suggestedDeviceName(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL)

internal fun suggestedDeviceName(
    settingsName: String?,
    model: String,
): String = (settingsName?.trim()?.takeIf { it.isNotEmpty() } ?: model.trim()).take(MAX_NAME)
