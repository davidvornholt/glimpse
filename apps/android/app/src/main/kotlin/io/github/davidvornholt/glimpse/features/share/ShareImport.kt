package io.github.davidvornholt.glimpse.features.share

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private var request = saved.get<String>("request")?.let { GlimpseJson.decodeFromString<ShareRequest>(it) }
    private var job: Job? = null
    var result by mutableStateOf(ImportResult(saved.get<Int>("added") ?: 0, request?.batch?.photoIds?.size ?: 0))
        private set
    var stage by mutableStateOf(
        when {
            request == null -> Stage.Editing
            !saved.contains("added") -> Stage.Sending
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
        saved["request"] = GlimpseJson.encodeToString(next)
        saved.remove<Int>("added")
        saved["completed"] = arrayListOf<String>()
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
                val completed = saved.get<ArrayList<String>>("completed") ?: arrayListOf()
                result =
                    library.addImages(images, PhotoSource.Share, current.note, current.batch, current.server, completed.toSet()) {
                        completed.add(it)
                        saved["completed"] = completed
                    }
                saved["added"] = result.added
                stage = outcome(result)
            }
    }

    private fun outcome(result: ImportResult) =
        when {
            result.isComplete -> Stage.Sent
            result.isPartial -> Stage.Partial
            else -> Stage.Failed
        }
}
