"""Sets up the real Doze test on a phone: two [TEST GGCAL] events, a popup and an alarm, due about 80 and 85 minutes from now.

  python doze_real_setup.py --serial RZGYB28HPTJ

Nothing is waited for and nothing is cleaned up: the phone is then unplugged and left alone (still, screen off) for the hour,
and doze_real_read.py reads the result when it is plugged in again. The events carry the tag of this run (saved in the state file);
doze_real_read.py --clean removes only those, so other runs on the same account are never touched.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import flows as F  # noqa: E402
import harness  # noqa: E402
import cases_g as G  # noqa: E402
from adbx import Device  # noqa: E402

STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs", "doze_real.json")

ap = argparse.ArgumentParser()
ap.add_argument("--serial", required=True)
ap.add_argument("--account", default="profgusinu@gmail.com")
ap.add_argument("--popup-min", type=int, default=80)
ap.add_argument("--alarm-min", type=int, default=85)
a = ap.parse_args()

d = Device(a.serial, os.path.join(os.path.dirname(STATE), "doze_real_shots"))
ctx = harness.Ctx(d, a.account, None)
# the screen stays on while the events are created, and the phone's own setting is put back before the end
stay_on = d.shell("settings get global stay_on_while_plugged_in").strip() or "0"
d.shell("svc power stayon true")
d.shell("input keyevent 224")
F.start_app(ctx)
# a bigger log buffer keeps the app's lines for the whole hour; the battery history gets the wake locks too
d.shell("logcat -G 8M")
d.shell("dumpsys batterystats --enable full-wake-history")
t0 = G.now(ctx)
pop = G.create_with_reminders(ctx, "z_popup", "zpopup", [("POPUP", a.popup_min * 60)], t0)
alm = G.create_with_reminders(ctx, "z_alarm", "zalarm", [("ALARM", a.alarm_min * 60)], t0)
F.home(ctx)
d.shell(f"settings put global stay_on_while_plugged_in {stay_on}")
out = dict(serial=a.serial, run_tag=ctx.run_tag, popup_title=pop["title"], popup_due=pop["fires"][0].isoformat(), alarm_title=alm["title"],
           alarm_due=alm["fires"][0].isoformat(), set_up_at=G.now(ctx).isoformat())
os.makedirs(os.path.dirname(STATE), exist_ok=True)
with open(STATE, "w", encoding="utf-8") as f:
    json.dump(out, f, indent=1)
print(json.dumps(out, indent=1))
