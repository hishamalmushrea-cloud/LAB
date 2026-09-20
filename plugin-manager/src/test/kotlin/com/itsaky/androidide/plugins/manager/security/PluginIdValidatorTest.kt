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
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginIdValidatorTest {
	@Test
	fun `accepts lower case reverse DNS identifiers`() {
		assertThat(PluginIdValidator.isValid("org.example.my_plugin2")).isTrue()
	}

	@Test
	fun `rejects traversal separators and ambiguous identifiers`() {
		listOf(
			"../escape",
			"org.example/escape",
			"org.example\\escape",
			"Org.example.plugin",
			"org.example.my-plugin",
			"single",
			"org..plugin",
			" org.example.plugin",
		).forEach { assertThat(PluginIdValidator.isValid(it)).isFalse() }
	}

	@Test
	fun `rejects identifiers over the storage limit`() {
		val tooLong = "a." + "b".repeat(PluginIdValidator.MAX_LENGTH)
		assertThrows(IllegalArgumentException::class.java) {
			PluginIdValidator.requireValid(tooLong)
		}
	}
}
