<?php
declare(strict_types=1);

require_once __DIR__ . '/bootstrap.php';

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

require_auth();

$action = $_GET['action'] ?? $_POST['action'] ?? null;

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    if ($action === 'state') {
        json_response(build_panel_state());
    }
    json_response(['error' => 'unknown_action'], 400);
}

$contentType = $_SERVER['CONTENT_TYPE'] ?? '';
$payload = [];
if (is_string($contentType) && stripos($contentType, 'application/json') !== false) {
    $raw = file_get_contents('php://input') ?: '';
    $decoded = json_decode($raw, true);
    if (is_array($decoded)) {
        $payload = $decoded;
    }
} else {
    $payload = $_POST;
}

$token = $payload['csrf'] ?? ($_SERVER['HTTP_X_CSRF_TOKEN'] ?? null);
if (!verify_csrf_token(is_string($token) ? $token : null)) {
    json_response(['error' => 'invalid_csrf'], 419);
}

$payloadAction = $payload['action'] ?? null;
if (is_string($payloadAction) && $payloadAction !== '') {
    $action = $payloadAction;
}

switch ($action) {
    case 'save_providers':
        save_providers($payload);
        break;
    case 'save_hosters':
        save_hosters($payload);
        break;
    case 'save_rules':
        save_rules($payload);
        break;
    case 'add_user':
        add_user($payload);
        break;
    case 'update_user':
        update_user($payload);
        break;
    case 'delete_user':
        delete_user($payload);
        break;
    case 'reset_password':
        reset_password($payload);
        break;
    case 'change_password':
        change_password($payload);
        break;
    case 'github_sync':
        github_sync_action();
        break;
    default:
        json_response(['error' => 'unknown_action'], 400);
}

die;

function ok(array $payload = [], bool $withHistory = true): void
{
    $payload['csrf'] = csrf_token();
    if ($withHistory) {
        $payload['history'] = [
            'entries' => history_entries(),
        ];
    }
    json_response($payload);
}

function save_providers(array $payload): void
{
    require_admin();

    $rows = $payload['providers'] ?? null;
    if (!is_array($rows)) {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $max = defined('MAX_PROVIDERS') ? (int) MAX_PROVIDERS : 32;
    $normalized = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $slug = normalize_slug((string) ($row['slug'] ?? ''));
        $name = trim((string) ($row['name'] ?? ''));
        $baseUrl = trim((string) ($row['baseUrl'] ?? ''));
        if ($slug === '' || $name === '' || $baseUrl === '') {
            continue;
        }
        if (!filter_var($baseUrl, FILTER_VALIDATE_URL)) {
            json_response(['error' => 'invalid_url', 'slug' => $slug], 422);
        }
        $normalized[$slug] = [
            'name' => $name,
            'baseUrl' => $baseUrl,
        ];
    }
    if (count($normalized) > $max) {
        json_response(['error' => 'limit_reached', 'max' => $max], 422);
    }

    $previous = read_json('providers.json', ['version' => 1, 'providers' => []]);
    $payload = [
        'version' => 1,
        'providers' => $normalized,
    ];
    write_json('providers.json', $payload);
    append_history_entry('providers_saved', 'Providers mis a jour', ['Providers actifs : ' . count($normalized)]);
    ok(['ok' => true, 'providers' => $payload]);
}

function save_hosters(array $payload): void
{
    require_admin();
    $rows = $payload['hosters'] ?? null;
    if (!is_array($rows)) {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $normalized = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $key = normalize_slug((string) ($row['key'] ?? ''));
        $name = trim((string) ($row['name'] ?? ''));
        $url = trim((string) ($row['url'] ?? ''));
        if ($key === '' || $name === '' || $url === '') {
            continue;
        }
        $normalized[$key] = [
            'name' => $name,
            'url' => $url,
        ];
    }
    $payload = [
        'version' => 1,
        'hosters' => $normalized,
    ];
    write_json('hosters.json', $payload);
    append_history_entry('hosters_saved', 'Hosters mis a jour', ['Hosters actifs : ' . count($normalized)]);
    ok(['ok' => true, 'hosters' => $payload]);
}

function save_rules(array $payload): void
{
    require_admin();
    $rows = $payload['rules'] ?? null;
    if (!is_array($rows)) {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $normalized = [];
    foreach ($rows as $row) {
        if (!is_array($row)) {
            continue;
        }
        $slug = normalize_slug((string) ($row['slug'] ?? ''));
        if ($slug === '') {
            continue;
        }
        $rule = [
            'searchPath' => trim((string) ($row['searchPath'] ?? '')),
            'searchParam' => trim((string) ($row['searchParam'] ?? '')),
            'itemSel' => trim((string) ($row['itemSel'] ?? '')),
            'titleSel' => trim((string) ($row['titleSel'] ?? '')),
            'urlSel' => trim((string) ($row['urlSel'] ?? '')),
            'embedSel' => trim((string) ($row['embedSel'] ?? '')),
        ];
        $hasValue = false;
        foreach ($rule as $value) {
            if ($value !== '') {
                $hasValue = true;
                break;
            }
        }
        if ($hasValue) {
            $normalized[$slug] = $rule;
        }
    }
    $rulesPayload = [
        'version' => 1,
        'rules' => $normalized,
    ];
    write_json('rules.json', $rulesPayload);
    append_history_entry('rules_saved', 'Regles mises a jour', ['Regles actives : ' . count($normalized)]);
    ok(['ok' => true, 'rules' => $rulesPayload]);
}

function add_user(array $payload): void
{
    require_admin();
    $username = normalize_username($payload['username'] ?? '');
    $display = trim((string) ($payload['displayName'] ?? ''));
    $password = (string) ($payload['password'] ?? '');
    $role = ($payload['role'] ?? 'editor') === 'admin' ? 'admin' : 'editor';
    if ($username === '' || $password === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }
    if (strlen($password) < 8) {
        json_response(['error' => 'password_too_short'], 422);
    }
    $users = load_users();
    if (isset($users[$username])) {
        json_response(['error' => 'user_exists'], 409);
    }
    $users[$username] = [
        'username' => $username,
        'displayName' => $display !== '' ? $display : ucfirst($username),
        'role' => $role,
        'passwordHash' => password_hash($password, PASSWORD_DEFAULT),
        'createdAt' => date('c'),
        'updatedAt' => date('c'),
    ];
    save_users($users);
    append_history_entry('user_added', 'Utilisateur ajoute', ['Utilisateur : ' . $username, 'Role : ' . $role]);
    ok(['ok' => true, 'users' => sanitize_users($users)]);
}

function update_user(array $payload): void
{
    require_admin();
    $username = normalize_username($payload['username'] ?? '');
    if ($username === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $users = load_users();
    if (!isset($users[$username])) {
        json_response(['error' => 'not_found'], 404);
    }
    $display = trim((string) ($payload['displayName'] ?? ''));
    $role = ($payload['role'] ?? 'editor') === 'admin' ? 'admin' : 'editor';
    $users[$username]['displayName'] = $display !== '' ? $display : ucfirst($username);
    $users[$username]['role'] = $role;
    $users[$username]['updatedAt'] = date('c');
    save_users($users);
    append_history_entry('user_updated', 'Utilisateur mis a jour', ['Utilisateur : ' . $username]);
    ok(['ok' => true, 'users' => sanitize_users($users)]);
}

function delete_user(array $payload): void
{
    require_admin();
    $username = normalize_username($payload['username'] ?? '');
    if ($username === '' || $username === current_user()) {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $users = load_users();
    if (!isset($users[$username])) {
        json_response(['error' => 'not_found'], 404);
    }
    unset($users[$username]);
    if (!array_filter($users, fn($u) => ($u['role'] ?? 'editor') === 'admin')) {
        json_response(['error' => 'last_admin'], 422);
    }
    save_users($users);
    append_history_entry('user_deleted', 'Utilisateur supprime', ['Utilisateur : ' . $username]);
    ok(['ok' => true, 'users' => sanitize_users($users)]);
}

function reset_password(array $payload): void
{
    require_admin();
    $username = normalize_username($payload['username'] ?? '');
    $password = (string) ($payload['password'] ?? '');
    if ($username === '' || $password === '' || strlen($password) < 8) {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $users = load_users();
    if (!isset($users[$username])) {
        json_response(['error' => 'not_found'], 404);
    }
    $users[$username]['passwordHash'] = password_hash($password, PASSWORD_DEFAULT);
    $users[$username]['updatedAt'] = date('c');
    save_users($users);
    append_history_entry('user_password_reset', 'Mot de passe reinitialise', ['Utilisateur : ' . $username]);
    ok(['ok' => true]);
}

function change_password(array $payload): void
{
    $current = (string) ($payload['currentPassword'] ?? '');
    $new = (string) ($payload['newPassword'] ?? '');
    if ($current === '' || $new === '' || strlen($new) < 8) {
        json_response(['error' => 'invalid_payload'], 400);
    }
    $username = current_user();
    if ($username === null) {
        json_response(['error' => 'auth_required'], 401);
    }
    $users = load_users();
    $user = $users[$username] ?? null;
    if (!$user || !password_verify($current, (string) ($user['passwordHash'] ?? ''))) {
        json_response(['error' => 'invalid_password'], 422);
    }
    $users[$username]['passwordHash'] = password_hash($new, PASSWORD_DEFAULT);
    $users[$username]['updatedAt'] = date('c');
    save_users($users);
    append_history_entry('user_password_changed', 'Mot de passe modifie', ['Utilisateur : ' . $username]);
    ok(['ok' => true]);
}

function github_sync_action(): void
{
    require_admin();
    if (!github_enabled()) {
        json_response(['error' => 'github_disabled'], 400);
    }
    try {
        $providers = read_json('providers.json', ['version' => 1, 'providers' => []]);
        $hosters = read_json('hosters.json', ['version' => 1, 'hosters' => []]);
        $rules = read_json('rules.json', ['version' => 1, 'rules' => []]);
        $files = [
            ['path' => 'providers.json', 'content' => json_encode($providers, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)],
            ['path' => 'hosters.json', 'content' => json_encode($hosters, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)],
            ['path' => 'rules.json', 'content' => json_encode($rules, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE)],
        ];
        $results = github_sync_files($files);
        $lines = array_map(static function ($item) {
            return sprintf('%s : %s', $item['path'] ?? '?', $item['status'] ?? '?');
        }, $results);
        append_history_entry('github_sync', 'Synchronisation GitHub', $lines);
        ok(['ok' => true, 'results' => $results]);
    } catch (Throwable $e) {
        panel_log_exception('github', $e);
        json_response(['error' => 'github_sync_failed', 'message' => $e->getMessage()], 500);
    }
}
