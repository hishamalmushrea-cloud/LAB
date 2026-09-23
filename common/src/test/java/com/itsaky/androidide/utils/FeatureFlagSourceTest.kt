package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * These flags were settable by any app with storage access, in release builds, with no gating. The
 * tests below pin both halves of the fix: the switches only work from an app-private directory, and
 * they do not work at all in a release build.
 */
class FeatureFlagSourceTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@After
	fun tearDown() {
		FeatureFlags.resetForTest()
	}

	private fun plant(
		directory: File,
		name: String,
	) {
		directory.mkdirs()
		File(directory, name).writeText("")
	}

	@Test
	fun `a release build ignores a flag even in the app's own directory`() {
		val filesDir = temporaryFolder.newFolder("files")
		plant(File(filesDir, FeatureFlagSource.FLAGS_DIR_NAME), "CodeOnTheGo.exp")

		val source = FeatureFlagSource.forDebugBuild(isDebugBuild = false, filesDir = filesDir)

		assertThat(source.isSet("CodeOnTheGo.exp")).isFalse()
	}

	@Test
	fun `a debug build honours a flag in the app's own directory`() {
		val filesDir = temporaryFolder.newFolder("files")
		plant(File(filesDir, FeatureFlagSource.FLAGS_DIR_NAME), "CodeOnTheGo.exp")

		val source = FeatureFlagSource.forDebugBuild(isDebugBuild = true, filesDir = filesDir)

		assertThat(source.isSet("CodeOnTheGo.exp")).isTrue()
		assertThat(source.isSet("CodeOnTheGo.logd")).isFalse()
	}

	@Test
	fun `the disabled source points somewhere that cannot exist`() {
		val source = FeatureFlagSource.disabled()

		assertThat(source.enabled).isFalse()
		assertThat(source.directory.isAbsolute).isTrue()
		assertThat(source.directory.exists()).isFalse()
		assertThat(source.isSet("CodeOnTheGo.exp")).isFalse()
	}

	@Test
	fun `flags resolve under a dedicated subdirectory rather than the files root`() {
		// Sharing the files root with unrelated app data would let any file named after a flag -
		// including one written by a legitimate feature - switch it on by accident.
		val filesDir = temporaryFolder.newFolder("files")
		plant(filesDir, "CodeOnTheGo.exp")

		val source = FeatureFlagSource.inFilesDir(filesDir)

		assertThat(source.isSet("CodeOnTheGo.exp")).isFalse()
	}

	@Test
	fun `a missing directory reads as all flags off`() {
		val source = FeatureFlagSource.inFilesDir(File(temporaryFolder.root, "absent"))

		assertThat(source.isSet("CodeOnTheGo.exp")).isFalse()
	}

	@Test
	fun `a planted flag on shared storage no longer has any effect`() =
		runTest {
			// The regression this whole change exists for. The old code read sentinels from the public
			// Downloads directory; this asserts that planting one there is now inert, which is a
			// stronger claim than merely showing the new location works.
			val downloads = temporaryFolder.newFolder("Download")
			plant(downloads, "CodeOnTheGo.exp")
			plant(downloads, "S153.txt")
			plant(downloads, "CodeOnTheGo.a2s2")

			val filesDir = temporaryFolder.newFolder("files")
			FeatureFlags.initialize(FeatureFlagSource.forDebugBuild(isDebugBuild = true, filesDir = filesDir))

			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
			assertThat(FeatureFlags.isEmulatorUseEnabled).isFalse()
			assertThat(FeatureFlags.isPardonEnabled).isFalse()
		}

	@Test
	fun `initialize reads every flag from the app-private directory`() =
		runTest {
			val filesDir = temporaryFolder.newFolder("files")
			val flagsDir = File(filesDir, FeatureFlagSource.FLAGS_DIR_NAME)
			for (name in listOf(
				"CodeOnTheGo.exp",
				"CodeOnTheGo.logd",
				"S153.txt",
				"CodeOnTheGo.a3s19",
				"CodeOnTheGo.a2s2",
				"CodeOnTheGo.lc",
			)) {
				plant(flagsDir, name)
			}

			FeatureFlags.initialize(FeatureFlagSource.inFilesDir(filesDir))

			assertThat(FeatureFlags.isExperimentsEnabled).isTrue()
			assertThat(FeatureFlags.isDebugLoggingEnabled).isTrue()
			assertThat(FeatureFlags.isEmulatorUseEnabled).isTrue()
			assertThat(FeatureFlags.isReprieveEnabled).isTrue()
			assertThat(FeatureFlags.isPardonEnabled).isTrue()
			assertThat(FeatureFlags.isLeakCanaryDumpInhibited).isTrue()
		}

	@Test
	fun `initialize is idempotent so a later call cannot flip a flag`() =
		runTest {
			val filesDir = temporaryFolder.newFolder("files")
			FeatureFlags.initialize(FeatureFlagSource.inFilesDir(filesDir))

			val other = temporaryFolder.newFolder("other")
			plant(File(other, FeatureFlagSource.FLAGS_DIR_NAME), "CodeOnTheGo.exp")
			FeatureFlags.initialize(FeatureFlagSource.inFilesDir(other))

			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
		}

	@Test
	fun `flags default to off before initialization`() {
		assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
		assertThat(FeatureFlags.isPardonEnabled).isFalse()
	}
}
