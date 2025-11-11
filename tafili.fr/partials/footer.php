<?php if (isset($initialState)): ?>
    <script>
        window.__PANEL_STATE__ = <?= json_encode($initialState, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) ?>;
    </script>
    <script src="assets/app.js" type="module"></script>
<?php endif; ?>
</main>
</body>
</html>
