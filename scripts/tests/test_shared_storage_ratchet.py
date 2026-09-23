import json
import pathlib
import tempfile
import unittest

import scripts.shared_storage_ratchet as ratchet


class SharedStorageRatchetTest(unittest.TestCase):
	def setUp(self):
		self._temporary = tempfile.TemporaryDirectory()
		self.addCleanup(self._temporary.cleanup)
		self.root = pathlib.Path(self._temporary.name)

	def write(self, relative: str, body: str) -> None:
		path = self.root / relative
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(body, encoding="utf-8")

	def ids(self) -> list[str]:
		return [site.id for site in ratchet.collect(self.root)]

	def test_empty_tree_has_no_call_sites(self):
		self.assertEqual([], ratchet.collect(self.root))

	def test_detects_each_pattern(self):
		self.write("app/src/main/A.kt", "val a = Environment.getExternalStorageDirectory()\n")
		self.write("app/src/main/B.kt", "val b = Environment.getExternalStoragePublicDirectory(x)\n")
		self.write("app/src/main/C.kt", "val c = Environment.isExternalStorageManager()\n")
		self.write("app/src/main/D.kt", 'val d = File("/sdcard/Projects")\n')

		self.assertEqual(
			["external-storage-root", "public-directory", "all-files-access", "hardcoded-storage-path"],
			sorted(self.ids(), key=["external-storage-root", "public-directory", "all-files-access", "hardcoded-storage-path"].index),
		)

	def test_ignores_comments_and_unscanned_trees(self):
		self.write("app/src/main/A.kt", "// getExternalStorageDirectory() is the old way\n")
		self.write("app/src/main/B.kt", " * see Environment.getExternalStorageDirectory()\n")
		self.write("app/src/test/C.kt", "val c = Environment.getExternalStorageDirectory()\n")
		self.write("termux/src/main/D.kt", "val d = Environment.getExternalStorageDirectory()\n")

		self.assertEqual([], self.ids())

	def test_ignores_non_source_suffixes(self):
		self.write("app/src/main/notes.txt", "getExternalStorageDirectory()\n")

		self.assertEqual([], self.ids())

	def test_gateway_file_is_exempt(self):
		gateway = next(iter(ratchet.EXEMPT_PATHS))
		self.write(gateway, "String p = Environment.getExternalStorageDirectory().getAbsolutePath();\n")

		self.assertEqual([], self.ids())

	def test_call_site_records_file_and_line(self):
		self.write("app/src/main/A.kt", "package x\n\nval a = getExternalStorageDir()\n")

		(site,) = ratchet.collect(self.root)
		self.assertEqual("app/src/main/A.kt:3", site.location)
		self.assertEqual("external-storage-root:app/src/main/A.kt:3", site.key())

	def test_ratchet_reports_new_and_removed_entries(self):
		self.write("app/src/main/A.kt", "val a = getExternalStorageDir()\n")
		call_sites = ratchet.collect(self.root)

		new, removed = ratchet.compare(call_sites, {"schemaVersion": 1, "accepted": []})
		self.assertEqual(["external-storage-root"], [site.id for site in new])
		self.assertEqual([], removed)

		matching = {"schemaVersion": 1, "accepted": [{"key": site.key()} for site in call_sites]}
		self.assertEqual(([], []), ratchet.compare(call_sites, matching))

		stale = {"schemaVersion": 1, "accepted": matching["accepted"] + [{"key": "gone:file.kt:1"}]}
		new, removed = ratchet.compare(call_sites, stale)
		self.assertEqual([], new)
		self.assertEqual(["gone:file.kt:1"], removed)

	def test_rejects_unknown_baseline_schema(self):
		path = self.root / ratchet.BASELINE
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(json.dumps({"schemaVersion": 7, "accepted": []}), encoding="utf-8")

		with self.assertRaisesRegex(ratchet.RatchetError, "schemaVersion must be 1"):
			ratchet.load_baseline(self.root)

	def test_rejects_non_array_accepted(self):
		path = self.root / ratchet.BASELINE
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(json.dumps({"schemaVersion": 1, "accepted": {}}), encoding="utf-8")

		with self.assertRaisesRegex(ratchet.RatchetError, "must be an array"):
			ratchet.load_baseline(self.root)

	def test_cli_round_trip(self):
		self.write("app/src/main/A.kt", "val a = getExternalStorageDir()\n")

		self.assertEqual(1, ratchet.main(["--root", str(self.root)]))
		self.assertEqual(0, ratchet.main(["--root", str(self.root), "--write-baseline"]))
		self.assertEqual(0, ratchet.main(["--root", str(self.root)]))
		self.assertEqual(0, ratchet.main(["--root", str(self.root), "--report"]))

		document = json.loads((self.root / ratchet.BASELINE).read_text(encoding="utf-8"))
		self.assertEqual(1, len(document["accepted"]))
		self.assertIn("adr", document)

		self.write("app/src/main/A.kt", "val a = workspace.root\n")
		self.assertEqual(1, ratchet.main(["--root", str(self.root)]))


class RepositoryBaselineTest(unittest.TestCase):
	def test_tracked_repository_matches_its_baseline(self):
		new, removed = ratchet.compare(ratchet.collect(), ratchet.load_baseline())

		self.assertEqual([], [site.key() for site in new])
		self.assertEqual([], removed)

	def test_adr_referenced_by_the_baseline_exists(self):
		document = ratchet.load_baseline()

		self.assertTrue((ratchet.REPOSITORY_ROOT / str(document["adr"])).is_file())


if __name__ == "__main__":
	unittest.main()
