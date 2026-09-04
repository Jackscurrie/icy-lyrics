"""Copy the separate motion fixture/harness into the frozen baseline's test source set only."""
from prepare_android_extended import ROOT, BASELINE, verify_original_production_sources


def prepare():
    verified = verify_original_production_sources()
    directory = BASELINE / "app/src/androidTest/java/com/icy/lyrics/parity"
    for required in ("IcyParityFixtures.kt", "NativeViewportProfile.kt"):
        if not (directory / required).is_file():
            raise ValueError("Prepare the frozen baseline and extended metrics adapters first")
    fixture = ROOT / "iOS/shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui/IcyMotionFixtureScreen.kt"
    (directory / fixture.name).write_bytes(fixture.read_bytes())
    source = (ROOT / "iOS/tests/android/IcyMotionParityScreenshotTest.kt").read_text(encoding="utf-8")
    original = '''  val platform = rememberAndroidIcyUiPlatform()
  CompositionLocalProvider(LocalIcyUiPlatform provides platform) {
    IcyMotionFixtureScreen(state, onStep, onEnter, onLeave)
  }'''
    if source.count(original) != 1:
        raise ValueError("Motion host changed; review the frozen-baseline test adapter")
    source = source.replace(original, "  IcyMotionFixtureScreen(state, onStep, onEnter, onLeave)")
    (directory / "IcyMotionParityScreenshotTest.kt").write_text(source, encoding="utf-8")
    verify_original_production_sources()
    print(f"Prepared motion test adapter only; {len(verified)} original production hashes verified.")


if __name__ == "__main__":
    prepare()
