"""Host-only validation of permission results, AppOps verification and preflight."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import app_permissions as app
import install_apk as device


class ResultTests(unittest.TestCase):
    def test_matches_run_and_package(self):
        row = {'run_id': 'run', 'package': 'com.bzsoft.fixture', 'ok': True}
        self.assertEqual(app.parse_result(app.PREFIX+json.dumps(row), 'run', 'com.bzsoft.fixture'), row)

    def test_foreign_and_duplicate_results_rejected(self):
        row = app.PREFIX+json.dumps({'run_id': 'other', 'package': 'com.bzsoft.fixture'})
        with self.assertRaises(device.InstallError): app.parse_result(row, 'mine', 'com.bzsoft.fixture')
        row = app.PREFIX+json.dumps({'run_id': 'mine', 'package': 'com.bzsoft.fixture'})
        with self.assertRaises(device.InstallError): app.parse_result(row+'\n'+row, 'mine', 'com.bzsoft.fixture')

    def test_appop_parser(self):
        self.assertEqual(app.parse_appop('  WRITE_SETTINGS: allow; time=+1s', 'WRITE_SETTINGS'), 'allow')
        self.assertEqual(app.parse_appop('Uid mode: CAMERA: foreground', 'CAMERA'), 'foreground')
        self.assertIsNone(app.parse_appop('No operations.', 'WRITE_SETTINGS'))

    def test_default_mode_reported_without_stored_operation(self):
        self.assertEqual(app.parse_appop('No operations.\nDefault mode: default\n', 'WRITE_SETTINGS'), 'default')

    def test_appop_must_be_read_back(self):
        class FakeAdb:
            def __init__(self): self.calls = []
            def shell(self, *args, **kwargs):
                self.calls.append(args)
                return subprocess.CompletedProcess([], 0, 'WRITE_SETTINGS: ignore' if 'get' in args else '', '')
        class Report: data = {}
        adb = FakeAdb()
        with self.assertRaises(device.InstallError):
            app.appops(adb, 'com.bzsoft.fixture', 'appops-set', 'WRITE_SETTINGS', 'allow', Report())
        self.assertEqual(len(adb.calls), 3)
        self.assertFalse(Report.data['appop_change']['verified'])

    def test_install_flag_uses_own_helper(self):
        with patch.object(app, 'permissions', return_value={'changes': [{'verified': True}]}) as helper:
            result = device.grant_runtime_permissions(None, 'com.bzsoft.fixture', 'report')
        self.assertEqual(result, [{'verified': True}])
        helper.assert_called_once_with(None, 'com.bzsoft.fixture', 'grant-runtime', [], 'report')

    def test_install_flag_failure_is_post_install_error(self):
        with patch.object(app, 'permissions', side_effect=device.InstallError('denied', 4)):
            with self.assertRaises(device.InstallError) as error:
                device.grant_runtime_permissions(None, 'com.bzsoft.fixture', 'report')
        self.assertEqual(error.exception.code, 8)


class MainTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
    def tearDown(self): self.temp.cleanup()

    def invoke(self, *args):
        class FakeAdb:
            serial = 'FAKE'
            def __init__(self, *args, **kwargs): pass
            def shell(self, *args, **kwargs): return subprocess.CompletedProcess([], 0, '0', '')
        output = io.StringIO()
        with patch.object(app, 'ROOT', self.root), patch.object(device, 'Adb', FakeAdb), patch.object(device, 'choose_device', return_value='FAKE'), patch.object(device, 'installed', return_value={'system': False}), contextlib.redirect_stdout(output):
            code = app.main([*args, '--json'])
        return code, json.loads(output.getvalue())

    def test_dry_run_inspects_without_mutating(self):
        with patch.object(device, 'reject_system_package'), patch.object(app, 'permissions') as helper:
            code, report = self.invoke('grant', 'com.bzsoft.fixture', 'android.permission.CAMERA', '--dry-run')
        self.assertEqual(code, 0)
        self.assertEqual(helper.call_args.args[2], 'inspect')
        self.assertEqual(helper.call_args.args[3], [])
        self.assertEqual(report['status'], 'dry_run')

    def test_system_package_blocked_before_helper(self):
        with patch.object(device, 'reject_system_package', side_effect=device.InstallError('system excluded', 4)), patch.object(app, 'permissions') as helper:
            code, report = self.invoke('grant-runtime', 'com.bzsoft.fixture')
        self.assertEqual(code, 4)
        helper.assert_not_called()

    def test_failed_helper_not_reported_as_success(self):
        with patch.object(app, 'permissions', side_effect=device.InstallError('permission rejected', 4)):
            code, report = self.invoke('inspect', 'com.bzsoft.fixture')
        self.assertEqual(code, 4)
        self.assertEqual(report['status'], 'failed')

    def test_all_appops_without_selection_not_allowed(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            app.main(['appops-set', 'com.bzsoft.fixture', '--mode', 'allow'])
        self.assertEqual(error.exception.code, 2)


if __name__ == '__main__': unittest.main()
