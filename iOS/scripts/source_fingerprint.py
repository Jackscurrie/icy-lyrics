"""Fingerprint port sources without reading ignored credentials or generated files."""
from pathlib import Path
import hashlib
import subprocess

ROOT=Path(__file__).resolve().parents[1]
def fingerprint():
    repo=ROOT.parent
    output=subprocess.check_output(["git","ls-files","-z","--cached","--others","--exclude-standard","--",
                                    "iOS","android-v2",".github/workflows/ci.yml"],cwd=repo)
    digest=hashlib.sha256()
    for raw in sorted(set(output.split(b"\0"))):
        if not raw: continue
        file=repo/raw.decode("utf-8")
        if not file.is_file(): continue
        digest.update(raw+b"\0")
        digest.update(hashlib.sha256(file.read_bytes()).digest())
    return digest.hexdigest()
if __name__=="__main__": print(fingerprint())
