import hashlib
import tempfile
import unittest
from pathlib import Path

from build_distribution import descriptor


class DistributionDescriptorTest(unittest.TestCase):
    def test_managed_asset_url_contains_content_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            asset = Path(temp_dir) / "eclipse-client.jar"
            asset.write_bytes(b"new-client-build")

            result = descriptor(
                asset,
                "client/mods/eclipse-client.jar",
                "https://dist.example",
                cache_bust=True,
            )

        expected_hash = hashlib.sha256(b"new-client-build").hexdigest()
        self.assertEqual(result["sha256"], expected_hash)
        self.assertEqual(
            result["url"],
            f"https://dist.example/client/mods/eclipse-client.jar?sha256={expected_hash}",
        )

    def test_versioned_filename_can_keep_plain_url(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            installer = Path(temp_dir) / "launcher-1.2.3.exe"
            installer.write_bytes(b"installer")
            result = descriptor(
                installer,
                "launcher/stable/launcher-1.2.3.exe",
                "https://dist.example/",
            )

        self.assertEqual(
            result["url"],
            "https://dist.example/launcher/stable/launcher-1.2.3.exe",
        )


if __name__ == "__main__":
    unittest.main()
