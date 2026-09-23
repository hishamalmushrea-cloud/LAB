package com.itsaky.androidide.doctor

/**
 * Turns a snapshot of the device and installation into a ranked list of findings.
 *
 * The roadmap (§4.3) asks for an Environment Doctor screen so that "the build doesn't work" stops
 * being the most common bug report. The hard part of that screen is not the UI: it is deciding what
 * counts as a problem, which problems matter most, and which ones the app can actually fix. That
 * decision is what lives here, as a pure function over [EnvironmentSnapshot] with no Android
 * dependency, so every rule is unit-testable on the JVM and the eventual UI is a thin renderer.
 *
 * Deliberately not a health *score*: a single number hides which specific thing is wrong, and users
 * cannot act on "72%". Findings carry a [Remedy] instead, which is what a fix button binds to.
 */
object EnvironmentDoctor {
	/** Minimum free space for a build; below this Gradle and the SDK tooling start failing. */
	const val MINIMUM_FREE_BYTES: Long = 2L * 1024 * 1024 * 1024

	/** Below this, builds work but with heavy pressure; worth warning about. */
	const val COMFORTABLE_FREE_BYTES: Long = 6L * 1024 * 1024 * 1024

	/** A build needs roughly this much RAM before the daemon starts thrashing. */
	const val MINIMUM_TOTAL_MEMORY_BYTES: Long = 3L * 1024 * 1024 * 1024

	/** Below this fraction of battery, an unplugged long build is likely to be interrupted. */
	const val LOW_BATTERY_FRACTION: Float = 0.2f

	/**
	 * Runs every rule against [snapshot] and returns the findings, most severe first.
	 *
	 * Ordering is stable: findings of equal severity keep the order the rules ran in, so the screen
	 * does not reshuffle between refreshes when nothing has changed.
	 */
	fun diagnose(snapshot: EnvironmentSnapshot): List<Finding> =
		buildList {
			addAll(abiFindings(snapshot))
			addAll(storageFindings(snapshot))
			addAll(memoryFindings(snapshot))
			addAll(toolchainFindings(snapshot))
			addAll(assetFindings(snapshot))
			addAll(powerFindings(snapshot))
		}.sortedByDescending { it.severity.ordinal }

	private fun abiFindings(snapshot: EnvironmentSnapshot): List<Finding> {
		if (snapshot.supportedAbis.isEmpty()) return emptyList()
		if (snapshot.supportedAbis.contains(snapshot.installedAbi)) return emptyList()
		return listOf(
			Finding(
				id = "abi-mismatch",
				severity = Severity.BLOCKER,
				summary = "This build targets ${snapshot.installedAbi}, which this device does not support.",
				detail =
					"The device reports ${snapshot.supportedAbis.joinToString()}. Native tooling will fail " +
						"to load. Install the build matching this device's ABI.",
				remedy = Remedy.REINSTALL_MATCHING_ABI,
			),
		)
	}

	private fun storageFindings(snapshot: EnvironmentSnapshot): List<Finding> {
		val free = snapshot.freeStorageBytes
		return when {
			free < MINIMUM_FREE_BYTES -> {
				listOf(
					Finding(
						id = "storage-critical",
						severity = Severity.BLOCKER,
						summary = "Only ${formatBytes(free)} of storage is free.",
						detail =
							"A build needs at least ${formatBytes(MINIMUM_FREE_BYTES)} for Gradle caches and " +
								"build outputs. Free space, or clear the build caches.",
						remedy = Remedy.CLEAR_CACHES,
					),
				)
			}

			free < COMFORTABLE_FREE_BYTES -> {
				listOf(
					Finding(
						id = "storage-low",
						severity = Severity.WARNING,
						summary = "Storage is getting tight: ${formatBytes(free)} free.",
						detail = "Large builds may fail. Clearing old build caches usually recovers several GB.",
						remedy = Remedy.CLEAR_CACHES,
					),
				)
			}

			else -> {
				emptyList()
			}
		}
	}

	private fun memoryFindings(snapshot: EnvironmentSnapshot): List<Finding> {
		val findings = mutableListOf<Finding>()
		if (snapshot.totalMemoryBytes in 1 until MINIMUM_TOTAL_MEMORY_BYTES) {
			findings +=
				Finding(
					id = "memory-small",
					severity = Severity.WARNING,
					summary = "This device has ${formatBytes(snapshot.totalMemoryBytes)} of RAM.",
					detail =
						"Gradle's daemon is memory-hungry. Lower the daemon heap so builds fail slowly rather " +
							"than being killed by the system.",
					remedy = Remedy.REDUCE_GRADLE_MEMORY,
				)
		}
		if (snapshot.lowMemory) {
			findings +=
				Finding(
					id = "memory-pressure",
					severity = Severity.WARNING,
					summary = "The system is under memory pressure right now.",
					detail = "Close background apps before starting a build, or the daemon may be killed mid-build.",
					remedy = Remedy.NONE,
				)
		}
		return findings
	}

	private fun toolchainFindings(snapshot: EnvironmentSnapshot): List<Finding> {
		val findings = mutableListOf<Finding>()
		if (!snapshot.javaHomeInstalled) {
			findings +=
				Finding(
					id = "jdk-missing",
					severity = Severity.BLOCKER,
					summary = "No JDK is installed.",
					detail = "Builds cannot run without a JDK. Run the setup flow to install the bundled one.",
					remedy = Remedy.RUN_SETUP,
				)
		}
		if (!snapshot.androidSdkInstalled) {
			findings +=
				Finding(
					id = "sdk-missing",
					severity = Severity.BLOCKER,
					summary = "The Android SDK is not installed.",
					detail = "Android builds need the SDK platform and build tools. Run the setup flow.",
					remedy = Remedy.RUN_SETUP,
				)
		}
		return findings
	}

	private fun assetFindings(snapshot: EnvironmentSnapshot): List<Finding> {
		val corrupt = snapshot.assets.filter { it.state == AssetState.CHECKSUM_MISMATCH }
		val missing = snapshot.assets.filter { it.state == AssetState.MISSING }
		val findings = mutableListOf<Finding>()
		if (corrupt.isNotEmpty()) {
			findings +=
				Finding(
					id = "assets-corrupt",
					severity = Severity.BLOCKER,
					summary = "${corrupt.size} installed asset(s) do not match their expected checksum.",
					detail =
						"Affected: ${corrupt.joinToString { it.id }}. A mismatch means the file was truncated, " +
							"modified or replaced; re-download rather than trusting it.",
					remedy = Remedy.REDOWNLOAD_ASSETS,
				)
		}
		if (missing.isNotEmpty()) {
			findings +=
				Finding(
					id = "assets-missing",
					severity = Severity.WARNING,
					summary = "${missing.size} asset(s) are not installed.",
					detail = "Affected: ${missing.joinToString { it.id }}. Features depending on them will fail.",
					remedy = Remedy.REDOWNLOAD_ASSETS,
				)
		}
		return findings
	}

	private fun powerFindings(snapshot: EnvironmentSnapshot): List<Finding> {
		val findings = mutableListOf<Finding>()
		if (snapshot.thermalThrottling) {
			findings +=
				Finding(
					id = "thermal-throttling",
					severity = Severity.WARNING,
					summary = "The device is thermally throttled.",
					detail = "Builds will be much slower than usual until it cools down.",
					remedy = Remedy.NONE,
				)
		}
		if (!snapshot.charging && snapshot.batteryFraction in 0f..LOW_BATTERY_FRACTION) {
			findings +=
				Finding(
					id = "battery-low",
					severity = Severity.INFO,
					summary = "Battery is at ${(snapshot.batteryFraction * 100).toInt()}% and not charging.",
					detail = "A long build may not finish. Plug in before starting one.",
					remedy = Remedy.NONE,
				)
		}
		return findings
	}

	/**
	 * Formats a byte count for a user-facing string.
	 *
	 * Binary units with one decimal place, chosen to match what Android's own storage settings show
	 * so the two do not appear to disagree.
	 */
	internal fun formatBytes(bytes: Long): String {
		if (bytes < 0) return "unknown"
		val units = listOf("B", "KB", "MB", "GB", "TB")
		var value = bytes.toDouble()
		var unit = 0
		while (value >= 1024 && unit < units.lastIndex) {
			value /= 1024
			unit++
		}
		return if (unit == 0) "$bytes ${units[unit]}" else String.format("%.1f %s", value, units[unit])
	}
}
