package io.github.davidvornholt.glimpse.features.share

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.davidvornholt.glimpse.shared.api.GlimpseJson
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.photos.ImageBatch
import io.github.davidvornholt.glimpse.shared.photos.ImportResult
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal enum class Stage { Editing, Sending, Sent, Partial, Failed }

@Serializable
private data class ShareRequest(
    val batch: ImageBatch,
    val server: String,
    val note: String?,
)

/** One import per share activity. Rotation reattaches to the job, and process restoration reuses its ids. */
internal class ShareImport(
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var request = (saved.get<Any>(REQUEST) as? String)?.let(::decode)
    private var job: Job? = null
    var result by mutableStateOf(ImportResult(saved.get<Any>(ADDED) as? Int ?: 0, request?.batch?.photoIds?.size ?: 0))
        private set
    var stage by mutableStateOf(
        when {
            request == null -> Stage.Editing
            !saved.contains(ADDED) -> Stage.Sending
            else -> outcome(result)
        },
    )
        private set

    fun send(
        images: List<Uri>,
        note: String?,
        server: String,
        library: PhotoLibrary,
    ) {
        if (stage != Stage.Editing && stage != Stage.Failed) return
        val next = ShareRequest(library.newBatch(images.size), server, note)
        request = next
        saved[REQUEST] = GlimpseJson.encodeToString(next)
        saved.remove<Int>(ADDED)
        saved[COMPLETED] = arrayListOf<String>()
        stage = Stage.Sending
        resume(images, library)
    }

    fun resume(
        images: List<Uri>,
        library: PhotoLibrary,
    ) {
        val current = request ?: return
        if (stage != Stage.Sending || job?.isActive == true) return
        job =
            viewModelScope.launch {
                val completed = (saved.get<Any>(COMPLETED) as? List<*>).orEmpty().filterIsInstanceTo(arrayListOf<String>())
                result =
                    library.addImages(images, PhotoSource.Share, current.note, current.batch, current.server, completed.toSet()) {
                        completed.add(it)
                        saved[COMPLETED] = completed
                    }
                saved[ADDED] = result.added
                stage = outcome(result)
            }
    }

    /** A value that isn't one this screen saved means there is no import to resume. */
    private fun decode(json: String): ShareRequest? =
        try {
            GlimpseJson.decodeFromString<ShareRequest>(json)
        } catch (_: IllegalArgumentException) {
            // SerializationException is one.
            null
        }

    private fun outcome(result: ImportResult) =
        when {
            result.isComplete -> Stage.Sent
            result.isPartial -> Stage.Partial
            else -> Stage.Failed
        }

    companion object {
        /**
         * Builds the model from the state this screen saved alone. Android would otherwise seed that state with the
         * share intent's extras, which the sending app picks, so they could start an import nobody sent.
         */
        val factory =
            viewModelFactory {
                initializer {
                    val withoutIntentExtras = MutableCreationExtras(this).apply { set(DEFAULT_ARGS_KEY, Bundle.EMPTY) }
                    ShareImport(withoutIntentExtras.createSavedStateHandle())
                }
            }

        private const val REQUEST = "io.github.davidvornholt.glimpse.share.request"
        private const val ADDED = "io.github.davidvornholt.glimpse.share.added"
        private const val COMPLETED = "io.github.davidvornholt.glimpse.share.completed"
    }
}
