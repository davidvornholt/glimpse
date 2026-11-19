package io.github.davidvornholt.glimpse.features.pairing

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.settings.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PairingFlowTest {
    @Test
    fun `a recreated screen resumes the scanned link and finishes a permission request once`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ViewModelStore()
            try {
                fun screen() = ViewModelProvider.create(store, ViewModelProvider.NewInstanceFactory())[PairingFlow::class]
                val link = PairingLink("https://glimpse.example.com", "code")
                val claimer = mock(PairingClaimer::class.java)
                val session = Session(link.server, "test", "device", "Camera phone")
                `when`(claimer.claim(link, session.deviceName)).thenReturn(session)
                screen().open(link)
                assertEquals(Step.Confirming(link), screen().step)
                screen().pair(link, session.deviceName, claimer) { true }
                runCurrent()
                val request = Step.Permission(link, session.deviceName)
                assertEquals(request, screen().step)
                screen().step = request.copy(launched = true)
                screen().permissionResult(true, claimer)
                screen().permissionResult(true, claimer)
                runCurrent()
                verify(claimer, times(1)).claim(link, session.deviceName)
                assertEquals(Step.Done(session), screen().step)
            } finally {
                store.clear()
                Dispatchers.resetMain()
            }
        }
}
