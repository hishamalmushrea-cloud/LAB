package com.itsaky.androidide.doctor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The doctor's value is entirely in which conditions it calls a problem and how it ranks them, so
 * these tests pin the rules rather than the wording.
 */
class EnvironmentDoctorTest {
	private val healthy =
		EnvironmentSnapshot(
			installedAbi = "arm64-v8a",
			supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
			androidApiLevel = 33,
			totalMemoryBytes = 8L * 1024 * 1024 * 1024,
			freeStorageBytes = 20L * 1024 * 1024 * 1024,
			javaHomeInstalled = true,
			androidSdkInstalled = true,
			assets = listOf(AssetStatus("android-sdk", AssetState.OK)),
			batteryFraction = 0.9f,
			charging = true,
		)

	private fun ids(snapshot: EnvironmentSnapshot) = EnvironmentDoctor.diagnose(snapshot).map { it.id }

	@Test
	fun `a healthy environment yields no findings`() {
		assertThat(EnvironmentDoctor.diagnose(healthy)).isEmpty()
	}

	@Test
	fun `an unsupported ABI is a blocker`() {
		val finding =
			EnvironmentDoctor
				.diagnose(healthy.copy(installedAbi = "x86_64"))
				.single()

		assertThat(finding.id).isEqualTo("abi-mismatch")
		assertThat(finding.severity).isEqualTo(Severity.BLOCKER)
		assertThat(finding.remedy).isEqualTo(Remedy.REINSTALL_MATCHING_ABI)
	}

	@Test
	fun `an unknown ABI list is not reported as a mismatch`() {
		// A device that reports nothing is a gap in our knowledge, not a proven incompatibility;
		// claiming the install is wrong would be a false alarm the user cannot act on.
		assertThat(ids(healthy.copy(supportedAbis = emptyList()))).isEmpty()
	}

	@Test
	fun `storage is graded in two bands`() {
		assertThat(ids(healthy.copy(freeStorageBytes = 1L * 1024 * 1024 * 1024)))
			.containsExactly("storage-critical")
		assertThat(ids(healthy.copy(freeStorageBytes = 4L * 1024 * 1024 * 1024)))
			.containsExactly("storage-low")
		assertThat(ids(healthy.copy(freeStorageBytes = EnvironmentDoctor.COMFORTABLE_FREE_BYTES))).isEmpty()
	}

	@Test
	fun `the storage thresholds are exact boundaries`() {
		assertThat(ids(healthy.copy(freeStorageBytes = EnvironmentDoctor.MINIMUM_FREE_BYTES - 1)))
			.containsExactly("storage-critical")
		assertThat(ids(healthy.copy(freeStorageBytes = EnvironmentDoctor.MINIMUM_FREE_BYTES)))
			.containsExactly("storage-low")
	}

	@Test
	fun `a small device warns about the daemon heap`() {
		val finding =
			EnvironmentDoctor
				.diagnose(healthy.copy(totalMemoryBytes = 2L * 1024 * 1024 * 1024))
				.single()

		assertThat(finding.id).isEqualTo("memory-small")
		assertThat(finding.remedy).isEqualTo(Remedy.REDUCE_GRADLE_MEMORY)
	}

	@Test
	fun `unknown memory is not reported as small`() {
		assertThat(ids(healthy.copy(totalMemoryBytes = 0))).isEmpty()
	}

	@Test
	fun `a missing toolchain blocks builds`() {
		assertThat(ids(healthy.copy(javaHomeInstalled = false, androidSdkInstalled = false)))
			.containsExactly("jdk-missing", "sdk-missing")
	}

	@Test
	fun `a corrupt asset outranks a missing one`() {
		val findings =
			EnvironmentDoctor.diagnose(
				healthy.copy(
					assets =
						listOf(
							AssetStatus("gradle", AssetState.MISSING),
							AssetStatus("android-sdk", AssetState.CHECKSUM_MISMATCH),
						),
				),
			)

		assertThat(findings.map { it.id }).containsExactly("assets-corrupt", "assets-missing").inOrder()
		assertThat(findings.first().severity).isEqualTo(Severity.BLOCKER)
		assertThat(findings.last().severity).isEqualTo(Severity.WARNING)
	}

	@Test
	fun `a low battery matters only when unplugged`() {
		assertThat(ids(healthy.copy(batteryFraction = 0.1f, charging = true))).isEmpty()
		assertThat(ids(healthy.copy(batteryFraction = 0.1f, charging = false)))
			.containsExactly("battery-low")
	}

	@Test
	fun `an unknown battery level is not reported`() {
		assertThat(ids(healthy.copy(batteryFraction = -1f, charging = false))).isEmpty()
	}

	@Test
	fun `findings are ordered by severity`() {
		val findings =
			EnvironmentDoctor.diagnose(
				healthy.copy(
					batteryFraction = 0.05f,
					charging = false,
					thermalThrottling = true,
					javaHomeInstalled = false,
				),
			)

		assertThat(findings.map { it.severity })
			.containsExactly(Severity.BLOCKER, Severity.WARNING, Severity.INFO)
			.inOrder()
	}

	@Test
	fun `every finding carries a distinct id`() {
		val findings =
			EnvironmentDoctor.diagnose(
				healthy.copy(
					installedAbi = "x86_64",
					freeStorageBytes = 0,
					totalMemoryBytes = 1L * 1024 * 1024 * 1024,
					lowMemory = true,
					javaHomeInstalled = false,
					androidSdkInstalled = false,
					assets = listOf(AssetStatus("a", AssetState.CHECKSUM_MISMATCH)),
					thermalThrottling = true,
					batteryFraction = 0.05f,
				),
			)

		assertThat(findings.map { it.id }).containsNoDuplicates()
		assertThat(findings).isNotEmpty()
	}

	@Test
	fun `byte formatting matches what storage settings show`() {
		assertThat(EnvironmentDoctor.formatBytes(0)).isEqualTo("0 B")
		assertThat(EnvironmentDoctor.formatBytes(512)).isEqualTo("512 B")
		assertThat(EnvironmentDoctor.formatBytes(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GB")
		assertThat(EnvironmentDoctor.formatBytes(-1)).isEqualTo("unknown")
	}
}
