<?php
// big.php?mb=11 (1 to 20): a calendar of more than 10 MB, made while it is sent (the app refuses it, and the site keeps no big file).
// big.php?mb=11&declared=1 sends the length first, so that the app can refuse it before reading; without it the size is found while reading.
$mb = isset($_GET['mb']) ? max(1, min(20, (int)$_GET['mb'])) : 11;
$head = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//GGCalendar//Prove//IT\r\nX-WR-CALNAME:GGCAL Prova troppo grande\r\n"
      . "BEGIN:VEVENT\r\nUID:big-1@ggcalendar.test\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20261027T090000Z\r\nDTEND:20261027T100000Z\r\n"
      . "SUMMARY:[PROVA] Un evento in un file troppo grande\r\nEND:VEVENT\r\n";
$line = "X-FILL:" . str_repeat("0123456789abcdef", 4) . "\r\n";
$foot = "END:VCALENDAR\r\n";
$lines = intdiv($mb * 1024 * 1024, strlen($line));
header('Content-Type: text/calendar; charset=utf-8');
header('Cache-Control: no-store');
if (isset($_GET['declared'])) { header('Content-Length: ' . (strlen($head) + $lines * strlen($line) + strlen($foot))); }
echo $head;
for ($i = 0; $i < $lines; $i++) { echo $line; if ($i % 2000 === 0) { flush(); } }
echo $foot;
