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

package com.itsaky.androidide.plugins.manager.security

import org.junit.Assert.assertThrows
import org.junit.Test

class PluginTrustPolicyTest {
	private val pluginId = "org.example.plugin"

	@Test
	fun `normal mode rejects unsigned packages`() {
		assertThrows(IllegalArgumentException::class.java) {
			PluginTrustPolicy.requireTrusted(pluginId, emptySet(), setOf("host"))
		}
	}

	@Test
	fun `normal mode accepts host publisher`() {
		PluginTrustPolicy.requireTrusted(pluginId, setOf("host"), setOf("host"))
	}

	@Test
	fun `updates require complete signer continuity`() {
		assertThrows(SecurityException::class.java) {
			PluginTrustPolicy.requireTrusted(
				pluginId,
				setOf("a"),
				emptySet(),
				existingPluginDigests = setOf("a", "b"),
			)
		}
		PluginTrustPolicy.requireTrusted(
			pluginId,
			setOf("a", "b"),
			emptySet(),
			existingPluginDigests = setOf("a", "b"),
		)
	}

	@Test
	fun `developer mode explicitly permits unsigned local packages`() {
		PluginTrustPolicy.requireTrusted(
			pluginId,
			emptySet(),
			emptySet(),
			developerMode = true,
		)
	}

	@Test
	fun `signed installed packages receive migration allowance`() {
		PluginTrustPolicy.requireTrusted(
			pluginId,
			setOf("legacy"),
			emptySet(),
			isAlreadyInstalled = true,
		)
	}
}
