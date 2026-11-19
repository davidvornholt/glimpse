package io.github.davidvornholt.glimpse.shared.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlimpseApiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var api: GlimpseApi

    @BeforeTest
    fun start() {
        server.start()
        api = GlimpseApi(client, server.url("/").toString(), token = "device-token")
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    private fun json(
        body: String,
        code: Int = 200,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun error(
        code: Int,
        tag: String,
        message: String = "No",
    ) = json("""{"error":{"tag":"$tag","message":"$message"}}""", code)

    private val device = """{"id":"dev_1","name":"Pixel 6","createdAt":"2026-11-18T09:00:00Z","lastSeenAt":null}"""

    @Test
    fun `sends the phone's token`() =
        runTest {
            server.enqueue(json("""{"kind":"device","device":$device}"""))

            val me = api.me()

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/me", request.url.encodedPath)
            assertEquals("Bearer device-token", request.headers["Authorization"])
            assertEquals("Pixel 6", me.device?.name)
        }

    @Test
    fun `reads the owner as well as a phone`() =
        runTest {
            server.enqueue(json("""{"kind":"owner"}"""))

            assertEquals(Me("owner", null), api.me())
        }

    @Test
    fun `claims a code without a token`() =
        runTest {
            server.enqueue(json("""{"token":"new-token","device":$device}""", code = 201))

            val result = GlimpseApi(client, server.url("/").toString(), token = null).claim("Kq3-v8_TzP", "Pixel 6")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/pairings/claim", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
            assertEquals("""{"code":"Kq3-v8_TzP","deviceName":"Pixel 6"}""", request.body?.utf8())
            assertEquals("new-token", result.token)
            assertEquals("dev_1", result.device.id)
        }

    @Test
    fun `keeps a path in the server address`() =
        runTest {
            server.enqueue(json("""{"kind":"owner"}"""))

            GlimpseApi(client, server.url("/glimpse/").toString(), token = "device-token").me()

            assertEquals("/glimpse/v1/me", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `registers a photo and tells a new one from a known one`() =
        runTest {
            server.enqueue(json(testPhotoJson(), code = 201))
            server.enqueue(json(testPhotoJson()))
            val metadata = PhotoMetadata(TEST_TIME, PhotoSource.Scan, 2480, 3508, OriginalType.Png, batchId = "batch")

            val first = api.register(TEST_PHOTO_ID, metadata)
            val again = api.register(TEST_PHOTO_ID, metadata)

            assertTrue(first.created)
            assertFalse(again.created)
            assertEquals(TEST_PHOTO_ID, first.photo.id)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/v1/photos/$TEST_PHOTO_ID", request.url.encodedPath)
            assertEquals(
                """{"takenAt":"2026-11-18T09:30:00Z","source":"scan","width":2480,"height":3508,"originalType":"image/png","batchId":"batch"}""",
                request.body?.utf8(),
            )
        }

    @Test
    fun `sends a removed note as null`() =
        runTest {
            server.enqueue(json(testPhotoJson()))

            api.setNote(TEST_PHOTO_ID, null)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("""{"note":null}""", request.body?.utf8())
        }

    @Test
    fun `uploads a file with its type and reports progress`() =
        runTest {
            server.enqueue(json(testPhotoJson()))
            server.enqueue(json(testPhotoJson()))
            val file = folder.newFile("preview.jpg").apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
            val progress = mutableListOf<Pair<Long, Long>>()

            api.putPreview(TEST_PHOTO_ID, file) { sent, total -> progress += sent to total }
            api.putOriginal(TEST_PHOTO_ID, file, OriginalType.Webp)

            val preview = server.takeRequest()
            assertEquals("PUT", preview.method)
            assertEquals("/v1/photos/$TEST_PHOTO_ID/preview", preview.url.encodedPath)
            assertEquals("image/jpeg", preview.headers["Content-Type"])
            assertEquals(200_000, preview.body?.size)
            assertEquals(200_000L to 200_000L, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a.first < b.first })
            val original = server.takeRequest()
            assertEquals("/v1/photos/$TEST_PHOTO_ID/original", original.url.encodedPath)
            assertEquals("image/webp", original.headers["Content-Type"])
        }

    @Test
    fun `asks for a page of photos`() =
        runTest {
            server.enqueue(json("""{"photos":[${testPhotoJson(viewedAt = "2026-11-18T09:31:00Z")}],"nextCursor":"next"}"""))

            val page = api.photos(limit = 20, before = "cursor", since = Instant.parse("2026-11-01T00:00:00Z"))

            val url = server.takeRequest().url
            assertEquals("/v1/photos", url.encodedPath)
            assertEquals("20", url.queryParameter("limit"))
            assertEquals("cursor", url.queryParameter("before"))
            assertEquals("2026-11-01T00:00:00Z", url.queryParameter("since"))
            assertEquals("next", page.nextCursor)
            assertEquals(Instant.parse("2026-11-18T09:31:00Z"), page.photos.single().viewedAt)
        }

    @Test
    fun `downloads into place`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "image/jpeg")
                    .body("jpeg bytes")
                    .build(),
            )
            val target = folder.root.resolve("preview.jpg")

            val type = api.download(api.previewUrl(TEST_PHOTO_ID), target)

            assertEquals("image/jpeg", type)
            assertEquals("jpeg bytes", target.readText())
            assertEquals(listOf("preview.jpg"), folder.root.list()?.toList())
            assertEquals("/v1/photos/$TEST_PHOTO_ID/preview", server.takeRequest().url.encodedPath)
        }

    @Test
    fun `downloads the same photo twice at once`() =
        runTest {
            val body = "jpeg bytes ".repeat(DOWNLOAD_REPEATS)
            repeat(2) {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(body)
                        .throttleBody(DOWNLOAD_CHUNK, DOWNLOAD_PAUSE_MILLIS, TimeUnit.MILLISECONDS)
                        .build(),
                )
            }
            val target = folder.root.resolve("glimpse-$TEST_PHOTO_ID.jpg")

            withContext(Dispatchers.IO) {
                listOf(
                    async { api.download(api.originalUrl(TEST_PHOTO_ID), target) },
                    async { api.download(api.originalUrl(TEST_PHOTO_ID), target) },
                ).awaitAll()
            }

            assertEquals(body, target.readText())
            assertEquals(listOf(target.name), folder.root.list()?.toList())
        }

    @Test
    fun `reads the error tag the server sends`() =
        runTest {
            server.enqueue(error(404, "NotFound", "No photo with this id"))

            val error = assertFailsWith<ApiException> { api.photo(TEST_PHOTO_ID) }

            assertEquals(404, error.status)
            assertEquals(ApiErrorTag.NotFound, error.tag)
            assertEquals("No photo with this id", error.message)
            assertFalse(error.isTransient)
        }

    @Test
    fun `falls back to the status when the body has no tag it knows`() =
        runTest {
            server.enqueue(error(409, "SomethingNew"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(413)
                    .body("<html>Request Entity Too Large</html>")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(502).build())

            assertEquals(ApiErrorTag.Conflict, assertFailsWith<ApiException> { api.delete(TEST_PHOTO_ID) }.tag)
            assertEquals(ApiErrorTag.TooLarge, assertFailsWith<ApiException> { api.delete(TEST_PHOTO_ID) }.tag)
            val gateway = assertFailsWith<ApiException> { api.delete(TEST_PHOTO_ID) }
            assertEquals(ApiErrorTag.ServerError, gateway.tag)
            assertTrue(gateway.isTransient)
        }

    @Test
    fun `treats an answer it can't read as a network failure`() =
        runTest {
            server.enqueue(json("""{"unexpected":true}"""))

            assertFailsWith<IOException> { api.photo(TEST_PHOTO_ID) }
        }

    @Test
    fun `checks the server's health without failing`() =
        runTest {
            server.enqueue(json("""{"ok":true}"""))
            server.enqueue(error(503, "ServerError"))

            assertTrue(api.health())
            assertFalse(api.health())
            assertNull(server.takeRequest().headers["Authorization"])
        }

    @Test
    fun `reads each event the server sends`() {
        assertEquals(
            ServerEvent.PhotoChanged(testPhoto(viewedAt = TEST_TIME)),
            api.parseEvent("photo", testPhotoJson(viewedAt = "2026-11-18T09:30:00Z")),
        )
        assertEquals(ServerEvent.PhotoDeleted(TEST_PHOTO_ID), api.parseEvent("photo-deleted", """{"id":"$TEST_PHOTO_ID"}"""))
        assertEquals(ServerEvent.Waiting(2), api.parseEvent("waiting", """{"count":2}"""))
        assertNull(api.parseEvent("something-new", "{}"))
    }

    @Test
    fun `streams events and skips the ones it doesn't know`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        "event: waiting\ndata: {\"count\":1}\n\n" +
                            ": keep-alive\n\n" +
                            "event: something-new\ndata: {}\n\n" +
                            "event: photo\ndata: ${testPhotoJson(viewedAt = "2026-11-18T09:30:00Z").replace("\n", "")}\n\n",
                    ).build(),
            )

            val events = api.events().take(3).toList()

            assertEquals(
                listOf(ServerEvent.Connected, ServerEvent.Waiting(1), ServerEvent.PhotoChanged(testPhoto(viewedAt = TEST_TIME))),
                events,
            )
            val request = server.takeRequest()
            assertEquals("/v1/events", request.url.encodedPath)
            assertEquals("Bearer device-token", request.headers["Authorization"])
            assertEquals("text/event-stream", request.headers["Accept"])
        }

    @Test
    fun `keeps every event of a burst while the app is busy`() =
        runTest {
            val burst = (1..200).joinToString("") { "event: waiting\ndata: {\"count\":$it}\n\n" }
            server.enqueue(
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(burst)
                    .build(),
            )

            val events =
                api
                    .events()
                    .onEach { event ->
                        // The feed fetches its list after connecting, while the stream keeps arriving.
                        if (event == ServerEvent.Connected) Thread.sleep(BUSY_MILLIS)
                    }.take(201)
                    .toList()

            assertEquals(listOf<ServerEvent>(ServerEvent.Connected) + (1..200).map { ServerEvent.Waiting(it) }, events)
        }

    @Test
    fun `ends the event stream with the server's refusal`() =
        runTest {
            server.enqueue(error(401, "Unauthorized"))

            val error = assertFailsWith<ApiException> { api.events().toList() }

            assertEquals(ApiErrorTag.Unauthorized, error.tag)
        }
}

private const val BUSY_MILLIS = 500L

private const val DOWNLOAD_REPEATS = 2000

private const val DOWNLOAD_CHUNK = 4096L

private const val DOWNLOAD_PAUSE_MILLIS = 20L
