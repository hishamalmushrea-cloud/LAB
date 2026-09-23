package com.itsaky.androidide.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The layout is the only thing standing between an externally supplied name and the filesystem, so
 * the traversal tests below are the ones that matter.
 */
class WorkspaceLayoutTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun layout() = WorkspaceLayout(temporaryFolder.root)

	@Test
	fun `standard directories live under the root`() {
		val layout = layout()

		for (directory in listOf(layout.projects, layout.logs, layout.caches, layout.imports)) {
			assertThat(layout.contains(directory)).isTrue()
			assertThat(directory.parentFile).isEqualTo(temporaryFolder.root)
		}
	}

	@Test
	fun `createDirectories is idempotent`() {
		val layout = layout()

		layout.createDirectories()
		layout.createDirectories()

		assertThat(layout.projects.isDirectory).isTrue()
		assertThat(layout.logs.isDirectory).isTrue()
	}

	@Test
	fun `a traversing project name cannot escape the projects directory`() {
		val layout = layout()

		val directory = layout.projectDirectory("../../etc/passwd")

		assertThat(directory.parentFile).isEqualTo(layout.projects)
		assertThat(layout.contains(directory)).isTrue()
		assertThat(directory.name).doesNotContain("/")
	}

	@Test
	fun `project names are sanitised rather than rejected`() {
		// These arrive from a text field or from the display name of a picked SAF tree, so failing
		// loudly far from the input would be worse than producing a usable name.
		assertThat(WorkspaceLayout.safeProjectName("My App")).isEqualTo("My_App")
		assertThat(WorkspaceLayout.safeProjectName("a/b")).isEqualTo("a_b")
		assertThat(WorkspaceLayout.safeProjectName("..")).isEqualTo("project")
		assertThat(WorkspaceLayout.safeProjectName("   ")).isEqualTo("project")
		assertThat(WorkspaceLayout.safeProjectName(".hidden")).isEqualTo("hidden")
		assertThat(WorkspaceLayout.safeProjectName("ok-name_1.2")).isEqualTo("ok-name_1.2")
	}

	@Test
	fun `a very long name is truncated`() {
		val name = WorkspaceLayout.safeProjectName("x".repeat(500))

		assertThat(name).hasLength(WorkspaceLayout.MAX_NAME_LENGTH)
	}

	@Test
	fun `resolveWithin accepts a path inside the root`() {
		val layout = layout()

		val resolved = layout.resolveWithin("projects/app/src")

		assertThat(resolved).isNotNull()
		assertThat(layout.contains(resolved!!)).isTrue()
	}

	@Test
	fun `resolveWithin rejects traversal and the root itself`() {
		val layout = layout()

		assertThat(layout.resolveWithin("../outside")).isNull()
		assertThat(layout.resolveWithin("projects/../../outside")).isNull()
		assertThat(layout.resolveWithin("")).isNull()
		assertThat(layout.resolveWithin("   ")).isNull()
		// Returning the root would let a caller treat the whole workspace as one project.
		assertThat(layout.resolveWithin(".")).isNull()
	}

	@Test
	fun `resolveWithin rejects an absolute path outside the root`() {
		val layout = layout()

		assertThat(layout.resolveWithin("/etc/passwd")).isNull()
	}

	@Test
	fun `contains is not fooled by a symlink pointing outside`() {
		// A plain string prefix check would pass this, which is why the implementation canonicalises.
		val layout = layout()
		val outside = temporaryFolder.newFolder("outside-target")
		val link = File(temporaryFolder.root, "link")
		java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())

		assertThat(layout.contains(link)).isFalse()
	}

	@Test
	fun `contains rejects a sibling directory with a shared prefix`() {
		val root = temporaryFolder.newFolder("workspace")
		val sibling = temporaryFolder.newFolder("workspace-other")

		assertThat(WorkspaceLayout(root).contains(sibling)).isFalse()
	}
}
