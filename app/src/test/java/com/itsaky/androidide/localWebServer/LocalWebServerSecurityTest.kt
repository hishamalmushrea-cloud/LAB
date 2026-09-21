package com.itsaky.androidide.localWebServer

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalWebServerSecurityTest {
	@Test
	fun `session tokens carry 256 bits in URL-safe unpadded form`() {
		val tokens = List(32) { LocalWebServerSecurity.newSessionToken() }

		assertThat(tokens.toSet()).hasSize(tokens.size)
		tokens.forEach { token ->
			assertThat(token).hasLength(43)
			assertThat(Regex("[A-Za-z0-9_-]+").matches(token)).isTrue()
		}
	}

	@Test
	fun `server configuration cannot expose its listener or leave stalled clients unbounded`() {
		assertThrows(IllegalArgumentException::class.java) {
			testServerConfig().copy(bindName = "0.0.0.0")
		}
		assertThrows(IllegalArgumentException::class.java) {
			testServerConfig().copy(clientRequestTimeoutMs = 99)
		}
	}

	@Test
	fun `session token validation rejects empty short and cookie-breaking values`() {
		listOf("", "short", "a".repeat(31), "a".repeat(31) + ";").forEach { token ->
			assertThrows(IllegalArgumentException::class.java) {
				LocalWebServerSecurity.requireValidSessionToken(token)
			}
		}
	}

	@Test
	fun `dynamic endpoint capability accepts exactly one matching header or cookie`() {
		val cookie = "unrelated=x; ${LocalWebServerSecurity.SESSION_COOKIE_NAME}=$TEST_SESSION_TOKEN; another=y"

		assertThat(
			LocalWebServerSecurity.hasValidSession(
				mapOf(LocalWebServerSecurity.SESSION_HEADER to TEST_SESSION_TOKEN),
				TEST_SESSION_TOKEN,
			),
		).isTrue()
		assertThat(LocalWebServerSecurity.hasValidSession(mapOf("cookie" to cookie), TEST_SESSION_TOKEN)).isTrue()
		assertThat(LocalWebServerSecurity.hasValidSession(emptyMap(), TEST_SESSION_TOKEN)).isFalse()
		assertThat(
			LocalWebServerSecurity.hasValidSession(
				mapOf(LocalWebServerSecurity.SESSION_HEADER to "wrong-session-token-0123456789abcdef"),
				TEST_SESSION_TOKEN,
			),
		).isFalse()
	}

	@Test
	fun `ambiguous duplicate capabilities are rejected even when their values match`() {
		val duplicatedCookie =
			"${LocalWebServerSecurity.SESSION_COOKIE_NAME}=$TEST_SESSION_TOKEN; " +
				"${LocalWebServerSecurity.SESSION_COOKIE_NAME}=$TEST_SESSION_TOKEN"
		val headerAndCookie =
			mapOf(
				LocalWebServerSecurity.SESSION_HEADER to TEST_SESSION_TOKEN,
				"cookie" to "${LocalWebServerSecurity.SESSION_COOKIE_NAME}=$TEST_SESSION_TOKEN",
			)

		assertThat(LocalWebServerSecurity.hasValidSession(mapOf("cookie" to duplicatedCookie), TEST_SESSION_TOKEN)).isFalse()
		assertThat(LocalWebServerSecurity.hasValidSession(headerAndCookie, TEST_SESSION_TOKEN)).isFalse()
	}

	@Test
	fun `webview cookie is scoped to dynamic paths and hidden from scripts`() {
		val cookie = LocalWebServerSecurity.sessionCookie(TEST_SESSION_TOKEN)

		assertThat(cookie).contains("${LocalWebServerSecurity.SESSION_COOKIE_NAME}=$TEST_SESSION_TOKEN")
		assertThat(cookie).contains("Path=/pr/")
		assertThat(cookie).contains("HttpOnly")
		assertThat(cookie).contains("SameSite=Strict")
	}

	@Test
	fun `only exact loopback authorities and same origins are accepted`() {
		val port = 6174

		listOf("localhost:$port", "127.0.0.1:$port", "[::1]:$port").forEach {
			assertThat(LocalWebServerSecurity.isAllowedHost(it, port)).isTrue()
		}
		listOf(
			"localhost",
			"attacker.example",
			"localhost.attacker.example:$port",
			"localhost:80",
			"localhost@$port",
		).forEach {
			assertThat(LocalWebServerSecurity.isAllowedHost(it, port)).isFalse()
		}
		listOf("http://localhost:$port", "http://127.0.0.1:$port", "http://[::1]:$port").forEach {
			assertThat(LocalWebServerSecurity.isAllowedOrigin(it, port)).isTrue()
		}
		listOf("https://localhost:$port", "http://localhost", "http://attacker.example:$port", "null").forEach {
			assertThat(LocalWebServerSecurity.isAllowedOrigin(it, port)).isFalse()
		}
	}
}
