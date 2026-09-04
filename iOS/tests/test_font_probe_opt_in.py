"""Guard the isolated, public-only manual font experiment and its artifact allowlist."""
from pathlib import Path
import unittest


WORKFLOW = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()


class FontProbeOptInTest(unittest.TestCase):
    def test_input_is_manual_boolean_false_by_default(self):
        dispatch = WORKFLOW.split("workflow_dispatch:", 1)[1].split("permissions:", 1)[0]
        entry = dispatch.split("font_backend_probe:", 1)[1]
        self.assertIn("type: boolean", entry)
        self.assertIn("default: false", entry)
        self.assertIn("required: false", entry)

    def test_probe_is_independent_public_mac_job_without_owner_credentials(self):
        job = WORKFLOW.split("  ios-font-backend-probe:\n", 1)[1]
        self.assertIn("github.event.repository.private == false && github.event_name == 'workflow_dispatch' && inputs.font_backend_probe", job)
        self.assertIn("runs-on: macos-26", job)
        self.assertIn("/Applications/Xcode_26.4.1.app/Contents/Developer", job)
        for forbidden in ("needs:", "secrets.", "SPOTIFY_CLIENT_ID", "owner-delivery", "build_ios.sh", "package_ipa"):
            self.assertNotIn(forbidden, job)
        self.assertIn("persist-credentials: false", job)
        existing_jobs = WORKFLOW.split("  ios-font-backend-probe:\n", 1)[0]
        self.assertNotIn("needs: ios-font-backend-probe", existing_jobs)

    def test_artifact_paths_are_explicit_evidence_only(self):
        job = WORKFLOW.split("  ios-font-backend-probe:\n", 1)[1]
        upload = job.split("- name: Upload font probe evidence only", 1)[1]
        self.assertIn("if: always()", upload)
        path_block = upload.split("path: |", 1)[1].split("if-no-files-found:", 1)[0]
        actual = {line.strip() for line in path_block.splitlines() if line.strip()}
        expected = {"run.json", "sources.lock.json", "validation.json", "android-context-comparison.json",
                    "android36-font-metrics.json", "captures/*.json", "captures/*.png", "*.log"}
        self.assertEqual({"iOS/build/freetype-probe/ci/" + name for name in expected}, actual)


if __name__ == "__main__":
    unittest.main()
