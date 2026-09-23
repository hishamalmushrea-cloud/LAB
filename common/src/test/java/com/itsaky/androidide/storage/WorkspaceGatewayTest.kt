package com.itsaky.androidide.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * These tests pin the consequence of the ADR 0016 decision to keep existing projects where they
 * are: a granted tree is never directly buildable, and nothing outside the workspace is writable.
 */
class WorkspaceGatewayTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun gateway(root: File = temporaryFolder.root) = WorkspaceGateway(WorkspaceLayout(root))

	@Test
	fun `a managed directory that exists is buildable`() {
		val project = temporaryFolder.newFolder("projects", "app")

		val eligibility = gateway().buildEligibility(WorkspaceLocation.Managed(project))

		assertThat(eligibility).isEqualTo(BuildEligibility.Eligible(project))
	}

	@Test
	fun `a granted tree always needs an import first`() {
		// The central trade-off of ADR 0016: Gradle and the Termux toolchain need real paths, so a
		// SAF grant alone is never enough, however convenient it would be to pretend otherwise.
		val location = WorkspaceLocation.Granted(treeUri = "content://tree/primary%3AProjects", displayName = "Projects")

		val eligibility = gateway().buildEligibility(location)

		assertThat(eligibility).isEqualTo(BuildEligibility.Blocked(BuildBlocker.NEEDS_IMPORT))
	}

	@Test
	fun `a missing directory is reported as missing rather than as a generic failure`() {
		val absent = File(temporaryFolder.root, "gone")

		val eligibility = gateway().buildEligibility(WorkspaceLocation.Managed(absent))

		assertThat(eligibility).isEqualTo(BuildEligibility.Blocked(BuildBlocker.MISSING))
	}

	@Test
	fun `an unwritable directory is distinguished from a missing one`() {
		val project = temporaryFolder.newFolder("readonly")
		assertThat(project.setWritable(false)).isTrue()

		try {
			val eligibility = gateway().buildEligibility(WorkspaceLocation.Managed(project))

			assertThat(eligibility).isEqualTo(BuildEligibility.Blocked(BuildBlocker.NOT_WRITABLE))
		} finally {
			project.setWritable(true)
		}
	}

	@Test
	fun `importing a granted tree lands in the projects directory under a safe name`() {
		val layout = WorkspaceLayout(temporaryFolder.root)
		val location = WorkspaceLocation.Granted(treeUri = "content://tree/x", displayName = "../escape")

		val destination = WorkspaceGateway(layout).importDestination(location)

		assertThat(destination).isNotNull()
		assertThat(destination!!.parentFile).isEqualTo(layout.projects)
		assertThat(layout.contains(destination)).isTrue()
	}

	@Test
	fun `importing an already managed project is refused`() {
		// Silently duplicating the user's work is the failure mode that made bulk auto-copy
		// unacceptable; returning null keeps that from happening one project at a time.
		val project = temporaryFolder.newFolder("projects", "app")

		assertThat(gateway().importDestination(WorkspaceLocation.Managed(project))).isNull()
	}

	@Test
	fun `only paths inside the workspace are writable`() {
		val gateway = gateway()

		assertThat(gateway.isWritable(File(temporaryFolder.root, "logs/idelog.txt"))).isTrue()
		assertThat(gateway.isWritable(File("/storage/emulated/0/idelog.txt"))).isFalse()
		assertThat(gateway.isWritable(File("/sdcard/idelog.txt"))).isFalse()
	}

	@Test
	fun `a location's id is stable and distinguishes the two kinds`() {
		val managed = WorkspaceLocation.Managed(File(temporaryFolder.root, "projects/app"))
		val granted = WorkspaceLocation.Granted(treeUri = "content://tree/x", displayName = "x")

		assertThat(managed.id).isEqualTo(managed.directory.absolutePath)
		assertThat(granted.id).isEqualTo("content://tree/x")
		assertThat(managed.id).isNotEqualTo(granted.id)
	}
}
