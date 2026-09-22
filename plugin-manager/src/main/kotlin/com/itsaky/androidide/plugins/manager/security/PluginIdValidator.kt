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

/** Canonical validation for every plugin identifier that can reach storage or registries. */
object PluginIdValidator {
	const val MAX_LENGTH = 255
	private val pattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")

	fun isValid(pluginId: String): Boolean =
		pluginId.length in 1..MAX_LENGTH &&
			pluginId == pluginId.trim() &&
			pattern.matches(pluginId)

	fun requireValid(pluginId: String): String {
		require(isValid(pluginId)) {
			"Invalid plugin ID '$pluginId'. Expected a lower-case reverse-DNS identifier."
		}
		return pluginId
	}
}
