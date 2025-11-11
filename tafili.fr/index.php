<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

$title = defined('PANEL_TITLE') ? PANEL_TITLE : 'GramFlix Panel';

if (isset($_GET['logout'])) {
    logout();
    header('Location: ' . (defined('PANEL_BASE_URL') ? PANEL_BASE_URL : '/'));
    exit;
}

$error = null;
if (!is_authenticated() && $_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'login') {
    $username = trim((string) ($_POST['username'] ?? ''));
    $password = (string) ($_POST['password'] ?? '');
    if (!login($username, $password)) {
        $error = 'Identifiants invalides';
    } else {
        header('Location: ' . (defined('PANEL_BASE_URL') ? PANEL_BASE_URL : '/'));
        exit;
    }
}

if (!is_authenticated()) {
    include __DIR__ . '/partials/login.php';
    exit;
}

$view = $_GET['view'] ?? 'dashboard';
$allowedViews = ['dashboard', 'providers', 'hosters', 'rules', 'users', 'account'];
if (!in_array($view, $allowedViews, true)) {
    $view = 'dashboard';
}
if (!is_admin() && in_array($view, ['rules', 'users'], true)) {
    $view = 'dashboard';
}

$initialState = build_panel_state();
if (is_admin()) {
    $initialState['users'] = sanitize_users(load_users());
} else {
    $initialState['users'] = [];
}
$initialState['history'] = ['entries' => history_entries()];
$initialState['diagnostics'] = $initialState['diagnostics'] ?? [];
$initialState['currentUser'] = current_user_record();
$initialState['isAdmin'] = is_admin();

include __DIR__ . '/partials/header.php';

include __DIR__ . '/views/' . $view . '.php';

include __DIR__ . '/partials/footer.php';
