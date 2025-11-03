# Codex Session State

## Modifications recentes
- Fallback IMDB : embed migre vers `https://vidsrc.net` pour rester compatible avec l extractor VidSrc de Cloudstream.
- `gramflix-all.cs3` reconstruit (version 6, 48055 octets) et pret pour la release `GramFlix v1.4.1`.
- `plugins.json`, `CHANGES.md` et `release_notes.md` alignes sur la nouvelle version.

## Tests / verifications
- OK `./gradlew.bat :app:compileReleaseKotlin`
- OK `./gradlew.bat :app:make`
- OK Verification du nouvel artefact (`app/build/app.cs3` -> `gramflix-all.cs3`, 48055 octets).

## A faire / suivi
- Mettre a jour la release GitHub (`GramFlix v1.4.1`) avec le nouvel asset et les notes revisees.
- Tester dans Cloudstream : recherche IMDB (ex. "Avatar 2") puis lecture fallback pour confirmer le correctif.
- Supprimer `token.txt` une fois toutes les publications terminees.
- Envisager de corriger les avertissements de nullabilite (`optString`) si un build strict est souhaite.

## Guide WebPanel / Distribution
1. **Acces WebPanel** : se connecter sur `https://cs.tafili.fr` avec les identifiants habituels, onglets disponibles pour `providers.json`, `rules.json`, `hosters.json`, `home.json`.
2. **Mise a jour providers** : modifier via l interface, sauvegarder, cliquer sur "Publier" (push GitHub `GramFlixExt2/main`), verifier via curl que le CDN renvoie la nouvelle valeur.
3. **Regles de scraping** : utiliser l editeur JSON ("Rules"), ajuster `itemSel`, `titleSel`, `urlSel`, `embedSel`, tester via "Preview" pour valider la structure retournee.
4. **Configuration `home.json`** : onglet "Home", ajouter sections (`title`, `imdbId`, `poster`, `year`), publier pour alimenter le fallback d accueil.
5. **Pipeline de publication** : lancer `:app:make` pour chaque nouveau build, mettre a jour `plugins.json` (version, taille), pousser sur GitHub ou via le WebPanel, mettre a jour la release en remplacant `gramflix-all.cs3`.
6. **Cache Cloudstream** : apres chaque publication, demander aux testeurs de vider le cache de l extension (Parametres -> Extensions -> Vider le cache).
7. **Suivi des logs** : onglet "Activity" du WebPanel pour s assurer que `providers`, `rules`, `home` sont servis en HTTP 200 apres publication.

> Notes internes : conserver ce fichier localement (ne pas pousser si informations sensibles).
