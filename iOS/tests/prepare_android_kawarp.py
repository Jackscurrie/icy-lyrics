"""Copy only the Kawarp reflection probe into the frozen original test source set."""
from prepare_android_extended import ROOT, BASELINE, verify_original_production_sources


def prepare():
    verified = verify_original_production_sources()
    directory = BASELINE / "app/src/androidTest/java/com/icy/lyrics/parity"
    if not (directory / "IcyParityFixtures.kt").is_file():
        raise ValueError("The original frozen fixture adapter must already exist")
    source = ROOT / "iOS/tests/android/KawarpGpuPhaseProbeTest.kt"
    (directory / source.name).write_bytes(source.read_bytes())
    verify_original_production_sources()
    print(f"Prepared Kawarp test only; all {len(verified)} original production hashes remain unchanged")


if __name__ == "__main__":
    prepare()
