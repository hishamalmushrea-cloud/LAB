package com.itsaky.androidide.doctor

/**
 * Everything [EnvironmentDoctor] needs to know about the device and installation.
 *
 * Plain data with no Android types on purpose: collecting it needs a `Context`, but *judging* it
 * must not, so the rules stay unit-testable and a caller can build a snapshot from a saved report
 * as easily as from the live device.
 *
 * @property installedAbi the ABI this build was compiled for, e.g. `arm64-v8a`.
 * @property supportedAbis the ABIs the device reports, most preferred first. Empty when unknown.
 * @property androidApiLevel the running platform API level.
 * @property totalMemoryBytes total device RAM, or 0 when unknown.
 * @property freeStorageBytes free space on the volume holding the workspace, or -1 when unknown.
 * @property lowMemory whether the system currently reports memory pressure.
 * @property javaHomeInstalled whether a usable JDK is present.
 * @property androidSdkInstalled whether the Android SDK is present.
 * @property assets the state of each tracked external asset.
 * @property thermalThrottling whether the device is currently throttled.
 * @property batteryFraction battery level in `0f..1f`, or -1f when unknown.
 * @property charging whether the device is plugged in.
 */
data class EnvironmentSnapshot(
	val installedAbi: String = "",
	val supportedAbis: List<String> = emptyList(),
	val androidApiLevel: Int = 0,
	val totalMemoryBytes: Long = 0,
	val freeStorageBytes: Long = -1,
	val lowMemory: Boolean = false,
	val javaHomeInstalled: Boolean = true,
	val androidSdkInstalled: Boolean = true,
	val assets: List<AssetStatus> = emptyList(),
	val thermalThrottling: Boolean = false,
	val batteryFraction: Float = -1f,
	val charging: Boolean = false,
)

/** The integrity of one tracked external asset. */
data class AssetStatus(
	val id: String,
	val state: AssetState,
)

/** Why an asset is or is not usable. */
enum class AssetState {
	/** Present and its SHA-256 matches the manifest. */
	OK,

	/** Not installed. */
	MISSING,

	/**
	 * Present but its digest does not match the manifest.
	 *
	 * Treated as more serious than [MISSING]: a wrong file is worse than no file, because code that
	 * checks only for existence will happily use it.
	 */
	CHECKSUM_MISMATCH,
}

/** How bad a finding is. Ordinal order matters: [EnvironmentDoctor] sorts by it. */
enum class Severity {
	/** Worth knowing, nothing is broken. */
	INFO,

	/** Builds still work, but degraded or at risk. */
	WARNING,

	/** Builds cannot succeed until this is resolved. */
	BLOCKER,
}

/**
 * The action that resolves a finding.
 *
 * An enum rather than a lambda so a finding stays serialisable into an exported report, and so the
 * set of things the app claims it can fix is enumerable and reviewable.
 */
enum class Remedy {
	/** Nothing the app can do; the user has to change something physical or external. */
	NONE,

	/** Re-run the first-launch setup flow. */
	RUN_SETUP,

	/** Delete build and Gradle caches. */
	CLEAR_CACHES,

	/** Re-download the affected external assets. */
	REDOWNLOAD_ASSETS,

	/** Lower the Gradle daemon's heap setting. */
	REDUCE_GRADLE_MEMORY,

	/** Install the build matching this device's ABI. */
	REINSTALL_MATCHING_ABI,
}

/** One diagnosed problem, ready to render as a row with an optional fix button. */
data class Finding(
	val id: String,
	val severity: Severity,
	val summary: String,
	val detail: String,
	val remedy: Remedy,
)
