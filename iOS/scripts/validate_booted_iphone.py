"""Validate a retained simulator by UUID and device type, never its editable name."""
import json
import sys
from uuid import UUID


def booted_iphone(payload, identifier):
    identifier = str(UUID(identifier))
    devices = payload.get("devices") if isinstance(payload, dict) else None
    if not isinstance(devices, dict):
        raise ValueError("simctl did not return a device inventory")
    matches = []
    for runtime, entries in devices.items():
        if not runtime.startswith("com.apple.CoreSimulator.SimRuntime.iOS-"):
            continue
        if not isinstance(entries, list):
            raise ValueError("Invalid iOS simulator inventory")
        for device in entries:
            if isinstance(device, dict) and str(device.get("udid", "")).lower() == identifier:
                matches.append((runtime, device))
    if len(matches) != 1:
        raise ValueError("Expected one existing iOS simulator matching the retained UUID")
    runtime, device = matches[0]
    if device.get("isAvailable") is not True:
        raise ValueError("The retained iOS simulator is unavailable")
    if device.get("state") != "Booted":
        raise ValueError("The retained iOS simulator is not booted")
    device_type = device.get("deviceTypeIdentifier", "")
    if not isinstance(device_type, str) or not device_type.startswith("com.apple.CoreSimulator.SimDeviceType.iPhone-"):
        raise ValueError("The retained simulator is not an iPhone device type")
    return {"udid": identifier, "runtime": runtime, "deviceTypeIdentifier": device_type,
            "state": "Booted", "name": device.get("name")}


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: validate_booted_iphone.py SIMULATOR_UUID < simctl-devices.json")
    try:
        print(json.dumps(booted_iphone(json.load(sys.stdin), sys.argv[1]), sort_keys=True))
    except (ValueError, TypeError) as error:
        raise SystemExit(f"Retained simulator rejected: {error}") from error


if __name__ == "__main__":
    main()
