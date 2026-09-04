"""Strict, unmasked, same-size RGBA comparison of real before/after screenshots."""
from pathlib import Path
from PIL import Image, ImageChops, ImageStat
import hashlib, json
root = Path(__file__).resolve().parents[2]
results = root / "iOS/tests/results/android"
baseline = results / "baseline"
extracted = results / "extracted"
expected = ["onboarding", "empty", "portrait", "portrait-long", "portrait-failed", "background-static",
    "background-disabled", "reduced-motion", "settings", "library", "library-empty", "legal", "diagnostics",
    "landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics", "landscape-mixed-right", "multilingual", "syllables"]
rows = []
for name in expected:
    before, after = baseline / (name + ".png"), extracted / (name + ".png")
    if not before.exists() or not after.exists():
        rows.append({"scenario": name, "status": "missing", "baselineExists": before.exists(), "extractedExists": after.exists()})
        continue
    a, b = Image.open(before).convert("RGBA"), Image.open(after).convert("RGBA")
    row = {"scenario": name, "baselineSizePx": list(a.size), "extractedSizePx": list(b.size),
        "baselineSha256": hashlib.sha256(before.read_bytes()).hexdigest(), "extractedSha256": hashlib.sha256(after.read_bytes()).hexdigest()}
    expected_size = (2400, 1080) if name.startswith("landscape-") or name in ("multilingual", "syllables") else (1080, 2400)
    if a.size != b.size or a.size != expected_size:
        row["status"] = "size-mismatch"
    else:
        diff = ImageChops.difference(a, b)
        changed = sum(1 for pixel in diff.getdata() if any(pixel))
        row.update(status="identical" if changed == 0 else "different", changedPixels=changed,
            totalPixels=a.width*a.height, maxChannelDelta=max(high for low, high in diff.getextrema()),
            meanAbsoluteChannelDelta=sum(ImageStat.Stat(diff).mean)/4)
        if changed:
            # The visible diagnostic uses RGB only; acceptance above compares all RGBA bytes.
            diff.convert("RGB").save(results / (name + "-diff.png"))
    rows.append(row)
manifest = json.loads((root / "iOS/tests/baseline/android-source-manifest.json").read_text(encoding="utf-8-sig"))
source_checks = []
for item in manifest["files"]:
    if "/src/main/" in item["path"]:
        p = root / "iOS/build/android-baseline" / item["path"].removeprefix("android-v2/")
        actual = hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None
        source_checks.append({"path":item["path"],"unchanged":actual==item["sha256"]})
report = {"comparison": "Exact RGBA pixels; no masks, resizing, tolerances, or accepted differences.",
    "baselineProductionSourcesUnchanged": all(x["unchanged"] for x in source_checks), "sourceChecks":source_checks,
    "scenarios":rows, "allIdentical":len(rows)==len(expected) and all(x["status"]=="identical" for x in rows)}
(results / "comparison.json").write_text(json.dumps(report, indent=2))
lines = ["# Android extraction pixel parity", "", report["comparison"], "",
    "| Scenario | Result | Changed pixels | Dimensions |", "|---|---|---:|---|"]
for row in rows:
    lines.append(f"| {row['scenario']} | {row['status']} | {row.get('changedPixels','—')} | {row.get('baselineSizePx','—')} |")
lines += ["", "Baseline production-source hashes unchanged: " + str(report["baselineProductionSourcesUnchanged"]),
    "", "These captures verify Android before/after extraction only. iOS native linking, screenshots, animation trajectories, traditional-Chinese locale fallback, complex emoji shaping, and physical-device output require separate verification."]
(results / "REPORT.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
print("\n".join(lines))
raise SystemExit(0 if report["allIdentical"] and report["baselineProductionSourcesUnchanged"] else 1)
