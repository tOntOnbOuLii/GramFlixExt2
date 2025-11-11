<?php
$providersCount = $providersCount ?? 0;
$hostersCount = $hostersCount ?? 0;
$usersTotal = isset($usersData) ? count($usersData) : 0;
?>
<section class="card">
    <h2>Tableau de bord</h2>
    <p>Bienvenue sur le panneau GramFlix. Utilisez les raccourcis ci-dessous pour administrer vos donnees.</p>
    <div class="grid-cards">
        <a class="dash-card" href="?view=providers">
            <span class="dash-card__title">Providers</span>
            <span class="dash-card__count"><?= $providersCount ?></span>
            <span class="dash-card__hint">Gerer jusqu'a <?= defined('MAX_PROVIDERS') ? (int) MAX_PROVIDERS : 32 ?> sites.</span>
        </a>
        <a class="dash-card" href="?view=hosters">
            <span class="dash-card__title">Hosters</span>
            <span class="dash-card__count"><?= $hostersCount ?></span>
            <span class="dash-card__hint">Mettre a jour les miroirs et modes de lecture.</span>
        </a>
        <?php if ($isAdmin): ?>
            <a class="dash-card" href="?view=users">
                <span class="dash-card__title">Utilisateurs</span>
                <span class="dash-card__count"><?= $usersTotal ?></span>
                <span class="dash-card__hint">Ajouter ou retirer des acces au panel.</span>
            </a>
        <?php endif; ?>
        <a class="dash-card" href="?view=account">
            <span class="dash-card__title">Mon compte</span>
            <span class="dash-card__count">⚙️</span>
            <span class="dash-card__hint">Modifier votre mot de passe et vos informations.</span>
        </a>
    </div>
</section>

<?php if (github_enabled() && $isAdmin): ?>
    <section class="card">
        <h3>Synchronisation GitHub</h3>
        <p class="muted">
            Depot cible :
            <code><?= htmlspecialchars((defined('GITHUB_OWNER') ? GITHUB_OWNER : '...') . '/' . (defined('GITHUB_REPO') ? GITHUB_REPO : '...')) ?></code>
            (branche <?= htmlspecialchars(defined('GITHUB_BRANCH') ? GITHUB_BRANCH : 'main') ?>)
        </p>
        <div id="sync-app"></div>
    </section>
<?php endif; ?>

<section class="card">
    <h3>Derniers changements</h3>
    <p class="muted">
        Les modifications sont sauvegardees dans <code>data/*.json</code>. Pensez a lancer votre script de synchronisation
        GitHub apres chaque serie d'updates afin de publier les nouveautes.
    </p>
</section>
