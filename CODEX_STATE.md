# Codex Session State

## Modifications recentes
- Configuration dynamique réduite au provider `1JOUR1FILM` pointant vers `https://1jour1film1025b.site/`.
- `gramflix-1jour1film.cs3` généré (48 055 octets) à partir de `app/build/app.cs3` pour tests ciblés site par site.
- `providers.json` (racine + assets) ne contient plus que l'entrée 1J1F.

## Tests / vérifications
- OK `./gradlew.bat :app:make --rerun-tasks` → artefact Cloudstream régénéré.
- Vérification manuelle : page d'accueil `https://1jour1film1025b.site/` et recherche `?s=avatar` renvoient des cartes exploitables (sélecteur `.item` / `.poster`).

## À faire / suivi
- Publier sur GitHub les JSON et l'artefact `gramflix-1jour1film.cs3`, puis valider dans Cloudstream (vider le cache de l'extension, tester accueil + recherche).
- Après validation de 1J1F, répéter la procédure pour `anime-sama`.
- Supprimer `token.txt` une fois toutes les publications terminées.

## Notes héritées
- Historique release `GramFlix v1.4.1` (plugin v6) disponible dans les commits précédents : `gramflix-all.cs3`, `plugins.json`, `CHANGES.md`, `release_notes.md` déjà alignés et publiés.
