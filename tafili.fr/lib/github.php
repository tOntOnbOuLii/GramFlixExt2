<?php
// Minimal GitHub Contents API helper (optional)
require_once __DIR__ . '/../config.php';

// Configure these constants in config.php if you want automatic sync
// const GITHUB_SYNC_ENABLED = false;
// const GITHUB_OWNER = 'tOntOnbOuLii';
// const GITHUB_REPO = 'GramFlixExt2';
// const GITHUB_BRANCH = 'main';
// const GITHUB_TOKEN = '';

function github_sync_enabled(): bool {
    return defined('GITHUB_SYNC_ENABLED') && GITHUB_SYNC_ENABLED === true && defined('GITHUB_TOKEN') && GITHUB_TOKEN !== '';
}

function github_put_file(string $path, string $content, string $message): bool {
    if (!github_sync_enabled()) return false;
    $owner = GITHUB_OWNER; $repo = GITHUB_REPO; $branch = GITHUB_BRANCH;
    $api = "https://api.github.com/repos/$owner/$repo/contents/" . ltrim($path, '/');
    $headers = [
        'Content-Type: application/json',
        'Authorization: token ' . GITHUB_TOKEN,
        'User-Agent: cs-admin-panel'
    ];
    // Get existing file SHA
    $sha = null;
    $ch = curl_init($api . '?ref=' . urlencode($branch));
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);
    $res = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($code === 200) {
        $obj = json_decode($res, true);
        $sha = $obj['sha'] ?? null;
    }

    $payload = json_encode([
        'message' => $message,
        'content' => base64_encode($content),
        'branch' => $branch,
        'sha' => $sha,
    ]);

    $ch2 = curl_init($api);
    curl_setopt($ch2, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch2, CURLOPT_HTTPHEADER, $headers);
    curl_setopt($ch2, CURLOPT_CUSTOMREQUEST, 'PUT');
    curl_setopt($ch2, CURLOPT_POSTFIELDS, $payload);
    curl_exec($ch2);
    $code2 = curl_getinfo($ch2, CURLINFO_HTTP_CODE);
    curl_close($ch2);
    return $code2 >= 200 && $code2 < 300;
}

