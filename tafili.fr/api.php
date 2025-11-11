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
    switch ($action) {
        case 'state':
            $current = current_user_record();
            $state = [
                'providers' => read_json('providers.json', ['version' => 1, 'providers' => []]),
                'hosters' => read_json('hosters.json', ['version' => 1, 'hosters' => []]),
                'csrf' => csrf_token(),
                'max' => defined('MAX_PROVIDERS') ? (int) MAX_PROVIDERS : 32,
                'currentUser' => $current ? [
                    'username' => $current['username'],
                    'displayName' => $current['displayName'] ?? ucfirst($current['username']),
                    'role' => $current['role'] ?? 'editor',
                ] : null,
                'isAdmin' => is_admin(),
            ];
            if (is_admin()) {
                $state['users'] = sanitize_users(load_users());
            }
            json_response($state);
            break;
        default:
            json_response(['error' => 'unknown_action'], 400);
    }
}

$contentType = $_SERVER['CONTENT_TYPE'] ?? '';
$payload = [];
if (is_string($contentType) && str_contains($contentType, 'application/json')) {
    $raw = file_get_contents('php://input') ?: '';
    $decoded = json_decode($raw, true);
    if (is_array($decoded)) {
        $payload = $decoded;
    }
} else {
    $payload = $_POST;
}

$payloadAction = $payload['action'] ?? null;
if (is_string($payloadAction) && $payloadAction !== '') {
    $action = $payloadAction;
}

$token = $payload['csrf'] ?? ($_SERVER['HTTP_X_CSRF_TOKEN'] ?? null);
if (!verify_csrf_token(is_string($token) ? $token : null)) {
    json_response(['error' => 'invalid_csrf'], 419);
}

switch ($action) {
    case 'save_providers':
        save_providers($payload);
        break;
    case 'save_hosters':
        save_hosters($payload);
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
    case 'check_providers':
        check_providers_status();
        break;
    default:
        json_response(['error' => 'unknown_action'], 400);
}

function save_providers(array $payload): void
{
    $max = defined('MAX_PROVIDERS') ? (int) MAX_PROVIDERS : 32;
    $items = $payload['providers'] ?? null;
    if (!is_array($items)) {
        json_response(['error' => 'invalid_payload'], 400);
    }

    $normalized = [];
    $errors = [];
    foreach ($items as $row) {
        if (!is_array($row)) {
            continue;
        }
        $slug = isset($row['slug']) ? normalize_slug((string) $row['slug']) : '';
        $name = trim((string) ($row['name'] ?? ''));
        $baseUrl = trim((string) ($row['baseUrl'] ?? ''));

        if ($slug === '' || $name === '' || $baseUrl === '') {
            $errors[] = "Entree incomplete pour le slug \"{$row['slug']}\"";
            continue;
        }
        if (!filter_var($baseUrl, FILTER_VALIDATE_URL)) {
            $errors[] = "URL invalide pour \"$slug\"";
            continue;
        }
        $duplicateKey = strtolower($slug);
        if (isset($normalized[$duplicateKey])) {
            $errors[] = "Slug duplique : \"$slug\"";
            continue;
        }

        $normalized[$duplicateKey] = [
            'slug' => $slug,
            'data' => [
                'name' => $name,
                'baseUrl' => $baseUrl,
            ],
        ];
    }

    if (!empty($errors)) {
        json_response(['error' => 'validation_failed', 'details' => $errors], 422);
    }

    $count = count($normalized);
    if ($count > $max) {
        json_response(['error' => 'limit_exceeded', 'max' => $max], 422);
    }

    $providers = [];
    foreach ($normalized as $entry) {
        $providers[$entry['slug']] = $entry['data'];
    }

    $payload = [
        'version' => 1,
        'providers' => $providers,
    ];
    write_json('providers.json', $payload);
    json_response(['ok' => true, 'count' => $count, 'csrf' => csrf_token()]);
}

function check_providers_status(): void
{
    $data = read_json('providers.json', ['version' => 1, 'providers' => []]);
    $providers = is_array($data['providers'] ?? null) ? $data['providers'] : [];
    $results = [];
    foreach ($providers as $slug => $info) {
        $baseUrl = trim((string) ($info['baseUrl'] ?? ''));
        if ($baseUrl === '') {
            continue;
        }
        $probe = probe_provider_url($baseUrl);
        $results[] = [
            'slug' => $slug,
            'baseUrl' => $baseUrl,
            'ok' => $probe['ok'],
            'httpCode' => $probe['httpCode'],
            'message' => $probe['message'],
            'resolvedUrl' => $probe['resolvedUrl'],
        ];
    }

    json_response([
        'ok' => true,
        'items' => $results,
        'csrf' => csrf_token(),
    ]);
}

function save_hosters(array $payload): void
{
    $items = $payload['hosters'] ?? null;
    if (!is_array($items)) {
        json_response(['error' => 'invalid_payload'], 400);
    }

    $normalized = [];
    foreach ($items as $row) {
        if (!is_array($row)) {
            continue;
        }
        $key = isset($row['key']) ? strtolower(normalize_slug((string) $row['key'])) : '';
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
    json_response(['ok' => true, 'count' => count($normalized), 'csrf' => csrf_token()]);
}

function add_user(array $payload): void
{
    require_admin();

    $username = normalize_username($payload['username'] ?? '');
    $displayName = trim((string) ($payload['displayName'] ?? ''));
    $password = (string) ($payload['password'] ?? '');
    $role = normalize_role($payload['role'] ?? 'editor');

    if ($username === '' || $password === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }
    if (strlen($password) < 8) {
        json_response(['error' => 'password_too_short', 'min' => 8], 422);
    }

    $users = load_users();
    if (isset($users[$username])) {
        json_response(['error' => 'user_exists'], 409);
    }

    $now = date('c');
    $users[$username] = [
        'username' => $username,
        'displayName' => $displayName !== '' ? $displayName : ucfirst($username),
        'role' => $role,
        'passwordHash' => password_hash($password, PASSWORD_DEFAULT),
        'createdAt' => $now,
        'updatedAt' => $now,
    ];

    save_users($users);
    json_response([
        'ok' => true,
        'users' => sanitize_users($users),
        'csrf' => csrf_token(),
    ]);
}

function update_user(array $payload): void
{
    require_admin();

    $username = normalize_username($payload['username'] ?? '');
    if ($username === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }

    $displayName = trim((string) ($payload['displayName'] ?? ''));
    $role = normalize_role($payload['role'] ?? 'editor');

    $users = load_users();
    if (!isset($users[$username])) {
        json_response(['error' => 'not_found'], 404);
    }

    $users[$username]['displayName'] = $displayName !== '' ? $displayName : ucfirst($username);

    $previousRole = $users[$username]['role'] ?? 'editor';
    if ($previousRole !== $role) {
        if ($previousRole === 'admin' && $role !== 'admin' && count_admins($users) <= 1) {
            json_response(['error' => 'last_admin'], 422);
        }
        $users[$username]['role'] = $role;
        if (current_user() === $username) {
            $_SESSION['role'] = $role;
        }
    }

    $users[$username]['updatedAt'] = date('c');

    save_users($users);
    json_response([
        'ok' => true,
        'users' => sanitize_users($users),
        'csrf' => csrf_token(),
    ]);
}

function delete_user(array $payload): void
{
    require_admin();

    $username = normalize_username($payload['username'] ?? '');
    if ($username === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }

    if ($username === current_user()) {
        json_response(['error' => 'cannot_delete_self'], 422);
    }

    $users = load_users();
    if (!isset($users[$username])) {
        json_response(['error' => 'not_found'], 404);
    }

    $role = $users[$username]['role'] ?? 'editor';
    unset($users[$username]);

    if ($role === 'admin' && count_admins($users) === 0) {
        json_response(['error' => 'last_admin'], 422);
    }

    save_users($users);
    json_response([
        'ok' => true,
        'users' => sanitize_users($users),
        'csrf' => csrf_token(),
    ]);
}

function reset_password(array $payload): void
{
    require_admin();

    $username = normalize_username($payload['username'] ?? '');
    $password = (string) ($payload['password'] ?? '');

    if ($username === '' || $password === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }
    if (strlen($password) < 8) {
        json_response(['error' => 'password_too_short', 'min' => 8], 422);
    }

    $users = load_users();
    if (!isset($users[$username])) {
        json_response(['error' => 'not_found'], 404);
    }

    $users[$username]['passwordHash'] = password_hash($password, PASSWORD_DEFAULT);
    $users[$username]['updatedAt'] = date('c');

    save_users($users);
    json_response([
        'ok' => true,
        'users' => sanitize_users($users),
        'csrf' => csrf_token(),
    ]);
}

function change_password(array $payload): void
{
    $currentPassword = (string) ($payload['currentPassword'] ?? '');
    $newPassword = (string) ($payload['newPassword'] ?? '');

    if ($currentPassword === '' || $newPassword === '') {
        json_response(['error' => 'invalid_payload'], 400);
    }
    if (strlen($newPassword) < 8) {
        json_response(['error' => 'password_too_short', 'min' => 8], 422);
    }

    $username = current_user();
    if ($username === null) {
        json_response(['error' => 'auth_required'], 401);
    }

    $users = load_users();
    $user = $users[$username] ?? null;
    if ($user === null) {
        json_response(['error' => 'not_found'], 404);
    }

    if (!password_verify($currentPassword, (string) ($user['passwordHash'] ?? ''))) {
        json_response(['error' => 'invalid_password'], 422);
    }

    $users[$username]['passwordHash'] = password_hash($newPassword, PASSWORD_DEFAULT);
    $users[$username]['updatedAt'] = date('c');

    save_users($users);
    json_response([
        'ok' => true,
        'csrf' => csrf_token(),
    ]);
}

function probe_provider_url(string $url): array
{
    $attempt = fetch_provider_url($url, true);
    if (!$attempt['ok'] && ($attempt['httpCode'] === null || $attempt['httpCode'] >= 400)) {
        $attempt = fetch_provider_url($url, false);
    }
    return $attempt;
}

function fetch_provider_url(string $url, bool $head = false): array
{
    $result = [
        'ok' => false,
        'httpCode' => null,
        'message' => null,
        'resolvedUrl' => null,
    ];
    if (!function_exists('curl_init')) {
        $result['message'] = 'cURL indisponible';
        return $result;
    }
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_HEADER => true,
        CURLOPT_NOBODY => $head,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_MAXREDIRS => 4,
        CURLOPT_TIMEOUT => 6,
        CURLOPT_CONNECTTIMEOUT => 4,
        CURLOPT_USERAGENT => 'GramFlixPanel/1.0',
        CURLOPT_SSL_VERIFYPEER => false,
    ]);
    $response = curl_exec($ch);
    $err = curl_error($ch);
    $code = curl_getinfo($ch, CURLINFO_RESPONSE_CODE) ?: 0;
    $finalUrl = curl_getinfo($ch, CURLINFO_EFFECTIVE_URL) ?: $url;
    curl_close($ch);

    $result['httpCode'] = $code ?: null;
    $result['resolvedUrl'] = $finalUrl;
    if ($response !== false && $code >= 200 && $code < 400) {
        $result['ok'] = true;
        $result['message'] = 'OK';
        return $result;
    }
    $result['message'] = $err ?: ($code ? 'HTTP ' . $code : 'Aucune reponse');
    return $result;
}

function normalize_slug(string $slug): string
{
    $slug = trim($slug);
    $slug = preg_replace('/[^A-Za-z0-9\-_.]/', '-', $slug);
    $slug = preg_replace('/-+/', '-', $slug);
    return trim($slug, '-');
}

function normalize_username(?string $username): string
{
    if ($username === null) {
        return '';
    }
    $username = strtolower(trim($username));
    $username = preg_replace('/[^a-z0-9._-]/', '', $username);
    return $username;
}

function normalize_role(string $role): string
{
    return $role === 'admin' ? 'admin' : 'editor';
}

function github_sync_action(): void
{
    require_admin();
    if (!github_enabled()) {
        json_response(['error' => 'github_disabled'], 400);
    }

    try {
        $branch = defined('GITHUB_BRANCH') ? trim((string) GITHUB_BRANCH) : 'main';

        $providersPayload = read_json('providers.json', ['version' => 1, 'providers' => []]);
        $hostersPayload = read_json('hosters.json', ['version' => 1, 'hosters' => []]);

        $files = [
            [
                'path' => 'providers.json',
                'content' => json_encode($providersPayload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . PHP_EOL,
            ],
            [
                'path' => 'hosters.json',
                'content' => json_encode($hostersPayload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . PHP_EOL,
            ],
        ];

        $message = 'Panel sync ' . date('Y-m-d H:i:s');
        $results = github_sync_files($files, $message, $branch);

        json_response([
            'ok' => true,
            'branch' => $branch,
            'results' => $results,
            'message' => $message,
            'csrf' => csrf_token(),
        ]);
    } catch (Throwable $e) {
        json_response([
            'error' => 'github_sync_failed',
            'message' => $e->getMessage(),
        ], 500);
    }
}



