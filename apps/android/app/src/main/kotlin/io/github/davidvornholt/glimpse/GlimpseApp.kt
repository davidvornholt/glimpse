package io.github.davidvornholt.glimpse

import android.app.Application
import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

class GlimpseApp :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Start CameraX now, so the viewfinder is up quickly when the activity opens.
        ProcessCameraProvider.getInstance(this)
        // Live updates run while the app is on screen; uploads run regardless.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = graph.feed.start()

                override fun onStop(owner: LifecycleOwner) = graph.feed.stop()
            },
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.imageHttp })) }
            .crossfade(true)
            .build()
}

val Context.graph: AppGraph get() = (applicationContext as GlimpseApp).graph
