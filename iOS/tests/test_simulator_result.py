"""Validate recorded Xcode test evidence without claiming to execute Xcode here."""
from pathlib import Path
from copy import deepcopy
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"scripts"))
from record_simulator_result import validate_summary

class SimulatorSummaryValidation(unittest.TestCase):
    def summary(self):
        return {"result":"Passed","passedTests":17,"failedTests":0,"skippedTests":0,
                "expectedFailures":0,"totalTestCount":17,"testFailures":[],
                "devicesAndConfigurations":[{"device":{"deviceId":"TEST-DEVICE","platform":"iOS Simulator","architecture":"arm64"}}]}

    def test_complete_selected_simulator_pass_is_accepted(self):
        validate_summary(self.summary(),"test-device")

    def test_failure_skip_or_partial_count_cannot_be_reported_as_a_pass(self):
        for updates in ({"result":"Failed"},{"failedTests":1},{"skippedTests":1},
                        {"expectedFailures":1},{"totalTestCount":18},{"passedTests":0,"totalTestCount":0},
                        {"testFailures":[{"testName":"failed"}]},{"passedTests":True}):
            with self.subTest(updates=updates),self.assertRaises(ValueError):
                validate_summary(self.summary()|updates,"TEST-DEVICE")

    def test_missing_summary_counts_are_rejected(self):
        summary=self.summary()
        del summary["totalTestCount"]
        with self.assertRaisesRegex(ValueError,"count"): validate_summary(summary,"TEST-DEVICE")

    def test_physical_phone_or_wrong_simulator_cannot_supply_simulator_evidence(self):
        for updates in ({"platform":"iOS"},{"architecture":"x86_64"},{"deviceId":"OTHER-DEVICE"}):
            summary=self.summary()
            summary["devicesAndConfigurations"][0]["device"].update(updates)
            with self.subTest(updates=updates),self.assertRaisesRegex(ValueError,"selected arm64"):
                validate_summary(summary,"TEST-DEVICE")

    def test_summary_without_device_identity_is_rejected(self):
        summary=self.summary()
        del summary["devicesAndConfigurations"]
        with self.assertRaisesRegex(ValueError,"device information"): validate_summary(summary,"TEST-DEVICE")

if __name__=="__main__": unittest.main()
