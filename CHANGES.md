GramFlix v1.3.0
---------------

- Search results are now ranked by normalized relevance (accents/stop-words cleaned) and deduplicated per provider.
- Home page extraction uses resilient selectors, expanded attribute probing and records provider slugs for load steps.
- Streaming links now follow `getxfield` AJAX flows to grab the actual iframe/hoster URLs before invoking extractors.
- Packaging verified with `gradlew :app:make`.

Historical Notes
----------------

- Added local fallback config at `app/src/main/assets/providers.json` preloaded by the plugin, with background refresh from a remote JSON (WebPanel/GitHub mirror).
- Implemented `RemoteConfig.primeFromAssets` and made `refreshFromNetwork` non-fatal.
- Wired `Plugin.load` to initialize remote config.
- Added Gradle wrapper files and toolchain settings to target JDK 17 (auto-download enabled).

