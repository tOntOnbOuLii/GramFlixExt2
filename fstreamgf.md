Note : toujours repondre en francais.

# Etat FstreamGF (suivi local)

## Session du 06/12 matin
- FrenchStream : recherche basculee sur `index.php?do=search&subaction=search&story=<query>` avec fallback `?s=` et filtre des URLs /xfsearch. Domaine synchronise sur https://french-stream.pink (code + fstreamgf_links.json + providers.json).
- Build fstreamgf v21 compilee (manifest version 21, plugins.json mis a jour, fstreamgf.cs3 126044 du 06/12 03:07).
- Logs recents (logcat_2025_12_06_02_57.txt) : invokeSources SandStone/FrenchStream/Frembed/BlackInk pour serie The Abandons S1E4, recherches sur `?s=` renvoient 200 mais "No match found" -> aucun embed => seules sources SandStone visibles. Warnings checksum mismatch fstreamgf.2114172613.cs3.
- Home/screens : seules sources "SandStone Streamup VF" (HD et VF+VOSTFR) apparaissent.

## TODO immediat
- Retester en app avec v21 : verifier FSDBG FS search -> chosenUrl != null et embeds > 0 pour films/series.
- Si encore 0 resultat, capturer la page HTML de `/index.php?do=search...` cote app (verifier bloc `div.short`) et les headers/HTTP codes.
- Confirmer que le cache plugin se vide bien (version 21) si un checksum mismatch persiste.

## Fichiers touches (session)
- fstreamgf/src/main/java/com/lagradost/cloudstream3/FSTREAM_TO_UPDATE/mediaSources/FrenchStreamSource.kt (nouveau endpoint de recherche + fallback).
- fstreamgf/build.gradle.kts (cloudstream version 21 explicite).
- manifest.json (version 21), plugins.json (FstreamGF version 21, fileSize 126044).
- fstreamgf.cs3 regenere et copie a la racine.
