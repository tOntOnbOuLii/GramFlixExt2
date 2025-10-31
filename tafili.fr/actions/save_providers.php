<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../lib/auth.php';
require_once __DIR__ . '/../lib/providers.php';
require_once __DIR__ . '/../lib/csrf.php';

require_login();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: /providers.php');
    exit;
}

$token = (string)($_POST['csrf'] ?? '');
if (!csrf_check($token)) {
    header('Location: /providers.php');
    exit;
}

$incoming = $_POST['providers'] ?? [];
if (!is_array($incoming)) $incoming = [];

$clean = [];
foreach ($incoming as $key => $row) {
    if (!is_array($row)) continue;
    $k = trim((string)$key);
    if ($k === '') continue;
    $name = trim((string)($row['name'] ?? ''));
    $url = trim((string)($row['baseUrl'] ?? ''));
    $clean[$k] = [
        'name' => $name,
        'baseUrl' => $url,
    ];
}

$data = [
    'version' => 1,
    'providers' => $clean,
];

if (!save_providers($data)) {
    header('Location: /providers.php');
    exit;
}

header('Location: /providers.php');
exit;

