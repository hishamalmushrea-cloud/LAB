package com.itsaky.androidide.localWebServer

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Security boundary shared by the loopback server and the WebViews that use it. */
internal object LocalWebServerSecurity {
	const val DEFAULT_PORT = 6174
	const val SESSION_HEADER = "x-codeonthego-session"
	const val SESSION_COOKIE_NAME = "CodeOnTheGo-Session"

	private const val SESSION_BYTES = 32
	private const val MIN_SESSION_TOKEN_LENGTH = 32
	private val tokenPattern = Regex("[A-Za-z0-9_-]+")

	/** Generates 256 bits of process-local entropy without characters that need cookie escaping. */
	fun newSessionToken(random: SecureRandom = SecureRandom()): String =
		ByteArray(SESSION_BYTES)
			.also(random::nextBytes)
			.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

	fun requireValidSessionToken(token: String): String =
		token.also {
			require(it.length >= MIN_SESSION_TOKEN_LENGTH && tokenPattern.matches(it)) {
				"Local web-server session token is missing or malformed"
			}
		}

	/** HttpOnly keeps documentation JavaScript from reading the capability it is allowed to use. */
	fun sessionCookie(token: String): String =
		"$SESSION_COOKIE_NAME=${requireValidSessionToken(token)}; Path=/pr/; HttpOnly; SameSite=Strict"

	fun serverOrigin(port: Int): String = "http://localhost:$port"

	/**
	 * A dynamic `/pr/` request is authorized by either the native-client header or the WebView's
	 * process-private cookie. Duplicate cookies are rejected rather than choosing an attacker-picked
	 * first or last value.
	 */
	fun hasValidSession(
		headers: Map<String, String>,
		expectedToken: String,
	): Boolean {
		val candidates = mutableListOf<String>()
		headers[SESSION_HEADER]?.let(candidates::add)
		headers["cookie"]
			?.split(';')
			?.map { it.trim() }
			?.filter { it.substringBefore('=', missingDelimiterValue = "") == SESSION_COOKIE_NAME }
			?.mapTo(candidates) { it.substringAfter('=', missingDelimiterValue = "") }

		return candidates.size == 1 && constantTimeEquals(candidates.single(), expectedToken)
	}

	fun isLoopbackBindName(value: String): Boolean =
		value.lowercase() in setOf("localhost", "127.0.0.1", "::1")

	fun isAllowedHost(value: String, port: Int): Boolean {
		val authorities = mutableSetOf("localhost:$port", "127.0.0.1:$port", "[::1]:$port")
		if (port == 80) authorities += setOf("localhost", "127.0.0.1", "[::1]")
		return value.trim().lowercase() in authorities
	}

	fun isAllowedOrigin(value: String, port: Int): Boolean =
		runCatching {
			val origin = URI(value)
			val host = origin.host?.lowercase()
			origin.scheme.equals("http", ignoreCase = true) &&
				origin.userInfo == null &&
				origin.query == null &&
				origin.fragment == null &&
				origin.rawPath.isNullOrEmpty() &&
				origin.port == port &&
				host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
		}.getOrDefault(false)

	private fun constantTimeEquals(left: String, right: String): Boolean =
		MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}
