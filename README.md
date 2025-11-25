# GramFlix pour CloudStream

Extension CloudStream dynamique (config distante + fallback embarque). Ce repo contient uniquement ce qui est necessaire au plugin et aux JSON consommes par le webpanel. Le webpanel lui-meme reste hors repo (FTP perso).

## Providers actifs (15)
- 1JOUR1FILM — https://1jour1film1125b.site
- AnimeSama — https://anime-sama.org (parse `episodes.js` pour extraire les lecteurs)
- CinePlateforme — https://www.cineplateforme.cc
- Cinepulse — https://riverlanes.site/cinepulse/
- CoFliX — https://coflix.foo
- Flemmix — https://flemmix.one
- FrenchStream — https://fs-miroir6.lol
- HDss — https://hdss.now
- MoiFliX — https://moiflix.org
- Nebryx — https://nebryx.fr
- PapaduStream — https://papadustream.garden
- Purstream — https://purstream.to
- SenpaiStream — https://senpai-stream.bio
- WowFilms — https://wowfilms1125b.site
- XalaFlix — https://xalaflix.in

## Config distante
Les JSON publics (providers/hosters/rules/home) sont a la racine et dupliques dans `app/src/main/assets` pour le fallback offline. Le webpanel ecrit dans ces JSON cote FTP puis un job GitHub peut les pousser.

## Build rapide
```bash
./gradlew :app:assembleRelease
```
Sortie : `app/build/outputs/aar/app-release.aar` (renommer en `.cs3` pour CloudStream si besoin).

## Notes
- Hosters ajoutes : `ups2up.fun`, `luluvid.com`, `savefiles.com`, `hglink.to`, alias VOE (`christopheruntilpoint.com`) et FileLions (`dintezuvio.com`).

