import hashlib
import tempfile
import unittest
from pathlib import Path

import deploy_vps_server_mod


class ServerModPublisherTest(unittest.TestCase):
    def test_sha256_matches_standard_library(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "server.jar"
            artifact.write_bytes(b"fabric-server-hotfix")
            self.assertEqual(
                deploy_vps_server_mod.sha256(artifact),
                hashlib.sha256(b"fabric-server-hotfix").hexdigest(),
            )

    def test_remote_script_has_atomic_safety_contract(self):
        script = deploy_vps_server_mod.remote_deploy_script()
        self.assertNotIn("\r", script)
        for required in (
            "server-mod-pre-publish-",
            "deployment failed; restoring previous server mod",
            "systemctl stop eclipse-rp",
            "systemctl start eclipse-rp",
            "Done (",
            "InvalidAccessorException",
            ":25565",
            ":25580",
            "SERVER_MOD_DEPLOY_OK",
        ):
            self.assertIn(required, script)

    def test_server_deploy_precedes_public_mirrors(self):
        publisher = (Path(__file__).parent / "publish_update.py").read_text(encoding="utf-8")
        fabric_build = publisher.index('repo / "fabric-server" / "build.ps1"')
        distribution = publisher.index("build_distribution.py")
        deploy = publisher.index("deploy_vps_server_mod.py")
        r2 = publisher.index("upload_r2_distribution.py")
        vps = publisher.index("upload_vps_distribution.py")
        self.assertLess(fabric_build, distribution)
        self.assertLess(distribution, deploy)
        self.assertLess(deploy, r2)
        self.assertLess(deploy, vps)

    def test_build_script_targets_authoritative_fabric_tree(self):
        build_script = (
            Path(__file__).parents[1] / "fabric-server" / "build.ps1"
        ).read_text(encoding="utf-8-sig")
        self.assertIn('$workspace = $PSScriptRoot', build_script)
        self.assertNotIn('$workspace = Split-Path -Parent $PSScriptRoot', build_script)


if __name__ == "__main__":
    unittest.main()
