package com.itsaky.androidide.localWebServer

internal const val TEST_SESSION_TOKEN = "test-session-token-0123456789abcdef"

/**
 * The `ServerConfig` every test in this package uses.
 *
 * Every path is given explicitly: `ServerConfig` has no defaults for the developer-override paths
 * (see `DeveloperOverrides`), and a JVM test has no stub for them anyway. Shared rather than copied
 * per class, so a new required field is a one-line fix instead of a hunt, and two fixtures cannot
 * drift into subtly different servers.
 */
internal fun testServerConfig(port: Int = 0) =
	ServerConfig(
		port = port,
		databasePath = "/nonexistent/test.db",
		sessionToken = TEST_SESSION_TOKEN,
		debugDatabasePath = "/nonexistent/debug.db",
		debugEnablePath = "/nonexistent/debug-flag",
		experimentsEnablePath = "/nonexistent/exp-flag",
		clearCacheEnablePath = "/nonexistent/cs0-flag",
		projectDatabasePath = "/nonexistent/recent-projects.db",
	)
