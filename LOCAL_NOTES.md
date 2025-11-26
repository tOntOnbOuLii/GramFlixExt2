# Notes locales (ne pas pousser)

## Changements récents
- Configs ramenées à 15 providers (racine + assets + webpanel local). Webpanel ignoré par git.
- Hosters enrichis : ups2up, luluvid, savefiles, hglink, alias VOE (christopheruntilpoint.com), alias FileLions (dintezuvio.com).
- AnimeSama : parsing `episodes.js` (eps1/eps2…), swap lecteurs, support `episode`/`reader` dans `LoadData` et `loadLinks`.
- FrenchStream : règles mises à jour `.short` / `.short-title` / `.short-poster@href`, base URL `https://fs-miroir6.lol`.
- Builds : `./gradlew :app:make` → `gramflix-all.cs3` (151 658 octets). Plugins.json version 83, fileSize 151658.
- Push main effectué après corrections (rebase origin/main, version 83).

## URLs distantes (GitHub)
- providers.json/rules.json/hosters.json/home.json à la racine du repo.
- plugins.json → url `gramflix-all.cs3` sur main (version 83).

## À surveiller / prochain run
- Vérifier dans CloudStream que FrenchStream apparaît bien après purge de cache source (si souci, rechecker la structure `.short`).
- Webpanel reste uniquement local/FTP : ne pas le pousser (ignoré par .gitignore).
- Si Moiflix API / autres sites changent, nécessite peut-être recompilation.
