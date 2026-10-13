"""Groups H (network, conflicts) and I (robustness)."""
import re
import time

import flows as F
from adbx import PKG
from harness import TEST_PREFIX, case


def D():
    import cases_d
    return cases_d


def pending_badge(ctx):
    """The number on the refresh button (changes not yet sent to Google), or 0."""
    d = ctx.d
    btn = d.find(tag="btn_refresh")
    if not btn:
        return 0
    b = btn[0]
    for n in d.dump():
        if re.fullmatch(r"\d+", n.text or "") and b.l - 40 <= n.center[0] <= b.r + 90 and b.t - 90 <= n.center[1] <= b.b + 40:
            return int(n.text)
    return 0


@case("T70", "H", "A change made offline waits in the queue and goes out when the network is back", account=True, slow=True)
def t70(ctx):
    d = ctx.d
    d.network(False)
    try:
        ctx.check(not d.has_network(), "could not switch the network off")
        title = F.create_event(ctx, "offline")
        F.home(ctx)
        time.sleep(3)
        badge = pending_badge(ctx)
        ctx.step("offline_badge")
        ctx.check(badge >= 1, "the refresh button shows no pending change while offline")
    finally:
        d.network(True)
    end = time.time() + 120
    while time.time() < end and pending_badge(ctx) > 0:
        time.sleep(4)
    ctx.check(pending_badge(ctx) == 0, "the change was not sent after the network came back")
    ctx.check(F.roundtrip(ctx), "login failed after clearing the data")
    nodes = F.search(ctx, TEST_PREFIX)
    ctx.check(title in [n.text for n in F.result_titles(ctx, nodes)], "the event made offline did not reach Google")
    F.close_search(ctx)


@case("T71", "H", "No network at start: no 'unable to resolve host' banner, no crash", account=True)
def t71(ctx):
    d = ctx.d
    d.network(False)
    try:
        F.start_app(ctx, fresh=True)
        d.click(tag="btn_refresh")
        time.sleep(8)
        ctx.step("offline_start")
        ctx.check(not d.find(text=re.compile(r"resolve host|UnknownHost", re.I)), "a network error banner is shown")
        ctx.check(not d.find(tag="snackbar"), "a banner appeared although only the network is missing")
        ctx.check(d.pid(), "the app died")
    finally:
        d.network(True)


@case("T80", "I", "Monkey: 1500 random events, offline, no crash (the app data is cleared afterwards)", account=True, slow=True)
def t80(ctx):
    d = ctx.d
    d.network(False)
    try:
        ctx.check(not d.has_network(), "the network is still up: refusing to run the monkey (it could change real events)")
        F.home(ctx)
        out = d.shell(
            f"monkey -p {PKG} --throttle 150 -s 4242 --pct-syskeys 0 --pct-nav 0 --pct-majornav 0 --pct-appswitch 0 --ignore-security-exceptions -v 1500",
            timeout=900,
        )
        ctx.check("Monkey finished" in out, "the monkey did not finish")
        ctx.check("// CRASH" not in out and "ANR" not in out, "the monkey caused a crash or an ANR")
        ctx.note("monkey: " + re.sub(r"\s+", " ", out.strip().splitlines()[-1])[:80])
    finally:
        # whatever the monkey queued (edits, deletions) must never reach Google: clear the data before the network returns
        d.shell(f"pm clear {PKG}")
        d.network(True)
    time.sleep(3)
    F.start_app(ctx)
    ctx.check(F.login(ctx), "login failed after the monkey")


@case("T81", "I", "App in the background under memory pressure comes back with its state", account=True)
def t81(ctx):
    d = ctx.d
    F.select_view(ctx, "WEEK")
    d.key(3)
    time.sleep(1)
    d.shell(f"am send-trim-memory {PKG} RUNNING_CRITICAL")
    d.shell(f"am kill {PKG}")
    time.sleep(2)
    F.start_app(ctx)
    ctx.check(F.selected_view(ctx) == "WEEK", "the view was not kept after the process was killed")
    F.select_view(ctx, "SCHEDULE")


@case("T82", "I", "No crash, ANR or failed worker in the whole run", account=False)
def t82(ctx):
    results = getattr(ctx, "results", [])
    bad = [r["id"] for r in results if "crash or failed worker" in (r.get("detail") or "")]
    ctx.check(not bad, f"crashes or failed workers in: {', '.join(bad)}")
    ctx.note(f"{len(results)} cases looked at")
