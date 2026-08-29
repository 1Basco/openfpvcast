package io.basco.djiaoa

import android.util.Log
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource

/**
 * Bridges the goggles' H.264 byte stream into libwebrtc as camera-style frames.
 *
 * v1 uses the re-encode path: a dedicated [VideoDecoder] (separate from the preview one, both
 * fed by [VideoFanout]) renders into a SurfaceTexture owned by a [SurfaceTextureHelper]; each
 * texture frame is handed to the [VideoSource]'s capturer observer and libwebrtc HW-encodes it
 * per viewer, handling RTP/NACK/PLI/congestion. Injecting the pre-encoded H.264 directly into
 * vanilla libwebrtc is brittle — deferred.
 *
 * [sink] is the fan-out sink (copy-and-return, decode happens on the decoder's own thread).
 */
class WebRtcDecodeCapturer(
	eglContext: EglBase.Context,
	private val videoSource: VideoSource
) {
	private val helper = SurfaceTextureHelper.create("WebRtcCapture", eglContext)
	private var surface: Surface? = null
	private var decoder: VideoDecoder? = null

	/** Register this with the video fan-out. Safe before/after [start]. */
	val sink: (ByteArray) -> Unit = { bytes -> decoder?.feed(bytes) }

	fun start() {
		if (decoder != null) return
		// Default to the N3's nominal 1080p frame; the decoder corrects it from the SPS.
		helper.setTextureSize(1920, 1080)
		videoSource.capturerObserver.onCapturerStarted(true)
		helper.startListening { frame -> videoSource.capturerObserver.onFrameCaptured(frame) }
		val s = Surface(helper.surfaceTexture)
		surface = s
		decoder = VideoDecoder(s).also { dec ->
			dec.onVideoSize = { w, h ->
				Log.i(TAG, "stream size $w x $h")
				helper.setTextureSize(w, h)
			}
			dec.start()
		}
	}

	fun stop() {
		try { helper.stopListening() } catch (_: Throwable) {}
		try { videoSource.capturerObserver.onCapturerStopped() } catch (_: Throwable) {}
		decoder?.close()
		decoder = null
		surface?.release()
		surface = null
		try { helper.dispose() } catch (_: Throwable) {}
	}

	companion object {
		private const val TAG = "WebRtcCapture"
	}
}
