package com.gramflix.extensions

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.gramflix.extensions.config.RemoteConfig
import com.gramflix.extensions.config.HostersConfig
import com.gramflix.extensions.config.RulesConfig
import com.gramflix.extensions.config.HomeConfig

@Suppress("unused")
@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Prime remote-config from bundled asset so providers get sensible fallbacks.
        RemoteConfig.primeFromAssets(context)
        // Fire-and-forget network refresh (ignored if offline/unavailable).
        kotlin.concurrent.thread(isDaemon = true, name = "gf-rc-refresh") {
            RemoteConfig.refreshFromNetwork()
        }

        // Also warm up other remote configs
        HostersConfig.primeFromAssets(context)
        RulesConfig.primeFromAssets(context)
        HomeConfig.primeFromAssets(context)
        kotlin.concurrent.thread(isDaemon = true, name = "gf-hosters-refresh") {
            HostersConfig.refreshFromNetwork()
        }
        kotlin.concurrent.thread(isDaemon = true, name = "gf-rules-refresh") {
            RulesConfig.refreshFromNetwork()
        }
        kotlin.concurrent.thread(isDaemon = true, name = "gf-home-refresh") {
            HomeConfig.refreshFromNetwork()
        }

        // Providers registration will be added incrementally in dedicated classes.
        // Register the dynamic provider to make config-driven scraping available.
        registerMainAPI(com.gramflix.extensions.providers.ConfigDrivenProvider())
    }
}
