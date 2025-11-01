# Codex Session State

## Actions performed
- Audit initial structure + docs; added offline default assets (`providers.json`, `rules.json`).
- Implémenté la configuration distante avec fallback GitHub (providers, hosters, règles).
- Créé et enregistré `ConfigDrivenProvider` (heuristiques de scraping dynamiques).
- Ajouté Gradle wrapper + workflows GitHub Actions (build/release) et itéré jusqu’à une CI stable (toolchains, SDK, wrapper, fins tags v0.2.1).
- Montée de version outillage : AGP 8.7.3 / Kotlin 2.1.0 / Gradle 8.9, utilisation JDK 17.
- Refacto du provider vers les API Kotlin 2.1 + helpers extracteurs (`loadExtractor`, `newMovie*`).
- Annotations Cloudstream (`@CloudstreamPlugin`), packaging `:app:make`, alignement des dépendances sur le canal `pre-release`.
- Correctifs Windows : `gradlew.bat` respecte `JAVA_HOME`, suppression de `org.gradle.java.home`.
- Publication complète : commits poussés, tag `v1.0.0`, workflow « Release CS3 » réussi, asset `gramflix-all.cs3` en ligne (`releases/latest`).
- Nettoyage sécurité : suppression du PAT local, validation que l’URL `releases/latest/download/gramflix-all.cs3` répond (HTTP 200).
- Nettoyage dépôt : supprimé les dossiers auxiliaires `tafili.fr/` (web panel auto-hébergé) et `GramFlixExt2/` (copie statique) pour ne garder que l’extension Cloudstream.

## Outstanding issues
- Avertissements de nullabilité (`optString`) persistants dans les helpers de config.
- Pas encore de validation fonctionnelle côté client Cloudstream (installation depuis `repo.json`).

## Next intended steps
- Tester l’ajout du repo dans Cloudstream (lecture `repo.json`, téléchargement `gramflix-all.cs3`, vérif langue/icone).
- Corriger les helpers JSON pour supprimer les avertissements et pouvoir serrer les règles Kotlin.
- Monitorer la prochaine publication : si nouvelles extensions, incrémenter la version (`v1.0.x`) et relancer `:app:make`/release.
