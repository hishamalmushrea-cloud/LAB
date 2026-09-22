package com.itsaky.androidide.localWebServer

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device-level proof that a different installed UID gets no diagnostic capability. */
@RunWith(AndroidJUnit4::class)
class ExternalAppLocalWebServerTest {
	@Test
	fun separatelyPackagedClientCannotReadProjectDiagnosticsWithoutSession() {
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val clientContext = instrumentation.context
		val targetContext = instrumentation.targetContext
		assertThat(clientContext.packageName).isNotEqualTo(targetContext.packageName)

		val databaseFile = File(targetContext.cacheDir, "external-client-documentation.db")
		SQLiteDatabase.openOrCreateDatabase(databaseFile, null).close()
		val port = ServerSocket(0).use { it.localPort }
		val server =
			WebServer(
				ServerConfig(
					port = port,
					databasePath = databaseFile.absolutePath,
					sessionToken = "instrumentation-session-0123456789abcdef",
					diagnosticsEnabled = true,
					debugDatabasePath = File(targetContext.cacheDir, "missing-debug.db").absolutePath,
					debugEnablePath = File(targetContext.cacheDir, "missing-debug-flag").absolutePath,
					experimentsEnablePath = File(targetContext.cacheDir, "missing-experiments-flag").absolutePath,
					clearCacheEnablePath = File(targetContext.cacheDir, "missing-cache-flag").absolutePath,
					projectDatabasePath = File(targetContext.filesDir, "private-recent-projects.db").absolutePath,
				),
			)
		val thread = Thread(server::start, "external-client-web-server-test").apply { isDaemon = true }
		thread.start()

		try {
			awaitPortBound(port)
			val result = requestFromExternalProcess(clientContext, port, "/pr/pr")

			assertThat(result.getInt(ExternalSocketProbeService.RESULT_UID))
				.isNotEqualTo(targetContext.applicationInfo.uid)
			assertThat(result.getString(ExternalSocketProbeService.RESULT_PACKAGE)).isEqualTo(clientContext.packageName)
			val response = checkNotNull(result.getString(ExternalSocketProbeService.RESULT_RESPONSE))
			assertThat(response).startsWith("HTTP/1.1 404")
			assertThat(response).doesNotContain(targetContext.filesDir.absolutePath)
		} finally {
			server.stop()
			thread.join(2_000)
			databaseFile.delete()
		}
	}

	private fun requestFromExternalProcess(
		clientContext: Context,
		port: Int,
		path: String,
	): Bundle {
		val received = AtomicReference<Bundle>()
		val completed = CountDownLatch(1)
		val receiver =
			object : ResultReceiver(Handler(Looper.getMainLooper())) {
				override fun onReceiveResult(
					resultCode: Int,
					resultData: Bundle?,
				) {
					if (resultCode == ExternalSocketProbeService.RESULT_OK && resultData != null) {
						received.set(resultData)
					}
					completed.countDown()
				}
			}
		val intent =
			Intent(clientContext, ExternalSocketProbeService::class.java)
				.putExtra(ExternalSocketProbeService.EXTRA_PORT, port)
				.putExtra(ExternalSocketProbeService.EXTRA_PATH, path)
				.putExtra(ExternalSocketProbeService.EXTRA_RECEIVER, receiver)
		val connection =
			object : ServiceConnection {
				override fun onServiceConnected(
					name: ComponentName?,
					service: IBinder?,
				) = Unit

				override fun onServiceDisconnected(name: ComponentName?) = Unit
			}
		check(clientContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
			"Could not bind the external socket probe"
		}
		try {
			check(completed.await(10, TimeUnit.SECONDS)) { "External socket probe timed out" }
			return checkNotNull(received.get()) { "External socket probe failed" }
		} finally {
			clientContext.unbindService(connection)
		}
	}

	private fun awaitPortBound(port: Int) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (System.nanoTime() < deadline) {
			try {
				Socket().use { it.connect(InetSocketAddress("localhost", port), 200) }
				return
			} catch (_: Exception) {
				Thread.sleep(10)
			}
		}
		error("WebServer did not bind port $port in time")
	}
}

/** Runs in the test APK's own process and UID; it never receives the server session token. */
class ExternalSocketProbeService : Service() {
	override fun onBind(intent: Intent?): IBinder {
		val request = requireNotNull(intent)
		val receiver =
			requireNotNull(
				IntentCompat.getParcelableExtra(request, EXTRA_RECEIVER, ResultReceiver::class.java),
			)
		val port = request.getIntExtra(EXTRA_PORT, -1)
		val path = requireNotNull(request.getStringExtra(EXTRA_PATH))
		Thread {
			val result =
				runCatching {
					Bundle().apply {
						putInt(RESULT_UID, Process.myUid())
						putString(RESULT_PACKAGE, packageName)
						putString(RESULT_RESPONSE, rawGetWithoutSession(port, path))
					}
				}
			result.onSuccess { receiver.send(RESULT_OK, it) }.onFailure { receiver.send(RESULT_ERROR, Bundle()) }
		}.start()
		return Binder()
	}

	private fun rawGetWithoutSession(
		port: Int,
		path: String,
	): String =
		Socket().use { socket ->
			socket.connect(InetSocketAddress("localhost", port), 2_000)
			socket.soTimeout = 2_000
			socket.getOutputStream().apply {
				write("GET $path HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
				flush()
			}
			socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
		}

	companion object {
		const val EXTRA_PORT = "port"
		const val EXTRA_PATH = "path"
		const val EXTRA_RECEIVER = "receiver"
		const val RESULT_UID = "uid"
		const val RESULT_PACKAGE = "package"
		const val RESULT_RESPONSE = "response"
		const val RESULT_OK = 1
		const val RESULT_ERROR = 2
	}
}
