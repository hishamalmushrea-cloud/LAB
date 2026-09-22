#!/usr/bin/env python3
"""Validate or hydrate the tracked external-asset supply-chain manifest."""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import pathlib
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
from typing import Any
from urllib.parse import urlparse

SHA256 = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_ABIS = {"common", "arm64-v8a", "armeabi-v7a", "host"}
REQUIRED_ENTRY_FIELDS = {
    "id",
    "variant",
    "localPath",
    "remotePath",
    "url",
    "version",
    "abi",
    "size",
    "sha256",
    "source",
    "license",
}


class ManifestError(ValueError):
    """The asset manifest does not satisfy its trust contract."""


def _non_empty_string(value: Any, field: str, asset_id: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"{asset_id}: {field} must be a non-empty string")
    return value


def _validate_https_url(value: Any, field: str, asset_id: str) -> str:
    url = _non_empty_string(value, field, asset_id)
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ManifestError(f"{asset_id}: {field} must be an HTTPS URL without user info")
    lowered_path = parsed.path.lower()
    if "/releases/latest" in lowered_path or "/latest/download" in lowered_path:
        raise ManifestError(f"{asset_id}: {field} must pin a release, not use latest")
    return url


def _validate_relative_path(value: Any, field: str, asset_id: str) -> str:
    raw = _non_empty_string(value, field, asset_id)
    path = pathlib.PurePosixPath(raw)
    if path.is_absolute() or ".." in path.parts or "\\" in raw:
        raise ManifestError(f"{asset_id}: {field} must be a contained POSIX path")
    return raw


def validate_manifest(document: Any, *, allow_missing_digests: bool = False) -> list[dict[str, Any]]:
    if not isinstance(document, dict) or document.get("schemaVersion") != 1:
        raise ManifestError("schemaVersion must be 1")
    _non_empty_string(document.get("manifestVersion"), "manifestVersion", "manifest")
    assets = document.get("assets")
    if not isinstance(assets, list) or not assets:
        raise ManifestError("assets must be a non-empty array")

    seen_ids: set[str] = set()
    seen_paths: set[str] = set()
    for raw_entry in assets:
        if not isinstance(raw_entry, dict):
            raise ManifestError("every assets entry must be an object")
        missing_fields = REQUIRED_ENTRY_FIELDS - raw_entry.keys()
        asset_id = str(raw_entry.get("id", "<unknown>"))
        if missing_fields:
            raise ManifestError(f"{asset_id}: missing fields: {', '.join(sorted(missing_fields))}")

        asset_id = _non_empty_string(raw_entry["id"], "id", asset_id)
        if asset_id in seen_ids:
            raise ManifestError(f"duplicate asset id: {asset_id}")
        seen_ids.add(asset_id)

        local_path = _validate_relative_path(raw_entry["localPath"], "localPath", asset_id)
        _validate_relative_path(raw_entry["remotePath"], "remotePath", asset_id)
        if local_path in seen_paths:
            raise ManifestError(f"duplicate localPath: {local_path}")
        seen_paths.add(local_path)

        _validate_https_url(raw_entry["url"], "url", asset_id)
        _non_empty_string(raw_entry["variant"], "variant", asset_id)
        _non_empty_string(raw_entry["version"], "version", asset_id)

        abis = raw_entry["abi"]
        if not isinstance(abis, list) or not abis or any(abi not in ALLOWED_ABIS for abi in abis):
            raise ManifestError(f"{asset_id}: abi must contain only {sorted(ALLOWED_ABIS)}")

        source = raw_entry["source"]
        if not isinstance(source, dict):
            raise ManifestError(f"{asset_id}: source must be an object")
        _validate_https_url(source.get("project"), "source.project", asset_id)
        _non_empty_string(source.get("revision"), "source.revision", asset_id)

        license_info = raw_entry["license"]
        if not isinstance(license_info, dict):
            raise ManifestError(f"{asset_id}: license must be an object")
        _non_empty_string(license_info.get("id"), "license.id", asset_id)
        _validate_relative_path(license_info.get("reference"), "license.reference", asset_id)

        size = raw_entry["size"]
        digest = raw_entry["sha256"]
        missing_digest = size is None or digest is None
        if missing_digest and not allow_missing_digests:
            raise ManifestError(f"{asset_id}: size and sha256 must be populated")
        if not missing_digest:
            if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
                raise ManifestError(f"{asset_id}: size must be a positive integer")
            if not isinstance(digest, str) or not SHA256.fullmatch(digest):
                raise ManifestError(f"{asset_id}: sha256 must be 64 lowercase hex characters")

    return assets


def _hash_remote(entry: dict[str, Any], retries: int) -> tuple[str, int, str]:
    asset_id = entry["id"]
    url = entry["url"]
    last_error: BaseException | None = None
    for attempt in range(1, retries + 1):
        digest = hashlib.sha256()
        size = 0
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/octet-stream,*/*",
                "Accept-Encoding": "identity",
                "User-Agent": "CodeOnTheGo-asset-manifest/1",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                final_url = response.geturl()
                if urlparse(final_url).scheme != "https":
                    raise ManifestError(f"{asset_id}: redirect downgraded HTTPS to {final_url}")
                while chunk := response.read(1024 * 1024):
                    digest.update(chunk)
                    size += len(chunk)
            return asset_id, size, digest.hexdigest()
        except (OSError, urllib.error.URLError, ManifestError) as error:
            last_error = error
            if attempt < retries:
                time.sleep(2**attempt)
    raise ManifestError(f"{asset_id}: download failed after {retries} attempts: {last_error}")


def hydrate_missing(document: dict[str, Any], workers: int, retries: int) -> None:
    assets = validate_manifest(document, allow_missing_digests=True)
    missing = [entry for entry in assets if entry["size"] is None or entry["sha256"] is None]
    if not missing:
        return

    print(f"Hydrating {len(missing)} assets with {workers} workers", flush=True)
    by_id = {entry["id"]: entry for entry in missing}
    errors: list[str] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(_hash_remote, entry, retries): entry["id"] for entry in missing}
        for future in concurrent.futures.as_completed(futures):
            try:
                asset_id, size, digest = future.result()
            except ManifestError as error:
                errors.append(str(error))
                print(f"FAILED: {error}", file=sys.stderr, flush=True)
                continue
            by_id[asset_id]["size"] = size
            by_id[asset_id]["sha256"] = digest
            print(f"{asset_id}: {size} bytes sha256:{digest}", flush=True)

    if errors:
        raise ManifestError("asset hydration failures:\n" + "\n".join(sorted(errors)))
    validate_manifest(document)


def _load(path: pathlib.Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ManifestError(f"cannot read {path}: {error}") from error


def _atomic_write(path: pathlib.Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = pathlib.Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(document, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=pathlib.Path)
    parser.add_argument("--hydrate-missing", action="store_true")
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--retries", type=int, default=3)
    arguments = parser.parse_args()

    try:
        document = _load(arguments.manifest)
        if arguments.hydrate_missing:
            if not 1 <= arguments.workers <= 8:
                raise ManifestError("workers must be between 1 and 8")
            try:
                hydrate_missing(document, arguments.workers, arguments.retries)
            finally:
                # Preserve successful results for diagnosis even when another source is unavailable.
                _atomic_write(arguments.output or arguments.manifest, document)
        else:
            validate_manifest(document)
    except ManifestError as error:
        print(f"asset manifest error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
