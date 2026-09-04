"""Add only test fixtures/dependencies to an already frozen Android baseline checkout."""
from pathlib import Path
import argparse
parser = argparse.ArgumentParser()
parser.add_argument("baseline", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
baseline = args.baseline.resolve()
expected = (root / "iOS/build/android-baseline").resolve()
if baseline != expected:
    raise SystemExit("Baseline must be the frozen iOS/build/android-baseline directory")
fixtures = root / "iOS/shared/ui/src/commonMain/kotlin/com/icy/lyrics/ui"
tests = baseline / "app/src/androidTest/java/com/icy/lyrics/parity"
tests.mkdir(parents=True, exist_ok=True)
builder = (fixtures / "IcyParityFixtures.kt").read_text(encoding="utf-8")
builder = builder.replace("import androidx.compose.ui.graphics.ImageBitmap", "import androidx.compose.ui.graphics.ImageBitmap\nimport androidx.compose.ui.graphics.asAndroidBitmap")
builder = builder.replace("artwork = artwork(),", "artwork = artwork().asAndroidBitmap(),")
(tests / "IcyParityFixtures.kt").write_text(builder, encoding="utf-8")
screen = (fixtures / "IcyParityFixtureScreen.kt").read_text(encoding="utf-8")
body = screen[screen.index("    IcyLyricsApp("):screen.rindex("  }\n}")]
screen = "package com.icy.lyrics.ui\nimport androidx.compose.runtime.*\n@Composable fun IcyParityFixtureScreen(id: String) {\n  val state = remember(id) { IcyParityFixtures.state(id) }\n" + body + "}\n"
(tests / "IcyParityFixtureScreen.kt").write_text(screen, encoding="utf-8")
harness = (root / "iOS/tests/android/IcyParityScreenshotTest.kt").read_text(encoding="utf-8")
harness = harness.replace("      val platform = rememberAndroidIcyUiPlatform()\n      CompositionLocalProvider(LocalIcyUiPlatform provides platform) {\n        key(current) { IcyParityFixtureScreen(current) }\n      }", "      key(current) { IcyParityFixtureScreen(current) }")
(tests / "IcyParityScreenshotTest.kt").write_text(harness, encoding="utf-8")
gradle = baseline / "app/build.gradle.kts"
s = gradle.read_text()
if "ui-test-junit4" not in s:
    s = s.replace('  debugImplementation("androidx.compose.ui:ui-tooling")', '''  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:core:1.7.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")''')
    gradle.write_text(s)
if 'ui-test-manifest' not in s:
    s = s.replace('  debugImplementation("androidx.compose.ui:ui-tooling")', '  debugImplementation("androidx.compose.ui:ui-tooling")\n  debugImplementation("androidx.compose.ui:ui-test-manifest")')
    gradle.write_text(s)
print("Prepared baseline test-only adapters; production sources preserved.")
