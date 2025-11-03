GramFlix v1.4.0

- Accueil : repli offline via un carrousel IMDB (JSON hébergé) lorsque les scrapers dynamiques échouent.
- Recherche : fallback automatique sur l'API de suggestion IMDB avec liens directs `vidsrc` (imdb://).
- Lecture : génération d'embed `https://vidsrc.vip` pour les titres IMDB (dataUrl JSON enrichi).
- Build : `:app:make` (nouvelle taille 48052 octets, version 5 dans `plugins.json`).
