#!/usr/bin/env python3
"""Run Xcode tests alongside a scoped, exact-byte simctl framebuffer capture service.

The XCTest runner writes Documents/icy-native-capture/requests/<UUID>.json:
{schemaVersion:1, requestId:UUID, scenario:string, expectedWidthPx:int,
 expectedHeightPx:int, metadata:object}. A response with the same UUID contains
status, sourceCommand, sha256, widthPx, heightPx and optional PNG metadata/error.
Whenever simctl produced a file, its original bytes are retained and returned,
including on a dimension/decoding failure. No image transformation is performed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

from PIL import Image


MAX_REQUEST_BYTES = 256 * 1024
MAX_PNG_BYTES = 128 * 1024 * 1024


def require_uuid(value: str) -> str:
    if not isinstance(value, str) or str(uuid.UUID(value)) != value.lower():
        raise ValueError("Expected a complete hyphenated UUID")
    return value


def direct_path(root: Path, *parts: str) -> Path:
    """Reject symlink components before reading/writing this fixed sandbox path."""
    candidate = root
    if root.is_symlink():
        raise ValueError("Capture sandbox root is a symlink")
    for part in parts:
        candidate /= part
        if candidate.is_symlink():
            raise ValueError("Capture sandbox path contains a symlink")
    candidate.resolve().relative_to(root.resolve())
    return candidate


def atomic_new(path: Path, data: bytes) -> None:
    """Publish once without replacing an existing success, failure or partial result."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".partial-" + str(uuid.uuid4()))
    try:
        with temporary.open("xb") as output:
            output.write(data)
        # A hard link publishes atomically and refuses an existing destination.
        os.link(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def json_bytes(value) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode("utf-8")


def read_request(path: Path, raw: bytes | None = None) -> dict:
    if path.stat().st_size > MAX_REQUEST_BYTES:
        raise ValueError("Capture request exceeds the bounded JSON size")
    raw = path.read_bytes() if raw is None else raw
    def distinct(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError("Duplicate request JSON key")
            result[key] = value
        return result
    request = json.loads(raw, object_pairs_hook=distinct,
                         parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite request value")))
    if not isinstance(request, dict) or type(request.get("schemaVersion")) is not int or request["schemaVersion"] != 1:
        raise ValueError("Unsupported capture request schema")
    require_uuid(request.get("requestId"))
    if path.stem != request["requestId"]:
        raise ValueError("Capture request ID differs from filename")
    if not isinstance(request.get("scenario"), str) or not request["scenario"].strip() or len(request["scenario"]) > 200:
        raise ValueError("Invalid capture scenario")
    for name in ("expectedWidthPx", "expectedHeightPx"):
        if type(request.get(name)) is not int or not 1 <= request[name] <= 8192:
            raise ValueError("Invalid expected framebuffer dimensions")
    if not isinstance(request.get("metadata"), dict):
        raise ValueError("Capture request requires app draw metadata")
    return request


def png_info(path: Path) -> dict:
    size = path.stat().st_size
    if not 0 < size <= MAX_PNG_BYTES:
        raise ValueError("Framebuffer PNG has an invalid size")
    raw = path.read_bytes()
    result = {"bytes": size, "sha256": hashlib.sha256(raw).hexdigest()}
    with Image.open(path) as image:
        if image.format != "PNG": raise ValueError("simctl did not produce PNG data")
        result.update(widthPx=image.width, heightPx=image.height, pngMode=image.mode)
        result["pngOrientation"] = int(image.getexif().get(274, 1))
        profile = image.info.get("icc_profile")
        if profile: result["pngIccProfileSha256"] = hashlib.sha256(profile).hexdigest()
        for key in ("srgb", "gamma", "dpi"):
            if key in image.info: result["png" + key.title()] = image.info[key]
    with Image.open(path) as image:
        image.verify()
    return result


class CaptureService:
    def __init__(self, simulator: str, output: Path, started_ns: int, run=subprocess.run):
        self.simulator, self.output, self.started_ns, self.run = require_uuid(simulator), output, started_ns, run
        self.processed, self.results = set(), []

    def poll(self, container: Path) -> None:
        requests = direct_path(container, "Documents", "icy-native-capture", "requests")
        if not requests.is_dir(): return
        for path in sorted(requests.glob("*.json")):
            request_id = require_uuid(path.stem)
            key = request_id.lower()
            if key in self.processed: continue
            direct_path(container, "Documents", "icy-native-capture", "requests", path.name)
            if path.stat().st_mtime_ns < self.started_ns: continue
            self.processed.add(key)
            self.capture(container, path, request_id)

    def capture(self, container: Path, request_path: Path, request_id: str) -> None:
        response_json = direct_path(container, "Documents", "icy-native-capture", "responses", request_id + ".json")
        response_png = direct_path(container, "Documents", "icy-native-capture", "responses", request_id + ".png")
        evidence = self.output / request_id
        # Reusing an ID must not overwrite an earlier capture/response.
        if evidence.exists() or response_json.exists() or response_png.exists():
            raise ValueError("Capture request ID was already used")
        evidence.mkdir()
        raw_png = evidence / "framebuffer.png"
        command = ["xcrun", "simctl", "io", self.simulator, "screenshot", "--type=png", "--mask=ignored", str(raw_png)]
        result = {"schemaVersion": 1, "requestId": request_id, "status": "error",
                  "source": "simctl framebuffer", "sourceCommand": command,
                  "imageTransformed": False, "appearanceParityVerified": False}
        try:
            if request_path.stat().st_size > MAX_REQUEST_BYTES:
                raise ValueError("Capture request exceeds the bounded JSON size")
            request_bytes = request_path.read_bytes()
            request = read_request(request_path, request_bytes)
            atomic_new(evidence / "request.json", request_bytes)
            result["scenario"] = request["scenario"]
            result["requestSha256"] = hashlib.sha256(request_bytes).hexdigest()
            result["expectedWidthPx"], result["expectedHeightPx"] = request["expectedWidthPx"], request["expectedHeightPx"]
            completed = self.run(command, capture_output=True, text=True, timeout=30)
            atomic_new(evidence / "simctl.log", (completed.stdout + completed.stderr).encode("utf-8"))
            result["captureExitCode"] = completed.returncode
            if raw_png.is_file():
                # Copy exact bytes even when decoding/geometry subsequently fails.
                raw = raw_png.read_bytes()
                result.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
                atomic_new(response_png, raw)
                result.update(png_info(raw_png))
            if completed.returncode: raise ValueError("simctl screenshot command failed")
            if not raw_png.is_file(): raise ValueError("simctl did not produce a framebuffer PNG")
            if (result["widthPx"], result["heightPx"]) != (request["expectedWidthPx"], request["expectedHeightPx"]):
                raise ValueError("Framebuffer dimensions differ from the current app draw; no rotation or resize was applied")
            result["status"] = "captured"
        except Exception as error:
            result["error"] = str(error)
        finally:
            # A timed-out/failed command can still have produced useful raw data.
            # Keep that original file available to XCTest before publishing its ack.
            if raw_png.is_file() and not response_png.exists():
                try:
                    raw = raw_png.read_bytes()
                    result.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
                    atomic_new(response_png, raw)
                    result.update(png_info(raw_png))
                except Exception as error:
                    result["diagnosticText"] = str(error)
            atomic_new(evidence / "response.json", json_bytes(result))
            atomic_new(response_json, json_bytes(result))
            self.results.append(result)


def runner_container(simulator: str, runner: str, run=subprocess.run) -> Path | None:
    completed = run(["xcrun", "simctl", "get_app_container", simulator, runner, "data"],
                    capture_output=True, text=True, timeout=10)
    if completed.returncode: return None  # XCTest may not have installed its runner yet.
    container = Path(completed.stdout.strip())
    expected = Path.home() / "Library/Developer/CoreSimulator/Devices" / str(uuid.UUID(simulator)).upper() / "data/Containers/Data/Application"
    if not container.is_absolute() or container.is_symlink():
        raise ValueError("simctl returned an invalid runner data container")
    relative = container.resolve().relative_to(expected.resolve())
    if len(relative.parts) != 1 or not container.is_dir():
        raise ValueError("Runner container is outside this simulator's application data")
    return container


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator", required=True)
    parser.add_argument("--runner-bundle-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    require_uuid(args.simulator)
    if not re.fullmatch(r"[A-Za-z0-9.-]+\.xctrunner", args.runner_bundle_id):
        parser.error("Use the exact XCTest runner bundle identifier")
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command: parser.error("Provide the xcodebuild command after --")
    if sys.platform != "darwin": parser.error("Host framebuffer capture requires macOS simctl")
    output = args.output.resolve()
    build = Path(__file__).resolve().parents[1] / "build"
    output.relative_to(build.resolve())
    if output == build.resolve(): parser.error("Use a new output directory below iOS/build")
    output.mkdir(parents=True, exist_ok=False)
    service = CaptureService(args.simulator, output, time.time_ns())
    summary = {"schemaVersion": 1, "simulator": args.simulator, "runnerBundleId": args.runner_bundle_id,
               "source": "public simctl framebuffer", "appearanceParityVerified": False, "errors": []}
    child = None
    try:
        child = subprocess.Popen(command)
        container = None
        next_lookup = 0.0
        while child.poll() is None:
            try:
                # Installation can take minutes. Avoid spawning simctl five times
                # per second while compiling, and reuse the verified data container.
                if container is not None and not container.is_dir():
                    container = None
                if container is None and time.monotonic() >= next_lookup:
                    container = runner_container(args.simulator, args.runner_bundle_id)
                    next_lookup = time.monotonic() + 1.0
                if container: service.poll(container)
            except Exception as error:
                if str(error) not in summary["errors"]:
                    summary["errors"].append(str(error))
            time.sleep(0.2)
        return child.returncode
    except OSError as error:
        summary["errors"].append(str(error))
        return 127
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=15)
        summary.update(xcodeExitCode=child.returncode if child is not None else None, captures=service.results)
        atomic_new(output / "host-summary.json", json_bytes(summary))


if __name__ == "__main__":
    raise SystemExit(main())
