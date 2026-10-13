"""Groups A (start and structure) and B (settings). See app_planning/test_API_emulator.md."""
import io
import re
import time

import flows as F
from adbx import PKG
from harness import case
from strings import S, has, CANCEL, OK


def brightness(ctx, region=(0.30, 0.088, 0.95, 0.100)):
    """Brightness (0-255) of a strip of plain background just under the toolbar (no event block reaches it)."""
    from PIL import Image
    png = ctx.d.adb("exec-out", "screencap", "-p", binary=True)
    img = Image.open(io.BytesIO(png)).convert("L")
    w, h = img.size
    box = (int(w * region[0]), int(h * region[1]), int(w * region[2]), int(h * region[3]))
    px = sorted(img.crop(box).resize((40, 40)).getdata())
    return px[len(px) // 2]  # the median is the background even when event blocks cover part of the screen


def settled_brightness(ctx):
    """Brightness once the transitions are over (two readings alike)."""
    last = brightness(ctx)
    for _ in range(8):
        time.sleep(0.8)
        now = brightness(ctx)
        if abs(now - last) < 4:
            return now
        last = now
    return last


def current_theme(ctx):
    nodes = ctx.d.dump()
    for mode in ("SYSTEM", "LIGHT", "DARK"):
        n = ctx.d.find(nodes, tag=f"settings_theme_{mode}")
        if n and n[0].selected:
            return mode
    return None


# ================================================================= A. start and structure
@case("T01", "A", "Clean install and first start")
def t01(ctx):
    d = ctx.d
    d.shell(f"pm uninstall {PKG}")
    out = d.adb("install", "-r", ctx.apk, timeout=240)
    ctx.check("Success" in out, f"install failed: {out.strip()[:120]}")
    F.grant_permissions(ctx)
    F.start_app(ctx, fresh=True)
    ctx.step("first_start")
    ctx.check(d.find(tag="fab_new_event"), "the + button is missing at first start")
    ctx.state["fresh_install"] = True


@case("T09", "A", "+ with no account shows the notice (no crash)")
def t09(ctx):
    d = ctx.d
    if not ctx.state.get("fresh_install"):
        ctx.skip("needs the state right after the clean install (T01)")
    F.home(ctx)
    d.click(tag="fab_new_event")
    n = d.wait(timeout=8, text=has("message_no_calendar"))
    ctx.check(n is not None, "no notice when adding an event without an account")
    ctx.check(not d.find(tag="editor_title"), "the editor opened without any calendar")
    ctx.check(d.pid(), "the app died")


@case("T02", "A", "Update over the installed version keeps the data")
def t02(ctx):
    d = ctx.d
    before = d.version_name()
    out = d.adb("install", "-r", ctx.apk, timeout=240)
    ctx.check("Success" in out, f"update failed: {out.strip()[:120]}")
    F.start_app(ctx, fresh=True)
    ctx.check(d.version_name() == before or ctx.apk_prev is None, "unexpected version after the update")
    if ctx.state.get("account_ok"):
        ctx.check(F.has_account(ctx), "the account is gone after the update")


@case("T03", "A", "Drawer lists every entry")
def t03(ctx):
    d = ctx.d
    F.home(ctx)
    F.open_drawer(ctx)
    ctx.step("drawer")
    for tag in [f"drawer_view_{v}" for v in F.VIEWS] + ["drawer_refresh", "drawer_alarms", "drawer_add_account", "drawer_settings", "drawer_export", "drawer_about"]:
        F.drawer_item(ctx, tag, click=False)
    d.back()


@case("T04", "A", "Each view opens and the last one is remembered")
def t04(ctx):
    d = ctx.d
    for v in F.VIEWS:
        F.select_view(ctx, v)
        ctx.check(F.selected_view(ctx) == v, f"view {v} not selected after choosing it")
        F.home(ctx)
        ctx.step(f"view_{v}")
    F.start_app(ctx, fresh=True)
    ctx.check(F.selected_view(ctx) == F.VIEWS[-1], "the last view was not remembered after a restart")
    F.select_view(ctx, "SCHEDULE")


@case("T05", "A", "Year picker from the month + schedule view")
def t05(ctx):
    d = ctx.d
    F.select_view(ctx, "MONTH_LIST")
    d.click(tag="btn_title")
    ctx.check(d.wait(timeout=8, text=S("choose_year")), "the year picker did not open")
    year = int(F.device_now(ctx).year) - 1
    d.click(text=re.compile(rf"^{year}$"))
    ctx.check(d.gone(timeout=6, text=S("choose_year")), "the year picker did not close")
    time.sleep(1.5)
    title = F.toolbar_title(ctx) or ""
    ctx.check(str(year) in title, f"the title '{title}' does not show {year}")
    d.click(tag="btn_today")
    F.select_view(ctx, "SCHEDULE")


@case("T06", "A", "Today icon returns to the current month")
def t06(ctx):
    d = ctx.d
    F.select_view(ctx, "MONTH")
    start = F.toolbar_title(ctx)
    for _ in range(3):
        w = d.dump()
        d.swipe(900, 1200, 200, 1200, 250)
        time.sleep(0.8)
    moved = F.toolbar_title(ctx)
    ctx.check(moved != start, "swiping did not change the month")
    d.click(tag="btn_today")
    time.sleep(1.2)
    ctx.check(F.toolbar_title(ctx) == start, "Today did not go back to the current month")
    F.select_view(ctx, "SCHEDULE")


@case("T07", "A", "Swiping moves between periods (week, day, month)")
def t07(ctx):
    d = ctx.d
    for v in ("WEEK", "DAY", "MONTH"):
        F.select_view(ctx, v)
        before = {n.text for n in d.dump() if n.text}
        d.swipe(900, 1200, 200, 1200, 250)
        time.sleep(1.0)
        after = {n.text for n in d.dump() if n.text}
        ctx.check(before != after, f"nothing changed after swiping in {v}")
        d.swipe(200, 1200, 900, 1200, 250)
    F.select_view(ctx, "SCHEDULE")


@case("T08", "A", "About shows the installed version and the build date")
def t08(ctx):
    d = ctx.d
    F.home(ctx)
    F.open_drawer(ctx)
    F.drawer_item(ctx, "drawer_about")
    ctx.check(d.wait(timeout=8, text=re.compile("Gusinu")), "the About sheet did not open")
    version = d.version_name()
    nodes = d.dump()
    ctx.check(any(n.text == version for n in nodes), f"version {version} not shown")
    date_row = d.find(nodes, text=re.compile(r"\d{4}|\d{1,2}[ /.]\w+"))
    ctx.check(date_row, "no build date shown")
    ctx.step("about")
    d.back()


@case("T10", "A", "Rotation and system dark mode do not crash")
def t10(ctx):
    d = ctx.d
    F.select_view(ctx, "WEEK")
    d.shell("settings put system accelerometer_rotation 0")
    for rot in (1, 3, 0):
        d.shell(f"settings put system user_rotation {rot}")
        time.sleep(2)
        ctx.check(d.pid(), f"the app died at rotation {rot}")
        ctx.check(d.wait(timeout=8, tag="btn_menu"), f"no toolbar at rotation {rot}")
        ctx.step(f"rotation_{rot}")
    d.shell("cmd uimode night yes")
    time.sleep(2)
    ctx.step("system_night")
    d.shell("cmd uimode night no")
    ctx.check(d.pid(), "the app died when the system theme changed")
    F.select_view(ctx, "SCHEDULE")


@case("T12", "A", "Larger font and display size keep the main controls")
def t12(ctx):
    d = ctx.d
    F.home(ctx)
    try:
        d.shell("settings put system font_scale 1.3")
        d.shell("wm density 560")
        time.sleep(3)
        F.start_app(ctx, fresh=True)
        for tag in ("btn_menu", "btn_search", "btn_refresh", "fab_new_event"):
            ctx.check(d.find(tag=tag), f"{tag} missing with a large font")
        ctx.step("large_font")
        F.open_drawer(ctx)
        F.drawer_item(ctx, "drawer_about", click=False)
        d.back()
    finally:
        d.shell("settings put system font_scale 1.0")
        d.shell("wm density reset")
        time.sleep(2)
    F.start_app(ctx, fresh=True)


# ================================================================= B. settings
def open_settings(ctx):
    F.home(ctx)
    ctx.d.click(tag="btn_settings")
    if ctx.d.wait(timeout=10, tag="settings_save") is None:
        ctx.fail("the settings did not open")


def save_settings(ctx):
    d = ctx.d
    btn = d.wait(timeout=6, tag="settings_save")
    if btn is not None and not btn.enabled:  # nothing changed (the choice was already the current one): just leave
        d.click(tag="settings_back")
        ctx.check(d.gone(timeout=8, tag="settings_save"), "the settings stayed open after Back")
        return
    d.click(tag="settings_save")
    ctx.check(d.gone(timeout=8, tag="settings_save"), "the settings stayed open after Save")
    time.sleep(1.0)


@case("T13", "B", "Theme light, dark and system, applied at once and kept")
def t13(ctx):
    d = ctx.d
    try:
        open_settings(ctx)
        d.click(tag="settings_theme_LIGHT")
        save_settings(ctx)
        light = settled_brightness(ctx)
        open_settings(ctx)
        d.click(tag="settings_theme_DARK")
        save_settings(ctx)
        dark = settled_brightness(ctx)
        ctx.step("dark")
        ctx.check(dark < 90 < light and light > 150, f"theme brightness light={light:.0f} dark={dark:.0f}")
        F.start_app(ctx, fresh=True)
        ctx.check(settled_brightness(ctx) < 90, "the dark theme was not kept after a restart")
    finally:
        open_settings(ctx)
        d.click(tag="settings_theme_SYSTEM")
        if d.find(tag="settings_save")[0].enabled:
            save_settings(ctx)
        else:
            d.click(tag="settings_back")


@case("T14", "B", "Leaving settings with unsaved changes asks to discard")
def t14(ctx):
    d = ctx.d
    open_settings(ctx)
    before = current_theme(ctx)
    other = "LIGHT" if before != "LIGHT" else "DARK"
    d.click(tag=f"settings_theme_{other}")
    d.click(tag="settings_back")
    ctx.check(d.wait(timeout=6, tag="discard_keep"), "no discard question")
    d.click(tag="discard_keep")
    ctx.check(d.gone(timeout=5, tag="discard_keep"), "Keep editing did not close the question")
    ctx.check(d.find(tag="settings_save"), "the settings closed on Keep editing")
    d.click(tag="settings_back")
    d.click(tag="discard_confirm")
    ctx.check(d.gone(timeout=8, tag="settings_save"), "Discard did not close the settings")
    open_settings(ctx)
    ctx.check(current_theme(ctx) == before, "Discard still changed the theme")
    d.click(tag="settings_back")


@case("T15", "B", "First day of the week: Monday, Saturday, Sunday")
def t15(ctx):
    d = ctx.d
    first = {}
    for day in ("MONDAY", "SATURDAY", "SUNDAY"):
        open_settings(ctx)
        d.click(tag=f"settings_week_{day}")
        save_settings(ctx)
        F.select_view(ctx, "MONTH")
        nodes = d.dump()
        header = sorted([n for n in nodes if re.fullmatch(r"[A-Za-zÀ-ú]{1,4}\.?", n.text or "") and n.t < 700], key=lambda n: n.l)
        first[day] = header[0].text if header else None
        ctx.step(f"week_{day}")
    ctx.check(len(set(first.values())) == 3 and None not in first.values(), f"first column labels {first}")
    open_settings(ctx)
    d.click(tag="settings_week_MONDAY")
    save_settings(ctx)
    F.select_view(ctx, "SCHEDULE")


@case("T16", "B", "Start of the day is kept")
def t16(ctx):
    d = ctx.d
    open_settings(ctx)
    d.click(tag="settings_day_start")
    n = d.wait(timeout=6, text=re.compile(r"^(9:00|09:00)\b.*", re.I))
    ctx.check(n, "the hour menu has no 9:00")
    d.tap(*n.center)
    save_settings(ctx)
    open_settings(ctx)
    ctx.check(d.wait(timeout=6, text=re.compile(r"^(9:00|09:00)\b.*", re.I)), "9:00 was not kept")
    d.click(tag="settings_day_start")
    n = d.wait(timeout=6, text=re.compile(r"^(7:00|07:00)\b.*", re.I))
    d.tap(*n.center)
    save_settings(ctx)


@case("T17", "B", "Dim past events switch is kept")
def t17(ctx):
    d = ctx.d

    def state():
        nodes = d.dump()
        row = d.find(nodes, tag="settings_dim_past")[0]
        sw = [n for n in nodes if n.cls.endswith("Switch") or "Switch" in n.cls or n.checked is not None and n.l >= row.r - 250 and n.t >= row.t and n.b <= row.b]
        return any(n.checked for n in sw)

    open_settings(ctx)
    start = state()
    d.click(tag="settings_dim_past")
    save_settings(ctx)
    open_settings(ctx)
    ctx.check(state() != start, "the switch was not kept")
    d.click(tag="settings_dim_past")
    save_settings(ctx)
    open_settings(ctx)
    ctx.check(state() == start, "the switch did not return to the original value")
    d.click(tag="settings_back")


@case("T19", "B", "Notifications and alarms sheet: duration slider is kept")
def t19(ctx):
    d = ctx.d
    open_settings(ctx)
    d.tap(*F.scroll_to(ctx, "settings_notifications").center)
    sl = d.wait(timeout=8, tag="notif_alarm_slider")
    ctx.check(sl, "the notifications sheet did not open")
    ctx.step("notifications_sheet")
    d.swipe(sl.center[0], sl.center[1], max(sl.l - 120, 2), sl.center[1], 500)  # drag to the minimum
    time.sleep(1.0)
    ctx.check(d.find(text=re.compile(r"^1 (minut)", re.I)), "the slider did not reach 1 minute")
    d.back()
    time.sleep(0.6)
    d.tap(*F.scroll_to(ctx, "settings_notifications").center)
    sl = d.wait(timeout=8, tag="notif_alarm_slider")
    ctx.check(d.find(text=re.compile(r"^1 (minut)", re.I)), "1 minute was not kept")
    d.swipe(sl.l + 20, sl.center[1], sl.l + int(sl.width * 0.13), sl.center[1], 500)  # about 2 minutes again
    d.back()
    F.home(ctx)
    ctx.note("the duration is left as set by the last swipe; T55 sets it again")
