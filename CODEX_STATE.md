# Codex Session State

## Modifications recentes
- Synopses 1JOUR1FILM ajoutes (scraping + fallback WordPress) pour alimenter les fiches Cloudstream.
- Accueil Cloudstream alimente via l'API WordPress (populaires, derniers films/series/saisons/episodes).
- Deplie les embeds `api.voirfilm.cam` pour exposer les miroirs (Uqload, Voe, Netu, Dood, ...) directement dans Cloudstream.
- Fallback DooPlay : bascule automatique sur `/wp-admin/admin-ajax.php` lorsque `dtAjax` est absent (1JOUR1FILM).
- Paquet(s) `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres pour GramFlix v1.4.6 (plugin version 11, 63971 octets).
- Config dynamique limitee au provider `1JOUR1FILM` (`https://1jour1film1025b.site/`) pour la phase de tests site par site.
- `providers.json` (racine + assets) ne contient plus que l'entree 1J1F.
- Integration du player 1J1F : parsing `dtAjax`, appels `doo_player_ajax`, support multi-sources et fallback home dedie pour alimenter l'accueil Cloudstream.

## Tests / verifications
- OK `./gradlew.bat :app:compileDebugKotlin`
- OK `./gradlew.bat :app:make` (artefacts 63971 octets, copies `gramflix-all.cs3` / `gramflix-1jour1film.cs3` mises a jour).
- Verification manuelle : accueil `https://1jour1film1025b.site/` et recherche `?s=avatar` exploitables (`.item` + `.poster`).

## A faire / suivi
- Release GitHub GramFlix v1.4.6 publiee (artefacts `gramflix-all.cs3` / `gramflix-1jour1film.cs3`, plugin 11).
- Mettre a jour les releases v1.4.2/v1.4.3 si toujours necessaire (remplacement artefacts uniquement).
- Tester dans Cloudstream (vider le cache de l'extension, verifier accueil, recherche et lecture multi-sources).
- Une fois 1J1F valide, lancer la meme sequence pour `anime-sama`.
- Supprimer `token.txt` apres les publications.

## Notes heritage
- Releases precedentes (v1.4.1 et anterieures) deja alignees sur plugin version 6.
