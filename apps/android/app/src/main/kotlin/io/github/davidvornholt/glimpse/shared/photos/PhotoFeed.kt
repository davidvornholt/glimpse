package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.api.Photo
import io.github.davidvornholt.glimpse.shared.api.ServerEvent
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Duration
import java.time.Instant

data class RemotePhotos(
    val photos: Map<String, Photo> = emptyMap(),
    val loadedFor: String? = null,
)

enum class Connection { Offline, Connecting, Live }

/**
 * The server's photos of the last 30 days, kept current through `GET
 * /v1/events` while the app is on screen. Events aren't replayed, so the
 * list is fetched again whenever the stream (re)opens.
 */
class PhotoFeed(
    private val sessions: SessionStore,
    private val apiFor: (Session) -> GlimpseApi,
    private val scope: CoroutineScope,
) {
    private val all = MutableStateFlow(RemotePhotos())
    private val waitingCount = MutableStateFlow(0)
    private val state = MutableStateFlow(Connection.Offline)
    private val listed = MutableSharedFlow<ServerList>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val removed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val newlyViewed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    private var job: Job? = null
    private var discarded: Session? = null

    val photos: StateFlow<RemotePhotos> = all.asStateFlow()

    /** How many agents wait for a photo with `glimpse wait`. */
    val waiting: StateFlow<Int> = waitingCount.asStateFlow()

    val connection: StateFlow<Connection> = state.asStateFlow()

    /** Each complete list of the server's photos, fetched as the stream (re)opens. */
    val lists: SharedFlow<ServerList> = listed.asSharedFlow()

    /** The id of each photo deleted on the server while the stream is live. */
    val deleted: SharedFlow<String> = removed.asSharedFlow()

    /** The id of each photo the moment an agent first looks at it. */
    val viewed: SharedFlow<String> = newlyViewed.asSharedFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { follow() }
    }

    fun stop() {
        job?.cancel()
        job = null
        state.value = Connection.Offline
        waitingCount.value = 0
    }

    private suspend fun follow() {
        sessions.state.collectLatest { session ->
            if (session !is SessionState.Paired) {
                all.value = RemotePhotos()
                waitingCount.value = 0
                state.value = Connection.Offline
                return@collectLatest
            }
            if (all.value.loadedFor != session.session.server) all.value = RemotePhotos()
            val api = apiFor(session.session)
            var backoff = FIRST_BACKOFF
            while (true) {
                state.value = Connection.Connecting
                try {
                    api.events().collect { event ->
                        when (event) {
                            ServerEvent.Connected -> {
                                state.value = Connection.Live
                                backoff = FIRST_BACKOFF
                                load(api, session.session)
                            }

                            is ServerEvent.PhotoChanged -> {
                                upsert(session.session, event.photo)
                            }

                            is ServerEvent.PhotoDeleted -> {
                                forget(event.id)
                                removed.emit(event.id)
                            }

                            is ServerEvent.Waiting -> {
                                synchronized(this@PhotoFeed) { if (accepts(session.session)) waitingCount.value = event.count }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (error.tag == ApiErrorTag.Unauthorized) {
                        sessions.markRemoved(session.session.token)
                        return@collectLatest
                    }
                } catch (_: IOException) {
                    // Offline or the server restarted; try again after a pause.
                }
                state.value = Connection.Offline
                waitingCount.value = 0
                delay(backoff.toMillis())
                backoff = minOf(backoff.multipliedBy(2), MAX_BACKOFF)
            }
        }
    }

    private suspend fun load(
        api: GlimpseApi,
        session: Session,
    ) {
        val since = Instant.now().minus(WINDOW)
        val photos = mutableListOf<Photo>()
        var cursor: String? = null
        do {
            val page = api.photos(limit = GlimpseApi.MAX_PAGE, before = cursor, since = since)
            photos += page.photos
            cursor = page.nextCursor
        } while (cursor != null && photos.size < MAX_PHOTOS)
        synchronized(this) {
            if (!accepts(session)) return
            all.value = RemotePhotos(photos.associateBy { it.id }, session.server)
            if (cursor == null) listed.tryEmit(ServerList(session, photos.mapTo(HashSet()) { it.id }))
        }
    }

    @Synchronized
    fun clear() {
        discarded = (sessions.state.value as? SessionState.Paired)?.session
        all.value = RemotePhotos()
        waitingCount.value = 0
        state.value = Connection.Offline
    }

    private fun accepts(session: Session) = session != discarded && (sessions.state.value as? SessionState.Paired)?.session == session

    @Synchronized
    fun upsert(
        session: Session,
        photo: Photo,
    ) {
        if (!accepts(session)) return
        val previous = all.value.photos[photo.id]
        all.update { it.copy(photos = it.photos + (photo.id to photo)) }
        if (photo.viewedAt != null && previous?.viewedAt == null) newlyViewed.tryEmit(photo.id)
    }

    fun forget(id: String) {
        all.update { it.copy(photos = it.photos - id) }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofDays(31)
        val FIRST_BACKOFF: Duration = Duration.ofSeconds(1)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)
        const val MAX_PHOTOS = 5000
    }
}
