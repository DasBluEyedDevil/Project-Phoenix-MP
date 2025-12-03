package com.devil.phoenixproject

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import androidx.compose.ui.window.ComposeUIViewController

/**
 * Creates the main UIViewController for iOS that hosts the Compose Multiplatform UI.
 * This is called from Swift via: MainViewControllerKt.MainViewController()
 */
fun MainViewController() = ComposeUIViewController {
    ensureImageLoader()
    App()
}

/**
 * Mirrors the Android Application image loader setup so Coil uses Ktor and crossfade on iOS too.
 * setSafe prevents re-initialization if the controller is recreated.
 */
private fun ensureImageLoader() {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory())
            }
            .crossfade(true)
            .logger(DebugLogger())
            .build()
    }
}
