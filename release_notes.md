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
