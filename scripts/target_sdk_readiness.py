#!/usr/bin/env python3
"""Report the behaviour changes that block raising the app's target SDK.

The app still targets API 28, which opts it out of a decade of platform
behaviour changes. Raising the target in one step is unsafe, so this tool turns
the migration into a measurable ratchet instead of a prose document:

	* every known blocker is detected mechanically from the manifest and sources;
	* the accepted blockers live in a checked-in baseline;
	* CI fails when a NEW blocker appears, or when a baselined blocker is fixed
	  but not removed from the baseline.

Raising `TARGET_SDK` is therefore a matter of emptying this baseline, one
entry at a time, with each fix independently reviewable.

Usage:
	python3 scripts/target_sdk_readiness.py                 # ratchet against the baseline
	python3 scripts/target_sdk_readiness.py --report        # human-readable inventory
	python3 scripts/target_sdk_readiness.py --write-baseline
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from typing import Iterable

ANDROID = "{http://schemas.android.com/apk/res/android}"
REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = pathlib.Path("app/src/main/AndroidManifest.xml")
BASELINE = pathlib.Path("config/target-sdk-baseline.json")
BUILD_CONFIG = pathlib.Path(
	"composite-builds/build-logic/common/src/main/java/com/itsaky/androidide/build/config/BuildConfig.kt"
)
SOURCE_ROOTS = (pathlib.Path("app/src/main"),)
SOURCE_SUFFIXES = (".kt", ".java")

TARGET_SDK_PATTERN = re.compile(r"\bTARGET_SDK\s*=\s*(\d+)")
PENDING_INTENT_PATTERN = re.compile(r"PendingIntent\.get(Activity|Service|Broadcast|ForegroundService)\s*\(")
MUTABILITY_PATTERN = re.compile(r"FLAG_(?:IM)?MUTABLE")
REGISTER_RECEIVER_PATTERN = re.compile(r"(?<![.\w])registerReceiver\s*\(")
RECEIVER_FLAG_PATTERN = re.compile(r"RECEIVER_(?:NOT_)?EXPORTED")
START_FOREGROUND_PATTERN = re.compile(r"(?<![.\w])startForeground\s*\(")

# Foreground service types became mandatory in API 34; a service that calls
# startForeground must declare one in the manifest and hold its permission.
FOREGROUND_SERVICE_TYPE_API = 34


class ReadinessError(ValueError):
	"""The target SDK readiness inventory could not be produced."""


@dataclass(frozen=True, order=True)
class Finding:
	"""A single behaviour change that must be handled before raising the target."""

	id: str
	api: int
	location: str
	summary: str

	def key(self) -> str:
		return f"{self.id}:{self.location}"

	def as_dict(self) -> dict[str, object]:
		return {"id": self.id, "api": self.api, "location": self.location, "summary": self.summary}


@dataclass
class Inventory:
	target_sdk: int
	findings: list[Finding] = field(default_factory=list)


def _read_text(root: pathlib.Path, relative: pathlib.Path) -> str:
	path = root / relative
	try:
		return path.read_text(encoding="utf-8")
	except OSError as error:
		raise ReadinessError(f"cannot read {relative}: {error}") from error


def _current_target_sdk(root: pathlib.Path) -> int:
	match = TARGET_SDK_PATTERN.search(_read_text(root, BUILD_CONFIG))
	if not match:
		raise ReadinessError(f"no TARGET_SDK assignment found in {BUILD_CONFIG}")
	return int(match.group(1))


def _iter_sources(root: pathlib.Path) -> Iterable[tuple[pathlib.Path, str]]:
	for source_root in SOURCE_ROOTS:
		base = root / source_root
		if not base.is_dir():
			continue
		for path in sorted(base.rglob("*")):
			if path.suffix in SOURCE_SUFFIXES and path.is_file():
				yield path.relative_to(root), path.read_text(encoding="utf-8", errors="replace")


def _manifest_findings(root: pathlib.Path) -> list[Finding]:
	text = _read_text(root, MANIFEST)
	try:
		manifest = ElementTree.fromstring(text)
	except ElementTree.ParseError as error:
		raise ReadinessError(f"cannot parse {MANIFEST}: {error}") from error

	findings: list[Finding] = []
	application = manifest.find("application")
	if application is None:
		raise ReadinessError(f"{MANIFEST} has no <application> element")

	if application.get(f"{ANDROID}requestLegacyExternalStorage") == "true":
		findings.append(
			Finding(
				"legacy-external-storage",
				29,
				"app/src/main/AndroidManifest.xml:application",
				"requestLegacyExternalStorage is ignored from API 30; project files need scoped "
				"storage, SAF grants or an app-private workspace.",
			)
		)

	if application.get(f"{ANDROID}usesCleartextTraffic") == "true":
		findings.append(
			Finding(
				"cleartext-traffic",
				28,
				"app/src/main/AndroidManifest.xml:application",
				"usesCleartextTraffic=true widens the network security policy; scope it to the "
				"loopback build server in network_security_config instead.",
			)
		)

	for permission in manifest.findall("uses-permission"):
		name = permission.get(f"{ANDROID}name", "")
		if name.endswith(".MANAGE_EXTERNAL_STORAGE"):
			findings.append(
				Finding(
					"manage-external-storage",
					30,
					"app/src/main/AndroidManifest.xml:uses-permission",
					"MANAGE_EXTERNAL_STORAGE requires a Play policy exemption and a user-granted "
					"special access screen once the target reaches 30.",
				)
			)
		if name.endswith(".READ_EXTERNAL_STORAGE") and permission.get(f"{ANDROID}maxSdkVersion") is None:
			findings.append(
				Finding(
					"broad-read-external-storage",
					33,
					"app/src/main/AndroidManifest.xml:uses-permission",
					"READ_EXTERNAL_STORAGE stops being granted at API 33; declare maxSdkVersion and "
					"move media access to the granular permissions.",
				)
			)

	for component in application:
		if component.tag not in {"activity", "service", "receiver", "provider"}:
			continue
		name = component.get(f"{ANDROID}name", "<unnamed>")
		location = f"app/src/main/AndroidManifest.xml:{component.tag}:{name}"
		if component.findall("intent-filter") and component.get(f"{ANDROID}exported") is None:
			findings.append(
				Finding(
					"missing-exported",
					31,
					location,
					"A component with an intent filter must declare android:exported from API 31; "
					"installation fails otherwise.",
				)
			)
		if component.tag == "service" and component.get(f"{ANDROID}foregroundServiceType") is None:
			if name in _foreground_service_names(root):
				findings.append(
					Finding(
						"missing-foreground-service-type",
						FOREGROUND_SERVICE_TYPE_API,
						location,
						"This service calls startForeground but declares no foregroundServiceType; "
						"API 34 throws MissingForegroundServiceTypeException.",
					)
				)

	return findings


def _foreground_service_names(root: pathlib.Path) -> set[str]:
	"""Return manifest-style names of classes that call startForeground."""
	names: set[str] = set()
	for path, text in _iter_sources(root):
		if not START_FOREGROUND_PATTERN.search(text):
			continue
		parts = path.with_suffix("").parts
		if "java" in parts:
			qualified = ".".join(parts[parts.index("java") + 1 :])
		elif "kotlin" in parts:
			qualified = ".".join(parts[parts.index("kotlin") + 1 :])
		else:
			qualified = path.stem
		names.add(qualified)
		names.add(qualified.replace("com.itsaky.androidide", ""))
	return names


def _source_findings(root: pathlib.Path) -> list[Finding]:
	findings: list[Finding] = []
	for path, text in _iter_sources(root):
		lines = text.splitlines()
		for number, line in enumerate(lines, start=1):
			# A multi-line call keeps its flags a few lines below the opening call, so the
			# window has to cover a formatted argument list, not just the matched line.
			window = "\n".join(lines[number - 1 : number + 8])
			if PENDING_INTENT_PATTERN.search(line) and not MUTABILITY_PATTERN.search(window):
				findings.append(
					Finding(
						"mutable-pending-intent",
						31,
						f"{path}:{number}",
						"PendingIntent without FLAG_IMMUTABLE/FLAG_MUTABLE throws from API 31.",
					)
				)
			if (
				REGISTER_RECEIVER_PATTERN.search(line)
				and "ContextCompat" not in line
				and "registerReceiver(null" not in line.replace(" ", "")
				and not RECEIVER_FLAG_PATTERN.search(window)
			):
				findings.append(
					Finding(
						"unflagged-receiver",
						34,
						f"{path}:{number}",
						"registerReceiver for a non-protected broadcast must pass RECEIVER_EXPORTED "
						"or RECEIVER_NOT_EXPORTED from API 34.",
					)
				)
	return findings


def collect(root: pathlib.Path = REPOSITORY_ROOT) -> Inventory:
	"""Return every detected target-SDK blocker, sorted deterministically."""
	findings = _manifest_findings(root) + _source_findings(root)
	return Inventory(target_sdk=_current_target_sdk(root), findings=sorted(set(findings)))


def load_baseline(root: pathlib.Path = REPOSITORY_ROOT) -> dict[str, object]:
	path = root / BASELINE
	if not path.is_file():
		return {"schemaVersion": 1, "targetSdk": None, "accepted": []}
	try:
		document = json.loads(path.read_text(encoding="utf-8"))
	except (OSError, json.JSONDecodeError) as error:
		raise ReadinessError(f"cannot read {BASELINE}: {error}") from error
	if not isinstance(document, dict) or document.get("schemaVersion") != 1:
		raise ReadinessError(f"{BASELINE}: schemaVersion must be 1")
	if not isinstance(document.get("accepted"), list):
		raise ReadinessError(f"{BASELINE}: accepted must be an array")
	return document


def compare(inventory: Inventory, baseline: dict[str, object]) -> tuple[list[Finding], list[str]]:
	"""Return findings missing from the baseline and baseline entries now fixed."""
	accepted = {str(entry.get("key")) for entry in baseline["accepted"] if isinstance(entry, dict)}
	current = {finding.key(): finding for finding in inventory.findings}
	new = sorted(finding for key, finding in current.items() if key not in accepted)
	resolved = sorted(key for key in accepted if key not in current)
	return new, resolved


def _render(inventory: Inventory) -> str:
	lines = [f"target SDK: {inventory.target_sdk}", f"blockers: {len(inventory.findings)}", ""]
	for finding in inventory.findings:
		lines.append(f"[API {finding.api}] {finding.id}")
		lines.append(f"  {finding.location}")
		lines.append(f"  {finding.summary}")
	return "\n".join(lines)


def _baseline_document(inventory: Inventory) -> dict[str, object]:
	return {
		"schemaVersion": 1,
		"targetSdk": inventory.target_sdk,
		"comment": (
			"Accepted target-SDK migration blockers. Shrink this list; never grow it. "
			"Regenerate with scripts/target_sdk_readiness.py --write-baseline only when a "
			"reviewer has agreed that a new entry is unavoidable."
		),
		"accepted": [{"key": finding.key(), **finding.as_dict()} for finding in inventory.findings],
	}


def main(argv: list[str] | None = None) -> int:
	parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
	parser.add_argument("--root", type=pathlib.Path, default=REPOSITORY_ROOT)
	parser.add_argument("--report", action="store_true", help="print the inventory and exit 0")
	parser.add_argument("--write-baseline", action="store_true", help="rewrite the accepted list")
	arguments = parser.parse_args(argv)

	try:
		inventory = collect(arguments.root)
		if arguments.report:
			print(_render(inventory))
			return 0
		if arguments.write_baseline:
			path = arguments.root / BASELINE
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_text(
				json.dumps(_baseline_document(inventory), indent=2, ensure_ascii=False) + "\n",
				encoding="utf-8",
			)
			print(f"wrote {BASELINE} with {len(inventory.findings)} accepted blockers")
			return 0

		baseline = load_baseline(arguments.root)
		new, resolved = compare(inventory, baseline)
		for finding in new:
			print(
				f"NEW BLOCKER [API {finding.api}] {finding.id} at {finding.location}: {finding.summary}",
				file=sys.stderr,
			)
		for key in resolved:
			print(
				f"RESOLVED: {key} no longer occurs; remove it from {BASELINE} to lock the fix in.",
				file=sys.stderr,
			)
		if new or resolved:
			return 1
		print(
			f"OK: target SDK {inventory.target_sdk} with {len(inventory.findings)} accepted, "
			"unchanged migration blockers"
		)
		return 0
	except ReadinessError as error:
		print(f"target sdk readiness error: {error}", file=sys.stderr)
		return 1


if __name__ == "__main__":
	raise SystemExit(main())
