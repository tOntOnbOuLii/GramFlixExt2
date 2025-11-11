<section class="card">
    <h2>Vue d'ensemble</h2>
    <div class="stats">
        <div>
            <p class="label">Providers</p>
            <strong><?= count($initialState['providers']['providers'] ?? []) ?></strong>
        </div>
        <div>
            <p class="label">Hosters</p>
            <strong><?= count($initialState['hosters']['hosters'] ?? []) ?></strong>
        </div>
        <div>
            <p class="label">Règles</p>
            <strong><?= count($initialState['rules']['rules'] ?? []) ?></strong>
        </div>
        <div>
            <p class="label">Utilisateurs</p>
            <strong><?= count($initialState['users'] ?? []) ?></strong>
        </div>
    </div>
</section>

<section class="card" id="diagnostics">
    <h2>Diagnostics data/</h2>
    <ul class="diagnostics">
        <?php foreach ($initialState['diagnostics'] as $name => $info): ?>
            <?php if (!is_array($info)) continue; ?>
            <li>
                <strong><?= htmlspecialchars($name) ?></strong>
                <span><?= htmlspecialchars($info['path'] ?? '') ?></span>
                <span class="pill <?= !empty($info['writable']) ? 'ok' : 'warn' ?>">
                    <?= !empty($info['writable']) ? 'OK' : 'Bloque' ?>
                </span>
            </li>
        <?php endforeach; ?>
    </ul>
</section>

<section class="card" id="history">
    <h2>Historique recent</h2>
    <div id="history-app"></div>
</section>

<section class="card" id="github">
    <h2>Synchronisation GitHub</h2>
    <?php if (!empty($initialState['github']['enabled'])): ?>
        <p>Depot: <?= htmlspecialchars(($initialState['github']['owner'] ?? '?') . '/' . ($initialState['github']['repo'] ?? '?')) ?> (branche <?= htmlspecialchars($initialState['github']['branch'] ?? 'main') ?>)</p>
        <button class="btn btn-primary" data-github-sync>Synchroniser maintenant</button>
        <p class="muted" id="github-status"></p>
    <?php else: ?>
        <p class="muted">Configurez GITHUB_* dans config.local.php pour activer la synchronisation.</p>
    <?php endif; ?>
</section>
