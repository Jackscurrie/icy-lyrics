"""Derive compact iOS date profiles from the preserved Android 36 measurement archive."""
from pathlib import Path
import hashlib
import json
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    archive = root / "tests/evidence/android36-date-formats.zip"
    manifest = json.loads(archive.with_suffix(".json").read_text(encoding="utf-8"))
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["archiveSha256"]
    with zipfile.ZipFile(archive) as source:
        raw = source.read("android36-date-formats/patterns-and-symbols.json")
        inventory = json.loads(raw)
        samples = json.loads(source.read("android36-date-formats/samples.json"))
    records, index, keys = [], {}, {}
    for locale in inventory["locales"]:
        assert locale["calendarType"] == "gregory"
        assert locale["combinedPattern"] == locale["datePattern"] + " " + locale["timePattern"]
        record = {"pattern": locale["combinedPattern"], "shortMonths": locale["shortMonths"][:12],
                  "amPm": locale["amPmStrings"], "zeroDigit": locale["zeroDigit"]}
        key = json.dumps(record, ensure_ascii=False, sort_keys=True)
        if key not in keys:
            keys[key] = len(records)
            records.append(record)
        index[locale["locale"]] = keys[key]
    # Both aliases were explicitly sampled through Android's no-locale production overload.
    # Do not invent script inference for unmeasured language/region combinations.
    aliases = {"zh-CN": "zh-Hans-CN", "zh-TW": "zh-Hant-TW"}
    for alias, target in aliases.items():
        assert all(s["pattern"] == records[index[target]]["pattern"]
                   for s in samples["samples"] if s["locale"] == alias)
    output = {"schemaVersion": 1, "calendar": "gregory", "sdk": inventory["sdk"],
              "icuVersion": inventory["icuVersion"], "cldrVersion": inventory["cldrVersion"],
              "sourceInventorySha256": hashlib.sha256(raw).hexdigest(), "aliases": aliases,
              "localeProfiles": index, "profiles": records}
    destination = root / "shared/ui/assets/date/android36-medium-short.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    fixture = {"schemaVersion": 1, "sourceArchiveSha256": manifest["archiveSha256"], "samples": [
        {key: s[key] for key in ("locale", "timezone", "epochMs", "combined")} for s in samples["samples"]]}
    sample_path = root / "tests/fixtures/android36-date-samples.json"
    sample_path.parent.mkdir(parents=True, exist_ok=True)
    sample_path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Generated {len(index)} locale mappings, {len(records)} profiles, and {len(fixture['samples'])} observed samples")


if __name__ == "__main__":
    main()
