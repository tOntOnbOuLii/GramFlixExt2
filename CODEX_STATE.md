# Codex Session State

## Modifications récentes
- Provider dynamique renforcé (matching accent-insensible, tri par score, suivi des slugs, prise en charge AJAX `getxfield`).
- Filet de sécurité IMDB : fallback recherche/accueil via `home.json` + API `v2.sg.media-imdb.com`, lecture via embeds `vidsrc`.
- Nouvelle config `HomeConfig` (+ asset `home.json`) et initialisation dans `Plugin.kt`.
- Empaquetage `gramflix-all.cs3` (`gradlew :app:make`) → version 5, 48052 octets, publié en v1.4.0.
- Release GitHub `GramFlix v1.4.0` mise à jour avec notes et asset.

## Tests / vérifications
- ✅ `./gradlew.bat :app:compileReleaseKotlin`
- ✅ `./gradlew.bat :app:make`
- ✅ Téléchargement release (`Content-Length: 48052`).

## À faire / suivi
- Vérifier dans Cloudstream l’accueil fallback IMDB, la recherche (ex. "Dune") et la lecture `vidsrc`.
- Confirmer le chargement correct du dépôt (`repo.json`) après purge de cache côté Cloudstream.
- Nettoyer les avertissements de nullabilité (`optString`) si build strict souhaité.
- Retirer le `token.txt` local dès validation finale.
- Prévoir itérations futures pour enrichir la résolution directe des hébergeurs natifs.

## Guide WebPanel / Distribution
1. **Accès WebPanel** : se connecter au panneau (hébergé sur `tafili.fr`) avec les identifiants habituels. Les modules dédiés permettent d’éditer `providers.json`, `rules.json`, `hosters.json` et désormais `home.json`.
2. **Mise à jour des providers** :
   - Modifier l’URL ou le nom via l’interface, sauvegarder.
   - Déclencher le déploiement CDN (bouton « Publier ») pour pousser la version vers GitHub (`GramFlixExt2` → branche `main`).
   - Vérifier que le CDN renvoie bien la nouvelle valeur (`curl https://raw.githubusercontent.com/...`).
3. **Gestion des règles de scraping** :
   - Utiliser l’éditeur JSON du WebPanel (onglet « Rules »).
   - Ajuster `itemSel`, `titleSel`, `urlSel` ou `embedSel` pour un site donné.
   - Tester immédiatement via le module « Preview » : entrer une URL ou une requête, le WebPanel renvoie la structure extraite.
4. **Configuration `home.json`** :
   - Nouvel onglet « Home » : ajouter des sections (nom + liste d’éléments IMDB).
   - Chaque entrée doit contenir `title`, `imdbId`, `poster`, `year`; l’interface vérifie le format.
   - Déployer après modification pour alimenter le fallback d’accueil.
5. **Pipeline de publication** :
   - Une fois les JSON mis à jour, lancer `:app:make` localement si une nouvelle version de l’APK est prévue.
   - Mettre à jour `plugins.json` (numéro de version, taille) puis pousser sur GitHub via le WebPanel ou en local.
   - Créer/mettre à jour la release et remplacer l’asset `gramflix-all.cs3`.
6. **Cache Cloudstream** : suite à chaque publication, demander aux testeurs de supprimer le cache du dépôt dans l’application (Paramètres → Extensions → Vider le cache) pour forcer le rafraîchissement.
7. **Suivi des logs** : le WebPanel expose les logs récents (onglet « Activity ») pour vérifier que chaque ressource (`providers`, `rules`, `home`) a bien été servie en HTTP 200 après déploiement.

> Les descriptions ci-dessus restent locales : ne pas pousser ce fichier si des notes internes doivent rester privées.
