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

package com.itsaky.androidide.plugins.manager.context

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagerImplTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun manager(pluginId: String = "org.example.plugin") =
		ResourceManagerImpl(
			pluginId = pluginId,
			pluginsDir = temporaryFolder.root,
			classLoader = javaClass.classLoader!!,
		)

	@Test
	fun `allows nested paths inside plugin directory`() {
		val file = manager().getPluginFile("nested/file.txt")
		assertThat(file.toPath().startsWith(temporaryFolder.root.toPath())).isTrue()
	}

	@Test
	fun `rejects traversal and prefix sibling escape`() {
		assertThrows(SecurityException::class.java) {
			manager().getPluginFile("../escape/file.txt")
		}
		assertThrows(SecurityException::class.java) {
			manager().getPluginFile("../../org.example.plugin-escape/file.txt")
		}
	}

	@Test
	fun `rejects invalid plugin identifiers`() {
		assertThrows(IllegalArgumentException::class.java) { manager("../escape") }
	}
}
