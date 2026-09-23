package com.itsaky.androidide.storage

import java.io.File

/**
 * Decides what the IDE may do with a given project location.
 *
 * This is the policy half of the ADR 0016 gateway. It exists because the decision recorded there -
 * existing projects are *not* copied into the workspace, they stay where they are behind a SAF
 * grant - only works if something enforces the consequence: a build can run against a managed
 * directory and cannot run against a granted tree until that tree has been imported.
 *
 * Keeping the rule here rather than at each call site means the answer is the same everywhere, and
 * that adding a new caller cannot quietly reintroduce "convert the tree to a File and hope".
 */
class WorkspaceGateway(
	private val layout: WorkspaceLayout,
) {
	/**
	 * Whether a build may run against [location], and if not, what the user has to do.
	 *
	 * A [WorkspaceLocation.Granted] is always blocked with [BuildBlocker.NEEDS_IMPORT]. That is not
	 * a limitation of this function but the central trade-off of ADR 0016: Gradle and the Termux
	 * toolchain need real filesystem paths, so a tree the IDE only holds a SAF grant for has to be
	 * materialised before it can be built.
	 */
	fun buildEligibility(location: WorkspaceLocation): BuildEligibility =
		when (location) {
			is WorkspaceLocation.Granted -> BuildEligibility.Blocked(BuildBlocker.NEEDS_IMPORT)
			is WorkspaceLocation.Managed -> {
				val directory = location.directory
				when {
					!directory.exists() -> BuildEligibility.Blocked(BuildBlocker.MISSING)
					!directory.canWrite() -> BuildEligibility.Blocked(BuildBlocker.NOT_WRITABLE)
					else -> BuildEligibility.Eligible(directory)
				}
			}
		}

	/**
	 * The directory an imported copy of [location] would occupy.
	 *
	 * Returns null for a location that is already managed, because importing it would be a no-op
	 * that silently duplicated the user's work - the failure mode that led to rejecting bulk
	 * auto-copy in the first place.
	 */
	fun importDestination(location: WorkspaceLocation): File? =
		when (location) {
			is WorkspaceLocation.Managed -> null
			is WorkspaceLocation.Granted -> layout.projectDirectory(location.displayName)
		}

	/**
	 * Whether [file] may be written by the IDE.
	 *
	 * Only paths inside the workspace qualify. This is what stops diagnostics, crash logs and
	 * caches from being written to shared storage, which is how `idelog.txt` came to be dropped -
	 * world-readable - in the root of the user's SD card.
	 */
	fun isWritable(file: File): Boolean = layout.contains(file)
}
