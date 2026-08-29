package io.basco.djiaoa

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Embedded HTTP + WebSocket server for the LAN broadcast.
 *
 * - `GET /` (any non-WS request) → the self-contained viewer page (`webrtc-viewer.html` asset).
 * - WebSocket upgrade (the viewer opens `ws://<phone>:<port>/ws`) → one signaling channel per
 *   viewer; SDP/ICE JSON messages are relayed to [Handler] (the [WebRtcBroadcaster]).
 *
 * NanoHTTPD's read timeout would drop idle sockets, so each WebSocket is pinged every 2 s.
 */
class SignalingServer(
	port: Int,
	private val viewerHtml: String,
	private val handler: Handler
) : NanoWSD(port) {

	interface Handler {
		/** A viewer socket opened. Return false to reject it (e.g. viewer cap reached). */
		fun onViewerOpen(socket: ViewerSocket): Boolean
		fun onViewerMessage(socket: ViewerSocket, text: String)
		fun onViewerClosed(socket: ViewerSocket)
	}

	private val pinger: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
		Thread(r, "SignalingPinger").apply { isDaemon = true }
	}

	override fun openWebSocket(handshake: IHTTPSession): WebSocket = ViewerSocket(handshake)

	override fun serveHttp(session: IHTTPSession): Response {
		return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", viewerHtml)
	}

	fun startServer() {
		// timeout=0 disables NanoHTTPD's socket read timeout; liveness comes from WS pings.
		start(0, true)
	}

	fun stopServer() {
		pinger.shutdownNow()
		stop()
	}

	inner class ViewerSocket(handshake: IHTTPSession) : WebSocket(handshake) {

		private var pingTask: ScheduledFuture<*>? = null

		override fun onOpen() {
			pingTask = pinger.scheduleWithFixedDelay({
				try { ping(PING_PAYLOAD) } catch (_: Throwable) { closeQuietly() }
			}, 2, 2, TimeUnit.SECONDS)
			if (!handler.onViewerOpen(this)) {
				sendSafe("""{"type":"full"}""")
				closeQuietly()
			}
		}

		override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
			pingTask?.cancel(false)
			pingTask = null
			handler.onViewerClosed(this)
		}

		override fun onMessage(message: WebSocketFrame) {
			val text = message.textPayload ?: return
			handler.onViewerMessage(this, text)
		}

		override fun onPong(pong: WebSocketFrame) {}

		override fun onException(exception: IOException) {
			Log.w(TAG, "viewer socket error: ${exception.message}")
		}

		fun sendSafe(text: String) {
			try { send(text) } catch (e: Throwable) {
				Log.w(TAG, "ws send failed: ${e.message}")
			}
		}

		fun closeQuietly() {
			try { close(WebSocketFrame.CloseCode.NormalClosure, "bye", false) } catch (_: Throwable) {}
		}
	}

	companion object {
		private const val TAG = "SignalingServer"
		private val PING_PAYLOAD = byteArrayOf(0x70)
	}
}
