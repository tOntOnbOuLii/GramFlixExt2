<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/auth.php';

require_login();
$me = current_user();
?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Tableau de bord</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <h2><?= htmlspecialchars(SITE_NAME) ?></h2>
      <div>
        <span class="muted">Connecté en tant que <?= htmlspecialchars($me['username']) ?> (<?= htmlspecialchars($me['role']) ?>)</span>
        <a class="btn" style="margin-left:12px;" href="/logout.php">Se déconnecter</a>
      </div>
    </div>
    <div class="row">
      <?php if (($me['role'] ?? '') === 'admin'): ?>
      <div class="col">
        <div class="card">
          <h3>Utilisateurs</h3>
          <p class="muted">Gérer les comptes (ajout/suppression).</p>
          <a class="btn" href="/users.php">Ouvrir</a>
        </div>
      </div>
      <?php endif; ?>
      <div class="col">
        <div class="card">
          <h3>Sites (providers)</h3>
          <p class="muted">Modifier les URLs des sites consommées par l’extension.</p>
          <a class="btn" href="/providers.php">Gérer</a>
        </div>
      </div>
      <div class="col">
        <div class="card">
          <h3>Hébergeurs</h3>
          <p class="muted">Gérer la liste des hosters pour la lecture.</p>
          <a class="btn" href="/hosters.php">Gérer</a>
        </div>
      </div>
      <div class="col">
        <div class="card">
          <h3>Règles d’extraction</h3>
          <p class="muted">Définir des sélecteurs CSS par site pour le scraping dynamique.</p>
          <a class="btn" href="/rules.php">Gérer</a>
        </div>
      </div>
      <div class="col">
        <div class="card">
          <h3>Test Fetch</h3>
          <p class="muted">Tester l’accessibilité d’un site ou d’une URL.</p>
          <a class="btn" href="/test.php">Ouvrir</a>
        </div>
      </div>
    </div>
  </div>
</body>
</html>
