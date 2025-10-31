<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/auth.php';
require_once __DIR__ . '/lib/providers.php';

require_login();

function http_get_preview(string $url, int $timeout = 10): array {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_TIMEOUT => $timeout,
        CURLOPT_USERAGENT => 'cs-admin-test/1.0',
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
    ]);
    $body = curl_exec($ch);
    $info = curl_getinfo($ch);
    $err = curl_error($ch);
    curl_close($ch);
    $status = (int)($info['http_code'] ?? 0);
    $len = is_string($body) ? strlen($body) : 0;
    $snippet = '';
    if (is_string($body)) {
        // produce a small text snippet for debugging; avoid dumping full HTML
        $snippet = substr(preg_replace('/\s+/', ' ', strip_tags($body)), 0, 600);
    }
    return [
        'status' => $status,
        'final_url' => $info['url'] ?? $url,
        'content_type' => $info['content_type'] ?? '',
        'length' => $len,
        'error' => $err,
        'snippet' => $snippet,
    ];
}

$data = load_providers();
$providers = $data['providers'] ?? [];

$slug = isset($_GET['slug']) ? (string)$_GET['slug'] : '';
$custom = isset($_GET['url']) ? trim((string)$_GET['url']) : '';
$target = '';
if ($custom !== '') {
    $target = $custom;
} elseif ($slug !== '' && isset($providers[$slug]['baseUrl'])) {
    $target = $providers[$slug]['baseUrl'];
}

$result = null;
if ($target !== '') {
    $result = http_get_preview($target);
}
?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Test Fetch</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <h2>Test Fetch</h2>
      <div>
        <a class="btn" href="/dashboard.php">Tableau de bord</a>
        <a class="btn" style="margin-left:8px;" href="/logout.php">Se déconnecter</a>
      </div>
    </div>
    <div class="card">
      <form method="get">
        <div class="row">
          <div class="col">
            <label>Provider (slug)</label>
            <select name="slug">
              <option value="">-- choisir --</option>
              <?php foreach ($providers as $k => $p): ?>
                <option value="<?= htmlspecialchars($k) ?>" <?= $slug===$k?'selected':'' ?>><?= htmlspecialchars($k) ?> (<?= htmlspecialchars($p['name'] ?? '') ?>)</option>
              <?php endforeach; ?>
            </select>
          </div>
          <div class="col">
            <label>URL custom (optionnel)</label>
            <input type="url" name="url" value="<?= htmlspecialchars($custom) ?>" placeholder="https://…" />
          </div>
        </div>
        <div style="margin-top:12px;"><button type="submit">Tester</button></div>
      </form>
    </div>
    <?php if ($result): ?>
      <div class="card" style="margin-top:16px;">
        <h3>Résultat</h3>
        <p class="muted">URL: <code><?= htmlspecialchars($target) ?></code></p>
        <p>Status: <strong><?= (int)$result['status'] ?></strong> | Type: <?= htmlspecialchars($result['content_type']) ?> | Taille: <?= (int)$result['length'] ?> octets</p>
        <?php if ($result['error']): ?><div class="flash err">Erreur cURL: <?= htmlspecialchars($result['error']) ?></div><?php endif; ?>
        <?php if ($result['snippet']): ?><pre style="white-space:pre-wrap; background:#0b1220; padding:12px; border-radius:8px; border:1px solid #1f2937; max-height:420px; overflow:auto;"><?= htmlspecialchars($result['snippet']) ?></pre><?php endif; ?>
      </div>
    <?php endif; ?>
  </div>
</body>
</html>

