"""Isolated publication validation; never writes real workspace manifests/APKs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('vmos_manifest_publisher',
                                            ROOT / 'scripts/publish-vmos-build-manifest.py')
PUBLISHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISHER)


def sha(data):
    return hashlib.sha256(data).hexdigest()


class PublishVmosBuildManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='vcam-publish-test-')
        self.addCleanup(self.temp.cleanup)
        # Match the CLI's normalized root, including Windows TEMP's 8.3 alias.
        self.root = Path(self.temp.name).resolve()
        self.artifacts = self.root / 'artifacts'
        self.proxy = b'fixture native proxy bytes'
        self.wrapper = b'fixture camera crop wrapper bytes'
        self.proxy_patch = mock.patch.object(PUBLISHER, 'PROXY_SHA', sha(self.proxy))
        self.wrapper_patch = mock.patch.object(PUBLISHER, 'WRAPPER_SHA', sha(self.wrapper))
        self.proxy_patch.start(); self.wrapper_patch.start()
        self.addCleanup(self.proxy_patch.stop); self.addCleanup(self.wrapper_patch.stop)
        self.top_path = self.artifacts / 'BUILD-MANIFEST.json'
        self.guest_path = self.artifacts / 'vmos/broker-build-manifest.json'
        self.old_top = {
            'generatedAtUtc': '2000-01-01T00:00:00Z',
            'files': {'VirtualCameraPro.apk': {'bytes':88, 'sha256':'old-host'},
                      'VirtualCameraPro-emulator-verified.apk': {'bytes':99, 'sha256':'old-emulator'},
                      'unrelated.bin': {'bytes':4, 'sha256':'kept'}},
            'physicalDeviceVerification': {'virtualCameraVerified':False, 'controllerSha256':'old-host',
                                           'nested':{'report':'physical-device/report.json'}},
            'emulatorVerification': {'acceptancePassed':True, 'controllerSha256':'old-emulator',
                                    'nested':{'cameraId':'10'}},
            'unrelatedMetadata': {'preserve':[1, 2, {'value':True}]},
        }
        self.old_guest = {'schemaVersion':1, 'historicalMetadata':{'preserve':'original record'},
                          'files':{'old.apk':{'bytes':1, 'sha256':'old'}}}
        self.write_json(self.top_path, self.old_top)
        self.write_json(self.guest_path, self.old_guest)
        self.unrelated = self.artifacts / 'unrelated.bin'
        self.unrelated.write_bytes(b'kept')
        for name in PUBLISHER.FILES:
            path = self.artifacts / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if name.endswith('.apk'):
                self.write_apk(path, [('classes.dex', ('fixture-' + name).encode())])
            else:
                path.write_bytes(('fixture-' + name).encode())
        (self.artifacts / 'vmos/vmos-camera-proxy').write_bytes(self.proxy)
        (self.artifacts / 'vmos/camera.vphw90.crop-wrapper.so').write_bytes(self.wrapper)
        source_proxy = self.root / 'android/guest-tools/src/main/assets/vmos-camera-proxy'
        source_proxy.parent.mkdir(parents=True)
        source_proxy.write_bytes(self.proxy)
        self.broker_path = self.artifacts / 'vmos/VirtualCameraProVmosBroker.apk'
        self.broker_entries = [
            ('classes.dex', b'fixture broker'),
            ('assets/vmos-camera-proxy', self.proxy),
            ('assets/vmos-camera-proxy.sha256', (sha(self.proxy) + '\n').encode()),
            ('assets/camera.vphw90.crop-wrapper.so', self.wrapper),
            ('assets/camera.vphw90.crop-wrapper.so.sha256', (sha(self.wrapper) + '\n').encode()),
        ]
        self.write_apk(self.broker_path, self.broker_entries)
        self.tools_path = self.artifacts / 'VirtualCameraProGuestTools.apk'
        self.write_apk(self.tools_path, [('classes.dex', b'fixture tools'),
                                         ('assets/vmos-camera-proxy', self.proxy)])
        self.alias_path = self.artifacts / 'VcamGuestDiagnostics.apk'
        self.alias_path.write_bytes(self.tools_path.read_bytes())
        self.controller_path = self.artifacts / 'vmos/VirtualCameraProVmosController.apk'
        self.write_apk(self.controller_path, [('classes.dex', b'fixture controller'),
                                              ('assets/index.android.bundle', b'fixture standalone JS')])
        self.controller_alias_path = self.artifacts / 'VirtualCameraPro.apk'
        self.controller_alias_path.write_bytes(self.controller_path.read_bytes())
        for name in PUBLISHER.EVIDENCE:
            path = self.artifacts / name
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.suffix == '.json':
                self.write_json(path, {'passed':True, 'stage':'complete'})
            else:
                path.write_text('fixture completed camera evidence\n')
        for directory, camera in [('camera2-crop-back-sampled', '0'), ('camera2-crop-video', '1')]:
            self.write_json(self.artifacts / f'vmos/{directory}/crop-result.json', self.crop(camera))
        self.write_json(self.artifacts / 'vmos/crop-video-activation.json',
                        {'type':'video', 'phase':'active', 'cameraId':'1', 'proof':{'sourceVerified':True}})
        self.before = self.manifests()

    @staticmethod
    def write_json(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

    @staticmethod
    def write_apk(path, entries):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)  # Deliberate duplicate ZIP fixture.
            with zipfile.ZipFile(path, 'w') as archive:
                for name, value in entries:
                    entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                    archive.writestr(entry, value)

    @staticmethod
    def crop(camera):
        return {
            'passed':True, 'cameraTestExecuted':True, 'cameraId':camera,
            'cameraOpens':1, 'sessionConfigurations':1, 'cameraControlClosed':True,
            'imageReaderClosed':True, 'baselineTimestampNs':100,
            'width':1280, 'height':720, 'sampleGrid':[20, 12],
            'observations':[{'name':name, 'stableMatchingFrames':3, 'lastChannelMae':0.25,
                             'actualTimestampNs':200 + i * 100, 'updateToObservedMs':25 + i}
                            for i, name in enumerate(['center-2x', 'top-left-2x',
                                                       'bottom-right-2x', 'tall-center', 'restored-full'])],
        }

    def manifests(self):
        return self.top_path.read_bytes(), self.guest_path.read_bytes()

    def all_files(self):
        return {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()}

    def assert_refused_without_publication(self, **kwargs):
        with self.assertRaises((ValueError, OSError, KeyError, zipfile.BadZipFile)):
            PUBLISHER.publish(self.root, **kwargs)
        self.assertEqual(self.manifests(), self.before)
        self.assertFalse(list(self.artifacts.rglob('.vcam-manifest-*.tmp')))

    def identity(self, **updates):
        value = {'passed':True, 'finalRuntimeRetestPassed':True,
                 'packageApkSha256':{name:sha((self.artifacts / name).read_bytes()) for name in (
                     'vmos/VirtualCameraProVmosController.apk', 'vmos/VirtualCameraProVmosBroker.apk',
                     'VirtualCameraProGuestTools.apk')}}
        value.update(updates)
        path = self.artifacts / 'vmos/final-device-identity.json'
        self.write_json(path, value)
        return path

    def test_valid_publication_merges_and_preserves_old_proofs_and_unrelated_files(self):
        files_before = self.all_files()
        guest, top = PUBLISHER.publish(self.root)
        for key in ('physicalDeviceVerification', 'emulatorVerification', 'unrelatedMetadata'):
            self.assertEqual(top[key], self.old_top[key])
        self.assertEqual(top['files']['unrelated.bin'], self.old_top['files']['unrelated.bin'])
        self.assertEqual(top['files']['VirtualCameraPro-emulator-verified.apk'],
                         self.old_top['files']['VirtualCameraPro-emulator-verified.apk'])
        self.assertEqual(top['files']['VirtualCameraPro.apk'],
                         top['files']['vmos/VirtualCameraProVmosController.apk'])
        self.assertNotEqual(top['files']['VirtualCameraPro.apk'], self.old_top['files']['VirtualCameraPro.apk'])
        self.assertEqual(top['physicalDeviceVerification']['controllerSha256'], 'old-host')
        self.assertEqual(top['emulatorVerification']['controllerSha256'], 'old-emulator')
        self.assertEqual(guest['historicalMetadata'], self.old_guest['historicalMetadata'])
        self.assertEqual(guest['generatedAtUtc'], top['generatedAtUtc'])
        self.assertEqual(guest['verification'], top['vmosVerification'])
        self.assertEqual(set(guest['files']), set(PUBLISHER.FILES))
        self.assertTrue(top['vmosVerification']['canonicalApkPackagingVerified'])
        self.assertFalse(top['vmosVerification']['canonicalApkSetInstalledAndRetested'])
        self.assertFalse(top['vmosVerification']['otherDevicesAndAndroidVersionsVerified'])
        for name in ('vmos/final-before-cold-start.txt', 'vmos/final-cold-start.txt'):
            self.assertEqual(top['vmosVerification']['evidence'][name]['sha256'],
                             sha((self.artifacts / name).read_bytes()))
        self.assertEqual(guest, json.loads(self.guest_path.read_text()))
        self.assertEqual(top, json.loads(self.top_path.read_text()))
        files_after = self.all_files()
        for name, value in files_before.items():
            if name not in ('artifacts/BUILD-MANIFEST.json', 'artifacts/vmos/broker-build-manifest.json'):
                self.assertEqual(files_after[name], value, name)

    def test_check_only_validates_without_any_writes(self):
        before = self.all_files()
        guest, top = PUBLISHER.publish(self.root, check_only=True)
        self.assertTrue(guest['verification']['brokerNativeAssetsAndHashSidecarsVerified'])
        self.assertEqual(self.all_files(), before)
        self.assertEqual(top['emulatorVerification'], self.old_top['emulatorVerification'])

    def test_each_native_asset_rejects_same_size_stale_bytes(self):
        for asset in ('vmos-camera-proxy', 'camera.vphw90.crop-wrapper.so'):
            with self.subTest(asset=asset):
                entries = [(n, bytes([value[0] ^ 1]) + value[1:] if n == 'assets/' + asset else value)
                           for n, value in self.broker_entries]
                self.write_apk(self.broker_path, entries)
                self.assert_refused_without_publication()

    def test_missing_broker_asset_or_sidecar_is_refused(self):
        for asset in ('assets/vmos-camera-proxy', 'assets/camera.vphw90.crop-wrapper.so',
                      'assets/vmos-camera-proxy.sha256', 'assets/camera.vphw90.crop-wrapper.so.sha256'):
            with self.subTest(asset=asset):
                self.write_apk(self.broker_path, [(n,v) for n,v in self.broker_entries if n != asset])
                self.assert_refused_without_publication()

    def test_duplicate_native_asset_or_sidecar_is_refused(self):
        for asset in ('assets/vmos-camera-proxy', 'assets/camera.vphw90.crop-wrapper.so',
                      'assets/vmos-camera-proxy.sha256', 'assets/camera.vphw90.crop-wrapper.so.sha256'):
            with self.subTest(asset=asset):
                duplicate = next((n,v) for n,v in self.broker_entries if n == asset)
                self.write_apk(self.broker_path, self.broker_entries + [duplicate])
                self.assert_refused_without_publication()

    def test_bad_hash_sidecar_wrong_digest_crlf_or_no_newline_is_refused(self):
        for text in ('0' * 64 + '\n', sha(self.proxy) + '\r\n', sha(self.proxy)):
            with self.subTest(text=text):
                self.write_apk(self.broker_path, [(n, text.encode() if n.endswith('vmos-camera-proxy.sha256') else v)
                                                for n,v in self.broker_entries])
                self.assert_refused_without_publication()

    def test_stale_canonical_proxy_or_wrapper_refused_even_when_apk_self_consistent(self):
        for relative, data in [('vmos/vmos-camera-proxy', self.proxy),
                               ('vmos/camera.vphw90.crop-wrapper.so', self.wrapper)]:
            path = self.artifacts / relative
            path.write_bytes(b'!' + data[1:])
            self.assert_refused_without_publication()
            path.write_bytes(data)

    def test_guest_tools_alias_difference_is_refused(self):
        self.alias_path.write_bytes(self.tools_path.read_bytes() + b'different alias')
        self.assert_refused_without_publication()

    def test_main_controller_and_vmos_controller_alias_mismatch_is_refused(self):
        self.controller_alias_path.write_bytes(self.controller_path.read_bytes() + b'old main controller')
        self.assert_refused_without_publication()
        self.assertEqual(json.loads(self.top_path.read_text())['physicalDeviceVerification'],
                         self.old_top['physicalDeviceVerification'])
        self.assertEqual(json.loads(self.top_path.read_text())['emulatorVerification'],
                         self.old_top['emulatorVerification'])

    def test_guest_tools_embedded_stale_proxy_is_refused(self):
        self.write_apk(self.tools_path, [('assets/vmos-camera-proxy', b'!' + self.proxy[1:])])
        self.alias_path.write_bytes(self.tools_path.read_bytes())
        self.assert_refused_without_publication()

    def test_guest_tools_source_stale_proxy_is_refused(self):
        (self.root / 'android/guest-tools/src/main/assets/vmos-camera-proxy').write_bytes(b'stale source proxy')
        self.assert_refused_without_publication()

    def test_missing_canonical_package_is_refused(self):
        (self.artifacts / 'vmos/VirtualCameraProVmosBrokerTests.apk').unlink()
        self.assert_refused_without_publication()

    def test_empty_artifact_and_invalid_zip_are_refused(self):
        self.broker_path.write_bytes(b'')
        self.assert_refused_without_publication()
        self.broker_path.write_bytes(b'not an APK ZIP')
        self.assert_refused_without_publication()

    def test_missing_empty_or_duplicate_controller_js_bundle_is_refused(self):
        for entries in ([('classes.dex', b'fixture')], [('assets/index.android.bundle', b'')],
                        [('assets/index.android.bundle', b'one'), ('assets/index.android.bundle', b'two')]):
            self.write_apk(self.controller_path, entries)
            self.controller_alias_path.write_bytes(self.controller_path.read_bytes())
            self.assert_refused_without_publication()

    def test_required_evidence_missing_or_failed_refuses_publication(self):
        proof = self.artifacts / 'vmos/finite-acceptance.json'
        proof.unlink()
        self.assert_refused_without_publication()
        self.write_json(proof, {'passed':False, 'status':'failed'})
        self.assert_refused_without_publication()

    def test_crop_false_pixels_or_stale_timestamp_is_refused(self):
        path = self.artifacts / 'vmos/camera2-crop-video/crop-result.json'
        for field, value in [('stableMatchingFrames', 2), ('lastChannelMae', 50), ('actualTimestampNs', 100)]:
            proof = self.crop('1'); proof['observations'][0][field] = value
            self.write_json(path, proof)
            self.assert_refused_without_publication()

    def test_video_source_requires_independent_verified_active_source(self):
        self.write_json(self.artifacts / 'vmos/crop-video-activation.json',
                        {'type':'video', 'phase':'active', 'cameraId':'1', 'proof':{'sourceVerified':False}})
        self.assert_refused_without_publication()

    def test_device_identity_hash_mismatch_is_refused(self):
        identity = self.identity()
        value = json.loads(identity.read_text())
        value['packageApkSha256']['vmos/VirtualCameraProVmosBroker.apk'] = '0' * 64
        self.write_json(identity, value)
        self.assert_refused_without_publication(device_identity=identity)

    def test_device_identity_must_pass_and_pin_all_three_runtime_packages(self):
        for changes in ({'passed':False}, {'packageApkSha256':{}},
                        {'packageApkSha256':{'vmos/VirtualCameraProVmosBroker.apk':sha(self.broker_path.read_bytes())}}):
            self.assert_refused_without_publication(device_identity=self.identity(**changes))

    def test_device_identity_rejects_unknown_package_or_non_apk_key(self):
        for bad_name in ('unexpected.apk', 'vmos/vmos-camera-proxy'):
            identity = self.identity()
            value = json.loads(identity.read_text())
            value['packageApkSha256'][bad_name] = sha(self.proxy)
            self.write_json(identity, value)
            self.assert_refused_without_publication(device_identity=identity)

    def test_passing_identity_preserves_exact_runtime_hashes_and_report_fingerprint(self):
        path = self.identity()
        report_before = path.read_bytes()
        guest, top = PUBLISHER.publish(self.root, device_identity=path)
        verified = guest['verification']
        self.assertTrue(verified['canonicalRuntimeApksInstalled'])
        self.assertTrue(verified['canonicalRuntimeApksRetested'])
        self.assertEqual(verified['installedPackageApkSha256'], json.loads(report_before)['packageApkSha256'])
        self.assertEqual(verified['deviceIdentity']['sha256'], sha(report_before))
        self.assertEqual(verified['deviceIdentity']['path'], 'vmos/final-device-identity.json')
        self.assertEqual(top['physicalDeviceVerification'], self.old_top['physicalDeviceVerification'])
        self.assertEqual(path.read_bytes(), report_before)

    def test_identity_without_retest_does_not_claim_retest(self):
        guest, _ = PUBLISHER.publish(self.root, check_only=True,
                                     device_identity=self.identity(finalRuntimeRetestPassed=False))
        self.assertTrue(guest['verification']['canonicalRuntimeApksInstalled'])
        self.assertFalse(guest['verification']['canonicalRuntimeApksRetested'])
        self.assertEqual(self.manifests(), self.before)

    def test_staging_write_failure_keeps_both_old_manifests_and_cleans_temporary_files(self):
        original_dump = json.dump
        calls = []

        def fail_second_stage(value, stream, **kwargs):
            calls.append(stream.name)
            if len(calls) == 2:
                raise OSError('fixture full disk during second staged manifest')
            return original_dump(value, stream, **kwargs)

        with mock.patch.object(PUBLISHER.json, 'dump', side_effect=fail_second_stage):
            self.assert_refused_without_publication()
        self.assertEqual(len(calls), 2)


if __name__ == '__main__':
    unittest.main()
