"""Inspect assembled artifacts without loading Minecraft or changing runtime mods."""
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path(__file__).with_name("artifact-inspection.json")
ASSET = "assets/eclipseclient/textures/gui/carry/concept_01.png"
ORIGINAL = ROOT / "art/pickup_highlight_concepts_2026-10-17/01_ivory_contour.png"
EXPECTED_ASSET = "38b6681d69f0ab29a6b145910eadc3459362673467105f0c0d247b9e77d7dace"
SHARED = [
    "ua/rp/chat/carry/CarryActionPayload.class",
    "ua/rp/chat/carry/CarrySyncPayload.class",
    "ua/rp/chat/carry/CarryWire.class",
    "ua/rp/chat/carry/CarryTransforms.class",
    "ua/rp/chat/carry/CarryPlacement.class",
    "ua/rp/chat/microvoxel/MicrovoxelPortableVolume.class",
]


def inspect(path, version, required):
    with ZipFile(path) as jar:
        names = jar.namelist()
        assert len(names) == len(set(names)), f"Duplicate entries: {path.name}"
        metadata = json.loads(jar.read("fabric.mod.json"))
        assert metadata["version"] == version, metadata
        for name in SHARED + required:
            assert name in names, f"Missing {name} in {path.name}"
            if name.endswith(".class"):
                assert int.from_bytes(jar.read(name)[6:8], "big") == 69, f"Expected Java 25: {name}"
        assert not any("CarryServerTest" in name or "CarryPresentationTest" in name for name in names)
        result = {
            "path": str(path), "version": metadata["version"], "mod_id": metadata["id"],
            "bytes": path.stat().st_size, "sha256": sha256(path.read_bytes()).hexdigest(),
            "entries": len(names), "required_entries_present": True,
        }
        if ASSET in names:
            asset_hash = sha256(jar.read(ASSET)).hexdigest()
            assert asset_hash == sha256(ORIGINAL.read_bytes()).hexdigest() == EXPECTED_ASSET
            entrypoints = metadata["entrypoints"]["client"]
            assert "ua.rp.chat.client.carry.CarryClient" in entrypoints
            mixins = json.loads(jar.read("eclipseclient.mixins.json"))
            configured = mixins.get("mixins", []) + mixins.get("client", [])
            for mixin in ["carry.CarryMinecraftMixin", "carry.CarryEntityRendererMixin",
                          "carry.CarryDisplayRendererMixin", "carry.CarryItemDisplayAccessor"]:
                assert mixin in configured, f"Missing registered hook: {mixin}"
            prefix = mixins["package"].replace(".", "/") + "/"
            for mixin in configured:
                assert prefix + mixin.replace(".", "/") + ".class" in names, mixin
            for locale in ["uk_ua", "ru_ru", "en_us"]:
                lang = json.loads(jar.read(f"assets/eclipseclient/lang/{locale}.json"))
                assert all(key in lang for key in ["key.eclipse.carry_menu", "key.eclipse.carry_rotate_left", "key.eclipse.carry_rotate_right"])
            result.update(original_concept_01_sha256=asset_hash, client_hooks_registered=True,
                          carry_key_locales=["uk_ua", "ru_ru", "en_us"])
        return result


def main():
    raw_log = Path(__file__).with_name("verification.log").read_bytes()
    log = raw_log.decode("utf-16" if raw_log.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
    assert "BUILD SUCCESSFUL" in log and "BUILD FAILED" not in log
    assert all(f"> Task :{side}:{task}" in log for side in ["server", "client"] for task in ["check", "assemble"])
    assert "CarryRecoveryTest:" in log and "CarryBlockVisualTest: 64" in log
    assert not any(f"> Task :{task}" in log for task in ["server:runServer", "client:runClient", "server:syncServerDistribution", "client:syncClientDistribution"])
    artifacts = [
        inspect(ROOT / "fabric-server/client-mod-gradle/build/libs/client-1.5.68.jar", "1.5.68", [
            "ua/rp/chat/client/carry/CarryClient.class", "ua/rp/chat/client/carry/CarryFeedback.class",
            "ua/rp/chat/client/carry/CarryPoseRenderer.class", "ua/rp/chat/client/carry/CarryWorldFeedback.class",
            "ua/rp/chat/client/mixin/carry/CarryDisplayRendererMixin.class",
        ]),
        inspect(ROOT / "fabric-server/server/build/libs/server-1.5.34.jar", "1.5.34", [
            "ua/rp/chat/carry/CarryManager.class", "ua/rp/chat/carry/CarryLedger.class",
            "ua/rp/chat/carry/CarryBlockSnapshot.class", "ua/rp/chat/carry/CarryBlockMutation.class",
            "ua/rp/chat/carry/CarryRecovery.class", "ua/rp/chat/carry/CarrySyncBuilder.class",
            "ua/rp/chat/microvoxel/MicrovoxelCarryBridge.class",
        ]),
    ]
    report = {
        "verified_utc": datetime.now(timezone.utc).isoformat(), "artifacts": artifacts,
        "joint_check_assemble_passed": True, "game_launched": False,
        "runtime_mods_changed": False, "published": False,
        "verification_log_sha256": sha256(Path(__file__).with_name("verification.log").read_bytes()).hexdigest(),
    }
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
