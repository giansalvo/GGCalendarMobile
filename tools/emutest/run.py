"""Runs the emulator tests described in app_planning/test_API_emulator.md.

  python run.py --serial emulator-5556 --apk ../../dist/GGCalendar-0.4.1.apk
  python run.py --serial emulator-5556 --apk ... --only T03,T04     (a few cases)
  python run.py --serial emulator-5556 --apk ... --group G          (a whole group)
  python run.py --list                                              (the cases and what they need)

Results go to <out>/<date>_api<N>_<version>/ (screenshots, report.md, results.json), outside the repositories.
"""
import argparse
import datetime
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import flows as F  # noqa: E402
import harness  # noqa: E402
from adbx import Device  # noqa: E402

MODULES = ["cases_ab", "cases_c", "cases_d", "cases_g", "cases_h"]


def load_cases():
    for m in MODULES:
        try:
            __import__(m)
        except ModuleNotFoundError as e:
            if e.name != m:
                raise


def setup_account(ctx):
    if ctx.state.get("account_checked"):
        return
    ctx.state["account_checked"] = True
    try:
        if F.has_account(ctx):
            ctx.state["account_ok"] = True
        else:
            ctx.state["account_ok"] = bool(F.login(ctx))
    except Exception as e:  # noqa: BLE001
        print("  (login failed:", e, ")")
        ctx.state["account_ok"] = False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5556")
    ap.add_argument("--apk", default=None)
    ap.add_argument("--account", default="profgusinu@gmail.com")
    ap.add_argument("--only", default="")
    ap.add_argument("--group", default="")
    ap.add_argument("--skip-slow", action="store_true", help="skip the cases that wait minutes (reminders)")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "runs"),
                    help="results folder; inside the project but git-ignored (it holds screenshots of the real calendar)")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    load_cases()
    if a.list:
        for cid, group, title, o, _ in harness.CASES:
            tags = [k for k in ("account", "slow") if o[k]]
            print(f"{cid:6} {group}  {title}  {'[' + ','.join(tags) + ']' if tags else ''}")
        return 0

    only = {x.strip().upper() for x in a.only.split(",") if x.strip()}
    groups = {x.strip().upper() for x in a.group.split(",") if x.strip()}
    chosen = [c for c in harness.CASES
              if (not only or c[0].upper() in only) and (not groups or c[1].upper() in groups) and not (a.skip_slow and c[3]["slow"])]
    if not chosen:
        print("no case selected")
        return 2

    probe = Device(a.serial, os.path.join(a.out, "probe"))
    if not probe.booted():
        print("the emulator is not booted")
        return 2
    apk = os.path.abspath(a.apk) if a.apk else None
    if apk is None or not os.path.exists(apk):
        print("--apk is needed (the release APK under test)")
        return 2
    if not probe.pm_has():
        probe.adb("install", "-r", apk, timeout=240)
    version = probe.version_name() or "unknown"
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M")
    outdir = os.path.join(a.out, f"{stamp}_api{probe.api}_{version}")
    d = Device(a.serial, outdir)
    ctx = harness.Ctx(d, a.account, apk)
    print(f"API {d.api}, app {version}, {len(chosen)} cases -> {outdir}")

    results = []
    ctx.results = results
    d.shell("svc power stayon true")
    d.shell("settings put system screen_off_timeout 1800000")
    try:
        for entry in chosen:
            cid, group, title, opts, _ = entry
            if cid not in ("T01", "T09"):
                setup_account(ctx)
            status, detail, secs = harness.run_case(ctx, entry)
            results.append(dict(id=cid, group=group, title=title, status=status, detail=detail, seconds=round(secs, 1)))
            print(f"{cid:6} {status:5} {secs:6.1f}s  {title}" + (f"  -> {detail[:140]}" if detail and status != "PASS" else ""))
            if cid == "T09":
                setup_account(ctx)
    finally:
        try:
            if ctx.state.get("account_ok"):
                n = F.cleanup(ctx)
                print(f"cleanup: {n} test event(s) removed")
        except Exception as e:  # noqa: BLE001
            print("cleanup failed:", e)
        # leave the device as found
        for cmd in ("svc power stayon false", "settings put system screen_off_timeout 60000", "settings put system font_scale 1.0", "wm density reset", "settings put system user_rotation 0",
                    "cmd uimode night no"):
            try:
                d.shell(cmd)
            except Exception:
                pass
        try:
            d.network(True)
        except Exception:
            pass

    with open(os.path.join(outdir, "results.json"), "w", encoding="utf-8") as f:
        json.dump(dict(api=d.api, version=version, results=results), f, indent=1, ensure_ascii=False)
    counts = {s: sum(1 for r in results if r["status"] == s) for s in ("PASS", "FAIL", "SKIP")}
    with open(os.path.join(outdir, "report.md"), "w", encoding="utf-8") as f:
        f.write(f"# Emulator run: API {d.api}, app {version}, {stamp}\n\n")
        f.write(f"PASS {counts['PASS']}  FAIL {counts['FAIL']}  SKIP {counts['SKIP']}\n\n")
        f.write("| ID | Group | Result | Seconds | Case | Note |\n|---|---|---|---|---|---|\n")
        for r in results:
            note = (r["detail"] or "").replace("\n", " ").replace("|", "/")[:300]
            f.write(f"| {r['id']} | {r['group']} | {r['status']} | {r['seconds']} | {r['title']} | {note} |\n")
    print(f"\nPASS {counts['PASS']}  FAIL {counts['FAIL']}  SKIP {counts['SKIP']}  -> {os.path.join(outdir, 'report.md')}")
    return 1 if counts["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())
