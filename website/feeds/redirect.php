<?php
// redirect.php?kind=ok | chain | to_http | to_other: the redirects the app must handle. The targets are fixed here (no open redirect).
$base = (isset($_SERVER['HTTPS']) ? 'https' : 'http') . '://' . $_SERVER['HTTP_HOST'] . dirname($_SERVER['SCRIPT_NAME']);
$kind = isset($_GET['kind']) ? $_GET['kind'] : 'ok';
switch ($kind) {
    case 'ok':       header('Location: ' . $base . '/ggcal_basic.ics', true, 302); break;           // one hop to a good file
    case 'to_http':  header('Location: http://example.org/calendar.ics', true, 302); break;           // to an address that is not https: refused
    case 'chain':    $n = isset($_GET['n']) ? (int)$_GET['n'] : 0;                                    // a chain: after 5 hops the app stops
                     header('Location: ' . $base . '/redirect.php?kind=chain&n=' . ($n + 1), true, 302); break;
    default:         http_response_code(404);
}
