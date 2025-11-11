# GramFlix Webpanel (tafili.fr)

Panel PHP responsive pour modifier les JSON consommes par les extensions GramFlix, gerer les utilisateurs et maintenir les hosters. Aucun framework requis : upload des fichiers et c'est pret. Le panneau embarque maintenant le branding GramFlix (logo + palette neon) ainsi qu'un check automatique de la disponibilite des URLs providers.

## Structure

```
tafili.fr/
├── assets/              # JS + CSS
├── data/                # JSON persistes (providers, hosters, users)
├── api.php              # Endpoints AJAX (providers/hosters/utilisateurs)
├── bootstrap.php        # Helpers communs (auth, CSRF, stockage JSON)
├── config.php           # Configuration par defaut (a surcharger)
├── config.local.php     # Overrides locaux (optionnel, gitignore)
└── index.php            # Interface web
```

## Installation

1. Copiez le dossier `tafili.fr` sur votre hebergement (FTP/SFTP, rsync...).
2. Ouvrez/creez `config.local.php` pour personnaliser le panneau :
   ```php
   <?php
   define('PANEL_TITLE', 'GramFlix Webpanel');
   define('DEFAULT_ADMIN_USERNAME', 'florian');
   define('DEFAULT_ADMIN_DISPLAY', 'Florian');
   define('DEFAULT_ADMIN_PASSWORD', '14061989');
   ```
   > Les valeurs ci-dessus servent uniquement a initialiser le premier compte. Connectez-vous puis changez le mot de passe immediatement.
3. Assurez-vous que PHP peut ecrire dans `tafili.fr/data/` (chmod 775/755 selon l'hebergement).
4. Rendez-vous sur `https://cs.tafili.fr/` (ou votre URL) et connectez-vous avec l'utilisateur `Florian`.

## Fonctionnalites

- Responsive (tableaux scrollables, boutons adaptatifs).
- Jusqu'a **32 providers** (1J1F + 31 autres).
- CRUD complet des hosters.
- Gestion des utilisateurs (ajout, edition, suppression, reset mot de passe).
- Chaque utilisateur peut modifier son mot de passe via le panneau.
- Mots de passe hashes (`password_hash`, `password_verify`).
- Bouton de synchronisation GitHub (met a jour `providers.json` / `hosters.json` sur le depot configure).
- Verification automatique des providers : le dashboard indique quelles URLs sont injoignables et vous notifie a la connexion.
- CSRF + sessions pour securiser les appels API.
- Ecriture atomique des JSON (`*.tmp` + rename) pour eviter la corruption.

## Synchronisation GitHub

Le panel modifie simplement les fichiers du dossier `data/`. Reutilisez votre script ou job existant (cron, bouton panel precedent, etc.) pour `git add/commit/push`.

## Personnalisation

- `PANEL_TITLE`, `PANEL_BASE_URL`, `MAX_PROVIDERS` : ajustez-les dans `config.local.php`.
- Les styles sont dans `assets/style.css` et le front dans `assets/app.js`.
- Vous pouvez remplacer `assets/logo.jpg` / `favicon.png` par vos propres elements si besoin.
- Pour creer un autre compte par defaut, maj `DEFAULT_ADMIN_*` avant le premier lancement (apres creation les utilisateurs sont conserves dans `data/users.json`).
