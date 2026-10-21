<?php
// slow.php?s=25-anything: waits that many seconds (at most 29: the host of Altervista stops a script after about 30), then gives a small
// calendar. The app waits at most 20 seconds for the first byte, so 25 ends with a timeout. The text after the minus sign changes the
// address (the host keeps the answer of an address it has already seen): the tests put the tag of the run there.
$raw = isset($_GET['s']) ? $_GET['s'] : (isset($_GET['seconds']) ? $_GET['seconds'] : '25');
$seconds = max(0, min(29, (int)$raw));
header('Cache-Control: no-store');
sleep($seconds);
header('Content-Type: text/calendar; charset=utf-8');
readfile(__DIR__ . '/ggcal_basic.ics');
