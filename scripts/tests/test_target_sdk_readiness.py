import json
import pathlib
import tempfile
import unittest

import scripts.target_sdk_readiness as readiness

MANIFEST_TEMPLATE = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
{permissions}
	<application{application_attributes}>
{components}
	</application>
</manifest>
"""

BUILD_CONFIG_TEMPLATE = "object BuildConfig {{\n\tconst val TARGET_SDK = {target}\n}}\n"


class TargetSdkReadinessTest(unittest.TestCase):
	def setUp(self):
		self._temporary = tempfile.TemporaryDirectory()
		self.addCleanup(self._temporary.cleanup)
		self.root = pathlib.Path(self._temporary.name)
		self.write_project()

	def write_project(
		self,
		*,
		permissions: str = "",
		application_attributes: str = "",
		components: str = "",
		sources: dict[str, str] | None = None,
		target: int = 28,
	) -> None:
		manifest = self.root / readiness.MANIFEST
		manifest.parent.mkdir(parents=True, exist_ok=True)
		manifest.write_text(
			MANIFEST_TEMPLATE.format(
				permissions=permissions,
				application_attributes=application_attributes,
				components=components,
			),
			encoding="utf-8",
		)
		build_config = self.root / readiness.BUILD_CONFIG
		build_config.parent.mkdir(parents=True, exist_ok=True)
		build_config.write_text(BUILD_CONFIG_TEMPLATE.format(target=target), encoding="utf-8")

		source_root = self.root / "app/src/main/java/com/itsaky/androidide"
		source_root.mkdir(parents=True, exist_ok=True)
		for name, body in (sources or {}).items():
			path = source_root / name
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_text(body, encoding="utf-8")

	def ids(self) -> list[str]:
		return [finding.id for finding in readiness.collect(self.root).findings]

	def test_clean_project_reports_no_blockers(self):
		self.assertEqual([], self.ids())
		self.assertEqual(28, readiness.collect(self.root).target_sdk)

	def test_detects_manifest_storage_and_cleartext_blockers(self):
		self.write_project(
			permissions=(
				'\t<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />\n'
				'\t<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />'
			),
			application_attributes=(
				' android:requestLegacyExternalStorage="true" android:usesCleartextTraffic="true"'
			),
		)

		self.assertEqual(
			[
				"broad-read-external-storage",
				"cleartext-traffic",
				"legacy-external-storage",
				"manage-external-storage",
			],
			self.ids(),
		)

	def test_read_external_storage_with_max_sdk_is_accepted(self):
		self.write_project(
			permissions=(
				'\t<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"'
				' android:maxSdkVersion="32" />'
			)
		)

		self.assertEqual([], self.ids())

	def test_detects_component_without_exported(self):
		self.write_project(
			components=(
				'\t\t<activity android:name=".Main">\n'
				"\t\t\t<intent-filter>\n"
				'\t\t\t\t<action android:name="android.intent.action.MAIN" />\n'
				"\t\t\t</intent-filter>\n"
				"\t\t</activity>"
			)
		)

		self.assertEqual(["missing-exported"], self.ids())

	def test_foreground_service_type_required_only_for_services_that_start_foreground(self):
		components = '\t\t<service android:name=".services.Build" android:exported="false" />'
		self.write_project(components=components, sources={"services/Build.kt": "class Build\n"})
		self.assertEqual([], self.ids())

		self.write_project(
			components=components,
			sources={"services/Build.kt": "class Build {\n\tfun go() = startForeground(1, n)\n}\n"},
		)
		self.assertEqual(["missing-foreground-service-type"], self.ids())

		self.write_project(
			components=(
				'\t\t<service android:name=".services.Build" android:exported="false"'
				' android:foregroundServiceType="dataSync" />'
			),
			sources={"services/Build.kt": "class Build {\n\tfun go() = startForeground(1, n)\n}\n"},
		)
		self.assertEqual([], self.ids())

	def test_detects_pending_intent_without_mutability_flag(self):
		self.write_project(
			sources={"Notifier.kt": "val i = PendingIntent.getActivity(this, 0, x, FLAG_UPDATE_CURRENT)\n"}
		)
		self.assertEqual(["mutable-pending-intent"], self.ids())

	def test_accepts_multi_line_pending_intent_with_immutable_flag(self):
		self.write_project(
			sources={
				"Notifier.kt": (
					"val i =\n"
					"\tPendingIntent.getActivity(\n"
					"\t\tthis,\n"
					"\t\t0,\n"
					"\t\tlaunch,\n"
					"\t\tPendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,\n"
					"\t)\n"
				)
			}
		)
		self.assertEqual([], self.ids())

	def test_detects_unflagged_register_receiver_but_accepts_compat_and_sticky_reads(self):
		self.write_project(sources={"App.kt": "fun go() = registerReceiver(receiver, filter)\n"})
		self.assertEqual(["unflagged-receiver"], self.ids())

		self.write_project(
			sources={
				"App.kt": (
					"fun go() =\n"
					"\tContextCompat.registerReceiver(\n"
					"\t\tcontext,\n"
					"\t\treceiver,\n"
					"\t\tfilter,\n"
					"\t\tContextCompat.RECEIVER_NOT_EXPORTED,\n"
					"\t)\n"
				),
				"Power.kt": "val last = context.registerReceiver(null, IntentFilter(ACTION))\n",
			}
		)
		self.assertEqual([], self.ids())

	def test_ratchet_flags_new_and_resolved_entries(self):
		self.write_project(application_attributes=' android:usesCleartextTraffic="true"')
		inventory = readiness.collect(self.root)

		empty_baseline = {"schemaVersion": 1, "targetSdk": 28, "accepted": []}
		new, resolved = readiness.compare(inventory, empty_baseline)
		self.assertEqual(["cleartext-traffic"], [finding.id for finding in new])
		self.assertEqual([], resolved)

		matching = {
			"schemaVersion": 1,
			"targetSdk": 28,
			"accepted": [{"key": finding.key()} for finding in inventory.findings],
		}
		self.assertEqual(([], []), readiness.compare(inventory, matching))

		stale = {
			"schemaVersion": 1,
			"targetSdk": 28,
			"accepted": matching["accepted"] + [{"key": "gone:somewhere"}],
		}
		new, resolved = readiness.compare(inventory, stale)
		self.assertEqual([], new)
		self.assertEqual(["gone:somewhere"], resolved)

	def test_rejects_unknown_baseline_schema(self):
		path = self.root / readiness.BASELINE
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(json.dumps({"schemaVersion": 2, "accepted": []}), encoding="utf-8")

		with self.assertRaisesRegex(readiness.ReadinessError, "schemaVersion must be 1"):
			readiness.load_baseline(self.root)

	def test_requires_a_target_sdk_declaration(self):
		(self.root / readiness.BUILD_CONFIG).write_text("object BuildConfig\n", encoding="utf-8")

		with self.assertRaisesRegex(readiness.ReadinessError, "no TARGET_SDK assignment"):
			readiness.collect(self.root)

	def test_cli_round_trip_writes_a_passing_baseline(self):
		self.write_project(application_attributes=' android:usesCleartextTraffic="true"')

		self.assertEqual(1, readiness.main(["--root", str(self.root)]))
		self.assertEqual(0, readiness.main(["--root", str(self.root), "--write-baseline"]))
		self.assertEqual(0, readiness.main(["--root", str(self.root)]))
		self.assertEqual(0, readiness.main(["--root", str(self.root), "--report"]))


class RepositoryBaselineTest(unittest.TestCase):
	def test_tracked_repository_matches_its_baseline(self):
		inventory = readiness.collect()
		new, resolved = readiness.compare(inventory, readiness.load_baseline())

		self.assertEqual([], [finding.key() for finding in new])
		self.assertEqual([], resolved)


if __name__ == "__main__":
	unittest.main()
