import importlib.util
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "extract-unified-setup-snapshot.py"
SPEC = importlib.util.spec_from_file_location("unified_snapshot", SCRIPT)
snapshot = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(snapshot)


class UnifiedSnapshotTests(unittest.TestCase):
    def preferences(self, value):
        root = ET.Element("map")
        ET.SubElement(root, "string", name="snapshot").text = json.dumps(value, ensure_ascii=False)
        return ET.tostring(root, encoding="utf-8", xml_declaration=True)

    def test_preserves_live_stage_unicode_and_actual_flags(self):
        parsed = snapshot.extract_snapshot(self.preferences({
            "state": "preparing", "ready": False, "currentStep": "jpeg", "message": "1280 × 720 & YUV",
            "components": {"root": {"verified": True, "code": "GUEST_ROOT_VERIFIED"}},
        }))
        self.assertEqual("1280 × 720 & YUV", parsed["message"])
        self.assertFalse(parsed["ready"])
        self.assertTrue(snapshot.summary(parsed)["root"]["verified"])

    def test_recursive_redaction_omits_media_and_credentials(self):
        parsed = snapshot.extract_snapshot(self.preferences({
            "state": "ready", "sourceUri": "content://private/photo", "accessToken": "secret",
            "components": {"broker": {"proof": {"sourceSha256": "personal-digest", "proxyPid": 123}}},
            "message": "file:///sdcard/secret.jpg FILE:///data/user/0/com.virtualcamerapro/files/source-private.media "
                       "/data/user/0/com.virtualcamerapro/files/source-123.media",
        }))
        text = json.dumps(parsed)
        for private in ("content://", "file://", "FILE://", "personal-digest", "secret", "sourceSha256", "source-123", "/data/user/0"):
            self.assertNotIn(private, text)
        self.assertEqual(123, parsed["components"]["broker"]["proof"]["proxyPid"])

    def test_summary_preserves_actual_authorization_failure_beside_read_only_root_state(self):
        parsed = snapshot.extract_snapshot(self.preferences({
            "state": "root_required", "components": {"root": {
                "verified": False, "code": "GUEST_ROOT_NOT_CHECKED",
                "lastAuthorizationCheck": {"verified": False, "code": "VMOS_ROOT_BOOTSTRAP_MISSING",
                                           "checkedAt": 100, "completedAt": 200,
                                           "authorizationBridge": {"launched": False, "reason": "No matching request"}},
            }},
        }))
        root = snapshot.summary(parsed)["root"]
        self.assertEqual("GUEST_ROOT_NOT_CHECKED", root["code"])
        self.assertEqual("VMOS_ROOT_BOOTSTRAP_MISSING", root["lastAuthorizationCheck"]["code"])
        self.assertEqual(200, root["lastAuthorizationCheck"]["completedAt"])
        self.assertFalse(root["lastAuthorizationCheck"]["authorizationBridge"]["launched"])

    def test_rejects_missing_duplicate_and_wrong_snapshot_shapes(self):
        invalid = [b"<map />", b"<wrong />", b'<map><string name="snapshot">[]</string></map>',
                   b'<map><string name="snapshot">{}</string><string name="snapshot">{}</string></map>']
        for data in invalid:
            with self.subTest(data=data), self.assertRaises(ValueError):
                snapshot.extract_snapshot(data)

    def test_bounds_untrusted_input_size(self):
        with self.assertRaises(ValueError):
            snapshot.extract_snapshot(b" " * (snapshot.MAX_INPUT_BYTES + 1))


if __name__ == "__main__":
    unittest.main()
