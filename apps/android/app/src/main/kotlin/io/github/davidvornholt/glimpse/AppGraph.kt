package io.github.davidvornholt.glimpse

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.api.isOnServer
import io.github.davidvornholt.glimpse.shared.haptics.Haptics
import io.github.davidvornholt.glimpse.shared.image.Gallery
import io.github.davidvornholt.glimpse.shared.image.ImageProcessor
import io.github.davidvornholt.glimpse.shared.photos.PhotoFeed
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import io.github.davidvornholt.glimpse.shared.settings.SettingsStore
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureStore
import io.github.davidvornholt.glimpse.shared.upload.DeleteScheduler
import io.github.davidvornholt.glimpse.shared.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration

/** The app's long-lived objects, made once per process. */
class AppGraph(
    context: Context,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** For work a screen starts that must finish even if the screen goes, then tells the user. */
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(Duration.ofSeconds(15))
            // The event stream sends a comment every 20 s, so a quiet minute means it broke.
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(60))
            .build()

    val sessions = SessionStore(context, scope)
    val settings = SettingsStore(context, scope)
    val captures = CaptureStore(File(context.filesDir, "captures"))
    val uploads = UploadScheduler(context)
    val deletes = DeleteScheduler(context)
    val haptics = Haptics(context)
    val feed = PhotoFeed(sessions, ::api, scope)
    val processor = ImageProcessor(context.contentResolver)
    val gallery = Gallery(context.contentResolver)
    val library =
        PhotoLibrary(
            captures = captures,
            undoStore =
                PreferenceDataStoreFactory.create {
                    context.preferencesDataStoreFile("undo")
                },
            uploads = uploads,
            deletes = deletes,
            feed = feed,
            sessions = sessions,
            settings = settings,
            processor = processor,
            gallery = gallery,
            ids = UlidGenerator(),
            apiFor = ::api,
            cacheDir = context.cacheDir,
            scope = scope,
        )

    /** For Coil: previews from the server need the phone's token too. */
    val imageHttp: OkHttpClient =
        http
            .newBuilder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val session = (sessions.state.value as? SessionState.Paired)?.session
                    if (session != null && isOnServer(request.url, session.server) && request.header("Authorization") == null) {
                        chain.proceed(request.newBuilder().header("Authorization", "Bearer ${session.token}").build())
                    } else {
                        chain.proceed(request)
                    }
                },
            ).build()

    fun api(session: Session) = GlimpseApi(http, session.server, session.token)

    /** The API before pairing, which only health checks and claims use. */
    fun anonymousApi(server: String) = GlimpseApi(http, server, token = null)
}
