package xyz.nulldev.androidcompat

import android.util.Log
import android.webkit.WebView
import xyz.nulldev.androidcompat.config.ApplicationInfoConfigModule
import xyz.nulldev.androidcompat.config.FilesConfigModule
import xyz.nulldev.androidcompat.config.SystemConfigModule
import xyz.nulldev.androidcompat.webkit.ManatanCefWebViewProvider
import xyz.nulldev.ts.config.GlobalConfigManager

/**
 * Initializes the Android compatibility module
 */
class AndroidCompatInitializer {
    fun init() {
        // Register config modules
        GlobalConfigManager.registerModules(
            FilesConfigModule.register(GlobalConfigManager.config),
            ApplicationInfoConfigModule.register(GlobalConfigManager.config),
            SystemConfigModule.register(GlobalConfigManager.config),
        )

        val providerName = System.getProperty("manatan.nativeWebViewProvider")?.trim()?.lowercase()
        val bridgeUrl = System.getProperty("manatan.nativeWebViewBridgeUrl")?.trim().orEmpty()

        if (
            bridgeUrl.isNotEmpty() ||
            providerName in
            setOf(
                "manatan-cef",
                "manatan-ios",
                "manatan-bridge",
                "manatan-wkwebview",
            )
        ) {
            WebView.setProviderFactory { view: WebView -> ManatanCefWebViewProvider(view) }
        } else {
            Log.w(
                "AndroidCompatInitializer",
                "No native WebView bridge configured; WebView-backed extensions will be unavailable",
            )
        }

        // Set some properties extensions use
        System.setProperty(
            "http.agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
        )
    }
}
