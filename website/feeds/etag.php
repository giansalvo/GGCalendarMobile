<?php
// etag.php?f=ggcal_basic: a calendar of the folder served with ETag and Last-Modified, and answered with 304 when the app asks for the
// copy it already has (If-None-Match / If-Modified-Since). Only for the tests of GGCalendar.
//   f=NAME      one of the .ics files of this folder, without the extension (default ggcal_basic)
//   rev=TEXT    changes the ETag without changing the content (a new "version" for the cache check); it also changes the address
//   noetag=1    no ETag header (only Last-Modified)
//   nolm=1      no Last-Modified header (only ETag)
// The static .ics files of the folder have Last-Modified only: this script is the one with an ETag.
$name = isset($_GET['f']) ? $_GET['f'] : 'ggcal_basic';
if (!preg_match('/^[A-Za-z0-9_]{1,40}$/', $name) || !is_file(__DIR__ . '/' . $name . '.ics')) {
    http_response_code(404);
    header('Cache-Control: no-store');
    header('Content-Type: text/plain; charset=utf-8');
    echo "File di prova non trovato\n";
    exit;
}
$path = __DIR__ . '/' . $name . '.ics';
$rev = isset($_GET['rev']) ? $_GET['rev'] : '';
$etag = '"' . md5_file($path) . ($rev === '' ? '' : '-' . substr(md5($rev), 0, 8)) . '"';
$modified = filemtime($path);
$useEtag = !isset($_GET['noetag']);
$useModified = !isset($_GET['nolm']);

header('Cache-Control: no-cache');
if ($useEtag) { header('ETag: ' . $etag); }
if ($useModified) { header('Last-Modified: ' . gmdate('D, d M Y H:i:s', $modified) . ' GMT'); }

$notModified = false;
$ifNoneMatch = isset($_SERVER['HTTP_IF_NONE_MATCH']) ? trim($_SERVER['HTTP_IF_NONE_MATCH']) : '';
if ($useEtag && $ifNoneMatch !== '') {
    foreach (explode(',', $ifNoneMatch) as $candidate) {
        if (trim(preg_replace('/^W\//', '', trim($candidate))) === $etag) { $notModified = true; }
    }
} elseif ($useModified && isset($_SERVER['HTTP_IF_MODIFIED_SINCE'])) {
    $since = strtotime($_SERVER['HTTP_IF_MODIFIED_SINCE']);
    if ($since !== false && $since >= $modified) { $notModified = true; }
}
if ($notModified) {
    http_response_code(304);
    exit;
}
header('Content-Type: text/calendar; charset=utf-8');
header('Content-Length: ' . filesize($path));
readfile($path);
