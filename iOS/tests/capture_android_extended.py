"""Explicit extended-v1 captures. Never writes to original twenty-fixture evidence directories."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
import subprocess
import uuid

from prepare_android_extended import verify_original_production_sources

CASE_IDS = ["portrait-expanded", "settings-fullscreen", "settings-sources", "settings-troubleshooting",
            "settings-privacy", "token-consent", "legal-lower", "legal-agpl", "legal-agpl-scrolled",
            "legal-third-party", "legal-third-party-scrolled"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tree", choices=("baseline", "extracted"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--case", choices=CASE_IDS)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    tree = root / ("iOS/build/android-baseline" if args.tree == "baseline" else "android-v2")
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = root / "iOS/tests/results/android/extended-v1" / args.tree / run_id

    def run(*command, timeout=180):
        result = subprocess.run([str(adb), "-s", args.serial, *command], capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    if not args.serial.startswith("emulator-") or run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("Extended reference capture is restricted to an owned Android emulator")
    if args.tree == "baseline":
        verify_original_production_sources()
    metadata = {"suite": "extended-v1", "runId": run_id, "tree": args.tree, "serial": args.serial,
        "size": run("shell", "wm", "size").strip(), "density": run("shell", "wm", "density").strip(),
        "fontScale": run("shell", "settings", "get", "system", "font_scale").strip(),
        "rotation": run("shell", "settings", "get", "system", "user_rotation").strip(),
        "device": run("shell", "getprop", "ro.build.fingerprint").strip(),
        "timezone": run("shell", "getprop", "persist.sys.timezone").strip(),
        "locale": run("shell", "getprop", "persist.sys.locale").strip(),
        "caseOrder": [args.case] if args.case else CASE_IDS, "appearanceParityVerified": False}
    metadata["gles"] = next((line.strip() for line in run("shell", "dumpsys", "SurfaceFlinger").splitlines()
                             if line.startswith("GLES:")), "unavailable")
    output.mkdir(parents=True, exist_ok=False)
    for apk in [tree / "app/build/outputs/apk/play/debug/app-play-debug.apk",
                tree / "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
        metadata.setdefault("apkSha256", {})[apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(run("install", "-r", "-t", str(apk)), flush=True)
    (output / "device.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    text = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
        "com.icy.lyrics.parity.IcyExtendedParityScreenshotTest", "-e", "extendedRunId", run_id,
        *(["-e", "extendedCase", args.case] if args.case else []),
        "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner", timeout=300)
    print(text, flush=True)
    (output / "instrumentation.log").write_text(text, encoding="utf-8")
    # Preserve partial output on failures, with the manifest's complete=false intact.
    run("pull", f"/sdcard/Android/data/com.icy.lyrics/files/parity/extended-v1/{run_id}/.", str(output))
    if "OK (1 test)" not in text or "FAILURES" in text or "shortMsg=" in text:
        raise RuntimeError(f"Extended instrumentation failed; preserved evidence: {output}")
    manifest = json.loads((output / "manifest.json").read_text())
    if manifest["complete"] is not True or manifest["caseOrder"] != metadata["caseOrder"]:
        raise ValueError("Extended capture manifest is incomplete or has an unexpected order")
    for case in metadata["caseOrder"]:
        capture = json.loads((output / f"{case}.json").read_text())
        png = (output / f"{case}.png").read_bytes()
        if hashlib.sha256(png).hexdigest() != capture["pngSha256"]:
            raise ValueError(f"PNG hash mismatch: {case}")
        if capture["dialogExpected"] and capture["dialogRootCount"] < 1:
            raise ValueError(f"Dialog missing from captured state: {case}")
    if args.tree == "baseline":
        verify_original_production_sources()
    print(f"Captured {len(metadata['caseOrder'])} extended references: {output}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
