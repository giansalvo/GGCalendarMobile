<?php
// status.php?code=404 (or 403, 410, 429, 500, 503): answers with that status and a short text. Only for the tests of GGCalendar.
$allowed = [301, 302, 304, 401, 403, 404, 410, 418, 429, 500, 503];
$code = isset($_GET['code']) ? (int)$_GET['code'] : 404;
if (!in_array($code, $allowed, true)) { $code = 404; }
http_response_code($code);
header('Cache-Control: no-store');
header('Content-Type: text/plain; charset=utf-8');
echo "Risposta di prova: $code\n";
