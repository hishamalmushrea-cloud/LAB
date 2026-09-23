package com.itsaky.androidide.utils

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private data class FlagsCache(
	val experimentsEnabled: Boolean = false,
	val debugLoggingEnabled: Boolean = false,
	val emulatorUseEnabled: Boolean = false,
	val reprieveEnabled: Boolean = false,
	val pardonEnabled: Boolean = false,
	val leakCanaryDumpInhibited: Boolean = false,
) {
	companion object {
		/**
		 * Default flags.
		 */
		val DEFAULT = FlagsCache()
	}
}

object FeatureFlags {
	private const val EXPERIMENTS_FILE_NAME = "CodeOnTheGo.exp"
	private const val LOGD_FILE_NAME = "CodeOnTheGo.logd"
	private const val EMULATOR_FILE_NAME = "S153.txt"
	private const val REPRIEVE_FILE_NAME = "CodeOnTheGo.a3s19"
	private const val PARDON_FILE_NAME = "CodeOnTheGo.a2s2"
	private const val LEAKCANARY_FILE_NAME = "CodeOnTheGo.lc"

	private val logger = LoggerFactory.getLogger(FeatureFlags::class.java)

	private val mutex = Mutex()
	private var flags = FlagsCache.DEFAULT

	/**
	 * Whether Code On the Go experiments are enabled.
	 */
	val isExperimentsEnabled: Boolean
		get() = flags.experimentsEnabled

	/**
	 * Whether debug log *reporting* is enabled or not.
	 */
	val isDebugLoggingEnabled: Boolean
		get() = flags.debugLoggingEnabled

	/**
	 * Whether emulator use is enabled or not.
	 */
	val isEmulatorUseEnabled: Boolean
		get() = flags.emulatorUseEnabled

	/**
	 * Whether reprieve is enabled or not.
	 */
	val isReprieveEnabled: Boolean
		get() = flags.reprieveEnabled

	/**
	 * Whether pardon is enabled or not.
	 */
	val isPardonEnabled: Boolean
		get() = flags.pardonEnabled

	/**
	 * Whether LeakCanary heap dumping is inhibited.
	 */
	val isLeakCanaryDumpInhibited: Boolean
		get() = flags.leakCanaryDumpInhibited

	/**
	 * Initialize feature flag values from [source]. This is thread-safe and idempotent i.e.
	 * subsequent calls do not access disk.
	 *
	 * The source is a parameter rather than a constant resolved here because these switches used to
	 * be read from world-writable shared storage; see [FeatureFlagSource] for what that allowed.
	 * Requiring the caller to supply it means a call site cannot silently reintroduce that.
	 */
	suspend fun initialize(source: FeatureFlagSource): Unit =
		mutex.withLock {
			if (flags !== FlagsCache.DEFAULT) {
				// already initialized
				return@withLock
			}

			fun checkFlag(fileName: String) = source.isSet(fileName)

			flags =
				withContext(Dispatchers.IO) {
					runCatching {
						logger.info("Loading feature flags...")
						FlagsCache(
							experimentsEnabled = checkFlag(EXPERIMENTS_FILE_NAME),
							debugLoggingEnabled = checkFlag(LOGD_FILE_NAME),
							emulatorUseEnabled = checkFlag(EMULATOR_FILE_NAME),
							reprieveEnabled = checkFlag(REPRIEVE_FILE_NAME),
							pardonEnabled = checkFlag(PARDON_FILE_NAME),
							leakCanaryDumpInhibited = checkFlag(LEAKCANARY_FILE_NAME),
						)
					}.getOrElse { error ->
						logger.error("Failed to load feature flags. Falling back to default values.", error)
						FlagsCache.DEFAULT
					}
				}
		}

	/**
	 * Discards loaded flags so the next [initialize] reads again. Test-only: the flags are a
	 * process-wide singleton, so without this one test's flags would leak into the next.
	 */
	@VisibleForTesting
	internal fun resetForTest() {
		flags = FlagsCache.DEFAULT
	}
}
