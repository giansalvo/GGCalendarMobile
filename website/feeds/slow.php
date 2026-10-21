<?php
// slow.php?seconds=70 (at most 120): waits, then gives a small calendar. The app gives up after 60 seconds.
$seconds = isset($_GET['seconds']) ? max(0, min(120, (int)$_GET['seconds'])) : 70;
set_time_limit(150);
sleep($seconds);
header('Content-Type: text/calendar; charset=utf-8');
readfile(__DIR__ . '/ggcal_basic.ics');
