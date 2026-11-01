#!/usr/bin/env python3
"""Inspect/grant/revoke app permissions and explicitly selected AppOps, locally or via SSH."""
from contextlib import ExitStack
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sys
import uuid

import install_apk as device

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'HUWIFIADB_PERMISSIONS_JSON '


def parse_result(output, run_id, package):
    records = []
    for line in output.splitlines():
        if line.startswith(PREFIX):
            try:
                record = json.loads(line[len(PREFIX):])
            except json.JSONDecodeError:
                continue
            if record.get('run_id') == run_id and record.get('package') == package:
                records.append(record)
    if len(records) != 1:
        raise device.InstallError('Permission helper did not return one matching result; outcome may be unknown', 5, outcome_unknown=True)
    return records[0]


def permissions(adb, package, action, names, report):
    helper = device.build_helper(ROOT, helper_name='PermissionTool')
    remote = '/data/local/tmp/hu-wifi-adb-permissions-' + report.data['run_id'] + '.apk'
    report.data.setdefault('remote_files', []).append(remote)
    adb.call('push', str(helper), remote)
    command = 'CLASSPATH=' + shlex.quote(remote) + ' ' + shlex.join([
        '/system/bin/app_process', '/system/bin', 'PermissionTool', report.data['run_id'], action, package, *names])
    try:
        response = adb.call('shell', command, timeout=60, check=False)
        (report.directory / 'permissions-output.txt').write_text(response.stdout + '\n' + response.stderr)
        result = parse_result(response.stdout, report.data['run_id'], package)
        report.data['permission_result'] = result
        if not result.get('ok'):
            raise device.InstallError(result.get('error', 'Some permissions were rejected; inspect changes in report'), 4)
        return result
    finally:
        response = adb.shell('rm', '-f', remote, check=False)
        report.data['permission_helper_cleaned'] = response.returncode == 0


def parse_appop(output, op):
    # get can include last-access times after ';', which are not part of the mode.
    match = re.search(r'(?m)^\s*(?:Uid mode:\s*)?' + re.escape(op) + r':\s*(allow|ignore|deny|default|foreground)\b', output)
    if match:
        return match.group(1)
    default = re.search(r'(?m)^Default mode:\s*(allow|ignore|deny|default|foreground)\b', output)
    return default.group(1) if default else None


def appops(adb, package, action, op, mode, report):
    before = adb.shell('cmd', 'appops', 'get', '--user', '0', package, *([op] if op else []), check=False)
    report.data['appops_before'] = before.stdout
    if before.returncode or re.search(r'(?im)^(error|exception|unknown)', before.stdout):
        raise device.InstallError('Cannot read AppOps: ' + before.stdout + before.stderr, 4)
    if action == 'appops-get':
        return
    old = parse_appop(before.stdout, op)
    response = adb.shell('cmd', 'appops', 'set', '--user', '0', package, op, mode, check=False)
    after = adb.shell('cmd', 'appops', 'get', '--user', '0', package, op, check=False)
    actual = parse_appop(after.stdout, op)
    report.data['appop_change'] = {'op': op, 'before': old, 'desired': mode, 'after': actual,
                                  'raw_after': after.stdout, 'verified': after.returncode == 0 and actual == mode}
    if response.returncode or not report.data['appop_change']['verified']:
        raise device.InstallError('AppOp was rejected or the requested mode was not verified', 4)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['inspect', 'grant', 'grant-runtime', 'revoke', 'appops-get', 'appops-set'])
    p.add_argument('package')
    p.add_argument('permissions', nargs='*', help='Fully qualified names for grant/revoke')
    p.add_argument('--op', help='Explicit AppOp name, e.g. SYSTEM_ALERT_WINDOW')
    p.add_argument('--mode', choices=['allow', 'ignore', 'deny', 'default', 'foreground'])
    p.add_argument('--serial')
    p.add_argument('--ssh')
    p.add_argument('--ssh-key', type=Path)
    p.add_argument('--ssh-port', type=int, default=22)
    p.add_argument('--adb', default=shutil.which('adb') or 'adb')
    p.add_argument('--dry-run', action='store_true', help='Inspect and build a plan, without changing permissions/AppOps; does connect to device')
    p.add_argument('--json', action='store_true')
    return p


def main(argv=None):
    p = parser(); args = p.parse_args(argv)
    if not device.PACKAGE_RE.fullmatch(args.package): p.error('Invalid package name')
    if args.ssh_key and not args.ssh: p.error('--ssh-key requires --ssh')
    if args.action in {'grant', 'revoke'} and not args.permissions: p.error('Specify permission names')
    if args.action not in {'grant', 'revoke'} and args.permissions: p.error('Permission names only apply to grant/revoke')
    if any(not device.PACKAGE_RE.fullmatch(x) for x in args.permissions): p.error('Invalid permission name')
    if args.action == 'appops-set' and (not args.op or not args.mode): p.error('appops-set requires --op and --mode')
    if args.op and not re.fullmatch(r'[A-Z][A-Z0-9_]*', args.op): p.error('Use an uppercase AppOp name')
    if args.action not in {'appops-get', 'appops-set'} and (args.op or args.mode): p.error('--op/--mode only apply to AppOps')
    report = device.Report(ROOT, 'permissions-' + uuid.uuid4().hex)
    report.data.update({'action': args.action, 'package': args.package, 'dry_run': args.dry_run})
    code = 0
    try:
        with ExitStack() as contexts:
            env = contexts.enter_context(device.ssh_transport(args.ssh, args.ssh_key, args.ssh_port, report)) if args.ssh else None
            adb = device.Adb(args.adb, report, environment=env)
            serial = device.choose_device(adb, args.serial, 'session')
            report.data['serial'] = serial
            with device.device_lock(ROOT, serial):
                if adb.shell('am', 'get-current-user').stdout.strip() != '0':
                    raise device.InstallError('Only current Android user 0 supported', 3)
                installed = device.installed(adb, args.package)
                if not installed: raise device.InstallError('Package is not installed', 4)
                mutation = args.action not in {'inspect', 'appops-get'}
                if mutation: device.reject_system_package(adb, args.package, installed)
                if args.action.startswith('appops-'):
                    appops(adb, args.package, 'appops-get' if args.dry_run else args.action, args.op, args.mode, report)
                else:
                    permissions(adb, args.package, 'inspect' if args.dry_run else args.action, args.permissions if not args.dry_run else [], report)
                report.data['status'] = 'dry_run' if args.dry_run else 'verified'
                if args.dry_run:
                    report.data['plan'] = {'action': args.action, 'permissions': args.permissions, 'op': args.op, 'mode': args.mode}
    except device.InstallError as error:
        code = error.code
        report.data.update(status='failed', error=str(error), outcome_unknown=error.outcome_unknown)
    except (OSError, ValueError) as error:
        code = 1; report.data.update(status='failed', error=str(error))
    except KeyboardInterrupt:
        code = 130; report.data.update(status='interrupted', outcome_unknown=True)
    report.data['exit_code'] = code
    report.save()
    if args.json:
        print(json.dumps(report.data, ensure_ascii=False))
    else:
        print(json.dumps(report.data, ensure_ascii=False, indent=2))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
