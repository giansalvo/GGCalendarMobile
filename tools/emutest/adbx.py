"""Thin adb + UI Automator layer used by the emulator tests (see app_planning/test_emulator_e_device.md).

Everything goes through adb without root, so it also works on images with the Play Store.
Controls are found by their `testTag` (exposed as resource id), or by text / content description, where text can be
a regular expression built from the app's own strings (see strings.py), so the same test runs in English and Italian.
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SDK = os.environ.get("ANDROID_SDK_ROOT") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
PKG = "io.github.giansalvo.ggcalendar"


class Node:
    def __init__(self, el):
        a = el.attrib
        self.text = a.get("text", "")
        self.desc = a.get("content-desc", "")
        self.rid = a.get("resource-id", "")
        self.cls = a.get("class", "")
        self.pkg = a.get("package", "")
        self.clickable = a.get("clickable") == "true"
        self.enabled = a.get("enabled") == "true"
        self.checked = a.get("checked") == "true"
        self.selected = a.get("selected") == "true"
        self.focused = a.get("focused") == "true"
        m = re.findall(r"-?\d+", a.get("bounds", "[0,0][0,0]"))
        self.l, self.t, self.r, self.b = (int(x) for x in m[:4])

    @property
    def center(self):
        return (self.l + self.r) // 2, (self.t + self.b) // 2

    @property
    def width(self):
        return self.r - self.l

    @property
    def height(self):
        return self.b - self.t

    def __repr__(self):
        return f"<{self.rid or self.cls} text={self.text!r} desc={self.desc!r} [{self.l},{self.t},{self.r},{self.b}]>"


def _match(value, pattern):
    if pattern is None:
        return True
    if hasattr(pattern, "search"):
        return bool(pattern.search(value))
    return value.strip().lower() == str(pattern).strip().lower()


class Device:
    def __init__(self, serial, outdir):
        self.serial = serial
        self.out = outdir
        os.makedirs(outdir, exist_ok=True)
        self._api = None
        self._shots = 0

    # ---- adb
    def adb(self, *args, timeout=90, binary=False):
        cmd = [ADB, "-s", self.serial, *args]
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode("utf-8", errors="replace")

    def shell(self, command, timeout=90):
        return self.adb("shell", command, timeout=timeout)

    @property
    def api(self):
        if self._api is None:
            self._api = int(self.shell("getprop ro.build.version.sdk").strip())
        return self._api

    @property
    def screen_h(self):
        """Height in pixels of the screen (the size override of `wm size` wins over the physical one)."""
        if getattr(self, "_screen_h", None) is None:
            out = self.shell("wm size")
            m = re.findall(r"(\d+)x(\d+)", out)
            self._screen_h = int(m[-1][1]) if m else 2200
        return self._screen_h

    @property
    def usable_h(self):
        """Bottom edge of the app area: a navigation bar (3 buttons) takes its height off the screen."""
        if getattr(self, "_usable_h", None) is None:
            bar = 0
            try:
                m = re.search(r"type=navigationBars.*?insetsSize=Insets\{[^}]*bottom=(\d+)", self.shell("dumpsys window", timeout=60))
                bar = int(m.group(1)) if m else 0
            except Exception:
                pass
            self._usable_h = self.screen_h - bar
        return self._usable_h

    def animations(self, on):
        """Android's window, transition and animator animations: off makes every UI step faster and steadier."""
        v = "1.0" if on else "0.0"
        for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.shell(f"settings put global {key} {v}")

    ANIMATION_KEYS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    def animation_scales(self):
        """The three animation settings as they are now; a value that was never set is None (the phone's animator scale)."""
        saved = {}
        for key in self.ANIMATION_KEYS:
            v = self.shell(f"settings get global {key}").strip()
            saved[key] = None if v in ("", "null") else v
        return saved

    def restore_animations(self, saved):
        """Puts the settings back as animation_scales() read them, deleting the ones that were not set."""
        for key, v in saved.items():
            self.shell(f"settings delete global {key}" if v is None else f"settings put global {key} {v}")

    def prop(self, name):
        return self.shell(f"getprop {name}").strip()

    def booted(self):
        return self.shell("getprop sys.boot_completed").strip() == "1"

    def wait_boot(self, timeout=240):
        end = time.time() + timeout
        while time.time() < end:
            try:
                if self.booted():
                    time.sleep(3)
                    return True
            except Exception:
                pass
            time.sleep(3)
        return False

    # ---- screen and input
    def wake(self):
        self.shell("input keyevent 224")
        self.shell("wm dismiss-keyguard")

    def sleep_screen(self):
        self.shell("input keyevent 223")

    def screen_on(self):
        return "mWakefulness=Awake" in self.shell("dumpsys power | grep mWakefulness")

    def tap(self, x, y):
        self.shell(f"input tap {x} {y}")

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {ms}")

    def key(self, *codes):
        self.shell("input keyevent " + " ".join(str(c) for c in codes))

    def back(self):
        self.key(4)

    def text(self, s):
        """Types plain text (spaces as %s); the characters % and & are not supported by `input text`."""
        safe = s.replace("\\", "\\\\").replace("'", "'\\''").replace(" ", "%s")
        self.shell(f"input text '{safe}'")

    def clear_field(self):
        self.key(123)  # MOVE_END
        self.shell("input keyevent " + " ".join(["67"] * 70))

    def shot(self, name):
        self._shots += 1
        path = os.path.join(self.out, f"{self._shots:03d}_{re.sub(r'[^A-Za-z0-9_-]+', '_', name)}.png")
        with open(path, "wb") as f:
            f.write(self.adb("exec-out", "screencap", "-p", binary=True))
        return path

    # ---- UI tree
    def dump(self, retries=4):
        last = None
        for _ in range(retries):
            raw = self.adb("exec-out", "uiautomator dump /dev/tty", timeout=60)
            i = raw.find("<?xml")
            j = raw.rfind("</hierarchy>")
            if i >= 0 and j > i:
                try:
                    root = ET.fromstring(raw[i:j + len("</hierarchy>")])
                    return [Node(e) for e in root.iter("node")]
                except ET.ParseError as e:
                    last = e
            time.sleep(0.7)
        raise RuntimeError(f"cannot dump the UI: {last}")

    def find(self, nodes=None, tag=None, text=None, desc=None, either=None, pkg=None, clickable=None):
        """Nodes matching all the given criteria. `either` matches the text or the content description."""
        nodes = self.dump() if nodes is None else nodes
        out = []
        for n in nodes:
            if tag is not None and n.rid != tag:
                continue
            if text is not None and not _match(n.text, text):
                continue
            if desc is not None and not _match(n.desc, desc):
                continue
            if either is not None and not (_match(n.text, either) or _match(n.desc, either)):
                continue
            if pkg is not None and n.pkg != pkg:
                continue
            if clickable is not None and n.clickable != clickable:
                continue
            out.append(n)
        return out

    def wait(self, timeout=15, interval=0.8, **crit):
        """The first node matching the criteria, polling the UI; None on timeout."""
        end = time.time() + timeout
        while True:
            try:
                found = self.find(**crit)
            except Exception:
                found = []
            if found:
                return found[0]
            if time.time() >= end:
                return None
            time.sleep(interval)

    def gone(self, timeout=10, interval=0.8, **crit):
        end = time.time() + timeout
        while True:
            try:
                found = self.find(**crit)
            except Exception:
                found = [1]
            if not found:
                return True
            if time.time() >= end:
                return False
            time.sleep(interval)

    def click(self, timeout=12, **crit):
        n = self.wait(timeout=timeout, **crit)
        if n is None:
            raise LookupError(f"not found: {crit}")
        self.tap(*n.center)
        return n

    # ---- app and system state
    def pm_has(self, pkg=PKG):
        return pkg in self.shell(f"pm list packages {pkg}")

    def version_name(self):
        m = re.search(r"versionName=(\S+)", self.shell(f"dumpsys package {PKG} | grep -m1 versionName"))
        return m.group(1) if m else None

    def top_activity(self):
        out = self.shell("dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity' | head -3")
        return out.strip()

    def pid(self):
        return self.shell(f"pidof {PKG}").strip()

    def logcat_clear(self):
        self.adb("logcat", "-c")

    def logcat(self, lines=None):
        out = self.adb("logcat", "-d", "-v", "time", timeout=120)
        return out if lines is None else "\n".join(out.splitlines()[-lines:])

    def problems(self, text=None):
        """Crashes and ANRs of the app in the log: the lines that must never appear."""
        text = self.logcat() if text is None else text
        bad = []
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if "FATAL EXCEPTION" in line and "SYSTEM PROCESS" not in line:
                blob = "\n".join(lines[i:i + 6])
                if PKG in blob:
                    bad.append(blob)
            elif f"ANR in {PKG}" in line:
                bad.append(line)
            elif "WM-WorkerWrapper" in line and "failed because it threw an exception" in line and PKG in line:
                bad.append(line)
        return bad

    # Offline = airplane mode, switched from the quick-settings tile like a person would: `svc wifi disable` has no
    # effect on the emulator's virtual Wi-Fi, a proxy does not stop the app, and the airplane broadcast needs root.
    def airplane_on(self):
        return self.shell("settings get global airplane_mode_on").strip() == "1"

    def network(self, on):
        want_airplane = not on
        if self.airplane_on() == want_airplane:
            return
        if self.api >= 29:  # Android 10 and later have a command for it
            self.shell(f"cmd connectivity airplane-mode {'disable' if on else 'enable'}")
            time.sleep(3.0)
            if on:
                end = time.time() + 60
                while time.time() < end:
                    if self.shell("dumpsys connectivity | grep -c VALIDATED").strip() not in ("", "0"):
                        break
                    time.sleep(3)
            return
        self.shell("cmd statusbar expand-settings")
        time.sleep(2.0)
        tile = None
        end = time.time() + 10
        while time.time() < end and tile is None:
            # the panel header also reads "Airplane mode": the tile is the match lower on the screen
            hits = [n for n in self.find(either=re.compile(r"Airplane|aereo|Flight", re.I)) if 300 < n.center[1] < 1400]
            tile = min(hits, key=lambda n: n.center[1]) if hits else None
            if tile is None:
                time.sleep(0.8)
        if tile is None:
            self.shell("cmd statusbar collapse")
            raise RuntimeError("no airplane mode tile in the quick settings")
        self.tap(*tile.center)
        time.sleep(3.0)
        self.shell("cmd statusbar collapse")
        time.sleep(1.0)
        if on:  # wait for the connection to come back
            end = time.time() + 60
            while time.time() < end:
                if self.shell("dumpsys connectivity | grep -c VALIDATED").strip() not in ("", "0"):
                    break
                time.sleep(3)

    def has_network(self):
        return not self.airplane_on()
