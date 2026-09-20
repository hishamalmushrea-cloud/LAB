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

/** Trust decision kept pure so normal/developer/update paths can be exhaustively tested. */
object PluginTrustPolicy {
	fun requireTrusted(
		pluginId: String,
		signerDigests: Set<String>,
		trustedPublisherDigests: Set<String>,
		existingPluginDigests: Set<String> = emptySet(),
		isAlreadyInstalled: Boolean = false,
		developerMode: Boolean = false,
	) {
		PluginIdValidator.requireValid(pluginId)
		if (developerMode) return
		require(signerDigests.isNotEmpty()) { "Plugin $pluginId is unsigned" }
		when {
			existingPluginDigests.isNotEmpty() && signerDigests == existingPluginDigests -> return
			trustedPublisherDigests.isNotEmpty() && signerDigests == trustedPublisherDigests -> return
			isAlreadyInstalled -> return
			else -> throw SecurityException("Plugin $pluginId is not signed by a trusted publisher")
		}
	}
}
