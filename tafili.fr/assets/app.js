const state = window.__PANEL_STATE__ || {};

const providersApp = document.getElementById('providers-app');
const hostersApp = document.getElementById('hosters-app');
const usersApp = document.getElementById('users-app');
const accountApp = document.getElementById('account-app');
const syncApp = document.getElementById('sync-app');
const globalAlerts = document.getElementById('global-alerts');

const providers = mapProviders(state.providers?.providers ?? {});
const hosters = mapHosters(state.hosters?.hosters ?? {});
const users = usersApp ? mapUsers(state.users ?? []) : [];
let syncPending = false;
const providerStatusMap = new Map();
let providerHealthLoaded = false;

renderProviders();
renderHosters();
if (usersApp) {
    renderUsers();
}
if (accountApp) {
    renderAccount();
}
if (syncApp && state.github?.enabled) {
    renderSync();
}

loadProviderHealth();

function mapProviders(source) {
    return Object.entries(source).map(([slug, data]) => ({
        slug,
        name: data.name ?? '',
        baseUrl: data.baseUrl ?? '',
    }));
}

function mapHosters(source) {
    return Object.entries(source).map(([key, data]) => ({
        key,
        name: data.name ?? '',
        url: data.url ?? '',
    }));
}

function mapUsers(source) {
    return source.map(entry => ({
        username: entry.username ?? '',
        displayName: entry.displayName ?? '',
        role: entry.role ?? 'editor',
        createdAt: entry.createdAt ?? null,
        updatedAt: entry.updatedAt ?? null,
    }));
}

function renderProviders(message) {
    if (!providersApp) return;
    const rows = providers.map((item, index) => providerRowTemplate(item, index)).join('');
    const canAdd = providers.length < state.max;

    providersApp.innerHTML = `
        ${renderMessage(message)}
        <div class="table-wrapper">
            <table class="table">
                <thead>
                <tr>
                    <th>#</th>
                    <th>Slug</th>
                    <th>Nom affiche</th>
                    <th>Base URL &amp; Statut</th>
                    <th></th>
                </tr>
                </thead>
                <tbody>
                    ${rows || `<tr><td colspan="5" class="muted">Aucun provider pour le moment.</td></tr>`}
                </tbody>
            </table>
        </div>
        <div class="actions">
            <button class="btn btn-ghost" id="add-provider" ${canAdd ? '' : 'disabled'}>+ Ajouter</button>
            <span class="muted">Limite : ${providers.length}/${state.max}</span>
            <span class="grow"></span>
            <button class="btn btn-primary" id="save-providers">Enregistrer</button>
        </div>
    `;

    providersApp.querySelector('#add-provider')?.addEventListener('click', () => {
        if (providers.length >= state.max) return;
        providers.push({ slug: '', name: '', baseUrl: '' });
        renderProviders();
    });

    providersApp.querySelector('#save-providers')?.addEventListener('click', saveProviders);

    providersApp.querySelectorAll('[data-provider-row]').forEach(row => {
        const index = Number(row.dataset.index);
        row.querySelectorAll('input').forEach(input => {
            input.addEventListener('input', event => {
                const target = event.target;
                if (!(target instanceof HTMLInputElement)) return;
                providers[index][target.name] = target.value;
            });
        });
        row.querySelector('[data-remove]')?.addEventListener('click', () => {
            providers.splice(index, 1);
            renderProviders();
        });
    });
}

function providerRowTemplate(item, index) {
    return `
        <tr data-provider-row data-index="${index}">
            <td>${index + 1}</td>
            <td><input type="text" name="slug" placeholder="ex: 1jour1film" value="${escapeHtml(item.slug)}" required></td>
            <td><input type="text" name="name" placeholder="Nom lisible" value="${escapeHtml(item.name)}" required></td>
            <td class="url-cell">
                <input type="url" name="baseUrl" placeholder="https://exemple.com/" value="${escapeHtml(item.baseUrl)}" required>
                ${renderStatusPill(item.slug)}
            </td>
            <td><button class="btn btn-ghost" type="button" data-remove>&times;</button></td>
        </tr>
    `;
}

async function saveProviders() {
    const payload = {
        action: 'save_providers',
        csrf: state.csrf,
        providers: providers.map(item => ({
            slug: item.slug.trim(),
            name: item.name.trim(),
            baseUrl: item.baseUrl.trim(),
        })),
    };

    try {
        const response = await callApi(payload);
        updateCsrf(response);
        if (response.ok) {
            renderProviders({ type: 'success', text: `Providers enregistres (${response.count}).` });
            loadProviderHealth();
        } else {
            renderProviders({
                type: 'error',
                text: formatError('Impossible d\'enregistrer.', response),
            });
        }
    } catch (error) {
        renderProviders({ type: 'error', text: `Erreur reseau: ${error}` });
    }
}

function renderHosters(message) {
    if (!hostersApp) return;
    const rows = hosters.map((item, index) => hosterRowTemplate(item, index)).join('');

    hostersApp.innerHTML = `
        ${renderMessage(message)}
        <div class="table-wrapper">
            <table class="table">
                <thead>
                <tr>
                    <th>#</th>
                    <th>Identifiant</th>
                    <th>Nom</th>
                    <th>URL / Pattern</th>
                    <th></th>
                </tr>
                </thead>
                <tbody>
                    ${rows || `<tr><td colspan="5" class="muted">Aucun hoster pour le moment.</td></tr>`}
                </tbody>
            </table>
        </div>
        <div class="actions">
            <button class="btn btn-ghost" id="add-hoster">+ Ajouter</button>
            <span class="grow"></span>
            <button class="btn btn-primary" id="save-hosters">Enregistrer</button>
        </div>
    `;

    hostersApp.querySelector('#add-hoster')?.addEventListener('click', () => {
        hosters.push({ key: '', name: '', url: '' });
        renderHosters();
    });

    hostersApp.querySelector('#save-hosters')?.addEventListener('click', saveHosters);

    hostersApp.querySelectorAll('[data-hoster-row]').forEach(row => {
        const index = Number(row.dataset.index);
        row.querySelectorAll('input').forEach(input => {
            input.addEventListener('input', event => {
                const target = event.target;
                if (!(target instanceof HTMLInputElement)) return;
                hosters[index][target.name] = target.value;
            });
        });
        row.querySelector('[data-remove]')?.addEventListener('click', () => {
            hosters.splice(index, 1);
            renderHosters();
        });
    });
}

function hosterRowTemplate(item, index) {
    return `
        <tr data-hoster-row data-index="${index}">
            <td>${index + 1}</td>
            <td><input type="text" name="key" placeholder="ex: uqload" value="${escapeHtml(item.key)}" required></td>
            <td><input type="text" name="name" placeholder="Nom" value="${escapeHtml(item.name)}" required></td>
            <td><input type="text" name="url" placeholder="https://uqload.*" value="${escapeHtml(item.url)}" required></td>
            <td><button class="btn btn-ghost" type="button" data-remove>&times;</button></td>
        </tr>
    `;
}

async function saveHosters() {
    const payload = {
        action: 'save_hosters',
        csrf: state.csrf,
        hosters: hosters.map(item => ({
            key: item.key.trim(),
            name: item.name.trim(),
            url: item.url.trim(),
        })),
    };

    try {
        const response = await callApi(payload);
        updateCsrf(response);
        if (response.ok) {
            renderHosters({ type: 'success', text: `Hosters enregistres (${response.count}).` });
        } else {
            renderHosters({
                type: 'error',
                text: formatError('Impossible d\'enregistrer.', response),
            });
        }
    } catch (error) {
        renderHosters({ type: 'error', text: `Erreur reseau: ${error}` });
    }
}

function renderUsers(message) {
    if (!usersApp) return;

    const rows = users.map(userRowTemplate).join('');

    usersApp.innerHTML = `
        ${renderMessage(message)}
        <div class="table-wrapper">
            <table class="table">
                <thead>
                <tr>
                    <th>Utilisateur</th>
                    <th>Nom affiche</th>
                    <th>Role</th>
                    <th>Derniere maj</th>
                    <th></th>
                </tr>
                </thead>
                <tbody>
                    ${rows || `<tr><td colspan="5" class="muted">Aucun utilisateur.</td></tr>`}
                </tbody>
            </table>
        </div>
        <form id="add-user-form" class="form-inline">
            <input type="text" name="username" placeholder="identifiant" required>
            <input type="text" name="displayName" placeholder="Nom affiche">
            <input type="password" name="password" placeholder="Mot de passe (min. 8 caracteres)" required>
            <select name="role">
                <option value="editor">Editeur</option>
                <option value="admin">Administrateur</option>
            </select>
            <button type="submit" class="btn btn-primary">Ajouter</button>
        </form>
        <p class="muted">Les mots de passe sont hashes automatiquement. Pensez a transmettre un mot de passe temporaire a l'utilisateur.</p>
    `;

    usersApp.querySelectorAll('[data-user-row]').forEach(row => {
        const username = row.dataset.username;
        const user = users.find(item => item.username === username);
        if (!user) return;

        row.querySelectorAll('input, select').forEach(input => {
            input.addEventListener('input', event => {
                const target = event.target;
                if (!(target instanceof HTMLInputElement) && !(target instanceof HTMLSelectElement)) return;
                user[target.name] = target.value;
            });
        });

        row.querySelector('[data-save]')?.addEventListener('click', () => updateUser(user));
        row.querySelector('[data-reset]')?.addEventListener('click', () => resetPassword(user));
        row.querySelector('[data-delete]')?.addEventListener('click', () => deleteUser(user));
    });

    usersApp.querySelector('#add-user-form')?.addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        if (!(form instanceof HTMLFormElement)) return;
        const formData = new FormData(form);
        const username = String(formData.get('username') ?? '').trim();
        const password = String(formData.get('password') ?? '').trim();
        const displayName = String(formData.get('displayName') ?? '').trim();
        const role = String(formData.get('role') ?? 'editor');

        if (username === '' || password === '') {
            renderUsers({ type: 'error', text: 'Identifiant et mot de passe requis.' });
            return;
        }

        try {
            const response = await callApi({
                action: 'add_user',
                csrf: state.csrf,
                username,
                password,
                displayName,
                role,
            });
            updateCsrf(response);
            if (response.ok) {
                updateUsers(response.users ?? []);
                form.reset();
                renderUsers({ type: 'success', text: 'Utilisateur ajoute.' });
            } else {
                renderUsers({ type: 'error', text: formatError('Creation impossible.', response) });
            }
        } catch (error) {
            renderUsers({ type: 'error', text: `Erreur reseau: ${error}` });
        }
    });
}

function userRowTemplate(user) {
    const updated = user.updatedAt ? formatDate(user.updatedAt) : '—';
    return `
        <tr data-user-row data-username="${escapeHtml(user.username)}">
            <td><code>${escapeHtml(user.username)}</code></td>
            <td><input type="text" name="displayName" value="${escapeHtml(user.displayName)}" placeholder="Nom affiche"></td>
            <td>
                <select name="role">
                    <option value="editor" ${user.role === 'editor' ? 'selected' : ''}>Editeur</option>
                    <option value="admin" ${user.role === 'admin' ? 'selected' : ''}>Administrateur</option>
                </select>
            </td>
            <td class="muted">${escapeHtml(updated)}</td>
            <td class="users-actions">
                <button type="button" class="btn btn-ghost" data-save>Enregistrer</button>
                <button type="button" class="btn btn-ghost" data-reset>Nouveau mot de passe</button>
                <button type="button" class="btn btn-ghost" data-delete>Supprimer</button>
            </td>
        </tr>
    `;
}

async function updateUser(user) {
    try {
        const response = await callApi({
            action: 'update_user',
            csrf: state.csrf,
            username: user.username,
            displayName: user.displayName,
            role: user.role,
        });
        updateCsrf(response);
        if (response.ok) {
            updateUsers(response.users ?? []);
            renderUsers({ type: 'success', text: 'Utilisateur mis a jour.' });
        } else {
            renderUsers({ type: 'error', text: formatError('Impossible de mettre a jour.', response) });
        }
    } catch (error) {
        renderUsers({ type: 'error', text: `Erreur reseau: ${error}` });
    }
}

async function deleteUser(user) {
    if (!confirm(`Supprimer l'utilisateur ${user.username} ?`)) {
        return;
    }
    try {
        const response = await callApi({
            action: 'delete_user',
            csrf: state.csrf,
            username: user.username,
        });
        updateCsrf(response);
        if (response.ok) {
            updateUsers(response.users ?? []);
            renderUsers({ type: 'success', text: 'Utilisateur supprime.' });
        } else {
            renderUsers({ type: 'error', text: formatError('Suppression impossible.', response) });
        }
    } catch (error) {
        renderUsers({ type: 'error', text: `Erreur reseau: ${error}` });
    }
}

async function resetPassword(user) {
    const password = prompt(`Nouveau mot de passe pour ${user.username} (min. 8 caracteres) :`);
    if (password === null) {
        return;
    }
    const trimmed = password.trim();
    if (trimmed.length < 8) {
        renderUsers({ type: 'error', text: 'Mot de passe trop court (min. 8 caracteres).' });
        return;
    }
    try {
        const response = await callApi({
            action: 'reset_password',
            csrf: state.csrf,
            username: user.username,
            password: trimmed,
        });
        updateCsrf(response);
        if (response.ok) {
            updateUsers(response.users ?? []);
            renderUsers({ type: 'success', text: 'Mot de passe mis a jour.' });
        } else {
            renderUsers({ type: 'error', text: formatError('Reset impossible.', response) });
        }
    } catch (error) {
        renderUsers({ type: 'error', text: `Erreur reseau: ${error}` });
    }
}

function renderAccount(message) {
    if (!accountApp) return;
    accountApp.innerHTML = `
        ${renderMessage(message)}
        <form id="password-form" class="form-grid max-360">
            <label>
                Mot de passe actuel
                <input type="password" name="currentPassword" autocomplete="current-password" required>
            </label>
            <label>
                Nouveau mot de passe
                <input type="password" name="newPassword" autocomplete="new-password" required>
            </label>
            <label>
                Confirmation
                <input type="password" name="confirmPassword" autocomplete="new-password" required>
            </label>
            <button type="submit" class="btn btn-primary">Modifier le mot de passe</button>
        </form>
        <p class="muted">Conseil : choisissez un mot de passe unique et mettez-le a jour regulierement.</p>
    `;

    accountApp.querySelector('#password-form')?.addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        if (!(form instanceof HTMLFormElement)) return;

        const formData = new FormData(form);
        const currentPassword = String(formData.get('currentPassword') ?? '');
        const newPassword = String(formData.get('newPassword') ?? '');
        const confirmPassword = String(formData.get('confirmPassword') ?? '');

        if (newPassword !== confirmPassword) {
            renderAccount({ type: 'error', text: 'La confirmation ne correspond pas.' });
            return;
        }

        try {
            const response = await callApi({
                action: 'change_password',
                csrf: state.csrf,
                currentPassword,
                newPassword,
            });
            updateCsrf(response);
            if (response.ok) {
                form.reset();
                renderAccount({ type: 'success', text: 'Mot de passe modifie.' });
            } else {
                renderAccount({ type: 'error', text: formatError('Modification impossible.', response) });
            }
        } catch (error) {
            renderAccount({ type: 'error', text: `Erreur reseau: ${error}` });
        }
    });
}

function renderSync(message, logHtml = '') {
    if (!syncApp || !state.github?.enabled) return;
    const info = state.github ?? {};
    const repoLabel = info.owner && info.repo ? `${info.owner}/${info.repo}` : 'Depot';
    const branchLabel = info.branch ?? 'main';

    syncApp.innerHTML = `
        ${renderMessage(message)}
        <p class="sync-meta">Depôt cible : <strong>${escapeHtml(repoLabel)}</strong> · branche <strong>${escapeHtml(branchLabel)}</strong></p>
        <div class="actions">
            <button class="btn btn-primary" id="sync-trigger" ${syncPending ? 'disabled' : ''}>
                ${syncPending ? 'Synchronisation en cours...' : 'Synchroniser maintenant'}
            </button>
        </div>
        <div id="sync-log" class="sync-log">${logHtml}</div>
    `;

    if (!syncPending) {
        syncApp.querySelector('#sync-trigger')?.addEventListener('click', runSync);
    }
}

async function runSync() {
    if (syncPending) return;
    try {
        syncPending = true;
        renderSync({ type: 'info', text: 'Synchronisation en cours...' });
        const response = await callApi({ action: 'github_sync', csrf: state.csrf });
        syncPending = false;
        updateCsrf(response);
        if (response.ok) {
            const lines = (response.results ?? []).map(item => {
                const status = item.status === 'updated' ? 'status-updated' : 'status-unchanged';
                return `<span class="${status}">${escapeHtml(item.path)} · ${escapeHtml(item.status)}</span>`;
            }).join('') || '<span class="muted">Aucun changement detecte.</span>';
            renderSync(
                { type: 'success', text: `Synchronisation reussie (${escapeHtml(response.branch ?? '')}).` },
                lines
            );
        } else {
            renderSync({ type: 'error', text: formatError('Synchronisation impossible.', response) });
        }
    } catch (error) {
        syncPending = false;
        renderSync({ type: 'error', text: `Erreur reseau: ${error}` });
    }
}

async function loadProviderHealth() {
    const providerEntries = state.providers?.providers;
    if (!providerEntries || Object.keys(providerEntries).length === 0) {
        renderHealthAlert([]);
        return;
    }
    try {
        const response = await callApi({ action: 'check_providers', csrf: state.csrf });
        updateCsrf(response);
        if (!response?.ok || !Array.isArray(response.items)) {
            if (response?.error) {
                renderHealthAlert([], `Verification des URLs impossible (${response.error}).`);
            }
            return;
        }
        providerStatusMap.clear();
        const issues = [];
        response.items.forEach(item => {
            const key = (item.slug ?? '').toLowerCase();
            if (key) {
                providerStatusMap.set(key, item);
                if (!item.ok) {
                    issues.push(item);
                }
            }
        });
        providerHealthLoaded = true;
        renderProviders();
        renderHealthAlert(issues);
    } catch (error) {
        renderHealthAlert([], `Erreur verification URLs: ${error}`);
    }
}

function renderHealthAlert(issues, errorMessage) {
    if (!globalAlerts) return;
    if (errorMessage) {
        globalAlerts.innerHTML = `<div class="alert alert-error">${escapeHtml(errorMessage)}</div>`;
        return;
    }
    if (!issues || issues.length === 0) {
        globalAlerts.innerHTML = '';
        return;
    }
    const intro = issues.length > 1
        ? `${issues.length} URLs semblent hors ligne.`
        : 'Une URL semble hors ligne.';
    const items = issues.map(item => {
        const base = `${escapeHtml(item.slug ?? '')} → ${escapeHtml(item.baseUrl ?? '')}`;
        const detail = item.message ? ` (${escapeHtml(item.message)})` : '';
        return `<li>${base}${detail}</li>`;
    }).join('');
    globalAlerts.innerHTML = `
        <div class="alert alert-error">
            <p>${intro}</p>
            <ul>${items}</ul>
        </div>
    `;
}

async function callApi(body) {
    const response = await fetch('api.php', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    return await response.json();
}

function updateUsers(nextUsers) {
    users.length = 0;
    users.push(...mapUsers(nextUsers));
}

function updateCsrf(response) {
    if (response && typeof response.csrf === 'string') {
        state.csrf = response.csrf;
    }
}

function getProviderStatus(slug) {
    if (!slug) return null;
    return providerStatusMap.get(slug.trim().toLowerCase()) ?? null;
}

function renderStatusPill(slug) {
    const status = getProviderStatus(slug);
    if (!providerHealthLoaded) {
        return `<span class="status-pill muted">Scan en cours...</span>`;
    }
    if (!status) {
        return `<span class="status-pill muted">Non verifie</span>`;
    }
    const cls = status.ok ? 'status-pill ok' : 'status-pill warn';
    const label = status.ok ? 'En ligne' : 'A verifier';
    const code = status.httpCode ? ` (${status.httpCode})` : '';
    const details = status.message || (status.ok ? 'Serveur accessible' : 'Serveur injoignable');
    return `<span class="${cls}" title="${escapeHtml(details)}">${label}${code}</span>`;
}

function renderMessage(message) {
    if (!message) return '';
    const classMap = {
        error: 'alert alert-error',
        success: 'alert alert-success',
        info: 'alert alert-info',
    };
    const cls = classMap[message.type] ?? 'alert alert-success';
    return `<p class="${cls}">${message.text}</p>`;
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function formatError(prefix, response) {
    let base = response?.error ? `${prefix} (${response.error}).` : `${prefix}`;
    if (Array.isArray(response?.details) && response.details.length > 0) {
        const safeDetails = response.details.map(item => escapeHtml(String(item)));
        return `${base}<br><span class="muted">${safeDetails.join('<br>')}</span>`;
    }
    if (response?.min) {
        return `${base}<br><span class="muted">Minimum requis: ${response.min}</span>`;
    }
    if (response?.message) {
        base += `<br><span class="muted">${escapeHtml(response.message)}</span>`;
    }
    return base;
}

function formatDate(input) {
    try {
        const date = new Date(input);
        if (Number.isNaN(date.getTime())) return input;
        return date.toLocaleString();
    } catch {
        return input;
    }
}
