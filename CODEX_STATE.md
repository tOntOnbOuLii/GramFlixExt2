# Codex Session State

## Modifications recentes
- Config dynamique limitee au provider `1JOUR1FILM` (`https://1jour1film1025b.site/`) pour la phase de tests site par site.
- Paquet `gramflix-all.cs3` regenere (GramFlix v1.4.2 / plugin version 7) et copie dediee `gramflix-1jour1film.cs3`.
- `providers.json` (racine + assets) ne contient plus que l entree 1J1F.

## Tests / verifications
- OK `./gradlew.bat :app:make --rerun-tasks` (artefact 48055 octets).
- Verification manuelle : accueil `https://1jour1film1025b.site/` et recherche `?s=avatar` exploitables (`.item` + `.poster`).

## A faire / suivi
- Publier la release GitHub GramFlix v1.4.2 (remplacer `gramflix-all.cs3`, ajouter `gramflix-1jour1film.cs3` si souhaite).
- Tester dans Cloudstream (vider le cache de l extension, verifier accueil et recherche).
- Une fois 1J1F valide, lancer la meme sequence pour `anime-sama`.
- Supprimer `token.txt` apres les publications.

## Notes heritage
- Releases precedentes (v1.4.1 et anterieures) deja alignees sur plugin version 6.
