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
$password = (string)($_POST['password'] ?? '');
$role = $_POST['role'] === 'admin' ? 'admin' : 'user';

if ($username === '' || $password === '') {
    header('Location: /users.php?err=' . urlencode('Nom et mot de passe requis.'));
    exit;
}

if (!preg_match('/^[A-Za-z0-9._-]{3,32}$/', $username)) {
    header('Location: /users.php?err=' . urlencode('Nom invalide (3-32 alphanum, . _ -).'));
    exit;
}

$users = load_users();
if (find_user($users, $username)) {
    header('Location: /users.php?err=' . urlencode('Utilisateur déjà existant.'));
    exit;
}

$hash = password_hash($password, PASSWORD_DEFAULT);
$user = [
    'username' => $username,
    'passwordHash' => $hash,
    'role' => $role,
    'createdAt' => time(),
];
$users[] = $user;
if (!save_users($users)) {
    header('Location: /users.php?err=' . urlencode('Erreur d’enregistrement. Vérifiez les permissions FTP.'));
    exit;
}

header('Location: /users.php?ok=' . urlencode('Utilisateur ajouté.'));
exit;

