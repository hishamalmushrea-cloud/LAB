package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.utils.Environment
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The allowlist is a trust boundary: a path it admits is a path a plugin may touch with the IDE's
 * own authority, because plugins share the app process. These tests pin the containment rules and
 * the fail-closed behaviour so the three-way duplication that used to exist cannot silently return.
 */
class PluginPathAllowlistTest {
	private lateinit var projectsDir: File
	private var originalProjectsDir: File? = null
	private var originalAndroidHome: File? = null
	private var originalTmpDir: File? = null
	private var originalIdeHome: File? = null

	private val noPermissions = emptySet<PluginPermission>()
	private val writePermission = setOf(PluginPermission.IDE_ENVIRONMENT_WRITE)

	@Before
	fun setUp() {
		originalProjectsDir = Environment.PROJECTS_DIR
		originalAndroidHome = Environment.ANDROID_HOME
		originalTmpDir = Environment.TMP_DIR
		originalIdeHome = Environment.ANDROIDIDE_HOME

		projectsDir =
			File.createTempFile("plugin-allowlist-", "").apply {
				delete()
				mkdirs()
			}
		Environment.PROJECTS_DIR = projectsDir
	}

	@After
	fun tearDown() {
		Environment.PROJECTS_DIR = originalProjectsDir
		Environment.ANDROID_HOME = originalAndroidHome
		Environment.TMP_DIR = originalTmpDir
		Environment.ANDROIDIDE_HOME = originalIdeHome
		projectsDir.deleteRecursively()
	}

	@Test
	fun `admits the configured projects directory and paths under it`() {
		val nested = File(projectsDir, "app/src/main/Main.kt")

		assertTrue(PluginPathAllowlist.isAllowed(projectsDir, noPermissions, "p"))
		assertTrue(PluginPathAllowlist.isAllowed(nested, noPermissions, "p"))
	}

	@Test
	fun `rejects a sibling directory sharing the allowed root's prefix`() {
		val sibling = File(projectsDir.parentFile, projectsDir.name + "Backup/secret.txt")

		assertFalse(PluginPathAllowlist.isAllowed(sibling, noPermissions, "p"))
	}

	@Test
	fun `rejects a parent of the allowed root and an unrelated path`() {
		assertFalse(PluginPathAllowlist.isAllowed(projectsDir.parentFile, noPermissions, "p"))
		assertFalse(PluginPathAllowlist.isAllowed(File("/etc/passwd"), noPermissions, "p"))
	}

	@Test
	fun `rejects traversal that escapes the allowed root`() {
		val escaping = File(projectsDir, "../../etc/passwd")

		assertFalse(PluginPathAllowlist.isAllowed(escaping, noPermissions, "p"))
	}

	@Test
	fun `prefers the configured projects directory over legacy shared storage roots`() {
		val paths = PluginPathAllowlist.defaultAllowedPaths(noPermissions, "p")

		assertTrue(paths.contains(projectsDir.canonicalPath))
		assertFalse(paths.any { it.startsWith("/sdcard") })
		assertFalse(paths.any { it.startsWith("/storage/emulated") })
	}

	@Test
	fun `falls back to legacy roots only when the projects directory is unset`() {
		Environment.PROJECTS_DIR = null

		val paths = PluginPathAllowlist.defaultAllowedPaths(noPermissions, "p")

		assertTrue(paths.any { it.endsWith(Environment.PROJECTS_FOLDER) })
	}

	@Test
	fun `never yields a blank root which would admit every absolute path`() {
		Environment.PROJECTS_DIR = null
		Environment.ANDROID_HOME = null
		Environment.TMP_DIR = null
		Environment.ANDROIDIDE_HOME = null

		val paths = PluginPathAllowlist.defaultAllowedPaths(writePermission, "p")

		assertTrue(paths.none { it.isBlank() })
		assertFalse(PluginPathAllowlist.isAllowed(File("/etc/passwd"), writePermission, "p"))
	}

	@Test
	fun `environment paths require the environment write permission`() {
		val androidHome =
			File.createTempFile("allowlist-sdk-", "").apply {
				delete()
				mkdirs()
			}
		Environment.ANDROID_HOME = androidHome
		try {
			val target = File(androidHome, "platforms/android-36/android.jar")

			assertFalse(PluginPathAllowlist.isAllowed(target, noPermissions, "p"))
			assertTrue(PluginPathAllowlist.isAllowed(target, writePermission, "p"))
		} finally {
			androidHome.deleteRecursively()
		}
	}

	@Test
	fun `a plugin's data directory is scoped to that plugin`() {
		val ideHome =
			File.createTempFile("allowlist-home-", "").apply {
				delete()
				mkdirs()
			}
		Environment.ANDROIDIDE_HOME = ideHome
		try {
			val mine = File(ideHome, "plugins/mine/data.json")
			val other = File(ideHome, "plugins/other/data.json")

			assertTrue(PluginPathAllowlist.isAllowed(mine, writePermission, "mine"))
			assertFalse(PluginPathAllowlist.isAllowed(other, writePermission, "mine"))
		} finally {
			ideHome.deleteRecursively()
		}
	}

	@Test
	fun `the returned roots contain no duplicates`() {
		val paths = PluginPathAllowlist.defaultAllowedPaths(writePermission, "p")

		assertTrue(paths.size == paths.distinct().size)
	}
}
