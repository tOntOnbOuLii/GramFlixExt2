<?php
/**
 * Configuration par défaut du panel.
 * Copiez ces valeurs dans config.local.php pour les surcharger sans écraser ce fichier.
 */

declare(strict_types=1);

// Nom affiché dans la barre de titre et les pages
define('PANEL_TITLE', 'GramFlix Webpanel');

// URL absolue où le panel sera accessible (utilisé pour les liens et les redirections)
define('PANEL_BASE_URL', '/');

// Nombre maximum de providers gérés via l’interface
define('MAX_PROVIDERS', 32);

// Utilisateur administrateur créé automatiquement si aucun compte n'existe.
define('DEFAULT_ADMIN_USERNAME', 'florian');
define('DEFAULT_ADMIN_DISPLAY', 'Florian');
define('DEFAULT_ADMIN_PASSWORD', '14061989');
