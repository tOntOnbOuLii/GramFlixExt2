# Codex Session State

## Modifications recentes
- Entetes navigateur (User-Agent, Accept, Accept-Language) forces sur les appels WordPress/AJAX pour reafficher l'accueil 1J1F dans Cloudstream.
- Paquet(s) `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres pour GramFlix v1.4.8 (plugin version 13, 66145 octets).
- Typage dynamique des listes (WordPress + fallback) pour distinguer films, series et anime dans Cloudstream.
- Sections d'accueil 1J1F alimentees directement via l'API WordPress (populaires, derniers films/series/saisons/episodes) avec titres ASCII.
- Release GitHub GramFlix v1.4.8 publiee (artefacts `gramflix-all.cs3` / `gramflix-1jour1film.cs3`, plugin 13).
- Synopses 1JOUR1FILM ajoutes (scraping + fallback WordPress) pour alimenter les fiches Cloudstream.
- Deplie les embeds `api.voirfilm.cam` pour exposer les miroirs (Uqload, Voe, Netu, Dood, ...) directement dans Cloudstream.
- Fallback DooPlay : bascule automatique sur `/wp-admin/admin-ajax.php` lorsque `dtAjax` est absent (1JOUR1FILM).
- Config dynamique limitee au provider `1JOUR1FILM` (`https://1jour1film1025b.site/`) pour la phase de tests site par site.
- `providers.json` (racine + assets) ne contient plus que l'entree 1J1F.
- Integration du player 1J1F : parsing `dtAjax`, appels `doo_player_ajax`, support multi-sources et fallback home dedie pour alimenter l'accueil Cloudstream.

## Tests / verifications
- OK `./gradlew.bat :app:compileDebugKotlin`
- OK `./gradlew.bat :app:make` (artefacts 66145 octets, copies `gramflix-all.cs3` / `gramflix-1jour1film.cs3` mises a jour).
- Verification manuelle : accueil `https://1jour1film1025b.site/` et recherche `?s=avatar` exploitables (`.item` + `.poster`).

## A faire / suivi
- Mettre a jour les releases v1.4.2/v1.4.3 si toujours necessaire (remplacement artefacts uniquement).
- Tester dans Cloudstream (vider le cache de l'extension, verifier accueil, recherche et lecture multi-sources).
- Une fois 1J1F valide, lancer la meme sequence pour `anime-sama`.
- Prevoir un nouveau token GitHub si autre publication necessaire (fichier local supprime).

## Notes heritage
- Releases precedentes (v1.4.1 et anterieures) deja alignees sur plugin version 6.

