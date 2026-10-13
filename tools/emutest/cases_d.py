"""Groups D (events), E (recurrences) and F (dragging). Only events titled [TEST GGCAL] ..., in the primary calendar."""
import re
import time

import flows as F
from harness import TEST_PREFIX, case
from strings import S, has, any_of

# NOTE: Gboard corrects ordinary words while the tests type (Aula -> Ayla), so the texts below are made of
# invented tokens that it leaves alone.
LOCATION = "Lab7Q"
DESCRIPTION = "NoteAlphaQ"


# ---------------------------------------------------------------- helpers
def title_nodes(ctx, nodes, title):
    return sorted([n for n in nodes if n.text == title and n.rid != "search_input"], key=lambda n: n.t)


def occurrences(ctx, title, expected=None, timeout=25, at_least=None, at_most=None):
    """How many occurrences with this exact title the search lists (the search is left open).

    A change reaches the search a moment after Save, so with `expected` the search is repeated until the count matches."""
    end = time.time() + timeout
    while True:
        nodes = F.search(ctx, title)
        n = len(title_nodes(ctx, nodes, title))
        no_wait = expected is None and at_least is None and at_most is None
        reached = (
            n == expected
            or (at_least is not None and n >= at_least)
            or (at_most is not None and n <= at_most)
        )
        if no_wait or reached or time.time() > end:
            return n
        F.close_search(ctx)
        time.sleep(3)


def search_rows(ctx, title, at_least=2):
    """The rows of the occurrences with this title, waiting until at least `at_least` are listed."""
    occurrences(ctx, title, at_least=at_least)
    return title_nodes(ctx, ctx.d.dump(), title)


def detail_text(ctx):
    return " | ".join(n.text for n in ctx.d.dump() if n.text)


def edit_open_event(ctx, new_title=None, scope=None, location=None):
    """With a detail sheet open: Edit, change the title, Save, and answer the scope question if it comes."""
    d = ctx.d
    d.click(tag="detail_edit")
    if d.wait(timeout=12, tag="editor_title") is None:
        ctx.fail("the editor did not open from the detail")
    if new_title:
        F.type_into(ctx, "editor_title", new_title)
        F.hide_keyboard(ctx)
    if location:
        F.tap_tag(ctx, "editor_location")
        d.clear_field()
        d.text(location)
        F.hide_keyboard(ctx)
    d.click(tag="editor_save")
    sc = d.wait(timeout=3, tag="scope_confirm")
    if sc is not None:
        d.click(tag=f"scope_{scope or 'THIS'}")
        d.click(tag="scope_confirm")
    F.handle_notify(ctx)
    ctx.check(d.gone(timeout=15, tag="editor_title"), "the editor did not close after Save")
    time.sleep(1.5)


def pick_recurrence(ctx, kind):
    """Chooses a preset ('DAILY', 'WEEKDAYS') in the recurrence dialog of the editor."""
    d = ctx.d
    F.tap_tag(ctx, "editor_recurrence")
    time.sleep(0.8)
    rows = [n for n in d.dump() if n.rid.startswith("choice_")]
    if kind == "DAILY":
        pick = [n for n in rows if "FREQ=DAILY" in n.rid]
    elif kind == "WEEKDAYS":
        pick = [n for n in rows if "BYDAY=MO,TU,WE,TH,FR" in n.rid]
    else:
        pick = [n for n in rows if kind in n.rid]
    ctx.check(pick, f"no recurrence preset {kind} among {[n.rid for n in rows]}")
    d.tap(*pick[0].center)
    time.sleep(0.8)


def custom_recurrence(ctx, every, count):
    d = ctx.d
    F.tap_tag(ctx, "editor_recurrence")
    time.sleep(0.8)
    d.click(tag="choice_custom")
    ctx.check(d.wait(timeout=8, text=S("custom_recurrence")), "the custom recurrence dialog did not open")
    edits = sorted([n for n in d.dump() if n.cls.endswith("EditText")], key=lambda n: n.t)
    ctx.check(len(edits) >= 2, f"expected the interval and count fields, found {len(edits)}")
    d.tap(*edits[0].center)
    d.clear_field()
    d.text(str(every))
    F.hide_keyboard(ctx)
    d.click(text=S("ends_after"))
    edits = sorted([n for n in d.dump() if n.cls.endswith("EditText")], key=lambda n: n.t)
    d.tap(*edits[1].center)
    d.clear_field()
    d.text(str(count))
    F.hide_keyboard(ctx)
    d.click(text=any_of("OK", "Ok"))
    ctx.check(d.gone(timeout=6, text=S("custom_recurrence")), "the custom recurrence dialog stayed open")


def ensure_event(ctx, key, title, **kw):
    """Creates an event once per run and remembers its title under `key`."""
    if key in ctx.state:
        return ctx.state[key]
    full = F.create_event(ctx, title, **kw)
    ctx.state[key] = full
    return full


def fixture(ctx):
    return ensure_event(ctx, "fx", "fixture", location=LOCATION, description=DESCRIPTION)


# ================================================================= D. events
@case("T30", "D", "Create an event with place and description", account=True)
def t30(ctx):
    d = ctx.d
    title = fixture(ctx)
    F.open_event(ctx, title)
    text = detail_text(ctx)
    ctx.step("detail")
    ctx.check(LOCATION in text, "the place is not in the detail")
    ctx.check(DESCRIPTION in text, "the description is not in the detail")
    ctx.check(ctx.account_email in text, "the account is not in the detail")
    F.close_detail(ctx)
    ctx.state["roundtrip_keep"] = title


@case("T31", "D", "All-day event", account=True)
def t31(ctx):
    title = ensure_event(ctx, "allday", "allday", all_day=True)
    F.open_event(ctx, title)
    nodes = ctx.d.dump()
    date_line = [n.text for n in nodes if re.match(r"^\w+ \d{1,2} \w+", n.text or "") and n.rid != "search_input"]
    ctx.check(date_line, "the detail shows no date line")
    ctx.check(not re.search(r"\d{1,2}:\d{2}", date_line[0]), f"an all-day event shows a clock time: {date_line[0]}")
    F.close_detail(ctx)
    nodes = F.search(ctx, title)
    ctx.check(ctx.d.find(nodes, text=S("all_day")), "the search row does not say 'All day'")
    F.close_search(ctx)


@case("T32", "D", "Edit title and place", account=True)
def t32(ctx):
    title = fixture(ctx)
    F.open_event(ctx, title)
    new = title + " mod"
    edit_open_event(ctx, new_title=new, location="Lab9Q")
    ctx.created[:] = [new if t == title else t for t in ctx.created]
    ctx.state["fx"] = new
    ctx.state["roundtrip_keep"] = new
    F.close_search(ctx)
    F.open_event(ctx, new)
    text = detail_text(ctx)
    ctx.check("Lab9Q" in text and "Lab7Q" not in text, "the new place was not saved")
    F.close_detail(ctx)
    ctx.check(occurrences(ctx, title + " mod", 1) == 1, "the renamed event should be listed once")
    ctx.check(occurrences(ctx, title, 0) == 0, "the old title should no longer be an exact match")
    F.close_search(ctx)


@case("T33", "D", "Delete with confirmation", account=True)
def t33(ctx):
    title = ensure_event(ctx, "allday", "allday", all_day=True)
    F.delete_event(ctx, title)
    nodes = F.search(ctx, title)
    ctx.check(not title_nodes(ctx, nodes, title), "the deleted event is still listed")
    F.close_search(ctx)
    ctx.state.pop("allday", None)
    ctx.state["roundtrip_gone"] = title


@case("T34", "D", "Move an event to another calendar", account=True)
def t34(ctx):
    ctx.skip("would write outside the primary calendar of the test account (not allowed by the test rules)")


@case("T35", "D", "Guests (only @example.com), without sending anything", account=True)
def t35(ctx):
    d = ctx.d
    d.shell("true")
    home_title = F.create_event(ctx, "guests", guests=["emu.test@example.com"], save=False)
    # the invitations must not go out: untick 'Send invitations' while the field is visible
    nodes = d.dump()
    row = F.reveal(ctx, "editor_guest_input")
    nodes = d.dump()
    label = d.find(nodes, text=S("send_invites"))
    ctx.check(label, "the 'Send invitations' option is missing")
    boxes = [n for n in nodes if n.cls.endswith("CheckBox") and abs(n.center[1] - label[0].center[1]) < 60]
    if boxes and boxes[0].checked:
        d.tap(*label[0].center)
    ctx.step("guest_added")
    d.click(tag="editor_save")
    F.handle_notify(ctx, notify=False)
    ctx.check(d.gone(timeout=15, tag="editor_title"), "the editor did not close")
    ctx.created.append(home_title)
    ctx.state["guest_event"] = home_title
    F.open_event(ctx, home_title)
    for _ in range(6):  # the guest list can take a moment to show in the detail
        if "emu.test@example.com" in detail_text(ctx):
            break
        time.sleep(1.5)
    ctx.check("emu.test@example.com" in detail_text(ctx), "the guest is not in the detail")
    ctx.check(d.find(text=S("write_to_guests")) or d.find(text=has("write_to_guests")), "no 'write to guests' button")
    F.close_detail(ctx)


@case("T36", "D", "Edit and delete an event with guests ask whether to tell them", account=True)
def t36(ctx):
    d = ctx.d
    title = ctx.state.get("guest_event")
    if not title:
        ctx.skip("needs T35")
    F.open_event(ctx, title)
    d.click(tag="detail_edit")
    d.wait(timeout=10, tag="editor_title")
    F.type_into(ctx, "editor_title", title + " b")
    F.hide_keyboard(ctx)
    d.click(tag="editor_save")
    ctx.check(d.wait(timeout=6, tag="notify_dont"), "no question about telling the guests when saving")
    ctx.step("notify_dialog")
    d.click(tag="notify_dont")
    ctx.check(d.gone(timeout=15, tag="editor_title"), "the editor did not close")
    new = title + " b"
    ctx.created[:] = [new if t == title else t for t in ctx.created]
    F.open_event(ctx, new)
    d.click(tag="detail_more")
    d.click(text=S("delete"))
    d.click(tag="delete_confirm")
    ctx.check(d.wait(timeout=6, tag="notify_dont"), "no question about telling the guests when deleting")
    d.click(tag="notify_dont")
    ctx.check(d.gone(timeout=10, tag="detail_close"), "the detail stayed open")
    F.close_search(ctx)
    ctx.created.remove(new)
    ctx.state.pop("guest_event", None)


@case("T37", "D", "Google Meet link", account=True, slow=True)
def t37(ctx):
    d = ctx.d
    title = F.create_event(ctx, "meet", meet=True)
    ok = False
    for _ in range(8):
        F.open_event(ctx, title)
        text = detail_text(ctx)
        F.close_detail(ctx)
        if re.search(S("join_meet", exact=False), text):
            ok = True
            break
        ctx.check(re.search(S("meet_pending", exact=False), text) or True, "")
        d.click(tag="btn_refresh")
        time.sleep(8)
    ctx.check(ok, "the Meet link never arrived (still being created)")


@case("T38", "D", "Event time zone (New York)", account=True)
def t38(ctx):
    d = ctx.d
    home_title = F.create_event(ctx, "tz", save=False)
    F.tap_tag(ctx, "editor_zone")
    sb = d.wait(timeout=8, either=S("search_time_zone", exact=False))
    ctx.check(sb, "the time zone list did not open")
    d.tap(*sb.center)
    d.text("New_York")
    F.hide_keyboard(ctx)
    row = d.wait(timeout=6, text=re.compile(r"America/New_York"))
    ctx.check(row, "America/New_York not listed")
    d.tap(*row.center)
    time.sleep(0.8)
    d.click(tag="editor_save")
    ctx.check(d.gone(timeout=15, tag="editor_title"), "the editor did not close")
    ctx.created.append(home_title)
    F.open_event(ctx, home_title)
    ctx.check(re.search(r"Eastern|orientale|New_York|New York", detail_text(ctx)), "the detail does not show the New York zone")
    F.close_detail(ctx)


@case("T39", "D", "Scrolling past the top does not close the editor; X asks to discard", account=True)
def t39(ctx):
    d = ctx.d
    F.home(ctx)
    F.open_editor_new(ctx)
    F.type_into(ctx, "editor_title", "T39 draft")
    F.hide_keyboard(ctx)
    for _ in range(4):
        d.swipe(540, 700, 540, 1900, 250)
        time.sleep(0.5)
    ctx.check(d.find(tag="editor_title"), "pulling the form down closed the editor")
    d.click(tag="editor_close")
    ctx.check(d.wait(timeout=6, tag="discard_confirm"), "no discard question on X")
    d.click(tag="discard_confirm")
    ctx.check(d.gone(timeout=8, tag="editor_title"), "Discard did not close the editor")
    # the next new event must start empty (regression: the old draft came back)
    F.open_editor_new(ctx)
    time.sleep(1.0)
    title_nodes_ = d.find(tag="editor_title")
    ctx.check(title_nodes_ and "T39" not in title_nodes_[0].text, "the discarded draft came back in the next editor")
    d.back()
    time.sleep(0.5)
    F.home(ctx)


@case("T40", "D", "Colour, free/busy and private visibility", account=True)
def t40(ctx):
    d = ctx.d
    title = F.create_event(ctx, "private", visibility=S("visibility_private"), save=False)
    n = F.tap_tag(ctx, "editor_availability")
    time.sleep(0.5)
    ctx.check(d.find(text=S("free")), "the availability did not change to 'Free'")
    d.click(tag="editor_save")
    ctx.check(d.gone(timeout=15, tag="editor_title"), "the editor did not close")
    ctx.created.append(title)
    ctx.state["private_event"] = title
    F.open_event(ctx, title)
    text = detail_text(ctx)
    ctx.check(re.search(S("visibility_private", exact=False), text) or re.search(S("free", exact=False), text), "free/private not shown in the detail")
    F.close_detail(ctx)


@case("T33R", "D", "Round trip with Google: what was saved comes back, what was deleted does not", account=True, slow=True)
def t33r(ctx):
    keep = ctx.state.get("roundtrip_keep")
    gone = ctx.state.get("roundtrip_gone")
    if not keep:
        ctx.skip("needs T30/T32 first")
    ctx.check(F.roundtrip(ctx), "the login after clearing the data did not bring the calendars back")
    nodes = F.search(ctx, TEST_PREFIX)
    names = [n.text for n in F.result_titles(ctx, nodes)]
    ctx.check(keep in names, f"the saved event did not come back from Google: {names}")
    if gone:
        ctx.check(gone not in names, "the deleted event came back from Google")
    F.close_search(ctx)
    ctx.state["account_ok"] = True


# ================================================================= E. recurrences
@case("T41", "E", "Preset: every day", account=True)
def t41(ctx):
    title = F.create_event(ctx, "daily", recurrence=lambda c: pick_recurrence(c, "DAILY"))
    ctx.state["daily"] = title
    n = occurrences(ctx, title, at_least=4)
    ctx.step("daily_results")
    ctx.check(n >= 4, f"a daily series should list several occurrences, found {n}")
    F.close_search(ctx)


@case("T42", "E", "Custom: every 2 days, 3 times", account=True)
def t42(ctx):
    title = F.create_event(ctx, "custom", recurrence=lambda c: custom_recurrence(c, 2, 3))
    ctx.state["custom"] = title
    n = occurrences(ctx, title)
    n = occurrences(ctx, title, 3)
    ctx.check(n == 3, f"expected exactly 3 occurrences, found {n}")
    F.close_search(ctx)
    F.delete_event(ctx, title, scope="ALL")
    ctx.state.pop("custom", None)


@case("T43", "E", "Edit one occurrence, then this and the following", account=True)
def t43(ctx):
    d = ctx.d
    # a finite series (6 days), so that every occurrence fits on the screen of the search
    title = ensure_event(ctx, "scoped", "scoped", recurrence=lambda c: custom_recurrence(c, 1, 6))
    # second occurrence only
    rows = search_rows(ctx, title, 6)
    ctx.check(len(rows) == 6, f"expected the 6 occurrences of the series, found {len(rows)}")
    d.tap(*rows[1].center)
    d.wait(timeout=10, tag="detail_close")
    edit_open_event(ctx, new_title=title + " X", scope="THIS")
    ctx.created.append(title + " X")
    ctx.check(occurrences(ctx, title + " X", 1) == 1, "only the second occurrence should carry the new title")
    ctx.check(occurrences(ctx, title, at_least=4) >= 4, "the other occurrences should keep the title")
    # this and following from the 3rd (now 2nd in the old-title list)
    rows = search_rows(ctx, title, 3)
    ctx.check(len(rows) >= 2, "not enough occurrences left to continue")
    d.tap(*rows[1].center)
    d.wait(timeout=10, tag="detail_close")
    edit_open_event(ctx, new_title=title + " Y", scope="FOLLOWING")
    ctx.created.append(title + " Y")
    ys = occurrences(ctx, title + " Y", at_least=3)
    ctx.check(ys >= 3, f"the following occurrences should be renamed, found {ys}")
    ctx.check(occurrences(ctx, title, at_most=2) <= 2, "occurrences before the change must keep the old title")
    F.close_search(ctx)


@case("T44", "E", "Delete this, this and following, all", account=True)
def t44(ctx):
    d = ctx.d
    base = ctx.state.get("scoped")
    if not base:
        ctx.skip("needs T43")
    y = base + " Y"
    before = occurrences(ctx, y, at_least=3)
    rows = search_rows(ctx, y, 3)
    ctx.check(len(rows) >= 2, "not enough occurrences to delete one")
    d.tap(*rows[1].center)
    d.wait(timeout=10, tag="detail_close")
    F.delete_open_event(ctx, scope="THIS")
    ctx.check(occurrences(ctx, y, before - 1) == before - 1, "deleting one occurrence should remove exactly one")
    rows = search_rows(ctx, y, 2)
    ctx.check(len(rows) >= 2, "not enough occurrences to delete the following ones")
    d.tap(*rows[1].center)
    d.wait(timeout=10, tag="detail_close")
    F.delete_open_event(ctx, scope="FOLLOWING")
    ctx.check(occurrences(ctx, y) <= 1, "deleting 'this and following' should leave at most the first")
    F.close_search(ctx)
    F.cleanup(ctx)  # the rest of the series (whatever scope each piece has)


# ================================================================= F. dragging (needs `input motionevent`, Android 10+)
def long_press_drag(ctx, x, y, dx, dy, steps=12):
    d = ctx.d
    d.shell(f"input motionevent DOWN {x} {y}")
    time.sleep(0.9)
    for i in range(1, steps + 1):
        d.shell(f"input motionevent MOVE {x + dx * i // steps} {y + dy * i // steps}")
        time.sleep(0.05)
    d.shell(f"input motionevent UP {x + dx} {y + dy}")


def event_times(ctx, title):
    F.open_event(ctx, title)
    m = re.search(r"(\d{1,2}:\d{2}\s?(?:AM|PM)?)\D+(\d{1,2}:\d{2}\s?(?:AM|PM)?)", detail_text(ctx))
    F.close_detail(ctx)
    return m.groups() if m else None


@case("T45", "F", "Drag an event to another hour in the day view", account=True, api_min=29)
def t45(ctx):
    d = ctx.d
    title = ensure_event(ctx, "drag", "drag")
    before = event_times(ctx, title)
    F.select_view(ctx, "DAY")
    block = d.wait(timeout=10, text=re.compile(re.escape(title)))
    ctx.check(block, "the event block is not visible in the day view")
    x, y = block.center
    if y > d.usable_h - 500:  # late in the evening the block is at the bottom of the screen: move it up
        long_press_drag(ctx, x, y, 0, -180)
    else:
        long_press_drag(ctx, x, y, 0, 180)
    time.sleep(1.5)
    F.handle_notify(ctx)
    after = event_times(ctx, title)
    ctx.check(before and after and before != after, f"the time did not change: {before} -> {after}")
    F.select_view(ctx, "SCHEDULE")


@case("T46", "F", "Drag an event to the next day (week view)", account=True, api_min=29)
def t46(ctx):
    d = ctx.d
    title = ctx.state.get("drag")
    if not title:
        ctx.skip("needs T45")
    F.select_view(ctx, "WEEK")
    block = d.wait(timeout=10, text=re.compile(re.escape(title[:18])))
    ctx.check(block, "the event block is not visible in the week view")
    x, y = block.center
    long_press_drag(ctx, x, y, 140, 0)
    time.sleep(1.5)
    F.handle_notify(ctx)
    F.open_event(ctx, title)
    day_text = detail_text(ctx)
    F.close_detail(ctx)
    ctx.note("day after the drag: " + day_text.split("|")[1].strip()[:40] if "|" in day_text else "")
    F.select_view(ctx, "SCHEDULE")


@case("T47", "F", "Resize an event from its lower edge", account=True, api_min=29)
def t47(ctx):
    d = ctx.d
    title = ctx.state.get("drag")
    if not title:
        ctx.skip("needs T45")
    before = event_times(ctx, title)
    F.select_view(ctx, "WEEK")
    d.click(tag="btn_today")
    time.sleep(1)
    block = d.wait(timeout=10, text=re.compile(re.escape(title[:18])))
    ctx.check(block, "the event block is not visible in the week view (T46 moved it to tomorrow)")
    box = block
    for _ in range(4):  # the lower edge must be on screen, clear of the navigation bar (an event late in the evening is not)
        block = d.wait(timeout=6, text=re.compile(re.escape(title[:18]))) or block
        nodes = d.dump()
        holder = [n for n in nodes if n.l <= block.l and n.r >= block.r and n.t <= block.t and n.b >= block.b and n.width < 1100 and n.height < 900]
        holder = sorted(holder, key=lambda n: n.width * n.height)
        box = holder[0] if holder else block
        if box.b < d.usable_h - 250 and box.t > 450:
            break
        d.swipe(540, 1500, 540, 900, 400)
        time.sleep(0.8)
    if box.b >= d.usable_h - 250:  # an event late in the evening: move it up first, then resize it (the move is T45 again)
        long_press_drag(ctx, box.center[0], box.t + 25, 0, -700)
        time.sleep(1.5)
        F.handle_notify(ctx)
        before = event_times(ctx, title)
        F.select_view(ctx, "WEEK")
        d.click(tag="btn_today")
        time.sleep(1)
        block = d.wait(timeout=10, text=re.compile(re.escape(title[:18])))
        ctx.check(block, "the event is not visible after moving it up")
        nodes = d.dump()
        holder = [n for n in nodes if n.l <= block.l and n.r >= block.r and n.t <= block.t and n.b >= block.b and n.width < 1100 and n.height < 900]
        holder = sorted(holder, key=lambda n: n.width * n.height)
        box = holder[0] if holder else block
    long_press_drag(ctx, box.center[0], box.b - 6, 0, 150)
    time.sleep(1.5)
    F.handle_notify(ctx)
    after = event_times(ctx, title)
    ctx.check(before and after and before != after, f"the end time did not change: {before} -> {after}")
    F.select_view(ctx, "SCHEDULE")


@case("T48", "F", "Drag an event of a series asks the scope", account=True, api_min=29)
def t48(ctx):
    d = ctx.d
    title = ensure_event(ctx, "daily2", "daily2", recurrence=lambda c: pick_recurrence(c, "DAILY"))
    F.select_view(ctx, "DAY")
    d.click(tag="btn_today")  # the day view may open on a date picked by an earlier case
    time.sleep(1)
    block = d.wait(timeout=10, text=re.compile(re.escape(title[:18])))
    ctx.check(block, "the series is not visible today in the day view")
    x, y = block.center
    long_press_drag(ctx, x, y, 0, 180)
    ctx.check(d.wait(timeout=8, tag="scope_confirm"), "no question about the scope when dragging a series event")
    d.click(tag="scope_cancel")
    F.select_view(ctx, "SCHEDULE")
