<?php
declare(strict_types=1);

define('BASE_PATH', __DIR__);
define('DATA_PATH', BASE_PATH . DIRECTORY_SEPARATOR . 'data');

require_once BASE_PATH . '/config.php';
if (file_exists(BASE_PATH . '/config.local.php')) {
    require_once BASE_PATH . '/config.local.php';
}

session_name('tafili_panel');

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

/**
 * Retourne le chemin absolu vers un fichier JSON stocke dans /data.
 */
function data_path(string $filename): string
{
    return DATA_PATH . DIRECTORY_SEPARATOR . $filename;
}

/**
 * Lit un fichier JSON et renvoie un tableau associatif.
 *
 * @return array<mixed>
 */
function read_json(string $filename, array $defaults = []): array
{
    $path = data_path($filename);
    if (!is_file($path)) {
        return $defaults;
    }
    $content = file_get_contents($path);
    if ($content === false || $content === '') {
        return $defaults;
    }
    $decoded = json_decode($content, true);
    if (!is_array($decoded)) {
        return $defaults;
    }
    return $decoded;
}

/**
 * Ecrit un tableau associatif dans un fichier JSON (avec fichier temporaire).
 *
 * @param array<mixed> $payload
 */
function write_json(string $filename, array $payload): void
{
    $path = data_path($filename);
    $dir = dirname($path);
    if (!is_dir($dir)) {
        if (!mkdir($dir, 0775, true) && !is_dir($dir)) {
            throw new RuntimeException("Impossible de creer le dossier data ($dir).");
        }
    }
    $json = json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . PHP_EOL;
    $tmpPath = $path . '.tmp';
    if (file_put_contents($tmpPath, $json, LOCK_EX) === false) {
        throw new RuntimeException("Ecriture impossible dans $tmpPath");
    }
    if (!rename($tmpPath, $path)) {
        throw new RuntimeException("Impossible de remplacer $path");
    }
}

/**
 * @return array<string,array<string,mixed>>
 */
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

/**
 * @param array<string,array<string,mixed>> $users
 */
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

    $username = defined('DEFAULT_ADMIN_USERNAME') ? strtolower((string) DEFAULT_ADMIN_USERNAME) : 'admin';
    $display = defined('DEFAULT_ADMIN_DISPLAY') ? (string) DEFAULT_ADMIN_DISPLAY : ucfirst($username);
    $password = defined('DEFAULT_ADMIN_PASSWORD') ? (string) DEFAULT_ADMIN_PASSWORD : bin2hex(random_bytes(4));
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
}

function current_user(): ?string
{
    return $_SESSION['user'] ?? null;
}

/**
 * @param array<string,array<string,mixed>> $users
 */
function sanitize_users(array $users): array
{
    $export = [];
    foreach ($users as $entry) {
        $export[] = [
            'username' => $entry['username'],
            'displayName' => $entry['displayName'] ?? ucfirst($entry['username']),
            'role' => $entry['role'] ?? 'editor',
            'createdAt' => $entry['createdAt'] ?? null,
            'updatedAt' => $entry['updatedAt'] ?? null,
        ];
    }
    usort($export, fn($a, $b) => strcmp($a['username'], $b['username']));
    return $export;
}

/**
 * @param array<string,array<string,mixed>> $users
 */
function count_admins(array $users): int
{
    $count = 0;
    foreach ($users as $entry) {
        if (($entry['role'] ?? '') === 'admin') {
            $count++;
        }
    }
    return $count;
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
    return current_user() !== null;
}

function is_admin(): bool
{
    if (!is_authenticated()) {
        return false;
    }
    if (isset($_SESSION['role'])) {
        return $_SESSION['role'] === 'admin';
    }
    $record = current_user_record();
    return ($record['role'] ?? '') === 'admin';
}

function login(string $username, string $password): bool
{
    $username = strtolower(trim($username));
    $users = load_users();
    $user = $users[$username] ?? null;
    if ($user === null) {
        return false;
    }
    $hash = (string) ($user['passwordHash'] ?? '');
    if (!password_verify($password, $hash)) {
        return false;
    }
    $_SESSION['user'] = $username;
    $_SESSION['role'] = $user['role'] ?? 'editor';
    ensure_csrf_token();
    return true;
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

function ensure_csrf_token(): string
{
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(16));
    }
    return $_SESSION['csrf_token'];
}

function csrf_token(): string
{
    return ensure_csrf_token();
}

function verify_csrf_token(?string $token): bool
{
    $expected = $_SESSION['csrf_token'] ?? null;
    if (!is_string($expected) || $expected === '') {
        return false;
    }
    return is_string($token) && hash_equals($expected, $token);
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

/**
 * @param array<string,mixed> $payload
 */
function json_response(array $payload, int $status = 200): void
{
    http_response_code($status);
    header('Content-Type: application/json');
    echo json_encode($payload);
    exit;
}

function github_enabled(): bool
{
    return defined('GITHUB_SYNC_ENABLED')
        && GITHUB_SYNC_ENABLED
        && defined('GITHUB_TOKEN')
        && is_string(GITHUB_TOKEN)
        && trim((string) GITHUB_TOKEN) !== '';
}

/**
 * @return array{owner:string,repo:string,token:string,branch:string}
 */
function github_repo_info(): array
{
    if (!github_enabled()) {
        throw new RuntimeException('GitHub sync is not enabled.');
    }

    $owner = defined('GITHUB_OWNER') ? trim((string) GITHUB_OWNER) : '';
    $repo = defined('GITHUB_REPO') ? trim((string) GITHUB_REPO) : '';
    $token = trim((string) GITHUB_TOKEN);
    $branch = defined('GITHUB_BRANCH') ? trim((string) GITHUB_BRANCH) : 'main';

    if ($owner === '' || $repo === '' || $token === '') {
        throw new RuntimeException('GitHub configuration is incomplete.');
    }

    return [
        'owner' => $owner,
        'repo' => $repo,
        'token' => $token,
        'branch' => $branch,
    ];
}

function github_escape_path(string $path): string
{
    $segments = array_filter(array_map('trim', explode('/', ltrim($path, '/'))), fn($segment) => $segment !== '');
    return implode('/', array_map('rawurlencode', $segments));
}

function github_api_request(array $info, string $method, string $endpoint, ?array $payload = null, bool $allow404 = false): ?array
{
    if (!function_exists('curl_init')) {
        throw new RuntimeException('cURL extension required for GitHub sync.');
    }

    $url = 'https://api.github.com' . $endpoint;
    $ch = curl_init($url);
    $headers = [
        'Accept: application/vnd.github+json',
        'Authorization: Bearer ' . $info['token'],
        'User-Agent: GramFlixPanel/1.0',
    ];
    if ($payload !== null) {
        $headers[] = 'Content-Type: application/json';
    }

    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_HTTPHEADER => $headers,
    ]);

    if ($payload !== null) {
        $json = json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        curl_setopt($ch, CURLOPT_POSTFIELDS, $json);
    }

    $response = curl_exec($ch);
    if ($response === false) {
        $error = curl_error($ch);
        curl_close($ch);
        throw new RuntimeException('GitHub API request failed: ' . $error);
    }

    $status = curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    curl_close($ch);

    if ($status === 404 && $allow404) {
        return null;
    }

    if ($status >= 400) {
        throw new RuntimeException("GitHub API error ($status): $response");
    }

    if ($response === '' || $response === null) {
        return [];
    }

    $decoded = json_decode($response, true);
    return is_array($decoded) ? $decoded : [];
}

function github_get_file(array $info, string $path, string $branch): ?array
{
    $escaped = github_escape_path($path);
    $endpoint = "/repos/{$info['owner']}/{$info['repo']}/contents/{$escaped}?ref=" . rawurlencode($branch);
    return github_api_request($info, 'GET', $endpoint, null, true);
}

function github_put_file(array $info, string $path, string $content, string $message, string $branch, ?string $sha): array
{
    $escaped = github_escape_path($path);
    $endpoint = "/repos/{$info['owner']}/{$info['repo']}/contents/{$escaped}";
    $payload = [
        'message' => $message,
        'content' => base64_encode($content),
        'branch' => $branch,
    ];
    if ($sha !== null) {
        $payload['sha'] = $sha;
    }
    return github_api_request($info, 'PUT', $endpoint, $payload);
}

/**
 * @param array<int,array{path:string,content:string}> $files
 * @return array<int,array{path:string,status:string}>
 */
function github_sync_files(array $files, string $message, string $branch): array
{
    $info = github_repo_info();
    $results = [];
    foreach ($files as $file) {
        $path = $file['path'];
        $content = $file['content'];
        $existing = github_get_file($info, $path, $branch);
        $sha = $existing['sha'] ?? null;
        $remoteContent = null;
        if (is_array($existing) && isset($existing['content'])) {
            $remoteContent = base64_decode(str_replace(["\n", "\r"], '', (string) $existing['content']), true);
        }

        if ($remoteContent !== null && rtrim($remoteContent) === rtrim($content)) {
            $results[] = ['path' => $path, 'status' => 'unchanged'];
            continue;
        }

        github_put_file($info, $path, $content, $message, $branch, $sha);
        $results[] = ['path' => $path, 'status' => 'updated'];
    }

    return $results;
}
