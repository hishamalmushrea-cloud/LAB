package com.itsaky.androidide.localWebServer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * These switches were world-writable files under shared `Download/`, so another app could turn on
 * verbose request logging in someone's release build. The tests below pin the two properties that
 * fix depends on: the paths are app-private, and a release build cannot enable them at all.
 */
class DeveloperOverridesTest {
	private val filesDir = File("/data/data/com.itsaky.androidide/files")

	@Test
	fun `debug build resolves switches inside the app's files directory`() {
		val overrides = DeveloperOverrides.forDebugBuild(isDebugBuild = true, filesDir = filesDir)

		val expectedRoot = File(filesDir, DeveloperOverrides.OVERRIDES_DIR_NAME).path
		assertEquals(File(expectedRoot, DeveloperOverrides.DEBUG_FILE_NAME).path, overrides.debugEnablePath)
		assertEquals(
			File(expectedRoot, DeveloperOverrides.EXPERIMENTS_FILE_NAME).path,
			overrides.experimentsEnablePath,
		)
		assertEquals(
			File(expectedRoot, DeveloperOverrides.CLEAR_CACHE_FILE_NAME).path,
			overrides.clearCacheEnablePath,
		)
		assertEquals(
			File(expectedRoot, DeveloperOverrides.DEBUG_DATABASE_FILE_NAME).path,
			overrides.debugDatabasePath,
		)
	}

	@Test
	fun `release build gets paths that cannot be enabled`() {
		val overrides = DeveloperOverrides.forDebugBuild(isDebugBuild = false, filesDir = filesDir)

		assertEquals(DeveloperOverrides.disabled(), overrides)
		for (path in overrides.allPaths()) {
			assertFalse("a release switch must not be under the app files dir", path.startsWith(filesDir.path))
			assertFalse("a release switch must never exist", File(path).exists())
		}
	}

	@Test
	fun `no switch resolves to shared storage`() {
		val debug = DeveloperOverrides.forDebugBuild(isDebugBuild = true, filesDir = filesDir)

		for (path in debug.allPaths() + DeveloperOverrides.disabled().allPaths()) {
			assertFalse(path, path.startsWith("/sdcard"))
			assertFalse(path, path.startsWith("/storage/emulated"))
			assertFalse(path, path.contains("/Download/"))
		}
	}

	@Test
	fun `every switch is a distinct absolute path`() {
		val paths = DeveloperOverrides.forDebugBuild(isDebugBuild = true, filesDir = filesDir).allPaths()

		assertEquals(paths.size, paths.distinct().size)
		assertTrue(paths.all { File(it).isAbsolute })
	}

	@Test
	fun `switches read as enabled only when the file is present`() {
		val temporaryFilesDir =
			File.createTempFile("cogo-overrides-", "").apply {
				delete()
				mkdirs()
			}
		try {
			val overrides = DeveloperOverrides.inFilesDir(temporaryFilesDir)
			assertFalse(File(overrides.debugEnablePath).exists())

			File(overrides.debugEnablePath).apply {
				parentFile?.mkdirs()
				writeText("")
			}

			assertTrue(File(overrides.debugEnablePath).exists())
			assertFalse(File(overrides.clearCacheEnablePath).exists())
		} finally {
			temporaryFilesDir.deleteRecursively()
		}
	}

	private fun DeveloperOverrides.allPaths() = listOf(debugEnablePath, experimentsEnablePath, clearCacheEnablePath, debugDatabasePath)
}
