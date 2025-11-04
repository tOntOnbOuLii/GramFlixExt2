# Codex Session State

## Modifications recentes
- Config dynamique limitee au provider `1JOUR1FILM` (`https://1jour1film1025b.site/`) pour la phase de tests site par site.
- Paquet(s) `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regenere(s) pour GramFlix v1.4.3 (plugin version 8, 48055 octets).
- `providers.json` (racine + assets) ne contient plus que l entree 1J1F.
- Integration du player 1J1F : parsing `dtAjax`, appels `doo_player_ajax`, support multi-sources et fallback home dedie pour alimenter l'accueil Cloudstream.

## Tests / verifications
- OK `./gradlew.bat :app:compileDebugKotlin`
- OK `./gradlew.bat :app:make` (artefacts 48055 octets, copies `gramflix-all.cs3` / `gramflix-1jour1film.cs3` mises a jour).
- Verification manuelle : accueil `https://1jour1film1025b.site/` et recherche `?s=avatar` exploitables (`.item` + `.poster`).

## A faire / suivi
- Publier la release GitHub GramFlix v1.4.2 (remplacer `gramflix-all.cs3`, ajouter `gramflix-1jour1film.cs3` si souhaite).
- Publier la release GitHub GramFlix v1.4.3 (remplacer `gramflix-all.cs3`, ajouter `gramflix-1jour1film.cs3` dedie si souhaite).
- Tester dans Cloudstream (vider le cache de l extension, verifier accueil, recherche et lecture multi-sources).
- Une fois 1J1F valide, lancer la meme sequence pour `anime-sama`.
- Supprimer `token.txt` apres les publications.

## Notes heritage
- Releases precedentes (v1.4.1 et anterieures) deja alignees sur plugin version 6.
