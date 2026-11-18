package io.github.davidvornholt.glimpse.shared.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Glimpse API as one phone uses it. [token] is null only before pairing,
 * when the claim is the one call that needs none.
 */
class GlimpseApi(
    private val client: OkHttpClient,
    server: String,
    private val token: String?,
) {
    val baseUrl: HttpUrl = server.trimEnd('/').toHttpUrl()

    private val jsonType = "application/json".toMediaType()

    suspend fun health(): Boolean =
        try {
            call(
                Request
                    .Builder()
                    .url(url("healthz"))
                    .get()
                    .build(),
                authorized = false,
            ) { it.isSuccessful }
        } catch (_: IOException) {
            false
        } catch (_: ApiException) {
            false
        }

    suspend fun claim(
        code: String,
        deviceName: String,
    ): ClaimResult = send("POST", url("v1", "pairings", "claim"), ClaimRequest(code, deviceName), authorized = false)

    suspend fun me(): Me = get(url("v1", "me"))

    /** Registers a photo; true when the server didn't have it yet. */
    suspend fun register(
        id: String,
        metadata: PhotoMetadata,
    ): Registered =
        call(jsonRequest("PUT", url("v1", "photos", id), metadata)) { response ->
            Registered(decode(response), created = response.code == HTTP_CREATED)
        }

    data class Registered(
        val photo: Photo,
        val created: Boolean,
    )

    suspend fun putPreview(
        id: String,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Photo = putFile(url("v1", "photos", id, "preview"), file, "image/jpeg", onProgress)

    suspend fun putOriginal(
        id: String,
        file: File,
        type: OriginalType,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Photo = putFile(url("v1", "photos", id, "original"), file, type.mimeType, onProgress)

    suspend fun setNote(
        id: String,
        note: String?,
    ): Photo = send("PATCH", url("v1", "photos", id), NotePatch(note))

    suspend fun photos(
        limit: Int = MAX_PAGE,
        before: String? = null,
        since: Instant? = null,
    ): PhotoPage {
        val url =
            url("v1", "photos")
                .newBuilder()
                .addQueryParameter("limit", limit.toString())
                .apply { if (before != null) addQueryParameter("before", before) }
                .apply { if (since != null) addQueryParameter("since", since.toString()) }
                .build()
        return get(url)
    }

    suspend fun photo(id: String): Photo = get(url("v1", "photos", id))

    /** Where a photo's preview is; fetching it as a phone doesn't mark it viewed. */
    fun previewUrl(id: String): HttpUrl = url("v1", "photos", id, "preview")

    fun originalUrl(id: String): HttpUrl = url("v1", "photos", id, "original")

    /** Downloads a preview or original into [target], replacing what was there. */
    suspend fun download(
        url: HttpUrl,
        target: File,
    ) = call(
        Request
            .Builder()
            .url(url)
            .get()
            .build(),
    ) { response ->
        val partial = File(target.parentFile, "${target.name}.part")
        partial.outputStream().use { output -> response.body.byteStream().use { it.copyTo(output) } }
        if (!partial.renameTo(target)) throw IOException("Couldn't move the download into place")
        response.header("Content-Type")
    }

    suspend fun delete(id: String) {
        call(
            Request
                .Builder()
                .url(url("v1", "photos", id))
                .delete()
                .build(),
        ) {}
    }

    /**
     * `GET /v1/events` as a flow. It ends with an [IOException] or
     * [ApiException] when the stream breaks; reconnecting is up to the caller.
     */
    fun events(): Flow<ServerEvent> =
        callbackFlow {
            val request =
                authorize(Request.Builder().url(url("v1", "events")).header("Accept", "text/event-stream"))
                    .get()
                    .build()
            val listener =
                object : EventSourceListener() {
                    override fun onOpen(
                        eventSource: EventSource,
                        response: Response,
                    ) {
                        trySend(ServerEvent.Connected)
                    }

                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String,
                    ) {
                        val event =
                            try {
                                parseEvent(type, data)
                            } catch (error: SerializationException) {
                                close(IOException("The server sent an event the app can't read", error))
                                return
                            } catch (error: IllegalArgumentException) {
                                close(IOException("The server sent an event the app can't read", error))
                                return
                            }
                        if (event != null) trySend(event)
                    }

                    override fun onClosed(eventSource: EventSource) {
                        close(IOException("The server closed the event stream"))
                    }

                    override fun onFailure(
                        eventSource: EventSource,
                        t: Throwable?,
                        response: Response?,
                    ) {
                        val error =
                            if (response != null && !response.isSuccessful) {
                                errorOf(response)
                            } else {
                                t ?: IOException("The event stream broke")
                            }
                        close(error)
                    }
                }
            val source = EventSources.createFactory(client).newEventSource(request, listener)
            awaitClose { source.cancel() }
        }

    internal fun parseEvent(
        type: String?,
        data: String,
    ): ServerEvent? =
        when (type) {
            "photo" -> ServerEvent.PhotoChanged(GlimpseJson.decodeFromString(Photo.serializer(), data))

            "photo-deleted" -> ServerEvent.PhotoDeleted(GlimpseJson.decodeFromString(DeletedEvent.serializer(), data).id)

            "waiting" -> ServerEvent.Waiting(GlimpseJson.decodeFromString(WaitingEvent.serializer(), data).count)

            // Newer servers may add events; older apps ignore them.
            else -> null
        }

    private fun url(vararg segments: String): HttpUrl =
        baseUrl
            .newBuilder()
            .apply { segments.forEach(::addPathSegment) }
            .build()

    private suspend inline fun <reified T> get(url: HttpUrl): T =
        call(
            Request
                .Builder()
                .url(url)
                .get()
                .build(),
        ) { decode(it) }

    private suspend inline fun <reified B, reified T> send(
        method: String,
        url: HttpUrl,
        body: B,
        authorized: Boolean = true,
    ): T = call(jsonRequest(method, url, body), authorized) { decode(it) }

    private inline fun <reified B> jsonRequest(
        method: String,
        url: HttpUrl,
        body: B,
    ): Request =
        Request
            .Builder()
            .url(url)
            .method(method, GlimpseJson.encodeToString(serializer<B>(), body).toRequestBody(jsonType))
            .build()

    private suspend fun putFile(
        url: HttpUrl,
        file: File,
        contentType: String,
        onProgress: (Long, Long) -> Unit,
    ): Photo {
        val body = ProgressFileBody(file, contentType, onProgress)
        return call(
            Request
                .Builder()
                .url(url)
                .put(body)
                .build(),
        ) { decode(it) }
    }

    private inline fun <reified T> decode(response: Response): T = decode(serializer<T>(), response)

    private fun <T> decode(
        serializer: KSerializer<T>,
        response: Response,
    ): T =
        try {
            GlimpseJson.decodeFromString(serializer, response.body.string())
        } catch (error: SerializationException) {
            throw IOException("The server sent an answer the app can't read", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("The server sent an answer the app can't read", error)
        }

    private fun authorize(builder: Request.Builder): Request.Builder =
        if (token == null) builder else builder.header("Authorization", "Bearer $token")

    private suspend fun <T> call(
        request: Request,
        authorized: Boolean = true,
        read: (Response) -> T,
    ): T {
        val prepared = if (authorized) authorize(request.newBuilder()).build() else request
        val response = client.newCall(prepared).await()
        return response.use {
            withContext(Dispatchers.IO) {
                if (!it.isSuccessful) throw errorOf(it)
                read(it)
            }
        }
    }

    private fun errorOf(response: Response): ApiException {
        val detail =
            try {
                GlimpseJson.decodeFromString(ErrorBody.serializer(), response.body.string()).error
            } catch (_: Exception) {
                null
            }
        val tag = ApiErrorTag.of(detail?.tag).takeUnless { it == ApiErrorTag.Unknown } ?: tagFor(response.code)
        return ApiException(response.code, tag, detail?.message ?: "The server answered ${response.code}")
    }

    private fun tagFor(status: Int): ApiErrorTag =
        when (status) {
            400 -> ApiErrorTag.InvalidRequest
            401 -> ApiErrorTag.Unauthorized
            403 -> ApiErrorTag.Forbidden
            404 -> ApiErrorTag.NotFound
            409 -> ApiErrorTag.Conflict
            413 -> ApiErrorTag.TooLarge
            429 -> ApiErrorTag.RateLimited
            in 500..599 -> ApiErrorTag.ServerError
            else -> ApiErrorTag.Unknown
        }

    companion object {
        const val MAX_PAGE = 100
        private const val HTTP_CREATED = 201
    }
}

/** A file upload that reports how much of it has gone out. */
private class ProgressFileBody(
    private val file: File,
    contentType: String,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    private val type = contentType.toMediaType()

    override fun contentType() = type

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var sent = 0L
        file.source().use { source ->
            while (true) {
                val read = source.read(sink.buffer, CHUNK)
                if (read == -1L) break
                sink.flush()
                sent += read
                onProgress(sent, total)
            }
        }
    }

    companion object {
        private const val CHUNK = 64 * 1024L
    }
}

/** Runs a call without blocking a thread, and cancels it with the coroutine. */
private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            },
        )
        continuation.invokeOnCancellation { cancel() }
    }
