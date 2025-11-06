# Codex Session State

## Modifications recentes
- Dechiffre et integre le player dismoiceline.uns.bio (Vidstack) pour recuperer HLS/MP4 et sous-titres directement dans Cloudstream.
- Paquet(s) gramflix-all.cs3 et gramflix-1jour1film.cs3 regeneres pour GramFlix v1.4.13 (plugin version 18, 68128 octets).
- Release GitHub GramFlix v1.4.13 prete (artefacts plugin 18) en attente de publication finale.
- Menu de selection d'accueil (MainPageData) expose 1JOUR1FILM (et futurs sites) dans Cloudstream.
- Fallback IMDB toujours disponible meme lorsque WordPress renvoie vide (HomeConfig.ensureLoaded + entree dediee).
- Note debug sources : doo_player_ajax renvoie Supervideo + uns.bio ; les autres miroirs (vidsrcme, savefiles...) sont injectes via l'iframe dismoiceline.uns.bio (player Vidstack).
- Paquet(s) gramflix-all.cs3 et gramflix-1jour1film.cs3 regeneres pour GramFlix v1.4.12 (plugin version 17, 68128 octets).
- Typage dynamique des listes (WordPress + fallback) pour distinguer films, series et anime dans Cloudstream.
- Sections d'accueil 1J1F alimentees directement via l'API WordPress (populaires, derniers films/series/saisons/episodes) avec titres ASCII.
- Release GitHub GramFlix v1.4.12 publiee (artefacts gramflix-all.cs3 / gramflix-1jour1film.cs3, plugin 17).
- Synopses 1JOUR1FILM ajoutes (scraping + fallback WordPress) pour alimenter les fiches Cloudstream.
- Deplie les embeds pi.voirfilm.cam pour exposer les miroirs (Uqload, Voe, Netu, Dood, ...) directement dans Cloudstream.
- Fallback DooPlay : bascule automatique sur /wp-admin/admin-ajax.php lorsque dtAjax est absent (1JOUR1FILM).
- Config dynamique limitee au provider 1JOUR1FILM (https://1jour1film1025b.site/).
- providers.json (racine + assets) ne contient plus que l'entree 1J1F.
- Integration du player 1J1F : parsing dtAjax, appels doo_player_ajax, support multi-sources et fallback home dedie pour alimenter l'accueil Cloudstream.

## Tests / verifications
- OK ./gradlew.bat :app:compileDebugKotlin
- OK ./gradlew.bat :app:make (artefacts 68128 octets, copies gramflix-all.cs3 / gramflix-1jour1film.cs3 maj, plugin 18).
- Verification manuelle : accueil https://1jour1film1025b.site/ et recherche ?s=avatar exploitables (.item + .poster).
- A realiser : lecture Cloudstream via source UnsBio (HLS + sous-titres) apres publication v1.4.13.

## A faire / suivi
- Mettre a jour les releases v1.4.2/v1.4.3 si toujours necessaire (remplacement artefacts uniquement).
- Publier la release GitHub v1.4.13 (artefacts plugin 18) et diffuser pour tests utilisateurs.
- Tester dans Cloudstream (vider le cache, verifier accueil, recherche et lecture UnsBio + autres sources).
- Une fois 1J1F valide, lancer la meme sequence pour nime-sama.
- Prevoir un nouveau token GitHub si autre publication necessaire (fichier local supprime).
- Monitorer la stabilite des endpoints /api/v1 UnsBio (cle/IV AES susceptibles d'evoluer).

## Notes heritage
- Releases precedentes (v1.4.1 et anterieures) deja alignees sur plugin version 6.
