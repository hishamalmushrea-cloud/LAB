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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AtomicPluginInstallerTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val pluginId = "org.example.plugin"

	@Test
	fun `publishes a validated package without deleting source`() {
		val source = source("new")
		val loaded = mutableListOf<String>()
		val target =
			installer().install(
				pluginId,
				source,
				null,
				validateStaged = { assertThat(it.readText()).isEqualTo("new") },
				unloadCurrent = {},
				loadReplacement = {
					loaded += it.readText()
					Result.success(Unit)
				},
				reloadPrevious = { Result.failure(AssertionError("nothing to reload")) },
			)
		assertThat(target.readText()).isEqualTo("new")
		assertThat(source.readText()).isEqualTo("new")
		assertThat(loaded).containsExactly("new")
	}

	@Test
	fun `validation failure leaves previous package untouched`() {
		val old = installed("old")
		var unloaded = false
		assertThrows(IllegalArgumentException::class.java) {
			installer().install(
				pluginId,
				source("bad"),
				old,
				validateStaged = { throw IllegalArgumentException("invalid") },
				unloadCurrent = { unloaded = true },
				loadReplacement = { Result.success(Unit) },
				reloadPrevious = { Result.success(Unit) },
			)
		}
		assertThat(old.readText()).isEqualTo("old")
		assertThat(unloaded).isFalse()
	}

	@Test
	fun `unload failure keeps and reloads previous package`() {
		val old = installed("old")
		var reloaded = false
		assertThrows(IllegalStateException::class.java) {
			installer().install(
				pluginId,
				source("new"),
				old,
				validateStaged = {},
				unloadCurrent = { throw IllegalStateException("unload failed") },
				loadReplacement = { Result.success(Unit) },
				reloadPrevious = {
					reloaded = true
					Result.success(Unit)
				},
			)
		}
		assertThat(installedFile().readText()).isEqualTo("old")
		assertThat(reloaded).isTrue()
	}

	@Test
	fun `load failure atomically restores and reloads previous package`() {
		val old = installed("old")
		val reloaded = mutableListOf<String>()
		assertThrows(IllegalStateException::class.java) {
			installer().install(
				pluginId,
				source("new"),
				old,
				validateStaged = {},
				unloadCurrent = {},
				loadReplacement = { Result.failure(IllegalStateException("load failed")) },
				reloadPrevious = { reloaded += it.readText(); Result.success(Unit) },
			)
		}
		assertThat(installedFile().readText()).isEqualTo("old")
		assertThat(reloaded).containsExactly("old")
	}

	@Test
	fun `successful update retains old bytes until replacement loads`() {
		val old = installed("old")
		var backupExisted = false
		installer().install(
			pluginId,
			source("new"),
			old,
			validateStaged = {},
			unloadCurrent = {},
			loadReplacement = {
				backupExisted = File(pluginsDir(), ".transactions/$pluginId.previous.cgp").exists()
				Result.success(Unit)
			},
			reloadPrevious = { Result.failure(AssertionError("must not roll back")) },
		)
		assertThat(backupExisted).isTrue()
		assertThat(installedFile().readText()).isEqualTo("new")
	}

	@Test
	fun `rejects a transaction directory symlink`() {
		val outside = temporaryFolder.newFolder("outside-transactions")
		Files.createSymbolicLink(File(pluginsDir(), ".transactions").toPath(), outside.toPath())

		assertThrows(SecurityException::class.java) {
			installer().install(
				pluginId,
				source("new"),
				null,
				validateStaged = {},
				unloadCurrent = {},
				loadReplacement = { Result.success(Unit) },
				reloadPrevious = { Result.success(Unit) },
			)
		}
		assertThat(outside.listFiles().orEmpty()).isEmpty()
	}

	@Test
	fun `startup recovery rolls activating update back`() {
		val transactions = File(pluginsDir(), ".transactions").apply { mkdirs() }
		installed("new")
		File(transactions, "$pluginId.previous.cgp").writeText("old")
		File(transactions, "$pluginId.transaction").writeText("$pluginId\nACTIVATING\n")
		AtomicPluginInstaller.recoverInterruptedTransactions(pluginsDir())
		assertThat(installedFile().readText()).isEqualTo("old")
		assertThat(transactions.exists()).isFalse()
	}

	@Test
	fun `startup recovery keeps committed replacement`() {
		val transactions = File(pluginsDir(), ".transactions").apply { mkdirs() }
		installed("new")
		File(transactions, "$pluginId.previous.cgp").writeText("old")
		File(transactions, "$pluginId.transaction").writeText("$pluginId\nCOMMITTED\n")
		AtomicPluginInstaller.recoverInterruptedTransactions(pluginsDir())
		assertThat(installedFile().readText()).isEqualTo("new")
		assertThat(transactions.exists()).isFalse()
	}

	@Test
	fun `startup recovery removes interrupted first install`() {
		val transactions = File(pluginsDir(), ".transactions").apply { mkdirs() }
		installed("unverified")
		File(transactions, "$pluginId.transaction").writeText("$pluginId\nACTIVATING\n")
		AtomicPluginInstaller.recoverInterruptedTransactions(pluginsDir())
		assertThat(installedFile().exists()).isFalse()
		assertThat(transactions.exists()).isFalse()
	}

	private fun installer() = AtomicPluginInstaller(pluginsDir())
	private fun pluginsDir() = File(temporaryFolder.root, "plugins").apply { mkdirs() }
	private fun installedFile() = File(pluginsDir(), "$pluginId.cgp")
	private fun installed(content: String) = installedFile().apply { writeText(content) }
	private fun source(content: String) =
		temporaryFolder.newFile("source-${System.nanoTime()}.cgp").apply { writeText(content) }
}
