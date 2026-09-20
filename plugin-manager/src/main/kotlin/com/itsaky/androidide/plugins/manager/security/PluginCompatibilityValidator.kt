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

import com.itsaky.androidide.plugins.manager.loaders.PluginManifest

data class IdeVersion(
	val year: Int,
	val week: Int,
	val patch: Int,
) : Comparable<IdeVersion> {
	override fun compareTo(other: IdeVersion): Int =
		compareValuesBy(this, other, IdeVersion::year, IdeVersion::week, IdeVersion::patch)

	companion object {
		private val pattern = Regex("^(\\d{1,4})\\.(\\d{1,2})(?:\\.(\\d+))?(?:[-+].*)?$")

		fun parse(value: String): IdeVersion? {
			val match = pattern.matchEntire(value.trim()) ?: return null
			val year = match.groupValues[1].toIntOrNull() ?: return null
			val week = match.groupValues[2].toIntOrNull() ?: return null
			val patch = match.groupValues[3].ifBlank { "0" }.toIntOrNull() ?: return null
			if (week !in 0..53) return null
			return IdeVersion(year, week, patch)
		}
	}
}

class PluginCompatibilityValidator(hostVersion: String) {
	private val host =
		requireNotNull(IdeVersion.parse(hostVersion)) { "Invalid host IDE version: $hostVersion" }

	fun requireCompatible(manifest: PluginManifest) {
		val minimum =
			requireNotNull(IdeVersion.parse(manifest.minIdeVersion)) {
				"Plugin ${manifest.id} has invalid minimum IDE version '${manifest.minIdeVersion}'"
			}
		val maximum =
			requireNotNull(IdeVersion.parse(manifest.maxIdeVersion)) {
				"Plugin ${manifest.id} has invalid maximum IDE version '${manifest.maxIdeVersion}'"
			}
		require(minimum <= maximum) { "Plugin ${manifest.id} has an inverted IDE version range" }
		require(host in minimum..maximum) {
			"Plugin ${manifest.id} requires IDE ${manifest.minIdeVersion}..${manifest.maxIdeVersion}; host is $host"
		}
	}
}

/** Deterministic dependency-first ordering with missing-dependency and cycle rejection. */
object PluginDependencyResolver {
	fun resolve(dependencies: Map<String, List<String>>): List<String> {
		dependencies.forEach { (pluginId, required) ->
			PluginIdValidator.requireValid(pluginId)
			required.forEach(PluginIdValidator::requireValid)
			val missing = required.firstOrNull { it !in dependencies }
			require(missing == null) { "Plugin $pluginId requires missing dependency $missing" }
		}
		val result = mutableListOf<String>()
		val visiting = linkedSetOf<String>()
		val visited = mutableSetOf<String>()

		fun visit(pluginId: String) {
			if (pluginId in visited) return
			require(visiting.add(pluginId)) {
				"Plugin dependency cycle: ${(visiting + pluginId).joinToString(" -> ")}"
			}
			dependencies.getValue(pluginId).distinct().sorted().forEach(::visit)
			visiting.remove(pluginId)
			visited.add(pluginId)
			result.add(pluginId)
		}
		dependencies.keys.sorted().forEach(::visit)
		return result
	}
}
