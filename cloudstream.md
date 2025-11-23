# Notes Cloudstream / Nebryx / CoFliX

## Ce qui a ete fait
- Separation CoFliX/Nebryx : CoFliX scrappe son site (API apiflix + pages), Nebryx reste TMDB/watch.html.
- CoFliX valide (accueil, recherche, hosters OK).
- Nebryx : ajout d'un fallback extraction via Frembed (API + page) et VidSrc (imdb + tmdb) en secours.
- Utilisation de l'API Frembed pour hosters Nebryx : https://frembed.my/api/films?id={tmdbId}&idType=tmdb (link1..7, variantes vo/vostfr).
- Ajout d'un extractor ChristopherUntilPoint (cherche m3u8/source dans l'embed, en posant Referer/Origin/User-Agent sur christopheruntilpoint.com).
- Hosters Nebryx : dsvplay, netu.fremtv, uqload, streamtales, christopheruntilpoint (via API Frembed).
- Warm-up Frembed avant appel API pour recuperer cookies (GET sur api/film.php ou api/serie.php), puis appel api/films avec headers (Accept JSON, Origin frembed, X-Requested-With, UA).
- loadLinks : passe referer = lien hoster pour eviter 403 + essai multi-referers (embed Frembed/Nebryx) et fallback referer Frembed generique.
- Rebuilds : v54 (API hosters + extractor Christopher), v55 (endpoint api/films), v56 (warm-up cookies + headers). Commits : b63eb02, e311e48, 3be3bb5, f75d1d0.
- Rebuild v57 avec logs Nebryx (warm-up/api/films) pour diagnostiquer l'absence de liens.
- Rebuild v58 : force les referers Frembed/Nebryx sur chaque lien API (uqload/netu/dsvplay/streamtales) + ajout patterns hosters pour afficher les bons labels. Fonctionnel sur mobile + emulateur.
- Rebuild v59 : Home FrenchTVLive nettoyée (sections chaînes avec cartes au lieu de boutons "Lire" vides).
- Rebuild v60 : Player FrenchTVLive (fstv) : extraction iframe `/player/fsplayer.php?id=...`, détection m3u8/mp4 dans la page/iframe + fallback loadExtractor, referer fstv. Plugin 60 (artefacts 136336o).
- Rebuilds v61..v72 : corrections FrenchTVLive fsplayer, accueil 1J1F (API+HTML), optimisation pageSize/cache, extraction Cineplateforme (ajax xfield + cookies + mirrors), désactivation du fallback IMDB home, ajout Wiflix/FrenchStream dans providers, labels hosters Dood/Filemoon/Netu/VOE, versions/plugins.json mises à jour et artefacts .cs3 régénérés (~144210o).

## A verifier / a faire
- Nebryx : surveiller si certains liens Frembed tombent (403/Too short playback) -> logger l'extractor raté et referer utilisé.
- Si Frembed se rebloque : prevoir fallback (proxy serveur, autre source VidSrc si dispo).
- Ameliorer extractor ChristopherUntilPoint si le m3u8 n'est pas detecte (VOE/JS obfusque) : log + regex suppl.
- Nettoyer fichiers temporaires avant release finale.
- Revalider Nebryx en accueil/recherche pour la separation.
- Revalider hosters dsvplay/netu/uqload/streamtales si certains 403 persistent (headers/referer specifiques au besoin).
- FrenchTVLive : surveiller si certaines chaînes fsplayer nécessitent un referer/UA spécifique; logger si “aucun lien” revient.
- Cineplateforme : vérifier que tous les hosters (VOE/Netu/Filemoon/Vidoza/Dood) remontent rapidement sans Uqload (exclu pour éviter timeouts).
- Accueil : vérifier affichage titres Cineplateforme, sections Wiflix/FrenchStream présentes, plus de fallback IMDB.

## Rappels indispensables pour chaque session
- Toujours répondre en français.
- À chaque modification, incrémenter le numéro de version dans `plugins.json` et ajuster le `fileSize` en cohérence avec les artefacts.
- Si nécessaire, lancer le build (`:app:make`), copier `app/build/app.cs3` vers `gramflix-all.cs3` et `gramflix-1jour1film.cs3`, puis push sur GitHub.
