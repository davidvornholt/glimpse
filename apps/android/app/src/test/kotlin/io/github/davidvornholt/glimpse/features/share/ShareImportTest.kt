package io.github.davidvornholt.glimpse.features.share

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.davidvornholt.glimpse.shared.photos.ImageBatch
import io.github.davidvornholt.glimpse.shared.photos.ImportResult
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito.mock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ShareImportTest {
    private val server = "https://glimpse.example.com"
    private val images = listOf(mock(Uri::class.java))
    private val batch = ImageBatch(null, listOf("01KAB3XQ8Z7Y6W5V4T3S2R1Q0P"))
    private val imported = mutableListOf<ImageBatch>()
    private val library =
        mock(PhotoLibrary::class.java) { call ->
            when (call.method.name) {
                "newBatch" -> {
                    batch
                }

                "addImages" -> {
                    imported += call.arguments[3] as ImageBatch
                    ImportResult(1, 1)
                }

                else -> {
                    null
                }
            }
        }
    private val store = ViewModelStore()

    private fun screen(saved: SavedStateHandle) =
        ViewModelProvider.create(store, viewModelFactory { initializer { ShareImport(saved) } })[ShareImport::class]

    @BeforeTest fun mainDispatcher() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest fun cleanUp() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `a saved import resumes with the same photos after the process is recreated`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val saved = SavedStateHandle()
            screen(saved).send(images, "Beach", server, library)
            // The process dies before the import ran, and the system restores the saved state.
            store.clear()
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
            val again = screen(restored)
            assertEquals(Stage.Sending, again.stage)
            again.resume(images, library)
            runCurrent()
            assertEquals(listOf(batch), imported)
            assertEquals(Stage.Sent, again.stage)
        }
}
