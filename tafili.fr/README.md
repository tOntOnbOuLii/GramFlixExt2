# GramFlix Webpanel

Ce dossier contient la version PHP autonome du panneau d'administration GramFlix. Vous pouvez le zipper puis l'envoyer tel quel sur votre FTP : aucun framework ou composer n'est requis.

## Fonctionnalites

- CRUD des providers (slug/nom/URL) avec limite `MAX_PROVIDERS` configurable.
- CRUD des hosters.
- Edition des regles d'extraction (`rules.json`) pour piloter les selecteurs CSS utilises par l'extension CloudStream.
- Gestion des utilisateurs (admin/editor), reset et changement de mot de passe.
- Historique persistant (`data/history.json`) et journalisation dans `data/panel.log`.
- Diagnostics des droits d'ecriture sur `data/`.
- Synchronisation GitHub (PUT via l'API) pour `providers.json`, `hosters.json` et `rules.json` lorsque `GITHUB_*` est defini.

## Installation

1. Copiez/zippez le dossier `tafili.fr/` puis uploadez-le sur votre hebergement.
2. Dupliquez `config.local.example.php` en `config.local.php` et renseignez titre, identifiants, HTTPS, GitHub, etc.
3. Verifiez que PHP peut ecrire dans `tafili.fr/data/` (chmod 775 sur le dossier + 664 sur les fichiers).
4. Connectez-vous avec les identifiants `DEFAULT_ADMIN_*`, changez le mot de passe dans l'onglet "Mon compte".

## Structure

```
tafili.fr/
+-- assets/          # JS, CSS, logos
+-- data/            # JSON persistents (providers, hosters, rules, users, history) + logs
+-- partials/        # fragments PHP (header/footer/login)
+-- views/           # sections (dashboard, providers, hosters, rules, users, account)
+-- api.php          # endpoints AJAX (JSON)
+-- bootstrap.php    # Auth, stockage, helpers GitHub
+-- config.php       # valeurs par defaut
+-- index.php        # routeur principal + rendu HTML
+-- README.md
```

## Flux de travail

- `index.php` injecte `window.__PANEL_STATE__` consomme par `assets/app.js`.
- Chaque action JS (`save_*`, `github_sync`, utilisateurs...) poste sur `api.php` avec token CSRF.
- Les mutations persistantes alimentent `history.json` et les diagnostics.
- `panel_log()` ecrit les anomalies serveur dans `data/panel.log`.

Bon deploiement !
