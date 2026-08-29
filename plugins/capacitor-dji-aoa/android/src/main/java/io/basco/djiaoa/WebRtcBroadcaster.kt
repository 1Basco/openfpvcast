package io.basco.djiaoa

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * True WebRTC broadcast of the goggles feed over the local network.
 *
 * The phone is the offerer/sender. One [VideoTrack] (fed by [WebRtcDecodeCapturer] from the
 * video fan-out) is shared across one [PeerConnection] per viewer; each PC gets its own HW
 * encoder, so viewers are capped ([MAX_VIEWERS]) to respect cheap SoCs' codec budget.
 *
 * LAN only: no ICE servers, host candidates only. Signaling + the viewer page are served by
 * the embedded [SignalingServer]; everything runs inside the CastService's foreground context.
 *
 * All SDP/ICE work is marshalled onto a single executor so libwebrtc and WS callbacks never
 * race each other.
 */
class WebRtcBroadcaster(
	private val context: Context,
	/** State callback: (running, url, viewerCount) — emitted on every change. */
	private val onState: (Boolean, String?, Int) -> Unit
) : SignalingServer.Handler {

	private val exec = Executors.newSingleThreadExecutor { r ->
		Thread(r, "WebRtcSignaling").apply { isDaemon = true }
	}

	private var eglBase: EglBase? = null
	private var factory: PeerConnectionFactory? = null
	private var videoSource: VideoSource? = null
	private var videoTrack: VideoTrack? = null
	private var capturer: WebRtcDecodeCapturer? = null
	private var server: SignalingServer? = null

	private val peers = ConcurrentHashMap<SignalingServer.ViewerSocket, PeerConnection>()

	@Volatile var url: String? = null; private set
	@Volatile var running = false; private set
	val viewers: Int get() = peers.size

	/** Fan-out sink — register with [VideoFanout] while running. */
	val sink: (ByteArray) -> Unit = { bytes -> capturer?.sink?.invoke(bytes) }

	/** Start the broadcast. Returns the viewer URL. Throws if the server can't bind. */
	@Synchronized
	fun start(port: Int = DEFAULT_PORT): String {
		if (running) return url!!

		PeerConnectionFactory.initialize(
			PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
				.createInitializationOptions()
		)
		val egl = EglBase.create()
		eglBase = egl
		val f = PeerConnectionFactory.builder()
			.setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, false))
			.setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
			.createPeerConnectionFactory()
		factory = f

		val source = f.createVideoSource(false)
		videoSource = source
		videoTrack = f.createVideoTrack("fpv-video", source)
		capturer = WebRtcDecodeCapturer(egl.eglBaseContext, source).also { it.start() }

		val html = context.assets.open(VIEWER_ASSET).bufferedReader().use { it.readText() }
		val srv = SignalingServer(port, html, this)
		try {
			srv.startServer()
		} catch (e: Throwable) {
			teardown()
			throw IllegalStateException("signaling server failed to bind :$port — ${e.message}", e)
		}
		server = srv

		val ip = pickLanIp() ?: run {
			teardown()
			throw IllegalStateException("no LAN IPv4 address — is WiFi/hotspot on?")
		}
		url = "http://$ip:$port/"
		running = true
		emitState()
		Log.i(TAG, "broadcast up at $url")
		return url!!
	}

	@Synchronized
	fun stop() {
		if (!running && server == null) return
		running = false
		teardown()
		emitState()
	}

	private fun teardown() {
		for ((ws, pc) in peers) {
			try { pc.close() } catch (_: Throwable) {}
			ws.closeQuietly()
		}
		peers.clear()
		try { server?.stopServer() } catch (_: Throwable) {}
		server = null
		capturer?.stop()
		capturer = null
		videoTrack?.dispose()
		videoTrack = null
		// VideoSource is owned by the track/factory graph; dispose after the track.
		try { videoSource?.dispose() } catch (_: Throwable) {}
		videoSource = null
		try { factory?.dispose() } catch (_: Throwable) {}
		factory = null
		try { eglBase?.release() } catch (_: Throwable) {}
		eglBase = null
		url = null
	}

	private fun emitState() {
		try { onState(running, url, peers.size) } catch (_: Throwable) {}
	}

	// SignalingServer.Handler callbacks land on the WS thread; hop onto the signaling executor.

	override fun onViewerOpen(socket: SignalingServer.ViewerSocket): Boolean {
		if (!running) return false
		if (peers.size >= MAX_VIEWERS) {
			Log.i(TAG, "viewer rejected — cap of $MAX_VIEWERS reached")
			return false
		}
		exec.execute { createPeer(socket) }
		return true
	}

	override fun onViewerMessage(socket: SignalingServer.ViewerSocket, text: String) {
		exec.execute { handleMessage(socket, text) }
	}

	override fun onViewerClosed(socket: SignalingServer.ViewerSocket) {
		exec.execute {
			peers.remove(socket)?.let { pc ->
				try { pc.close() } catch (_: Throwable) {}
				Log.i(TAG, "viewer left (${peers.size} remain)")
				emitState()
			}
		}
	}

	// From here down: peer lifecycle, running on the signaling executor.

	private fun createPeer(socket: SignalingServer.ViewerSocket) {
		val f = factory ?: return
		val track = videoTrack ?: return
		val config = PeerConnection.RTCConfiguration(emptyList()).apply {
			sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
		}
		val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
			override fun onIceCandidate(candidate: IceCandidate) {
				val msg = JSONObject()
					.put("type", "ice")
					.put("candidate", candidate.sdp)
					.put("sdpMid", candidate.sdpMid)
					.put("sdpMLineIndex", candidate.sdpMLineIndex)
				socket.sendSafe(msg.toString())
			}

			override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
				if (state == PeerConnection.IceConnectionState.FAILED ||
					state == PeerConnection.IceConnectionState.CLOSED
				) {
					exec.execute {
						peers.remove(socket)?.let {
							try { it.close() } catch (_: Throwable) {}
							socket.closeQuietly()
							emitState()
						}
					}
				}
			}

			override fun onSignalingChange(state: PeerConnection.SignalingState) {}
			override fun onIceConnectionReceivingChange(receiving: Boolean) {}
			override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
			override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
			override fun onAddStream(stream: MediaStream) {}
			override fun onRemoveStream(stream: MediaStream) {}
			override fun onDataChannel(channel: org.webrtc.DataChannel) {}
			override fun onRenegotiationNeeded() {}
		}) ?: run {
			Log.e(TAG, "createPeerConnection returned null")
			socket.closeQuietly()
			return
		}

		pc.addTrack(track, listOf("fpv-stream"))
		peers[socket] = pc
		emitState()

		pc.createOffer(object : SdpObserverAdapter("createOffer") {
			override fun onCreateSuccess(desc: SessionDescription) {
				exec.execute {
					pc.setLocalDescription(object : SdpObserverAdapter("setLocal") {}, desc)
					val msg = JSONObject().put("type", "offer").put("sdp", desc.description)
					socket.sendSafe(msg.toString())
				}
			}
		}, MediaConstraints())
	}

	private fun handleMessage(socket: SignalingServer.ViewerSocket, text: String) {
		val pc = peers[socket] ?: return
		try {
			val msg = JSONObject(text)
			when (msg.optString("type")) {
				"answer" -> {
					val desc = SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp"))
					pc.setRemoteDescription(object : SdpObserverAdapter("setRemote") {}, desc)
				}
				"ice" -> {
					val cand = IceCandidate(
						msg.optString("sdpMid", null),
						msg.optInt("sdpMLineIndex", 0),
						msg.getString("candidate")
					)
					pc.addIceCandidate(cand)
				}
			}
		} catch (e: Throwable) {
			Log.w(TAG, "bad signaling message: ${e.message}")
		}
	}

	/**
	 * Pick the LAN IPv4 the viewer should hit. Prefers WiFi-client (`wlan*`) and hotspot
	 * (`ap*` / `swlan*` / `192.168.43.x`) interfaces over anything else that's up.
	 */
	private fun pickLanIp(): String? {
		val candidates = mutableListOf<Pair<String, String>>() // (ifaceName, ip)
		try {
			for (nif in NetworkInterface.getNetworkInterfaces()) {
				if (!nif.isUp || nif.isLoopback) continue
				for (addr in nif.inetAddresses) {
					if (addr.isLoopbackAddress) continue
					val ip = addr.hostAddress ?: continue
					if (!ip.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) continue
					candidates.add(nif.name to ip)
				}
			}
		} catch (e: Throwable) {
			Log.w(TAG, "interface enumeration failed: ${e.message}")
		}
		val preferred = candidates.firstOrNull { (name, _) ->
			name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
		}
		return (preferred ?: candidates.firstOrNull())?.second
	}

	private open class SdpObserverAdapter(private val op: String) : SdpObserver {
		override fun onCreateSuccess(desc: SessionDescription) {}
		override fun onSetSuccess() {}
		override fun onCreateFailure(error: String?) {
			Log.e(TAG, "$op create failed: $error")
		}
		override fun onSetFailure(error: String?) {
			Log.e(TAG, "$op set failed: $error")
		}
	}

	companion object {
		private const val TAG = "WebRtcBroadcaster"
		private const val VIEWER_ASSET = "webrtc-viewer.html"
		const val DEFAULT_PORT = 8888
		/** Each viewer costs a dedicated HW encoder — cheap SoCs exhaust them fast. */
		const val MAX_VIEWERS = 3
	}
}
