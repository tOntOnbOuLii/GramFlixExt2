GramFlix Extensions Repo (public)

Contenu attendu dans ce repo GitHub:
- `repo.json` à la racine (index Cloudstream)
- Releases contenant l’artefact `.cs3` (plugin) construit depuis le projet Kotlin/Gradle

Exemple de `repo.json` minimal:
```json
{
  "name": "GramFlix Extensions",
  "plugins": [
    {
      "name": "GramFlix All-in-One",
      "packageName": "com.gramflix.extensions",
      "version": "0.1.0",
      "url": "https://github.com/OWNER/GramFlixExt2/releases/download/v0.1.0/gramflix-all.cs3",
      "iconUrl": "https://raw.githubusercontent.com/OWNER/GramFlixExt2/main/icon.png",
      "language": "fr",
      "tvTypes": ["Movie", "TvSeries", "Anime"]
    }
  ]
}
```

Remplace OWNER, version et nom du binaire selon ta release.

CI suggérée: workflow GitHub Actions qui build le module `app` → sort `.cs3` → crée une Release et met à jour `repo.json`.

