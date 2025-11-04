GramFlix v1.4.7
---------------

- Accueil Cloudstream alimente directement via l'API WordPress (sections populaires et derniers ajouts 1JOUR1FILM).
- Typage serie/film pour les listes WordPress et le fallback afin d'afficher correctement les contenus.
- Rebuilt package (`:app:make`, artefact 65305 octets, plugin version 12).

GramFlix v1.4.6
---------------

- Recupere les synopsis 1JOUR1FILM (scraping + fallback API WordPress) pour Cloudstream.
- Accueil Cloudstream alimente par l'API WordPress : films/series populaires, derniers films, series, saisons et episodes.
- Rebuilt package (`:app:make`, artefact 63971 octets, plugin version 11).

GramFlix v1.4.5
---------------

- Deplie les pages embed `api.voirfilm.cam` pour exposer les miroirs (Uqload, Voe, Netu, Dood, etc.) dans Cloudstream.
- Priorise les sources reelles avant les trailers YouTube lorsque disponibles.
- Rebuilt package (`:app:make`, artefact 58499 octets, plugin version 10).

GramFlix v1.4.4
---------------

- Fallback `admin-ajax.php` pour recuperer les sources DooPlay lorsque `dtAjax` est absent (1JOUR1FILM et sites DooPlay similaires).
- Rebuilt package (`:app:make`, artefact 56947 octets, plugin version 9).

GramFlix v1.4.3
---------------

- Support dynamic players for 1JOUR1FILM via `doo_player_ajax`, enabling source selection in Cloudstream.
- Home page fallback now scrapes 1JOUR1FILM sections when remote rules return empty results.
- Rebuilt package (`:app:make`, artefact 48055 octets, plugin version 8).

GramFlix v1.4.2
---------------

- Configuration dynamique restreinte a `1JOUR1FILM` pour tests site par site (baseUrl `https://1jour1film1025b.site/`).
- Fourniture d'un paquet dedie (`gramflix-1jour1film.cs3`) + mise a jour de `gramflix-all.cs3`.
- Build valide via `gradlew :app:make` (artefact 48055 octets, plugin version 7).

GramFlix v1.4.1
---------------

- Fixed IMDB fallback playback by switching embeds to `https://vidsrc.net`, matching the Cloudstream VidSrc extractor.
- Rebuilt package via `gradlew :app:make` (artefact 48055 octets, plugin version 6).

GramFlix v1.4.0
---------------

- Added IMDB-powered fallback search with direct `vidsrc` embeds when legacy scrapers return nothing.
- Home page now supports a curated IMDB carousel via `home.json` as a resilient fallback.
- Load/link pipeline enriched with IMDb metadata caching and dataUrl JSON payload (slug + imdbId + poster/year).
- Packaging verified with `gradlew :app:make` (artefact 48052 octets, plugin version 5).

Historical Notes (v1.3.0)
-------------------------

- Search results ranked by normalized relevance and deduplicated per provider.
- Home page extraction using resilient selectors, expanded attribute probing and provider slug tracking.
- Streaming links following `getxfield` AJAX flows to capture iframe/hoster URLs before extractor dispatch.

Legacy Notes
------------

- Added local fallback config a `app/src/main/assets/providers.json` preloaded par le plugin, avec rafraichissement en arriere-plan depuis un JSON distant.
- Implemented `RemoteConfig.primeFromAssets` et rendu `refreshFromNetwork` non fatal.
- Wired `Plugin.load` pour initialiser la config distante.
- Ajout des fichiers Gradle wrapper et toolchain pour cibler JDK 17 (telechargement automatique).
