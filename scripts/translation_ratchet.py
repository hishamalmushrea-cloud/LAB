#!/usr/bin/env python3
"""Check translated string resources against the base locale.

Two classes of problem are reported, and the distinction matters:

*Placeholder mismatches are errors.* A translation whose format specifiers differ from the base
string is not merely untranslated, it is broken. `String.format` throws when a specifier is present
with no argument, and - the case that prompted this script - a translation that silently *drops* a
specifier makes the formatted value disappear. `msg_crash_info` did exactly that in ten languages:
the base string ends in `%1$s`, the Arabic one does not, so `CrashReportFragment` looked for the
support-contact phrase inside the formatted text, failed to find it, and rendered a crash dialog
with no way to contact support. Nothing failed loudly; the feature was just absent.

*Missing translations are a tracked debt.* They fall back to English, which is unhelpful but not
broken, so they are held at a baseline that may only shrink - the same ratchet approach used for
the target SDK and shared storage.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Matches a Java format specifier, e.g. %s, %1$s, %.2f, %,d.
FORMAT_SPECIFIER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

DEFAULT_BASE = Path("resources/src/main/res/values/strings.xml")
DEFAULT_BASELINE = Path("config/translation-baseline.json")

SCHEMA_VERSION = 1


def _element_text(element: ET.Element) -> str:
	"""The full text of an element including nested markup such as <b> or <xliff:g>."""
	return "".join(element.itertext())


def parse_strings(path: Path) -> dict[str, list[str]]:
	"""Maps each resource name to every translatable text it contains.

	Plurals and string-arrays yield several texts under one name; each has to satisfy the
	placeholder rule independently, because any of them can be the one that reaches format().
	"""
	root = ET.parse(path).getroot()
	entries: dict[str, list[str]] = {}
	for element in root:
		name = element.get("name")
		if not name or element.get("translatable") == "false":
			continue
		if element.tag == "string":
			entries[name] = [_element_text(element)]
		elif element.tag in ("plurals", "string-array"):
			entries[name] = [_element_text(item) for item in element]
	return entries


def specifiers(text: str) -> list[str]:
	"""The format specifiers in `text`, sorted so ordering differences are allowed.

	Word order legitimately changes between languages, and a positional specifier such as `%1$s`
	already encodes which argument it refers to, so comparing sorted multisets avoids flagging a
	correct translation that reorders its arguments.
	"""
	return sorted(match.group(0) for match in FORMAT_SPECIFIER.finditer(text) if match.group(0) != "%%")


def locale_of(path: Path) -> str:
	"""The locale qualifier of a values-* directory, e.g. 'ar-rSA'."""
	return path.parent.name.removeprefix("values-")


def find_translations(base: Path) -> list[Path]:
	res_dir = base.parent.parent
	return sorted(
		candidate
		for candidate in res_dir.glob("values-*/strings.xml")
		if candidate.is_file()
	)


def analyse(base_path: Path) -> tuple[list[dict], dict[str, int]]:
	"""Returns (placeholder errors, per-locale count of missing translations)."""
	base = parse_strings(base_path)
	errors: list[dict] = []
	missing_counts: dict[str, int] = {}

	for path in find_translations(base_path):
		locale = locale_of(path)
		try:
			translated = parse_strings(path)
		except ET.ParseError as parse_error:
			errors.append(
				{
					"locale": locale,
					"name": "<file>",
					"kind": "malformed-xml",
					"detail": str(parse_error),
				}
			)
			continue

		for name, texts in sorted(translated.items()):
			if name not in base:
				continue
			expected = specifiers(base[name][0])
			for text in texts:
				actual = specifiers(text)
				if actual != expected:
					errors.append(
						{
							"locale": locale,
							"name": name,
							"kind": "placeholder-mismatch",
							"detail": f"base has {expected or '[]'}, translation has {actual or '[]'}",
						}
					)
					break

		missing_counts[locale] = len(set(base) - set(translated))

	return errors, missing_counts


def load_baseline(path: Path) -> dict:
	if not path.is_file():
		return {"schemaVersion": SCHEMA_VERSION, "missingByLocale": {}}
	return json.loads(path.read_text(encoding="utf-8"))


def write_baseline(path: Path, missing_counts: dict[str, int], base_total: int) -> None:
	payload = {
		"schemaVersion": SCHEMA_VERSION,
		"comment": (
			"Untranslated strings per locale. These fall back to English, so they are a tracked "
			"debt rather than a defect; the numbers may only shrink. Placeholder mismatches are "
			"not baselined - they are always errors. See docs/audits for the roadmap item."
		),
		"baseStringCount": base_total,
		"missingByLocale": dict(sorted(missing_counts.items())),
	}
	path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
	parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	parser.add_argument("--root", type=Path, default=Path("."), help="repository root")
	parser.add_argument("--base", type=Path, default=DEFAULT_BASE, help="base strings.xml")
	parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE, help="baseline JSON")
	parser.add_argument("--write-baseline", action="store_true", help="record current counts")
	parser.add_argument("--report", action="store_true", help="print per-locale detail")
	args = parser.parse_args(argv)

	base_path = args.root / args.base
	baseline_path = args.root / args.baseline

	if not base_path.is_file():
		print(f"base strings file not found: {base_path}", file=sys.stderr)
		return 2

	errors, missing_counts = analyse(base_path)
	base_total = len(parse_strings(base_path))

	if args.write_baseline:
		write_baseline(baseline_path, missing_counts, base_total)
		print(f"wrote {baseline_path} for {len(missing_counts)} locales")
		return 0

	if args.report:
		print(f"base translatable strings: {base_total}")
		for locale, missing in sorted(missing_counts.items()):
			translated = base_total - missing
			share = (translated / base_total * 100) if base_total else 0.0
			print(f"  {locale:10} {translated:5}/{base_total} translated ({share:5.1f}%), {missing} missing")

	failed = False

	for error in errors:
		print(
			f"BROKEN TRANSLATION {error['locale']} {error['name']}: {error['detail']}",
			file=sys.stderr,
		)
		failed = True
	if errors:
		print(
			"A translation whose format specifiers differ from the base string is broken at "
			"runtime, not merely untranslated. Fix the translation, or delete the entry so the "
			"string falls back to the base locale.",
			file=sys.stderr,
		)

	baseline = load_baseline(baseline_path)
	accepted = baseline.get("missingByLocale", {})

	for locale, missing in sorted(missing_counts.items()):
		allowed = accepted.get(locale)
		if allowed is None:
			print(
				f"NEW LOCALE {locale} with {missing} untranslated strings is not in the baseline; "
				f"re-run with --write-baseline.",
				file=sys.stderr,
			)
			failed = True
		elif missing > allowed:
			print(
				f"TRANSLATION REGRESSION {locale}: {missing} untranslated, baseline allows {allowed}.",
				file=sys.stderr,
			)
			failed = True

	for locale in sorted(set(accepted) - set(missing_counts)):
		print(
			f"REMOVED locale {locale} is in the baseline but has no strings.xml; "
			f"delete it from {args.baseline}.",
			file=sys.stderr,
		)
		failed = True

	if failed:
		return 1

	total_missing = sum(missing_counts.values())
	print(
		f"OK: {len(missing_counts)} locales, no placeholder mismatches, "
		f"{total_missing} accepted untranslated strings"
	)
	return 0


if __name__ == "__main__":
	raise SystemExit(main())
