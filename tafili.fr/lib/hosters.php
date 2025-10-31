<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/github.php';

const HOSTERS_WEB_JSON = __DIR__ . '/../hosters.json';
const HOSTERS_DATA_JSON = DATA_DIR . '/hosters.json';

function load_hosters(): array {
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0755, true);
    if (!file_exists(HOSTERS_DATA_JSON)) return ['version' => 1, 'hosters' => new stdClass()];
    $raw = @file_get_contents(HOSTERS_DATA_JSON);
    if ($raw === false || $raw === '') return ['version' => 1, 'hosters' => new stdClass()];
    $json = json_decode($raw, true);
    if (!is_array($json)) return ['version' => 1, 'hosters' => new stdClass()];
    if (!isset($json['version'])) $json['version'] = 1;
    if (!isset($json['hosters']) || !is_array($json['hosters'])) $json['hosters'] = [];
    return $json;
}

function save_hosters(array $data): bool {
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0755, true);
    if (!isset($data['version'])) $data['version'] = 1;
    $payload = json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    $ok = @file_put_contents(HOSTERS_DATA_JSON, $payload, LOCK_EX) !== false;
    if ($ok) {
        @file_put_contents(HOSTERS_WEB_JSON, $payload, LOCK_EX);
        if (github_sync_enabled()) {
            @github_put_file('hosters.json', $payload, 'Update hosters.json from cs.tafili.fr panel');
        }
    }
    return $ok;
}
