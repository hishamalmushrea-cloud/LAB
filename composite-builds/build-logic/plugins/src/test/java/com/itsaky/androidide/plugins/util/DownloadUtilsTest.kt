package com.itsaky.androidide.plugins.util

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.api.logging.Logging
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class DownloadUtilsTest {

    private lateinit var temporaryDirectory: Path
    private val logger = Logging.getLogger(DownloadUtilsTest::class.java)
    private val url = URL("https://downloads.example.test/asset.bin")

    @Before
    fun setUp() {
        temporaryDirectory = Files.createTempDirectory("download-utils-test")
    }

    @After
    fun tearDown() {
        temporaryDirectory.toFile().deleteRecursively()
    }

    @Test
    fun downloadsAndAtomicallyReplacesDestination() {
        val destination = file("asset.bin").apply { writeText("old-value") }
        val expected = "new-value".toByteArray()
        val connection = FakeConnection(url, HttpURLConnection.HTTP_OK, expected)

        download(destination, expected, connection)

        assertThat(destination.readBytes()).isEqualTo(expected)
        assertThat(file("asset.bin.part").exists()).isFalse()
    }

    @Test
    fun resumesPartialDownloadWithValidatedRange() {
        val destination = file("asset.bin")
        file("asset.bin.part").writeText("hello ")
        val expected = "hello world".toByteArray()
        val connection =
            FakeConnection(
                url,
                HttpURLConnection.HTTP_PARTIAL,
                "world".toByteArray(),
                contentRange = "bytes 6-10/11",
            )

        download(destination, expected, connection)

        assertThat(connection.requestHeaders["Range"]).isEqualTo("bytes=6-")
        assertThat(destination.readBytes()).isEqualTo(expected)
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val destination = file("asset.bin")
        file("asset.bin.part").writeText("stale prefix")
        val expected = "complete response".toByteArray()
        val connection = FakeConnection(url, HttpURLConnection.HTTP_OK, expected)

        download(destination, expected, connection)

        assertThat(connection.requestHeaders["Range"]).isEqualTo("bytes=12-")
        assertThat(destination.readBytes()).isEqualTo(expected)
    }

    @Test
    fun checksumMismatchPreservesPreviousDestinationAndDeletesPartial() {
        val destination = file("asset.bin").apply { writeText("old-value") }
        val expected = "new-value".toByteArray()
        val connection =
            FakeConnection(url, HttpURLConnection.HTTP_OK, "bad-value".toByteArray())

        val error =
            org.junit.Assert.assertThrows(GradleException::class.java) {
                download(destination, expected, connection)
            }

        assertThat(error).hasMessageThat().contains("Checksum mismatch")
        assertThat(destination.readText()).isEqualTo("old-value")
        assertThat(file("asset.bin.part").exists()).isFalse()
    }

    @Test
    fun truncatedResponseRemainsAvailableForResume() {
        val destination = file("asset.bin").apply { writeText("old-value") }
        val expected = "ten-bytes!".toByteArray()
        val connection = FakeConnection(url, HttpURLConnection.HTTP_OK, "short".toByteArray())

        val error =
            org.junit.Assert.assertThrows(GradleException::class.java) {
                download(destination, expected, connection)
            }

        assertThat(error).hasMessageThat().contains("Size mismatch")
        assertThat(destination.readText()).isEqualTo("old-value")
        assertThat(file("asset.bin.part").readText()).isEqualTo("short")
    }

    @Test
    fun validCachedDestinationDoesNotOpenConnection() {
        val expected = "already verified".toByteArray()
        val destination = file("asset.bin").apply { writeBytes(expected) }

        DownloadUtils.downloadFile(
            url,
            destination,
            sha256(expected),
            expected.size.toLong(),
            logger,
        ) {
            throw AssertionError("A valid cached destination must not access the network")
        }

        assertThat(destination.readBytes()).isEqualTo(expected)
    }

    @Test
    fun rejectsNonHttpsUrl() {
        val error =
            org.junit.Assert.assertThrows(GradleException::class.java) {
                DownloadUtils.downloadFile(
                    URL("http://downloads.example.test/asset.bin"),
                    file("asset.bin"),
                    "a".repeat(64),
                    1,
                    logger,
                ) {
                    throw AssertionError("Invalid URL must be rejected before opening a connection")
                }
            }

        assertThat(error).hasMessageThat().contains("non-HTTPS")
    }

    private fun download(
        destination: File,
        expected: ByteArray,
        connection: HttpURLConnection,
    ) {
        DownloadUtils.downloadFile(
            url,
            destination,
            sha256(expected),
            expected.size.toLong(),
            logger,
        ) { connection }
    }

    private fun file(name: String): File = temporaryDirectory.resolve(name).toFile()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val payload: ByteArray,
        private val contentRange: String? = null,
    ) : HttpURLConnection(url) {
        val requestHeaders = mutableMapOf<String, String>()

        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        override fun getResponseCode(): Int = status

        override fun getInputStream(): InputStream = ByteArrayInputStream(payload)

        override fun getContentLengthLong(): Long = payload.size.toLong()

        override fun getHeaderField(name: String?): String? =
            if (name.equals("Content-Range", ignoreCase = true)) contentRange else null

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }
}
