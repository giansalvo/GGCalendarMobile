"""Reads the result of the real Doze test prepared by doze_real_setup.py, with the phone plugged in again.

  python doze_real_read.py --serial RZGYB28HPTJ           (evidence: what the app received and when, what the phone was doing)
  python doze_real_read.py --serial RZGYB28HPTJ --clean   (removes the two test events: only the ones of this run, by its tag)

Do not open the app before reading: the point is to see the state the phone was left in. The evidence comes from
`dumpsys alarm` (alarms delivered by the app, alarms it cancelled and when) and `logcat` (notifications posted, Doze progression).
"""
import argparse
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbx import ADB  # noqa: E402

STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs", "doze_real.json")

ap = argparse.ArgumentParser()
ap.add_argument("--serial", required=True)
ap.add_argument("--clean", action="store_true")
a = ap.parse_args()
state = json.load(open(STATE, encoding="utf-8"))

if a.clean:
    import flows as F
    import harness
    from adbx import Device

    d = Device(a.serial, os.path.join(os.path.dirname(STATE), "doze_real_shots"))
    ctx = harness.Ctx(d, "profgusinu@gmail.com", None, run_tag=state["run_tag"])
    stay_on = d.shell("settings get global stay_on_while_plugged_in").strip() or "0"
    d.shell("svc power stayon true")
    d.shell("input keyevent 224")
    try:
        F.start_app(ctx)
        print("removed:", F.cleanup(ctx))
    finally:
        d.shell(f"settings put global stay_on_while_plugged_in {stay_on}")
    sys.exit(0)


def adb(*args, timeout=120):
    return subprocess.run([ADB, "-s", a.serial, *args], capture_output=True, timeout=timeout).stdout.decode("utf-8", "replace")


alarm = adb("shell", "dumpsys", "alarm")
log = adb("logcat", "-d", "-v", "time", "-b", "main,system,events")
print("test run:", state["run_tag"], "| popup due", state["popup_due"], "| alarm due", state["alarm_due"], "| set up", state["set_up_at"])
print("\n== alarms of the app delivered (wakeup history with the app uid)")
for line in alarm.splitlines():
    if "io.github.giansalvo.ggcalendar.REMINDER_FIRE" in line and "rtc=" in line and "uid=" in line:
        print("  ", line.strip()[:150])
print("\n== alarms of the app cancelled (removed before they went off), from the removal history")
block = None
for i, line in enumerate(alarm.splitlines()):
    if re.match(r"^    u0a\d+:$", line):
        block = line.strip()
    if block and "Reason=alarm_cancelled" in line:
        nxt = alarm.splitlines()[i + 2] if i + 2 < len(alarm.splitlines()) else ""
        if "ggcalendar" in nxt:
            print("  ", line.strip()[:120], "|", nxt.strip()[:80])
print("\n== popups and alarm screens of the app in the log")
for line in log.splitlines():
    if "io.github.giansalvo.ggcalendar" in line and ("notification_enqueue" in line or "AlarmService" in line or "alarm sound" in line):
        print("  ", line[:150])
print("\n== Doze progression in the log")
for line in log.splitlines():
    if "DeviceIdleController" in line and ("deep" in line.lower() or "idle" in line.lower()) and "state" in line.lower():
        print("  ", line[:150])
