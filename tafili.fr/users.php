<?php
require_once __DIR__ . '/config.php';
require_once __DIR__ . '/lib/auth.php';
require_once __DIR__ . '/lib/storage.php';
require_once __DIR__ . '/lib/csrf.php';

require_admin();
$me = current_user();
$users = load_users();

$flash = $_GET['ok'] ?? '';
$err = $_GET['err'] ?? '';
?>
<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title><?= htmlspecialchars(SITE_NAME) ?> · Utilisateurs</title>
  <link rel="stylesheet" href="/public/css/styles.css" />
</head>
<body>
  <div class="wrap">
    <div class="topbar">
      <h2>Utilisateurs</h2>
      <div>
        <a class="btn" href="/dashboard.php">Tableau de bord</a>
        <a class="btn" style="margin-left:8px;" href="/logout.php">Se déconnecter</a>
      </div>
    </div>
    <?php if ($flash): ?><div class="flash"><?= htmlspecialchars($flash) ?></div><?php endif; ?>
    <?php if ($err): ?><div class="flash err"><?= htmlspecialchars($err) ?></div><?php endif; ?>

    <div class="row">
      <div class="col">
        <div class="card">
          <h3>Ajouter un utilisateur</h3>
          <form method="post" action="/actions/add_user.php">
            <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
            <label>Nom d’utilisateur</label>
            <input type="text" name="username" required />
            <label>Mot de passe</label>
            <input type="password" name="password" required />
            <label>Rôle</label>
            <select name="role">
              <option value="user">Utilisateur</option>
              <option value="admin">Administrateur</option>
            </select>
            <div style="margin-top:12px;"><button type="submit">Ajouter</button></div>
          </form>
        </div>
      </div>
      <div class="col">
        <div class="card">
          <h3>Liste des utilisateurs</h3>
          <div class="table-wrap">
          <table class="table responsive">
            <thead><tr><th>Nom</th><th>Rôle</th><th>Créé</th><th>Actions</th></tr></thead>
            <tbody>
              <?php foreach ($users as $u): ?>
                <tr>
                  <td data-label="Nom"><?= htmlspecialchars($u['username']) ?></td>
                  <td data-label="Rôle"><?= htmlspecialchars($u['role'] ?? 'user') ?></td>
                  <td data-label="Créé le"><?= isset($u['createdAt']) ? date('Y-m-d H:i', $u['createdAt']) : '-' ?></td>
                  <td data-label="Actions">
                    <?php if (strcasecmp($u['username'], $me['username']) !== 0): ?>
                      <form method="post" action="/actions/delete_user.php" style="display:inline" onsubmit="return confirm('Supprimer cet utilisateur ?');">
                        <input type="hidden" name="csrf" value="<?= htmlspecialchars(csrf_token()) ?>" />
                        <input type="hidden" name="username" value="<?= htmlspecialchars($u['username']) ?>" />
                        <button class="btn danger" type="submit">Supprimer</button>
                      </form>
                    <?php else: ?>
                      <span class="muted">(vous)</span>
                    <?php endif; ?>
                  </td>
                </tr>
              <?php endforeach; ?>
            </tbody>
          </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</body>
</html>
