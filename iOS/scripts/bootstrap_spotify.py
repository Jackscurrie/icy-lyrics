"""Fetch the pinned upstream SDK into ignored staging; no developer credentials."""
from pathlib import Path
import hashlib
import json
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "5.0.1"
URL = f"https://codeload.github.com/spotify/ios-sdk/zip/refs/tags/v{VERSION}"
SHA256 = "0a33b716c124de3b5c195fd35c3d34940a6ef9d57918fd68e8f3c567a88c0063"
archive = ROOT / "build" / f"spotify-ios-sdk-{VERSION}.zip"
archive.parent.mkdir(parents=True, exist_ok=True)
if not archive.exists():
    with urllib.request.urlopen(URL, timeout=120) as response, archive.open("wb") as out:
        shutil.copyfileobj(response, out)
if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
    raise SystemExit("Spotify SDK checksum mismatch; refusing to use this archive.")
source = ROOT / "build" / "spotify-sdk-source"
source.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as zipped:
    for item in zipped.infolist():
        path = (source / item.filename).resolve()
        if not path.is_relative_to(source.resolve()):
            raise SystemExit("SDK archive contains an unsafe path.")
    zipped.extractall(source)
sdk = source / f"ios-sdk-{VERSION}"
destination = ROOT / "app" / "Frameworks" / "SpotifyiOS.xcframework"
shutil.copytree(sdk / "SpotifyiOS.xcframework", destination, dirs_exist_ok=True)
shutil.copytree(sdk / "Licenses", ROOT / "app" / "Frameworks" / "SpotifyLicenses", dirs_exist_ok=True)
(ROOT / "build" / "spotify-sdk.json").write_text(json.dumps({"version":VERSION,"url":URL,"sha256":SHA256}, indent=2)+"\n")
print(f"Verified Spotify iOS SDK {VERSION}; staged {destination}")
