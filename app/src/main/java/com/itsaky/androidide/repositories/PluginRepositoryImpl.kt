package com.itsaky.androidide.repositories

import android.util.Log
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.plugins.PluginMetadata
import com.itsaky.androidide.plugins.manager.core.PluginManager
import com.itsaky.androidide.plugins.manager.core.PluginValidation
import com.itsaky.androidide.plugins.manager.install.AtomicPluginInstaller
import com.itsaky.androidide.plugins.manager.loaders.toPluginMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Implementation of PluginRepository
 * Handles all plugin-related data operations
 */
class PluginRepositoryImpl(
	private val pluginManagerProvider: () -> PluginManager?,
	private val pluginsDir: File,
) : PluginRepository {
	private companion object {
		private const val TAG = "PluginRepository"
	}

	private val pluginManager: PluginManager?
		get() = pluginManagerProvider()

	override suspend fun getAllPlugins(): Result<List<PluginInfo>> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")
				manager.getAllPlugins()
			}.onFailure { exception ->
				Log.e(TAG, "Failed to get all plugins", exception)
			}
		}

	override suspend fun enablePlugin(pluginId: String): Result<Boolean> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")
				val result = manager.enablePlugin(pluginId)
				result
			}.onFailure { exception ->
				Log.e(TAG, "Failed to enable plugin: $pluginId", exception)
			}
		}

	override suspend fun disablePlugin(pluginId: String): Result<Boolean> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")
				val result = manager.disablePlugin(pluginId)
				result
			}.onFailure { exception ->
				Log.e(TAG, "Failed to disable plugin: $pluginId", exception)
			}
		}

	override suspend fun uninstallPlugin(pluginId: String): Result<Boolean> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")

				Log.d(TAG, "Uninstalling plugin: $pluginId")
				val result = manager.uninstallPlugin(pluginId)
				result
			}.onFailure { exception ->
				Log.e(TAG, "Failed to uninstall plugin: $pluginId", exception)
			}
		}

	override suspend fun getPluginMetadataFromFile(pluginFile: File): Result<PluginMetadata> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")
				manager.getPluginMetadataOnly(pluginFile).getOrThrow().toPluginMetadata()
			}
		}

	override suspend fun haveMatchingSignatures(
		incomingFile: File,
		existingPluginId: String,
	): Result<Boolean> =
		withContext(Dispatchers.IO) {
			runCatching {
				pluginManager?.haveMatchingSignatures(incomingFile, existingPluginId)
					?: throw IllegalStateException("Plugin system not available")
			}
		}

	override suspend fun installPluginFromFile(pluginFile: File): Result<Unit> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")

				val initialValidation = manager.getPluginValidation(pluginFile).getOrThrow()
				val pluginId = initialValidation.manifest.id
				val existingFile = manager.findInstalledPluginFile(pluginId)
				val replacingPluginId = pluginId.takeIf { existingFile != null }
				if (replacingPluginId != null) {
					manager.getEnabledDependent(pluginId)?.let { dependent ->
						throw IllegalStateException(
							"Disable dependent plugin $dependent before updating $pluginId",
						)
					}
				}
				manager.validatePluginForInstall(pluginFile, replacingPluginId).getOrThrow()
				validateDebugIcons(initialValidation)

				val wasLoaded = manager.getPlugin(pluginId) != null
				val wasEnabled = wasLoaded && manager.isPluginEnabled(pluginId)
				AtomicPluginInstaller(pluginsDir).install(
					pluginId = pluginId,
					source = pluginFile,
					existingFile = existingFile,
					validateStaged = { staged ->
						manager.validatePluginForInstall(staged, replacingPluginId).getOrThrow()
						validateDebugIcons(manager.getPluginValidation(staged).getOrThrow())
					},
					unloadCurrent = {
						if (manager.getPlugin(pluginId) != null) {
							check(manager.unloadPlugin(pluginId)) { "Could not unload plugin $pluginId" }
						}
					},
					loadReplacement = { installed ->
						manager.loadPlugin(installed, persistActivationFailure = false).map { Unit }
					},
					reloadPrevious = { restored ->
						if (!wasLoaded) {
							Result.success(Unit)
						} else {
							manager.loadPlugin(restored).mapCatching {
								if (wasEnabled && !manager.isPluginEnabled(pluginId)) {
									check(manager.enablePlugin(pluginId)) {
										"Could not restore enabled state for $pluginId"
									}
								}
								}
							}
						},
					)
			}.onFailure { exception ->
				Log.e(TAG, "Failed to install plugin from file: ${pluginFile.absolutePath}", exception)
			}
		}

	private fun validateDebugIcons(validation: PluginValidation) {
		if (!validation.isDebug) return
		val metadata = validation.manifest
		val missing =
			listOfNotNull(
				"icon_day".takeIf {
					metadata.iconDay == null || !validation.iconDayEntryExists
				},
				"icon_night".takeIf {
					metadata.iconNight == null || !validation.iconNightEntryExists
				},
			).joinToString(" and ") { "\"$it\"" }
		if (missing.isNotEmpty()) {
			throw IllegalArgumentException(
				"[${metadata.id}] Missing $missing for debug plugin. " +
					"Debug plugins must declare and ship both icon_day and icon_night assets.",
			)
		}
	}

	override suspend fun reloadPlugins(): Result<Unit> =
		withContext(Dispatchers.IO) {
			runCatching {
				val manager =
					pluginManager
						?: throw IllegalStateException("Plugin system not available")

				manager.loadPlugins()
			}.onFailure { exception ->
				Log.e(TAG, "Failed to reload plugins", exception)
			}
		}

	override fun isPluginManagerAvailable(): Boolean {
		val available = pluginManager != null
		return available
	}
}
