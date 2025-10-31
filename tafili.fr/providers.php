<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/auth.php';
require_once __DIR__ . '/lib/providers.php';
require_once __DIR__ . '/lib/csrf.php';

// Access: any authenticated user can edit provider URLs
require_login();
$me = current_user();

$data = load_providers();
$providers = $data['providers'] ?? [];

?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Providers</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <h2>Gestion des sites (providers)</h2>
      <div>
        <a class="btn" href="/dashboard.php">Tableau de bord</a>
        <a class="btn" style="margin-left:8px;" href="/logout.php">Se déconnecter</a>
      </div>
    </div>
    <div class="card">
      <p class="muted">Modifiez ici les URLs des sites. Enregistré → met à jour <code>providers.json</code> public.</p>
      <form method="post" action="/actions/save_providers.php">
        <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
        <div class="table-wrap">
        <table class="table responsive">
          <thead><tr><th>Slug</th><th>Nom</th><th>URL</th></tr></thead>
          <tbody>
          <?php foreach ($providers as $key => $p): ?>
            <tr>
              <td data-label="Slug"><code><?= htmlspecialchars($key) ?></code></td>
              <td data-label="Nom"><input type="text" name="providers[<?= htmlspecialchars($key) ?>][name]" value="<?= htmlspecialchars($p['name'] ?? '') ?>" /></td>
              <td data-label="URL"><input class="url-input" type="url" name="providers[<?= htmlspecialchars($key) ?>][baseUrl]" value="<?= htmlspecialchars($p['baseUrl'] ?? '') ?>" /></td>
            </tr>
          <?php endforeach; ?>
          </tbody>
        </table>
        </div>
        <div style="margin-top:12px; display:flex; gap:10px;">
          <button type="submit">Enregistrer</button>
          <a class="btn" href="/providers.json" target="_blank">Voir providers.json public</a>
        </div>
      </form>
    </div>
  </div>
</body>
</html>
