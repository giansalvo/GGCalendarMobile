"""Group G, hidden calendars (version 1.0.2): a calendar whose check is removed in the side menu neither rings nor wakes.

Three popup reminders are created in one go (the setup takes minutes) and the calendar is hidden and shown around them:
- T66 hidden when it falls due, shown again afterwards: it never appears, not even when the calendar comes back;
- T67 hidden, then shown again before it falls due: it rings (the alarm was registered again);
- T68 shown when it falls due, then hidden with its notification on screen (the notification stays) and "Snooze" pressed:
  the snooze rings anyway, ten minutes later.
Only events titled [TEST GGCAL] ..., in the primary calendar of the test account. The calendar is always shown again at the end.
"""
import re
import time

import flows as F
import cases_g as G
from harness import case

MY_CALENDAR = re.compile(r"^(My calendar|Il mio calendario|Mi calendario|Mein Kalender)$")
SNOOZE_BUTTON = re.compile(r"^(Snooze|Posticipa|Posponer|Verschieben|Snooze) 10", re.I)

# seconds after the start of the setup at which each reminder falls due
DUE = {"h_missed": 480, "h_back": 600, "h_snooze": 720}


def calendar_row(ctx):
    """The drawer row of the primary calendar of the test account (the drawer must be open)."""
    d = ctx.d
    nodes = d.dump()
    label = [n for n in nodes if MY_CALENDAR.match(n.text or "")]
    ctx.check(label, "the primary calendar is not in the drawer")
    return label[0]


def set_visible(ctx, visible):
    """Puts the check of the primary calendar on or off in the side menu.

    The check box is not readable through UI Automator, so the state is kept here (the calendar is visible at the start)."""
    d = ctx.d
    if ctx.state.get("h_visible", True) == visible:
        return
    F.home(ctx)
    F.open_drawer(ctx)
    row = calendar_row(ctx)
    d.tap(*row.center)
    time.sleep(1.2)
    ctx.state["h_visible"] = visible
    ctx.note(f"calendar {'shown' if visible else 'hidden'} at {G.now(ctx).time()}")
    d.back()
    time.sleep(0.8)
    F.home(ctx)


def h_setup(ctx):
    if ctx.state.get("h_ready"):
        return
    G.clear_shade(ctx)
    t0 = G.now(ctx)
    for key, after in DUE.items():
        G.create_with_reminders(ctx, key, key.replace("_", ""), [("POPUP", after)], t0)
    ctx.state["h_ready"] = True
    ctx.note("due: " + ", ".join(f"{k} {ctx.state[k]['fires'][0].time()}" for k in DUE))


def dumpsys_alarms(ctx):
    """How many alarms the app has registered with AlarmManager."""
    out = ctx.d.shell("dumpsys alarm")
    return sum(1 for l in out.splitlines() if G.PKG in l and "RTC" in l)


@case("T66", "G", "Hidden calendar: a reminder due meanwhile never shows, not even when it is shown again", account=True, slow=True)
def t66(ctx):
    h_setup(ctx)
    missed, back = ctx.state["h_missed"], ctx.state["h_back"]
    ctx.state.setdefault("finalizers", [])
    try:
        set_visible(ctx, False)
        G.wait_until(ctx, missed["fires"][0], 25)
        ctx.step("hidden_after_due")
        ctx.check(not G.notified(ctx, missed["title"]), "the reminder of a hidden calendar appeared")
        # shown again between the two reminders: the first stays missed, the second is registered again
        G.wait_until(ctx, missed["fires"][0], 40)
        set_visible(ctx, True)
        ctx.state["h_shown_at"] = G.now(ctx)
        ctx.check(not G.notified(ctx, missed["title"]), "the reminder that fell due while hidden appeared when the calendar came back")
    except Exception:
        set_visible(ctx, True)
        raise


@case("T67", "G", "Calendar shown again before the reminder: it rings", account=True, slow=True)
def t67(ctx):
    h_setup(ctx)
    back = ctx.state["h_back"]
    ctx.check(ctx.state.get("h_shown_at"), "needs T66")
    G.wait_until(ctx, back["fires"][0], 25)
    ctx.step("shown_again_after_due")
    ctx.check(G.notified(ctx, back["title"]), "the reminder did not ring after the calendar was shown again")
    ctx.check(not G.notified(ctx, ctx.state["h_missed"]["title"]), "the reminder missed while hidden rang later")


@case("T68", "G", "Snooze pressed on a notification of a calendar hidden meanwhile: it rings anyway", account=True, slow=True)
def t68(ctx):
    h_setup(ctx)
    d = ctx.d
    snooze = ctx.state["h_snooze"]
    # The notifications of the two earlier events would bundle with this one and hide its buttons: clear them first.
    G.wait_until(ctx, ctx.state["h_back"]["fires"][0], 40)
    G.clear_shade(ctx)
    G.wait_until(ctx, snooze["fires"][0], 25)
    ctx.check(G.notified(ctx, snooze["title"]), "the reminder did not ring while the calendar was shown")
    try:
        set_visible(ctx, False)
        ctx.check(G.notified(ctx, snooze["title"]), "hiding the calendar removed a notification already on screen")
        time.sleep(2.0)
        btn = None
        for attempt in range(3):
            G.show_shade(ctx)
            btn = d.wait(timeout=6, text=SNOOZE_BUTTON)
            if btn is None:  # a collapsed bundle or notification: expand it without opening the event
                n = d.find(text=re.compile(re.escape(snooze["title"])))
                if n:
                    d.swipe(n[0].center[0], n[0].center[1], n[0].center[0], n[0].center[1] + 200, 300)
                    time.sleep(1.0)
                    btn = d.wait(timeout=4, text=SNOOZE_BUTTON)
            if btn is not None:
                break
            ctx.note("shade attempt %d: %s" % (attempt + 1, [n.text for n in d.dump() if n.text][:12]))
            d.shell("cmd statusbar collapse")
            time.sleep(1.5)
        ctx.step("shade_with_snooze")
        ctx.check(btn, "the Snooze button is not on the notification")
        d.tap(*btn.center)
        time.sleep(1.5)
        d.shell("cmd statusbar collapse")
        ctx.check(not G.notified(ctx, snooze["title"]), "the notification stayed after Snooze")
        ctx.check(dumpsys_alarms(ctx) >= 1, "the snooze is not registered with AlarmManager")
        at = G.now(ctx)
        from datetime import timedelta
        G.wait_until(ctx, at + timedelta(minutes=10), 30)
        ctx.step("snooze_due")
        ctx.check(G.notified(ctx, snooze["title"]), "the snooze of a hidden calendar did not ring")
    finally:
        set_visible(ctx, True)
