<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/auth.php';
require_once __DIR__ . '/lib/providers.php';
require_once __DIR__ . '/lib/rules.php';
require_once __DIR__ . '/lib/csrf.php';

require_login();
$providers = (load_providers()['providers'] ?? []);
$data = load_rules();
$rules = $data['rules'] ?? [];
?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Rules</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <h2>Règles d’extraction</h2>
      <div>
        <a class="btn" href="/dashboard.php">Tableau de bord</a>
        <a class="btn" style="margin-left:8px;" href="/logout.php">Se déconnecter</a>
      </div>
    </div>
    <div class="card">
      <p class="muted">Définissez des règles (sélecteurs CSS) par site pour permettre un scraping dynamique côté extension sans recompiler.</p>
      <form method="post" action="/actions/save_rules.php">
        <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
        <table class="table">
          <thead><tr><th>Slug</th><th>Search Path</th><th>Query Param</th><th>Item Selector</th><th>Title Selector</th><th>URL Selector</th></tr></thead>
          <tbody>
          <?php foreach ($providers as $slug => $p): $r = $rules[$slug] ?? []; ?>
            <tr>
              <td><code><?= htmlspecialchars($slug) ?></code></td>
              <td><input name="rules[<?= htmlspecialchars($slug) ?>][searchPath]" value="<?= htmlspecialchars($r['searchPath'] ?? '') ?>" placeholder="/search" /></td>
              <td><input name="rules[<?= htmlspecialchars($slug) ?>][searchParam]" value="<?= htmlspecialchars($r['searchParam'] ?? 'q') ?>" placeholder="q" /></td>
              <td><input name="rules[<?= htmlspecialchars($slug) ?>][itemSel]" value="<?= htmlspecialchars($r['itemSel'] ?? '') ?>" placeholder=".item" /></td>
              <td><input name="rules[<?= htmlspecialchars($slug) ?>][titleSel]" value="<?= htmlspecialchars($r['titleSel'] ?? '') ?>" placeholder=".title" /></td>
              <td><input name="rules[<?= htmlspecialchars($slug) ?>][urlSel]" value="<?= htmlspecialchars($r['urlSel'] ?? '') ?>" placeholder="a@href" /></td>
            </tr>
          <?php endforeach; ?>
          </tbody>
        </table>
        <div style="margin-top:12px;"><button type="submit">Enregistrer</button></div>
      </form>
      <div style="margin-top:12px;">
        <a class="btn" href="/rules.json" target="_blank">Voir rules.json</a>
      </div>
    </div>
  </div>
</body>
</html>

