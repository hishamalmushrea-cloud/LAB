package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.utils.Environment
import java.io.File

/**
 * The single source of truth for which filesystem paths a plugin may touch.
 *
 * This list is a trust boundary: plugins run in the IDE's own process, so a path that appears here
 * is a path a plugin can read or write with the app's full authority. It used to be duplicated in
 * three places ([PluginPathAllowlist], `IdeEditorServiceImpl` and `IdeProjectServiceImpl`), and the
 * copies had already drifted - two of them ignored the configured projects directory entirely and
 * matched only hardcoded `/sdcard` and `/storage/emulated/0` prefixes, and one admitted
 * `/tmp/AndroidIDEProject` where the others admitted `/tmp/CodeOnTheGoProject`. A security boundary
 * that is defined three times is defined zero times, so all callers now go through this object.
 *
 * See `docs/adr/0016-scoped-workspace-storage-model.md`: the hardcoded shared-storage roots are
 * accepted debt tracked by `scripts/shared_storage_ratchet.py`, and they disappear once projects
 * live in an app-owned workspace.
 */
internal object PluginPathAllowlist {
	private const val PLUGIN_DATA_ROOT = "plugins"

	/**
	 * Legacy shared-storage roots, used only as a fallback when [Environment.PROJECTS_DIR] has not
	 * been initialised yet. They are wrong on secondary user profiles and on devices that do not
	 * expose `/sdcard`, which is exactly why the configured directory is preferred.
	 */
	private val legacyProjectRoots: List<String>
		get() =
			listOf(
				"/storage/emulated/0/${Environment.PROJECTS_FOLDER}",
				"/sdcard/${Environment.PROJECTS_FOLDER}",
				(System.getProperty("user.home") ?: "/") + "/${Environment.PROJECTS_FOLDER}",
			)

	/**
	 * A temporary project root kept for demos and manual testing. Historically two different names
	 * were in use; both are honoured so neither existing flow breaks.
	 */
	private val temporaryProjectRoots = listOf("/tmp/CodeOnTheGoProject", "/tmp/AndroidIDEProject")

	fun isAllowed(
		path: File,
		permissions: Set<PluginPermission>,
		pluginId: String,
	): Boolean {
		val canonical =
			try {
				path.canonicalPath
			} catch (e: Exception) {
				// An unresolvable path is never allowed: failing open here would let a plugin probe
				// with a path the containment check cannot reason about.
				return false
			}
		return defaultAllowedPaths(permissions, pluginId).any { root -> containsPath(canonical, root) }
	}

	fun defaultAllowedPaths(
		permissions: Set<PluginPermission>,
		pluginId: String,
	): List<String> {
		val paths = mutableListOf<String?>()

		// The configured projects directory is authoritative when it is available; the absolute and
		// canonical forms are both recorded because either may be what a caller presents.
		val projectsDir = runCatching { Environment.PROJECTS_DIR }.getOrNull()
		if (projectsDir != null) {
			paths += projectsDir.absolutePath
			paths += canonicalOrSelf(projectsDir)
		} else {
			paths += legacyProjectRoots.map { canonicalOrSelf(File(it)) }
		}

		paths += temporaryProjectRoots.map { canonicalOrSelf(File(it)) }

		if (PluginPermission.IDE_ENVIRONMENT_WRITE in permissions) {
			paths += canonicalOrNull(Environment.ANDROID_HOME)
			paths += canonicalOrNull(Environment.TMP_DIR)
			val pluginData = runCatching { Environment.ANDROIDIDE_HOME }.getOrNull()
			if (pluginData != null) {
				paths += canonicalOrNull(File(File(pluginData, PLUGIN_DATA_ROOT), pluginId))
			}
		}

		// Blank entries must never survive: containsPath("") would match every absolute path,
		// turning an uninitialised Environment field into a full-filesystem grant.
		return paths.filterNotNull().filter { it.isNotBlank() }.distinct()
	}

	/**
	 * Anchored on [File.separator] so a root such as `/.../CodeOnTheGoProjects` does not also admit
	 * a sibling like `/.../CodeOnTheGoProjectsBackup`.
	 */
	private fun containsPath(
		canonical: String,
		root: String,
	): Boolean {
		val trimmedRoot = root.trimEnd(File.separatorChar)
		return canonical == trimmedRoot || canonical.startsWith(trimmedRoot + File.separatorChar)
	}

	private fun canonicalOrSelf(file: File): String =
		try {
			file.canonicalPath
		} catch (e: Exception) {
			file.absolutePath
		}

	/** Null-safe variant for the [Environment] fields, which are unset until `Environment.init`. */
	private fun canonicalOrNull(file: File?): String? = file?.let { canonicalOrSelf(it) }
}
