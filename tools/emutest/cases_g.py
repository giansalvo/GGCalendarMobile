"""Group G: reminders, notifications and alarms. The core of the app; slow because it waits for real clock time.

All the events are created in one go (`g_setup`), each with reminders due at a chosen number of seconds after the
start of the setup, and the cases then check them in the order they fall due, so the waiting overlaps instead of adding up.
The events start at the next full hour and the reminders use a custom "N minutes before", so this works at any time of
day. Only events titled [TEST GGCAL] ..., in the primary calendar.
"""
import math
import re
import time
from datetime import datetime, timedelta

import flows as F
from adbx import PKG
from harness import case
from strings import S, has

# seconds after the start of the setup at which each reminder falls due (the setup itself takes about 4-5 minutes)
DUE = {
    "g_popup": [("POPUP", 330)],
    "g_alarm": [("ALARM", 620)],            # rings with the screen off
    "g_kill": [("POPUP", 780)],             # the process of the app is killed before it
    "g_pair": [("ALARM", 940), ("ALARM", 1000)],  # snooze, then the second alarm, then no answer
    "g_boot": [("POPUP", 1400)],            # the emulator is rebooted before it
    "g_early": [("ALARM", 3000)],           # closed early from the list
}


# ---------------------------------------------------------------- helpers
def now(ctx):
    return F.device_now(ctx)


def create_with_reminders(ctx, key, title, specs, t0=None):
    """Creates an event whose reminders fall due at absolute moments.

    specs: [(METHOD, seconds after t0)] (t0 defaults to now). Records ctx.state[key] = {title, fires: [datetime]}."""
    t0 = t0 or now(ctx)
    fires = []

    def reminders():
        # the event starts tomorrow at the hour the editor proposed when it was opened
        opened = ctx.state["editor_opened_at"]
        start = opened.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1, days=1)
        out = []
        for method, after in specs:
            target = t0 + timedelta(seconds=after)
            minutes = max(1, int((start - target).total_seconds() // 60))
            out.append((method, minutes))
            fires.append(start - timedelta(minutes=minutes))
        return out

    full = F.create_event(ctx, title, reminders=reminders, day_offset=1)
    ctx.state[key] = dict(title=full, fires=sorted(fires))
    return ctx.state[key]


def clear_shade(ctx):
    """Clears the notifications left by earlier runs: stale ones group together and a tap on a group does not open an event."""
    d = ctx.d
    if not d.serial.startswith("emulator-"):
        return  # a real phone: its notifications are not ours to clear
    show_shade(ctx)
    btn = d.find(text=re.compile(r"^(Clear all|Cancella tutto|Cancella)$", re.I))
    if btn:
        d.tap(*btn[0].center)
        time.sleep(1.0)
    d.shell("cmd statusbar collapse")
    time.sleep(0.8)


def g_setup(ctx):
    """Creates every event of the group once; the clock of the reminders starts here."""
    if ctx.state.get("g_ready"):
        return
    clear_shade(ctx)
    set_alarm_duration(ctx, 1)
    on_phone = not ctx.d.serial.startswith("emulator-")
    skip = ["g_alarm", "g_boot"] if on_phone else []  # no screen-off alarm and no reboot on a phone
    chosen = ctx.state.get("chosen")
    if chosen:  # create only the events the selected cases use (every alarm created rings on the phone)
        uses = {"g_popup": ("T50", "T51"), "g_alarm": ("T52", "T53", "T57"), "g_kill": ("T59",), "g_pair": ("T54", "T55", "T56"),
                "g_boot": ("T60",), "g_early": ("T57", "T58", "T61")}
        skip += [k for k, ids in uses.items() if k not in skip and not any(i in chosen for i in ids)]
    t0 = now(ctx)
    names = {"g_popup": "gpopup", "g_alarm": "galarm", "g_kill": "gkill", "g_pair": "gpair", "g_boot": "gboot", "g_early": "gearly"}
    for key, specs in DUE.items():
        if key in skip:
            continue
        create_with_reminders(ctx, key, names[key], specs, t0)
    ctx.state["g_ready"] = True
    ctx.note("due: " + ", ".join(f"{k[2:]} {ctx.state[k]['fires'][0].time()}" for k in DUE if k not in skip))


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
    # the rows arrive after the sheet opens; on a tall screen the sheet may stay low: pull it up if the rows are cut off
    end = time.time() + 10
    nodes = d.dump()
    while time.time() < end and not d.find(nodes, tag="alarm_row") and not d.find(nodes, text=re.compile("No alarms|Nessuna sveglia", re.I)):
        time.sleep(1.0)
        nodes = d.dump()
    sheet = d.find(nodes, tag="sheet_alarms")
    if sheet and not d.find(nodes, tag="alarm_row") and sheet[0].t > d.screen_h * 0.5:
        ctx.note(f"alarms sheet low: top {sheet[0].t} of {d.screen_h}")
        x = sheet[0].center[0]
        d.swipe(x, sheet[0].t + 20, x, int(d.screen_h * 0.2), 500)
        time.sleep(1.5)
        nodes = d.dump()
    return nodes


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
    d.tap(*F.scroll_to(ctx, "settings_notifications").center)
    sl = d.wait(timeout=8, tag="notif_alarm_slider")
    ctx.check(sl, "the notifications sheet did not open")
    if "orig_duration" not in ctx.state:
        cur = [m.group(1) for n in d.dump() for m in [re.match(r"^(\d+)\s*(?:minut|min)", n.text or "", re.I)] if m]
        ctx.state["orig_duration"] = int(cur[0]) if cur else None
        ctx.state.setdefault("finalizers", []).append(restore_alarm_duration)
    # 10 values along the track: drag to the exact spot
    x = sl.l + int(sl.width * (minutes - 1) / 9.0)
    d.swipe(sl.center[0], sl.center[1], x if minutes > 1 else max(sl.l - 120, 2), sl.center[1], 500)
    time.sleep(1.0)
    ctx.check(d.find(text=re.compile(rf"^{minutes} ", re.I)), f"the slider did not reach {minutes} minute(s)")
    d.back()
    time.sleep(0.6)
    F.home(ctx)


def restore_alarm_duration(ctx):
    """Puts the alarm duration back to what the device had before the run."""
    orig = ctx.state.get("orig_duration")
    if orig and orig != 1:
        set_alarm_duration(ctx, orig)
    elif orig is None:
        ctx.note("the original alarm duration could not be read: check it in Notifications and alarms")


def show_shade(ctx):
    ctx.d.shell("cmd statusbar expand-notifications")
    time.sleep(1.5)


def D():
    import cases_d
    return cases_d


# ================================================================= G. reminders (in the order they fall due)
@case("T50", "G", "Popup notification at the right time", account=True, slow=True)
def t50(ctx):
    g_setup(ctx)
    e = ctx.state["g_popup"]
    wait_until(ctx, e["fires"][0], 20)
    ctx.step("after_popup")
    ctx.check(notified(ctx, e["title"]), "the popup notification did not appear when due")


def tap_notification(ctx, title):
    """Taps the notification of this event in the shade and waits for the detail.

    Several notifications of the app are bundled in one group (an older missed-alarm notice, say): on Android 14 the first
    tap on a collapsed group only expands it, the next one opens the event. So it taps again when the detail has not opened."""
    d = ctx.d
    pat = re.compile(re.escape(title))
    n = d.wait(timeout=8, text=pat)
    ctx.step("shade")
    ctx.check(n, "the notification is not in the shade")
    d.tap(*n.center)
    if d.wait(timeout=8, tag="detail_close") is None:
        ctx.note("first tap on the notification did not open the event (a bundle expands first); tapping again")
        n = d.wait(timeout=4, text=pat)
        if n is None:
            show_shade(ctx)
            n = d.wait(timeout=8, text=pat)
        ctx.check(n, "the notification is gone after the first tap")
        d.tap(*n.center)
    ctx.check(d.wait(timeout=15, tag="detail_close"), "the notification did not open the event")


@case("T51", "G", "Tap on the notification opens the event", account=True, slow=True)
def t51(ctx):
    e = ctx.state.get("g_popup")
    if not e:
        ctx.skip("needs T50")
    show_shade(ctx)
    tap_notification(ctx, e["title"])
    F.close_detail(ctx)


@case("T51D", "G", "Device: a popup comes up and a tap on it opens the event (one event, no alarms)", account=True, slow=True)
def t51d(ctx):
    d = ctx.d
    # The variant for a real phone, where the group has no alarm cases. On an emulator it takes about six minutes in the
    # middle of the group: the alarms of T52, T57 and T59 fall due meanwhile and those cases fail (API 34, 14/10/2026).
    if d.serial.startswith("emulator-"):
        ctx.skip("phone-only variant of T51; on an emulator T51 covers the tap and T51D would make the later alarms fall due")
    t0 = now(ctx)
    e = create_with_reminders(ctx, "d_popup", "dpopup", [("POPUP", 300)], t0)
    ctx.note(f"popup due {e['fires'][0].time()}")
    F.home(ctx)
    wait_until(ctx, e["fires"][0], 25)
    ctx.check(notified(ctx, e["title"]), "the popup did not appear")
    show_shade(ctx)
    tap_notification(ctx, e["title"])
    F.close_detail(ctx)


@case("T57", "G", "Active alarms list: ordered, with the time left", account=True, slow=True)
def t57(ctx):
    e = ctx.state.get("g_alarm") or ctx.state.get("g_early")
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


@case("T59", "G", "A popup still shows after the app process was killed", account=True, slow=True)
def t59(ctx):
    d = ctx.d
    e = ctx.state.get("g_kill")
    if not e:
        ctx.skip("needs T50")
    d.key(3)  # HOME: the app goes to the background, so that `am kill` may kill it
    time.sleep(2)
    d.shell(f"am kill {PKG}")
    time.sleep(2)
    ctx.note("process after kill: " + (d.pid() or "none"))
    ctx.check(now(ctx) < e["fires"][0], "the popup was already due before the process was killed")
    wait_until(ctx, e["fires"][0], 25)
    ctx.step("after_kill")
    ctx.check(notified(ctx, e["title"]), "the popup did not appear after the process was killed")
    F.start_app(ctx)


@case("T54", "G", "Snooze 5 minutes: it is listed as snoozed", account=True, slow=True)
def t54(ctx):
    d = ctx.d
    e = ctx.state.get("g_pair")
    if not e:
        ctx.skip("needs T50")
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
    if not e or len(e["fires"]) < 2:
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
    # the duration was set to 1 minute in g_setup
    time.sleep(max(0, 75 - (now(ctx) - since).total_seconds()))
    ctx.check(d.gone(timeout=10, tag="alarm_stop") or not alarm_service_running(ctx), "the alarm kept ringing past the set duration")
    ctx.check(not alarm_service_running(ctx), "the ringing service did not stop by itself")
    out = notification_dump(ctx)
    missed = [b for b in out.split("NotificationRecord(") if PKG in b and re.search(S("missed_alarm", exact=False), b)]
    ctx.step("after_missed")
    ctx.check(missed, "no 'missed alarm' notification was left")
    set_alarm_duration(ctx, 2)


@case("T61", "G", "A time zone change keeps the app working and the alarms registered", account=True, slow=True)
def t61(ctx):
    d = ctx.d
    e = ctx.state.get("g_early")
    if not e:
        ctx.skip("needs T50")
    count = lambda: int((d.shell("dumpsys alarm | grep -c " + PKG).strip() or "0"))
    before = count()
    original = d.shell("getprop persist.sys.timezone").strip() or "UTC"
    try:
        for zone in ("Asia/Tokyo", "America/New_York", "UTC"):
            out = d.shell(f"cmd alarm set-timezone {zone}")
            if "rror" in out or "nknown" in out:
                d.shell(f"service call alarm 3 s16 {zone}")
            time.sleep(5)
            ctx.check(d.pid(), f"the app died when the zone changed to {zone}")
    finally:
        d.shell(f"cmd alarm set-timezone {original}")  # a real phone goes back to its own zone
        ctx.note(f"time zone back to {original}")
    time.sleep(3)
    ctx.check(count() > 0, f"no alarm registered after the zone changes (before: {before})")
    F.home(ctx)
    F.open_event(ctx, e["title"])
    ctx.check(e["title"] in D().detail_text(ctx), "the event cannot be opened after the zone changes")
    F.close_detail(ctx)


@case("T60", "G", "Reminders are restored after a reboot of the device", account=True, slow=True)
def t60(ctx):
    d = ctx.d
    e = ctx.state.get("g_boot")
    if not e:
        ctx.skip("needs T50")
    ctx.check(now(ctx) < e["fires"][0] - timedelta(seconds=150), "too late to reboot before the reminder falls due")
    before = d.shell("dumpsys alarm | grep -c " + PKG).strip()
    d.adb("reboot", timeout=60)
    time.sleep(15)
    ctx.check(d.wait_boot(300), "the emulator did not come back after the reboot")
    d.shell("svc power stayon true")
    d.shell("settings put global window_animation_scale 0.0")
    d.shell("settings put global transition_animation_scale 0.0")
    d.shell("settings put global animator_duration_scale 0.0")
    d.wake()
    time.sleep(25)
    after = d.shell("dumpsys alarm | grep -c " + PKG).strip()
    ctx.note(f"alarm entries of the app: {before} before, {after} after the reboot")
    ctx.check(int(after or 0) > 0, "no alarm of the app is registered after the reboot")
    wait_until(ctx, e["fires"][0], 40)
    ctx.step("after_reboot")
    ctx.check(notified(ctx, e["title"]), "the popup did not appear after the reboot")
    F.start_app(ctx)


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
