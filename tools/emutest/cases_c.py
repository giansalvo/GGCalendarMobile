"""Group C: views with events, search, export (.ics, .csv) and sharing. Needs the Google account in the app."""
import re
import time

import flows as F
from harness import TEST_PREFIX, case
from strings import S, has, any_of

DOWNLOADS = "/sdcard/Download"


def D():
    import cases_d  # imported late: it registers its own cases after this module's
    return cases_d


def ensure_private(ctx):
    if ctx.state.get("private_event"):
        return ctx.state["private_event"]
    title = F.create_event(ctx, "private", visibility=S("visibility_private"))
    ctx.state["private_event"] = title
    return title


@case("T20", "C", "Sync and the refresh button", account=True)
def t20(ctx):
    d = ctx.d
    F.home(ctx)
    d.click(tag="btn_refresh")
    time.sleep(4)
    # no error banner after a sync; the calendars are listed
    ctx.check(not d.find(tag="snackbar"), "an error or conflict banner appeared after the sync")
    F.open_drawer(ctx)
    ctx.check(len(d.find(tag="calendar_row")) >= 1, "no calendar listed after the sync")
    d.back()


@case("T21", "C", "Tap an event in the list: the detail opens", account=True)
def t21(ctx):
    d = ctx.d
    title = D().fixture(ctx)
    F.select_view(ctx, "SCHEDULE")
    n = d.wait(timeout=12, text=re.compile("^" + re.escape(title) + "$"))
    ctx.check(n, "the event is not in the schedule")
    d.tap(*n.center)
    ctx.check(d.wait(timeout=10, tag="detail_close"), "the detail did not open from the list")
    text = D().detail_text(ctx)
    ctx.check(title in text, "the detail does not show the title")
    F.close_detail(ctx)


@case("T22", "C", "Tap an empty slot of the week grid: the editor opens", account=True)
def t22(ctx):
    d = ctx.d
    F.select_view(ctx, "WEEK")
    # a real calendar has events in some slots: a tap that lands on one opens its detail, so try other slots
    opened = False
    for y in (1500, 1000, 1250, 1750, 700):
        for x in (660, 880, 330, 550, 990, 200):
            d.tap(x, y)
            if d.wait(timeout=3, tag="editor_title"):
                opened = True
                break
            if d.find(tag="detail_close"):
                F.close_detail(ctx)
        if opened:
            break
    ctx.check(opened, "the editor did not open from an empty slot")
    ctx.step("editor_from_grid")
    F.home(ctx)
    F.select_view(ctx, "SCHEDULE")


@case("T23", "C", "Search by title, description and no result", account=True)
def t23(ctx):
    d = ctx.d
    title = D().fixture(ctx)
    nodes = F.search(ctx, D().DESCRIPTION)
    ctx.check(D().title_nodes(ctx, nodes, title) or any(title in n.text for n in nodes), "search by description did not find the event")
    nodes = F.search(ctx, title.replace(TEST_PREFIX, "").strip().split()[0])
    ctx.check(any(TEST_PREFIX in n.text for n in nodes), "search by a word of the title found nothing")
    nodes = F.search(ctx, "zzqqxx")
    ctx.check(d.find(nodes, text=S("search_empty")), "no 'no events found' message for an unknown word")
    d.click(tag="search_input")
    d.clear_field()
    ctx.check(d.wait(timeout=5, text=S("search_prompt")), "no prompt on an empty search")
    F.close_search(ctx)


@case("T24", "C", "Mini calendar under the title follows the months", account=True)
def t24(ctx):
    d = ctx.d
    F.select_view(ctx, "SCHEDULE")
    start = F.toolbar_title(ctx)
    d.click(tag="btn_title")
    time.sleep(1.5)
    days = [n for n in d.dump() if re.fullmatch(r"\d{1,2}", n.text or "") and n.t < 1100]
    ctx.check(len(days) >= 28, f"the mini calendar shows {len(days)} day numbers")
    ctx.step("mini_calendar")
    d.swipe(900, 600, 150, 600, 250)
    time.sleep(1.5)
    ctx.check(F.toolbar_title(ctx) != start, "the title did not follow the mini calendar")
    d.click(tag="btn_today")
    time.sleep(1)
    F.home(ctx)


def pull_text(ctx, path):
    return ctx.d.adb("exec-out", "cat", path, binary=True)


def export(ctx, fmt):
    """Runs the export dialog for the whole current year and returns (file bytes, summary text)."""
    d = ctx.d
    d.shell(f"rm -f {DOWNLOADS}/GGCalendar_*")
    F.home(ctx)
    F.open_drawer(ctx)
    F.drawer_item(ctx, "drawer_export")
    ctx.check(d.wait(timeout=10, tag="export_confirm"), "the export dialog did not open")
    d.click(tag="export_ics" if fmt == "ics" else "export_csv")
    ctx.step("export_dialog_" + fmt)
    d.click(tag="export_confirm")
    save = None
    end = time.time() + 20
    while save is None and time.time() < end:  # the picker is com.android.documentsui or com.google.android.documentsui
        hits = [n for n in d.dump() if n.rid == "android:id/button1" and "documentsui" in n.pkg]
        save = hits[0] if hits else None
        if save is None:
            time.sleep(0.8)
    ctx.check(save, "the file picker did not open")
    d.tap(*save.center)
    ok = d.wait(timeout=30, tag="export_summary_ok")
    ctx.check(ok, "no summary after saving the file")
    summary = " ".join(n.text for n in d.dump() if n.text)
    ctx.step("export_summary_" + fmt)
    d.tap(*ok.center)
    time.sleep(1)
    files = d.shell(f"ls {DOWNLOADS}/GGCalendar_*.{fmt}").split()
    ctx.check(files, f"no .{fmt} file in the Downloads folder")
    return pull_text(ctx, files[0]), summary


@case("T25", "C", "Export .ics: format, CRLF, no organizer/guests/alarms/class, private events left out", account=True)
def t25(ctx):
    title = D().fixture(ctx)
    private = ensure_private(ctx)
    data, summary = export(ctx, "ics")
    text = data.decode("utf-8", errors="replace")
    ctx.check(text.startswith("BEGIN:VCALENDAR"), "the file does not start with BEGIN:VCALENDAR")
    ctx.check("\r\n" in text and "\n" not in text.replace("\r\n", ""), "lines are not CRLF")
    ctx.check(title in text.replace("\r\n ", ""), "the fixture event is not in the file")
    ctx.check(private not in text.replace("\r\n ", ""), "the private event was exported")
    for banned in ("ORGANIZER", "ATTENDEE", "BEGIN:VALARM", "CLASS:"):
        ctx.check(banned not in text, f"the file contains {banned}")
    too_long = [l for l in text.split("\r\n") if len(l.encode("utf-8")) > 75]
    ctx.check(not too_long, f"{len(too_long)} lines longer than 75 bytes")
    ctx.check(re.search(r"privat", summary, re.I), f"the summary does not mention the private events: {summary[:200]}")
    ctx.note(f"{text.count('BEGIN:VEVENT')} events exported")


@case("T26", "C", "Export CSV: BOM, ';' separator, header, one row per occurrence, private left out", account=True)
def t26(ctx):
    title = D().fixture(ctx)
    private = ensure_private(ctx)
    data, summary = export(ctx, "csv")
    ctx.check(data.startswith(b"\xef\xbb\xbf"), "no UTF-8 BOM at the start")
    text = data.decode("utf-8-sig", errors="replace")
    lines = text.split("\r\n")
    ctx.check(lines[0].count(";") == 13, f"the header has {lines[0].count(';') + 1} columns, expected 14")
    ctx.check(any(title in l for l in lines), "the fixture event is not in the file")
    ctx.check(not any(private in l for l in lines), "the private event was exported")
    ctx.check(re.search(r"privat", summary, re.I), "the summary does not mention the private events")
    ctx.note(f"{len([l for l in lines if l.strip()]) - 1} rows")


@case("T27", "C", "Share an event as text and as .ics: the system chooser opens", account=True)
def t27(ctx):
    d = ctx.d
    title = D().fixture(ctx)
    for tag in ("share_text", "share_ics"):
        F.open_event(ctx, title)
        d.click(tag="detail_share")
        ctx.check(d.wait(timeout=8, tag=tag), "the share dialog did not open")
        d.click(tag=tag)
        time.sleep(2.5)
        top = d.top_activity()
        ctx.check(re.search(r"Chooser|Resolver", top), f"the system chooser did not open ({top[:100]})")
        ctx.step("chooser_" + tag)
        d.back()
        time.sleep(1)
        F.home(ctx)


@case("TM19", "C", "Remove the account from the app (log out): calendars go, adding it again brings them back", account=True)
def tm19(ctx):
    # manual (14/10/2026): after the removal Google asks for the consent again, a step the script never takes
    ctx.skip("manual: adding the account again after the removal needs the user's consent on the Google page")
