"""Guard the isolated, public-only manual font experiment and its artifact allowlist."""
from pathlib import Path
import ast
import itertools
import re
import unittest


WORKFLOW = (Path(__file__).resolve().parents[2] / ".github/workflows/ci.yml").read_text()


def job_block(name):
    # Bound the font job before later independent experiments in the workflow.
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|\Z)",
                      WORKFLOW, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError(f"Missing workflow job: {name}")
    return match.group(1)


def job_guard(name):
    return re.search(r"^    if: (.+)$", job_block(name), re.MULTILINE).group(1)


def evaluate_guard(guard, event, private, probe, only, skiko=False):
    values = {"github.event_name": event, "github.event.repository.private": private,
              "inputs.font_backend_probe": probe, "inputs.font_backend_only": only,
              "inputs.skiko_freetype_only": skiko}
    for name, value in values.items():
        guard = guard.replace(name, repr(value))
    guard = re.sub(r"\btrue\b", "True", re.sub(r"\bfalse\b", "False", guard))
    tree = ast.parse(guard.replace("&&", " and ").replace("||", " or "), mode="eval")

    def evaluate(node):
        if isinstance(node, ast.Expression): return evaluate(node.body)
        if isinstance(node, ast.Constant) and type(node.value) in (bool, str): return node.value
        if isinstance(node, ast.BoolOp):
            values = [evaluate(value) for value in node.values]
            if isinstance(node.op, ast.And): return all(values)
            if isinstance(node.op, ast.Or): return any(values)
        if isinstance(node, ast.Compare) and len(node.ops) == 1:
            left, right = evaluate(node.left), evaluate(node.comparators[0])
            if isinstance(node.ops[0], ast.Eq): return left == right
            if isinstance(node.ops[0], ast.NotEq): return left != right
        raise AssertionError(f"Unexpected workflow guard syntax: {ast.dump(node)}")
    return evaluate(tree)


class FontProbeOptInTest(unittest.TestCase):
    def test_input_is_manual_boolean_false_by_default(self):
        dispatch = WORKFLOW.split("workflow_dispatch:", 1)[1].split("permissions:", 1)[0]
        for name in ("font_backend_probe", "font_backend_only", "skiko_freetype_only"):
            entry = re.search(rf"^      {name}:\n((?:        .+\n)+)", dispatch, re.MULTILINE).group(1)
            self.assertIn("type: boolean", entry)
            self.assertIn("default: false", entry)
            self.assertIn("required: false", entry)

    def test_probe_is_independent_public_mac_job_without_owner_credentials(self):
        job = job_block("ios-font-backend-probe")
        self.assertIn("github.event.repository.private == false && github.event_name == 'workflow_dispatch' && (inputs.font_backend_probe || inputs.font_backend_only)", job)
        self.assertIn("runs-on: macos-26", job)
        self.assertIn("/Applications/Xcode_26.4.1.app/Contents/Developer", job)
        for forbidden in ("needs:", "secrets.", "SPOTIFY_CLIENT_ID", "owner-delivery", "build_ios.sh", "package_ipa"):
            self.assertNotIn(forbidden, job)
        self.assertIn("persist-credentials: false", job)
        existing_jobs = WORKFLOW.split("  ios-font-backend-probe:\n", 1)[0]
        self.assertNotIn("needs: ios-font-backend-probe", existing_jobs)

    def test_only_manual_font_only_requests_skip_application_jobs(self):
        for event, private, probe, only in itertools.product(
                ("push", "pull_request", "workflow_dispatch"), (False, True), (False, True), (False, True)):
            with self.subTest(event=event, private=private, probe=probe, only=only):
                run_apps = not (event == "workflow_dispatch" and only)
                for job in ("extension", "android-play"):
                    self.assertEqual(run_apps, evaluate_guard(job_guard(job), event, private, probe, only))
                self.assertEqual(run_apps and not private,
                                 evaluate_guard(job_guard("ios-sideload"), event, private, probe, only))
                self.assertEqual(not private and event == "workflow_dispatch" and (probe or only),
                                 evaluate_guard(job_guard("ios-font-backend-probe"), event, private, probe, only))

    def test_only_explicit_manual_skiko_request_skips_existing_jobs(self):
        for event, private, probe, only, skiko in itertools.product(
                ("push", "pull_request", "workflow_dispatch"), (False, True),
                (False, True), (False, True), (False, True)):
            with self.subTest(event=event, private=private, probe=probe, only=only, skiko=skiko):
                run_apps = not (event == "workflow_dispatch" and (only or skiko))
                for job in ("extension", "android-play"):
                    self.assertEqual(run_apps, evaluate_guard(job_guard(job), event, private, probe, only, skiko))
                self.assertEqual(run_apps and not private,
                                 evaluate_guard(job_guard("ios-sideload"), event, private, probe, only, skiko))
                self.assertEqual(not private and event == "workflow_dispatch" and not skiko and (probe or only),
                                 evaluate_guard(job_guard("ios-font-backend-probe"), event, private, probe, only, skiko))
                enabled = not private and event == "workflow_dispatch" and skiko
                self.assertEqual(enabled,
                                 evaluate_guard(job_guard("skiko-freetype-native"), event, private, probe, only, skiko))
                package_guard = job_guard("skiko-freetype-package")
                for result in ("success", "failure", "cancelled", "skipped"):
                    self.assertEqual(enabled and result == "success",
                                     evaluate_guard(package_guard.replace("needs.skiko-freetype-native.result", repr(result)),
                                                    event, private, probe, only, skiko))

    def test_full_package_experiment_has_separate_public_standard_runner_jobs(self):
        native = job_block("skiko-freetype-native")
        package = job_block("skiko-freetype-package")
        self.assertNotIn("needs:", native)
        self.assertIn("needs: skiko-freetype-native", package)
        self.assertIn("target: [ios, iosSim]", native)
        self.assertIn("fail-fast: false", native)
        for job in (native, package):
            self.assertIn("runs-on: macos-26", job)
            self.assertIn("/Applications/Xcode_26.4.1.app/Contents/Developer", job)
            self.assertIn("java-version: 17", job)
            self.assertIn("persist-credentials: false", job)
            for forbidden in ("secrets.", "SPOTIFY_CLIENT_ID", "owner-delivery", "build_ios.sh", "package_ipa"):
                self.assertNotIn(forbidden, job)
        self.assertIn("--stage skia", native)
        self.assertIn("--stage package", package)
        self.assertIn("--stage consumer", package)
        self.assertIn("digest-mismatch: error", package)
        self.assertIn("steps.skiko_consumer.outcome == ''success''", package)

    def test_main_validation_evidence_precedes_optional_additional_capture(self):
        app = job_block("ios-sideload")
        self.assertLess(app.index("- name: Upload validation evidence"),
                        app.index("- name: Capture opt-in additional iPhone parity evidence"))

    def test_artifact_paths_are_explicit_evidence_only(self):
        job = job_block("ios-font-backend-probe")
        upload = job.split("- name: Upload font probe evidence only", 1)[1]
        self.assertIn("if: always()", upload)
        path_block = upload.split("path: |", 1)[1].split("if-no-files-found:", 1)[0]
        actual = {line.strip() for line in path_block.splitlines() if line.strip()}
        expected = {"run.json", "sources.lock.json", "verified-inputs.json", "validation.json", "android-context-comparison.json",
                    "android36-font-metrics.json", "captures/*.json", "captures/*.png", "*.log"}
        self.assertEqual({"iOS/build/freetype-probe/ci/" + name for name in expected}, actual)


if __name__ == "__main__":
    unittest.main()
