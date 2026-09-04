"""Retained iPhone identity is independent of its custom display name."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from validate_booted_iphone import booted_iphone

IDENTIFIER = "AE42CFCF-8FFB-497C-AAEB-F69DE0081C06"
RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
DEVICE = {"udid": IDENTIFIER, "name": "IcyLyricsVerification", "state": "Booted",
          "isAvailable": True, "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-16"}


class RetainedSimulatorTest(unittest.TestCase):
    def inventory(self, device=None):
        return {"devices": {RUNTIME: [deepcopy(DEVICE if device is None else device)]}}

    def test_actual_custom_verification_name_is_accepted_by_type_and_uuid(self):
        result = booted_iphone(self.inventory(), IDENTIFIER.lower())
        self.assertEqual("IcyLyricsVerification", result["name"])
        self.assertEqual(IDENTIFIER.lower(), result["udid"])
        self.assertEqual(DEVICE["deviceTypeIdentifier"], result["deviceTypeIdentifier"])

    def test_iphone_display_name_cannot_disguise_an_ipad(self):
        device = DEVICE | {"name": "iPhone 16", "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-M4"}
        with self.assertRaisesRegex(ValueError, "not an iPhone"):
            booted_iphone(self.inventory(device), IDENTIFIER)

    def test_unbooted_unavailable_or_untyped_devices_are_rejected(self):
        for change, expected in (({"state": "Shutdown"}, "not booted"),
                                 ({"isAvailable": False}, "unavailable"),
                                 ({"deviceTypeIdentifier": None}, "not an iPhone")):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, expected):
                booted_iphone(self.inventory(DEVICE | change), IDENTIFIER)

    def test_wrong_missing_or_duplicated_uuid_cannot_select_a_different_phone(self):
        cases = [self.inventory(DEVICE | {"udid": "12345678-1234-1234-1234-123456789abc"}),
                 {"devices": {}}, {"devices": {RUNTIME: [DEVICE, DEVICE]}}]
        for value in cases:
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "matching the retained UUID"):
                booted_iphone(value, IDENTIFIER)

    def test_non_ios_runtime_and_malformed_inventory_are_rejected(self):
        for value in ({"devices": {"com.apple.CoreSimulator.SimRuntime.tvOS-26-4": [DEVICE]}},
                      {"devices": []}, {"devices": {RUNTIME: None}}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                booted_iphone(value, IDENTIFIER)


if __name__ == "__main__":
    unittest.main()
