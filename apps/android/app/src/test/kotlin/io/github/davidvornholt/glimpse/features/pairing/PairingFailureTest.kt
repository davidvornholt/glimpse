package io.github.davidvornholt.glimpse.features.pairing

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class PairingFailureTest {
    private fun failureOf(
        status: Int,
        tag: ApiErrorTag,
    ) = PairingClaimer.failureOf(ApiException(status, tag, "No"))

    @Test
    fun `tells each server answer apart`() {
        assertEquals(PairingFailure.CodeExpired, failureOf(404, ApiErrorTag.NotFound))
        assertEquals(PairingFailure.Rejected, failureOf(400, ApiErrorTag.InvalidRequest))
        assertEquals(PairingFailure.Unknown, failureOf(500, ApiErrorTag.ServerError))
        assertEquals(PairingFailure.Unknown, failureOf(418, ApiErrorTag.Unknown))
    }

    @Test
    fun `calls a network failure unreachable`() {
        assertEquals(PairingFailure.Unreachable, PairingClaimer.failureOf(IOException("Connection refused")))
        assertEquals(PairingFailure.Unknown, PairingClaimer.failureOf(IllegalStateException("Broken")))
    }
}
