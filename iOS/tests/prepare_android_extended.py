"""Install only the opt-in extended instrumentation adapter in the frozen reference checkout."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "iOS/build/android-baseline"


def verify_original_production_sources():
    manifest = json.loads((ROOT / "iOS/tests/baseline/android-source-manifest.json").read_text(encoding="utf-8"))
    verified = []
    for record in manifest["files"]:
        relative = Path(record["path"]).relative_to("android-v2")
        if "/src/main/" not in "/" + relative.as_posix():
            continue
        source = BASELINE / relative
        actual = hashlib.sha256(source.read_bytes()).hexdigest()
        if actual != record["sha256"]:
            raise ValueError(f"Original production source changed: {relative}")
        verified.append(str(relative))
    if not verified:
        raise ValueError("No original production files were verified")
    return verified


def prepare():
    verified = verify_original_production_sources()
    directory = BASELINE / "app/src/androidTest/java/com/icy/lyrics/parity"
    for required in ("IcyParityFixtures.kt", "IcyParityFixtureScreen.kt"):
        if not (directory / required).is_file():
            raise ValueError("Prepare the original frozen baseline test adapters first")
    source = (ROOT / "iOS/tests/android/IcyExtendedParityScreenshotTest.kt").read_text(encoding="utf-8")
    original = '''  val platform = rememberAndroidIcyUiPlatform()
  CompositionLocalProvider(LocalIcyUiPlatform provides platform) { IcyParityFixtureScreen(baseId) }'''
    if source.count(original) != 1:
        raise ValueError("Extended fixture host changed; review the frozen-baseline test adapter")
    source = source.replace(original, "  IcyParityFixtureScreen(baseId)")
    source = source.replace("import com.icy.lyrics.ui.LocalIcyUiPlatform\n", "")
    source = source.replace("import com.icy.lyrics.ui.rememberAndroidIcyUiPlatform\n", "")
    (directory / "IcyExtendedParityScreenshotTest.kt").write_text(source, encoding="utf-8")
    helper = ROOT / "iOS/tests/android/NativeViewportProfile.kt"
    destination = directory / helper.name
    if destination.exists() and destination.read_bytes() != helper.read_bytes():
        raise ValueError("Existing baseline metrics adapter differs; review before replacing it")
    if not destination.exists():
        destination.write_bytes(helper.read_bytes())
    verify_original_production_sources()
    print(f"Prepared extended test adapter only; {len(verified)} frozen production files match original hashes.")


if __name__ == "__main__":
    prepare()
