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
