<?php
if (!isset($view)) {
    $view = 'dashboard';
}
?>
<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= htmlspecialchars($title) ?></title>
    <link rel="icon" type="image/png" href="favicon.png">
    <link rel="stylesheet" href="assets/style.css">
</head>
<body class="app-body">
<main class="container">
    <header class="header">
        <div class="brand">
            <img src="assets/logo.jpg" alt="GramFlix" class="brand-logo">
            <div>
                <p class="brand-kicker">Console GramFlix</p>
                <h1><?= htmlspecialchars($title) ?></h1>
                <?php if (!empty($currentUser)): ?>
                    <p class="muted">Connecte en tant que <strong><?= htmlspecialchars($currentUser['displayName']) ?></strong> (<?= htmlspecialchars($currentUser['role']) ?>)</p>
                <?php endif; ?>
            </div>
        </div>
        <nav class="nav">
            <a href="?view=dashboard" class="nav-link <?= $view === 'dashboard' ? 'active' : '' ?>">Dashboard</a>
            <a href="?view=providers" class="nav-link <?= $view === 'providers' ? 'active' : '' ?>">Providers</a>
            <a href="?view=hosters" class="nav-link <?= $view === 'hosters' ? 'active' : '' ?>">Hosters</a>
            <?php if ($isAdmin): ?>
                <a href="?view=users" class="nav-link <?= $view === 'users' ? 'active' : '' ?>">Utilisateurs</a>
            <?php endif; ?>
            <a href="?view=account" class="nav-link <?= $view === 'account' ? 'active' : '' ?>">Mon compte</a>
            <form method="get" class="nav-logout">
                <button type="submit" name="logout" value="1" class="btn btn-link">Se deconnecter</button>
            </form>
        </nav>
    </header>
    <div id="global-alerts" class="global-alerts"></div>
