"""Host-only tests: no real ADB device is used."""
import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import install_apk as app

PACKAGE = "org.example.fixture"
DUMP = """Packages:
  Package [org.example.fixture] (abc):
    versionCode=7 minSdk=28 targetSdk=30
    versionName=0.7
    lastUpdateTime=2026-10-26 10:00:00
    pkgFlags=[ DEBUGGABLE HAS_CODE ]
"""


class ParsingTests(unittest.TestCase):
    def test_badging_and_split_rejection(self):
        info = app.parse_badging(
            "package: name='org.example.fixture' versionCode='7' versionName='0.7'",
            "abc",
            1,
        )
        self.assertEqual(info.version_code, 7)
        with self.assertRaises(app.InstallError):
            app.parse_badging(
                "package: name='org.example.fixture' versionCode='7' split='config.en'",
                "abc",
                1,
            )

    def test_installed_dump_selects_exact_package(self):
        self.assertEqual(app.parse_installed_dump(DUMP, PACKAGE)["version_code"], 7)
        self.assertIsNone(app.parse_installed_dump(DUMP, PACKAGE + ".other"))
        self.assertTrue(
            app.parse_installed_dump(DUMP.replace("DEBUGGABLE", "SYSTEM"), PACKAGE)["system"]
        )

    def test_adb_remote_shell_quoting(self):
        class Reporter:
            def log(self, record):
                self.record = record

        reporter = Reporter()
        adb = app.Adb("/fake/adb", reporter, "SERIAL")
        value = "/data/local/tmp/a space; $(touch bad).apk"
        from subprocess import CompletedProcess

        with patch.object(
            app, "execute", return_value=CompletedProcess([], 0, "", "")
        ) as execute:
            adb.shell("sha256sum", value)
        self.assertEqual(
            shlex.split(execute.call_args.args[0][-1]), ["sha256sum", value]
        )

    def test_foreign_helper_callback_ignored(self):
        data = app.PREFIX + json.dumps(
            {"run_id": "other", "event": "result", "status": 0}
        )
        self.assertEqual(app.parse_helper_events(data, "mine"), [])

    def test_multiple_devices_require_explicit_serial(self):
        from subprocess import CompletedProcess

        class FakeAdb:
            serial = None
            def call(self, *args):
                return CompletedProcess(
                    [], 0, "List of devices attached\nA device\nB device\n", ""
                )

        with self.assertRaises(app.InstallError) as error:
            app.choose_device(FakeAdb(), None)
        self.assertEqual(error.exception.code, 3)


class SessionResultTests(unittest.TestCase):
    def check_result(self, event, expected_code=None):
        from subprocess import CompletedProcess

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "helper.apk"
            helper.write_bytes(b"host fixture")
            report = app.Report(root, "mine")
            info = app.Apk(PACKAGE, 7, "0.7", "a" * 64, 10)

            class FakeAdb:
                def shell(self, *args, **kwargs):
                    return CompletedProcess(
                        [], 0, "30" if args[0] == "getprop" else "", ""
                    )

                def call(self, *args, **kwargs):
                    output = (
                        app.PREFIX
                        + json.dumps(dict(run_id="mine", **event))
                        if args[0] == "shell"
                        else ""
                    )
                    return CompletedProcess([], 0, output, "")

            with patch.object(app, "build_helper", return_value=helper):
                if expected_code is None:
                    return app.run_session(
                        root / "target.apk", info, FakeAdb(), 10, report, root
                    )
                with self.assertRaises(app.InstallError) as error:
                    app.run_session(
                        root / "target.apk", info, FakeAdb(), 10, report, root
                    )
                self.assertEqual(error.exception.code, expected_code)
                return error.exception

    def test_pending_user_action_is_not_success(self):
        self.check_result({"event": "result", "status": -1}, 7)

    def test_wrong_package_callback_is_rejected(self):
        self.check_result(
            {"event": "result", "status": 0, "reported_package": "other.package"}, 6
        )

    def test_timeout_preserves_unknown_outcome(self):
        error = self.check_result({"event": "timeout"}, 5)
        self.assertTrue(error.outcome_unknown)

    def test_success_callback_returns_unique_remote_path(self):
        remote = self.check_result(
            {"event": "result", "status": 0, "reported_package": PACKAGE}
        )
        self.assertEqual(remote, "/data/local/tmp/hu-wifi-adb-install-mine")


class MainTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="hu-wifi-adb-test-")
        self.root = Path(self.temporary.name)
        self.apk = self.root / "fixture.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", "fixture")
        self.aapt = self.root / "aapt2"
        self.aapt.write_text(
            "#!" + sys.executable + "\n"
            "print(\"package: name='org.example.fixture' versionCode='7' versionName='0.7'\")\n"
        )
        self.aapt.chmod(0o755)

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, *flags):
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            patch.object(app, "ROOT", self.root),
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            result = app.main(
                [
                    str(self.apk),
                    "--aapt2",
                    str(self.aapt),
                    "--json",
                    *flags,
                ]
            )
        return result, json.loads(stdout.getvalue())

    def test_dry_run_never_calls_adb(self):
        with patch.object(app, "Adb", side_effect=AssertionError("ADB called")):
            code, report = self.invoke("--dry-run")
        self.assertEqual(code, 0)
        self.assertEqual(report["status"], "dry_run")
        self.assertEqual(report["backend"], "session")

    def test_missing_apk_is_structured_error(self):
        self.apk.unlink()
        code, report = self.invoke("--dry-run")
        self.assertEqual(code, 1)
        self.assertEqual(report["status"], "failed")

    def test_default_session_path_has_no_fallback(self):
        class FakeAdb:
            serial = "FAKE"
            def call(self, *args, **kwargs):
                from subprocess import CompletedProcess
                if args[:2] == ("devices", "-l"):
                    return CompletedProcess([], 0, "List of devices attached\nFAKE device\n", "")
                return CompletedProcess([], 0, "", "")
            def shell(self, *args, **kwargs):
                from subprocess import CompletedProcess
                if args[:2] == ("am", "get-current-user"):
                    return CompletedProcess([], 0, "0\n", "")
                if args[:2] == ("dumpsys", "package"):
                    return CompletedProcess([], 0, "", "")
                if args[:3] == ("pm", "list", "packages"):
                    return CompletedProcess([], 0, "", "")
                return CompletedProcess([], 0, "", "")

        with (
            patch.object(app, "Adb", return_value=FakeAdb()),
            patch.object(app, "build_helper"),
            patch.object(
                app,
                "run_session",
                side_effect=app.InstallError("fixture session rejection", 4),
            ),
        ):
            code, report = self.invoke("--serial", "FAKE")
        self.assertEqual(code, 4)
        self.assertEqual(report["error"], "fixture session rejection")


if __name__ == "__main__":
    unittest.main()
