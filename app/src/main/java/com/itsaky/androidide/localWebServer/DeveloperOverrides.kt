package com.itsaky.androidide.localWebServer

import java.io.File

/**
 * Resolves the local web server's developer override switches.
 *
 * These switches used to be sentinel files under the shared `Download/` directory, which had two
 * problems. The lesser one is scoped storage: `Environment.getExternalStorageDirectory()` is part
 * of the broad-storage surface that ADR 0016 removes. The more serious one is that the directory
 * is world-writable by any app holding storage access, so *another* application could plant
 * `CodeOnTheGo.webserver.debug` and turn on verbose request logging - including request lines and
 * rendered HTML - in someone's release build, or plant `CodeOnTheGo.webserver.cs0` and clear the
 * bookshelf cache on every request.
 *
 * The switches now live in the app's own files directory, which no other app can write, and
 * [forDebugBuild] returns [disabled] for a release build so a rooted device cannot enable them at
 * all in a shipped binary. Test and instrumentation code keeps passing explicit paths, so those
 * fixtures are unaffected.
 */
data class DeveloperOverrides(
	val debugEnablePath: String,
	val experimentsEnablePath: String,
	val clearCacheEnablePath: String,
	val debugDatabasePath: String,
) {
	companion object {
		internal const val DEBUG_FILE_NAME = "CodeOnTheGo.webserver.debug"
		internal const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"
		internal const val CLEAR_CACHE_FILE_NAME = "CodeOnTheGo.webserver.cs0"
		internal const val DEBUG_DATABASE_FILE_NAME = "documentation.db"

		/** The subdirectory of the app's files directory that holds the switches. */
		internal const val OVERRIDES_DIR_NAME = "webserver-overrides"

		/**
		 * Paths that cannot exist, so every switch reads as off.
		 *
		 * Deliberately absolute and inside a directory the app never creates: a relative path
		 * would resolve against the process working directory and could accidentally exist.
		 */
		fun disabled(): DeveloperOverrides {
			val root = File("/nonexistent/cogo-webserver-overrides")
			return DeveloperOverrides(
				debugEnablePath = File(root, DEBUG_FILE_NAME).path,
				experimentsEnablePath = File(root, EXPERIMENTS_FILE_NAME).path,
				clearCacheEnablePath = File(root, CLEAR_CACHE_FILE_NAME).path,
				debugDatabasePath = File(root, DEBUG_DATABASE_FILE_NAME).path,
			)
		}

		/** Switch files under [filesDir], the app-private directory only this app can write. */
		fun inFilesDir(filesDir: File): DeveloperOverrides {
			val root = File(filesDir, OVERRIDES_DIR_NAME)
			return DeveloperOverrides(
				debugEnablePath = File(root, DEBUG_FILE_NAME).path,
				experimentsEnablePath = File(root, EXPERIMENTS_FILE_NAME).path,
				clearCacheEnablePath = File(root, CLEAR_CACHE_FILE_NAME).path,
				debugDatabasePath = File(root, DEBUG_DATABASE_FILE_NAME).path,
			)
		}

		/**
		 * [inFilesDir] for a debug build, [disabled] for a release build.
		 *
		 * The build type is passed in rather than read from `BuildConfig` here so this stays a
		 * plain JVM-testable function.
		 */
		fun forDebugBuild(
			isDebugBuild: Boolean,
			filesDir: File,
		): DeveloperOverrides = if (isDebugBuild) inFilesDir(filesDir) else disabled()
	}
}
