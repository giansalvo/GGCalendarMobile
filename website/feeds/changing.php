<?php
// changing.php?tag=RUN: one address whose content the test changes while it runs, so that "update the subscription" can be tried with
// no one uploading files by hand. Only for the tests of GGCalendar.
//   changing.php?tag=RUN               gives what is set for RUN (version 1 at first)
//   changing.php?tag=RUN&set=2         sets it and answers "ok RUN 2" (plain text, not a calendar)
//   values of set: 1 (ggcal_change_v1.ics), 2 (ggcal_change_v2.ics), error (answers 500), html (a web page), empty (no content)
// The tag is part of the address, so that each run has its own state (and the host does not give back an answer it has already seen).
// The state is a small file per tag, removed after a day; at most 200 are kept.
header('Cache-Control: no-store');
$tag = isset($_GET['tag']) ? $_GET['tag'] : '';
if (!preg_match('/^[A-Za-z0-9_-]{1,40}$/', $tag)) {
    http_response_code(400);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Serve ?tag=SIGLA (lettere, cifre, - e _, al massimo 40)\n";
    exit;
}
$dir = __DIR__ . '/state';
if (!is_dir($dir)) { @mkdir($dir, 0755); }
$file = $dir . '/' . $tag . '.txt';
// housekeeping
$old = glob($dir . '/*.txt') ?: [];
foreach ($old as $f) { if (filemtime($f) < time() - 86400) { @unlink($f); } }
if (count($old) > 200 && !is_file($file)) {
    http_response_code(503);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Troppi stati di prova\n";
    exit;
}

$allowed = ['1', '2', 'error', 'html', 'empty'];
if (isset($_GET['set'])) {
    $value = (string)$_GET['set'];
    header('Content-Type: text/plain; charset=utf-8');
    if (!in_array($value, $allowed, true) || @file_put_contents($file, $value) === false) {
        http_response_code(400);
        echo "set non valido o cartella non scrivibile\n";
        exit;
    }
    echo "ok $tag $value\n";
    exit;
}

$state = is_file($file) ? trim((string)file_get_contents($file)) : '1';
switch ($state) {
    case 'error':
        http_response_code(500);
        header('Content-Type: text/plain; charset=utf-8');
        echo "Risposta di prova: 500\n";
        break;
    case 'html':
        header('Content-Type: text/html; charset=utf-8');
        echo "<!doctype html><html><head><title>Non un calendario</title></head><body><p>Questa non \u{e8} una risposta in formato iCalendar.</p></body></html>\n";
        break;
    case 'empty':
        header('Content-Type: text/calendar; charset=utf-8');
        header('Content-Length: 0');
        break;
    default:
        $name = $state === '2' ? 'ggcal_change_v2.ics' : 'ggcal_change_v1.ics';
        header('Content-Type: text/calendar; charset=utf-8');
        readfile(__DIR__ . '/' . $name);
}
