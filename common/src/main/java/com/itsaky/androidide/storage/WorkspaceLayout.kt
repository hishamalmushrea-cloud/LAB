package com.itsaky.androidide.storage

import java.io.File

/**
 * The single place that decides where things live inside the app-owned workspace.
 *
 * ADR 0016 calls for one storage gateway in `:common` so that the set of locations the IDE uses is
 * enumerable and reviewable, instead of being rediscovered at each call site via
 * `Environment.getExternalStorageDirectory()`. This is the path-resolution half of that gateway: it
 * is deliberately a plain function of a root directory, with no `Context` and no Android types, so
 * the rules can be tested and so a caller cannot accidentally resolve something outside the root.
 *
 * The root itself is supplied by the caller - in the app it is `context.filesDir`, which needs no
 * permission at any API level and is not readable by other applications.
 */
class WorkspaceLayout(
	/** The app-owned workspace root. */
	val root: File,
) {
	/** Projects created by the IDE, and projects the user has imported. */
	val projects: File get() = File(root, PROJECTS_DIR_NAME)

	/** Crash logs and diagnostics. Private: these have historically leaked to shared storage. */
	val logs: File get() = File(root, LOGS_DIR_NAME)

	/** Scratch space that may be deleted at any time to reclaim room. */
	val caches: File get() = File(root, CACHES_DIR_NAME)

	/** A staging area for trees being imported from a SAF grant. */
	val imports: File get() = File(root, IMPORTS_DIR_NAME)

	/** The directory for the project named [name], with the name sanitised by [safeProjectName]. */
	fun projectDirectory(name: String): File = File(projects, safeProjectName(name))

	/**
	 * Resolves [relative] beneath [root], or null when it would escape.
	 *
	 * Escape is checked on the *canonical* path so that `..` segments, a doubled separator and a
	 * symlink pointing outside the root are all caught. Returning null rather than throwing keeps
	 * this usable directly in a `when`, since a rejected path is an expected input here - project
	 * names and imported entry names both come from outside the app.
	 */
	fun resolveWithin(relative: String): File? {
		if (relative.isBlank()) return null
		val candidate = File(root, relative)
		val canonicalRoot = root.canonicalFile
		val canonicalCandidate = candidate.canonicalFile
		if (canonicalCandidate == canonicalRoot) return null
		return if (canonicalCandidate.toPath().startsWith(canonicalRoot.toPath())) canonicalCandidate else null
	}

	/** Whether [file] lies inside this workspace. Uses canonical paths, so symlinks cannot fool it. */
	fun contains(file: File): Boolean {
		val canonicalRoot = root.canonicalFile.toPath()
		return file.canonicalFile.toPath().startsWith(canonicalRoot)
	}

	/** Creates the standard directories. Safe to call repeatedly. */
	fun createDirectories() {
		for (directory in listOf(projects, logs, caches, imports)) {
			directory.mkdirs()
		}
	}

	companion object {
		internal const val PROJECTS_DIR_NAME = "projects"
		internal const val LOGS_DIR_NAME = "logs"
		internal const val CACHES_DIR_NAME = "caches"
		internal const val IMPORTS_DIR_NAME = "imports"

		/** The longest a sanitised project name may be, leaving room for nested build paths. */
		internal const val MAX_NAME_LENGTH = 64

		private val UNSAFE_CHARACTERS = Regex("""[^A-Za-z0-9._-]""")

		/**
		 * Turns an arbitrary user-supplied name into one safe to use as a single path segment.
		 *
		 * Path separators and `..` are the point: a project name reaches this from a text field, a
		 * template, or the display name of an imported SAF tree, and any of those could contain
		 * `../../`. Rather than rejecting such a name - which would surface as a confusing error
		 * far from the text field - every character outside a conservative allowlist becomes an
		 * underscore, which cannot traverse.
		 */
		fun safeProjectName(name: String): String {
			val collapsed = name.trim().replace(UNSAFE_CHARACTERS, "_")
			// A leading dot would make the directory hidden, and "." / ".." are traversal.
			val unhidden = collapsed.trimStart('.')
			val truncated = unhidden.take(MAX_NAME_LENGTH)
			return truncated.ifBlank { "project" }
		}
	}
}
