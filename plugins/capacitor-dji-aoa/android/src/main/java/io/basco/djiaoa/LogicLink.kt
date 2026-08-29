package io.basco.djiaoa

/**
 * LogicLink envelope (USB AOA transport).
 *
 * ```
 * off  size  field
 * 0    2     magic 0x55 0xCC
 * 2    2     port ID (u16 LE)
 * 4    2     payload length (u16 LE)
 * 6    2     reserved (always 0x00 0x00)
 * 8    N     payload
 * ```
 *
 * Ports observed:
 *   0x574A (22346) — H.264 Annex-B (primary video)
 *   anything else  — typically DUML (telemetry, camera control)
 *
 * The parser buffers across reads, resyncs on `0x55 0xCC`, and dispatches whole frames.
 */
class LogicLink(
	private val onFrame: (port: Int, payload: ByteArray) -> Unit
) {
	private var buf: ByteArray = ByteArray(INITIAL_BUF)
	private var size: Int = 0

	fun feed(chunk: ByteArray, off: Int, len: Int) {
		if (len <= 0) return
		ensureCapacity(size + len)
		System.arraycopy(chunk, off, buf, size, len)
		size += len
		drain()
	}

	private fun drain() {
		while (true) {
			val end = size - 1
			var sync = -1
			var i = 0
			while (i < end) {
				if (buf[i] == 0x55.toByte() && buf[i + 1] == 0xCC.toByte()) {
					sync = i; break
				}
				i++
			}
			if (sync < 0) {
				// No magic found. If buffer is huge, discard the older half to avoid unbounded growth.
				if (size > MAX_BUF / 2) {
					val keep = MAX_BUF / 4
					System.arraycopy(buf, size - keep, buf, 0, keep)
					size = keep
				}
				return
			}
			if (sync > 0) {
				System.arraycopy(buf, sync, buf, 0, size - sync)
				size -= sync
			}
			if (size < HEADER_LEN) return
			val port = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
			val plen = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
			val frameLen = HEADER_LEN + plen
			if (plen < 0 || frameLen > MAX_FRAME) {
				// Implausible — drop the magic and keep scanning.
				System.arraycopy(buf, 2, buf, 0, size - 2)
				size -= 2
				continue
			}
			if (size < frameLen) return
			val payload = ByteArray(plen)
			if (plen > 0) System.arraycopy(buf, HEADER_LEN, payload, 0, plen)
			if (size > frameLen) {
				System.arraycopy(buf, frameLen, buf, 0, size - frameLen)
			}
			size -= frameLen
			try {
				onFrame(port, payload)
			} catch (_: Throwable) {
				// Never let a frame handler crash the parser loop.
			}
		}
	}

	private fun ensureCapacity(min: Int) {
		if (buf.size >= min) return
		var newSize = buf.size * 2
		while (newSize < min) newSize *= 2
		if (newSize > MAX_BUF) newSize = MAX_BUF.coerceAtLeast(min)
		val nb = ByteArray(newSize)
		System.arraycopy(buf, 0, nb, 0, size)
		buf = nb
	}

	companion object {
		const val MAGIC0: Byte = 0x55
		const val MAGIC1: Byte = 0xCC.toByte()
		const val HEADER_LEN = 8
		const val VIDEO_PORT = 0x574A
		const val MAX_FRAME = 1 shl 22 // 4 MB
		const val INITIAL_BUF = 64 * 1024
		const val MAX_BUF = 8 * 1024 * 1024
	}
}

/**
 * Wrap a payload in a LogicLink envelope (used for TX, e.g. to send DUML control frames out).
 */
object LogicLinkEncoder {
	fun wrap(port: Int, payload: ByteArray): ByteArray {
		val out = ByteArray(LogicLink.HEADER_LEN + payload.size)
		out[0] = LogicLink.MAGIC0
		out[1] = LogicLink.MAGIC1
		out[2] = (port and 0xFF).toByte()
		out[3] = ((port ushr 8) and 0xFF).toByte()
		out[4] = (payload.size and 0xFF).toByte()
		out[5] = ((payload.size ushr 8) and 0xFF).toByte()
		// out[6] = 0, out[7] = 0 (reserved, already zero)
		if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, LogicLink.HEADER_LEN, payload.size)
		return out
	}
}
