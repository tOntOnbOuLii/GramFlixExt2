<?php
require_once __DIR__ . '/../config.php';

function ensure_data_dir(): void {
    if (!is_dir(DATA_DIR)) {
        @mkdir(DATA_DIR, 0755, true);
    }
    // Protect data directory via .htaccess (for Apache)
    $ht = DATA_DIR . '/.htaccess';
    if (!file_exists($ht)) {
        @file_put_contents($ht, "Deny from all\n");
    }
}

function load_users(): array {
    ensure_data_dir();
    if (!file_exists(USERS_FILE)) {
        return [];
    }
    $raw = @file_get_contents(USERS_FILE);
    if ($raw === false || $raw === '') return [];
    $json = json_decode($raw, true);
    if (!is_array($json)) return [];
    return $json['users'] ?? [];
}

function save_users(array $users): bool {
    ensure_data_dir();
    $payload = json_encode(['users' => array_values($users)], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    $tmp = USERS_FILE . '.tmp';
    if (@file_put_contents($tmp, $payload, LOCK_EX) === false) return false;
    return @rename($tmp, USERS_FILE);
}

function find_user(array $users, string $username): ?array {
    foreach ($users as $u) {
        if (strcasecmp($u['username'] ?? '', $username) === 0) return $u;
    }
    return null;
}

function upsert_user(array $users, array $user): array {
    $found = false;
    for ($i = 0; $i < count($users); $i++) {
        if (strcasecmp($users[$i]['username'] ?? '', $user['username']) === 0) {
            $users[$i] = $user;
            $found = true;
            break;
        }
    }
    if (!$found) $users[] = $user;
    return $users;
}

function delete_user(array $users, string $username): array {
    $res = [];
    foreach ($users as $u) {
        if (strcasecmp($u['username'] ?? '', $username) !== 0) {
            $res[] = $u;
        }
    }
    return $res;
}

function initialize_default_admin(): void {
    $users = load_users();
    if (count($users) > 0) return; // already initialized

    $hash = password_hash(INITIAL_ADMIN_PASSWORD, PASSWORD_DEFAULT);
    $admin = [
        'username' => INITIAL_ADMIN_USERNAME,
        'passwordHash' => $hash,
        'role' => 'admin',
        'createdAt' => time(),
    ];
    save_users([$admin]);
}

