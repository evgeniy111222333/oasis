"""Verify signed mirror manifests and downloaded carry client bytes."""
import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PUBLIC_KEY = "PBxj9O40kIzFg4kqIEg3DDmdGSCjqMdp2FURmfhay/M="


def fetch(url, timeout=35):
    separator = "&" if "?" in url else "?"
    request = urllib.request.Request(url + separator + "verify=" + str(time.time_ns()), headers={
        "User-Agent": "Eclipse-Release-Verification", "Cache-Control": "no-cache",
    })
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def verify(name, base, expected):
    attempts = 1 if name == "vps" else 3
    for attempt in range(attempts):
        try:
            manifest_bytes = fetch(base + "/manifests/production.json", 20 if name == "vps" else 35)
            manifest = json.loads(manifest_bytes)
            if manifest["release"]["id"] != expected["release"]["id"]:
                raise ValueError("Mirror serves another release: " + manifest["release"]["id"])
            saved = HERE / ("published-carry-manifest-" + name + ".json")
            saved.write_bytes(manifest_bytes)
            verifier = (
                "const fs=require('fs'),v=require(process.argv[1]);"
                "const m=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));"
                "const r=v.verifyManifestSignature(m,process.argv[3]);"
                "if(!r.ok)throw new Error(r.reason);"
            )
            subprocess.run(["node", "-e", verifier, str(ROOT / "launcher/manifest-signing.js"),
                            str(saved), PUBLIC_KEY], check=True, capture_output=True, text=True)
            target = next(item for item in manifest["client"]["mods"] if item["name"] == "eclipse-client-1.5.68.jar")
            local = next(item for item in expected["client"]["mods"] if item["name"] == target["name"])
            assert target["sha256"] == local["sha256"] and target["size"] == local["size"]
            artifact = ROOT / "fabric-server/client-mod-gradle/build/libs/client-1.5.68.jar"
            assert hashlib.sha256(artifact.read_bytes()).hexdigest() == target["sha256"]
            data = fetch(base + "/client/" + target["path"])
            assert len(data) == target["size"] and hashlib.sha256(data).hexdigest() == target["sha256"]
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                metadata = json.loads(jar.read("fabric.mod.json"))
                assert metadata["id"] == "eclipseclient" and metadata["version"] == "1.5.68"
                for member in ["carry/CarryClient", "carry/CarryFeedback", "carry/CarryPoseRenderer",
                               "carry/CarryWorldFeedback", "mixin/carry/CarryDisplayRendererMixin",
                               "animation/FirstPersonCameraAnchor"]:
                    assert "ua/rp/chat/client/" + member + ".class" in jar.namelist(), member
                asset = jar.read("assets/eclipseclient/textures/gui/carry/concept_01.png")
                assert hashlib.sha256(asset).hexdigest() == "38b6681d69f0ab29a6b145910eadc3459362673467105f0c0d247b9e77d7dace"
            return {"mirror": name, "ok": True, "release": manifest["release"]["id"],
                    "client": target["name"], "signature": "valid", "size": len(data),
                    "sha256": target["sha256"], "carry_and_camera_classes_present": True,
                    "concept_01_unchanged": True}
        except Exception as error:
            last_error = str(error) or type(error).__name__
            if attempt + 1 < attempts:
                time.sleep(2)
    return {"mirror": name, "ok": False, "error": last_error}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--github-commit", required=True)
    args = parser.parse_args()
    assert len(args.github_commit) == 40 and all(c in "0123456789abcdef" for c in args.github_commit)
    expected = json.loads((ROOT / "distribution-build/manifests/production.json").read_text(encoding="utf-8"))
    mirrors = {
        "r2": "https://dist.eclipse-roleplay.online",
        "github": "https://raw.githubusercontent.com/evgeniy111222333/oasis/" + args.github_commit,
        "vps": "https://api.eclipse-roleplay.online/dist",
    }
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(verify, name, base, expected) for name, base in mirrors.items()]
        report = [future.result() for future in futures]
    (HERE / "published-carry-verification.json").write_text(
        json.dumps({"github_commit": args.github_commit, "mirrors": report}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if any(not item["ok"] for item in report if item["mirror"] in ("r2", "github")):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
