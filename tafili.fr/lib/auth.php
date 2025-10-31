<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/storage.php';

function start_secure_session(): void {
    if (session_status() === PHP_SESSION_ACTIVE) return;
    $params = session_get_cookie_params();
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => $params['path'] ?? '/',
        'domain' => $params['domain'] ?? '',
        'secure' => SESSION_SECURE,
        'httponly' => SESSION_HTTPONLY,
        'samesite' => SESSION_SAMESITE,
    ]);
    session_name(SESSION_NAME);
    session_start();
}

function current_user(): ?array {
    start_secure_session();
    return $_SESSION['user'] ?? null;
}

function require_login(): void {
    if (!current_user()) {
        header('Location: /index.php');
        exit;
    }
}

function require_admin(): void {
    $u = current_user();
    if (!$u || ($u['role'] ?? '') !== 'admin') {
        http_response_code(403);
        echo 'Forbidden';
        exit;
    }
}

function login_attempt(string $username, string $password): bool {
    $users = load_users();
    $user = find_user($users, $username);
    if (!$user) return false;
    $hash = $user['passwordHash'] ?? '';
    if (!is_string($hash) || $hash === '') return false;
    if (!password_verify($password, $hash)) return false;

    // Optional: rehash if needed
    if (password_needs_rehash($hash, PASSWORD_DEFAULT)) {
        $user['passwordHash'] = password_hash($password, PASSWORD_DEFAULT);
        $users = upsert_user($users, $user);
        save_users($users);
    }

    start_secure_session();
    session_regenerate_id(true);
    $_SESSION['user'] = [
        'username' => $user['username'],
        'role' => $user['role'] ?? 'user',
    ];
    return true;
}

function logout(): void {
    start_secure_session();
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $params = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000, $params['path'] ?? '/', $params['domain'] ?? '', SESSION_SECURE, SESSION_HTTPONLY);
    }
    session_destroy();
}

