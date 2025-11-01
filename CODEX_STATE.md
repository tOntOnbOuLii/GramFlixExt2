# Codex Session State

## Actions performed
- Audited repository structure and documentation.
- Added asset-backed `providers.json` and `rules.json` for offline defaults.
- Implemented remote config loading with GitHub fallback (providers, hosters, rules).
- Registered `ConfigDrivenProvider` with heuristic scraping support.
- Updated web panel (hosters/providers/rules) to sync with GitHub and avoid domain mentions.
- Added Gradle wrapper, CI workflows (build/release), and GitHub Actions integration.
- Iteratively adjusted CI (SDK installs, LF endings, wrapper jar, toolchains) and triggered tags up to `v0.2.1`.
- Investigated build failures locally; aligned toolchain to AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.9 with JDK 17.
- Added explicit compile-time dependencies for Cloudstream MPP (`library`, `library-jvm`) plus NiceHttp.
- Reworked `ConfigDrivenProvider` to Kotlin 2.1 APIs (URI-based search building, new response builders, extractor loader).
- Annotated the plugin entry-point with `@CloudstreamPlugin`, updated packaging flow, and verified `:app:make` produces `app/build/app.cs3`.

## Outstanding issues
- `repo.json` still cible `releases/latest`; créer une release contenant `gramflix-all.cs3` avant de communiquer l’URL.
- Kotlin warnings subsistent dans les helpers config (`optString` nullable); à corriger si l’on active des règles plus strictes.

## Next intended steps
- Publier un tag (ex: `v1.0.0`) après génération locale de `app/build/app.cs3`, puis mettre en ligne l’asset dans la release GitHub.
- Vérifier/mettre à jour `repo.json` et la version hébergée pour pointer vers l’URL de l’asset nouvellement publié.
- Nettoyer les avertissements de nullabilité sur les configs pour faciliter des builds plus stricts.
