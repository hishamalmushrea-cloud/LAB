package com.itsaky.androidide.doctor

/**
 * Renders a shareable diagnostics report.
 *
 * The roadmap asks for "export report after redacting sensitive data". Redaction is the whole point
 * of this file: a report is pasted into a bug tracker or a chat, so anything that could carry a
 * username, a project name or a device identifier has to be removed *before* it is written, not
 * left to the person pasting it.
 *
 * What is deliberately never included: absolute paths, project names, the session token, network
 * addresses, and the serial or device identifiers. The findings themselves are authored to stay
 * free of user data, and [redact] is a second line of defence over anything interpolated into them.
 */
object DoctorReport {
	private const val REDACTED = "[redacted]"

	/**
	 * Paths are the main leak: `/storage/emulated/0/CodeOnTheGoProjects/MyEmployerSecretApp` names
	 * both the user's storage layout and their project. Any absolute-looking path is replaced
	 * wholesale rather than shortened, because a basename is often the identifying part.
	 */
	private val absolutePath = Regex("""(/(?:data|storage|sdcard|home|Users|mnt|var|tmp)[^\s"',;:)]*)""")

	/** A bare `user@host` or an email address in a stack trace or a Gradle message. */
	private val emailLike = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

	/** Long hex runs: session tokens, digests and device identifiers all look like this. */
	private val longHex = Regex("""\b[0-9a-fA-F]{32,}\b""")

	/**
	 * Returns [text] with anything identifying replaced.
	 *
	 * Order matters: emails are matched before paths, because an email inside a path would
	 * otherwise be swallowed by the path rule and the result would be the same, but a bare email
	 * outside one would not be caught if paths ran first and consumed the surrounding text.
	 */
	fun redact(text: String): String =
		text
			.replace(emailLike, REDACTED)
			.replace(absolutePath, REDACTED)
			.replace(longHex, REDACTED)

	/**
	 * Renders [findings] for [snapshot] as plain text, fully redacted.
	 *
	 * Plain text rather than JSON because the destination is a bug report body; the structured form
	 * is the [Finding] list itself, which a caller can serialise if it needs to.
	 */
	fun render(
		snapshot: EnvironmentSnapshot,
		findings: List<Finding>,
	): String =
		buildString {
			appendLine("=== Environment Doctor ===")
			appendLine("ABI: ${snapshot.installedAbi.ifEmpty { "unknown" }}")
			appendLine("Device ABIs: ${snapshot.supportedAbis.joinToString().ifEmpty { "unknown" }}")
			appendLine("Android API: ${snapshot.androidApiLevel}")
			appendLine("RAM: ${EnvironmentDoctor.formatBytes(snapshot.totalMemoryBytes)}")
			appendLine("Free storage: ${EnvironmentDoctor.formatBytes(snapshot.freeStorageBytes)}")
			appendLine("JDK installed: ${snapshot.javaHomeInstalled}")
			appendLine("Android SDK installed: ${snapshot.androidSdkInstalled}")
			appendLine("Assets: ${assetSummary(snapshot)}")
			appendLine()

			if (findings.isEmpty()) {
				appendLine("No problems detected.")
				return@buildString
			}

			appendLine("Findings (${findings.size}):")
			for (finding in findings) {
				appendLine()
				appendLine("[${finding.severity}] ${finding.id}")
				appendLine("  ${redact(finding.summary)}")
				appendLine("  ${redact(finding.detail)}")
				if (finding.remedy != Remedy.NONE) {
					appendLine("  Fix: ${finding.remedy}")
				}
			}
		}

	private fun assetSummary(snapshot: EnvironmentSnapshot): String {
		if (snapshot.assets.isEmpty()) return "none tracked"
		val byState = snapshot.assets.groupingBy { it.state }.eachCount()
		return AssetState.entries.filter { byState.containsKey(it) }.joinToString { "$it=${byState[it]}" }
	}
}
