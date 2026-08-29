package io.basco.djiaoa

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 Annex-B → MediaCodec → Surface decoder for the live preview.
 *
 * The DJI N3 streams the goggles' decoded composite at LogicLink port 0x574A as a continuous
 * Annex-B byte stream. We accumulate the bytes, walk start codes to extract NAL units, save
 * the first SPS/PPS we see to configure the codec, then queue each subsequent NAL.
 *
 * Output goes straight to the passed `Surface` — typically a `SurfaceView`'s holder. This
 * bypasses the WebView/JS bridge entirely; latency is whatever the hardware decoder gives us
 * (typically 50–100 ms on a modern phone).
 *
 * One-shot: create, `start()`, `feed(bytes)`, `close()`. Not reusable across surfaces.
 */
class VideoDecoder(private val surface: Surface) {

	private var codec: MediaCodec? = null
	private val byteQueue = LinkedBlockingQueue<ByteArray>()
	private val running = AtomicBoolean(false)
	private var thread: Thread? = null

	/** Coded picture size parsed from the stream's SPS (0 until the first SPS arrives). */
	@Volatile var videoWidth: Int = 0; private set
	@Volatile var videoHeight: Int = 0; private set

	/** Invoked (on the decoder thread) whenever the SPS reports a new picture size. */
	@Volatile var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

	fun start() {
		if (!running.compareAndSet(false, true)) return
		thread = Thread(::loop, "VideoDecoder").also {
			it.isDaemon = true
			it.start()
		}
	}

	/** Push raw H.264 Annex-B bytes (a chunk of the LogicLink video port stream). */
	fun feed(data: ByteArray) {
		if (!running.get() || data.isEmpty()) return
		byteQueue.offer(data)
	}

	fun close() {
		if (!running.compareAndSet(true, false)) return
		thread?.interrupt()
		thread = null
		try { codec?.stop() } catch (_: Throwable) {}
		try { codec?.release() } catch (_: Throwable) {}
		codec = null
	}

	private fun loop() {
		val pending = ByteArrayOutputStream()
		var sps: ByteArray? = null
		var pps: ByteArray? = null
		var codecReady = false
		var sentKeyframe = false

		while (running.get()) {
			val chunk = try {
				byteQueue.poll(50, TimeUnit.MILLISECONDS)
			} catch (_: InterruptedException) { null }
			if (chunk != null) pending.write(chunk)

			val bytes = pending.toByteArray()
			val (nals, leftover) = extractNalUnits(bytes)
			pending.reset()
			pending.write(leftover)

			for (nal in nals) {
				if (nal.isEmpty()) continue
				val type = nal[0].toInt() and 0x1F
				when (type) {
					NAL_SPS -> {
						sps = nal
						H264Sps.parse(nal)?.let {
							if (it.width != videoWidth || it.height != videoHeight) {
								videoWidth = it.width
								videoHeight = it.height
								try { onVideoSize?.invoke(it.width, it.height) } catch (_: Throwable) {}
							}
						}
					}
					NAL_PPS -> pps = nal
					NAL_IDR, NAL_SLICE -> {
						if (!codecReady && sps != null && pps != null) {
							codecReady = initCodec(sps!!, pps!!)
						}
						if (codecReady) {
							// Wait for an IDR before feeding regular slices, otherwise the
							// decoder emits green/garbled frames until the next keyframe.
							if (type == NAL_IDR) sentKeyframe = true
							if (sentKeyframe) queueNal(nal, type == NAL_IDR)
						}
					}
					// Other NAL types (SEI=6, AUD=9, etc.) — fine to forward, sometimes required.
					else -> if (codecReady && sentKeyframe) queueNal(nal, false)
				}
			}

			// Always drain any decoded output so we don't pile up.
			drainOutput()
		}
	}

	private fun initCodec(sps: ByteArray, pps: ByteArray): Boolean {
		try {
			// Dummy dimensions — the real values come from the SPS we pass as csd-0. The
			// decoder fires an INFO_OUTPUT_FORMAT_CHANGED event as soon as it parses them.
			val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
			format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
			format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
			}
			val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
			c.configure(format, surface, null, 0)
			c.start()
			codec = c
			Log.i(TAG, "Decoder configured (SPS ${sps.size}B, PPS ${pps.size}B)")
			return true
		} catch (e: Throwable) {
			Log.e(TAG, "initCodec failed", e)
			return false
		}
	}

	private fun queueNal(nal: ByteArray, isKey: Boolean) {
		val c = codec ?: return
		val idx = try { c.dequeueInputBuffer(10_000) } catch (e: Throwable) { -1 }
		if (idx < 0) return
		val buf = try { c.getInputBuffer(idx) } catch (e: Throwable) { null } ?: return
		buf.clear()
		buf.put(START_CODE)
		buf.put(nal)
		val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
		try {
			c.queueInputBuffer(idx, 0, START_CODE.size + nal.size, System.nanoTime() / 1000, flags)
		} catch (_: Throwable) {
			// Codec may have errored out — let close+reinit handle it.
		}
	}

	private fun drainOutput() {
		val c = codec ?: return
		val info = MediaCodec.BufferInfo()
		while (true) {
			val outIdx = try { c.dequeueOutputBuffer(info, 0) } catch (_: Throwable) { return }
			if (outIdx < 0) return
			try { c.releaseOutputBuffer(outIdx, true) } catch (_: Throwable) { return }
		}
	}

	companion object {
		private const val TAG = "VideoDecoder"
		private val START_CODE = byteArrayOf(0, 0, 0, 1)

		private const val NAL_SLICE = 1
		private const val NAL_IDR = 5
		private const val NAL_SPS = 7
		private const val NAL_PPS = 8

		private fun withStartCode(nal: ByteArray): ByteArray {
			val out = ByteArray(4 + nal.size)
			out[3] = 1
			System.arraycopy(nal, 0, out, 4, nal.size)
			return out
		}

		/**
		 * Split an Annex-B stream into NAL units (sans start codes). Returns the list of
		 * complete NAL units plus the unconsumed leftover (everything from the last start
		 * code onwards — possibly incomplete).
		 */
		internal fun extractNalUnits(data: ByteArray): Pair<List<ByteArray>, ByteArray> {
			val nals = mutableListOf<ByteArray>()
			val starts = mutableListOf<IntPair>()
			var i = 0
			while (i < data.size - 2) {
				if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
					// 0 0 0 1
					if (i < data.size - 3 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
						starts.add(IntPair(i, i + 4))
						i += 4
						continue
					}
					// 0 0 1
					if (data[i + 2] == 1.toByte()) {
						starts.add(IntPair(i, i + 3))
						i += 3
						continue
					}
				}
				i++
			}
			if (starts.isEmpty()) return Pair(nals, data)
			for (idx in 0 until starts.size - 1) {
				val from = starts[idx].after
				val to = starts[idx + 1].at
				if (to > from) nals.add(data.copyOfRange(from, to))
			}
			val lastStartAt = starts.last().at
			val leftover = data.copyOfRange(lastStartAt, data.size)
			return Pair(nals, leftover)
		}

		private data class IntPair(val at: Int, val after: Int)
	}
}
