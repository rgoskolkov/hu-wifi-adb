#!/usr/bin/env python3
"""One-command APK installation for BZ3X; no ordinary adb/pm install fallback."""
from __future__ import annotations

import argparse
from contextlib import ExitStack, contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+")
PREFIX = "HUWIFIADB_INSTALL_JSON "


class InstallError(Exception):
    def __init__(self, message, code=1, *, outcome_unknown=False):
        super().__init__(message)
        self.code = code
        self.outcome_unknown = outcome_unknown


@dataclass(frozen=True)
class Apk:
    package: str
    version_code: int
    version_name: str | None
    sha256: str
    size: int


def sha256(path):
    with Path(path).open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        return digest.hexdigest()


def parse_badging(text, digest, size):
    line = next((x for x in text.splitlines() if x.startswith('package: ')), '')
    fields = dict(re.findall(r"([\w]+)='([^']*)'", line))
    package = fields.get('name', '')
    if not PACKAGE_RE.fullmatch(package):
        raise InstallError('APK has no valid application package name')
    if fields.get('split') or fields.get('isFeatureSplit') == 'true':
        raise InstallError('Split APK is unsupported; supply a single base APK')
    try:
        version = int(fields['versionCode']) | (int(fields.get('versionCodeMajor', '0')) << 32)
    except (KeyError, ValueError) as error:
        raise InstallError('Cannot read APK versionCode') from error
    return Apk(package, version, fields.get('versionName'), digest, size)


def sdk_path(root=ROOT):
    candidates = [os.environ.get('ANDROID_SDK_ROOT'), os.environ.get('ANDROID_HOME')]
    properties = root / 'local.properties'
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith('sdk.dir='):
                candidates.append(line.split('=', 1)[1].replace('\\:', ':').replace('\\\\', '\\'))
    candidates += [str(Path.home() / 'Library/Android/sdk'), str(Path.home() / 'Android/Sdk')]
    return next((Path(x).expanduser() for x in candidates if x and Path(x).expanduser().is_dir()), None)


def version_key(path):
    return tuple(int(x) for x in re.findall(r'\d+', path.name))


def sdk_tool(name, sdk=None):
    found = shutil.which(name)
    if found:
        return str(Path(found).resolve())
    sdk = sdk or sdk_path()
    if sdk:
        for directory in sorted((sdk / 'build-tools').glob('*'), key=version_key, reverse=True):
            for filename in [name, name + '.bat', name + '.exe']:
                path = directory / filename
                if path.is_file():
                    return str(path)
    raise InstallError(f'{name} is unavailable; configure local.properties or ANDROID_SDK_ROOT')


def execute(argv, *, timeout=40, env=None):
    try:
        return subprocess.run([str(x) for x in argv], capture_output=True, text=True,
                              errors='replace', timeout=timeout, env=env)
    except FileNotFoundError as error:
        raise InstallError(f'Executable not found: {argv[0]}') from error
    except subprocess.TimeoutExpired as error:
        raise InstallError(f'Command timed out: {argv[0]}', 5, outcome_unknown=True) from error


def metadata(path, aapt2):
    try:
        with zipfile.ZipFile(path) as archive:
            if 'AndroidManifest.xml' not in archive.namelist():
                raise InstallError('File is not an installable APK: AndroidManifest.xml is missing')
    except zipfile.BadZipFile as error:
        raise InstallError('File is not a valid APK/ZIP') from error
    result = execute([aapt2, 'dump', 'badging', path])
    if result.returncode:
        raise InstallError('Cannot inspect APK: ' + result.stderr.strip()[-1500:])
    return parse_badging(result.stdout, sha256(path), path.stat().st_size)


class Report:
    def __init__(self, root, run_id):
        self.directory = root / 'artifacts/installations' / run_id
        self.directory.mkdir(parents=True, exist_ok=False)
        self.path = self.directory / 'result.json'
        self.log_path = self.directory / 'commands.jsonl'
        self.data = {'schema_version': 1, 'run_id': run_id, 'status': 'preparing',
                     'started_at': datetime.now(timezone.utc).isoformat(), 'warnings': []}

    def log(self, record):
        with self.log_path.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + '\n')

    def save(self):
        self.data['report'] = str(self.path)
        temporary = self.path.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.data, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(self.path)


class Adb:
    def __init__(self, executable, report, serial=None, environment=None):
        self.executable, self.report, self.serial = executable, report, serial
        self.environment = environment

    def argv(self, *args):
        return [self.executable] + (['-s', self.serial] if self.serial else []) + list(args)

    def call(self, *args, timeout=40, check=True):
        argv = self.argv(*args)
        result = execute(argv, timeout=timeout, env=self.environment)
        self.report.log({'argv': argv, 'returncode': result.returncode,
                         'stdout': result.stdout, 'stderr': result.stderr})
        if check and result.returncode:
            raise InstallError('ADB failed: ' + (result.stderr or result.stdout).strip()[-1800:])
        return result

    def shell(self, *args, **kwargs):
        # adb shell reparses its command remotely; local argv alone is insufficient quoting.
        return self.call('shell', shlex.join([str(x) for x in args]), **kwargs)


def choose_device(adb, requested, backend='session'):
    output = adb.call('devices', '-l').stdout
    rows = []
    for line in output.splitlines():
        if not line.strip() or line.startswith(('List of devices', '*')):
            continue
        fields = line.split()
        if len(fields) >= 2:
            rows.append((fields[0], fields[1]))
    requested = requested or os.environ.get('ANDROID_SERIAL')
    ready = [serial for serial, state in rows if state == 'device']
    if requested:
        if requested not in ready:
            raise InstallError(f'ADB device {requested!r} is not ready (offline/unauthorized/not connected)', 3)
        selected = requested
    elif len(ready) == 1:
        selected = ready[0]
    elif not ready:
        raise InstallError('No ready ADB device. Connect/authorize the head unit; this tool does not enable ADB.', 3)
    else:
        raise InstallError('Several ADB devices are connected; choose --serial explicitly', 3)
    adb.serial = selected
    return selected


def parse_installed_dump(text, package):
    start = re.search(r'(?m)^\s*Package \[' + re.escape(package) + r'\] \([^\n]+\):', text)
    if not start:
        return None
    text = text[start.end():]
    next_package = re.search(r'(?m)^  Package \[|^Hidden system packages:', text)
    if next_package:
        text = text[:next_package.start()]
    code = re.search(r'\bversionCode=(\d+)', text)
    name = re.search(r'(?m)^\s*versionName=(.*)$', text)
    update = re.search(r'(?m)^\s*lastUpdateTime=(.*)$', text)
    flags = re.search(r'(?m)^\s*(?:pkgFlags|flags)=\[([^]]*)\]', text)
    if not code:
        raise InstallError('Installed package dump has no versionCode')
    return {'version_code': int(code.group(1)), 'version_name': name.group(1).strip() if name else None,
            'last_update_time': update.group(1).strip() if update else None,
            'system': bool(flags and {'SYSTEM', 'UPDATED_SYSTEM_APP'} & set(flags.group(1).split()))}


def installed(adb, package):
    return parse_installed_dump(adb.shell('dumpsys', 'package', package).stdout, package)


def reject_system_package(adb, package, before):
    if package == 'android' or (before and before['system']):
        raise InstallError('System package updates are outside this tool\'s scope', 4)
    packages = adb.shell('pm', 'list', 'packages', '-s', '-u', package).stdout.splitlines()
    if 'package:' + package in [x.strip() for x in packages]:
        raise InstallError('Package belongs to the firmware, including hidden/uninstalled-for-user system packages', 4)


@contextmanager
def device_lock(root, serial):
    try:
        import fcntl
    except ImportError as error:
        raise InstallError('This version requires a POSIX host (macOS/Linux) for device locking') from error
    path = root / 'artifacts/device-tools/locks' / (hashlib.sha256(serial.encode()).hexdigest()[:24] + '.lock')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+') as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise InstallError('Another installation is already using this device', 3) from error
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


@contextmanager
def ssh_transport(target, key, port, report, *, ssh='ssh'):
    """Expose the remote loopback ADB server on a temporary local loopback port."""
    if not re.fullmatch(r'(?:[A-Za-z0-9_.-]+@)?[A-Za-z0-9][A-Za-z0-9_.-]*', target):
        raise InstallError('Invalid SSH target; expected [user@]hostname or IPv4 address', 3)
    if not 1 <= port <= 65535:
        raise InstallError('SSH port must be 1..65535', 3)
    options = [ssh, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
               '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=2', '-p', str(port)]
    if key:
        identity = Path(key).expanduser().resolve()
        if not identity.is_file():
            raise InstallError(f'SSH identity file does not exist: {identity}', 3)
        options += ['-i', str(identity)]
    # Starting the host-side server does not start/modify an Android service.
    ready = execute(options + [target, 'adb start-server'], timeout=20)
    report.log({'transport': 'ssh', 'target': target, 'stage': 'remote_adb_server',
                'returncode': ready.returncode, 'stdout': ready.stdout, 'stderr': ready.stderr})
    if ready.returncode:
        raise InstallError('Cannot start remote ADB server: ' + ready.stderr.strip()[-1500:], 3)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        local_port = reservation.getsockname()[1]
    stderr_path = report.directory / 'ssh-tunnel.txt'
    with stderr_path.open('w', encoding='utf-8') as errors:
        process = subprocess.Popen(options + ['-o', 'ExitOnForwardFailure=yes', '-N',
            '-L', f'127.0.0.1:{local_port}:127.0.0.1:5037', target],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=errors,
            start_new_session=True)
        try:
            deadline = time.monotonic() + 15
            while True:
                if process.poll() is not None:
                    raise InstallError('SSH tunnel exited: ' + stderr_path.read_text()[-1500:], 3)
                try:
                    with socket.create_connection(('127.0.0.1', local_port), timeout=0.2):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise InstallError('SSH tunnel did not become ready', 3)
                    time.sleep(0.05)
            report.data['transport'] = {'type': 'ssh', 'target': target, 'port': port,
                                        'remote_adb_server': '127.0.0.1:5037'}
            yield dict(os.environ, ADB_SERVER_SOCKET=f'tcp:127.0.0.1:{local_port}')
        finally:
            stop_owned_process(process)


def stop_owned_process(process):
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=2)
    except ProcessLookupError:
        pass


def build_helper(root=ROOT, sdk=None, *, helper_name='LocalInstall'):
    sdk = sdk or sdk_path(root)
    if not sdk:
        raise InstallError('Android SDK is required to build the session helper')
    if helper_name not in {'LocalInstall', 'PermissionTool'}:
        raise InstallError('Unknown helper')
    source = root / 'tools/device/apk-installer' / (helper_name + '.java')
    platforms = [x for x in (sdk / 'platforms').glob('*') if (x / 'android.jar').is_file()]
    if not platforms:
        raise InstallError('No Android SDK platform is installed')
    platform = sorted(platforms, key=version_key)[-1] / 'android.jar'
    d8 = sdk_tool('d8', sdk)
    javac = str(Path(os.environ['JAVA_HOME']) / 'bin/javac') if os.environ.get('JAVA_HOME') else shutil.which('javac')
    if not javac:
        raise InstallError('JDK javac is required for the session helper')
    fingerprint = hashlib.sha256(source.read_bytes() + str(platform).encode() + d8.encode()).hexdigest()
    cache = root / 'artifacts/device-tools' / helper_name.lower() / fingerprint[:20]
    helper = cache / 'localinstall.apk'
    if helper.is_file() and zipfile.is_zipfile(helper):
        return helper
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='build-', dir=cache) as temporary:
        temporary = Path(temporary)
        classes, dex = temporary / 'classes', temporary / 'dex'
        classes.mkdir(); dex.mkdir()
        for command in [[javac, '--release', '8', '-encoding', 'UTF-8', '-cp', str(platform), '-d', str(classes), str(source)],
                        [d8, '--min-api', '28', '--lib', str(platform), '--output', str(dex)]]:
            if command[0] == d8:
                command += [str(x) for x in sorted(classes.glob('*.class'))]
            result = execute(command, timeout=120)
            if result.returncode:
                raise InstallError('Helper build failed: ' + (result.stderr or result.stdout)[-2500:])
        draft = temporary / 'localinstall.apk'
        with zipfile.ZipFile(draft, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex / 'classes.dex', 'classes.dex')
        shutil.copy2(draft, helper)
        (cache / 'build.json').write_text(json.dumps({'source_sha256': sha256(source),
                'helper_sha256': sha256(helper), 'platform': str(platform), 'd8': d8}, indent=2) + '\n')
    return helper


def parse_helper_events(output, run_id):
    events = []
    for line in output.splitlines():
        if not line.startswith(PREFIX):
            continue
        try:
            event = json.loads(line[len(PREFIX):])
        except json.JSONDecodeError:
            continue
        if event.get('run_id') != run_id:
            continue
        events.append(event)
    return events


def run_session(apk_path, info, adb, timeout, report, root=ROOT):
    sdk = int(adb.shell('getprop', 'ro.build.version.sdk').stdout.strip())
    if sdk < 28:
        raise InstallError('Session helper requires Android API 28 or newer', 4)
    helper = build_helper(root)
    remote = '/data/local/tmp/hu-wifi-adb-install-' + report.data['run_id']
    report.data['remote_prefix'] = remote
    report.data['remote_files'] = [remote + '-target.apk', remote + '-helper.apk']
    report.data['helper_sha256'] = sha256(helper)
    adb.call('push', str(apk_path), remote + '-target.apk', timeout=max(60, info.size / 524288))
    adb.call('push', str(helper), remote + '-helper.apk')
    command = 'CLASSPATH=' + shlex.quote(remote + '-helper.apk') + ' ' + shlex.join([
        '/system/bin/app_process', '/system/bin', 'LocalInstall', remote + '-target.apk',
        info.package, str(timeout), report.data['run_id']])
    result = adb.call('shell', command, timeout=timeout + max(60, info.size / 524288), check=False)
    (report.directory / 'session-output.txt').write_text(result.stdout + '\n' + result.stderr)
    events = parse_helper_events(result.stdout, report.data['run_id'])
    report.data['session_events'] = events
    final = next((e for e in reversed(events) if e.get('event') in {'result', 'error', 'timeout'}), None)
    if not final:
        committed = any(e.get('event') == 'commit_requested' for e in events)
        raise InstallError('Session helper produced no final result; see session-output.txt', 5 if committed else 4,
                           outcome_unknown=committed)
    if final.get('event') == 'timeout':
        raise InstallError(final.get('message', 'PackageInstaller callback timed out'), 5, outcome_unknown=True)
    if final.get('event') == 'error':
        raise InstallError(final.get('message', 'Session helper failed'), 4,
                           outcome_unknown=final.get('outcome_unknown', False))
    report.data['package_installer_status'] = final.get('status')
    if final.get('reported_package') not in {None, '', info.package}:
        raise InstallError('PackageInstaller reported a different package', 6)
    if final.get('status') == -1:
        raise InstallError('Android requires user action; the tool does not auto-approve dialogs', 7)
    if final.get('status') != 0:
        raise InstallError(final.get('message') or f'PackageInstaller status={final.get("status")}', 4)
    return remote


def installed_hash(adb, package):
    result = adb.shell('pm', 'path', '--user', '0', package)
    paths = [line[len('package:'):].strip() for line in result.stdout.splitlines() if line.startswith('package:')]
    candidates = [p for p in paths if p.endswith('/base.apk')] or (paths if len(paths) == 1 else [])
    if len(candidates) != 1:
        return None
    checksum = adb.shell('sha256sum', candidates[0], check=False)
    match = re.search(r'(?m)^([a-fA-F0-9]{64})\s', checksum.stdout)
    return match.group(1).lower() if checksum.returncode == 0 and match else None


def verify(adb, info, before, timeout, callback_confirmed, *, clock=time.monotonic, sleep=time.sleep):
    deadline = clock() + timeout
    last = None
    while clock() < deadline:
        last = installed(adb, info.package)
        if last and last['version_code'] == info.version_code and (
                info.version_name is None or last['version_name'] == info.version_name):
            digest = installed_hash(adb, info.package)
            if digest == info.sha256:
                return {'level': 'apk_sha256', 'installed': last, 'apk_sha256_verified': True}
            changed = before is None or before['version_code'] != info.version_code or (
                    last.get('last_update_time') and last.get('last_update_time') != before.get('last_update_time'))
            if digest is None and (callback_confirmed or changed):
                return {'level': 'metadata_and_session' if callback_confirmed else 'metadata_and_install_change',
                        'installed': last, 'apk_sha256_verified': False}
        sleep(0.5)
    raise InstallError('Installed APK did not match expected version/hash before deadline; last metadata=' + repr(last),
                       6, outcome_unknown=not callback_confirmed)


def grant_runtime_permissions(adb, package, report):
    from app_permissions import permissions
    try:
        return permissions(adb, package, 'grant-runtime', [], report)['changes']
    except InstallError as error:
        raise InstallError('Installed, but permission step failed: ' + str(error), 8,
                           outcome_unknown=error.outcome_unknown) from error


def launch(adb, package):
    result = adb.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                       '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', package)
    candidates = [x.strip() for x in result.stdout.splitlines() if re.fullmatch(r'[\w.]+/[\w.$]+', x.strip())]
    if len(candidates) != 1 or candidates[0].split('/')[0] != package:
        raise InstallError('APK is installed, but a unique launcher activity was not found', 8)
    response = adb.shell('am', 'start', '--user', '0', '-n', candidates[0], check=False)
    if response.returncode or 'Error:' in response.stdout:
        raise InstallError('APK is installed, but launching failed: ' + (response.stderr or response.stdout), 8)
    return candidates[0]


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('apk', type=Path, help='One signed, standalone APK')
    result.add_argument('--serial', help='ADB serial; supports multiple connected devices')
    result.add_argument('--ssh', help='Remote ADB host, e.g. user@headunit-host; temporary SSH tunnel')
    result.add_argument('--ssh-key', type=Path, help='SSH identity file; key contents are never copied')
    result.add_argument('--ssh-port', type=int, default=22)
    result.add_argument('--adb', default=shutil.which('adb') or 'adb')
    result.add_argument('--aapt2', help='APK metadata tool (auto-detected from Android SDK)')
    result.add_argument('--timeout', type=int, default=120, help='installation/verification deadline, 10..600 seconds')
    result.add_argument('--reinstall', action='store_true', help='explicitly uninstall the existing NON-system app first; DELETES ITS DATA')
    result.add_argument('--grant-runtime-permissions', action='store_true', help='attempt declared runtime permissions after verified installation; no broad appops changes')
    result.add_argument('--launch', action='store_true', help='launch the verified app through its MAIN/LAUNCHER activity')
    result.add_argument('--dry-run', action='store_true', help='inspect APK and print plan; no ADB calls or helper/installer execution')
    result.add_argument('--json', action='store_true', help='one JSON result on stdout; progress goes to stderr')
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    if args.ssh_key and not args.ssh:
        parser().error('--ssh-key requires --ssh')
    if not 10 <= args.timeout <= 600:
        parser().error('--timeout must be between 10 and 600 seconds')
    report = Report(ROOT, uuid.uuid4().hex)
    report.data.update({'backend': 'session', 'source_apk': str(args.apk.expanduser().absolute()),
                        'options': {'reinstall': args.reinstall, 'grant_runtime_permissions': args.grant_runtime_permissions,
                                    'launch': args.launch, 'timeout_seconds': args.timeout}})
    exit_code = 0
    remote = None
    adb = None
    try:
        source = args.apk.expanduser().resolve()
        if not source.is_file():
            raise InstallError(f'APK file not found: {source}')
        aapt2 = args.aapt2 or sdk_tool('aapt2')
        with tempfile.TemporaryDirectory(prefix='hu-wifi-adb-install-') as temporary, ExitStack() as transports:
            folder = Path(temporary)
            immutable = folder / 'source.apk'
            shutil.copy2(source, immutable)
            info = metadata(immutable, aapt2)
            report.data['apk'] = asdict(info)
            if args.dry_run:
                report.data.update({'status': 'dry_run', 'device_checks_pending': True,
                    'plan': ['select exactly the requested device', 'reject system packages',
                             'uninstall this user package first (DATA LOSS)' if args.reinstall else 'preserve installed app data; never auto-uninstall',
                             'our source-built PackageInstaller.Session helper over app_process',
                             'verify installed package/version/hash',
                             'grant declared runtime permissions' if args.grant_runtime_permissions else 'no extra permissions/appops',
                             'launch MAIN/LAUNCHER' if args.launch else 'do not launch app']})
            else:
                environment = transports.enter_context(ssh_transport(args.ssh, args.ssh_key, args.ssh_port, report)) if args.ssh else None
                adb = Adb(args.adb, report, environment=environment)
                serial = choose_device(adb, args.serial)
                report.data['serial'] = serial
                print(f'Installing {info.package} {info.version_name or info.version_code} via session on {serial}', file=sys.stderr)
                with device_lock(ROOT, serial):
                    current_user = adb.shell('am', 'get-current-user').stdout.strip()
                    if current_user != '0':
                        raise InstallError('This version targets Android user 0; current user is ' + current_user, 3)
                    before = installed(adb, info.package)
                    report.data['before'] = before
                    reject_system_package(adb, info.package, before)
                    # Resolve all local dependencies before any explicit data deletion.
                    build_helper(ROOT)
                    if args.reinstall and before:
                        print(f'Explicit --reinstall: removing {info.package} and its data', file=sys.stderr)
                        removal = adb.call('uninstall', info.package)
                        if 'Success' not in removal.stdout or installed(adb, info.package) is not None:
                            raise InstallError('Explicit uninstall did not remove the package', 4)
                        before = None
                    report.data['status'] = 'installing'
                    report.save()
                    remote = run_session(immutable, info, adb, args.timeout, report)
                    report.data['verification'] = verify(adb, info, before, args.timeout, True)
                    if not report.data['verification']['apk_sha256_verified']:
                        report.data['warnings'].append('Installed APK SHA256 was not readable; verification used callback/metadata/change evidence')
                    report.data['status'] = 'installed'
                    report.save()
                    if args.grant_runtime_permissions:
                        report.data['permissions'] = grant_runtime_permissions(adb, info.package, report)
                    if args.launch:
                        report.data['launched_activity'] = launch(adb, info.package)
                    if remote:
                        removed = adb.shell('rm', '-f', remote + '-helper.apk', remote + '-target.apk', check=False)
                        report.data['remote_cleaned'] = removed.returncode == 0
                        if not report.data['remote_cleaned']:
                            report.data['warnings'].append('Installation verified; temporary device files could not be fully removed')
    except InstallError as error:
        exit_code = error.code
        was_installed = report.data.get('status') == 'installed'
        report.data.update({'status': 'installed_post_step_failed' if was_installed else 'failed',
                            'error': str(error), 'outcome_unknown': error.outcome_unknown})
    except (OSError, ValueError, KeyError) as error:
        exit_code = 1
        report.data.update({'status': 'failed', 'error': str(error)})
    except KeyboardInterrupt:
        exit_code = 130
        report.data.update({'status': 'interrupted', 'error': 'Interrupted; no automatic uninstall or retry',
                            'outcome_unknown': report.data.get('status') == 'installing'})
    report.data.update({'exit_code': exit_code, 'finished_at': datetime.now(timezone.utc).isoformat()})
    report.save()
    if args.json:
        print(json.dumps(report.data, ensure_ascii=False))
    elif exit_code:
        print(f'ERROR: {report.data.get("error")}\nReport: {report.path}', file=sys.stderr)
    else:
        print(f'{report.data["status"]}: {report.data["apk"]["package"]}\nReport: {report.path}')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
