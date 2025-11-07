# Codex Session State

## Modifications recentes
- `providers.json` (racine, assets, webpanel/data) alimente maintenant 32 entrees : 1J1F + 31 sites listes dans `sites.txt`, prets a etre edites via le panel.
- Backend du panel : normalisation case-insensitive des slugs/providers et des cles hosters, prevention des doublons, conservation du slug original dans le JSON persiste.
- `config.local.php` mis a jour avec le nouveau token GitHub (utilise pour la synchro/Release).
- Release GramFlix v1.4.15 assemblee (plugin version 20, artefacts 75524 octets copies vers `gramflix-all.cs3` et `gramflix-1jour1film.cs3`).

## Tests / verifications
- `./gradlew.bat :app:clean :app:make` OK -> artefact `app/build/app.cs3` recopie vers `gramflix-all.cs3` + `gramflix-1jour1film.cs3` (75 524 o, plugin 20).
- Pas de tests Cloudstream in-app faute d'environnement (a faire une fois la release poussee).
- Synchronisation GitHub via panel/API non testee cote serveur (token pret).

## A faire / suivi
- Deployer la nouvelle version du panel (`tafili.fr/`), verifier droits d'ecriture sur `data/` et tester le bouton de synchro GitHub (providers/hosters).
- Verifier sur GitHub que la release v1.4.15 et les assets `.cs3` restent accessibles (retenter upload si le token expire).
- Verifier dans Cloudstream : selection du provider, accueil, recherche et lecture (UnsBio & autres hotes) pour chaque nouveau site critique.
- Etendre la sequence (tests + reglages regles) aux prochains sites (Anime-sama en priorite) et monitorer l'evolution des endpoints UnsBio.

## Notes heritage
- releases precedentes (v1.4.1 et anterieures) alignees sur plugin version 6.
