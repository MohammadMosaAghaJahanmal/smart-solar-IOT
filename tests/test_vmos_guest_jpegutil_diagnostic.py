"""Exercise the production installer offline with real library bytes.

Only guest identity, virtual root metadata/SELinux labels, and Android's am are
mocked. Hashes, file contents, permissions, hard links and renames are real.
"""
import hashlib
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
SCRIPT = ROOT / "scripts/vmos-guest-jpegutil-diagnostic.sh"
ORIGINAL = ROOT / "artifacts/vmos/research/stock-camera-public/system/lib64/libjni_jpegutil.so"
SHIM = ROOT / "artifacts/vmos/jpegutil-diagnostic/libjni_jpegutil.so"


@unittest.skipUnless(os.name == "posix" and ORIGINAL.is_file() and SHIM.is_file(),
                     "Requires Linux/WSL and the pinned original plus built diagnostic library")
class JpegutilDiagnosticInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="vcam-jpegutil-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "mock-bin"
        self.bin.mkdir()
        self.target = self.path("/system/lib64/libjni_jpegutil.so")
        self.backup = self.target.with_name(".vcam-original-libjni_jpegutil.so")
        self.candidate = self.path("/data/local/tmp/vcam-jpegutil-diagnostic.so")
        self.record = self.path("/data/local/tmp/vcam-jpegutil-diagnostic-v1.txt")
        self.lock = self.target.with_name(".vcam-jpegutil-diagnostic-lock")
        self.jlock = self.record.with_name(".vcam-jpegutil-diagnostic-journal-lock")
        self.target.parent.mkdir(parents=True)
        self.candidate.parent.mkdir(parents=True)
        shutil.copyfile(ORIGINAL, self.target)
        shutil.copyfile(SHIM, self.candidate)
        self.target.chmod(0o644)
        self.candidate.chmod(0o644)
        self.metadata = self.root / "metadata.json"
        self.metadata.write_text("{}")
        self.environment = dict(os.environ, MOCK_ROOT=str(self.root), MOCK_TARGET=str(self.target),
                                MOCK_RECORD=str(self.record), MOCK_CANDIDATE=str(self.candidate))
        self.mock("id", '#!/bin/sh\necho "${MOCK_UID:-0}"\n')
        self.mock("getenforce", '#!/bin/sh\necho "${MOCK_SELINUX:-Disabled}"\n')
        self.mock("getprop", '''#!/bin/sh
case "$1" in
ro.hardware) echo "${MOCK_HARDWARE:-vphw90}";;
ro.build.version.sdk) echo "${MOCK_SDK:-28}";;
ro.product.cpu.abilist) echo "${MOCK_ABI:-arm64-v8a}";;
ro.build.fingerprint) echo "${MOCK_FINGERPRINT:-vmos/vphw90/vphw90:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys}";;
esac
''')
        prelude = '''#!/usr/bin/python3
import json, os, pathlib, stat, subprocess, sys
r = pathlib.Path(os.environ['MOCK_ROOT'])
m = r / 'metadata.json'
data = json.loads(m.read_text())
'''
        self.mock("stat", prelude + '''
fmt, p = sys.argv[2:4]
s = pathlib.Path(p).stat()
d = data.get(p, {})
uid, gid = d.get('uid', 0), d.get('gid', 0)
if p == os.environ['MOCK_CANDIDATE']: uid = int(os.environ.get('MOCK_CANDIDATE_UID', uid))
values = {'%u': str(uid), '%g': str(gid), '%h': str(s.st_nlink), '%s': str(s.st_size),
          '%a': format(stat.S_IMODE(s.st_mode), 'o'),
          '%a:%u:%g': format(stat.S_IMODE(s.st_mode), 'o') + ':' + str(uid) + ':' + str(gid)}
print(values[fmt])
''')
        self.mock("ls", prelude + '''
p = sys.argv[-1]
assert pathlib.Path(p).exists()
print(data.get(p, {}).get('label', '?') + ' ' + p)
''')
        self.mock("chown", prelude + '''
uid, gid = map(int, sys.argv[1].split(':'))
p = sys.argv[2]
d = data.setdefault(p, {})
d.update(uid=uid, gid=gid)
m.write_text(json.dumps(data))
''')
        self.mock("chcon", prelude + '''
if os.environ.get('MOCK_CHCON_FAIL') == '1': raise SystemExit(1)
label, p = sys.argv[1:3]
data.setdefault(p, {})['label'] = label
m.write_text(json.dumps(data))
''')
        self.mock("mv", prelude + '''
args = sys.argv[1:]
source, destination = args[-2:]
if source.endswith('/shim.tmp') and os.environ.get('MOCK_PUBLISH_FAIL') == '1': raise SystemExit(1)
if source.endswith('/shim.tmp') and os.environ.get('MOCK_CORRUPT_ON_PUBLISH') == '1':
    pathlib.Path(source).write_bytes(b'unexpected bytes')
# Refuse cross-filesystem staging in the test, even though both are under /tmp.
assert pathlib.Path(source).relative_to(r).parts[0] == pathlib.Path(destination).relative_to(r).parts[0]
result = subprocess.call(['/bin/mv'] + args)
if result == 0 and not pathlib.Path(source).exists():
    data[destination] = data.pop(source, {})
    m.write_text(json.dumps(data))
raise SystemExit(result)
''')
        self.mock("am", prelude + '''
with (r / 'am.log').open('a') as f: f.write(' '.join(sys.argv[1:]) + '\\n')
assert sys.argv[1:] == ['force-stop', '--user', '0', 'com.android.camera2']
if os.environ.get('MOCK_AM_CORRUPT_TARGET') == '1':
    pathlib.Path(os.environ['MOCK_TARGET']).write_bytes(b'unknown mutation: preserve')
if os.environ.get('MOCK_AM_CORRUPT_RECORD') == '1':
    pathlib.Path(os.environ['MOCK_RECORD']).write_bytes(b'unknown journal: preserve')
raise SystemExit(1 if os.environ.get('MOCK_AM_FAIL') == '1' else 0)
''')
        # Path substitution is confined to this disposable test copy.
        text = SCRIPT.read_text()
        text = text.replace('PATH=/system/bin:/system/xbin', 'PATH=' + str(self.bin) + ':/usr/bin:/bin')
        for prefix in ('/system', '/data'):
            text = text.replace(prefix, str(self.root) + prefix)
        self.script = self.root / "installer.sh"
        self.script.write_text(text)

    def path(self, value):
        return self.root / value.lstrip('/')

    def mock(self, name, text):
        p = self.bin / name
        p.write_text(text)
        p.chmod(0o755)

    def run_installer(self, action, success=True, **environment):
        result = subprocess.run(['/bin/sh', str(self.script), action], text=True, capture_output=True,
                                env=dict(self.environment, **environment), timeout=30)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def install(self):
        self.run_installer('install')
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())
        self.assertEqual(self.backup.read_bytes(), ORIGINAL.read_bytes())

    def test_release_pins_and_shell_syntax(self):
        text = SCRIPT.read_text()
        digest = re.search(r'^shim_hash=([a-f0-9]{64})$', text, re.M).group(1)
        size = int(re.search(r'^shim_size=(\d+)$', text, re.M).group(1))
        self.assertEqual(digest, hashlib.sha256(SHIM.read_bytes()).hexdigest())
        self.assertEqual(size, SHIM.stat().st_size)
        self.assertEqual(hashlib.sha256(ORIGINAL.read_bytes()).hexdigest(),
                         '6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5')
        subprocess.run(['/bin/sh', '-n', str(SCRIPT)], check=True)

    def test_install_restore_preserves_original_metadata_and_backup(self):
        label = 'u:object_r:system_file:s0'
        self.metadata.write_text(json.dumps({str(p): {'gid': 1000, 'label': label}
                                            for p in (self.target, self.candidate)}))
        self.target.chmod(0o640)
        self.candidate.chmod(0o640)
        self.install()
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
        self.assertIn('mode=640\nuid=0\ngid=1000\nlabel=' + label, self.record.read_text())
        self.run_installer('restore')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
        self.assertEqual(self.backup.read_bytes(), ORIGINAL.read_bytes())
        for p in (self.target, self.backup):
            self.assertEqual(stat.S_IMODE(p.stat().st_mode), 0o640)
            self.assertEqual(json.loads(self.metadata.read_text())[str(p)],
                             {'uid': 0, 'gid': 1000, 'label': label})
        self.assertFalse(self.record.exists())
        self.assertFalse(self.lock.exists())
        self.assertFalse(self.jlock.exists())
        self.assertEqual((self.root / 'am.log').read_text().splitlines(),
                         ['force-stop --user 0 com.android.camera2'] * 2)

    def test_inspection_does_not_write(self):
        self.run_installer('inspect')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
        self.assertFalse(self.backup.exists())
        self.assertFalse(self.record.exists())
        self.assertFalse(self.lock.exists())
        self.assertFalse((self.root / 'am.log').exists())

    def test_measured_vmos_777_mode_is_preserved(self):
        self.target.chmod(0o777)
        self.candidate.chmod(0o777)
        self.install()
        self.assertIn('mode=777\n', self.record.read_text())
        self.run_installer('restore')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o777)
        self.assertEqual(stat.S_IMODE(self.backup.stat().st_mode), 0o777)

    def test_refuse_unknown_guest_without_mutation(self):
        for key, value in [('MOCK_UID', '2000'), ('MOCK_SDK', '29'), ('MOCK_HARDWARE', 'unknown'),
                           ('MOCK_ABI', 'armeabi-v7a'), ('MOCK_FINGERPRINT', 'other'),
                           ('MOCK_SELINUX', 'Enforcing')]:
            with self.subTest(key=key):
                self.run_installer('install', success=False, **{key: value})
                self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
                self.assertFalse(self.backup.exists())
                self.assertFalse(self.record.exists())

    def test_refuse_unpinned_or_wrong_mode_candidate(self):
        self.candidate.write_bytes(b'untrusted shim')
        self.run_installer('install', success=False)
        shutil.copyfile(SHIM, self.candidate)
        self.candidate.chmod(0o755)
        self.run_installer('install', success=False)
        self.candidate.chmod(0o644)
        self.run_installer('install', success=False, MOCK_CANDIDATE_UID='2000')
        self.assertFalse(self.backup.exists())

    def test_refuse_symlink_and_hardlinked_candidate(self):
        self.candidate.unlink()
        self.candidate.symlink_to(SHIM)
        self.run_installer('install', success=False)
        self.candidate.unlink()
        shutil.copyfile(SHIM, self.candidate)
        self.candidate.chmod(0o644)
        os.link(self.candidate, self.candidate.with_suffix('.alias'))
        self.run_installer('install', success=False)
        self.assertFalse(self.backup.exists())

    def test_preserve_unknown_backup(self):
        self.backup.write_bytes(b'unknown backup')
        self.run_installer('install', success=False)
        self.assertEqual(self.backup.read_bytes(), b'unknown backup')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())

    def test_preserve_unknown_journal_without_executing(self):
        self.record.write_text('$(touch ' + str(self.root / 'executed') + ')\n')
        self.record.chmod(0o600)
        self.run_installer('restore', success=False)
        self.assertFalse((self.root / 'executed').exists())
        self.assertTrue(self.record.exists())

    def test_backup_symlink_and_target_symlink_are_refused(self):
        self.backup.symlink_to(self.target)
        self.run_installer('install', success=False)
        self.assertTrue(self.backup.is_symlink())
        self.backup.unlink()
        self.target.unlink()
        self.target.symlink_to(ORIGINAL)
        self.run_installer('install', success=False)
        self.assertTrue(self.target.is_symlink())

    def test_force_stop_failure_rolls_back_known_target(self):
        self.run_installer('install', success=False, MOCK_AM_FAIL='1')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
        self.assertTrue(self.record.is_file())
        self.run_installer('restore')
        self.assertFalse(self.record.exists())

    def test_publication_failure_retains_recovery_record(self):
        self.run_installer('install', success=False, MOCK_PUBLISH_FAIL='1')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
        self.assertTrue(self.record.is_file())
        self.run_installer('restore')

    def test_unknown_target_is_preserved_after_failed_installation(self):
        self.run_installer('install', success=False, MOCK_AM_CORRUPT_TARGET='1')
        self.assertEqual(self.target.read_bytes(), b'unknown mutation: preserve')
        self.assertTrue(self.record.exists())
        self.run_installer('restore', success=False)
        self.assertEqual(self.target.read_bytes(), b'unknown mutation: preserve')

    def test_unknown_journal_is_preserved_and_blocks_rollback(self):
        self.run_installer('install', success=False, MOCK_AM_CORRUPT_RECORD='1')
        self.assertEqual(self.record.read_bytes(), b'unknown journal: preserve')
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())
        self.run_installer('restore', success=False)
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())

    def test_existing_lock_is_preserved(self):
        self.lock.mkdir()
        marker = self.lock / 'preserve'
        marker.write_text('unknown')
        self.run_installer('install', success=False)
        self.assertEqual(marker.read_text(), 'unknown')
        self.assertFalse(self.record.exists())

    def test_restore_refuses_changed_metadata_and_backup(self):
        self.install()
        self.target.chmod(0o600)
        self.run_installer('restore', success=False)
        self.target.chmod(0o644)
        self.backup.write_bytes(b'unknown modified backup')
        self.run_installer('restore', success=False)
        self.assertEqual(self.backup.read_bytes(), b'unknown modified backup')

    def test_restore_force_stop_failure_retains_exact_original_and_journal(self):
        self.install()
        self.run_installer('restore', success=False, MOCK_AM_FAIL='1')
        self.assertEqual(self.target.read_bytes(), ORIGINAL.read_bytes())
        self.assertTrue(self.record.exists())
        self.run_installer('restore')
        self.assertFalse(self.record.exists())


if __name__ == '__main__':
    unittest.main()
