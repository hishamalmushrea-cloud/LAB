package com.itsaky.androidide.storage

import java.io.File

/**
 * Where a project lives, and therefore what the IDE is allowed to do with it.
 *
 * ADR 0016 decided that existing projects stay where the user put them and are reached through a
 * persisted SAF tree grant rather than being copied into the app's workspace. That decision only
 * holds if "is this a real filesystem path I may build in?" is a question the code can *ask*. A
 * bare [File] cannot answer it, which is how the current call sites ended up assuming every project
 * root is a writable path on shared storage.
 *
 * This type makes the distinction explicit and unforgeable: [Managed] carries a real path, [Granted]
 * carries only an opaque tree identifier, and there is deliberately no way to turn a [Granted] into
 * a [File]. Converting a picked tree back into a path is the anti-pattern ADR 0016 exists to end -
 * it appears to work on the developer's device and fails on secondary user profiles, on removable
 * volumes, and from API 30 onward.
 */
sealed interface WorkspaceLocation {
	/** A stable identifier, safe to persist and to compare. */
	val id: String

	/**
	 * A directory inside the app-owned workspace.
	 *
	 * Gradle, the JDK and the embedded Termux toolchain all need real filesystem paths, so this is
	 * the only kind of location a build can run against.
	 */
	data class Managed(
		val directory: File,
	) : WorkspaceLocation {
		override val id: String get() = directory.absolutePath
	}

	/**
	 * A tree outside the workspace, reachable only through a persisted SAF grant.
	 *
	 * [treeUri] is kept as a string rather than an `android.net.Uri` so this module stays free of
	 * Android types and the rules below remain unit-testable.
	 */
	data class Granted(
		val treeUri: String,
		val displayName: String,
	) : WorkspaceLocation {
		override val id: String get() = treeUri
	}
}

/** Why a location cannot be built in as-is. */
enum class BuildBlocker {
	/** The location is a SAF tree; it must be imported into the workspace first. */
	NEEDS_IMPORT,

	/** The directory does not exist. */
	MISSING,

	/** The directory exists but the process cannot write to it. */
	NOT_WRITABLE,
}

/** The outcome of asking whether a build may run against a location. */
sealed interface BuildEligibility {
	/** The build may run in [directory]. */
	data class Eligible(
		val directory: File,
	) : BuildEligibility

	/** The build may not run; [blocker] says why, in terms the UI can act on. */
	data class Blocked(
		val blocker: BuildBlocker,
	) : BuildEligibility
}
