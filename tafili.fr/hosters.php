<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/auth.php';
require_once __DIR__ . '/lib/hosters.php';
require_once __DIR__ . '/lib/csrf.php';

require_login();
$me = current_user();
$data = load_hosters();
$hosters = $data['hosters'] ?? [];
?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Hosters</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <h2>Hébergeurs (hosters)</h2>
      <div>
        <a class="btn" href="/dashboard.php">Tableau de bord</a>
        <a class="btn" style="margin-left:8px;" href="/logout.php">Se déconnecter</a>
      </div>
    </div>
    <div class="card">
      <p class="muted">Définissez les hébergeurs disponibles (nom + URL/pattern). L’extension peut s’y référer à l’exécution.</p>
      <form method="post" action="/actions/save_hosters.php">
        <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
        <div class="table-wrap">
        <table class="table responsive">
          <thead><tr><th>Slug</th><th>Nom</th><th>URL / Pattern</th></tr></thead>
          <tbody>
          <?php foreach ($hosters as $key => $h): ?>
            <tr>
              <td data-label="Slug"><code><?= htmlspecialchars($key) ?></code></td>
              <td data-label="Nom"><input type="text" name="hosters[<?= htmlspecialchars($key) ?>][name]" value="<?= htmlspecialchars($h['name'] ?? '') ?>" /></td>
              <td data-label="URL / Pattern"><input class="url-input" type="text" name="hosters[<?= htmlspecialchars($key) ?>][url]" value="<?= htmlspecialchars($h['url'] ?? '') ?>" /></td>
            </tr>
          <?php endforeach; ?>
          </tbody>
        </table>
        </div>
        <div class="row" style="margin-top:16px;">
          <div class="col">
            <div class="card">
              <h3>Ajouter un hoster</h3>
              <form method="post" action="/actions/add_hoster.php">
                <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
                <label>Slug</label>
                <input name="slug" pattern="[A-Za-z0-9._-]{2,40}" required />
                <label>Nom</label>
                <input name="name" required />
                <label>URL / Pattern</label>
                <input name="url" required />
                <div style="margin-top:12px;"><button type="submit">Ajouter</button></div>
              </form>
            </div>
          </div>
          <div class="col">
            <div class="card">
              <h3>Export</h3>
              <a class="btn" href="/hosters.json" target="_blank">Voir hosters.json public</a>
            </div>
          </div>
        </div>
        <div style="margin-top:12px;">
          <button type="submit">Enregistrer les modifications</button>
        </div>
      </form>
    </div>
  </div>
</body>
</html>
