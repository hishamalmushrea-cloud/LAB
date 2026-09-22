import copy
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import scripts.asset_manifest as asset_manifest


class AssetManifestTest(unittest.TestCase):
    def setUp(self):
        self.entry = {
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
            "source": {
                "project": "https://example.org/project",
                "revision": "v1",
            },
            "license": {
                "id": "Apache-2.0",
                "reference": "docs/assets/ASSET_LICENSES.md",
            },
        }
        self.manifest = {
            "schemaVersion": 1,
            "manifestVersion": "test.1",
            "inventoryDate": "2026-09-22",
            "assets": [self.entry],
        }

    def test_accepts_complete_manifest(self):
        self.assertEqual([self.entry], asset_manifest.validate_manifest(self.manifest))

    def test_rejects_missing_digest_by_default(self):
        self.entry["size"] = None
        self.entry["sha256"] = None

        with self.assertRaisesRegex(asset_manifest.ManifestError, "must be populated"):
            asset_manifest.validate_manifest(self.manifest)

        asset_manifest.validate_manifest(self.manifest, allow_missing_digests=True)

    def test_rejects_latest_release_url(self):
        self.entry["url"] = "https://example.org/releases/latest/download/example.bin"

        with self.assertRaisesRegex(asset_manifest.ManifestError, "pin a release"):
            asset_manifest.validate_manifest(self.manifest)

    def test_rejects_unknown_url_stability(self):
        self.entry["urlStability"] = "probably-stable"

        with self.assertRaisesRegex(asset_manifest.ManifestError, "urlStability"):
            asset_manifest.validate_manifest(self.manifest)

    def test_rejects_path_traversal(self):
        self.entry["localPath"] = "assets/../outside.bin"

        with self.assertRaisesRegex(asset_manifest.ManifestError, "contained POSIX path"):
            asset_manifest.validate_manifest(self.manifest)

    def test_rejects_duplicate_ids(self):
        self.manifest["assets"].append(copy.deepcopy(self.entry))

        with self.assertRaisesRegex(asset_manifest.ManifestError, "duplicate asset id"):
            asset_manifest.validate_manifest(self.manifest)

    def test_hydrates_missing_digest(self):
        self.entry["size"] = None
        self.entry["sha256"] = None
        with mock.patch.object(
            asset_manifest,
            "_hash_remote",
            return_value=("example", 7, "b" * 64),
        ):
            asset_manifest.hydrate_missing(self.manifest, workers=1, retries=1)

        self.assertEqual(7, self.entry["size"])
        self.assertEqual("b" * 64, self.entry["sha256"])

    def test_keeps_successful_results_when_another_hydration_fails(self):
        self.entry["size"] = None
        self.entry["sha256"] = None
        second = copy.deepcopy(self.entry)
        second["id"] = "second"
        second["localPath"] = "assets/second.bin"
        self.manifest["assets"].append(second)
        with mock.patch.object(
            asset_manifest,
            "_hash_remote",
            side_effect=[
                ("example", 7, "b" * 64),
                asset_manifest.ManifestError("second unavailable"),
            ],
        ):
            with self.assertRaisesRegex(asset_manifest.ManifestError, "second unavailable"):
                asset_manifest.hydrate_missing(self.manifest, workers=1, retries=1)

        self.assertEqual(7, self.entry["size"])
        self.assertEqual("b" * 64, self.entry["sha256"])
        self.assertIsNone(second["size"])

    def test_atomic_write_replaces_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "manifest.json"
            target.write_text("old", encoding="utf-8")

            asset_manifest._atomic_write(target, self.manifest)

            self.assertEqual(self.manifest, json.loads(target.read_text(encoding="utf-8")))
            self.assertEqual([], list(target.parent.glob("*.tmp")))


if __name__ == "__main__":
    unittest.main()
