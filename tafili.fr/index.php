<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

$title = defined('PANEL_TITLE') ? PANEL_TITLE : 'GramFlix Webpanel';

if (isset($_GET['logout'])) {
    logout();
    header('Location: ' . (defined('PANEL_BASE_URL') ? PANEL_BASE_URL : '/'));
    exit;
}

$error = null;

if (!is_authenticated()) {
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'login') {
        $username = $_POST['username'] ?? '';
        $password = $_POST['password'] ?? '';
        if (login($username, $password)) {
            header('Location: ' . (defined('PANEL_BASE_URL') ? PANEL_BASE_URL : '/?view=dashboard'));
            exit;
        }
        $error = 'Identifiants invalides.';
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
    <body class="auth-body">
    <main class="container auth-container">
        <section class="card auth-card neon-card">
            <div class="auth-hero">
                <img src="assets/logo.jpg" alt="GramFlix" class="auth-logo">
                <div>
                    <p class="auth-kicker">Console GramFlix</p>
                    <h1><?= htmlspecialchars($title) ?></h1>
                    <p class="muted">Mettez a jour vos providers et hosters en temps reel.</p>
                </div>
            </div>
            <h2>Connexion</h2>
            <?php if ($error): ?>
                <p class="alert alert-error"><?= htmlspecialchars($error) ?></p>
            <?php endif; ?>
            <form method="post" class="form-grid">
                <input type="hidden" name="action" value="login">
                <label>
                    Identifiant
                    <input type="text" name="username" autocomplete="username" required>
                </label>
                <label>
                    Mot de passe
                    <input type="password" name="password" autocomplete="current-password" required>
                </label>
                <button type="submit" class="btn btn-primary">Se connecter</button>
            </form>
        </section>
    </main>
    </body>
    </html>
    <?php
    exit;
}

$isAdmin = is_admin();

$providersData = read_json('providers.json', ['version' => 1, 'providers' => []]);
$hostersData = read_json('hosters.json', ['version' => 1, 'hosters' => []]);
$usersData = $isAdmin ? sanitize_users(load_users()) : [];

$view = $_GET['view'] ?? 'dashboard';
$allowedViews = ['dashboard', 'providers', 'hosters', 'account'];
if ($isAdmin) {
    $allowedViews[] = 'users';
}
if (!in_array($view, $allowedViews, true)) {
    $view = 'dashboard';
}

$providersCount = count($providersData['providers'] ?? []);
$hostersCount = count($hostersData['hosters'] ?? []);

$currentRecord = current_user_record();
$currentUser = $currentRecord ? [
    'username' => $currentRecord['username'],
    'displayName' => $currentRecord['displayName'] ?? ucfirst($currentRecord['username']),
    'role' => $currentRecord['role'] ?? 'editor',
] : null;

$initialState = [
    'csrf' => csrf_token(),
    'max' => defined('MAX_PROVIDERS') ? (int) MAX_PROVIDERS : 32,
    'currentUser' => $currentUser,
    'isAdmin' => $isAdmin,
    'providers' => $providersData,
    'github' => [
        'enabled' => github_enabled(),
        'owner' => defined('GITHUB_OWNER') ? (string) GITHUB_OWNER : null,
        'repo' => defined('GITHUB_REPO') ? (string) GITHUB_REPO : null,
        'branch' => defined('GITHUB_BRANCH') ? (string) GITHUB_BRANCH : 'main',
    ],
];

if ($view === 'hosters') {
    $initialState['hosters'] = $hostersData;
}
if ($view === 'users' && $isAdmin) {
    $initialState['users'] = $usersData;
}
if ($view === 'account') {
    // rien de plus a injecter pour l'instant
}

include __DIR__ . '/partials/header.php';

$viewPath = __DIR__ . '/views/' . $view . '.php';
if (is_file($viewPath)) {
    include $viewPath;
} else {
    echo '<section class="card"><p>Vue introuvable.</p></section>';
}

include __DIR__ . '/partials/footer.php';
