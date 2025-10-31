package com.gramflix.extensions.providers

// import com.lagradost.cloudstream3.MainAPI
// import com.lagradost.cloudstream3.TvType
// import com.lagradost.cloudstream3.utils.AppUtils
import com.gramflix.extensions.config.RemoteConfig

/**
 * Example dynamic provider.
 * Key used in remote JSON: "example"
 */
class ExampleProvider /*: MainAPI()*/ {
    private val key = "example"

    // In Cloudstream, these would be overrides:
    // override var name = RemoteConfig.getProviderNameOrNull(key, "Example Site") ?: "Example Site"
    // override var mainUrl = RemoteConfig.getProviderBaseUrlOrNull(key, "https://example.com") ?: "https://example.com"
    // override var lang = "fr"
    // override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TODO: implement search, load, home, etc. using mainUrl resolved above.
}

