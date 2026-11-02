"""Offline filesystem coverage of the production exact-build NV21 installer."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/vmos-guest-nv21-scale-repair.sh'
ORIGINAL = ROOT / 'artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.so'
ARTIFACTS = ROOT / 'artifacts/vmos/nv21-scale-repair'
PROVIDER_HASH = 'fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87'
SPEC = importlib.util.spec_from_file_location('camera_prepare', ROOT / 'scripts/prepare-vmos-camera.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


@unittest.skipUnless(os.name == 'posix' and ORIGINAL.is_file() and ARTIFACTS.is_dir(),
                     'Requires Linux/WSL and exact prepared HAL candidates')
class Nv21ScaleInstallerTests(unittest.TestCase):
    def setUp(self):
        text = SCRIPT.read_text()
        self.pins = {name: re.search(r'^' + name + '=([a-f0-9]{64})$', text, re.M).group(1)
                     for name in ('factory_hash', 'pipe_hash', 'factory_fixed', 'pipe_fixed')}
        self.sources = {'factory': ORIGINAL.read_bytes(), 'pipe': PREPARE.patch_module(ORIGINAL.read_bytes())}
        self.repairs = {}
        for flavor in ('factory', 'pipe'):
            matches = [p for p in ARTIFACTS.rglob('*.so')
                       if hashlib.sha256(p.read_bytes()).hexdigest() == self.pins[flavor + '_fixed']]
            self.assertTrue(matches, 'Missing pinned candidate for ' + flavor)
            self.repairs[flavor] = matches[0].read_bytes()
        self.temporary = tempfile.TemporaryDirectory(prefix='vcam-nv21-scale-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / 'mock-bin'
        self.bin.mkdir()
        self.target = self.path('/vendor/lib64/hw/camera.vphw90.so')
        self.provider = self.path('/vendor/bin/hw/android.hardware.camera.provider@2.4-service')
        self.candidate = self.path('/data/local/tmp/vcam-nv21-scale-camera.so')
        self.record = self.path('/data/local/tmp/vcam-nv21-scale-repair-v1.txt')
        self.lock = self.target.with_name('.vcam-nv21-scale-repair-lock')
        self.jlock = self.record.with_name('.vcam-nv21-scale-repair-journal-lock')
        for p in (self.target, self.provider, self.candidate): p.parent.mkdir(parents=True, exist_ok=True)
        self.provider.write_bytes(b'mock pinned provider executable')
        self.metadata = self.root / 'metadata.json'
        self.metadata.write_text('{}')
        self.environment = dict(os.environ, MOCK_ROOT=str(self.root), MOCK_TARGET=str(self.target),
                                MOCK_RECORD=str(self.record), MOCK_PROVIDER=str(self.provider))
        self.root.joinpath('pid').write_text('100')
        self.path('/proc/100').mkdir(parents=True)
        self.path('/proc/100/cmdline').write_bytes(str(self.provider).encode() + b'\0')
        driver = '''#!/usr/bin/python3
import hashlib, json, os, pathlib, stat, subprocess, sys
name = pathlib.Path(sys.argv[0]).name
r = pathlib.Path(os.environ['MOCK_ROOT'])
m = r / 'metadata.json'
d = json.loads(m.read_text())
a = sys.argv[1:]
if name == 'id': print(os.environ.get('MOCK_UID', '0'))
elif name == 'getenforce': print(os.environ.get('MOCK_SELINUX', 'Disabled'))
elif name == 'getprop':
    values = {'ro.hardware': ('MOCK_HARDWARE', 'vphw90'), 'ro.build.version.sdk': ('MOCK_SDK', '28'),
      'ro.product.cpu.abilist': ('MOCK_ABI', 'arm64-v8a'),
      'ro.build.fingerprint': ('MOCK_FINGERPRINT', 'vmos/vphw90/vphw90:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys'),
      'init.svc.vendor.camera-provider-2-4': ('MOCK_SERVICE', 'running')}
    key, value = values[a[0]]; print(os.environ.get(key, value))
elif name == 'stat':
    fmt, p = a[1:3]; s = pathlib.Path(p).stat(); e = d.get(p, {})
    uid, gid, mode = e.get('uid', 0), e.get('gid', 0), format(stat.S_IMODE(s.st_mode), 'o')
    print({'%u':str(uid), '%g':str(gid), '%a':mode, '%s':str(s.st_size), '%h':str(s.st_nlink),
           '%a:%u:%g': mode+':'+str(uid)+':'+str(gid)}[fmt])
elif name == 'ls': print(d.get(a[-1], {}).get('label', '?') + ' ' + a[-1])
elif name == 'chown':
    uid, gid = map(int, a[0].split(':')); d.setdefault(a[1], {}).update(uid=uid, gid=gid)
    m.write_text(json.dumps(d))
elif name == 'chcon':
    if os.environ.get('MOCK_CHCON_FAIL') == '1': raise SystemExit(1)
    d.setdefault(a[1], {})['label'] = a[0]; m.write_text(json.dumps(d))
elif name == 'sha256sum':
    b = pathlib.Path(a[0]).read_bytes() if a else sys.stdin.buffer.read()
    h = '__PROVIDER_HASH__' if b == b'mock pinned provider executable' else hashlib.sha256(b).hexdigest()
    print(h + '  ' + (a[0] if a else '-'))
elif name == 'pidof': print(os.environ.get('MOCK_PID', (r / 'pid').read_text()))
elif name == 'sleep': pass
elif name == 'setprop':
    assert a == ['ctl.restart', 'vendor.camera-provider-2-4']
    with (r / 'restart.log').open('a') as f: f.write(' '.join(a) + '\\n')
    if os.environ.get('MOCK_RESTART_CORRUPT_TARGET') == '1':
        pathlib.Path(os.environ['MOCK_TARGET']).write_bytes(b'unknown changed target')
    if os.environ.get('MOCK_RESTART_CORRUPT_RECORD') == '1':
        pathlib.Path(os.environ['MOCK_RECORD']).write_bytes(b'unknown changed journal')
    if os.environ.get('MOCK_RESTART_FAIL') != '1':
        p = r / 'pid'; pid = str(int(p.read_text()) + 1); p.write_text(pid)
        q = r / 'proc' / pid; q.mkdir(exist_ok=True)
        (q / 'cmdline').write_bytes(os.environ['MOCK_PROVIDER'].encode() + b'\\0')
elif name == 'mv':
    source, destination = a[-2:]
    assert pathlib.Path(source).relative_to(r).parts[0] == pathlib.Path(destination).relative_to(r).parts[0], 'cross-filesystem rename'
    if source.endswith('/repair.tmp') and os.environ.get('MOCK_PUBLISH_FAIL') == '1': raise SystemExit(1)
    result = subprocess.call(['/bin/mv'] + a)
    if result == 0 and not pathlib.Path(source).exists():
        d[destination] = d.pop(source, {}); m.write_text(json.dumps(d))
    raise SystemExit(result)
else: raise SystemExit('Unknown mock command ' + name)
'''.replace('__PROVIDER_HASH__', PROVIDER_HASH)
        for name in ('id', 'getenforce', 'getprop', 'stat', 'ls', 'chown', 'chcon', 'sha256sum',
                     'pidof', 'sleep', 'setprop', 'mv'):
            p = self.bin / name; p.write_text(driver); p.chmod(0o755)
        text = text.replace('PATH=/system/bin:/system/xbin', 'PATH=' + str(self.bin) + ':/usr/bin:/bin')
        for prefix in ('/vendor', '/data', '/proc'): text = text.replace(prefix, str(self.root) + prefix)
        self.script = self.root / 'installer.sh'
        self.script.write_text(text)
        self.select('factory')

    def path(self, path): return self.root / path.lstrip('/')

    def select(self, flavor):
        self.flavor = flavor
        self.target.write_bytes(self.sources[flavor]); self.target.chmod(0o777)
        self.candidate.write_bytes(self.repairs[flavor]); self.candidate.chmod(0o600)
        self.backup = self.target.with_name('.vcam-nv21-scale-original-' + self.pins[flavor + '_hash'] + '.so')

    def run_installer(self, action, success=True, **environment):
        result = subprocess.run(['/bin/sh', str(self.script), action], text=True, capture_output=True,
                                env=dict(self.environment, **environment), timeout=45)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def install(self):
        result = self.run_installer('install')
        self.assertIn('before=100 after=101', result.stdout)
        self.assertEqual(self.target.read_bytes(), self.repairs[self.flavor])
        self.assertEqual(self.backup.read_bytes(), self.sources[self.flavor])
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o777)

    def test_release_hashes_and_shell_syntax(self):
        for flavor in ('factory', 'pipe'):
            self.assertEqual(len(self.sources[flavor]), 2109016)
            self.assertEqual(len(self.repairs[flavor]), 2109016)
            self.assertEqual(hashlib.sha256(self.sources[flavor]).hexdigest(), self.pins[flavor + '_hash'])
        subprocess.run(['/bin/sh', '-n', str(SCRIPT)], check=True)

    def test_factory_install_restore_original777_and_only_exact_provider_restart(self):
        self.install()
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
        self.run_installer('restore')
        self.assertEqual(self.target.read_bytes(), self.sources['factory'])
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o777)
        self.assertFalse(self.record.exists()); self.assertFalse(self.lock.exists()); self.assertFalse(self.jlock.exists())
        self.assertEqual((self.root / 'restart.log').read_text().splitlines(),
                         ['ctl.restart vendor.camera-provider-2-4'] * 2)

    def test_pipe_install_restore_preserves_transport_source(self):
        self.select('pipe'); self.install(); self.run_installer('restore')
        self.assertEqual(self.target.read_bytes(), self.sources['pipe'])
        self.assertEqual(self.backup.read_bytes(), self.sources['pipe'])

    def test_preserves_nondefault_group_and_selinux_label(self):
        self.metadata.write_text(json.dumps({str(self.target): {'gid':1000, 'label':'u:object_r:vendor_file:s0'}}))
        self.install(); self.run_installer('restore')
        for p in (self.target, self.backup):
            self.assertEqual(json.loads(self.metadata.read_text())[str(p)],
                             {'uid':0, 'gid':1000, 'label':'u:object_r:vendor_file:s0'})

    def test_inspect_is_read_only(self):
        self.run_installer('inspect')
        self.assertFalse(self.record.exists()); self.assertFalse(self.backup.exists())
        self.assertFalse(self.lock.exists()); self.assertFalse((self.root / 'restart.log').exists())

    def test_unknown_guest_is_refused_before_mutation(self):
        for k,v in [('MOCK_UID','2000'), ('MOCK_SDK','29'), ('MOCK_ABI','armeabi-v7a'),
                    ('MOCK_HARDWARE','unknown'), ('MOCK_FINGERPRINT','other'), ('MOCK_SELINUX','Enforcing')]:
            with self.subTest(k=k): self.run_installer('install', success=False, **{k:v})
        self.assertFalse(self.record.exists()); self.assertFalse(self.backup.exists())

    def test_cross_pair_candidate_is_refused(self):
        self.candidate.write_bytes(self.repairs['pipe'])
        self.run_installer('install', success=False)
        self.assertEqual(self.target.read_bytes(), self.sources['factory'])
        self.assertFalse(self.backup.exists())

    def test_unknown_target_or_wrapper_is_refused(self):
        self.target.write_bytes(b'unknown wrapper')
        self.run_installer('install', success=False)
        self.assertEqual(self.target.read_bytes(), b'unknown wrapper')

    def test_candidate_symlink_hardlink_and_unknownbytes_are_refused(self):
        self.candidate.unlink(); self.candidate.symlink_to(ORIGINAL)
        self.run_installer('install', success=False)
        self.candidate.unlink(); self.candidate.write_bytes(self.repairs['factory'])
        os.link(self.candidate, self.candidate.with_suffix('.alias'))
        self.run_installer('install', success=False)
        self.candidate.unlink(); self.candidate.write_bytes(b'unknown')
        self.run_installer('install', success=False)
        self.assertFalse(self.backup.exists())

    def test_unknown_backup_and_symlink_are_preserved(self):
        self.backup.write_bytes(b'unknown backup')
        self.run_installer('install', success=False)
        self.assertEqual(self.backup.read_bytes(), b'unknown backup')
        self.backup.unlink(); self.backup.symlink_to(self.target)
        self.run_installer('install', success=False)
        self.assertTrue(self.backup.is_symlink())

    def test_unknown_journal_is_data_not_code(self):
        self.record.write_text('$(touch ' + str(self.root / 'executed') + ')\n'); self.record.chmod(0o600)
        self.run_installer('restore', success=False)
        self.assertFalse((self.root / 'executed').exists()); self.assertTrue(self.record.exists())

    def test_provider_unknown_bytes_pid_or_service_refuse_before_mutation(self):
        for environment in ({'MOCK_PID':'100 101'}, {'MOCK_PID':'1'}, {'MOCK_SERVICE':'stopped'}):
            self.run_installer('install', success=False, **environment)
            self.assertFalse(self.backup.exists())
        self.provider.write_bytes(b'unknown provider')
        self.run_installer('install', success=False)
        self.assertFalse(self.backup.exists())

    def test_provider_command_line_must_match_exact_executable(self):
        self.path('/proc/100/cmdline').write_bytes(b'/system/bin/not-camera\0')
        self.run_installer('install', success=False)
        self.assertFalse(self.backup.exists())

    def test_restart_failure_restores_source_and_retains_journal(self):
        self.run_installer('install', success=False, MOCK_RESTART_FAIL='1')
        self.assertEqual(self.target.read_bytes(), self.sources['factory'])
        self.assertTrue(self.record.exists()); self.run_installer('restore')
        self.assertFalse(self.record.exists())

    def test_restore_restart_failure_retains_source_and_journal(self):
        self.install(); self.run_installer('restore', success=False, MOCK_RESTART_FAIL='1')
        self.assertEqual(self.target.read_bytes(), self.sources['factory'])
        self.assertTrue(self.record.exists()); self.run_installer('restore')

    def test_publication_failure_retains_recoverable_journal(self):
        self.run_installer('install', success=False, MOCK_PUBLISH_FAIL='1')
        self.assertEqual(self.target.read_bytes(), self.sources['factory'])
        self.assertTrue(self.record.exists()); self.run_installer('restore')

    def test_unknown_target_after_restart_is_preserved(self):
        self.run_installer('install', success=False, MOCK_RESTART_CORRUPT_TARGET='1')
        self.assertEqual(self.target.read_bytes(), b'unknown changed target')
        self.run_installer('restore', success=False)
        self.assertEqual(self.target.read_bytes(), b'unknown changed target')

    def test_unknown_journal_after_restart_is_preserved(self):
        self.run_installer('install', success=False, MOCK_RESTART_CORRUPT_RECORD='1')
        self.assertEqual(self.record.read_bytes(), b'unknown changed journal')
        self.assertEqual(self.target.read_bytes(), self.repairs['factory'])
        self.run_installer('restore', success=False)

    def test_restore_rejects_changed_metadata_or_backup(self):
        self.install(); self.target.chmod(0o600)
        self.run_installer('restore', success=False)
        self.target.chmod(0o777); self.backup.write_bytes(b'unknown changed backup')
        self.run_installer('restore', success=False)
        self.assertEqual(self.backup.read_bytes(), b'unknown changed backup')

    def test_existing_lock_is_preserved(self):
        self.lock.mkdir(); marker = self.lock / 'unknown'; marker.write_text('preserve')
        self.run_installer('install', success=False)
        self.assertEqual(marker.read_text(), 'preserve'); self.assertFalse(self.record.exists())


if __name__ == '__main__': unittest.main()
