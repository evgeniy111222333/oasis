#!/usr/bin/env python3
"""Upload a prepared Eclipse distribution tree to Cloudflare R2."""

from __future__ import annotations

import argparse
import hashlib
import mimetypes
import os
from pathlib import Path

import boto3
from botocore.config import Config


DEFAULT_ENDPOINT = "https://111db7aacae65b6828b4d6c332e1f7c3.r2.cloudflarestorage.com"
DEFAULT_BUCKET = "eclipse-distribution"


def sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def content_type(path: Path) -> str:
    overrides = {
        ".jar": "application/java-archive",
        ".json": "application/json; charset=utf-8",
        ".exe": "application/vnd.microsoft.portable-executable",
    }
    return overrides.get(path.suffix.lower()) or mimetypes.guess_type(path.name)[0] or "application/octet-stream"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1] / "distribution-build")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    parser.add_argument("--bucket", default=DEFAULT_BUCKET)
    args = parser.parse_args()

    access_key = os.environ.get("ECLIPSE_DISTRIBUTION_R2_ACCESS_KEY_ID", "").strip()
    secret_key = os.environ.get("ECLIPSE_DISTRIBUTION_R2_SECRET_ACCESS_KEY", "").strip()
    if not access_key or not secret_key:
        raise SystemExit(
            "Set ECLIPSE_DISTRIBUTION_R2_ACCESS_KEY_ID and "
            "ECLIPSE_DISTRIBUTION_R2_SECRET_ACCESS_KEY before uploading."
        )

    source = args.source.resolve()
    if not source.is_dir():
        raise SystemExit(f"Distribution directory does not exist: {source}")

    client = boto3.client(
        "s3",
        endpoint_url=args.endpoint,
        region_name="auto",
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        config=Config(signature_version="s3v4"),
    )
    client.list_objects_v2(Bucket=args.bucket, MaxKeys=1)

    files = sorted(path for path in source.rglob("*") if path.is_file())
    for index, path in enumerate(files, start=1):
        key = path.relative_to(source).as_posix()
        cache_control = "no-cache, no-store, must-revalidate" if key.endswith(".json") else "public, max-age=31536000, immutable"
        client.upload_file(
            str(path),
            args.bucket,
            key,
            ExtraArgs={
                "ContentType": content_type(path),
                "CacheControl": cache_control,
                "Metadata": {"sha256": sha256(path)},
            },
        )
        print(f"[{index}/{len(files)}] {key}")


if __name__ == "__main__":
    main()
