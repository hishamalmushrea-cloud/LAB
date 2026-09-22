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

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.manager.loaders.PluginManifest
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginCompatibilityValidatorTest {
	private val validator = PluginCompatibilityValidator("26.38.1")

	@Test
	fun `accepts inclusive IDE range`() {
		validator.requireCompatible(manifest("26.38", "26.38.1"))
	}

	@Test
	fun `rejects host below or above range`() {
		assertThrows(IllegalArgumentException::class.java) {
			validator.requireCompatible(manifest("26.39", "99.0.0"))
		}
		assertThrows(IllegalArgumentException::class.java) {
			validator.requireCompatible(manifest("1.0.0", "26.37.9"))
		}
	}

	@Test
	fun `rejects malformed and inverted ranges`() {
		assertThrows(IllegalArgumentException::class.java) {
			validator.requireCompatible(manifest("latest", "99.0.0"))
		}
		assertThrows(IllegalArgumentException::class.java) {
			validator.requireCompatible(manifest("26.40", "26.30"))
		}
	}

	@Test
	fun `orders dependencies before dependents deterministically`() {
		val order =
			PluginDependencyResolver.resolve(
				mapOf(
					"org.example.app" to listOf("org.example.core", "org.example.ui"),
					"org.example.ui" to listOf("org.example.core"),
					"org.example.core" to emptyList(),
				),
			)
		assertThat(order).containsExactly("org.example.core", "org.example.ui", "org.example.app").inOrder()
	}

	@Test
	fun `rejects missing dependencies and cycles`() {
		assertThrows(IllegalArgumentException::class.java) {
			PluginDependencyResolver.resolve(
				mapOf("org.example.app" to listOf("org.example.missing")),
			)
		}
		assertThrows(IllegalArgumentException::class.java) {
			PluginDependencyResolver.resolve(
				mapOf(
					"org.example.a" to listOf("org.example.b"),
					"org.example.b" to listOf("org.example.a"),
				),
			)
		}
	}

	private fun manifest(
		min: String,
		max: String,
	) = PluginManifest(
		id = "org.example.plugin",
		name = "Plugin",
		version = "1.0.0",
		description = "",
		author = "",
		mainClass = "org.example.Plugin",
		minIdeVersion = min,
		maxIdeVersion = max,
		permissions = emptyList(),
		dependencies = emptyList(),
		extensions = emptyList(),
		sidebarItems = 0,
		iconDay = null,
		iconNight = null,
		vcsRevision = null,
		buildTimestamp = null,
	)
}
