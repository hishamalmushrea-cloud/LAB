#!/usr/bin/env python3
"""Sign and verify the external-asset manifest with a detached Ed25519 signature.

The manifest already fails closed on size and SHA-256 mismatches, but a digest
only proves that the bytes match what the manifest says; it does not prove who
authored the manifest. This tool adds that missing provenance link:

    keygen   create a release signing seed and its public key
    sign     write a detached signature over the canonical manifest bytes
    verify   check a detached signature against a trusted public key

Canonicalisation is deliberately boring: UTF-8 JSON, sorted keys, compact
separators, no trailing newline. Formatting changes to the tracked manifest
therefore never invalidate a signature, while any semantic change does.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
import pathlib
import sys
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

from scripts import ed25519  # noqa: E402
from scripts.asset_manifest import ManifestError, validate_manifest  # noqa: E402

SIGNATURE_SCHEMA_VERSION = 1
SIGNATURE_ALGORITHM = "ed25519"


class ProvenanceError(ValueError):
	"""The manifest signature does not satisfy its trust contract."""


def canonical_bytes(document: Any) -> bytes:
	"""Return the exact bytes that a manifest signature commits to."""
	if not isinstance(document, dict):
		raise ProvenanceError("manifest must be a JSON object")
	payload = {key: value for key, value in document.items() if key != "signature"}
	return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _decode_key(value: str, expected_size: int, label: str) -> bytes:
	try:
		raw = base64.b64decode(value.strip(), validate=True)
	except (ValueError, TypeError) as error:
		raise ProvenanceError(f"{label} must be standard base64") from error
	if len(raw) != expected_size:
		raise ProvenanceError(f"{label} must decode to {expected_size} bytes, got {len(raw)}")
	return raw


def _read_json(path: pathlib.Path) -> Any:
	try:
		return json.loads(path.read_text(encoding="utf-8"))
	except (OSError, json.JSONDecodeError) as error:
		raise ProvenanceError(f"cannot read {path}: {error}") from error


def _atomic_write_text(path: pathlib.Path, text: str) -> None:
	path.parent.mkdir(parents=True, exist_ok=True)
	temporary = path.with_name(f".{path.name}.tmp")
	try:
		with open(temporary, "w", encoding="utf-8") as stream:
			stream.write(text)
			stream.flush()
			os.fsync(stream.fileno())
		os.replace(temporary, path)
	finally:
		temporary.unlink(missing_ok=True)


def build_signature_document(document: Any, seed: bytes, key_id: str) -> dict[str, Any]:
	"""Return a detached signature document for an already-valid manifest."""
	if not key_id.strip():
		raise ProvenanceError("key id must be a non-empty string")
	payload = canonical_bytes(document)
	public_key = ed25519.public_key_from_seed(seed)
	signature = ed25519.sign(seed, payload)
	return {
		"schemaVersion": SIGNATURE_SCHEMA_VERSION,
		"algorithm": SIGNATURE_ALGORITHM,
		"keyId": key_id.strip(),
		"publicKey": base64.b64encode(public_key).decode("ascii"),
		"manifestVersion": document.get("manifestVersion"),
		"signature": base64.b64encode(signature).decode("ascii"),
	}


def verify_signature_document(
	document: Any,
	signature_document: Any,
	*,
	trusted_public_key: str | None = None,
	trusted_key_id: str | None = None,
) -> str:
	"""Verify a detached signature and return the key id that produced it."""
	if not isinstance(signature_document, dict):
		raise ProvenanceError("signature document must be a JSON object")
	if signature_document.get("schemaVersion") != SIGNATURE_SCHEMA_VERSION:
		raise ProvenanceError("signature schemaVersion must be 1")
	if signature_document.get("algorithm") != SIGNATURE_ALGORITHM:
		raise ProvenanceError("signature algorithm must be ed25519")

	key_id = signature_document.get("keyId")
	if not isinstance(key_id, str) or not key_id.strip():
		raise ProvenanceError("signature keyId must be a non-empty string")
	if trusted_key_id is not None and key_id.strip() != trusted_key_id.strip():
		raise ProvenanceError(f"signature keyId {key_id!r} is not the trusted key id")

	embedded_key = signature_document.get("publicKey")
	if not isinstance(embedded_key, str):
		raise ProvenanceError("signature publicKey must be a base64 string")
	public_key = _decode_key(embedded_key, ed25519.KEY_SIZE, "publicKey")

	if trusted_public_key is not None:
		expected = _decode_key(trusted_public_key, ed25519.KEY_SIZE, "trusted public key")
		if expected != public_key:
			raise ProvenanceError("signature was produced by an untrusted public key")
	elif trusted_key_id is None:
		raise ProvenanceError("verification requires a trusted public key or key id")

	raw_signature = signature_document.get("signature")
	if not isinstance(raw_signature, str):
		raise ProvenanceError("signature must be a base64 string")
	signature = _decode_key(raw_signature, ed25519.SIGNATURE_SIZE, "signature")

	declared_version = signature_document.get("manifestVersion")
	if declared_version is not None and declared_version != document.get("manifestVersion"):
		raise ProvenanceError("signature was issued for a different manifestVersion")

	if not ed25519.verify(public_key, canonical_bytes(document), signature):
		raise ProvenanceError("manifest does not match its signature")
	return key_id.strip()


def _load_seed(arguments: argparse.Namespace) -> bytes:
	if arguments.seed_file:
		try:
			raw = pathlib.Path(arguments.seed_file).read_text(encoding="utf-8")
		except OSError as error:
			raise ProvenanceError(f"cannot read seed file: {error}") from error
	elif os.environ.get("COTG_MANIFEST_SIGNING_SEED"):
		raw = os.environ["COTG_MANIFEST_SIGNING_SEED"]
	else:
		raw = getpass.getpass("base64 Ed25519 signing seed: ")
	return _decode_key(raw, ed25519.SEED_SIZE, "signing seed")


def _command_keygen(arguments: argparse.Namespace) -> int:
	seed = os.urandom(ed25519.SEED_SIZE)
	public_key = ed25519.public_key_from_seed(seed)
	seed_text = base64.b64encode(seed).decode("ascii")
	if arguments.seed_out:
		path = pathlib.Path(arguments.seed_out)
		_atomic_write_text(path, seed_text + "\n")
		os.chmod(path, 0o600)
		print(f"wrote private seed to {path} (keep it out of Git)", file=sys.stderr)
	else:
		print(f"seed (private): {seed_text}", file=sys.stderr)
	print(base64.b64encode(public_key).decode("ascii"))
	return 0


def _command_sign(arguments: argparse.Namespace) -> int:
	document = _read_json(arguments.manifest)
	validate_manifest(document)
	seed = _load_seed(arguments)
	signature_document = build_signature_document(document, seed, arguments.key_id)
	output = arguments.output or arguments.manifest.with_suffix(".sig.json")
	_atomic_write_text(output, json.dumps(signature_document, indent=2, ensure_ascii=False) + "\n")
	print(f"signed {arguments.manifest} -> {output}")
	print(f"public key: {signature_document['publicKey']}")
	return 0


def _command_verify(arguments: argparse.Namespace) -> int:
	document = _read_json(arguments.manifest)
	validate_manifest(document)
	signature_path = arguments.signature or arguments.manifest.with_suffix(".sig.json")
	signature_document = _read_json(signature_path)
	trusted_key = arguments.public_key or os.environ.get("COTG_MANIFEST_PUBLIC_KEY") or None
	key_id = verify_signature_document(
		document,
		signature_document,
		trusted_public_key=trusted_key,
		trusted_key_id=arguments.key_id,
	)
	scope = "trusted public key" if trusted_key else f"key id {key_id}"
	print(f"{arguments.manifest}: signature valid ({scope})")
	return 0


def main(argv: list[str] | None = None) -> int:
	parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
	subparsers = parser.add_subparsers(dest="command", required=True)

	keygen = subparsers.add_parser("keygen", help="generate a release signing key pair")
	keygen.add_argument("--seed-out", help="write the private seed to this path with mode 600")
	keygen.set_defaults(handler=_command_keygen)

	sign = subparsers.add_parser("sign", help="write a detached signature for a manifest")
	sign.add_argument("manifest", type=pathlib.Path)
	sign.add_argument("--key-id", required=True, help="stable identifier of the signing key")
	sign.add_argument("--seed-file", help="file holding the base64 signing seed")
	sign.add_argument("--output", type=pathlib.Path)
	sign.set_defaults(handler=_command_sign)

	verify = subparsers.add_parser("verify", help="verify a detached manifest signature")
	verify.add_argument("manifest", type=pathlib.Path)
	verify.add_argument("--signature", type=pathlib.Path)
	verify.add_argument("--public-key", help="trusted base64 public key")
	verify.add_argument("--key-id", help="trusted key id")
	verify.set_defaults(handler=_command_verify)

	arguments = parser.parse_args(argv)
	try:
		return arguments.handler(arguments)
	except (ProvenanceError, ManifestError) as error:
		print(f"manifest provenance error: {error}", file=sys.stderr)
		return 1


if __name__ == "__main__":
	raise SystemExit(main())
