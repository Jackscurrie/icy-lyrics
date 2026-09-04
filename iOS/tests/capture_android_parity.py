"""Capture real Android screenshots using production Compose and shared offline fixtures."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess
p = argparse.ArgumentParser()
p.add_argument("tree", choices=["baseline", "extracted"])
p.add_argument("--serial", required=True)
p.add_argument("--orientation", choices=["portrait", "landscape"], required=True)
p.add_argument("--scenario", help="Capture one fixture before running the complete orientation group")
p.add_argument("--rotation", choices=[0, 1, 2, 3], type=int, default=0,
    help="Android user_rotation for the owned AVD; 0 retains its natural orientation")
args = p.parse_args()
root = Path(__file__).resolve().parents[2]
tree = root / ("iOS/build/android-baseline" if args.tree == "baseline" else "android-v2")
adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
result = root / "iOS/tests/results/android" / args.tree
result.mkdir(parents=True, exist_ok=True)
def run(*cmd):
    r = subprocess.run([str(adb), "-s", args.serial, *cmd], capture_output=True, text=True, timeout=180)
    if r.returncode: raise RuntimeError(r.stdout + r.stderr)
    return r.stdout
metadata = {"serial": args.serial, "tree": args.tree, "size": run("shell", "wm", "size").strip(),
    "density": run("shell", "wm", "density").strip(), "device": run("shell", "getprop", "ro.build.fingerprint").strip(),
    "fontScale": run("shell", "settings", "get", "system", "font_scale").strip(),
    "timezone": run("shell", "getprop", "persist.sys.timezone").strip(),
    "requestedRotation": args.rotation, "orientation": args.orientation}
metadata["gles"] = next((line.strip() for line in run("shell", "dumpsys", "SurfaceFlinger").splitlines()
    if line.startswith("GLES:")), "unavailable")
portrait_ids = ["onboarding", "empty", "portrait", "portrait-long", "portrait-failed", "background-static",
    "background-disabled", "reduced-motion", "settings", "library", "library-empty", "legal", "diagnostics"]
landscape_ids = ["landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics",
    "landscape-mixed-right", "multilingual", "syllables"]
ids = landscape_ids if args.orientation == "landscape" else portrait_ids
if args.scenario:
    if args.scenario not in ids: p.error("Scenario must belong to the selected orientation")
    ids = [args.scenario]
for apk in [tree / "app/build/outputs/apk/play/debug/app-play-debug.apk",
            tree / "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
    metadata.setdefault("apkSha256", {})[apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
    print(run("install", "-r", "-t", str(apk)), flush=True)
rotation = run("shell", "settings", "get", "system", "user_rotation").strip()
auto = run("shell", "settings", "get", "system", "accelerometer_rotation").strip()
scale = run("shell", "settings", "get", "global", "animator_duration_scale").strip()
try:
    run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    for landscape in [args.orientation == "landscape"]:
        # Prefer an AVD with the requested natural geometry; the harness verifies actual dimensions.
        run("shell", "settings", "put", "system", "user_rotation", str(args.rotation))
        output = run("shell", "am", "instrument", "-w", "-r", "-e", "class", "com.icy.lyrics.parity.IcyParityScreenshotTest",
            "-e", "landscape", str(landscape).lower(), *(["-e", "scenario", args.scenario] if args.scenario else []),
            "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner")
        print(output, flush=True)
        if "FAILURES" in output or "shortMsg=" in output or "OK (1 test)" not in output:
            failures = result / "failed-attempts"
            failures.mkdir(exist_ok=True)
            attempt = args.scenario or args.orientation
            (failures / (attempt + ".log")).write_text(output)
            (failures / (attempt + "-device.json")).write_text(json.dumps(metadata, indent=2))
            raise RuntimeError("Screenshot instrumentation failed; see saved log")
        # Pull only this run's requested fixtures, preserving existing references from other runs.
        for name in ids:
            for extension in ["png", "json"]:
                print(run("pull", f"/sdcard/Android/data/com.icy.lyrics/files/parity/{name}.{extension}",
                    str(result / f"{name}.{extension}")), flush=True)
        (result / ((args.scenario or args.orientation) + ".log")).write_text(output)
finally:
    try:
        run("shell", "settings", "put", "system", "user_rotation", rotation)
        run("shell", "settings", "put", "system", "accelerometer_rotation", auto)
        run("shell", "settings", "put", "global", "animator_duration_scale", scale)
    except RuntimeError as error:
        print("Emulator disconnected while restoring settings:", error, flush=True)
(result / ((args.scenario or args.orientation) + "-device.json")).write_text(json.dumps(metadata, indent=2))
