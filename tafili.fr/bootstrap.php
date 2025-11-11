<?php
declare(strict_types=1);

const BASE_PATH = __DIR__;
const DATA_PATH = BASE_PATH . DIRECTORY_SEPARATOR . 'data';

require_once BASE_PATH . '/config.php';
if (file_exists(BASE_PATH . '/config.local.php')) {
    require_once BASE_PATH . '/config.local.php';
}

session_name('gramflix_panel');

if (defined('SESSION_SECURE')) {
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => defined('PANEL_BASE_URL') ? (string) PANEL_BASE_URL : '/',
        'secure' => (bool) SESSION_SECURE,
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
}

session_start();

initialize_user_store();
initialize_history_store();

function data_path(string $filename): string
{
    return DATA_PATH . DIRECTORY_SEPARATOR . $filename;
}

function panel_log(string $context, string $message): void
{
    $line = sprintf('[%s] %s: %s%s', date('c'), strtoupper($context), $message, PHP_EOL);
    @file_put_contents(data_path('panel.log'), $line, FILE_APPEND);
}

function panel_log_exception(string $context, Throwable $throwable): void
{
    panel_log($context, $throwable->getMessage() . ' in ' . $throwable->getFile() . ':' . $throwable->getLine());
}

function ensure_data_directory(): void
{
    if (!is_dir(DATA_PATH)) {
        if (!mkdir(DATA_PATH, 0775, true) && !is_dir(DATA_PATH)) {
            throw new RuntimeException('Impossible de creer le dossier data.');
        }
    }
}

function read_json(string $filename, array $defaults = []): array
{
    ensure_data_directory();
    $path = data_path($filename);
    if (!is_file($path)) {
        return $defaults;
    }
    $contents = file_get_contents($path);
    if ($contents === false || trim($contents) === '') {
        return $defaults;
    }
    $decoded = json_decode($contents, true);
    return is_array($decoded) ? $decoded : $defaults;
}

function write_json(string $filename, array $payload): void
{
    ensure_data_directory();
    $path = data_path($filename);
    $json = json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . PHP_EOL;
    $tmp = $path . '.tmp';
    if (file_put_contents($tmp, $json, LOCK_EX) === false) {
        throw new RuntimeException('Ecriture impossible dans ' . $tmp);
    }
    if (!rename($tmp, $path)) {
        throw new RuntimeException('Impossible de remplacer ' . $path);
    }
}

function load_users(): array
{
    $raw = read_json('users.json', ['version' => 1, 'users' => []]);
    $users = [];
    foreach ($raw['users'] as $entry) {
        if (!is_array($entry) || empty($entry['username'])) {
            continue;
        }
        $username = strtolower(trim((string) $entry['username']));
        $entry['username'] = $username;
        $users[$username] = $entry;
    }
    return $users;
}

function save_users(array $users): void
{
    $payload = [
        'version' => 1,
        'users' => array_values($users),
    ];
    write_json('users.json', $payload);
}

function initialize_user_store(): void
{
    $users = load_users();
    if (!empty($users)) {
        return;
    }
    $username = strtolower((string) (defined('DEFAULT_ADMIN_USERNAME') ? DEFAULT_ADMIN_USERNAME : 'admin'));
    $display = (string) (defined('DEFAULT_ADMIN_DISPLAY') ? DEFAULT_ADMIN_DISPLAY : ucfirst($username));
    $password = (string) (defined('DEFAULT_ADMIN_PASSWORD') ? DEFAULT_ADMIN_PASSWORD : bin2hex(random_bytes(8)));
    $hash = password_hash($password, PASSWORD_DEFAULT);
    $now = date('c');

    $users[$username] = [
        'username' => $username,
        'displayName' => $display,
        'role' => 'admin',
        'passwordHash' => $hash,
        'createdAt' => $now,
        'updatedAt' => $now,
    ];

    save_users($users);
    panel_log('users', 'Compte administrateur initialise pour ' . $username);
}

function history_defaults(): array
{
    return [
        'version' => 1,
        'entries' => [],
    ];
}

function initialize_history_store(): void
{
    $path = data_path('history.json');
    if (!is_file($path)) {
        write_json('history.json', history_defaults());
    }
}

function append_history_entry(string $action, string $summary, array $details = []): void
{
    $data = read_json('history.json', history_defaults());
    $entries = $data['entries'] ?? [];
    $user = current_user_record();
    $entry = [
        'id' => bin2hex(random_bytes(8)),
        'action' => $action,
        'summary' => $summary,
        'details' => $details,
        'user' => $user ? [
            'username' => $user['username'],
            'displayName' => $user['displayName'] ?? ucfirst($user['username']),
        ] : null,
        'timestamp' => date('c'),
    ];
    array_unshift($entries, $entry);
    $max = defined('HISTORY_MAX_ENTRIES') ? (int) HISTORY_MAX_ENTRIES : 200;
    if ($max > 0) {
        $entries = array_slice($entries, 0, $max);
    }
    write_json('history.json', [
        'version' => 1,
        'entries' => $entries,
    ]);
}

function history_entries(int $limit = 20): array
{
    $data = read_json('history.json', history_defaults());
    $entries = $data['entries'] ?? [];
    if ($limit > 0) {
        return array_slice($entries, 0, $limit);
    }
    return $entries;
}

function current_user(): ?string
{
    $user = $_SESSION['user'] ?? null;
    return is_string($user) ? strtolower($user) : null;
}

function current_user_record(): ?array
{
    $username = current_user();
    if ($username === null) {
        return null;
    }
    $users = load_users();
    return $users[$username] ?? null;
}

function is_authenticated(): bool
{
    return current_user_record() !== null;
}

function is_admin(): bool
{
    $record = current_user_record();
    return $record !== null && ($record['role'] ?? 'editor') === 'admin';
}

function login(string $username, string $password): bool
{
    $users = load_users();
    $key = strtolower(trim($username));
    $user = $users[$key] ?? null;
    if ($user && password_verify($password, (string) ($user['passwordHash'] ?? ''))) {
        $_SESSION['user'] = $key;
        $_SESSION['role'] = $user['role'] ?? 'editor';
        return true;
    }
    return false;
}

function logout(): void
{
    $_SESSION = [];
    if (ini_get('session.use_cookies')) {
        $params = session_get_cookie_params();
        setcookie(session_name(), '', time() - 42000, $params['path'], $params['domain'] ?? '', $params['secure'] ?? false, $params['httponly'] ?? false);
    }
    session_destroy();
}

function require_auth(): void
{
    if (!is_authenticated()) {
        http_response_code(401);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'auth_required']);
        exit;
    }
}

function require_admin(): void
{
    if (!is_admin()) {
        http_response_code(403);
        header('Content-Type: application/json');
        echo json_encode(['error' => 'forbidden']);
        exit;
    }
}

function ensure_csrf_token(): string
{
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(16));
    }
    return (string) $_SESSION['csrf_token'];
}

function csrf_token(): string
{
    return ensure_csrf_token();
}

function verify_csrf_token(?string $token): bool
{
    $expected = $_SESSION['csrf_token'] ?? null;
    return is_string($token) && is_string($expected) && hash_equals($expected, $token);
}

function json_response(array $payload, int $status = 200): void
{
    http_response_code($status);
    header('Content-Type: application/json');
    echo json_encode($payload);
    exit;
}

function github_enabled(): bool
{
    return defined('GITHUB_SYNC_ENABLED') && GITHUB_SYNC_ENABLED && defined('GITHUB_TOKEN') && trim((string) GITHUB_TOKEN) !== '';
}

function github_repo_info(): array
{
    return [
        'owner' => trim((string) (defined('GITHUB_OWNER') ? GITHUB_OWNER : '')),
        'repo' => trim((string) (defined('GITHUB_REPO') ? GITHUB_REPO : '')),
        'token' => trim((string) (defined('GITHUB_TOKEN') ? GITHUB_TOKEN : '')),
        'branch' => trim((string) (defined('GITHUB_BRANCH') ? GITHUB_BRANCH : 'main')),
    ];
}

function github_api_request(array $info, string $method, string $endpoint, ?array $payload = null): array
{
    if (!function_exists('curl_init')) {
        throw new RuntimeException('Extension cURL requise pour la synchronisation GitHub.');
    }
    $url = 'https://api.github.com' . $endpoint;
    $ch = curl_init($url);
    $headers = [
        'Accept: application/vnd.github+json',
        'Authorization: Bearer ' . $info['token'],
        'User-Agent: GramFlix-Webpanel/2.0',
    ];
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_HTTPHEADER => $headers,
    ]);
    if ($payload !== null) {
        curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode($payload));
    }
    $response = curl_exec($ch);
    $status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    if ($response === false) {
        $error = curl_error($ch);
        curl_close($ch);
        throw new RuntimeException('Requete GitHub echouee: ' . $error);
    }
    curl_close($ch);
    if ($status >= 400) {
        throw new RuntimeException('GitHub a renvoye ' . $status . ' : ' . $response);
    }
    $decoded = json_decode($response, true);
    return is_array($decoded) ? $decoded : [];
}

function github_get_file(array $info, string $path): ?array
{
    $endpoint = sprintf('/repos/%s/%s/contents/%s?ref=%s', rawurlencode($info['owner']), rawurlencode($info['repo']), ltrim($path, '/'), rawurlencode($info['branch']));
    try {
        return github_api_request($info, 'GET', $endpoint);
    } catch (RuntimeException $e) {
        if (strpos($e->getMessage(), '404') !== false) {
            return null;
        }
        throw $e;
    }
}

function github_put_file(array $info, string $path, string $content, ?string $sha): array
{
    $endpoint = sprintf('/repos/%s/%s/contents/%s', rawurlencode($info['owner']), rawurlencode($info['repo']), ltrim($path, '/'));
    $payload = [
        'message' => 'GramFlix panel sync ' . date('Y-m-d H:i:s'),
        'content' => base64_encode($content),
        'branch' => $info['branch'],
    ];
    if ($sha !== null) {
        $payload['sha'] = $sha;
    }
    return github_api_request($info, 'PUT', $endpoint, $payload);
}

function github_sync_files(array $files): array
{
    if (!github_enabled()) {
        throw new RuntimeException('La synchronisation GitHub est desactivee.');
    }
    $info = github_repo_info();
    $results = [];
    foreach ($files as $file) {
        $existing = github_get_file($info, $file['path']);
        $sha = $existing['sha'] ?? null;
        github_put_file($info, $file['path'], $file['content'], $sha);
        $results[] = ['path' => $file['path'], 'status' => $sha ? 'updated' : 'created'];
    }
    return $results;
}

function data_file_status(string $filename): array
{
    $path = data_path($filename);
    return [
        'path' => $path,
        'exists' => file_exists($path),
        'readable' => is_readable($path),
        'writable' => file_exists($path) ? is_writable($path) : is_writable(dirname($path)),
    ];
}

function data_directory_status(): array
{
    return [
        'path' => DATA_PATH,
        'exists' => is_dir(DATA_PATH),
        'writable' => is_dir(DATA_PATH) ? is_writable(DATA_PATH) : false,
    ];
}

function build_panel_state(): array
{
    $providers = read_json('providers.json', ['version' => 1, 'providers' => []]);
    $hosters = read_json('hosters.json', ['version' => 1, 'hosters' => []]);
    $rules = read_json('rules.json', ['version' => 1, 'rules' => []]);
    $diagnostics = [
        'providers' => data_file_status('providers.json'),
        'hosters' => data_file_status('hosters.json'),
        'rules' => data_file_status('rules.json'),
        'users' => data_file_status('users.json'),
        'history' => data_file_status('history.json'),
        'dataDir' => data_directory_status(),
    ];

    return [
        'providers' => $providers,
        'hosters' => $hosters,
        'rules' => $rules,
        'history' => [
            'entries' => history_entries(),
        ],
        'diagnostics' => $diagnostics,
        'isAdmin' => is_admin(),
        'currentUser' => current_user_record(),
        'max' => defined('MAX_PROVIDERS') ? (int) MAX_PROVIDERS : 32,
        'csrf' => csrf_token(),
        'github' => [
            'enabled' => github_enabled(),
            'owner' => defined('GITHUB_OWNER') ? (string) GITHUB_OWNER : null,
            'repo' => defined('GITHUB_REPO') ? (string) GITHUB_REPO : null,
            'branch' => defined('GITHUB_BRANCH') ? (string) GITHUB_BRANCH : 'main',
        ],
    ];
}

function normalize_slug(string $value): string
{
    $value = strtolower(trim($value));
    $value = preg_replace('/[^a-z0-9._-]/', '-', $value);
    return (string) preg_replace('/-+/', '-', $value);
}

function normalize_username(?string $username): string
{
    if ($username === null) {
        return '';
    }
    $username = strtolower(trim($username));
    return preg_replace('/[^a-z0-9._-]/', '', $username);
}

function sanitize_users(array $users): array
{
    $export = [];
    foreach ($users as $user) {
        $export[] = [
            'username' => $user['username'],
            'displayName' => $user['displayName'] ?? ucfirst($user['username']),
            'role' => $user['role'] ?? 'editor',
            'createdAt' => $user['createdAt'] ?? null,
            'updatedAt' => $user['updatedAt'] ?? null,
        ];
    }
    usort($export, static function ($a, $b) {
        return strcmp($a['username'], $b['username']);
    });
    return $export;
}
