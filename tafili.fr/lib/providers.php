<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/github.php';

const PROVIDERS_WEB_JSON = __DIR__ . '/../providers.json'; // public copy
const PROVIDERS_DATA_JSON = DATA_DIR . '/providers.json';   // internal storage

function load_providers(): array {
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0755, true);
    if (!file_exists(PROVIDERS_DATA_JSON)) return ['version' => 1, 'providers' => new stdClass()];
    $raw = @file_get_contents(PROVIDERS_DATA_JSON);
    if ($raw === false || $raw === '') return ['version' => 1, 'providers' => new stdClass()];
    $json = json_decode($raw, true);
    if (!is_array($json)) return ['version' => 1, 'providers' => new stdClass()];
    if (!isset($json['version'])) $json['version'] = 1;
    if (!isset($json['providers']) || !is_array($json['providers'])) $json['providers'] = [];
    return $json;
}

function save_providers(array $data): bool {
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0755, true);
    if (!isset($data['version'])) $data['version'] = 1;
    $payload = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    $ok = @file_put_contents(PROVIDERS_DATA_JSON, $payload, LOCK_EX) !== false;
    if ($ok) {
        // publish a web-accessible copy consumed by the APKs
        @file_put_contents(PROVIDERS_WEB_JSON, $payload, LOCK_EX);
        // optional GitHub sync
        if (github_sync_enabled()) {
            @github_put_file('providers.json', $payload, 'Update providers.json from web panel');
        }
    }
    return $ok;
}

function normalize_key(string $slug): string {
    // Keep as-is for now; keys are case-sensitive but consistent with your list
    return $slug;
}
