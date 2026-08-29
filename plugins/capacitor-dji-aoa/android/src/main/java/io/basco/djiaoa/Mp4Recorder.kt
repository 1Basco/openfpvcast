package io.basco.djiaoa

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the incoming H.264 Annex-B stream to an MP4 by REMUXING (no re-encode), optionally
 * muxing a phone-microphone AAC track.
 *
 * Pipeline: [videoSink] receives raw chunks on the USB reader thread and only enqueues them (never
 * blocks). A worker thread splits NAL units (reusing [VideoDecoder.extractNalUnits]), groups them
 * into access units (one MP4 sample per coded picture), and writes to a [MediaMuxer]. SPS/PPS
 * become the video track's csd; the muxer starts at the first IDR.
 *
 * Video PTS: the stream carries none, so each sample is stamped with the **arrival clock** of its
 * bytes (chunk `System.nanoTime()`, anchored to 0 at the first IDR, forced monotonic). This keeps
 * the video timeline equal to real time — and therefore in sync with the mic's sample-counted
 * audio track — regardless of the camera's true fps or link gaps. (A fixed-fps index would drift
 * unboundedly whenever the nominal [fps] differs from reality or chunks are lost.) USB burst
 * jitter is a few tens of ms — imperceptible next to that drift.
 */
class Mp4Recorder(
	private val context: Context,
	val recordingId: String,
	private val fps: Int,
	private val recordAudio: Boolean,
	private val onState: (state: String, error: String?, durationMs: Long?) -> Unit
) {
	/** Register THIS instance on the VideoFanout. Only enqueues — safe on the reader thread. */
	val videoSink: (ByteArray) -> Unit = { bytes ->
		if (running.get() && !queue.offer(TimedChunk(bytes, System.nanoTime()))) {
			droppedChunks++
			if (droppedChunks == 1L || droppedChunks % 100L == 0L) {
				Log.w(TAG, "recorder queue full — dropped $droppedChunks chunks (expect a glitch until next IDR)")
			}
		}
	}

	data class StopResult(val outputPath: String, val durationMs: Long, val sizeBytes: Long)

	private class TimedChunk(val bytes: ByteArray, val arrivalNs: Long)

	private val running = AtomicBoolean(false)
	@Volatile private var stopping = false
	private val queue = LinkedBlockingQueue<TimedChunk>(2048)
	private var droppedChunks = 0L
	private var worker: Thread? = null

	private var output: MediaStoreOutput? = null
	private var muxer: MediaMuxer? = null
	private val muxerLock = Object()
	@Volatile private var muxerStarted = false
	private var videoTrack = -1
	private var audioTrack = -1
	@Volatile private var hasWrittenSample = false

	private var sps: ByteArray? = null
	private var pps: ByteArray? = null
	private var frameIndex = 0L
	// Arrival-clock PTS state: arrival of the most recently dequeued chunk, the nanoTime anchor
	// set at the first muxed IDR (PTS 0), and the last PTS written (for monotonicity).
	@Volatile private var lastArrivalNs = 0L
	private var anchorNs = -1L
	private var lastPtsUs = -1L

	private val currentAu = ArrayList<ByteArray>(8)
	private var auHasVcl = false
	private var auHasIdr = false

	private var aac: AacMicRecorder? = null

	fun start() {
		if (!running.compareAndSet(false, true)) return
		onState("starting", null, null)
		worker = Thread(::loop, "Mp4Recorder").also { it.isDaemon = true; it.start() }
	}

	/** Stop and finalize. Returns null if nothing was ever written (deletes the empty file). */
	fun stop(): StopResult? {
		if (!running.compareAndSet(true, false)) return null
		stopping = true
		onState("stopping", null, null)
		// Stop the mic first so its EOS flushes (and audio writes complete) before we close the muxer.
		try { aac?.stop() } catch (_: Throwable) {}
		aac = null
		worker?.interrupt()
		try { worker?.join(2000) } catch (_: Throwable) {}
		worker = null
		// Emit the final buffered access unit. This also bootstraps the muxer for ultra-short clips
		// that only captured the first IDR; `stopping` keeps that fallback file video-only.
		try { flushAu() } catch (t: Throwable) { Log.w(TAG, "final flush: ${t.message}") }

		var result: StopResult? = null
		synchronized(muxerLock) {
			val out = output
			try {
				if (muxerStarted && hasWrittenSample) {
					try { muxer?.stop() } catch (t: Throwable) { Log.w(TAG, "muxer.stop: ${t.message}") }
				}
			} finally {
				try { muxer?.release() } catch (_: Throwable) {}
				muxer = null
			}
			if (out != null) {
				if (muxerStarted && hasWrittenSample) {
					out.finish()
					val durationMs = if (lastPtsUs > 0) lastPtsUs / 1000L else frameIndex * 1000L / fps
					result = StopResult(out.outputPath, durationMs, out.sizeBytes())
				} else {
					out.abort()
				}
			}
			output = null
			muxerStarted = false
		}
		onState("stopped", null, result?.durationMs)
		return result
	}

	private fun loop() {
		val pending = java.io.ByteArrayOutputStream()
		try {
			while (running.get()) {
				val chunk = try { queue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
				if (chunk != null) {
					lastArrivalNs = chunk.arrivalNs
					pending.write(chunk.bytes)
				}
				val bytes = pending.toByteArray()
				val (nals, leftover) = VideoDecoder.extractNalUnits(bytes)
				pending.reset()
				pending.write(leftover)
				for (nal in nals) onNal(nal)
			}
		} catch (e: Throwable) {
			Log.e(TAG, "record loop error", e)
			onState("error", e.message ?: "record loop failed", null)
		}
	}

	private fun onNal(nal: ByteArray) {
		if (nal.isEmpty()) return
		val type = nal[0].toInt() and 0x1F
		when (type) {
			NAL_SPS -> if (sps == null) sps = nal
			NAL_PPS -> if (pps == null) pps = nal
		}
		val isVcl = type in 1..5
		// A VCL NAL with first_mb_in_slice == 0 starts a new coded picture. Exp-Golomb ue(0) is a
		// single '1' bit, so first_mb==0 iff the top bit of the byte after the NAL header is set.
		val startsNewPicture = isVcl && nal.size > 1 && (nal[1].toInt() and 0x80) != 0
		if (startsNewPicture && auHasVcl) {
			flushAu()
		}
		currentAu.add(nal)
		if (isVcl) auHasVcl = true
		if (type == NAL_IDR) auHasIdr = true
	}

	private fun flushAu() {
		if (currentAu.isEmpty()) { resetAu(); return }
		val isIdr = auHasIdr
		if (!muxerStarted) {
			// Only start (and start writing) from the first IDR, once we have SPS+PPS.
			if (!isIdr || sps == null || pps == null) { resetAu(); return }
			if (!startMuxer()) { resetAu(); return }
		}
		val sample = buildAnnexB(currentAu)
		val info = MediaCodec.BufferInfo()
		val flags = if (isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
		// Arrival-clock PTS (see class doc). The AU is flushed when the next picture's first
		// NAL arrives, so lastArrivalNs is at most one frame late — constant, cancels out.
		if (anchorNs < 0) anchorNs = lastArrivalNs
		val arrivalPtsUs = (lastArrivalNs - anchorNs) / 1000L
		val ptsUs = if (arrivalPtsUs > lastPtsUs) arrivalPtsUs else lastPtsUs + 1000L
		lastPtsUs = ptsUs
		info.set(0, sample.remaining(), ptsUs, flags)
		synchronized(muxerLock) {
			if (muxerStarted) {
				try {
					muxer?.writeSampleData(videoTrack, sample, info)
					hasWrittenSample = true
				} catch (t: Throwable) {
					Log.w(TAG, "writeSampleData(video): ${t.message}")
				}
			}
		}
		frameIndex++
		resetAu()
	}

	private fun resetAu() {
		currentAu.clear()
		auHasVcl = false
		auHasIdr = false
	}

	private fun startMuxer(): Boolean {
		return try {
			val dim = H264Sps.parse(sps!!) ?: H264Sps.Dim(1920, 1080)
			val out = MediaStoreOutput.create(context, fileName())
			output = out
			val mx = MediaMuxer(out.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
			val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dim.width, dim.height).apply {
				setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps!!)))
				setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps!!)))
				setInteger(MediaFormat.KEY_FRAME_RATE, fps)
			}
			videoTrack = mx.addTrack(vf)
			// Only add the audio track if the mic actually starts: an audio track that never
			// receives a sample makes MediaMuxer.stop() fail. Skip audio during a stop-flush.
			if (recordAudio && !stopping && startMic()) {
				val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
					setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
					setByteBuffer("csd-0", ByteBuffer.wrap(AacMicRecorder.audioSpecificConfig(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)))
				}
				audioTrack = mx.addTrack(af)
			}
			mx.start()
			muxer = mx
			muxerStarted = true
			onState("recording", null, null)
			true
		} catch (t: Throwable) {
			Log.e(TAG, "startMuxer failed", t)
			onState("error", t.message ?: "muxer start failed", null)
			try { output?.abort() } catch (_: Throwable) {}
			output = null
			false
		}
	}

	/** Starts mic capture. Returns true if it initialized (so the caller can add the audio track). */
	private fun startMic(): Boolean {
		return try {
			val rec = AacMicRecorder(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS) { buf, info -> onAudioEncoded(buf, info) }
			rec.start()
			aac = rec
			true
		} catch (t: Throwable) {
			// Audio is best-effort: keep recording video even if the mic fails.
			Log.w(TAG, "mic start failed, continuing video-only: ${t.message}")
			aac = null
			false
		}
	}

	private fun onAudioEncoded(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
		if (info.size <= 0) return
		synchronized(muxerLock) {
			if (muxerStarted && audioTrack >= 0) {
				try { muxer?.writeSampleData(audioTrack, buf, info) } catch (t: Throwable) {
					Log.w(TAG, "writeSampleData(audio): ${t.message}")
				}
			}
		}
	}

	private fun buildAnnexB(nals: List<ByteArray>): ByteBuffer {
		var total = 0
		for (n in nals) total += 4 + n.size
		val out = ByteBuffer.allocate(total)
		for (n in nals) {
			out.put(START_CODE)
			out.put(n)
		}
		out.flip()
		return out
	}

	private fun fileName(): String {
		val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
		return "FPV_$ts.mp4"
	}

	companion object {
		private const val TAG = "Mp4Recorder"
		private const val NAL_IDR = 5
		private const val NAL_SPS = 7
		private const val NAL_PPS = 8
		private const val AUDIO_SAMPLE_RATE = 48_000
		private const val AUDIO_CHANNELS = 1
		private val START_CODE = byteArrayOf(0, 0, 0, 1)

		private fun withStartCode(nal: ByteArray): ByteArray {
			val out = ByteArray(4 + nal.size)
			out[3] = 1
			System.arraycopy(nal, 0, out, 4, nal.size)
			return out
		}
	}
}
