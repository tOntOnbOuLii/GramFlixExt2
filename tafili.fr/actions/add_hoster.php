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

$slug = trim((string)($_POST['slug'] ?? ''));
$name = trim((string)($_POST['name'] ?? ''));
$url  = trim((string)($_POST['url'] ?? ''));
if ($slug === '' || $name === '' || $url === '') {
    header('Location: /hosters.php');
    exit;
}

$data = load_hosters();
$hosters = $data['hosters'] ?? [];
if (!is_array($hosters)) $hosters = [];
if (!isset($hosters[$slug])) {
    $hosters[$slug] = [ 'name' => $name, 'url' => $url ];
}
$data['hosters'] = $hosters;
save_hosters($data);

header('Location: /hosters.php');
exit;

