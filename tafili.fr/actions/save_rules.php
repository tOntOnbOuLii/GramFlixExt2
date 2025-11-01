<?php
require_once __DIR__ . '/../config.php';
require_once __DIR__ . '/../lib/auth.php';
require_once __DIR__ . '/../lib/rules.php';
require_once __DIR__ . '/../lib/csrf.php';

require_login();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: /rules.php');
    exit;
}

$token = (string)($_POST['csrf'] ?? '');
if (!csrf_check($token)) {
    header('Location: /rules.php');
    exit;
}

$incoming = $_POST['rules'] ?? [];
if (!is_array($incoming)) $incoming = [];

$clean = [];
foreach ($incoming as $slug => $r) {
    if (!is_array($r)) continue;
    $clean[$slug] = [
        'searchPath' => trim((string)($r['searchPath'] ?? '')),
        'searchParam' => trim((string)($r['searchParam'] ?? 'q')),
        'itemSel' => trim((string)($r['itemSel'] ?? '')),
        'titleSel' => trim((string)($r['titleSel'] ?? '')),
        'urlSel' => trim((string)($r['urlSel'] ?? '')),
        'embedSel' => trim((string)($r['embedSel'] ?? '')),
    ];
}

$data = [ 'version' => 1, 'rules' => $clean ];
save_rules($data);
header('Location: /rules.php');
exit;
