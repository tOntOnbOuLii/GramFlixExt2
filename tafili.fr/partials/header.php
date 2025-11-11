<?php
$currentUser = $initialState['currentUser'] ?? null;
?>
<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= htmlspecialchars($title) ?></title>
    <link rel="stylesheet" href="assets/style.css">
    <link rel="icon" type="image/png" href="favicon.png">
</head>
<body class="app-shell">
<header class="app-header">
    <div class="app-header__inner container">
        <div class="brand">
            <img src="assets/logo.svg" alt="GramFlix" class="brand-mark" width="56" height="56" loading="lazy">
            <div class="brand-copy">
                <span class="brand-kicker">GramFlix</span>
                <span class="brand-title"><?= htmlspecialchars($title) ?></span>
            </div>
        </div>
        <button class="nav-toggle" type="button" data-nav-toggle aria-controls="panel-nav" aria-expanded="false">
            <span></span><span></span><span></span>
            <span class="sr-only">Ouvrir la navigation</span>
        </button>
        <nav class="app-nav" id="panel-nav">
            <a href="?view=dashboard" class="<?= $view === 'dashboard' ? 'active' : '' ?>">Dashboard</a>
            <a href="?view=providers" class="<?= $view === 'providers' ? 'active' : '' ?>">Providers</a>
            <a href="?view=hosters" class="<?= $view === 'hosters' ? 'active' : '' ?>">Hosters</a>
            <?php if ($initialState['isAdmin']): ?>
                <a href="?view=rules" class="<?= $view === 'rules' ? 'active' : '' ?>">Règles</a>
            <?php endif; ?>
            <?php if ($initialState['isAdmin']): ?>
                <a href="?view=users" class="<?= $view === 'users' ? 'active' : '' ?>">Utilisateurs</a>
            <?php endif; ?>
            <a href="?view=account" class="<?= $view === 'account' ? 'active' : '' ?>">Mon compte</a>
        </nav>
        <div class="user-meta">
            <span class="user-meta__name"><?= htmlspecialchars($currentUser['displayName'] ?? $currentUser['username'] ?? 'Utilisateur') ?></span>
            <a class="btn btn-sm" href="?logout=1">Deconnexion</a>
        </div>
    </div>
</header>
<main class="app-main container">
