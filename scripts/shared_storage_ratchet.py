#!/usr/bin/env python3
"""Stop the app's broad shared-storage surface from growing.

ADR 0016 moves projects into an app-owned workspace reached through a single
storage gateway. That migration is large, so the risk is not the work itself
but the drift around it: every new direct call to shared storage makes the
eventual port bigger and is invisible in review.

This tool inventories those call sites and ratchets them against
`config/shared-storage-baseline.json`:

	* a call site missing from the baseline fails CI - the debt cannot grow;
	* a baselined call site that disappeared also fails, so a removal is
	  locked in by deleting its entry and the list only ever shrinks.

Usage:
	python3 scripts/shared_storage_ratchet.py               # ratchet check
	python3 scripts/shared_storage_ratchet.py --report      # inventory
	python3 scripts/shared_storage_ratchet.py --write-baseline
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from dataclasses import dataclass

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent
BASELINE = pathlib.Path("config/shared-storage-baseline.json")
SOURCE_ROOTS = (
	pathlib.Path("app/src/main"),
	pathlib.Path("common/src/main"),
	pathlib.Path("editor/src/main"),
	pathlib.Path("plugin-manager/src/main"),
	pathlib.Path("plugin-api/src/main"),
)
SOURCE_SUFFIXES = (".kt", ".java")

# The storage gateway itself is allowed to call the platform APIs; that is the
# point of having one. Everything else must go through it.
EXEMPT_PATHS = frozenset({"common/src/main/java/com/itsaky/androidide/utils/FileUtil.java"})

PATTERNS: tuple[tuple[str, re.Pattern[str], str], ...] = (
	(
		"external-storage-root",
		re.compile(r"getExternalStorageDirectory\s*\(|getExternalStorageDir\s*\("),
		"Resolves the shared storage root directly instead of using the workspace gateway.",
	),
	(
		"public-directory",
		re.compile(r"getExternalStoragePublicDirectory\s*\("),
		"Targets a public shared directory, which scoped storage restricts from API 29.",
	),
	(
		"all-files-access",
		re.compile(r"isExternalStorageManager\s*\(|MANAGE_EXTERNAL_STORAGE"),
		"Depends on all-files access, the permission ADR 0016 aims to drop.",
	),
	(
		"hardcoded-storage-path",
		re.compile(r"\"/sdcard|\"/storage/emulated"),
		"Hardcodes a shared storage path that does not exist on every device or user profile.",
	),
)

COMMENT_PREFIXES = ("*", "//", "/*", "#")


class RatchetError(ValueError):
	"""The shared-storage inventory could not be produced."""


@dataclass(frozen=True, order=True)
class CallSite:
	"""One direct use of shared storage outside the gateway."""

	location: str
	id: str
	summary: str

	def key(self) -> str:
		return f"{self.id}:{self.location}"

	def as_dict(self) -> dict[str, str]:
		return {"key": self.key(), "id": self.id, "location": self.location, "summary": self.summary}


def _is_comment(line: str) -> bool:
	return line.strip().startswith(COMMENT_PREFIXES)


def collect(root: pathlib.Path = REPOSITORY_ROOT) -> list[CallSite]:
	"""Return every non-exempt shared-storage call site, deterministically ordered."""
	found: list[CallSite] = []
	for source_root in SOURCE_ROOTS:
		base = root / source_root
		if not base.is_dir():
			continue
		for path in sorted(base.rglob("*")):
			if path.suffix not in SOURCE_SUFFIXES or not path.is_file():
				continue
			relative = path.relative_to(root).as_posix()
			if relative in EXEMPT_PATHS:
				continue
			text = path.read_text(encoding="utf-8", errors="replace")
			for number, line in enumerate(text.splitlines(), start=1):
				if _is_comment(line):
					continue
				for identifier, pattern, summary in PATTERNS:
					if pattern.search(line):
						found.append(CallSite(f"{relative}:{number}", identifier, summary))
	return sorted(set(found))


def load_baseline(root: pathlib.Path = REPOSITORY_ROOT) -> dict[str, object]:
	path = root / BASELINE
	if not path.is_file():
		return {"schemaVersion": 1, "accepted": []}
	try:
		document = json.loads(path.read_text(encoding="utf-8"))
	except (OSError, json.JSONDecodeError) as error:
		raise RatchetError(f"cannot read {BASELINE}: {error}") from error
	if not isinstance(document, dict) or document.get("schemaVersion") != 1:
		raise RatchetError(f"{BASELINE}: schemaVersion must be 1")
	if not isinstance(document.get("accepted"), list):
		raise RatchetError(f"{BASELINE}: accepted must be an array")
	return document


def compare(
	call_sites: list[CallSite],
	baseline: dict[str, object],
) -> tuple[list[CallSite], list[str]]:
	"""Return call sites missing from the baseline and baseline entries now gone."""
	accepted = {str(entry.get("key")) for entry in baseline["accepted"] if isinstance(entry, dict)}
	current = {site.key(): site for site in call_sites}
	new = sorted(site for key, site in current.items() if key not in accepted)
	removed = sorted(key for key in accepted if key not in current)
	return new, removed


def _render(call_sites: list[CallSite]) -> str:
	lines = [f"shared storage call sites: {len(call_sites)}", ""]
	by_id: dict[str, list[CallSite]] = {}
	for site in call_sites:
		by_id.setdefault(site.id, []).append(site)
	for identifier in sorted(by_id):
		sites = by_id[identifier]
		lines.append(f"{identifier} ({len(sites)})")
		lines.append(f"  {sites[0].summary}")
		for site in sites:
			lines.append(f"    {site.location}")
		lines.append("")
	return "\n".join(lines).rstrip() + "\n"


def _baseline_document(call_sites: list[CallSite]) -> dict[str, object]:
	return {
		"schemaVersion": 1,
		"comment": (
			"Direct shared-storage call sites accepted while ADR 0016 (scoped workspace storage) "
			"is being implemented. Shrink this list; never grow it."
		),
		"adr": "docs/adr/0016-scoped-workspace-storage-model.md",
		"accepted": [site.as_dict() for site in call_sites],
	}


def main(argv: list[str] | None = None) -> int:
	parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
	parser.add_argument("--root", type=pathlib.Path, default=REPOSITORY_ROOT)
	parser.add_argument("--report", action="store_true")
	parser.add_argument("--write-baseline", action="store_true")
	arguments = parser.parse_args(argv)

	try:
		call_sites = collect(arguments.root)
		if arguments.report:
			print(_render(call_sites), end="")
			return 0
		if arguments.write_baseline:
			path = arguments.root / BASELINE
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_text(
				json.dumps(_baseline_document(call_sites), indent=2, ensure_ascii=False) + "\n",
				encoding="utf-8",
			)
			print(f"wrote {BASELINE} with {len(call_sites)} accepted call sites")
			return 0

		new, removed = compare(call_sites, load_baseline(arguments.root))
		for site in new:
			print(f"NEW SHARED STORAGE USE {site.id} at {site.location}: {site.summary}", file=sys.stderr)
		for key in removed:
			print(f"REMOVED: {key} no longer occurs; delete it from {BASELINE}.", file=sys.stderr)
		if new or removed:
			return 1
		print(f"OK: {len(call_sites)} accepted, unchanged shared-storage call sites")
		return 0
	except RatchetError as error:
		print(f"shared storage ratchet error: {error}", file=sys.stderr)
		return 1


if __name__ == "__main__":
	raise SystemExit(main())
