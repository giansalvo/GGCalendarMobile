"""Group G: reminders, notifications and alarms. The core of the app; slow because it waits for real clock time.

The events are created for the next full hour and the reminders use a custom "N minutes before", so each one fires a
few minutes after it was saved, whatever the time of day. Only events titled [TEST-EMU] ..., in the primary calendar.
"""
import math
import re
import time
from datetime import datetime, timedelta

import flows as F
from adbx import PKG
from harness import case
from strings import S, has

SERVICE = f"{PKG}"


# ---------------------------------------------------------------- helpers
def now(ctx):
    return F.device_now(ctx)


def wait_for_hour_margin(ctx, minutes=9):
    """Events start at the next full hour: with less than `minutes` to it, wait for the hour to pass."""
    n = now(ctx)
    nxt = n.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
    left = (nxt - n).total_seconds() / 60
    if left < minutes:
        ctx.note(f"waited {int(left * 60) + 30} s for the hour to pass")
        time.sleep(left * 60 + 30)


def create_with_reminders(ctx, key, title, specs):
    """Creates an event whose reminders fire `lead` seconds after they are added.

    specs: [(METHOD, lead_seconds)]. Records ctx.state[key] = {title, fires: [datetime]} and returns it."""
    wait_for_hour_margin(ctx)
    fires = []

    def reminders():
        n = now(ctx)
        start = n.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
        out = []
        for method, lead in specs:
            minutes = max(1, math.ceil(((start - n).total_seconds() - lead) / 60))
            out.append((method, minutes))
            fires.append(start - timedelta(minutes=minutes))
        return out

    full = F.create_event(ctx, title, reminders=reminders)
    ctx.state[key] = dict(title=full, fires=sorted(fires))
    return ctx.state[key]


def wait_until(ctx, moment, margin=0):
    left = (moment - now(ctx)).total_seconds() + margin
    if left > 0:
        time.sleep(left)


def notification_dump(ctx):
    return ctx.d.shell("dumpsys notification --noredact", timeout=120)


def notified(ctx, title):
    """The app has a notification showing this title."""
    out = notification_dump(ctx)
    blocks = out.split("NotificationRecord(")
    return any(PKG in b and title in b for b in blocks)


def alarm_service_running(ctx):
    return "AlarmService" in ctx.d.shell(f"dumpsys activity services {PKG}")


def open_alarms(ctx):
    d = ctx.d
    F.home(ctx)
    F.open_drawer(ctx)
    F.drawer_item(ctx, "drawer_alarms")
    ctx.check(d.wait(timeout=10, tag="sheet_alarms"), "the active alarms sheet did not open")
    time.sleep(1.0)
    return d.dump()


def alarm_rows(ctx, title=None):
    nodes = open_alarms(ctx)
    rows = ctx.d.find(nodes, tag="alarm_row")
    if title is None:
        return rows, nodes
    inside = [n for n in nodes if n.text == title]
    return [r for r in rows if any(r.l <= n.l and n.r <= r.r and r.t <= n.t and n.b <= r.b for n in inside)], nodes


def set_alarm_duration(ctx, minutes):
    """Sets the duration of the alarm (1-10 minutes) with the slider of the notifications sheet."""
    d = ctx.d
    F.home(ctx)
    d.click(tag="btn_settings")
    d.wait(timeout=10, tag="settings_notifications")
    d.click(tag="settings_notifications")
    sl = d.wait(timeout=8, tag="notif_alarm_slider")
    ctx.check(sl, "the notifications sheet did not open")
    # 10 values along the track: drag to the exact spot
    x = sl.l + int(sl.width * (minutes - 1) / 9.0)
    d.swipe(sl.center[0], sl.center[1], x if minutes > 1 else max(sl.l - 120, 2), sl.center[1], 500)
    time.sleep(1.0)
    ctx.check(d.find(text=re.compile(rf"^{minutes} ", re.I)), f"the slider did not reach {minutes} minute(s)")
    d.back()
    time.sleep(0.6)
    d.click(tag="settings_back")
    F.home(ctx)


def show_shade(ctx):
    d = ctx.d
    d.shell("cmd statusbar expand-notifications")
    time.sleep(1.5)


def collapse_shade(ctx):
    ctx.d.shell("cmd statusbar collapse")
    time.sleep(0.8)


# ================================================================= G. reminders
@case("T50", "G", "Popup notification at the right time", account=True, slow=True)
def t50(ctx):
    d = ctx.d
    set_alarm_duration(ctx, 1)
    e1 = create_with_reminders(ctx, "g_popup", "gpopup", [("POPUP", 200)])
    e2 = create_with_reminders(ctx, "g_alarm", "galarm", [("ALARM", 330)])
    e4 = create_with_reminders(ctx, "g_early", "gearly", [("ALARM", 1500)])
    ctx.note(f"popup due {e1['fires'][0].time()}, alarm due {e2['fires'][0].time()}")
    wait_until(ctx, e1["fires"][0], 20)
    ok = notified(ctx, e1["title"])
    ctx.step("after_popup")
    ctx.check(ok, "the popup notification did not appear when due")
    ctx.state["popup_seen_at"] = now(ctx)


@case("T51", "G", "Tap on the notification opens the event", account=True, slow=True)
def t51(ctx):
    d = ctx.d
    e1 = ctx.state.get("g_popup")
    if not e1:
        ctx.skip("needs T50")
    show_shade(ctx)
    n = d.wait(timeout=8, text=re.compile(re.escape(e1["title"])))
    ctx.step("shade")
    ctx.check(n, "the notification is not in the shade")
    d.tap(*n.center)
    ctx.check(d.wait(timeout=12, tag="detail_close"), "the notification did not open the event")
    F.close_detail(ctx)


@case("T57", "G", "Active alarms list: ordered, with the time left", account=True, slow=True)
def t57(ctx):
    e = ctx.state.get("g_alarm")
    if not e:
        ctx.skip("needs T50")
    rows, nodes = alarm_rows(ctx, e["title"])
    ctx.step("active_alarms")
    ctx.check(rows, "the pending alarm is not in the list")
    texts = [n.text for n in nodes if n.text]
    ctx.check(any(re.search(r"\d+\s?(min|h|m)\b|<\s?1", t) for t in texts), f"no time left shown: {texts[:12]}")
    ctx.d.tap(*rows[0].center)
    ctx.check(ctx.d.wait(timeout=10, tag="detail_close"), "tapping a row did not open the event")
    F.close_detail(ctx)


@case("T58", "G", "Close an alarm early (with confirmation): it no longer rings", account=True, slow=True)
def t58(ctx):
    d = ctx.d
    e = ctx.state.get("g_early")
    if not e:
        ctx.skip("needs T50")
    rows, nodes = alarm_rows(ctx, e["title"])
    ctx.check(rows, "the alarm to close is not in the list")
    close = [n for n in nodes if n.rid == "alarm_close" and rows[0].t <= n.center[1] <= rows[0].b]
    ctx.check(close, "the close button of the row is missing")
    d.tap(*close[0].center)
    ctx.check(d.wait(timeout=6, tag="alarm_close_confirm"), "no confirmation before closing the alarm")
    d.click(tag="alarm_close_confirm")
    time.sleep(1.5)
    rows, nodes = alarm_rows(ctx, e["title"])
    ctx.check(not rows, "the closed alarm is still in the list")
    F.home(ctx)


@case("T52", "G", "Alarm with the screen off: full-screen alarm and ringing service", account=True, slow=True)
def t52(ctx):
    d = ctx.d
    e = ctx.state.get("g_alarm")
    if not e:
        ctx.skip("needs T50")
    F.home(ctx)
    due = e["fires"][0]
    wait_until(ctx, due, -40)
    d.sleep_screen()
    time.sleep(2)
    ctx.check(not d.screen_on(), "could not turn the screen off")
    wait_until(ctx, due, 25)
    ctx.check(d.screen_on(), "the screen did not turn on for the alarm")
    stop = d.wait(timeout=15, tag="alarm_stop")
    ctx.step("alarm_screen")
    ctx.check(stop, "the full-screen alarm did not show (no Stop button)")
    ctx.check(alarm_service_running(ctx), "the ringing service is not running")
    ctx.state["alarm_ringing"] = True


@case("T53", "G", "Stop the alarm: it goes quiet and does not ring again", account=True, slow=True)
def t53(ctx):
    d = ctx.d
    if not ctx.state.get("alarm_ringing"):
        ctx.skip("needs T52")
    d.click(tag="alarm_stop")
    ctx.check(d.gone(timeout=10, tag="alarm_stop"), "the alarm screen stayed open after Stop")
    time.sleep(3)
    ctx.check(not alarm_service_running(ctx), "the ringing service kept running after Stop")
    time.sleep(10)
    ctx.check(not d.find(tag="alarm_stop"), "the alarm rang again")
    ctx.state["alarm_ringing"] = False
    F.home(ctx)


def create_pair(ctx):
    """One event with two alarms, the second a minute after the first (for snooze, queue and missed alarm)."""
    if "g_pair" not in ctx.state:
        create_with_reminders(ctx, "g_pair", "gpair", [("ALARM", 130), ("ALARM", 70)])
    return ctx.state["g_pair"]


@case("T54", "G", "Snooze 5 minutes: it is listed as snoozed", account=True, slow=True)
def t54(ctx):
    d = ctx.d
    e = create_pair(ctx)
    ctx.note(f"alarms due {e['fires'][0].time()} and {e['fires'][1].time()}")
    F.home(ctx)
    wait_until(ctx, e["fires"][0], 12)
    stop = d.wait(timeout=20, tag="alarm_stop")
    ctx.check(stop, "the first alarm did not ring")
    d.click(tag="alarm_snooze_choice_5")
    d.click(tag="alarm_snooze")
    ctx.check(d.gone(timeout=10, tag="alarm_stop"), "the alarm screen stayed open after Snooze")
    time.sleep(2)
    rows, nodes = alarm_rows(ctx, e["title"])
    ctx.step("snoozed_in_list")
    ctx.check(d.find(nodes, text=has("snoozed")), "the snoozed alarm is not marked as snoozed in the list")
    F.home(ctx)


@case("T56", "G", "The second alarm rings after the first was handled", account=True, slow=True)
def t56(ctx):
    d = ctx.d
    e = ctx.state.get("g_pair")
    if not e:
        ctx.skip("needs T54")
    wait_until(ctx, e["fires"][1], 12)
    stop = d.wait(timeout=20, tag="alarm_stop")
    ctx.step("second_alarm")
    ctx.check(stop, "the second alarm did not ring")
    ctx.state["second_ringing_since"] = now(ctx)


@case("T55", "G", "No answer: the alarm stops by itself after the set duration and leaves a missed-alarm notice", account=True, slow=True)
def t55(ctx):
    d = ctx.d
    since = ctx.state.get("second_ringing_since")
    if not since:
        ctx.skip("needs T56")
    # the duration was set to 1 minute in T50
    time.sleep(max(0, 75 - (now(ctx) - since).total_seconds()))
    ctx.check(d.gone(timeout=10, tag="alarm_stop") or not alarm_service_running(ctx), "the alarm kept ringing past the set duration")
    ctx.check(not alarm_service_running(ctx), "the ringing service did not stop by itself")
    out = notification_dump(ctx)
    missed = [b for b in out.split("NotificationRecord(") if PKG in b and re.search(S("missed_alarm", exact=False), b)]
    ctx.step("after_missed")
    ctx.check(missed, "no 'missed alarm' notification was left")
    set_alarm_duration(ctx, 2)


@case("T59", "G", "A popup still shows after the app process was killed", account=True, slow=True)
def t59(ctx):
    d = ctx.d
    e = create_with_reminders(ctx, "g_kill", "gkill", [("POPUP", 150)])
    d.key(3)  # HOME: the app goes to the background, so that `am kill` may kill it
    time.sleep(2)
    d.shell(f"am kill {PKG}")
    time.sleep(2)
    ctx.note("process after kill: " + (d.pid() or "none"))
    wait_until(ctx, e["fires"][0], 25)
    ctx.step("after_kill")
    ctx.check(notified(ctx, e["title"]), "the popup did not appear after the process was killed")
    F.start_app(ctx)


@case("T60", "G", "Reminders are restored after a reboot of the device", account=True, slow=True)
def t60(ctx):
    d = ctx.d
    e = create_with_reminders(ctx, "g_boot", "gboot", [("POPUP", 330)])
    before = d.shell("dumpsys alarm | grep -c " + PKG).strip()
    d.adb("reboot", timeout=60)
    time.sleep(15)
    ctx.check(d.wait_boot(300), "the emulator did not come back after the reboot")
    d.wake()
    time.sleep(25)
    after = d.shell("dumpsys alarm | grep -c " + PKG).strip()
    ctx.note(f"alarm entries of the app: {before} before, {after} after the reboot")
    ctx.check(int(after or 0) > 0, "no alarm of the app is registered after the reboot")
    wait_until(ctx, e["fires"][0], 40)
    ctx.step("after_reboot")
    ctx.check(notified(ctx, e["title"]), "the popup did not appear after the reboot")
    F.start_app(ctx)


@case("T61", "G", "A time zone change keeps the app working and the alarms registered", account=True, slow=True)
def t61(ctx):
    d = ctx.d
    e = create_with_reminders(ctx, "g_tz", "gtz", [("POPUP", 1500)])
    count = lambda: int((d.shell("dumpsys alarm | grep -c " + PKG).strip() or "0"))
    before = count()
    for zone in ("Asia/Tokyo", "America/New_York", "UTC"):
        out = d.shell(f"cmd alarm set-timezone {zone}")
        if "rror" in out or "nknown" in out:
            d.shell(f"service call alarm 3 s16 {zone}")
        time.sleep(5)
        ctx.check(d.pid(), f"the app died when the zone changed to {zone}")
    time.sleep(3)
    ctx.check(count() > 0, f"no alarm registered after the zone changes (before: {before})")
    F.home(ctx)
    F.open_event(ctx, e["title"])
    ctx.check(e["title"] in D().detail_text(ctx), "the event cannot be opened after the zone changes")
    F.close_detail(ctx)


def D():
    import cases_d
    return cases_d


@case("T64", "G", "Email reminder is kept in the event", account=True)
def t64(ctx):
    d = ctx.d
    title = F.create_event(ctx, "mailrem", reminders=lambda: [("EMAIL", 30)])
    F.open_event(ctx, title)
    text = D().detail_text(ctx)
    ctx.check(re.search(r"Email|e-?mail", text, re.I), f"the email reminder is not in the detail: {text[:200]}")
    F.close_detail(ctx)
    ctx.state["mail_event"] = title


@case("T65", "G", "An alarm survives the trip through Google (still an alarm, not a notification)", account=True, slow=True)
def t65(ctx):
    d = ctx.d
    title = F.create_event(ctx, "alarmsync", reminders=lambda: [("ALARM", 45)])
    ctx.check(F.roundtrip(ctx), "login failed after clearing the data")
    F.open_event(ctx, title)
    text = D().detail_text(ctx)
    ctx.check(re.search(r"Alarm|Sveglia", text), f"the reminder came back as a notification: {text[:200]}")
    F.close_detail(ctx)
