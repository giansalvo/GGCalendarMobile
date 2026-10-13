"""Starts and stops the test emulators, one at a time.

  python emu.py start API36Play        (stops any other emulator first; waits for the boot)
  python emu.py start API36Play --gpu host   (the PC's graphics card: faster, but it stalled the app once on 13/10/2026)
  python emu.py stop                   (stops every emulator)
  python emu.py list                   (the AVDs and the running emulators)

The default renderer is the software one (-gpu swiftshader_indirect): slower but steady. With --gpu host the emulator uses the
PC's graphics card; it fell back to software if it would not start, but on 13/10/2026 it gave one "not responding" stall of the
app (the graphics services of the emulator at 40-50% CPU), so it is an option, not the default.
The animations are switched off by run.py, which also speeds up the UI.
"""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adbx import ADB, SDK  # noqa: E402

EMULATOR = os.path.join(SDK, "emulator", "emulator.exe" if os.name == "nt" else "emulator")
PORT = 5556


def adb(*args, serial=None, timeout=60):
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, timeout=timeout).stdout.decode("utf-8", "replace")


def running():
    out = adb("devices")
    return [l.split()[0] for l in out.splitlines()[1:] if l.startswith("emulator-")]


def stop_all():
    for serial in running():
        adb("emu", "kill", serial=serial)
    for _ in range(30):
        if not running():
            return
        time.sleep(1)


def booted(serial):
    return adb("shell", "getprop", "sys.boot_completed", serial=serial).strip() == "1"


def start(avd, gpu="swiftshader_indirect", wait=300):
    stop_all()
    time.sleep(2)
    serial = f"emulator-{PORT}"
    log = open(os.path.join(os.environ.get("TEMP", "."), f"emu_{avd}.log"), "wb")
    for mode in ([gpu, "swiftshader_indirect"] if gpu != "swiftshader_indirect" else [gpu]):
        proc = subprocess.Popen([EMULATOR, "-avd", avd, "-port", str(PORT), "-no-snapshot", "-no-boot-anim", "-gpu", mode],
                                stdout=log, stderr=log)
        end = time.time() + wait
        while time.time() < end:
            if proc.poll() is not None:
                break  # the emulator quit: try the next renderer
            if serial in running() and booted(serial):
                time.sleep(5)
                print(f"{avd} is up on {serial} (gpu {mode})")
                return serial
            time.sleep(3)
        if proc.poll() is None:
            adb("emu", "kill", serial=serial)
        print(f"{avd} did not start with gpu {mode}")
    raise SystemExit(f"{avd} could not be started")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "list"
    if cmd == "start":
        gpu = sys.argv[sys.argv.index("--gpu") + 1] if "--gpu" in sys.argv else "swiftshader_indirect"
        start(sys.argv[2], gpu=gpu)
    elif cmd == "stop":
        stop_all()
        print("stopped")
    else:
        print(subprocess.run([EMULATOR, "-list-avds"], capture_output=True).stdout.decode())
        print("running:", running())


if __name__ == "__main__":
    main()
