"""Reads the app settings from the Settings screen at the start of a run and puts them back at the end.

The settings live in the app's DataStore (not readable with adb on a release build), but every one of them is visible on the
Settings screen, so the run reads them there. The tests change them (theme, first day of the week, start of the day, ...) and
leave their own values; on a real phone those would replace the user's choices, hence snapshot() / restore().
Covers: theme, dynamic colours, first day of the week, start of the day, dim past events. The alarm duration is handled by
cases_g.set_alarm_duration (same idea). The birthdays switch is never changed by the tests.
"""
import re
import time

import flows as F

# The cases that change the app settings (T19 only passes through the screen). A run without them never opens the
# Settings screen to read or restore the values: there is nothing to put back.
CHANGING_CASES = {"T13", "T14", "T15", "T16", "T17"}


def needed(chosen_ids):
    return bool(CHANGING_CASES & {c.upper() for c in chosen_ids})


THEMES = ("SYSTEM", "LIGHT", "DARK")
WEEK = ("MONDAY", "SATURDAY", "SUNDAY")
SWITCHES = ("settings_dynamic", "settings_dim_past")
HOUR = re.compile(r"^\d{1,2}[:.]\d{2}(\s?[AP]M)?\b", re.I)


def _open(ctx):
    F.home(ctx)
    ctx.d.click(tag="btn_settings")
    if ctx.d.wait(timeout=10, tag="settings_save") is None:
        ctx.fail("the settings did not open")


def _leave(ctx, save):
    d = ctx.d
    btn = d.wait(timeout=6, tag="settings_save")
    if save and btn is not None and btn.enabled:
        d.click(tag="settings_save")
        ctx.check(d.gone(timeout=8, tag="settings_save"), "the settings stayed open after Save")
        time.sleep(1.0)
        return
    d.click(tag="settings_back")
    if d.wait(timeout=2, tag="discard_confirm"):
        d.click(tag="discard_confirm")
    ctx.check(d.gone(timeout=8, tag="settings_save"), "the settings stayed open after Back")


def _selected(ctx, tags):
    for tag in tags:
        F.scroll_to(ctx, "settings_" + tag)
        found = ctx.d.find(ctx.d.dump(), tag="settings_" + tag)
        if found and (found[0].selected or found[0].checked):  # Android 16 flags a chosen radio as checked, older ones as selected
            return tag
    return None


def _switch_on(ctx, tag):
    F.scroll_to(ctx, tag)
    nodes = ctx.d.dump()
    row = ctx.d.find(nodes, tag=tag)[0]
    inside = [n for n in nodes if n.l >= row.l and n.r <= row.r and n.t >= row.t and n.b <= row.b]
    return any(n.checked for n in inside) or row.checked


def _day_start(ctx):
    F.scroll_to(ctx, "settings_day_start")
    nodes = ctx.d.dump()
    row = ctx.d.find(nodes, tag="settings_day_start")[0]
    for n in nodes:
        if n.l >= row.l and n.r <= row.r and n.t >= row.t and n.b <= row.b and HOUR.match(n.text or ""):
            return n.text
    return None


def snapshot(ctx):
    """Reads the settings; stores them in ctx.state['settings0'] and registers restore() to run at the end."""
    _open(ctx)
    try:
        snap = {}
        snap["theme"] = _selected(ctx, ["theme_" + m for m in THEMES])
        snap["week"] = _selected(ctx, ["week_" + w for w in WEEK])
        snap["switches"] = {tag: _switch_on(ctx, tag) for tag in SWITCHES}
        snap["day_start"] = _day_start(ctx)
    finally:
        _leave(ctx, save=False)
    ctx.state["settings0"] = snap
    ctx.state.setdefault("finalizers", []).append(restore)
    ctx.note(f"settings at the start: {snap}")
    return snap


def restore(ctx):
    """Puts the settings back to what snapshot() read. Anything it cannot read is left alone (and reported)."""
    snap = ctx.state.get("settings0")
    if not snap:
        return
    d = ctx.d
    _open(ctx)
    try:
        if snap.get("theme"):
            F.scroll_to(ctx, "settings_" + snap["theme"])
            d.click(tag="settings_" + snap["theme"])
        if snap.get("week"):
            F.scroll_to(ctx, "settings_" + snap["week"])
            d.click(tag="settings_" + snap["week"])
        for tag, want in snap.get("switches", {}).items():
            if _switch_on(ctx, tag) != want:
                d.click(tag=tag)
        want = snap.get("day_start")
        if want and _day_start(ctx) != want:
            d.click(tag="settings_day_start")
            item = d.wait(timeout=6, text=re.compile("^" + re.escape(want) + r"\b.*"))
            if item is None:
                print(f"  (restore: the hour {want!r} is not in the menu)")
            else:
                d.tap(*item.center)
                time.sleep(0.5)
    finally:
        _leave(ctx, save=True)
