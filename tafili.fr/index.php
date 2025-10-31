<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/storage.php';
require_once __DIR__ . '/lib/auth.php';
require_once __DIR__ . '/lib/csrf.php';

// First run: seed admin if no users
initialize_default_admin();

// Already logged in? go to dashboard
if (current_user()) {
    header('Location: /dashboard.php');
    exit;
}

$error = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $user = trim($_POST['username'] ?? '');
    $pass = (string)($_POST['password'] ?? '');
    $token = (string)($_POST['csrf'] ?? '');
    if (!csrf_check($token)) {
        $error = 'Requête invalide. Réessayez.';
    } elseif ($user === '' || $pass === '') {
        $error = 'Identifiants requis.';
    } else {
        if (login_attempt($user, $pass)) {
            header('Location: /dashboard.php');
            exit;
        } else {
            $error = 'Nom d’utilisateur ou mot de passe incorrect.';
        }
    }
}
?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Connexion</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="card" style="max-width:520px;margin:80px auto;">
      <h1><?= htmlspecialchars(SITE_NAME) ?></h1>
      <p class="muted">Accédez au panneau d’administration.</p>
      <?php if ($error): ?>
        <div class="flash err"><?= htmlspecialchars($error) ?></div>
      <?php endif; ?>
      <form method="post" action="/index.php">
        <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
        <label>Nom d’utilisateur</label>
        <input type="text" name="username" autocomplete="username" required />
        <label>Mot de passe</label>
        <input type="password" name="password" autocomplete="current-password" required />
        <div style="margin-top:16px; display:flex; gap:8px;">
          <button type="submit">Se connecter</button>
        </div>
      </form>
      <p class="muted" style="margin-top:12px;">Astuce: premier démarrage → utilisateur « Florian » créé automatiquement.</p>
    </div>
  </div>
</body>
</html>

