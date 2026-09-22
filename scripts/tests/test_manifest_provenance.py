import base64
import copy
import json
import pathlib
import tempfile
import unittest

import scripts.ed25519 as ed25519
import scripts.manifest_provenance as provenance


class Ed25519Test(unittest.TestCase):
	def test_matches_rfc8032_test_vector_1(self):
		seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
		expected_public = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
		expected_signature = (
			"e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
			"5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
		)

		self.assertEqual(expected_public, ed25519.public_key_from_seed(seed).hex())
		self.assertEqual(expected_signature, ed25519.sign(seed, b"").hex())
		self.assertTrue(
			ed25519.verify(ed25519.public_key_from_seed(seed), b"", bytes.fromhex(expected_signature))
		)

	def test_matches_rfc8032_test_vector_2(self):
		seed = bytes.fromhex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
		message = bytes.fromhex("72")
		signature = ed25519.sign(seed, message)

		self.assertEqual(
			"92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
			"085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
			signature.hex(),
		)

	def test_rejects_tampered_message_and_malformed_input(self):
		seed = bytes(range(32))
		public_key = ed25519.public_key_from_seed(seed)
		signature = ed25519.sign(seed, b"manifest")

		self.assertTrue(ed25519.verify(public_key, b"manifest", signature))
		self.assertFalse(ed25519.verify(public_key, b"manifes7", signature))
		self.assertFalse(ed25519.verify(public_key, b"manifest", signature[:-1] + b"\x00"))
		self.assertFalse(ed25519.verify(public_key[:31], b"manifest", signature))
		self.assertFalse(ed25519.verify(public_key, b"manifest", signature[:63]))

	def test_rejects_non_canonical_scalar(self):
		seed = bytes(range(32))
		public_key = ed25519.public_key_from_seed(seed)
		signature = ed25519.sign(seed, b"manifest")
		oversized = signature[:32] + (ed25519.L + 1).to_bytes(32, "little")

		self.assertFalse(ed25519.verify(public_key, b"manifest", oversized))

	def test_rejects_wrong_seed_size(self):
		with self.assertRaises(ValueError):
			ed25519.public_key_from_seed(b"short")


class ManifestProvenanceTest(unittest.TestCase):
	def setUp(self):
		self.seed = bytes(range(1, 33))
		self.manifest = {
			"schemaVersion": 1,
			"manifestVersion": "test.1",
			"inventoryDate": "2026-09-23",
			"assets": [
				{
					"id": "example",
					"variant": "debug",
					"localPath": "assets/example.bin",
					"remotePath": "example.bin",
					"url": "https://downloads.example.org/releases/v1/example.bin",
					"urlStability": "immutable-release",
					"version": "v1",
					"abi": ["common"],
					"size": 3,
					"sha256": "a" * 64,
					"source": {"project": "https://example.org/project", "revision": "v1"},
					"license": {"id": "Apache-2.0", "reference": "docs/assets/ASSET_LICENSES.md"},
				}
			],
		}
		self.signature = provenance.build_signature_document(self.manifest, self.seed, "release-2026")
		self.public_key = self.signature["publicKey"]

	def test_canonical_bytes_ignore_formatting_and_existing_signature(self):
		reordered = {"assets": self.manifest["assets"], **self.manifest, "signature": "ignored"}

		self.assertEqual(provenance.canonical_bytes(self.manifest), provenance.canonical_bytes(reordered))

	def test_verifies_signature_with_trusted_key(self):
		key_id = provenance.verify_signature_document(
			self.manifest, self.signature, trusted_public_key=self.public_key
		)

		self.assertEqual("release-2026", key_id)

	def test_rejects_modified_manifest(self):
		tampered = copy.deepcopy(self.manifest)
		tampered["assets"][0]["sha256"] = "b" * 64

		with self.assertRaisesRegex(provenance.ProvenanceError, "does not match its signature"):
			provenance.verify_signature_document(
				tampered, self.signature, trusted_public_key=self.public_key
			)

	def test_rejects_untrusted_public_key(self):
		other = base64.b64encode(ed25519.public_key_from_seed(bytes(32))).decode("ascii")

		with self.assertRaisesRegex(provenance.ProvenanceError, "untrusted public key"):
			provenance.verify_signature_document(self.manifest, self.signature, trusted_public_key=other)

	def test_rejects_swapped_public_key_without_valid_signature(self):
		forged = dict(self.signature)
		forged["publicKey"] = base64.b64encode(ed25519.public_key_from_seed(bytes(32))).decode("ascii")

		with self.assertRaisesRegex(provenance.ProvenanceError, "does not match its signature"):
			provenance.verify_signature_document(self.manifest, forged, trusted_key_id="release-2026")

	def test_rejects_wrong_key_id_algorithm_and_schema(self):
		with self.assertRaisesRegex(provenance.ProvenanceError, "trusted key id"):
			provenance.verify_signature_document(
				self.manifest, self.signature, trusted_key_id="other-key"
			)

		downgraded = dict(self.signature, algorithm="none")
		with self.assertRaisesRegex(provenance.ProvenanceError, "algorithm must be ed25519"):
			provenance.verify_signature_document(
				self.manifest, downgraded, trusted_public_key=self.public_key
			)

		future = dict(self.signature, schemaVersion=2)
		with self.assertRaisesRegex(provenance.ProvenanceError, "schemaVersion must be 1"):
			provenance.verify_signature_document(
				self.manifest, future, trusted_public_key=self.public_key
			)

	def test_rejects_signature_for_a_different_manifest_version(self):
		renamed = copy.deepcopy(self.manifest)
		renamed["manifestVersion"] = "test.2"

		with self.assertRaisesRegex(provenance.ProvenanceError, "different manifestVersion"):
			provenance.verify_signature_document(
				renamed, self.signature, trusted_public_key=self.public_key
			)

	def test_requires_a_trust_anchor(self):
		with self.assertRaisesRegex(provenance.ProvenanceError, "trusted public key or key id"):
			provenance.verify_signature_document(self.manifest, self.signature)

	def test_cli_round_trip(self):
		with tempfile.TemporaryDirectory() as directory:
			root = pathlib.Path(directory)
			manifest_path = root / "assets-manifest.json"
			seed_path = root / "seed.b64"
			signature_path = root / "assets-manifest.sig.json"
			manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
			seed_path.write_text(base64.b64encode(self.seed).decode("ascii"), encoding="utf-8")

			self.assertEqual(
				0,
				provenance.main(
					[
						"sign",
						str(manifest_path),
						"--key-id",
						"release-2026",
						"--seed-file",
						str(seed_path),
					]
				),
			)
			self.assertEqual(
				0,
				provenance.main(
					["verify", str(manifest_path), "--public-key", self.public_key]
				),
			)

			signature_document = json.loads(signature_path.read_text(encoding="utf-8"))
			self.assertEqual("release-2026", signature_document["keyId"])

			manifest_path.write_text(
				json.dumps({**self.manifest, "manifestVersion": "test.9"}), encoding="utf-8"
			)
			self.assertEqual(
				1,
				provenance.main(
					["verify", str(manifest_path), "--public-key", self.public_key]
				),
			)


if __name__ == "__main__":
	unittest.main()
