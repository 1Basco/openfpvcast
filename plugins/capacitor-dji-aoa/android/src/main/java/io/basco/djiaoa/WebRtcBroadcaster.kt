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
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
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
/**
 * How the broadcast trades latency for picture quality.
 *
 * The ceiling is the goggles' own downlink (nominally 1080p60) — nothing here can add detail
 * the link never sent. What these profiles buy back is the loss introduced by *our* re-encode:
 * libwebrtc's congestion control is tuned for the open internet, so on a quiet LAN it settles
 * far below what the link can carry and the quality scaler drops resolution rather than let
 * QP rise. Giving the encoder a high ceiling, a floor, and permission to keep the resolution
 * recovers most of that — paid for with the receiver-side buffer in [playoutDelayMs].
 */
enum class BroadcastQuality(
	/** Encoder ceiling. `null` leaves libwebrtc's own (conservative) default in place. */
	val maxBitrateBps: Int?,
	/** Encoder floor — keeps BWE from parking at a low rate on an idle LAN. */
	val minBitrateBps: Int?,
	val degradation: RtpParameters.DegradationPreference,
	/**
	 * Tag the source as screencast. Sounds wrong for FPV video, but this is the documented
	 * lever that disables libwebrtc's QP-driven *quality scaler* — the thing that silently
	 * halves resolution under load. We would rather drop frames than pixels.
	 */
	val disableQualityScaler: Boolean,
	/** H.264 High Profile instead of Constrained Baseline — better quality at equal bitrate. */
	val highProfile: Boolean,
	/** Jitter buffer the viewer page should hold. The latency the user is paying. */
	val playoutDelayMs: Int
) {
	/** The original behaviour: minimal buffer, libwebrtc adapts however it likes. */
	LOW_LATENCY(null, null, RtpParameters.DegradationPreference.BALANCED, false, false, 0),

	/** Enough headroom to stop the visible mush, still well under a quarter second. */
	BALANCED(8_000_000, 2_000_000, RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION, false, true, 200),

	/** As close to the source as a re-encode gets, at roughly a second behind. */
	FIDELITY(20_000_000, 6_000_000, RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION, true, true, 1000);

	/** The kebab-case name this profile carries across the plugin bridge. */
	val jsName: String get() = name.lowercase().replace('_', '-')

	companion object {
		/** Parse the JS-facing kebab-case name; unknown/absent falls back to [BALANCED]. */
		fun from(raw: String?): BroadcastQuality = when (raw) {
			"low-latency" -> LOW_LATENCY
			"fidelity" -> FIDELITY
			else -> BALANCED
		}
	}
}

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
	/** Active profile — fixed for the lifetime of a run; [start] rebuilds the graph to change it. */
	@Volatile var quality = BroadcastQuality.BALANCED; private set
	val viewers: Int get() = peers.size

	/** Fan-out sink — register with [VideoFanout] while running. */
	val sink: (ByteArray) -> Unit = { bytes -> capturer?.sink?.invoke(bytes) }

	/**
	 * Start the broadcast. Returns the viewer URL. Throws if the server can't bind.
	 *
	 * [quality] is baked into the encoder factory and the video source, so it cannot be changed
	 * on a live broadcast — callers switch profiles by stopping and starting again.
	 */
	@Synchronized
	fun start(port: Int = DEFAULT_PORT, quality: BroadcastQuality = BroadcastQuality.BALANCED): String {
		if (running) return url!!
		this.quality = quality

		PeerConnectionFactory.initialize(
			PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
				.createInitializationOptions()
		)
		val egl = EglBase.create()
		eglBase = egl
		val f = PeerConnectionFactory.builder()
			.setVideoEncoderFactory(
				DefaultVideoEncoderFactory(egl.eglBaseContext, true, quality.highProfile)
			)
			.setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
			.createPeerConnectionFactory()
		factory = f

		// `isScreencast` is the only handle on the quality scaler — see [BroadcastQuality].
		val source = f.createVideoSource(quality.disableQualityScaler)
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

		val sender = pc.addTrack(track, listOf("fpv-stream"))
		// Codec preference has to be expressed before the offer is built.
		preferH264(pc)
		peers[socket] = pc
		emitState()

		pc.createOffer(object : SdpObserverAdapter("createOffer") {
			override fun onCreateSuccess(desc: SessionDescription) {
				exec.execute {
					pc.setLocalDescription(object : SdpObserverAdapter("setLocal") {}, desc)
					// The sender's encodings only exist once the track is attached; setting them
					// after setLocalDescription is the point where libwebrtc has populated them.
					applyEncoding(sender)
					val msg = JSONObject()
						.put("type", "offer")
						.put("sdp", desc.description)
						// The viewer sizes its jitter buffer from this — the latency half of the
						// profile lives on the receiving end, where the buffering actually happens.
						.put("playoutDelayMs", quality.playoutDelayMs)
					socket.sendSafe(msg.toString())
				}
			}
		}, MediaConstraints())
	}

	/**
	 * Reorder the video transceiver's codec list so hardware H.264 is picked over VP8.
	 *
	 * libwebrtc offers VP8 first by default, and phones rarely have a VP8 hardware encoder — so
	 * the default negotiation can land on a *software* encode of a 1080p60 stream, which is both
	 * the worst picture and the highest CPU cost available. The full list is preserved and only
	 * reordered: dropping entries would take RTX (retransmission) and FEC with it.
	 *
	 * A no-op when the device advertises no H.264 encoder, leaving the default order intact.
	 */
	private fun preferH264(pc: PeerConnection) {
		val f = factory ?: return
		val transceiver = pc.transceivers.firstOrNull {
			it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
		} ?: return
		val codecs = try {
			f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
		} catch (e: Throwable) {
			Log.w(TAG, "sender capabilities unavailable — keeping default codec order", e)
			return
		}
		if (codecs.none { it.isH264() }) {
			Log.i(TAG, "no H.264 encoder advertised — keeping default codec order")
			return
		}
		// Rank: High Profile H.264, then any other H.264, then everything else untouched.
		val ranked = codecs.sortedBy {
			when {
				it.isH264() && it.isHighProfile() -> 0
				it.isH264() -> 1
				else -> 2
			}
		}
		try {
			transceiver.setCodecPreferences(ranked)
			Log.i(TAG, "codec order: ${ranked.take(3).joinToString { it.describe() }}")
		} catch (e: Throwable) {
			Log.w(TAG, "setCodecPreferences failed — keeping default order", e)
		}
	}

	/** Push the profile's bitrate window and degradation policy onto a live sender. */
	private fun applyEncoding(sender: RtpSender?) {
		val s = sender ?: return
		val params = try { s.parameters } catch (e: Throwable) {
			Log.w(TAG, "sender parameters unavailable", e); return
		} ?: return
		if (params.encodings.isEmpty()) {
			Log.w(TAG, "sender has no encodings — bitrate window not applied")
			return
		}
		params.degradationPreference = quality.degradation
		for (enc in params.encodings) {
			enc.maxBitrateBps = quality.maxBitrateBps
			enc.minBitrateBps = quality.minBitrateBps
		}
		val ok = try { s.setParameters(params) } catch (e: Throwable) {
			Log.w(TAG, "setParameters threw", e); false
		}
		Log.i(
			TAG,
			"profile ${quality.name}: max=${quality.maxBitrateBps} min=${quality.minBitrateBps} " +
				"degradation=${quality.degradation} applied=$ok"
		)
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

	private fun RtpCapabilities.CodecCapability.isH264() = name.equals("H264", ignoreCase = true)

	/**
	 * High Profile is `profile-level-id` `64xxxx`; Constrained Baseline is `42xxxx`. Absent
	 * parameters mean the codec never declared a profile, so treat it as not-High.
	 */
	private fun RtpCapabilities.CodecCapability.isHighProfile(): Boolean =
		parameters["profile-level-id"]?.startsWith("64") == true

	private fun RtpCapabilities.CodecCapability.describe(): String =
		if (isH264()) "$name/${parameters["profile-level-id"] ?: "?"}" else name

	companion object {
		private const val TAG = "WebRtcBroadcaster"
		private const val VIEWER_ASSET = "webrtc-viewer.html"
		const val DEFAULT_PORT = 8888
		/** Each viewer costs a dedicated HW encoder — cheap SoCs exhaust them fast. */
		const val MAX_VIEWERS = 3
	}
}
