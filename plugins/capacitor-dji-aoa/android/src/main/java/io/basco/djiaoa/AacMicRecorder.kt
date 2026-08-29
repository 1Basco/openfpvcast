package io.basco.djiaoa

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the phone microphone as PCM16 and encodes it to AAC-LC, handing each encoded frame to
 * [onEncoded] with a presentation timestamp counted from the first sample (so audio starts at ~0,
 * aligned with the video's synthetic frame-0 PTS). Encoding runs on its own thread.
 *
 * The caller is responsible for adding the audio track to the muxer BEFORE starting it — use
 * [audioSpecificConfig] for the AAC csd-0 so the track can be added deterministically without
 * waiting for the encoder's INFO_OUTPUT_FORMAT_CHANGED.
 *
 * Requires the RECORD_AUDIO runtime permission and a running foreground service of type microphone.
 */
class AacMicRecorder(
	private val sampleRate: Int,
	private val channelCount: Int,
	private val bitRate: Int = 128_000,
	private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
	private val running = AtomicBoolean(false)
	private var thread: Thread? = null
	private var audioRecord: AudioRecord? = null
	private var codec: MediaCodec? = null

	fun start() {
		if (!running.compareAndSet(false, true)) return
		val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
		val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
		val bufSize = if (minBuf > 0) minBuf * 2 else sampleRate * channelCount * 2

		val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
			setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
			setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
			setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
		}
		val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
		enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
		enc.start()
		codec = enc

		@Suppress("MissingPermission")
		val rec = AudioRecord(
			MediaRecorder.AudioSource.MIC,
			sampleRate,
			channelMask,
			AudioFormat.ENCODING_PCM_16BIT,
			bufSize
		)
		if (rec.state != AudioRecord.STATE_INITIALIZED) {
			running.set(false)
			try { rec.release() } catch (_: Throwable) {}
			try { enc.stop(); enc.release() } catch (_: Throwable) {}
			codec = null
			throw IllegalStateException("AudioRecord failed to initialize")
		}
		rec.startRecording()
		audioRecord = rec

		thread = Thread(::loop, "AacMicRecorder").also { it.isDaemon = true; it.start() }
	}

	fun stop() {
		if (!running.compareAndSet(true, false)) return
		// Do NOT interrupt or release here — let the loop thread finish its EOS drain and release
		// AudioRecord/MediaCodec itself (in its finally). Releasing them from this thread while the
		// loop is still inside read()/dequeue*() is undefined behaviour and can crash natively.
		val t = thread
		thread = null
		try { t?.join(2000) } catch (_: InterruptedException) {}
	}

	private fun loop() {
		val rec = audioRecord
		val enc = codec
		if (rec == null || enc == null) return
		val info = MediaCodec.BufferInfo()
		val pcm = ByteArray(2048 * channelCount)
		var totalSamples = 0L      // per-channel frames read, for PTS
		val bytesPerFrame = 2 * channelCount

		try {
			while (running.get()) {
				val read = try { rec.read(pcm, 0, pcm.size) } catch (_: Throwable) { -1 }
				if (read > 0) {
					val inIdx = try { enc.dequeueInputBuffer(10_000) } catch (_: Throwable) { -1 }
					if (inIdx >= 0) {
						val inBuf = enc.getInputBuffer(inIdx)
						inBuf?.clear()
						inBuf?.put(pcm, 0, read)
						val ptsUs = totalSamples * 1_000_000L / sampleRate
						enc.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
						totalSamples += read / bytesPerFrame
					}
				}
				drainEncoder(enc, info)
			}
			val inIdx = try { enc.dequeueInputBuffer(10_000) } catch (_: Throwable) { -1 }
			if (inIdx >= 0) {
				enc.queueInputBuffer(inIdx, 0, 0, totalSamples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
			}
			drainEncoder(enc, info)
		} catch (e: Throwable) {
			Log.w(TAG, "audio loop error: ${e.message}")
		} finally {
			try { rec.stop() } catch (_: Throwable) {}
			try { rec.release() } catch (_: Throwable) {}
			try { enc.stop() } catch (_: Throwable) {}
			try { enc.release() } catch (_: Throwable) {}
			audioRecord = null
			codec = null
		}
	}

	private fun drainEncoder(enc: MediaCodec, info: MediaCodec.BufferInfo) {
		while (true) {
			val outIdx = try { enc.dequeueOutputBuffer(info, 0) } catch (_: Throwable) { return }
			if (outIdx < 0) return
			val outBuf = enc.getOutputBuffer(outIdx)
			if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
				outBuf.position(info.offset)
				outBuf.limit(info.offset + info.size)
				try { onEncoded(outBuf, info) } catch (_: Throwable) {}
			}
			try { enc.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) { return }
			if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
		}
	}

	companion object {
		private const val TAG = "AacMicRecorder"

		/** AAC-LC AudioSpecificConfig (csd-0) for the given rate/channels — used to add the muxer track. */
		fun audioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
			val freqIdx = when (sampleRate) {
				96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
				24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
				else -> 3
			}
			val objectType = 2 // AAC-LC
			val b0 = ((objectType shl 3) or (freqIdx shr 1)).toByte()
			val b1 = (((freqIdx and 1) shl 7) or (channelCount shl 3)).toByte()
			return byteArrayOf(b0, b1)
		}
	}
}
