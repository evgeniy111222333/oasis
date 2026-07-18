#!/usr/bin/env python3
"""Build the public Eclipse RolePlay launcher/client distribution tree."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path


PUBLIC_BASE_URL = "https://dist.eclipse-roleplay.online"


def digest(path: Path, algorithm: str) -> str:
    hasher = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def descriptor(path: Path, key: str, public_base_url: str, *, cache_bust: bool = False) -> dict:
    result = {
        "name": path.name,
        "path": key,
        "url": f"{public_base_url.rstrip('/')}/{key}",
        "size": path.stat().st_size,
        "sha1": digest(path, "sha1"),
        "sha256": digest(path, "sha256"),
    }
    # Имена управляемых файлов стабильны между релизами. Хеш в URL не даёт
    # CDN вернуть предыдущий JAR из immutable-кеша после публикации обновления.
    if cache_bust:
        result["url"] = f'{result["url"]}?sha256={result["sha256"]}'
    return result


def build(repo: Path, output: Path, public_base_url: str) -> Path:
    client_source = repo / "plugins" / "RPChat" / "client"
    package = json.loads((repo / "launcher" / "package.json").read_text(encoding="utf-8"))
    launcher_version = package["version"]
    installer_source = repo / "launcher" / "dist" / f"Eclipse RolePlay Launcher Setup {launcher_version}.exe"
    mods_source_manifest = client_source / "mods.json"
    release_source = repo / "deployment" / "release.json"

    required = [client_source, installer_source, mods_source_manifest]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise FileNotFoundError("Missing distribution input(s): " + ", ".join(missing))

    if output.exists():
        import stat
        import os
        def on_rm_error(func, path, exc_info):
            try:
                os.chmod(path, stat.S_IWRITE)
                func(path)
            except Exception:
                pass
        shutil.rmtree(output, onerror=on_rm_error)
    client_output = output / "client"
    launcher_output = output / "launcher" / "stable"
    manifests_output = output / "manifests"
    # Повторный запуск после прерванной сборки должен безопасно дописать staging,
    # даже если Windows не успела удалить уже созданный каталог client.
    shutil.copytree(client_source, client_output, dirs_exist_ok=True)
    launcher_output.mkdir(parents=True, exist_ok=True)
    manifests_output.mkdir(parents=True, exist_ok=True)

    installer_name = f"Eclipse-RolePlay-Launcher-Setup-{launcher_version}.exe"
    installer_output = launcher_output / installer_name
    shutil.copy2(installer_source, installer_output)

    source_mods = json.loads(mods_source_manifest.read_text(encoding="utf-8"))
    mods = []
    for entry in source_mods:
        relative_path = str(entry["path"]).replace("\\", "/")
        source = client_source / relative_path
        if not source.is_file():
            raise FileNotFoundError(f"Manifest references missing file: {source}")
        current = descriptor(
            source,
            f"client/{relative_path}",
            public_base_url,
            cache_bust=True,
        )
        if entry.get("size") and current["size"] != int(entry["size"]):
            raise ValueError(f"Size mismatch in source manifest for {relative_path}")
        if entry.get("sha1") and current["sha1"] != str(entry["sha1"]).lower():
            raise ValueError(f"SHA-1 mismatch in source manifest for {relative_path}")
        current["path"] = relative_path
        # Presentation and activation metadata is launcher configuration, not
        # file-integrity data. Preserve it while always recomputing URLs/hashes.
        for key in (
            "optional", "preferenceKey", "modId", "displayName", "version",
            "category", "description", "icon", "defaultEnabled",
        ):
            if key in entry:
                current[key] = entry[key]
        mods.append(current)

    profile_relative = f"versions/26.1.2/26.1.2.json"
    profile_source = client_source / profile_relative
    if not profile_source.is_file():
        raise FileNotFoundError(f"Missing client profile: {profile_source}")

    launcher = descriptor(
        installer_output,
        f"launcher/stable/{installer_name}",
        public_base_url,
    )
    launcher["version"] = launcher_version
    launcher["productName"] = package["build"]["productName"]

    profile = descriptor(
        profile_source,
        f"client/{profile_relative}",
        public_base_url,
        cache_bust=True,
    )
    profile["path"] = profile_relative
    generated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    release = json.loads(release_source.read_text(encoding="utf-8")) if release_source.exists() else {"published": False}
    manifest = {
        "schemaVersion": 1,
        "channel": "production",
        "brand": "Eclipse RolePlay",
        "generatedAt": generated_at,
        "release": release,
        "launcher": launcher,
        "client": {
            "gameVersion": "26.1.2",
            "baseUrl": f"{public_base_url.rstrip('/')}/client",
            "profile": profile,
            "mods": mods,
        },
    }

    (client_output / "mods.json").write_text(
        json.dumps(mods, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    manifest_path = manifests_output / "production.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (output / "launcher" / "latest.json").write_text(
        json.dumps(launcher, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return manifest_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    parser.add_argument("--public-base-url", default=PUBLIC_BASE_URL)
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = (args.output or repo / "distribution-build").resolve()
    manifest = build(repo, output, args.public_base_url)
    print(manifest)


if __name__ == "__main__":
    main()
