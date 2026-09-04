"""Measure Android's original java.text date API on an explicitly owned emulator."""
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
    output = root / "iOS/tests/results/android/date-formats" / run_id

    def run(*arguments):
        result = subprocess.run([str(adb), "-s", args.serial, *map(str, arguments)],
                                capture_output=True, text=True, timeout=180)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    if not args.serial.startswith("emulator-") or run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("The date-format probe is restricted to an owned Android emulator")
    output.mkdir(parents=True, exist_ok=False)
    metadata = {"schemaVersion": 1, "runId": run_id, "serial": args.serial,
                "buildFingerprint": run("shell", "getprop", "ro.build.fingerprint").strip(),
                "description": "Actual Android java.text MEDIUM/SHORT patterns and symbols; no UI or OS settings changed.",
                "apkSha256": {}, "complete": False}
    for relative in ["app/build/outputs/apk/play/debug/app-play-debug.apk",
                     "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
        apk = root / "android-v2" / relative
        metadata["apkSha256"][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(run("install", "-r", "-t", apk), flush=True)
    log = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
              "com.icy.lyrics.parity.DateFormatParityProbeTest", "-e", "dateProbeRunId", run_id,
              "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner")
    print(log, flush=True)
    (output / "instrumentation.log").write_text(log, encoding="utf-8")
    run("pull", f"/sdcard/Android/data/com.icy.lyrics/files/date-format/{run_id}/.", output)
    if "OK (1 test)" not in log or "FAILURES" in log or "shortMsg=" in log:
        (output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
        raise RuntimeError(f"Date-format probe failed; evidence preserved at {output}")
    inventory = json.loads((output / "patterns-and-symbols.json").read_text(encoding="utf-8"))
    samples = json.loads((output / "samples.json").read_text(encoding="utf-8"))
    assert inventory["localeCount"] == len(inventory["locales"]) > 100
    assert len(samples["samples"]) == 144
    assert samples["productionApiVerifiedForEverySample"] and samples["globalDefaultsRestored"]
    metadata["complete"] = True
    metadata["outputSha256"] = {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                                for name in ["patterns-and-symbols.json", "samples.json", "instrumentation.log"]}
    metadata["sourceSha256"] = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in [
        "iOS/tests/android/DateFormatParityProbeTest.kt", "iOS/tests/capture_android_date_formats.py"]}
    (output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Captured {inventory['localeCount']} Android locale records and 144 samples: {output}")


if __name__ == "__main__":
    main()
