/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins.manager.install

import com.itsaky.androidide.plugins.manager.security.PluginIdValidator
import org.adfa.constants.PLUGIN_ARCHIVE_EXTENSION
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ

/** Crash-recoverable, same-filesystem plugin package replacement. */
class AtomicPluginInstaller(
	private val pluginsDir: File,
) {
	private val transactionsDir = File(pluginsDir, TRANSACTION_DIRECTORY)

	fun install(
		pluginId: String,
		source: File,
		existingFile: File?,
		validateStaged: (File) -> Unit,
		unloadCurrent: () -> Unit,
		loadReplacement: (File) -> Result<Unit>,
		reloadPrevious: (File) -> Result<Unit>,
	): File =
		synchronized(transactionLock) {
			installLocked(
				pluginId,
				source,
				existingFile,
				validateStaged,
				unloadCurrent,
				loadReplacement,
				reloadPrevious,
			)
		}

	private fun installLocked(
		pluginId: String,
		source: File,
		existingFile: File?,
		validateStaged: (File) -> Unit,
		unloadCurrent: () -> Unit,
		loadReplacement: (File) -> Result<Unit>,
		reloadPrevious: (File) -> Result<Unit>,
	): File {
		PluginIdValidator.requireValid(pluginId)
		require(source.isFile && source.canRead()) { "Plugin source is not readable: $source" }
		require(pluginsDir.exists() || pluginsDir.mkdirs()) { "Could not create $pluginsDir" }
		recoverInterruptedTransactionsLocked(pluginsDir)
		require(transactionsDir.exists() || transactionsDir.mkdirs()) { "Could not create $transactionsDir" }

		val target = requireInside(pluginsDir, File(pluginsDir, "$pluginId.$PLUGIN_ARCHIVE_EXTENSION"))
		val staged = requireInside(transactionsDir, File(transactionsDir, "$pluginId$STAGED_SUFFIX"))
		val backup = requireInside(transactionsDir, File(transactionsDir, "$pluginId$BACKUP_SUFFIX"))
		val marker = requireInside(transactionsDir, File(transactionsDir, "$pluginId$MARKER_SUFFIX"))
		val current = existingFile ?: target.takeIf(File::exists)
		if (current != null && current.canonicalFile.parentFile != pluginsDir.canonicalFile) {
			throw SecurityException("Installed plugin package is outside the plugin directory")
		}
		if (current != null && current.canonicalFile != target.canonicalFile && target.exists()) {
			throw IllegalStateException("Multiple installed packages claim plugin ID $pluginId")
		}

		var unloadAttempted = false
		var previousBackedUp = false
		var replacementPublished = false
		try {
			copyAndSync(source, staged)
			validateStaged(staged)
			writeMarker(marker, pluginId, State.PREPARED)

			if (current != null) {
				unloadAttempted = true
				unloadCurrent()
				if (current.canonicalFile == target.canonicalFile) {
					copyAndSync(current, backup)
				} else {
					move(current, backup)
				}
				previousBackedUp = true
			}
			writeMarker(marker, pluginId, State.ACTIVATING)
			move(staged, target)
			replacementPublished = true

			loadReplacement(target).getOrThrow()
			writeMarker(marker, pluginId, State.COMMITTED)

			// Cleanup failure is recoverable and must not roll a successfully loaded package back.
			if ((!backup.exists() || backup.delete()) && marker.delete()) {
				fsyncDirectory(transactionsDir)
				transactionsDir.delete()
			}
			return target
		} catch (failure: Throwable) {
			if (replacementPublished) {
				runCatching(unloadCurrent).onFailure(failure::addSuppressed)
				if (target.exists() && !target.delete()) {
					failure.addSuppressed(IllegalStateException("Could not remove failed replacement $target"))
				}
			}
			if (previousBackedUp && backup.exists()) {
				runCatching {
					writeMarker(marker, pluginId, State.ROLLING_BACK)
					move(backup, target)
					reloadPrevious(target).getOrThrow()
				}.onFailure(failure::addSuppressed)
			} else if (unloadAttempted && current != null && current.exists()) {
				runCatching { reloadPrevious(current).getOrThrow() }.onFailure(failure::addSuppressed)
			}
			throw failure
		} finally {
			if (!marker.exists() || marker.readLines().getOrNull(1) != State.COMMITTED.name) {
				staged.delete()
				// Keep the only previous package if rollback itself failed; startup completes it.
				if (!backup.exists()) marker.delete()
				fsyncDirectory(pluginsDir)
				fsyncDirectory(transactionsDir)
				if (!marker.exists() && !backup.exists()) transactionsDir.delete()
			}
		}
	}

	private fun copyAndSync(source: File, destination: File) {
		source.inputStream().use { input ->
			FileOutputStream(destination).use { output ->
				input.copyTo(output)
				output.fd.sync()
			}
		}
		fsyncDirectory(destination.parentFile)
	}

	private fun writeMarker(
		marker: File,
		pluginId: String,
		state: State,
	) {
		FileOutputStream(marker, false).use { output ->
			output.write("$pluginId\n${state.name}\n".toByteArray())
			output.fd.sync()
		}
		fsyncDirectory(marker.parentFile)
	}

	private fun move(
		source: File,
		destination: File,
	) {
		try {
			Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
		} catch (_: AtomicMoveNotSupportedException) {
			Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING)
		}
		fsyncDirectory(destination.parentFile)
	}

	private enum class State {
		PREPARED,
		ACTIVATING,
		ROLLING_BACK,
		COMMITTED,
	}

	companion object {
		private const val TRANSACTION_DIRECTORY = ".transactions"
		private const val STAGED_SUFFIX = ".staged.$PLUGIN_ARCHIVE_EXTENSION"
		private const val BACKUP_SUFFIX = ".previous.$PLUGIN_ARCHIVE_EXTENSION"
		private const val MARKER_SUFFIX = ".transaction"
		private val transactionLock = Any()

		/** Rolls every interrupted transaction back before the manager scans plugin packages. */
		fun recoverInterruptedTransactions(pluginsDir: File) {
			synchronized(transactionLock) {
				recoverInterruptedTransactionsLocked(pluginsDir)
			}
		}

		private fun recoverInterruptedTransactionsLocked(pluginsDir: File) {
			val transactions = File(pluginsDir, TRANSACTION_DIRECTORY)
			if (!transactions.isDirectory) return

			transactions.listFiles { file -> file.name.endsWith(MARKER_SUFFIX) }.orEmpty().forEach { marker ->
				val lines = marker.readLines()
				val pluginId = lines.firstOrNull()?.trim().orEmpty()
				if (!PluginIdValidator.isValid(pluginId)) {
					throw IllegalStateException("Invalid interrupted plugin transaction: ${marker.name}")
				}
				val state = lines.getOrNull(1)?.trim()
				val target = requireInside(pluginsDir, File(pluginsDir, "$pluginId.$PLUGIN_ARCHIVE_EXTENSION"))
				val backup = requireInside(transactions, File(transactions, "$pluginId$BACKUP_SUFFIX"))
				val staged = requireInside(transactions, File(transactions, "$pluginId$STAGED_SUFFIX"))

				when (state) {
					State.COMMITTED.name -> {
						if (backup.exists() && !backup.delete()) return@forEach
					}
					State.PREPARED.name -> Unit
					State.ROLLING_BACK.name -> {
						if (backup.exists()) {
							target.delete()
							moveRecovered(backup, target)
						}
					}
					State.ACTIVATING.name -> {
						target.delete()
						if (backup.exists()) moveRecovered(backup, target)
					}
					else -> throw IllegalStateException("Unknown plugin transaction state: $state")
				}
				staged.delete()
				marker.delete()
			}

			// A backup without a marker is conservatively treated as an interrupted update.
			transactions.listFiles { file -> file.name.endsWith(BACKUP_SUFFIX) }.orEmpty().forEach { backup ->
				val pluginId = backup.name.removeSuffix(BACKUP_SUFFIX)
				if (File(transactions, "$pluginId$MARKER_SUFFIX").exists()) return@forEach
				if (!PluginIdValidator.isValid(pluginId)) {
					throw IllegalStateException("Invalid orphaned plugin backup: ${backup.name}")
				}
				val target = requireInside(pluginsDir, File(pluginsDir, "$pluginId.$PLUGIN_ARCHIVE_EXTENSION"))
				target.delete()
				moveRecovered(backup, target)
			}
			transactions
				.listFiles { file ->
					!file.name.endsWith(MARKER_SUFFIX) && !file.name.endsWith(BACKUP_SUFFIX)
				}.orEmpty()
				.forEach { it.delete() }
			fsyncDirectory(pluginsDir)
			fsyncDirectory(transactions)
			if (transactions.listFiles().isNullOrEmpty()) transactions.delete()
		}

		private fun moveRecovered(source: File, destination: File) {
			try {
				Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
			} catch (_: AtomicMoveNotSupportedException) {
				Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING)
			}
		}

		private fun requireInside(root: File, child: File): File {
			val canonicalRoot = root.canonicalFile.toPath()
			val canonicalChild = child.canonicalFile.toPath()
			if (!canonicalChild.startsWith(canonicalRoot) || canonicalChild == canonicalRoot) {
				throw SecurityException("Plugin transaction path escaped its root")
			}
			return canonicalChild.toFile()
		}

		private fun fsyncDirectory(directory: File?) {
			if (directory == null || !directory.isDirectory) return
			runCatching { FileChannel.open(directory.toPath(), READ).use { it.force(true) } }
		}
	}
}
