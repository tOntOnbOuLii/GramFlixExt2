GramFlix v1.4.2
---------------

- Configuration dynamique restreinte à `1JOUR1FILM` pour campagne de tests site par site (baseUrl `https://1jour1film1025b.site/`).
- Fourniture d'un paquet dédié (`gramflix-1jour1film.cs3`) + mise à jour de `gramflix-all.cs3`.
- Build validé via `gradlew :app:make` (artefact 48055 octets, plugin version 7).

GramFlix v1.4.1
---------------

- Fixed IMDB fallback playback by switching embeds to `https://vidsrc.net`, matching the Cloudstream VidSrc extractor.
- Rebuilt package via `gradlew :app:make` (artifact 48055 octets, plugin version 6).

GramFlix v1.4.0
---------------

- Added IMDB-powered fallback search (suggestion API) with direct `vidsrc` embeds when legacy scrapers return nothing.
- Home page now supports a curated IMDB carousel via `home.json` (assets + GitHub mirror) as a resilient fallback.
- Load/link pipeline enriched with IMDb metadata caching and dataUrl JSON payload (slug + imdbId + poster/year).
- Packaging verified with `gradlew :app:make` (artifact 48052 octets, plugin version 5).

Historical Notes (v1.3.0)
-------------------------

- Search results ranked by normalized relevance (accents/stop-words cleaned) and deduplicated per provider.
- Home page extraction using resilient selectors, expanded attribute probing and provider slug tracking.
- Streaming links following `getxfield` AJAX flows to capture iframe/hoster URLs before extractor dispatch.

Legacy Notes
------------

- Added local fallback config at `app/src/main/assets/providers.json` preloaded by the plugin, with background refresh from a remote JSON (WebPanel/GitHub mirror).
- Implemented `RemoteConfig.primeFromAssets` and made `refreshFromNetwork` non-fatal.
- Wired `Plugin.load` to initialize remote config.
- Added Gradle wrapper files and toolchain settings to target JDK 17 (auto-download enabled).
