<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../lib/auth.php';
require_once __DIR__ . '/../lib/hosters.php';
require_once __DIR__ . '/../lib/csrf.php';

require_login();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: /hosters.php');
    exit;
}

$token = (string)($_POST['csrf'] ?? '');
if (!csrf_check($token)) {
    header('Location: /hosters.php');
    exit;
}

$incoming = $_POST['hosters'] ?? [];
if (!is_array($incoming)) $incoming = [];
$clean = [];
foreach ($incoming as $k => $row) {
    if (!is_array($row)) continue;
    $slug = trim((string)$k);
    if ($slug === '') continue;
    $name = trim((string)($row['name'] ?? ''));
    $url  = trim((string)($row['url'] ?? ''));
    $clean[$slug] = [ 'name' => $name, 'url' => $url ];
}

$data = [ 'version' => 1, 'hosters' => $clean ];
save_hosters($data);

header('Location: /hosters.php');
exit;

