"""Capture the shared mixed/lyrics motion plan on an owned Android emulator, separately from all stills."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import base64
import hashlib
import json
import os
import subprocess
import uuid

from prepare_android_extended import verify_original_production_sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tree", choices=("baseline", "extracted"))
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    tree = root / ("iOS/build/android-baseline" if args.tree == "baseline" else "android-v2")
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = root / "iOS/tests/results/android/motion-v1" / args.tree / run_id

    def run(*command, timeout=180):
        result = subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    if not args.serial.startswith("emulator-") or run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("Motion reference capture is restricted to an owned Android emulator")
    if args.tree == "baseline":
        verify_original_production_sources()
    def text_hash(path):
        return hashlib.sha256((root / path).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
    source_identity = {"textHashEncoding": "utf8-lf",
        "referenceSourceManifestSha256": text_hash("iOS/tests/baseline/android-source-manifest.json"),
        "motionFixtureSourceSha256": text_hash("iOS/shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui/IcyMotionFixtureScreen.kt"),
        "fixtureDataSourceSha256": text_hash("iOS/shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui/IcyParityFixtures.kt")}
    asset_root = root / "iOS/shared/ui/assets"
    source_assets = {path.relative_to(asset_root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in sorted((asset_root / "font").iterdir()) if path.suffix in (".ttf", ".ttc")}
    system_fonts = {}
    for relative, expected in source_assets.items():
        actual = run("shell", "sha256sum", "/system/fonts/" + Path(relative).name).split()[0].lower()
        if actual != expected:
            raise ValueError(f"Android system font differs from the preserved source: {relative}")
        system_fonts[relative] = actual
    asset_argument = base64.b64encode(json.dumps(source_assets).encode()).decode()
    system_font_argument = base64.b64encode(json.dumps(system_fonts).encode()).decode()
    metadata = {"suite": "mixed-lyrics-motion-v1", "runId": run_id, "tree": args.tree, "serial": args.serial,
        "size": run("shell", "wm", "size").strip(), "density": run("shell", "wm", "density").strip(),
        "fontScale": run("shell", "settings", "get", "system", "font_scale").strip(),
        "device": run("shell", "getprop", "ro.build.fingerprint").strip(),
        "timezone": run("shell", "getprop", "persist.sys.timezone").strip(),
        "locale": run("shell", "getprop", "persist.sys.locale").strip(), "appearanceParityVerified": False,
        "sourceIdentity": source_identity, "sourceAssetSha256": source_assets, "androidSystemFontSha256": system_fonts}
    metadata["gles"] = next((line.strip() for line in run("shell", "dumpsys", "SurfaceFlinger").splitlines()
                             if line.startswith("GLES:")), "unavailable")
    prior = [("system", "user_rotation"), ("system", "accelerometer_rotation"), ("global", "animator_duration_scale")]
    metadata["priorSettings"] = {key: run("shell", "settings", "get", table, key).strip() for table, key in prior}
    output.mkdir(parents=True, exist_ok=False)
    for apk in [tree / "app/build/outputs/apk/play/debug/app-play-debug.apk",
                tree / "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
        metadata.setdefault("apkSha256", {})[apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(run("install", "-r", "-t", str(apk)), flush=True)
    try:
        run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        run("shell", "settings", "put", "system", "user_rotation", "1")
        run("shell", "settings", "put", "global", "animator_duration_scale", "1")
        for side in ("left", "right"):
            method = "capture" + side.capitalize()
            text = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
                f"com.icy.lyrics.parity.IcyMotionParityScreenshotTest#{method}", "-e", "motionRunId", run_id,
                *[argument for name, value in source_identity.items() for argument in ("-e", name, value)],
                "-e", "sourceAssetSha256Base64", asset_argument,
                "-e", "androidSystemFontSha256Base64", system_font_argument,
                "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner", timeout=300)
            print(text, flush=True)
            (output / f"{side}.log").write_text(text, encoding="utf-8")
            run("pull", f"/sdcard/Android/data/com.icy.lyrics/files/parity/motion-v1/{run_id}/.", str(output))
            if "OK (1 test)" not in text or "FAILURES" in text or "shortMsg=" in text:
                raise RuntimeError(f"Motion instrumentation failed; partial evidence retained: {output}")
            manifest = json.loads((output / side / "manifest.json").read_text())
            if manifest["complete"] is not True or len(manifest["frames"]) != 22:
                raise ValueError(f"Incomplete motion sequence: {side}")
            for frame in manifest["frames"]:
                png = (output / side / (frame["id"] + ".png")).read_bytes()
                if hashlib.sha256(png).hexdigest() != frame["pngSha256"]:
                    raise ValueError(f"PNG hash mismatch: {frame['id']}")
    finally:
        failures = []
        for table, key in prior:
            try:
                value = metadata["priorSettings"][key]
                run("shell", "settings", "delete", table, key) if value == "null" else run("shell", "settings", "put", table, key, value)
            except Exception as error:
                failures.append(str(error))
        metadata["restoreErrors"] = failures
        (output / "device.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        if failures:
            raise RuntimeError("Owned emulator settings restoration failed: " + "; ".join(failures))
    if args.tree == "baseline":
        verify_original_production_sources()
    print(f"Captured 44 motion reference frames: {output}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
