package io.github.davidvornholt.glimpse.shared.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Since Android 17, an app reaches the local network only once the person
 * allows it, and a connection without that permission just times out. A
 * server at home, with a private address, needs it; one on the internet
 * doesn't, so the app asks only for the first kind.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val FIRST_SDK = 37

    fun isAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < FIRST_SDK ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether [host] resolves to an address on the local network. A host that doesn't resolve isn't. */
    suspend fun isLocal(host: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                InetAddress.getAllByName(host).any(::isLocalAddress)
            } catch (_: UnknownHostException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    /**
     * Private and link-local addresses, unique local IPv6 addresses, and the
     * shared range Tailscale and carrier-grade NAT use: Android doesn't say
     * whether it counts that one, so the app asks to be safe.
     */
    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet6Address -> bytes[0].toInt() and 0xFE == 0xFC
            is Inet4Address -> bytes[0].toInt() and 0xFF == 100 && bytes[1].toInt() and 0xC0 == 64
            else -> false
        }
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
