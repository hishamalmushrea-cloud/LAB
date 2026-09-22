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

package com.itsaky.androidide.plugins.util

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Shared fail-closed downloader for external build inputs. */
object DownloadUtils {
	private const val CONNECT_TIMEOUT_MILLIS = 30_000
	private const val READ_TIMEOUT_MILLIS = 180_000
	private val sha256Pattern = Regex("[0-9a-f]{64}")
	private val contentRangePattern = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")

	/**
	 * Downloads [url] into [destination] through a resumable sibling `.part` file. The destination
	 * is replaced atomically only after both [expectedSize] and [sha256Checksum] have been verified.
	 */
	fun downloadFile(
		url: URL,
		destination: File,
		sha256Checksum: String,
		expectedSize: Long,
		logger: Logger,
	) {
		downloadFile(url, destination, sha256Checksum, expectedSize, logger) { target ->
			target.openConnection() as HttpURLConnection
		}
	}

	internal fun downloadFile(
		url: URL,
		destination: File,
		sha256Checksum: String,
		expectedSize: Long,
		logger: Logger,
		connectionFactory: (URL) -> HttpURLConnection,
	) {
		if (url.protocol != "https") {
			throw GradleException("Refusing non-HTTPS asset URL: $url")
		}
		if (!sha256Pattern.matches(sha256Checksum)) {
			throw GradleException("Expected SHA-256 must be 64 lowercase hexadecimal characters")
		}
		if (expectedSize <= 0L) {
			throw GradleException("Expected asset size must be positive")
		}

		val absoluteDestination = destination.absoluteFile
		val parent =
			absoluteDestination.parentFile
				?: throw GradleException("Asset destination has no parent: $absoluteDestination")
		if (!parent.exists() && !parent.mkdirs()) {
			throw GradleException("Unable to create asset directory: $parent")
		}
		if (!parent.isDirectory) {
			throw GradleException("Asset destination parent is not a directory: $parent")
		}

		val partial = File(parent, "${absoluteDestination.name}.part")
		val lockFile = File(parent, "${absoluteDestination.name}.lock")
		if (Files.isSymbolicLink(lockFile.toPath())) {
			throw GradleException("Asset lock must not be a symbolic link: $lockFile")
		}
		RandomAccessFile(lockFile, "rw").channel.use { channel ->
			channel.lock().use {
				downloadLocked(
					url,
					absoluteDestination,
					partial,
					sha256Checksum,
					expectedSize,
					logger,
					connectionFactory,
				)
			}
		}
	}

	private fun downloadLocked(
		url: URL,
		destination: File,
		partial: File,
		sha256Checksum: String,
		expectedSize: Long,
		logger: Logger,
		connectionFactory: (URL) -> HttpURLConnection,
	) {
		rejectUnsafeFile(destination, "destination")
		rejectUnsafeFile(partial, "partial download")

		if (isExpectedFile(destination, expectedSize, sha256Checksum)) {
			logger.info("Verified cached asset {}.", destination)
			return
		}

		if (partial.exists() && partial.length() > expectedSize) {
			Files.delete(partial.toPath())
		}
		if (partial.length() == expectedSize) {
			if (isExpectedFile(partial, expectedSize, sha256Checksum)) {
				moveAtomically(partial, destination)
				return
			}
			Files.delete(partial.toPath())
		}

		logger.quiet("Downloading {} to resumable file {}.", url, partial)
		transfer(url, partial, expectedSize, connectionFactory)

		if (partial.length() != expectedSize) {
			throw GradleException(
				"Unable to download $url. Size mismatch. " +
					"expected=$expectedSize actual=${partial.length()}.",
			)
		}

		val actualChecksum = MessageDigest.getInstance("SHA-256").sha256(partial)
		if (actualChecksum != sha256Checksum) {
			Files.deleteIfExists(partial.toPath())
			logger.error(
				"Checksum mismatch. expected={} actual={}.",
				sha256Checksum,
				actualChecksum,
			)
			throw GradleException(
				"Unable to download $url. Checksum mismatch. " +
					"expected=$sha256Checksum actual=$actualChecksum.",
			)
		}

		moveAtomically(partial, destination)
	}

	private fun transfer(
		url: URL,
		partial: File,
		expectedSize: Long,
		connectionFactory: (URL) -> HttpURLConnection,
	) {
		var offset = partial.length()
		val connection = connectionFactory(url)
		try {
			connection.instanceFollowRedirects = true
			connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
			connection.readTimeout = READ_TIMEOUT_MILLIS
			connection.setRequestProperty("Accept-Encoding", "identity")
			if (offset > 0L) {
				connection.setRequestProperty("Range", "bytes=$offset-")
			}

			val status = connection.responseCode
			if (connection.url.protocol != "https") {
				throw GradleException("Asset URL redirected away from HTTPS: ${connection.url}")
			}

			val append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL
			when {
				append -> validateContentRange(connection, offset, expectedSize)
				status == HttpURLConnection.HTTP_OK -> offset = 0L
				else -> throw GradleException("Unable to download $url. HTTP status $status.")
			}

			val declaredLength = connection.contentLengthLong
			val maximumResponseSize = expectedSize - offset
			if (declaredLength > maximumResponseSize) {
				throw GradleException(
					"Unable to download $url. Response exceeds expected size " +
						"($declaredLength > $maximumResponseSize).",
				)
			}

			connection.inputStream.use { input ->
				FileOutputStream(partial, append).use { output ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var total = offset
					while (true) {
						val count = input.read(buffer)
						if (count < 0) break
						total += count
						if (total > expectedSize) {
							throw GradleException(
								"Unable to download $url. Received more than $expectedSize bytes.",
							)
						}
						output.write(buffer, 0, count)
					}
					output.fd.sync()
				}
			}
		} finally {
			connection.disconnect()
		}
	}

	private fun validateContentRange(
		connection: HttpURLConnection,
		offset: Long,
		expectedSize: Long,
	) {
		val value =
			connection.getHeaderField("Content-Range")
				?: throw GradleException("Resume response omitted Content-Range")
		val match =
			contentRangePattern.matchEntire(value)
				?: throw GradleException("Invalid Content-Range: $value")
		val start = match.groupValues[1].toLong()
		val end = match.groupValues[2].toLong()
		val total = match.groupValues[3].toLongOrNull()
		if (start != offset || end < start || total != expectedSize) {
			throw GradleException(
				"Unexpected Content-Range: $value; expected bytes $offset-*/$expectedSize",
			)
		}
	}

	private fun rejectUnsafeFile(
		file: File,
		description: String,
	) {
		if (Files.isSymbolicLink(file.toPath())) {
			throw GradleException("Asset $description must not be a symbolic link: $file")
		}
		if (file.exists() && !file.isFile) {
			throw GradleException("Asset $description must be a regular file: $file")
		}
	}

	private fun isExpectedFile(
		file: File,
		expectedSize: Long,
		checksum: String,
	): Boolean =
		file.isFile &&
			file.length() == expectedSize &&
			MessageDigest.getInstance("SHA-256").sha256(file) == checksum

	private fun moveAtomically(
		partial: File,
		destination: File,
	) {
		try {
			Files.move(
				partial.toPath(),
				destination.toPath(),
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING,
			)
		} catch (error: Exception) {
			throw GradleException(
				"Unable to atomically replace asset $destination; verified data remains at $partial",
				error,
			)
		}
	}
}
