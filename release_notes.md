GramFlix v1.4.15

- Ajoute les 31 providers supplementaires (Anime-Sama, BuzzMonclick, Cinepulse, etc.) via `providers.json` afin qu'ils soient pilotes a distance depuis le WebPanel.
- Revoit le WebPanel (`tafili.fr`) pour supporter 32 entrees avec normalisation des slugs et deduplication case-insensitive avant synchronisation GitHub.
- Build : `:app:make` (plugin version 20, artefact 75524 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.14

- Decode les scripts `dt_main_ajax` embarques en data:URI base64 pour restaurer `dtAjax` lorsque WordPress l'injecte dans un `<script src="data:">`.
- Normalise la lecture des attributs (`play_ajaxmd`, `class_item`) pour conserver les sources 1J1F dans Cloudstream.
- Build : `:app:make` (plugin version 19, artefact 75524 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.13

- Déchiffre les payloads `dismoiceline.uns.bio` (AES/CBC) afin d'exposer les flux HLS/MP4 et sous-titres directement dans Cloudstream.
- Ajoute la détection automatique des iframes UnsBio lors du chargement des liens.
- Build : `:app:make` (plugin version 18, artefact 68128 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.12

- Active explicitement l'accueil Cloudstream (`hasMainPage=true`) et expose 1JOUR1FILM / fallback IMDB dans le menu d'accueil.
- Force le chargement du fallback `home.json` (HomeConfig.ensureLoaded) pour afficher des sections meme lorsque la home distante renvoie vide.
- Build : `:app:make` (plugin version 17, artefact 68128 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.11

- Ajoute la selection de provider sur la page d'accueil (MainPageData) pour choisir 1JOUR1FILM et les futurs sites.
- Retourne uniquement les sections du provider demande et expose une entree fallback IMDB lorsque la home distante est vide.
- Build : `:app:make` (plugin version 16, artefact 68128 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.10

- Html home 1J1F recupere sans referer pour contourner les 403 Cloudflare et ajouter les sections fallback IMDB.
- Fallback `home.json` ajoute en complement des sections 1J1F (page 1) pour ne plus obtenir d'accueil vide dans Cloudstream.
- Build : `:app:make` (plugin version 15, artefact 67129 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.9

- Emule des entetes complets (Sec-Fetch, Origin, cache-control) sur les appels WordPress/Dooplay pour supprimer les retours vides.
- Bascule automatiquement sur le fallback `home.json` lorsque la configuration distante est manquante, garantissant au moins des sections IMDB.
- Build : `:app:make` (plugin version 14, artefact 67136 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.8

- Force des entetes navigateur (User-Agent, Accept, Accept-Language) sur les appels WordPress et AJAX pour debloquer l'accueil 1JOUR1FILM.
- Applique les memes entetes aux parsings fallback pour contourner les protections Cloudflare.
- Build : `:app:make` (plugin version 13, artefact 66145 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.7

- Accueil Cloudstream alimente directement via l'API WordPress (populaires, derniers films/series/saisons/episodes).
- Typage serie/film corrige pour les listes WordPress afin d'afficher correctement les contenus.
- Build : `:app:make` (plugin version 12, artefact 65305 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.6

- Recupere les synopsis 1JOUR1FILM (scraping + fallback API WordPress) pour Cloudstream.
- Accueil Cloudstream alimente par l'API WordPress : films/series populaires, derniers films, series, saisons et episodes.
- Build : `:app:make` (plugin version 11, artefact 63971 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.5

- Deplie les players `api.voirfilm.cam` pour recuperer automatiquement les miroirs (Uqload, Voe, Netu, Dood, etc.).
- Priorise les hebergeurs complets avant la bande-annonce YouTube lorsque plusieurs sources sont disponibles.
- Build : `:app:make` (plugin version 10, artefact 58499 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).

GramFlix v1.4.4

- Fallback automatique vers `/wp-admin/admin-ajax.php` pour recuperer les sources DooPlay quand `dtAjax` n'est pas expose.
- Build : `:app:make` (plugin version 9, artefact 56947 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` regeneres).


