"""Tests for scripts/translation_ratchet.py."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts import translation_ratchet as ratchet

BASE = """<?xml version="1.0" encoding="utf-8"?>
<resources>
  <string name="greeting">Hello %1$s</string>
  <string name="plain">Plain text</string>
  <string name="count">%1$d of %2$d</string>
  <string name="internal" translatable="false">do not translate</string>
  <plurals name="files">
	<item quantity="one">%1$d file</item>
	<item quantity="other">%1$d files</item>
  </plurals>
</resources>
"""


def write(path: Path, content: str) -> None:
	path.parent.mkdir(parents=True, exist_ok=True)
	path.write_text(content, encoding="utf-8")


class TranslationRatchetTest(unittest.TestCase):
	def setUp(self) -> None:
		self._tmp = tempfile.TemporaryDirectory()
		self.root = Path(self._tmp.name)
		self.res = self.root / "resources" / "src" / "main" / "res"
		self.base = self.res / "values" / "strings.xml"
		write(self.base, BASE)
		self.addCleanup(self._tmp.cleanup)

	def add_locale(self, locale: str, body: str) -> None:
		write(
			self.res / f"values-{locale}" / "strings.xml",
			f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{body}\n</resources>\n',
		)

	def run_cli(self, *args: str) -> int:
		return ratchet.main(
			[
				"--root",
				str(self.root),
				"--base",
				"resources/src/main/res/values/strings.xml",
				"--baseline",
				"baseline.json",
				*args,
			]
		)

	def baseline(self) -> dict:
		return json.loads((self.root / "baseline.json").read_text(encoding="utf-8"))

	# -- placeholder rules ------------------------------------------------

	def test_specifiers_ignores_escaped_percent(self):
		self.assertEqual(ratchet.specifiers("100%% done"), [])
		self.assertEqual(ratchet.specifiers("%1$s of %2$d"), ["%1$s", "%2$d"])

	def test_reordered_placeholders_are_accepted(self):
		# Word order differs between languages; a positional specifier already says which
		# argument it refers to, so reordering is correct rather than broken.
		self.add_locale("de-rDE", '<string name="count">%2$d von %1$d</string>')
		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 0)

	def test_a_dropped_placeholder_is_an_error(self):
		# The real defect this script was written for: the formatted value silently vanishes.
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا</string>')
		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 1)

	def test_an_added_placeholder_is_an_error(self):
		self.add_locale("fr-rFR", '<string name="plain">Texte %1$s</string>')
		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 1)

	def test_a_broken_plural_item_is_caught(self):
		self.add_locale(
			"es-rES",
			'<plurals name="files"><item quantity="one">un archivo</item>'
			'<item quantity="other">%1$d archivos</item></plurals>',
		)
		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 1)

	def test_a_matching_translation_passes(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>')
		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 0)

	def test_placeholder_errors_are_not_silenced_by_the_baseline(self):
		# A baseline written while a locale is broken must not make it pass; only missing
		# translations are baselined, never mismatches.
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا</string>')

		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 1)

	def test_untranslatable_strings_are_not_required(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>')
		self.run_cli("--write-baseline")

		self.assertNotIn("internal", ratchet.parse_strings(self.base))

	# -- ratchet behaviour ------------------------------------------------

	def test_baseline_records_missing_counts(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>')

		self.run_cli("--write-baseline")

		# Four translatable names in the base: greeting, plain, count, files.
		self.assertEqual(self.baseline()["missingByLocale"], {"ar-rSA": 3})
		self.assertEqual(self.baseline()["baseStringCount"], 4)

	def test_a_new_untranslated_string_fails(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>\n<string name="plain">نص</string>')
		self.run_cli("--write-baseline")

		write(
			self.res / "values-ar-rSA" / "strings.xml",
			'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
			'<string name="greeting">مرحبا %1$s</string>\n</resources>\n',
		)

		self.assertEqual(self.run_cli(), 1)

	def test_more_translation_passes_and_can_be_rebaselined(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>')
		self.run_cli("--write-baseline")

		self.add_locale(
			"ar-rSA",
			'<string name="greeting">مرحبا %1$s</string>\n<string name="plain">نص</string>',
		)

		self.assertEqual(self.run_cli(), 0)
		self.run_cli("--write-baseline")
		self.assertEqual(self.baseline()["missingByLocale"]["ar-rSA"], 2)

	def test_an_unbaselined_locale_fails(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>')

		self.assertEqual(self.run_cli(), 1)

	def test_a_vanished_locale_fails(self):
		self.add_locale("ar-rSA", '<string name="greeting">مرحبا %1$s</string>')
		self.run_cli("--write-baseline")
		(self.res / "values-ar-rSA" / "strings.xml").unlink()

		self.assertEqual(self.run_cli(), 1)

	def test_malformed_xml_is_reported_rather_than_crashing(self):
		write(self.res / "values-ar-rSA" / "strings.xml", "<resources><string>oops")
		self.run_cli("--write-baseline")

		self.assertEqual(self.run_cli(), 1)

	def test_a_missing_base_file_is_a_usage_error(self):
		self.base.unlink()

		self.assertEqual(self.run_cli(), 2)


class RealRepositoryTest(unittest.TestCase):
	"""The checked-in resources must satisfy the script."""

	def test_repository_has_no_placeholder_mismatches(self):
		root = Path(__file__).resolve().parents[2]
		base = root / ratchet.DEFAULT_BASE
		if not base.is_file():
			self.skipTest("base strings.xml not present")

		errors, _ = ratchet.analyse(base)

		self.assertEqual([f"{e['locale']}/{e['name']}" for e in errors], [])

	def test_repository_matches_its_baseline(self):
		root = Path(__file__).resolve().parents[2]
		base = root / ratchet.DEFAULT_BASE
		baseline_path = root / ratchet.DEFAULT_BASELINE
		if not base.is_file() or not baseline_path.is_file():
			self.skipTest("resources or baseline not present")

		_, missing = ratchet.analyse(base)
		accepted = ratchet.load_baseline(baseline_path)["missingByLocale"]

		self.assertEqual(missing, accepted)


if __name__ == "__main__":
	unittest.main()
