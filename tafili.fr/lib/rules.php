<?php
require_once __DIR__ . '/../config.php';

const RULES_WEB_JSON = __DIR__ . '/../rules.json';
const RULES_DATA_JSON = DATA_DIR . '/rules.json';

function load_rules(): array {
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0755, true);
    if (!file_exists(RULES_DATA_JSON)) return ['version' => 1, 'rules' => new stdClass()];
    $raw = @file_get_contents(RULES_DATA_JSON);
    if ($raw === false || $raw === '') return ['version' => 1, 'rules' => new stdClass()];
    $json = json_decode($raw, true);
    if (!is_array($json)) return ['version' => 1, 'rules' => new stdClass()];
    if (!isset($json['version'])) $json['version'] = 1;
    if (!isset($json['rules']) || !is_array($json['rules'])) $json['rules'] = [];
    return $json;
}

function save_rules(array $data): bool {
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0755, true);
    if (!isset($data['version'])) $data['version'] = 1;
    $payload = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    $ok = @file_put_contents(RULES_DATA_JSON, $payload, LOCK_EX) !== false;
    if ($ok) {
        @file_put_contents(RULES_WEB_JSON, $payload, LOCK_EX);
    }
    return $ok;
}

