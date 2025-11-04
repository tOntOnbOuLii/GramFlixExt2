GramFlix v1.4.6

- Ajoute la récupération automatique des synopsis via la page et l’API WordPress pour alimenter les fiches Cloudstream.
- Alimente l’accueil Cloudstream en direct depuis l’API 1JOUR1FILM (populaires, derniers films/séries/saisons/épisodes).
- Build : `:app:make` (plugin version 11, artefact 63971 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` régénérés).

GramFlix v1.4.5

- Déplie les players `api.voirfilm.cam` pour récupérer automatiquement les miroirs (Uqload, Voe, Netu, Dood, etc.) côté Cloudstream.
- Priorise les hébergeurs complets avant la bande-annonce YouTube lorsque plusieurs sources sont disponibles.
- Build : `:app:make` (plugin version 10, artefact 58499 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` régénérés).

GramFlix v1.4.4

- Fallback automatique vers `/wp-admin/admin-ajax.php` pour récupérer les sources DooPlay quand `dtAjax` n'est pas exposé.
- Build : `:app:make` (plugin version 9, artefact 56947 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` régénérés).

GramFlix v1.4.3

- Ajoute la recuperation AJAX des players `doo_player_ajax` pour 1JOUR1FILM afin de proposer toutes les sources.
- Fallback accueil dedie pour 1JOUR1FILM lorsque les regles distantes ne renvoient rien.
- Build : `:app:make` (plugin version 8, artefact 48055 octets, `gramflix-all.cs3` et `gramflix-1jour1film.cs3` mis a jour).

GramFlix v1.4.2

- Configuration dynamique limitee a `1JOUR1FILM` (`https://1jour1film1025b.site/`) pour la phase de tests site par site.
- Build : `:app:make` (plugin version 7, artefact 48055 octets, `gramflix-all.cs3` + `gramflix-1jour1film.cs3`).

GramFlix v1.4.1

- Lecture fallback IMDB : alignement sur `https://vidsrc.net` pour eviter les ouvertures `webpanel.invalid` et rester compatible avec l extracteur Cloudstream.
- Build : `:app:make` (nouvelle taille 48055 octets, version 6 dans `plugins.json`).
