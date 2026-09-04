"""Capture real Android screenshots using production Compose and shared offline fixtures."""
from pathlib import Path
import argparse, json, os, subprocess
p = argparse.ArgumentParser()
p.add_argument("tree", choices=["baseline", "extracted"])
p.add_argument("--serial", required=True)
p.add_argument("--orientation", choices=["portrait", "landscape"], required=True)
args = p.parse_args()
root = Path(__file__).resolve().parents[2]
tree = root / ("iOS/build/android-baseline" if args.tree == "baseline" else "android-v2")
adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
result = root / "iOS/tests/results/android" / args.tree
result.mkdir(parents=True, exist_ok=True)
def run(*cmd):
    r = subprocess.run([str(adb), "-s", args.serial, *cmd], capture_output=True, text=True)
    if r.returncode: raise RuntimeError(r.stdout + r.stderr)
    return r.stdout
metadata = {"serial": args.serial, "tree": args.tree, "size": run("shell", "wm", "size").strip(),
    "density": run("shell", "wm", "density").strip(), "device": run("shell", "getprop", "ro.build.fingerprint").strip(),
    "fontScale": run("shell", "settings", "get", "system", "font_scale").strip(),
    "timezone": run("shell", "getprop", "persist.sys.timezone").strip()}
(result / (args.orientation + "-device.json")).write_text(json.dumps(metadata, indent=2))
for apk in [tree / "app/build/outputs/apk/play/debug/app-play-debug.apk",
            tree / "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
    print(run("install", "-r", "-t", str(apk)), flush=True)
rotation = run("shell", "settings", "get", "system", "user_rotation").strip()
auto = run("shell", "settings", "get", "system", "accelerometer_rotation").strip()
scale = run("shell", "settings", "get", "global", "animator_duration_scale").strip()
try:
    run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    for landscape in [args.orientation == "landscape"]:
        # Boot dedicated AVDs at each size; dynamic resizing crashes this Windows emulator.
        run("shell", "settings", "put", "system", "user_rotation", "0")
        output = run("shell", "am", "instrument", "-w", "-r", "-e", "class", "com.icy.lyrics.parity.IcyParityScreenshotTest",
            "-e", "landscape", str(landscape).lower(), "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner")
        (result / ("landscape.log" if landscape else "portrait.log")).write_text(output)
        print(output, flush=True)
        print(run("pull", "/sdcard/Android/data/com.icy.lyrics/files/parity/.", str(result)), flush=True)
        if "FAILURES" in output or "shortMsg=" in output or "OK (1 test)" not in output:
            raise RuntimeError("Screenshot instrumentation failed; see saved log")
    print(run("pull", "/sdcard/Android/data/com.icy.lyrics/files/parity/.", str(result)), flush=True)
finally:
    try:
        run("shell", "settings", "put", "system", "user_rotation", rotation)
        run("shell", "settings", "put", "system", "accelerometer_rotation", auto)
        run("shell", "settings", "put", "global", "animator_duration_scale", scale)
    except RuntimeError as error:
        print("Emulator disconnected while restoring settings:", error, flush=True)
(result / (args.orientation + "-device.json")).write_text(json.dumps(metadata, indent=2))
