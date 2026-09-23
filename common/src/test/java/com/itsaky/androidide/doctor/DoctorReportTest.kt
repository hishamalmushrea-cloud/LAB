package com.itsaky.androidide.doctor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A report is meant to be pasted into a public bug tracker, so the redaction tests below are the
 * important ones: a leak here is a privacy incident, not a formatting bug.
 */
class DoctorReportTest {
	private val snapshot =
		EnvironmentSnapshot(
			installedAbi = "arm64-v8a",
			supportedAbis = listOf("arm64-v8a"),
			androidApiLevel = 33,
			totalMemoryBytes = 4L * 1024 * 1024 * 1024,
			freeStorageBytes = 10L * 1024 * 1024 * 1024,
		)

	@Test
	fun `redacts absolute paths that would name the user's projects`() {
		val redacted = DoctorReport.redact("Failed at /storage/emulated/0/CodeOnTheGoProjects/SecretApp/build")

		assertThat(redacted).doesNotContain("SecretApp")
		assertThat(redacted).doesNotContain("CodeOnTheGoProjects")
		assertThat(redacted).contains("[redacted]")
	}

	@Test
	fun `redacts app private and home paths too`() {
		for (path in listOf("/data/data/com.itsaky.androidide/files", "/home/ahmed/work", "/Users/ahmed/x")) {
			assertThat(DoctorReport.redact("at $path here")).doesNotContain(path)
		}
	}

	@Test
	fun `redacts email addresses`() {
		assertThat(DoctorReport.redact("reported by ahmed.saleh@example.com"))
			.isEqualTo("reported by [redacted]")
	}

	@Test
	fun `redacts long hex runs such as tokens and digests`() {
		val token = "a".repeat(64)

		assertThat(DoctorReport.redact("session=$token")).doesNotContain(token)
	}

	@Test
	fun `leaves ordinary text and short hex alone`() {
		val text = "Build failed after 3 tasks; free space 2.0 GB; code abc123"

		assertThat(DoctorReport.redact(text)).isEqualTo(text)
	}

	@Test
	fun `renders a clean environment without findings`() {
		val report = DoctorReport.render(snapshot, emptyList())

		assertThat(report).contains("No problems detected.")
		assertThat(report).contains("ABI: arm64-v8a")
		assertThat(report).contains("Free storage: 10.0 GB")
	}

	@Test
	fun `renders findings and their remedies`() {
		val findings = EnvironmentDoctor.diagnose(snapshot.copy(javaHomeInstalled = false))

		val report = DoctorReport.render(snapshot, findings)

		assertThat(report).contains("[BLOCKER] jdk-missing")
		assertThat(report).contains("Fix: RUN_SETUP")
	}

	@Test
	fun `omits the fix line when nothing can be fixed automatically`() {
		val findings =
			listOf(Finding("thermal", Severity.WARNING, "Hot.", "Wait for it to cool.", Remedy.NONE))

		assertThat(DoctorReport.render(snapshot, findings)).doesNotContain("Fix:")
	}

	@Test
	fun `redacts text that reached a finding from elsewhere`() {
		// Findings are authored to be clean, but anything interpolated into one - an asset id read
		// from a manifest, say - must still not survive into the report.
		val findings =
			listOf(
				Finding(
					id = "assets-missing",
					severity = Severity.WARNING,
					summary = "missing /storage/emulated/0/CodeOnTheGoProjects/Client/app.jar",
					detail = "contact ahmed@example.com",
					remedy = Remedy.REDOWNLOAD_ASSETS,
				),
			)

		val report = DoctorReport.render(snapshot, findings)

		assertThat(report).doesNotContain("Client")
		assertThat(report).doesNotContain("ahmed@example.com")
	}

	@Test
	fun `summarises asset states`() {
		val report =
			DoctorReport.render(
				snapshot.copy(
					assets =
						listOf(
							AssetStatus("a", AssetState.OK),
							AssetStatus("b", AssetState.OK),
							AssetStatus("c", AssetState.MISSING),
						),
				),
				emptyList(),
			)

		assertThat(report).contains("OK=2")
		assertThat(report).contains("MISSING=1")
	}

	@Test
	fun `reports unknown values rather than misleading zeros`() {
		val report = DoctorReport.render(EnvironmentSnapshot(), emptyList())

		assertThat(report).contains("ABI: unknown")
		assertThat(report).contains("Device ABIs: unknown")
		assertThat(report).contains("Free storage: unknown")
	}
}
