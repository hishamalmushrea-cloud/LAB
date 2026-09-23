package com.itsaky.androidide.utils

import java.io.File

/**
 * Resolves where [FeatureFlags] looks for its sentinel files.
 *
 * The flags used to be read from the shared public `Download/` directory, which any application
 * holding storage access can write. That made six switches remotely settable in a *release* build
 * by a third party, with no debug gating at all:
 *
 * - `CodeOnTheGo.exp` turned on unfinished experimental surfaces and raised the minimum storage
 *   requirement from 4 GB to 6 GB, so planting it could make the app refuse to start.
 * - `S153.txt` re-enabled running on x86, the configuration [
 *   com.itsaky.androidide.app.configuration.IDEBuildConfigProvider] deliberately exits on.
 * - `CodeOnTheGo.a2s2` ("pardon") disabled StrictMode, and `CodeOnTheGo.a3s19` ("reprieve")
 *   downgraded its violations, weakening the app's own safety net.
 * - `CodeOnTheGo.logd` raised log verbosity and enabled tooling stream logging.
 * - `CodeOnTheGo.lc` suppressed LeakCanary heap dumps.
 *
 * The switches now resolve against the app-private files directory, which no other app can write,
 * and [forDebugBuild] yields [disabled] for a release build so the flags cannot be set in a shipped
 * binary even on a rooted device. This mirrors the fix already applied to the local web server's
 * developer overrides and to the documentation interceptor's sentinel.
 */
data class FeatureFlagSource(
	/** Directory the sentinel files are read from. It need not exist. */
	val directory: File,
	/** Whether flags may be honoured at all. */
	val enabled: Boolean,
) {
	/** Whether the flag named [fileName] is set. Always false when this source is not [enabled]. */
	fun isSet(fileName: String): Boolean = enabled && File(directory, fileName).exists()

	companion object {
		/** The subdirectory of the app's files directory that holds the switches. */
		internal const val FLAGS_DIR_NAME = "feature-flags"

		/**
		 * A source where every flag reads as off.
		 *
		 * The directory is absolute and one the app never creates, so even if [enabled] were
		 * flipped by a future edit the lookups would still miss.
		 */
		fun disabled(): FeatureFlagSource =
			FeatureFlagSource(directory = File("/nonexistent/cogo-feature-flags"), enabled = false)

		/** Switch files under [filesDir], the app-private directory only this app can write. */
		fun inFilesDir(filesDir: File): FeatureFlagSource =
			FeatureFlagSource(directory = File(filesDir, FLAGS_DIR_NAME), enabled = true)

		/**
		 * [inFilesDir] for a debug build, [disabled] for a release build.
		 *
		 * The build type is passed in rather than read from `BuildConfig` so this stays a plain
		 * JVM-testable function and `:common` keeps no dependency on the app's build config.
		 */
		fun forDebugBuild(
			isDebugBuild: Boolean,
			filesDir: File,
		): FeatureFlagSource = if (isDebugBuild) inFilesDir(filesDir) else disabled()
	}
}
