"""Capture the stock Android Compose text shadow diagnostic on an owned emulator."""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = root / "iOS/tests/results/android/shadow-radius" / run_id

    def run(*arguments):
        result = subprocess.run([str(adb), "-s", args.serial, *map(str, arguments)],
                                capture_output=True, text=True, timeout=180)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    if not args.serial.startswith("emulator-") or run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("This probe is restricted to an owned Android emulator")
    output.mkdir(parents=True, exist_ok=False)
    metadata = {"schemaVersion": 1, "runId": run_id, "serial": args.serial,
                "buildFingerprint": run("shell", "getprop", "ro.build.fingerprint").strip(),
                "apkSha256": {}, "complete": False}
    for relative in ["app/build/outputs/apk/play/debug/app-play-debug.apk",
                     "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
        apk = root / "android-v2" / relative
        metadata["apkSha256"][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(run("install", "-r", "-t", apk), flush=True)
    log = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
              "com.icy.lyrics.parity.TextShadowParityProbeTest", "-e", "shadowProbeRunId", run_id,
              "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner")
    print(log, flush=True)
    (output / "instrumentation.log").write_text(log, encoding="utf-8")
    run("pull", f"/sdcard/Android/data/com.icy.lyrics/files/shadow-radius/{run_id}/.", output)
    if "OK (1 test)" not in log or "FAILURES" in log or "shortMsg=" in log:
        (output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
        raise RuntimeError(f"Shadow probe failed; evidence preserved at {output}")
    report = json.loads((output / "report.json").read_text(encoding="utf-8"))
    assert len(report["samples"]) == 8
    assert report["density"] == 2.625 and report["fontScale"] == 1
    assert {(x["authoredRadiusPx"], x["placement"]) for x in report["samples"]} == {
        (radius, placement) for radius in [0, 4, 15, 30] for placement in ["base", "span"]}
    for sample in report["samples"]:
        assert hashlib.sha256((output / sample["png"]).read_bytes()).hexdigest() == sample["pngSha256"]
    metadata["complete"] = True
    metadata["outputSha256"] = {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(output.iterdir())}
    metadata["sourceSha256"] = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in [
        "iOS/tests/android/TextShadowParityProbeTest.kt", "iOS/tests/capture_android_shadows.py"]}
    (output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Captured eight stock Android shadow probes: {output}")


if __name__ == "__main__":
    main()
