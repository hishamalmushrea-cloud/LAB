package com.itsaky.androidide.documentation

import android.net.Uri
import android.webkit.WebResourceRequest
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers which requests the interceptor takes and which it hands to the local web server
 * (ADFA-5176). Asserts on `contentFor` rather than `intercept`, because building the
 * WebResourceResponse `intercept` returns needs the framework.
 */
class DocumentationRequestInterceptorTest {
	@get:Rule
	val folder = TemporaryFolder()

	private lateinit var source: DocumentationContentSource

	/** The switch is off unless a test creates the file; the path is app-private, never shared. */
	private fun interceptor(sentinel: File = File(folder.root, DocumentationRequestInterceptor.DISABLE_SENTINEL)) =
		DocumentationRequestInterceptor(source, sentinel)

	@Before
	fun setUp() {
		source = mockk(relaxed = true)
		every { source.lookupRequestPath(any()) } answers {
			RequestLookup(firstArg(), DocumentationLookup.Found(DocumentationContent("page".toByteArray(), "text/html")))
		}
	}

	@After
	fun tearDown() {
		unmockkAll()
	}

	private fun request(
		url: String = "http://localhost:6174/i/index.html",
		method: String = "GET",
	): WebResourceRequest {
		// Uri is a framework class, so stand in for the three parts the interceptor reads. Only
		// encodedPath is stubbed, not path: a call to the decoded form should fail the test.
		val parsed = java.net.URI(url)
		val uri =
			mockk<Uri> {
				every { host } returns parsed.host
				every { port } returns parsed.port
				every { encodedPath } returns parsed.rawPath
			}

		return mockk {
			every { this@mockk.method } returns method
			every { this@mockk.url } returns uri
		}
	}

	@Test
	fun `serves a documentation path from the content source`() {
		val content = interceptor().contentFor(request())

		assertThat(content).isNotNull()
		assertThat(content!!.bytes.toString(Charsets.UTF_8)).isEqualTo("page")
	}

	@Test
	fun `declines anything but GET, since only the server handles a request with a body`() {
		assertThat(interceptor().contentFor(request(method = "POST"))).isNull()
	}

	@Test
	fun `declines a host or port that is not the local documentation server`() {
		val interceptor = interceptor()

		assertThat(interceptor.contentFor(request(url = "http://example.com:6174/i/index.html"))).isNull()
		assertThat(interceptor.contentFor(request(url = "http://localhost:8080/i/index.html"))).isNull()
	}

	@Test
	fun `declines the developer endpoints, which only the server implements`() {
		val interceptor = interceptor()

		assertThat(interceptor.contentFor(request(url = "http://localhost:6174/pr/bs"))).isNull()
		assertThat(interceptor.contentFor(request(url = "http://localhost:6174/pr/db"))).isNull()
	}

	@Test
	fun `declines a bare origin with no path`() {
		assertThat(interceptor().contentFor(request(url = "http://localhost:6174/"))).isNull()
	}

	@Test
	fun `declines what the source cannot find, so the server can answer it`() {
		every { source.lookupRequestPath(any()) } answers { RequestLookup(firstArg(), DocumentationLookup.NotFound) }

		assertThat(interceptor().contentFor(request())).isNull()
	}

	// Stored Content.path rows are percent-encoded, so the raw target is what matches them; the
	// source owns the decoded fallback, identically for both transports.
	@Test
	fun `passes the raw percent-encoded target to the source, not the decoded form`() {
		val queried = mutableListOf<String>()
		every { source.lookupRequestPath(capture(queried)) } answers {
			RequestLookup(firstArg(), DocumentationLookup.Found(DocumentationContent("page".toByteArray(), "text/html")))
		}

		val content =
			interceptor()
				.contentFor(request(url = "http://localhost:6174/t/Draft%20%20Tutorial.html"))

		assertThat(content).isNotNull()
		assertThat(queried).containsExactly("t/Draft%20%20Tutorial.html")
	}

	@Test
	fun `the sentinel file puts documentation back on the web server`() {
		val sentinel = File(folder.root, DocumentationRequestInterceptor.DISABLE_SENTINEL)
		sentinel.createNewFile()

		val interceptor = interceptor(sentinel)

		assertThat(interceptor.contentFor(request())).isNull()
		assertThat(interceptor.servedSummary()).contains("off")
	}

	@Test
	fun `a sentinel on shared storage no longer disables in-process serving`() {
		// The switch used to live under shared Download/, where any app with storage access could
		// create it. Such a file must now have no effect at all.
		File(folder.root, "Download").mkdirs()
		File(folder.root, "Download/CodeOnTheGo.nointercept").createNewFile()

		val interceptor = interceptor()

		assertThat(interceptor.contentFor(request())).isNotNull()
		assertThat(interceptor.servedSummary()).doesNotContain("off")
	}

	@Test
	fun `reports what it has served`() {
		val interceptor = interceptor()
		repeat(3) { interceptor.contentFor(request()) }

		assertThat(interceptor.servedSummary()).isEqualTo("3 requests, 12 bytes served in-process")
	}

	@Test
	fun `splits a stored MIME type into what a WebResourceResponse needs`() {
		assertThat(DocumentationRequestInterceptor.mimeAndCharset("text/html; charset=utf-8"))
			.isEqualTo("text/html" to "utf-8")
		// Text with no stated charset: the doc set is UTF-8 throughout.
		assertThat(DocumentationRequestInterceptor.mimeAndCharset("text/css")).isEqualTo("text/css" to "utf-8")
		// Binary content must not claim one, or a WebView will try to decode it as text.
		assertThat(DocumentationRequestInterceptor.mimeAndCharset("image/png")).isEqualTo("image/png" to null)
		assertThat(DocumentationRequestInterceptor.mimeAndCharset("application/pdf")).isEqualTo("application/pdf" to null)
	}
}
