<?php if (!$isAdmin): ?>
    <section class="card">
        <p>Vous n'avez pas les droits necessaires pour acceder a cette page.</p>
    </section>
<?php else: ?>
    <section class="card">
        <h2>Utilisateurs</h2>
        <p>Gerer les comptes autorises a utiliser le panel. Les mots de passe sont hashes automatiquement.</p>
        <div id="users-app"></div>
    </section>
<?php endif; ?>
