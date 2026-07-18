#!/usr/bin/env python3
"""Publish an immutable, browser-downloadable Google Drive mirror for an Eclipse release."""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import zipfile
from pathlib import Path

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload


SCOPES = ["https://www.googleapis.com/auth/drive.file"]
FOLDER_MIME = "application/vnd.google-apps.folder"
UPLOAD_FIELDS = "id,name,size,md5Checksum,webViewLink,webContentLink"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def md5(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_config(repo: Path, path: Path) -> dict:
    config = json.loads(path.read_text(encoding="utf-8"))
    for key in ("folderId", "clientSecretPath", "tokenPath"):
        if not config.get(key):
            raise ValueError(f"Google Drive config is missing {key}")
    for key in ("clientSecretPath", "tokenPath"):
        candidate = Path(config[key])
        config[key] = candidate if candidate.is_absolute() else repo / candidate
    config["makePublic"] = bool(config.get("makePublic", True))
    return config


def authorize(config: dict) -> Credentials:
    token_path: Path = config["tokenPath"]
    credentials = None
    if token_path.exists():
        credentials = Credentials.from_authorized_user_file(str(token_path), SCOPES)
    if credentials and credentials.expired and credentials.refresh_token:
        credentials.refresh(Request())
    if not credentials or not credentials.valid:
        flow = InstalledAppFlow.from_client_secrets_file(str(config["clientSecretPath"]), SCOPES)
        credentials = flow.run_local_server(port=0, open_browser=True)
    token_path.parent.mkdir(parents=True, exist_ok=True)
    token_path.write_text(credentials.to_json(), encoding="utf-8")
    try:
        os.chmod(token_path, 0o600)
    except OSError:
        pass
    return credentials


def escape_query(value: str) -> str:
    return value.replace("'", "\\'")


def find_named(service, parent_id: str, name: str, mime_type: str | None = None) -> dict | None:
    query = f"'{parent_id}' in parents and name = '{escape_query(name)}' and trashed = false"
    if mime_type:
        query += f" and mimeType = '{mime_type}'"
    result = service.files().list(
        q=query,
        spaces="drive",
        fields="files(id,name,mimeType,appProperties,webViewLink)",
        pageSize=20,
    ).execute()
    return next(iter(result.get("files", [])), None)


def ensure_release_folder(service, root_id: str, release_id: str, public: bool) -> dict:
    name = f"Eclipse RolePlay — {release_id}"
    existing = find_named(service, root_id, name, FOLDER_MIME)
    if existing:
        folder = existing
    else:
        folder = service.files().create(
            body={
                "name": name,
                "mimeType": FOLDER_MIME,
                "parents": [root_id],
                "appProperties": {"eclipseReleaseId": release_id},
            },
            fields="id,name,webViewLink",
        ).execute()
    if public:
        try:
            service.permissions().create(
                fileId=folder["id"],
                body={"type": "anyone", "role": "reader"},
                fields="id",
            ).execute()
        except Exception as error:
            # A duplicate permission is harmless; policy restrictions are not.
            if "already" not in str(error).lower():
                raise
    return folder


def upload_file(service, folder_id: str, path: Path, release_id: str, distribution: Path) -> dict:
    previous = find_named(service, folder_id, path.name)
    media = MediaFileUpload(
        str(path),
        mimetype=mimetypes.guess_type(path.name)[0] or "application/octet-stream",
        resumable=True,
        chunksize=8 * 1024 * 1024,
    )
    body = {
        "name": path.name,
        "appProperties": {"eclipseReleaseId": release_id, "sha256": sha256(path)},
    }
    if previous:
        result = service.files().update(
            fileId=previous["id"], body=body, media_body=media, fields=UPLOAD_FIELDS
        ).execute()
    else:
        body["parents"] = [folder_id]
        result = service.files().create(body=body, media_body=media, fields=UPLOAD_FIELDS).execute()
    if int(result.get("size", -1)) != path.stat().st_size:
        raise RuntimeError(f"Google Drive size verification failed for {path.name}")
    if result.get("md5Checksum", "").lower() != md5(path):
        raise RuntimeError(f"Google Drive MD5 verification failed for {path.name}")
    result["sha256"] = sha256(path)
    result["path"] = path.relative_to(distribution).as_posix()
    return result


def create_client_bundle(distribution: Path, release_id: str) -> Path:
    bundle_dir = distribution / "google-drive"
    bundle_dir.mkdir(parents=True, exist_ok=True)
    bundle = bundle_dir / f"Eclipse-RolePlay-Client-{release_id}.zip"
    client_root = distribution / "client"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for source in sorted(client_root.rglob("*")):
            if source.is_file():
                archive.write(source, source.relative_to(distribution).as_posix())
    return bundle


def write_checksums(distribution: Path, release_id: str, files: list[Path]) -> Path:
    payload = {
        "releaseId": release_id,
        "algorithm": "SHA-256",
        "files": [{"name": path.name, "size": path.stat().st_size, "sha256": sha256(path)} for path in files],
    }
    target = distribution / "google-drive" / f"Eclipse-RolePlay-Checksums-{release_id}.json"
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target


def release_paths(distribution: Path, release_id: str, manifest_only: bool) -> list[Path]:
    manifest = distribution / "manifests" / "production.json"
    if manifest_only:
        return [manifest]
    installer = next((distribution / "launcher" / "stable").glob("*.exe"), None)
    if installer is None:
        raise FileNotFoundError("Launcher installer was not found in the distribution")
    bundle = create_client_bundle(distribution, release_id)
    checksums = write_checksums(distribution, release_id, [installer, bundle, manifest])
    # The ZIP remains a manual recovery option. Individual assets make Drive a
    # normal resumable mirror for the launcher instead of a browser-only link.
    client_assets = sorted(path for path in (distribution / "client").rglob("*") if path.is_file())
    return [installer, *client_assets, bundle, manifest, checksums]


def publish(repo: Path, distribution: Path, config: dict, manifest_only: bool) -> None:
    manifest = json.loads((distribution / "manifests" / "production.json").read_text(encoding="utf-8"))
    release_id = str(manifest["release"]["id"])
    service = build("drive", "v3", credentials=authorize(config), cache_discovery=False)
    folder = ensure_release_folder(service, config["folderId"], release_id, config["makePublic"])
    uploaded = [upload_file(service, folder["id"], path, release_id, distribution)
                for path in release_paths(distribution, release_id, manifest_only)]
    metadata_path = repo / "deployment" / "google_drive_release.json"
    existing = {}
    if metadata_path.exists():
        existing = json.loads(metadata_path.read_text(encoding="utf-8"))
    files = {
        entry.get("path", entry.get("name")): entry
        for entry in existing.get("files", [])
        if existing.get("releaseId") == release_id
    }
    files.update({entry["path"]: entry for entry in uploaded})
    metadata = {
        "releaseId": release_id,
        "folderId": folder["id"],
        "folderUrl": folder.get("webViewLink") or f"https://drive.google.com/drive/folders/{folder['id']}",
        "files": sorted(files.values(), key=lambda entry: entry["name"]),
    }
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"GOOGLE_DRIVE_MIRROR_OK release={release_id} folder={metadata['folderUrl']}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--distribution", type=Path)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--authorize", action="store_true")
    parser.add_argument("--manifest-only", action="store_true")
    args = parser.parse_args()
    repo = args.repo.resolve()
    config_path = (args.config or repo / "deployment" / "google_drive.local.json").resolve()
    config = load_config(repo, config_path)
    if args.authorize:
        authorize(config)
        print("GOOGLE_DRIVE_OAUTH_OK")
        return
    publish(repo, (args.distribution or repo / "distribution-build").resolve(), config, args.manifest_only)


if __name__ == "__main__":
    main()
