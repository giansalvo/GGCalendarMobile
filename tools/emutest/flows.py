"""Reusable user flows (open the drawer, create an event, search, delete...) built on adbx.

Rule: a flow only touches events whose title starts with TEST_PREFIX and only in the calendar of the test account.
"""
import re
import time
from datetime import datetime, timedelta

from adbx import PKG
from harness import TEST_PREFIX, Fail, Skip
from strings import CANCEL, OK, S, has

# Controls that exist only while something covers the calendar screen (a sheet, a dialog, the drawer, a full screen).
COVERS = {"editor_title", "detail_close", "settings_back", "search_back", "drawer_view_SCHEDULE", "alarm_stop", "scope_confirm",
          "notify_send", "delete_confirm", "sheet_about", "sheet_alarms", "splash_logo", "intro_allow", "intro_skip", "export_confirm",
          "export_summary_ok", "share_text", "reminder_ok", "discard_keep", "alarm_close_confirm",
          "drawer_refresh", "drawer_alarms", "drawer_add_account", "drawer_settings", "drawer_export", "drawer_about", "calendar_row"}
DRAWER_ANY = ["drawer_view_SCHEDULE", "drawer_refresh", "drawer_alarms", "drawer_add_account", "drawer_settings", "drawer_export", "drawer_about", "calendar_row"]
VIEWS = ["SCHEDULE", "DAY", "SIX_DAYS", "WEEK", "MONTH", "MONTH_LIST"]


# ---------------------------------------------------------------- permissions
def grant_permissions(ctx):
    """Grants the app's runtime permissions on an EMULATOR (the user authorized it on 13/10/2026); never on a phone."""
    d = ctx.d
    if not d.serial.startswith("emulator-"):
        return
    for perm in ("android.permission.READ_CONTACTS", "android.permission.POST_NOTIFICATIONS"):
        d.shell(f"pm grant {PKG} {perm}")  # a permission the Android version does not know is simply refused
    d.shell(f"appops set {PKG} SCHEDULE_EXACT_ALARM allow")
    d.shell(f"appops set {PKG} USE_FULL_SCREEN_INTENT allow")
    # The Play Store images carry Google's own Calendar app: signed in with the test account it posts its own reminder
    # notifications for the same events (title included), and a tap on one opens Google Calendar, not GGCalendar. The cases
    # that read the shade would then see the wrong notifications (T51, T51D, T57, T59 on API 34, 14/10/2026).
    # Disabled on the emulator only (undo: pm enable com.google.android.calendar); a phone's apps are never touched.
    d.shell("pm disable-user --user 0 com.google.android.calendar")


# ---------------------------------------------------------------- app lifecycle
def start_app(ctx, fresh=False):
    d = ctx.d
    d.wake()
    if fresh:
        d.shell(f"am force-stop {PKG}")
    d.shell(f"am start -n {PKG}/.MainActivity --ez skip_intro true")
    if d.wait(timeout=25, tag="btn_menu") is None:
        try:
            home(ctx)  # a sheet left open (e.g. the event opened from a notification) hides the bar
        except Exception:
            pass
    if d.wait(timeout=10, tag="btn_menu") is None:
        ctx.fail("the app did not reach the home screen")
    hide_keyboard(ctx)


def permission_question_pending(ctx):
    """A system permission question is on screen: answering it is the user's choice, never the test's."""
    return "GrantPermissionsActivity" in ctx.d.top_activity()


def stop_if_permission_question(ctx):
    if permission_question_pending(ctx):
        raise Skip("a permission question is waiting on the emulator: answer it by hand once, then run again")


def hide_keyboard(ctx):
    d = ctx.d
    if "mInputShown=true" in d.shell("dumpsys input_method | grep mInputShown"):
        d.back()
        time.sleep(0.6)


def home(ctx, tries=6):
    """Back to the plain calendar screen (closes sheets, dialogs, the drawer and the keyboard)."""
    d = ctx.d
    d.wake()
    for _ in range(tries):
        stop_if_permission_question(ctx)
        nodes = d.dump()
        in_app = any(n.pkg == PKG for n in nodes)
        top = d.find(nodes, tag="btn_menu")
        covered = any(n.rid in COVERS or n.rid.startswith("choice_") for n in nodes)
        if in_app and top and not covered:
            return
        if not in_app:
            start_app(ctx)
            continue
        hide_keyboard(ctx)
        d.back()
        time.sleep(0.8)
    start_app(ctx, fresh=True)


# ---------------------------------------------------------------- drawer and views
def open_drawer(ctx):
    d = ctx.d
    d.click(tag="btn_menu")
    end = time.time() + 8
    opened = False
    while not opened:
        opened = any(n.rid in DRAWER_ANY for n in d.dump())
        if opened or time.time() > end:
            break
        time.sleep(0.4)
    if not opened:
        ctx.fail("the drawer did not open")
    # the drawer remembers its scroll position: bring it back to the top
    for _ in range(2):
        d.swipe(400, 600, 400, 1900, 300)
        time.sleep(0.4)


def drawer_item(ctx, tag, click=True):
    """Finds (and taps) an entry of the open drawer, scrolling the drawer when the entry is below the fold."""
    d = ctx.d
    for _ in range(6):
        found = d.find(tag=tag)
        if found and 100 < found[0].center[1] < d.usable_h - 40:
            if click:
                d.tap(*found[0].center)
            return found[0]
        d.swipe(400, 1800, 400, 600, 400)
        time.sleep(0.6)
    ctx.fail(f"drawer entry not found: {tag}")


def select_view(ctx, view):
    d = ctx.d
    home(ctx)
    open_drawer(ctx)
    d.click(tag=f"drawer_view_{view}")
    ctx.check(d.gone(timeout=8, tag="drawer_view_SCHEDULE"), f"the drawer stayed open after choosing {view}")
    time.sleep(1.2)


def selected_view(ctx):
    d = ctx.d
    home(ctx)
    open_drawer(ctx)
    nodes = d.dump()
    sel = [n.rid.replace("drawer_view_", "") for n in nodes if n.rid.startswith("drawer_view_") and (n.selected or n.checked)]
    d.back()
    time.sleep(0.6)
    return sel[0] if sel else None


def toolbar_title(ctx):
    n = ctx.d.wait(timeout=6, tag="btn_title")
    if n is None:
        return None
    nodes = ctx.d.dump()
    inside = [x for x in nodes if x.text and x.l >= n.l and x.r <= n.r and x.t >= n.t and x.b <= n.b]
    return inside[0].text if inside else None


# ---------------------------------------------------------------- account
def calendar_rows(ctx):
    return ctx.d.find(tag="calendar_row")


def has_account(ctx):
    home(ctx)
    open_drawer(ctx)
    ok = bool(ctx.d.wait(timeout=6, text=re.compile(re.escape(ctx.account_email), re.I)))
    ctx.d.back()
    time.sleep(0.5)
    return ok


def login(ctx):
    """Chooses the (already added) system account in the app. The consent was given once, by hand."""
    d = ctx.d
    home(ctx)
    open_drawer(ctx)
    drawer_item(ctx, "drawer_add_account")
    n = d.wait(timeout=15, text=re.compile(re.escape(ctx.account_email), re.I))
    if n is None:
        ctx.fail(f"{ctx.account_email} is not among the accounts of the device")
    d.tap(*n.center)
    time.sleep(0.8)
    # On Android 14 a tap on the address does not choose the row (OK stays disabled): then the radio button at its right does.
    ok = d.wait(timeout=6, text=OK)
    if ok is not None and not ok.enabled:
        d.tap(int(d.screen_w * 0.765), n.center[1])
        time.sleep(0.8)
    d.click(timeout=6, text=OK)
    # the drawer stays open behind the account question; the first sync fills its calendar list
    found = d.wait(timeout=120, interval=2.0, tag="calendar_row") is not None
    d.back()
    time.sleep(0.8)
    home(ctx)
    return found


# ---------------------------------------------------------------- editor
def type_into(ctx, tag, value, clear=True):
    d = ctx.d
    d.click(tag=tag)
    time.sleep(0.5)
    if clear:
        d.clear_field()
    if value:
        d.text(value)
    time.sleep(0.4)


def reveal(ctx, tag, tries=7):
    """Scrolls the editor until the control with this tag is on screen; returns its node."""
    d = ctx.d
    hide_keyboard(ctx)  # swipes over the keyboard would be typed as glide letters
    last = None
    for i in range(tries):
        found = d.find(tag=tag)
        if found and 260 < found[0].center[1] < d.screen_h - 110:  # as before the navigation-bar work: the editor was tuned with this limit
            return found[0]
        if found and found[0].center[1] == last and 260 < last < d.usable_h - 40:  # the form cannot scroll further
            return found[0]
        last = found[0].center[1] if found else None
        # a control above the fold needs the opposite direction
        if found and found[0].center[1] <= 260:
            d.swipe(540, 700, 540, 1500, 350)
        else:
            d.swipe(540, 1700, 540, 800, 350)
        time.sleep(0.5)
    ctx.fail(f"control not reachable in the editor: {tag}")


def scroll_to(ctx, tag, tries=6):
    """Scrolls a long screen (the settings) until the control is well inside it, clear of the system gesture area."""
    d = ctx.d
    last = None
    for _ in range(tries):
        found = d.find(tag=tag)
        if found:
            y = found[0].center[1]
            if 150 < y < d.usable_h - 150:  # the last row of a list rests near the bottom edge
                return found[0]
            if y == last and 150 < y < d.usable_h - 40:  # the list cannot scroll any further: this is as high as it gets
                return found[0]
            last = y
        d.swipe(540, d.usable_h - 500, 540, 700, 350)
        time.sleep(0.5)
    ctx.fail(f"control not reachable by scrolling: {tag}")


def tap_tag(ctx, tag):
    n = reveal(ctx, tag)
    ctx.d.tap(*n.center)
    return n


def open_editor_new(ctx):
    d = ctx.d
    ctx.state["editor_opened_at"] = device_now(ctx)  # the editor proposes the next full hour from this moment
    d.click(tag="fab_new_event")
    if d.wait(timeout=12, tag="editor_title") is None:
        ctx.fail("the editor did not open")


def pick_start_day(ctx, offset):
    """Moves the start of the event `offset` days ahead with the date picker (the end follows)."""
    d = ctx.d
    target = ctx.state["editor_opened_at"].date() + timedelta(days=offset)
    tap_tag(ctx, "editor_start_date")
    if d.wait(timeout=8, text=OK) is None:
        ctx.fail("the date picker did not open")
    if target.month != ctx.state["editor_opened_at"].month:
        nxt = d.find(desc=re.compile(r"next|success|prossim", re.I))
        ctx.check(nxt, "no next-month button in the date picker")
        d.tap(*nxt[0].center)
        time.sleep(0.8)
    day_re = re.compile(rf"(^|\D){target.day}(\D|$)")
    nodes = [n for n in d.dump() if n.t > 500 and n.height < 300]
    cells = [n for n in nodes if n.text == str(target.day)]
    if not cells:  # Android 16 exposes the day only as "Wednesday, October 14, 2026"
        full = re.compile(rf"(?<!\d){target.day}(?!\d)\D*{target.year}")  # "Wednesday, October 14, 2026" / "mercoledì 14 ottobre 2026"
        cells = [n for n in nodes if full.search(n.text or "") or full.search(n.desc or "")]
    if not cells:
        ctx.note("date picker nodes: " + "; ".join(f"{n.text!r}/{n.desc!r}" for n in nodes[:12]))
    ctx.check(cells, f"the day {target.day} is not in the date picker")
    d.tap(*cells[0].center)
    time.sleep(0.5)
    d.click(text=OK)
    ctx.check(d.gone(timeout=6, text=S("cancel")), "the date picker stayed open")


def calendar_in_editor_is_test_account(ctx):
    nodes = ctx.d.dump()
    return any(ctx.account_email.lower() in (n.text or "").lower() for n in nodes)


def add_reminder(ctx, method, minutes, drop_defaults=True):
    """method: POPUP, ALARM or EMAIL; minutes: a custom number of minutes before the event."""
    d = ctx.d
    tap_tag(ctx, "editor_add_reminder")
    if d.wait(timeout=8, tag="reminder_custom_minutes") is None:
        ctx.fail("the reminder dialog did not open")
    d.click(tag=f"reminder_method_{method}")
    d.click(tag="reminder_custom_minutes")
    d.text(str(minutes))
    hide_keyboard(ctx)
    d.click(tag="reminder_ok")
    ctx.check(d.gone(timeout=6, tag="reminder_custom_minutes"), "the reminder dialog stayed open")


def remove_default_reminders(ctx, keep_matching=None):
    """Removes the reminders listed before ours (the calendar defaults), so that only the test ones fire."""
    d = ctx.d
    for _ in range(8):
        rm = [n for n in d.find(desc=S("remove")) if n.cls.endswith("ImageView") or n.cls.endswith("View")]
        rm = [n for n in rm if n.t > 0]
        if not rm:
            return
        d.tap(*rm[0].center)
        time.sleep(0.5)


def create_event(ctx, title, *, all_day=False, location=None, description=None, reminders=None, guests=None, meet=False,
                 recurrence=None, visibility=None, save=True, notify=None, day_offset=0):
    """Creates an event from the + button. Returns the full title. `reminders`: [(METHOD, minutes)]."""
    d = ctx.d
    full = title if title.startswith(TEST_PREFIX) else f"{TEST_PREFIX} {title}"
    home(ctx)
    open_editor_new(ctx)
    ctx.check(calendar_in_editor_is_test_account(ctx), f"the editor does not show {ctx.account_email}: refusing to write elsewhere")
    # the emulator keyboard sometimes garbles what is typed: the prefix is what lets the cleanup find the event
    for attempt in range(4):
        type_into(ctx, "editor_title", full)
        typed = d.find(tag="editor_title")
        if typed and typed[0].text == full:
            break
        time.sleep(1.0)
    else:
        ctx.fail(f"the title was typed wrongly: {typed[0].text if typed else None!r}")
    hide_keyboard(ctx)
    if day_offset:
        pick_start_day(ctx, day_offset)
    if all_day:
        tap_tag(ctx, "editor_all_day")
    if guests:
        for g in guests:
            tap_tag(ctx, "editor_guest_input")
            time.sleep(1.5)
            stop_if_permission_question(ctx)
            d.text(g)
            d.key(66)  # Enter / Done
            time.sleep(0.5)
        hide_keyboard(ctx)
    if meet:
        tap_tag(ctx, "editor_meet")
    if recurrence:
        recurrence(ctx)
    if location:
        tap_tag(ctx, "editor_location")
        d.text(location)
        hide_keyboard(ctx)
    if reminders is not None:
        reveal(ctx, "editor_add_reminder")
        remove_default_reminders(ctx)  # the X of a default row turns the defaults into an explicit list
        items = reminders() if callable(reminders) else reminders  # computed now: the lead time counts from here
        for method, minutes in items:
            add_reminder(ctx, method, minutes)
    if visibility:
        tap_tag(ctx, "editor_visibility")
        d.click(text=visibility)
    if description:
        tap_tag(ctx, "editor_description")
        d.text(description)
        hide_keyboard(ctx)
    ctx.step("editor_filled")
    if not save:
        return full
    d.click(tag="editor_save")
    handle_notify(ctx, notify)
    ctx.check(d.gone(timeout=15, tag="editor_title"), "the editor did not close after Save")
    ctx.created.append(full)
    time.sleep(1.5)
    return full


def handle_notify(ctx, notify=None):
    """The 'tell the guests?' question, when it comes: always answer 'do not send' unless told otherwise."""
    d = ctx.d
    n = d.wait(timeout=2.5, tag="notify_dont")
    if n is not None:
        d.click(tag="notify_send" if notify else "notify_dont")
        time.sleep(0.8)


# ---------------------------------------------------------------- finding and opening events
def open_search(ctx):
    home(ctx)
    for attempt in range(3):  # on a slow emulator the first tap can land while the screen is still settling
        ctx.d.click(tag="btn_search")
        if ctx.d.wait(timeout=8, tag="search_input") is not None:
            return
        home(ctx)
    ctx.fail("search did not open")


def search(ctx, query):
    """Opens search, types the query, returns the nodes with the titles shown (the search stays open)."""
    d = ctx.d
    open_search(ctx)
    d.click(tag="search_input")
    d.clear_field()
    d.text(query)
    time.sleep(2.0)
    hide_keyboard(ctx)
    return d.dump()


def result_titles(ctx, nodes, prefix=TEST_PREFIX):
    return [n for n in nodes if n.text.startswith(prefix) and n.rid != "search_input"]


def open_event(ctx, title):
    """Opens the detail of an event found by search (title exactly as created)."""
    d = ctx.d
    hits = []
    for attempt in range(4):
        nodes = search(ctx, title)
        hits = [n for n in nodes if n.text == title and n.rid != "search_input"]
        if hits:
            break
        close_search(ctx)
        time.sleep(3)
    if not hits:
        ctx.fail(f"event not found: {title}")
    d.tap(*hits[0].center)
    if d.wait(timeout=10, tag="detail_close") is None:
        ctx.fail("the detail sheet did not open")
    return True


def close_search(ctx):
    d = ctx.d
    if d.find(tag="search_back"):
        d.click(tag="search_back")
        time.sleep(0.5)


def close_detail(ctx):
    d = ctx.d
    if d.find(tag="detail_close"):
        d.click(tag="detail_close")
        d.gone(timeout=6, tag="detail_close")
    close_search(ctx)


def delete_open_event(ctx, scope="ALL", notify=None):
    """With the detail open: Delete (and confirm). `scope`: THIS, FOLLOWING or ALL for a series."""
    d = ctx.d
    d.click(tag="detail_more")
    d.click(text=S("delete"))
    n = d.wait(timeout=6, either=None, tag="delete_confirm")
    if n is not None:
        d.tap(*n.center)
    else:
        sc = d.wait(timeout=4, tag="scope_confirm")
        if sc is None:
            ctx.fail("no delete confirmation")
        d.click(tag=f"scope_{scope}")
        d.click(tag="scope_confirm")
    handle_notify(ctx, notify)
    ctx.check(d.gone(timeout=10, tag="detail_close"), "the detail stayed open after Delete")
    time.sleep(1.0)


def delete_event(ctx, title, scope="ALL"):
    open_event(ctx, title)
    delete_open_event(ctx, scope)
    close_search(ctx)
    if title in ctx.created:
        ctx.created.remove(title)


def cleanup(ctx, limit=40):
    """Deletes every event whose title starts with the prefix (and only those). Returns how many."""
    d = ctx.d
    removed = 0
    skipped = 0  # rows that cannot be changed from the app (read-only): the next one is tried instead of stopping
    for _ in range(limit):
        nodes = search(ctx, TEST_PREFIX)
        hits = result_titles(ctx, nodes)
        if skipped >= len(hits):
            close_search(ctx)
            break
        d.tap(*hits[skipped].center)
        if d.wait(timeout=10, tag="detail_close") is None:
            close_search(ctx)
            break
        if not d.find(tag="detail_more"):  # not ours to change from here
            print(f"  cleanup: '{hits[skipped].text}' is read-only in the app, left alone")
            skipped += 1
            close_detail(ctx)
            continue
        delete_open_event(ctx, "ALL")
        removed += 1
    home(ctx)
    ctx.created.clear()
    return removed


# ---------------------------------------------------------------- round trip with Google
def roundtrip(ctx):
    """Clears the app data and logs in again: whatever is still there came back from Google."""
    d = ctx.d
    if not d.serial.startswith("emulator-"):  # it wipes the app data: never on a real phone (it happened on 13/10/2026 with T70)
        raise Skip("clearing the app data is only done on emulators")
    d.shell(f"pm clear {PKG}")
    time.sleep(1.5)
    grant_permissions(ctx)
    start_app(ctx)
    return login(ctx)


# ---------------------------------------------------------------- misc
def minutes_to_next_hour():
    now = datetime.now()
    nxt = now.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
    return (nxt - now).total_seconds() / 60.0


def device_now(ctx):
    out = ctx.d.shell("date +%Y-%m-%dT%H:%M:%S").strip()
    return datetime.strptime(out, "%Y-%m-%dT%H:%M:%S")


def notification_text(ctx):
    return ctx.d.shell("dumpsys notification --noredact")
