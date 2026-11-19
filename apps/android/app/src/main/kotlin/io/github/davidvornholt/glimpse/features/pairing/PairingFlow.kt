package io.github.davidvornholt.glimpse.features.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.settings.Session
import kotlinx.coroutines.launch
import java.io.IOException

internal sealed interface Step {
    data object Welcome : Step

    data object Scanning : Step

    data object Pasting : Step

    data class Confirming(
        val link: PairingLink,
    ) : Step

    data class Claiming(
        val link: PairingLink,
    ) : Step

    data class Permission(
        val link: PairingLink,
        val name: String,
        val launched: Boolean = false,
    ) : Step

    data class Failed(
        val link: PairingLink,
        val failure: PairingFailure,
    ) : Step

    data class Done(
        val session: Session,
    ) : Step
}

/** The link and claim stay with the activity's ViewModelStore across rotation. */
internal class PairingFlow : ViewModel() {
    var step by mutableStateOf<Step>(Step.Welcome)

    fun open(link: PairingLink) {
        if (step !is Step.Claiming && step !is Step.Permission) step = Step.Confirming(link)
    }

    fun pair(
        link: PairingLink,
        name: String,
        claimer: PairingClaimer,
        needsPermission: suspend () -> Boolean,
    ) {
        if (step != Step.Confirming(link)) return
        step = Step.Claiming(link)
        viewModelScope.launch {
            if (needsPermission()) step = Step.Permission(link, name) else claim(link, name, claimer)
        }
    }

    fun permissionResult(
        granted: Boolean,
        claimer: PairingClaimer,
    ) {
        val request = step as? Step.Permission ?: return
        if (granted) {
            step = Step.Claiming(request.link)
            viewModelScope.launch { claim(request.link, request.name, claimer) }
        } else {
            step = Step.Failed(request.link, PairingFailure.LocalNetworkDenied)
        }
    }

    private suspend fun claim(
        link: PairingLink,
        name: String,
        claimer: PairingClaimer,
    ) {
        step =
            try {
                Step.Done(claimer.claim(link, name))
            } catch (error: ApiException) {
                Step.Failed(link, PairingClaimer.failureOf(error))
            } catch (error: IOException) {
                Step.Failed(link, PairingClaimer.failureOf(error))
            }
    }
}
