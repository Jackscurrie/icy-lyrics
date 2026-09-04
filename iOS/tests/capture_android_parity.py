"""Capture real Android screenshots using production Compose and shared offline fixtures."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess
from android_viewport_profile import (load_profile, instrumentation_arguments, native_result_directory,
    prior_wm_override, validate_capture, wm_geometry)
p = argparse.ArgumentParser()
p.add_argument("tree", choices=["baseline", "extracted"])
p.add_argument("--serial", required=True)
p.add_argument("--orientation", choices=["portrait", "landscape"], required=True)
p.add_argument("--scenario", help="Capture one fixture before running the complete orientation group")
p.add_argument("--rotation", choices=[0, 1, 2, 3], type=int, default=0,
    help="Android user_rotation for the owned AVD; 0 retains its natural orientation")
p.add_argument("--viewport-profile", type=Path,
    help="Measured native viewport JSON; captures separately and never replaces the original20 baselines")
args = p.parse_args()
root = Path(__file__).resolve().parents[2]
profile = None
if args.viewport_profile:
    try: profile = load_profile(args.viewport_profile)
    except (ValueError, OSError) as error: p.error(str(error))
    if profile["orientation"] != args.orientation: p.error("Profile orientation must match --orientation")
    if args.scenario and args.scenario != profile["scenario"]: p.error("Profile scenario must match --scenario")
    args.scenario = profile["scenario"]
tree = root / ("iOS/build/android-baseline" if args.tree == "baseline" else "android-v2")
adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
result = (native_result_directory(root, profile, args.tree) if profile else root / "iOS/tests/results/android" / args.tree)
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
if profile:
    if run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Native viewport configuration is restricted to an owned Android emulator")
    metadata["nativeViewportProfile"] = profile
    metadata["viewportProfileSha256"] = hashlib.sha256(args.viewport_profile.read_bytes()).hexdigest()
    metadata["appearanceParityVerified"] = False
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
    if profile:
        wm_size, wm_density = wm_geometry(profile, args.rotation)
        run("shell", "wm", "size", wm_size)
        run("shell", "wm", "density", wm_density)
        run("shell", "settings", "put", "system", "font_scale", str(profile["fontScale"]))
    run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    for landscape in [args.orientation == "landscape"]:
        # Prefer an AVD with the requested natural geometry; the harness verifies actual dimensions.
        run("shell", "settings", "put", "system", "user_rotation", str(args.rotation))
        output = run("shell", "am", "instrument", "-w", "-r", "-e", "class", "com.icy.lyrics.parity.IcyParityScreenshotTest",
            "-e", "landscape", str(landscape).lower(), *(["-e", "scenario", args.scenario] if args.scenario else []),
            *(instrumentation_arguments(profile) if profile else []),
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
            if profile:
                capture = json.loads((result / f"{name}.json").read_text())
                validate_capture(profile, capture, (result / f"{name}.png").read_bytes())
                metadata["effectiveCompose"] = capture
                metadata["fontScalingComparison"] = {
                    "status": "pending native sp-to-px observations; equal fontScale alone is insufficient",
                    "largeTextComparisonReady": False if profile["fontScale"] != 1 else None,
                    "androidSpToPx": capture["spToPx"], "native": profile.get("nativeFontScaling"),
                }
                (result / f"{name}-viewport-profile.json").write_bytes(args.viewport_profile.read_bytes())
        (result / ((args.scenario or args.orientation) + ".log")).write_text(output)
finally:
    restore = [("shell", "settings", "put", "system", "user_rotation", rotation),
        ("shell", "settings", "put", "system", "accelerometer_rotation", auto),
        ("shell", "settings", "put", "global", "animator_duration_scale", scale)]
    if profile:
        restore += [("shell", "wm", "size", prior_wm_override(metadata["size"], "size")),
            ("shell", "wm", "density", prior_wm_override(metadata["density"], "density")),
            (("shell", "settings", "delete", "system", "font_scale") if metadata["fontScale"] == "null" else
             ("shell", "settings", "put", "system", "font_scale", metadata["fontScale"]))]
    restore_errors = []
    for command in restore:
        try: run(*command)
        except (RuntimeError, subprocess.TimeoutExpired) as error: restore_errors.append(str(error))
    metadata["restoreErrors"] = restore_errors
    (result / ((args.scenario or args.orientation) + "-device.json")).write_text(json.dumps(metadata, indent=2))
    if restore_errors:
        raise RuntimeError("Emulator settings restoration failed: " + "; ".join(restore_errors))
(result / ((args.scenario or args.orientation) + "-device.json")).write_text(json.dumps(metadata, indent=2))
