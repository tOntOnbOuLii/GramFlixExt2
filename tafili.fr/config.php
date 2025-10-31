<?php
// Basic site config
const SITE_NAME = 'GramFlix CS Admin';

// Initial admin seeding (used only if users.json is empty/missing)
const INITIAL_ADMIN_USERNAME = 'Florian';
const INITIAL_ADMIN_PASSWORD = 'F859973951';

// Session settings
const SESSION_NAME = 'cs_admin_sess';
const SESSION_SECURE = false; // set true if you enforce HTTPS only
const SESSION_HTTPONLY = true;
const SESSION_SAMESITE = 'Lax';

// Storage
const DATA_DIR = __DIR__ . '/data';
const USERS_FILE = DATA_DIR . '/users.json';

// Optional: GitHub sync for published JSONs
// Set to true and configure owner/repo/branch/token to enable automatic commits
// Note: Requires PHP cURL extension enabled on your hosting
const GITHUB_SYNC_ENABLED = false; // set true to enable
const GITHUB_OWNER = 'tOntOnbOuLii';
const GITHUB_REPO = 'GramFlixExt2';
const GITHUB_BRANCH = 'main';
const GITHUB_TOKEN = ''; // create a classic token with repo scope or use a fine-grained PAT

// Local override: do NOT commit secrets; place them in config.local.php
// Example entries in config.local.php may redefine GITHUB_* constants and SESSION_SECURE
@include_once __DIR__ . '/config.local.php';
