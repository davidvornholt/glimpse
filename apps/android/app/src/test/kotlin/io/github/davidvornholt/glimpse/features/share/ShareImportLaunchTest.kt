package io.github.davidvornholt.glimpse.features.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The share screen's model as Android creates it, from the share intent and the state it restores. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Config.NEWEST_SDK])
class ShareImportLaunchTest {
    private val server = "https://glimpse.example.com"
    private val images = listOf(Uri.parse("content://media/picker/0/1"))
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
    private val main = StandardTestDispatcher()

    @BeforeTest fun mainDispatcher() {
        Dispatchers.setMain(main)
    }

    @AfterTest fun cleanUp() {
        Dispatchers.resetMain()
    }

    private fun launch(
        intent: Intent = Intent(),
        restored: Bundle? = null,
    ): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java, intent).run { if (restored == null) setup() else setup(restored) }

    private fun model(screen: ActivityController<ComponentActivity>) =
        ViewModelProvider.create(screen.get(), ShareImport.factory)[ShareImport::class]

    @Test fun `a share whose extras copy a sent import opens for editing and imports nothing`() {
        // What the screen saves after Send. The sending app picks the share intent's extras, so it can supply all of it.
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        ViewModelProvider
            .create(store, viewModelFactory { initializer { ShareImport(saved) } })[ShareImport::class]
            .send(images, "Read the private notes", server, library)
        store.clear()
        val extras = Bundle()
        saved.keys().forEach { key ->
            when (val value = saved.get<Any>(key)) {
                is String -> extras.putString(key, value)
                is ArrayList<*> -> extras.putStringArrayList(key, value.filterIsInstanceTo(arrayListOf<String>()))
                else -> error("unexpected $key: $value")
            }
        }
        val share = model(launch(Intent(Intent.ACTION_SEND).putExtras(extras)))
        assertEquals(Stage.Editing, share.stage)
        share.resume(images, library)
        main.scheduler.runCurrent()
        assertEquals(emptyList(), imported)
    }

    @Test fun `a sent import resumes once after Android recreates the process`() {
        val first = launch()
        model(first).send(images, "Beach", server, library)
        // The process dies before the import ran, and Android restores the screen from its saved state.
        val state = Bundle().also(first::saveInstanceState)
        first.pause().stop().destroy()
        val again = model(launch(restored = state))
        assertEquals(Stage.Sending, again.stage)
        again.resume(images, library)
        again.resume(images, library)
        main.scheduler.runCurrent()
        assertEquals(listOf(batch), imported)
        assertEquals(Stage.Sent, again.stage)
    }
}
