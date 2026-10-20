import base64
import os
import tempfile
import unittest

from inventori_server import InventoriService, Settings, extract_output_text


class InventoriServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.settings = Settings(
            db_path=os.path.join(self.temp.name, "usage.sqlite3"),
            token_secret="test-secret",
            monthly_scan_limit=2,
            mock_openai=True,
        )
        self.service = InventoriService(self.settings)
        self.install_id = "test-install-1234"
        self.registration = self.service.register(self.install_id)
        self.image = "data:image/jpeg;base64," + base64.b64encode(b"fake-image").decode("ascii")

    def tearDown(self):
        self.temp.cleanup()

    def payload(self, expected_items=1):
        return {
            "install_id": self.install_id,
            "install_token": self.registration["install_token"],
            "workspace_type": "home",
            "expected_items": expected_items,
            "image_data_url": self.image,
        }

    def test_successful_scan_is_metered(self):
        status, result = self.service.analyze(self.payload())
        self.assertEqual(200, status)
        self.assertTrue(result["charged"])
        self.assertEqual(1, result["scans_used"])
        self.assertEqual(1, result["scans_remaining"])
        self.assertEqual(1, len(result["items"]))

    def test_multiple_items_still_cost_one_scan(self):
        status, result = self.service.analyze(self.payload(expected_items=8))
        self.assertEqual(200, status)
        self.assertEqual(2, len(result["items"]))
        self.assertEqual(1, result["scans_used"])

    def test_quota_blocks_third_successful_scan(self):
        self.assertEqual(200, self.service.analyze(self.payload())[0])
        self.assertEqual(200, self.service.analyze(self.payload())[0])
        status, result = self.service.analyze(self.payload())
        self.assertEqual(429, status)
        self.assertEqual(0, result["scans_remaining"])

    def test_invalid_token_rejected(self):
        payload = self.payload()
        payload["install_token"] = "wrong"
        status, _ = self.service.analyze(payload)
        self.assertEqual(401, status)

    def test_output_text_extraction(self):
        raw = {
            "output": [
                {
                    "content": [
                        {"type": "output_text", "text": '{"items":[]}'}
                    ]
                }
            ]
        }
        self.assertEqual('{"items":[]}', extract_output_text(raw))


if __name__ == "__main__":
    unittest.main()
