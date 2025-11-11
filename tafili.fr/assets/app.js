const state = window.__PANEL_STATE__ || {};
const navToggle = document.querySelector('[data-nav-toggle]');
const panelNav = document.getElementById('panel-nav');

if (navToggle && panelNav) {
    navToggle.addEventListener('click', () => {
        panelNav.classList.toggle('is-open');
        navToggle.setAttribute('aria-expanded', panelNav.classList.contains('is-open'));
    });
    panelNav.querySelectorAll('a').forEach(link => {
        link.addEventListener('click', () => {
            panelNav.classList.remove('is-open');
            navToggle.setAttribute('aria-expanded', 'false');
        });
    });
}
const providerTable = document.querySelector('[data-provider-table] tbody');
const hosterTable = document.querySelector('[data-hoster-table] tbody');
const historyApp = document.getElementById('history-app');
const githubButton = document.querySelector('[data-github-sync]');
const providersMessage = document.getElementById('providers-message');
const hostersMessage = document.getElementById('hosters-message');
const rulesTable = document.querySelector('[data-rules-table] tbody');
const rulesMessage = document.getElementById('rules-message');
const githubStatus = document.getElementById('github-status');
const accountForm = document.querySelector('[data-change-password]');
const accountMessage = document.getElementById('account-message');
const usersMessage = document.getElementById('users-message');
const userForm = document.querySelector('[data-user-form]');
const userTable = document.querySelector('[data-user-table] tbody');

renderHistory(state.history?.entries || []);

function renderHistory(entries) {
    if (!historyApp) return;
    if (!entries.length) {
        historyApp.innerHTML = '<p class="muted">Aucune entree pour le moment.</p>';
        return;
    }
    historyApp.innerHTML = '<ul class="history-list">' + entries.map(entry => `
        <li>
            <div><strong>${escapeHtml(entry.summary || '')}</strong> <span class="muted">${formatDate(entry.timestamp)}</span></div>
            ${renderHistoryDetails(entry.details)}
        </li>`).join('') + '</ul>';
}

function renderHistoryDetails(details) {
    if (!details || !details.length) return '';
    if (Array.isArray(details)) {
        return '<ul>' + details.map(item => `<li class="muted">${escapeHtml(typeof item === 'string' ? item : JSON.stringify(item))}</li>`).join('') + '</ul>';
    }
    return '';
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function formatDate(input) {
    if (!input) return '';
    const date = new Date(input);
    return Number.isNaN(date.getTime()) ? input : date.toLocaleString('fr-FR');
}

if (providerTable) {
    document.querySelector('[data-add-provider]').addEventListener('click', () => {
        providerTable.appendChild(buildProviderRow());
    });
    document.querySelector('[data-save-providers]').addEventListener('click', async () => {
        const payload = collectRows(providerTable, ['slug', 'name', 'baseUrl']);
        try {
            const response = await callApi('save_providers', { providers: payload });
            providersMessage.textContent = 'Providers enregistres (' + Object.keys(response.providers.providers || {}).length + ')';
            renderHistory(response.history?.entries || state.history?.entries || []);
        } catch (error) {
            providersMessage.textContent = formatError(error);
        }
    });
}

if (hosterTable) {
    document.querySelector('[data-add-hoster]').addEventListener('click', () => {
        hosterTable.appendChild(buildHosterRow());
    });
    document.querySelector('[data-save-hosters]').addEventListener('click', async () => {
        const payload = collectRows(hosterTable, ['key', 'name', 'url']);
        try {
            await callApi('save_hosters', { hosters: payload });
            hostersMessage.textContent = 'Hosters mis a jour (' + payload.length + ')';
        } catch (error) {
            hostersMessage.textContent = formatError(error);
        }
    });
}

if (rulesTable) {
    const addRuleBtn = document.querySelector('[data-add-rule]');
    const saveRulesBtn = document.querySelector('[data-save-rules]');
    if (addRuleBtn) {
        addRuleBtn.addEventListener('click', () => {
            rulesTable.appendChild(buildRuleRow());
        });
    }
    rulesTable.addEventListener('click', event => {
        const clearBtn = event.target.closest('[data-clear-rule]');
        if (!clearBtn) return;
        const row = clearBtn.closest('tr');
        if (!row) return;
        row.querySelectorAll('input').forEach(input => {
            if (input.dataset.field === 'slug') return;
            input.value = '';
        });
    });
    if (saveRulesBtn) {
        saveRulesBtn.addEventListener('click', async () => {
            const payload = collectRows(rulesTable, ['slug', 'searchPath', 'searchParam', 'itemSel', 'titleSel', 'urlSel', 'embedSel']);
            try {
                const response = await callApi('save_rules', { rules: payload });
                const count = Object.keys(response.rules?.rules || {}).length;
                if (rulesMessage) {
                    rulesMessage.textContent = 'Règles enregistrées (' + count + ')';
                }
            } catch (error) {
                if (rulesMessage) {
                    rulesMessage.textContent = formatError(error);
                }
            }
        });
    }
}

if (githubButton) {
    githubButton.addEventListener('click', async () => {
        githubButton.disabled = true;
        githubStatus.textContent = 'Synchronisation en cours...';
        try {
            const response = await callApi('github_sync');
            githubStatus.textContent = 'OK : ' + (response.results?.length || 0) + ' fichiers pousses.';
            renderHistory(response.history?.entries || state.history?.entries || []);
        } catch (error) {
            githubStatus.textContent = 'Erreur: ' + formatError(error);
        } finally {
            githubButton.disabled = false;
        }
    });
}

if (accountForm) {
    accountForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        const current = accountForm.querySelector('input[name="current"]').value;
        const next = accountForm.querySelector('input[name="next"]').value;
        try {
            await callApi('change_password', { currentPassword: current, newPassword: next });
            accountMessage.textContent = 'Mot de passe mis a jour.';
            accountForm.reset();
        } catch (error) {
            accountMessage.textContent = formatError(error);
        }
    });
}

if (userForm && userTable) {
    let editing = null;
userTable.addEventListener('click', async (event) => {
        const editBtn = event.target.closest('[data-edit-user]');
        if (editBtn) {
            const username = editBtn.getAttribute('data-edit-user');
            const data = (state.users || []).find(user => user.username === username);
            if (!data) return;
            userForm.querySelector('[data-user-username]').value = data.username;
            userForm.querySelector('[data-user-username]').disabled = true;
            userForm.querySelector('[data-user-display]').value = data.displayName;
            userForm.querySelector('[data-user-role]').value = data.role;
            userForm.querySelector('[data-user-password]').value = '';
            document.getElementById('user-form-title').textContent = 'Editer ' + data.username;
            editing = data.username;
            return;
        }
        const resetBtn = event.target.closest('[data-reset-user]');
        if (resetBtn) {
            const username = resetBtn.getAttribute('data-reset-user');
            const password = prompt('Nouveau mot de passe pour ' + username);
            if (!password) return;
            try {
                await callApi('reset_password', { username, password });
                usersMessage.textContent = 'Mot de passe reinitialise.';
            } catch (error) {
                usersMessage.textContent = formatError(error);
            }
            return;
        }
        const deleteBtn = event.target.closest('[data-delete-user]');
        if (deleteBtn) {
            const username = deleteBtn.getAttribute('data-delete-user');
            if (!confirm('Supprimer ' + username + ' ?')) return;
            try {
                const response = await callApi('delete_user', { username });
                refreshUsers(response.users || []);
                usersMessage.textContent = 'Utilisateur supprime.';
            } catch (error) {
                usersMessage.textContent = formatError(error);
            }
        }
    });

    userForm.querySelector('[data-reset-user-form]').addEventListener('click', () => {
        editing = null;
        userForm.querySelector('[data-user-username]').disabled = false;
        userForm.reset();
        document.getElementById('user-form-title').textContent = 'Ajouter un utilisateur';
    });

    userForm.querySelector('[data-save-user]').addEventListener('click', async () => {
        const username = userForm.querySelector('[data-user-username]').value;
        const displayName = userForm.querySelector('[data-user-display]').value;
        const role = userForm.querySelector('[data-user-role]').value;
        const password = userForm.querySelector('[data-user-password]').value;
        try {
            let response;
            if (editing) {
                response = await callApi('update_user', { username: editing, displayName, role });
            } else {
                response = await callApi('add_user', { username, displayName, role, password });
            }
            refreshUsers(response.users || []);
            usersMessage.textContent = 'Utilisateur enregistre.';
            userForm.reset();
            userForm.querySelector('[data-user-username]').disabled = false;
            editing = null;
            document.getElementById('user-form-title').textContent = 'Ajouter un utilisateur';
        } catch (error) {
            usersMessage.textContent = formatError(error);
        }
    });
}

function refreshUsers(users) {
    if (!userTable) return;
    state.users = users;
    userTable.innerHTML = users.map(user => `
        <tr>
            <td>${escapeHtml(user.username)}</td>
            <td>${escapeHtml(user.displayName)}</td>
            <td>${escapeHtml(user.role)}</td>
            <td>
                <button class="btn btn-sm" data-edit-user="${escapeHtml(user.username)}">Editer</button>
                <button class="btn btn-sm" data-reset-user="${escapeHtml(user.username)}">Reset</button>
                <button class="btn btn-sm btn-danger" data-delete-user="${escapeHtml(user.username)}">Supprimer</button>
            </td>
        </tr>`).join('');
}

function buildProviderRow() {
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td><input type="text" data-field="slug"></td>
        <td><input type="text" data-field="name"></td>
        <td><input type="url" data-field="baseUrl"></td>`;
    return tr;
}

function buildHosterRow() {
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td><input type="text" data-field="key"></td>
        <td><input type="text" data-field="name"></td>
        <td><input type="url" data-field="url"></td>`;
    return tr;
}

function buildRuleRow() {
    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td><input type="text" data-field="slug"></td>
        <td><input type="text" data-field="searchPath" placeholder="/search"></td>
        <td><input type="text" data-field="searchParam" placeholder="q"></td>
        <td><input type="text" data-field="itemSel" placeholder=".post a[href]"></td>
        <td><input type="text" data-field="titleSel" placeholder=".title"></td>
        <td><input type="text" data-field="urlSel" placeholder="a@href"></td>
        <td><input type="text" data-field="embedSel" placeholder="iframe@src"></td>
        <td><button type="button" class="btn btn-sm btn-link" data-clear-rule>Effacer</button></td>`;
    return tr;
}

function collectRows(tbody, fields) {
    const rows = [];
    tbody.querySelectorAll('tr').forEach(row => {
        const entry = {};
        let empty = true;
        fields.forEach(field => {
            const value = row.querySelector(`[data-field="${field}"]`)?.value.trim() || '';
            entry[field] = value;
            if (value) empty = false;
        });
        if (!empty) rows.push(entry);
    });
    return rows;
}

async function callApi(action, payload = {}) {
    const body = { action, csrf: state.csrf, ...payload };
    const response = await fetch('api.php', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const text = await response.text();
    let data;
    try {
        data = JSON.parse(text);
    } catch (error) {
        throw new Error('Reponse invalide (' + response.status + ')');
    }
    if (data.csrf) {
        state.csrf = data.csrf;
    }
    if (data.history && Array.isArray(data.history.entries)) {
        state.history = data.history.entries;
        renderHistory(state.history);
    }
    if (!response.ok || data.error) {
        throw data;
    }
    return data;
}

function formatError(err) {
    if (!err) return 'Erreur inconnue';
    if (typeof err === 'string') return err;
    if (err.message) return err.message;
    if (err.error) return err.error;
    return 'Erreur';
}
