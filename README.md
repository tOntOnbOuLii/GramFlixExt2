GramFlix Cloudstream Extensions (Skeleton)

Objectif
- Dépôt local prêt pour des extensions Cloudstream.
- Résolution dynamique des URLs via un JSON hébergé sur `https://tafili.fr` pour éviter de recompiler lors des changements de domaine.

Etat
- Squelette Gradle/Android + classes Kotlin de base.
- Chargeur de configuration distante (pointant sur `https://cs.tafili.fr/providers.json`) et 32 providers squelettes connectés à cette config.
- En attente de ta liste des 32 sites (NOM + URL) pour générer les stubs.

Idée d’architecture
- Un fichier JSON distant (ex: `https://tafili.fr/cs/providers.json`) expose une map `{ "provider_key": { "name": "...", "baseUrl": "..." } }`.
- Chaque provider Cloudstream lit son `baseUrl` dynamiquement à partir de cette config. Ainsi, changement de domaine = mise à jour du JSON.
- Optionnel phase 2: un moteur "config-driven" si tes sites partagent le même template, pour ajouter de nouveaux sites seulement via JSON (sans recompiler). Sinon, ajouter un site au code reste nécessaire.

Prochaines étapes
1) Donne-moi:
   - Liste des 32 sites (nom lisible + URL actuelle + un identifiant court/slug recommandé).
   - Préfixe de package Kotlin (ex: `com.gramflix.extensions`) si tu veux autre chose.
2) Je génère 32 providers squelettes liés à la config distante.
3) Tu publies `providers.json` sur `https://tafili.fr` (voir plus bas) et tu pourras changer les domaines à chaud.

Spécification JSON distante (proposée)
```json
{
  "version": 1,
  "providers": {
    "example": {
      "name": "Example Site",
      "baseUrl": "https://example.com"
    }
  }
}
```

Hébergement sur tafili.fr
- Emplacement suggéré: `https://tafili.fr/cs/providers.json` (tu peux changer le chemin si besoin, on l’ajustera dans le code).
- Publication: via FTP, uploade simplement le fichier JSON à l’emplacement choisi.

Notes de build
- Ce dépôt utilise la structure standard d’une extension Cloudstream (module `app`).
- Les dépendances Cloudstream et Android Gradle Plugin sont déclarées, mais la compilation nécessite un environnement Android/Gradle configuré et l’accès réseau.
- Pour l’instant, concentre-toi sur la liste des sites; on finalisera l’implémentation après.

Panel d’admin (cs.tafili.fr)
- Page `Providers`: modifie noms + URLs des sites, publie `providers.json`.
- Page `Hosters`: modifie la liste des hébergeurs de lecture, publie `hosters.json`.
- Les changements sauvent en `data/*.json` et publient des copies Web à la racine.
- Optionnel: synchro GitHub (commits automatiques) — configurer dans `tafili.fr/config.php`.

Publication GitHub (repo.json)
- `repo.json` d’exemple dans `GramFlixExt2/repo.json`. Mets à jour OWNER/URL de release.
- Workflows CI: `build.yml` (artefacts) et `release.yml` (release sur tag `v*`).
- Le plugin Cloudstream est activé; la tâche `:app:packageReleasePlugin` produit le `.cs3`.

Guide d’utilisation rapide
- Changer une URL de site: connecte-toi → `Providers` → modifie → Enregistrer. L’extension lit `https://cs.tafili.fr/providers.json`.
- Changer/ajouter un hoster: `Hosters` → modifie/ajoute → Enregistrer. L’extension peut lire `https://cs.tafili.fr/hosters.json`.
- Activer synchro GitHub: édite `tafili.fr/config.php`, mets `GITHUB_SYNC_ENABLED=true` et un `GITHUB_TOKEN` avec accès au repo `GramFlixExt2`.
- Publier une release `.cs3`: pousse un tag `v0.1.0` → GitHub Actions build + crée la release et y attache le `.cs3`.
