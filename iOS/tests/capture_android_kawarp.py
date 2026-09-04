"""Capture original Android preprocessing and eight fixed-uniform hardware Kawarp phases."""
from datetime import datetime, timezone
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import uuid
from prepare_android_extended import ROOT, BASELINE, verify_original_production_sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()
    verified = verify_original_production_sources()
    adb = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = ROOT / "iOS/tests/results/android/kawarp-gpu-phases" / run_id

    def run(*arguments):
        result = subprocess.run([str(adb), "-s", args.serial, *map(str, arguments)],
                                capture_output=True, text=True, timeout=180)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    if not args.serial.startswith("emulator-") or run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise ValueError("This hardware probe is restricted to an owned Android emulator")
    output.mkdir(parents=True, exist_ok=False)
    source_manifest = ROOT / "iOS/tests/baseline/android-source-manifest.json"
    metadata = {"schemaVersion": 1, "runId": run_id, "serial": args.serial, "complete": False,
        "originalProductionFilesVerified": verified, "apkSha256": {},
        "referenceSourceManifestSha256": hashlib.sha256(source_manifest.read_bytes()).hexdigest(),
        "buildFingerprint": run("shell", "getprop", "ro.build.fingerprint").strip(),
        "gles": [line.strip() for line in run("shell", "dumpsys", "SurfaceFlinger").splitlines() if "GLES:" in line],
        "wmSize": run("shell", "wm", "size").strip(), "wmDensity": run("shell", "wm", "density").strip(),
        "note": "Original Android test APK; no system geometry/font/rotation settings are changed by this probe."}
    for relative in ["app/build/outputs/apk/play/debug/app-play-debug.apk",
                     "app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"]:
        apk = BASELINE / relative
        metadata["apkSha256"][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(run("install", "-r", "-t", apk), flush=True)
    try:
        log = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
            "com.icy.lyrics.parity.KawarpGpuPhaseProbeTest", "-e", "kawarpProbeRunId", run_id,
            "-e", "referenceSourceManifestSha256", metadata["referenceSourceManifestSha256"],
            "com.icy.lyrics.test/androidx.test.runner.AndroidJUnitRunner")
        print(log, flush=True)
        (output / "instrumentation.log").write_text(log, encoding="utf-8")
        # Preserve preprocessing artifacts even when a later hardware assertion fails.
        pull = subprocess.run([str(adb), "-s", args.serial, "pull",
            f"/sdcard/Android/data/com.icy.lyrics/files/kawarp-gpu-phases/{run_id}/.", str(output)],
            capture_output=True, text=True, timeout=180)
        if "OK (1 test)" not in log or "FAILURES" in log or "shortMsg=" in log or pull.returncode:
            raise RuntimeError("Kawarp probe failed; partial evidence remains in " + str(output))
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        expected = {f"{w}x{h}-phase-{t}" for w, h in [(256, 512), (512, 256)] for t in (0, 1, 3, 12)}
        assert len(report["frames"]) == 8 and {f["id"] for f in report["frames"]} == expected
        assert report["referenceSourceManifestSha256"] == metadata["referenceSourceManifestSha256"]
        records = [report[k] for k in ("sourceArtwork", "resizedIntermediate", "processedArtwork")]
        for frame in report["frames"]:
            assert frame["canvasHardwareAccelerated"] and not frame["paintDither"]
            assert frame["fromShader"]["filterMode"] == frame["toShader"]["filterMode"] == 0
            records.append(frame["pixels"])
        for record in records:
            for filename, checksum in [("png", "pngSha256"), ("argbLittleEndianFile", "argbLittleEndianSha256"), ("rgbaFile", "rgbaSha256")]:
                assert hashlib.sha256((output / record[filename]).read_bytes()).hexdigest() == record[checksum]
        metadata["complete"] = True
    finally:
        metadata["originalProductionFilesVerifiedAfterRun"] = verify_original_production_sources()
        metadata["sourceSha256"] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in (
            ROOT / "iOS/tests/android/KawarpGpuPhaseProbeTest.kt", ROOT / "iOS/tests/prepare_android_kawarp.py",
            ROOT / "iOS/tests/capture_android_kawarp.py", BASELINE / "app/src/main/java/com/icy/lyrics/ui/ArtworkBackground.kt",
            BASELINE / "app/src/androidTest/java/com/icy/lyrics/parity/IcyParityFixtures.kt")}
        metadata["outputSha256"] = {f.name: hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(output.iterdir()) if f.is_file()}
        (output / "provenance.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Captured eight original Android Kawarp GPU phases and actual textures: {output}")


if __name__ == "__main__":
    main()
