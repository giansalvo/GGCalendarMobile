"""Case registry and context for the emulator tests."""
import random
import time
import traceback

from adbx import PKG, Device  # noqa: F401

CASES = []  # (id, group, title, options, function)
TEST_PREFIX = "[TEST GGCAL]"


class Skip(Exception):
    pass


class Fail(Exception):
    pass


def case(cid, group, title, account=False, api_min=0, api_max=999, slow=False):
    """Registers a test. `account`: needs the Google account in the app. `slow`: waits minutes (reminders)."""

    def deco(fn):
        CASES.append((cid, group, title, dict(account=account, api_min=api_min, api_max=api_max, slow=slow), fn))
        return fn

    return deco


class Ctx:
    def __init__(self, device, account_email, apk, apk_prev=None, run_tag=None):
        self.d = device
        # Every event this run creates ends with this tag, and the cleanup deletes only the events carrying it: several runs
        # (an emulator and a phone, on the same account) can work at the same time without deleting each other's events.
        self.run_tag = run_tag or "r" + "".join(random.choice("0123456789abcdef") for _ in range(4))
        self.account_email = account_email
        self.apk = apk
        self.apk_prev = apk_prev
        self.state = {}      # shared between the cases of a run (e.g. the events they created)
        self.notes = []
        self.created = []    # titles of the events created by the run, for the final cleanup

    # --- verdicts
    def check(self, cond, message):
        if not cond:
            self.d.shot("FAIL_" + message[:30])
            raise Fail(message)

    def fail(self, message):
        self.check(False, message)

    def skip(self, reason):
        raise Skip(reason)

    def note(self, text):
        self.notes.append(text)

    def step(self, name):
        return self.d.shot(name)

    def sleep(self, seconds):
        time.sleep(seconds)


def run_case(ctx, entry):
    cid, group, title, opts, fn = entry
    d = ctx.d
    ctx.notes = []
    try:
        d.shell("cmd statusbar collapse")  # an open notification shade made uiautomator hang (13/10/2026)
    except Exception:
        pass
    d.logcat_clear()
    t0 = time.time()
    try:
        if d.api < opts["api_min"] or d.api > opts["api_max"]:
            raise Skip(f"needs Android API {opts['api_min']}-{opts['api_max']}, this is {d.api}")
        if opts["account"] and not ctx.state.get("account_ok"):
            raise Skip("no Google account in the app")
        fn(ctx)
        bad = d.problems()
        if bad:
            d.shot("crash_" + cid)
            raise Fail("crash or failed worker in the log: " + bad[0][:300].replace("\n", " | "))
        return "PASS", "; ".join(ctx.notes), time.time() - t0
    except Skip as e:
        return "SKIP", str(e), time.time() - t0
    except Fail as e:
        return "FAIL", str(e), time.time() - t0
    except LookupError as e:
        d.shot("lookup_" + cid)
        return "FAIL", f"control not found: {e} " + traceback.format_exc(limit=3).replace(chr(10), " | "), time.time() - t0
    except Exception as e:  # noqa: BLE001
        try:
            d.shot("error_" + cid)
        except Exception:
            pass
        return "FAIL", f"{type(e).__name__}: {e}\n{traceback.format_exc(limit=3)}", time.time() - t0
