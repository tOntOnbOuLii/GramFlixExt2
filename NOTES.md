## Fait

- Extension Cloudstream : support complet films + séries (TMDB/Nebryx, `newEpisode`, `newTvSeriesLoadResponse`) + build release testé.
- Webpanel : ajout éditeur de règles (`rules.json`), navigation sécurisée (admin only), synchro GitHub étendue, UI/README mis à jour.

## À faire

- Vérifier les écrans `hosters.php`, `providers.php`, `users.php` qui avaient déjà des modifications locales non liées à cette passe avant une prochaine synchro GitHub.
- Préparer un ZIP prêt à l’emploi du dossier `tafili.fr/` pour simplifier les uploads FTP récurrents.

## Session 2025-11-11

- Hosters importes depuis tafili.fr/hosters.txt, JSON publics mis a jour et plugin ajuste pour renommer automatiquement les sources dans Cloudstream.
- L'accueil GramFlix Dynamic retente plusieurs providers avant de basculer sur IMDB, ce qui devrait restaurer les sections des sites.
- Pour demain : repasser un test complet dans Cloudstream (accueil + recherche + lecture) et, si tout est bon, finaliser la synchro webpanel/FTP.
[2025-11-12 21:45] Release v1.4.16 publi�e (tag, notes, gramflix-all/1jour1film.cs3 attach�s). Rien touch� sur tafili.fr : les fichiers locaux restent � uploader manuellement si besoin.
[2025-11-12 22:10] Release v1.4.17 assemblee (hosters normalises, plugin version 22, gramflix-all.cs3 et gramflix-1jour1film.cs3 regeneres).
