<?php
$providersList = $initialState['providers']['providers'] ?? [];
$rulesList = $initialState['rules']['rules'] ?? [];
$allSlugs = array_keys($providersList);
foreach (array_keys($rulesList) as $slug) {
    if (!in_array($slug, $allSlugs, true)) {
        $allSlugs[] = $slug;
    }
}
natcasesort($allSlugs);
?>
<section class="card" id="rules">
    <h2>Règles d'extraction</h2>
    <p class="muted">
        Définissez les sélecteurs CSS utilisés par l'extension pour scrapper chaque site.
        Laissez un champ vide pour supprimer la règle correspondante.
    </p>
    <div class="table-wrapper table-wrapper--wide">
        <table class="table table--dense" data-rules-table>
            <thead>
                <tr>
                    <th>Slug</th>
                    <th>Search path</th>
                    <th>Param</th>
                    <th>Item selector</th>
                    <th>Titre</th>
                    <th>URL</th>
                    <th>Embed</th>
                    <th></th>
                </tr>
            </thead>
            <tbody>
            <?php foreach ($allSlugs as $slug):
                $rule = $rulesList[$slug] ?? [];
            ?>
                <tr>
                    <td><input type="text" data-field="slug" value="<?= htmlspecialchars($slug) ?>"></td>
                    <td><input type="text" data-field="searchPath" placeholder="/search" value="<?= htmlspecialchars($rule['searchPath'] ?? '') ?>"></td>
                    <td><input type="text" data-field="searchParam" placeholder="q" value="<?= htmlspecialchars($rule['searchParam'] ?? '') ?>"></td>
                    <td><input type="text" data-field="itemSel" placeholder=".post a[href]" value="<?= htmlspecialchars($rule['itemSel'] ?? '') ?>"></td>
                    <td><input type="text" data-field="titleSel" placeholder=".title" value="<?= htmlspecialchars($rule['titleSel'] ?? '') ?>"></td>
                    <td><input type="text" data-field="urlSel" placeholder="a@href" value="<?= htmlspecialchars($rule['urlSel'] ?? '') ?>"></td>
                    <td><input type="text" data-field="embedSel" placeholder="iframe@src" value="<?= htmlspecialchars($rule['embedSel'] ?? '') ?>"></td>
                    <td><button type="button" class="btn btn-sm btn-link" data-clear-rule>Effacer</button></td>
                </tr>
            <?php endforeach; ?>
            </tbody>
        </table>
    </div>
    <div class="actions">
        <button type="button" class="btn" data-add-rule>Ajouter une ligne</button>
        <button type="button" class="btn btn-primary" data-save-rules>Enregistrer</button>
    </div>
    <p class="muted" id="rules-message"></p>
</section>
