<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../lib/auth.php';
require_once __DIR__ . '/../lib/storage.php';
require_once __DIR__ . '/../lib/csrf.php';

require_admin();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: /users.php');
    exit;
}

$token = (string)($_POST['csrf'] ?? '');
if (!csrf_check($token)) {
    header('Location: /users.php?err=' . urlencode('Requête invalide.'));
    exit;
}

$username = trim($_POST['username'] ?? '');
if ($username === '') {
    header('Location: /users.php?err=' . urlencode('Nom requis.'));
    exit;
}

// Prevent deleting last admin
$users = load_users();
$target = find_user($users, $username);
if (!$target) {
    header('Location: /users.php?err=' . urlencode('Utilisateur introuvable.'));
    exit;
}

if (($target['role'] ?? 'user') === 'admin') {
    $adminCount = 0;
    foreach ($users as $u) {
        if (($u['role'] ?? 'user') === 'admin') $adminCount++;
    }
    if ($adminCount <= 1) {
        header('Location: /users.php?err=' . urlencode('Impossible de supprimer le dernier admin.'));
        exit;
    }
}

$users = delete_user($users, $username);
if (!save_users($users)) {
    header('Location: /users.php?err=' . urlencode('Erreur d’enregistrement.'));
    exit;
}

header('Location: /users.php?ok=' . urlencode('Utilisateur supprimé.'));
exit;

