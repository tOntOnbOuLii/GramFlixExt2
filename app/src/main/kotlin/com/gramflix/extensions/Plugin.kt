package com.gramflix.extensions

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@Suppress("unused")
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Providers registration will be added incrementally.
        // Packaging a valid plugin with zero providers to validate CI and installation.
    }
}
