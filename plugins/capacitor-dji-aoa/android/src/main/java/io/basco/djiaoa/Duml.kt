package io.basco.djiaoa

/**
 * DUML wire format:
 *
 * ```
 * off  size  field
 * 0    1     magic 0x55
 * 1    2     length(10b) | version(6b)   (length = total packet bytes including trailing CRC-16)
 * 3    1     CRC-8 of bytes 0..2
 * 4    1     src address
 * 5    1     dst address
 * 6    2     sequence (u16 BE — packet[6]=hi, packet[7]=lo)
 * 8    1     command type
 * 9    1     command set
 * 10   1     command id
 * 11.. N     payload
 * end-2 2    CRC-16 of bytes 0..end-3 (LE)
 * ```
 *
 * DUML addresses:
 *   0x01 camera | 0x02 mobile/app | 0x03 flight controller | 0x04 gimbal
 *   0x06 RC     | 0x07 Wi-Fi (air side) | 0x0A PC | 0x1B Wi-Fi (ground side, addr 27)
 */
object DumlAddr {
	const val CAMERA = 0x01
	const val MOBILE = 0x02
	const val FC = 0x03
	const val GIMBAL = 0x04
	const val RC = 0x06
	const val WIFI_AIR = 0x07
	const val WIFI_GND = 0x1B
}

object DumlCmdSet {
	const val CAMERA = 0x02
}

/**
 * cmdType bit flags — `(cmdType & 0x80) != 0` marks a REPLY, and camcap camera commands are
 * all sent with cmdType=0x40 (REQUEST, no other bits).
 *
 *   bit 7 (0x80) — REPLY: set by the responding device on its reply packets.
 *   bit 6 (0x40) — REQUEST: set when we are asking. Do NOT set ACK_REQUIRED — setting it gets
 *                  the camera to drop the request silently.
 */
object DumlCmdType {
	const val REQUEST = 0x40
	const val REPLY = 0x80
}

data class DumlPacket(
	val src: Int,
	val dst: Int,
	val seq: Int,
	val cmdType: Int,
	val cmdSet: Int,
	val cmdId: Int,
	val payload: ByteArray
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is DumlPacket) return false
		return src == other.src && dst == other.dst && seq == other.seq &&
			cmdType == other.cmdType && cmdSet == other.cmdSet && cmdId == other.cmdId &&
			payload.contentEquals(other.payload)
	}

	override fun hashCode(): Int {
		var r = src
		r = 31 * r + dst
		r = 31 * r + seq
		r = 31 * r + cmdType
		r = 31 * r + cmdSet
		r = 31 * r + cmdId
		r = 31 * r + payload.contentHashCode()
		return r
	}
}

object Duml {

	private const val MAGIC: Byte = 0x55

	/**
	 * Encode a DUML packet. Set `lengthPrefixPayload = true` to prefix the payload with a single
	 * byte = payload length (used for some camcap commands).
	 */
	fun encode(
		src: Int,
		dst: Int,
		seq: Int,
		cmdType: Int,
		cmdSet: Int,
		cmdId: Int,
		payload: ByteArray,
		version: Int = 1,
		lengthPrefixPayload: Boolean = false
	): ByteArray {
		val pl = if (lengthPrefixPayload) {
			require(payload.size <= 255) { "payload too large for 1-byte length prefix: ${payload.size}" }
			ByteArray(payload.size + 1).also {
				it[0] = payload.size.toByte()
				System.arraycopy(payload, 0, it, 1, payload.size)
			}
		} else payload

		val totalLen = pl.size + 13
		require(totalLen <= 1023) { "DUML length exceeds 10-bit max: $totalLen" }

		val out = ByteArray(totalLen)
		out[0] = MAGIC
		out[1] = (totalLen and 0xFF).toByte()
		out[2] = (((totalLen ushr 8) and 0x03) or ((version and 0x3F) shl 2)).toByte()
		out[3] = Crc.crc8(out, 0, 3).toByte()
		out[4] = (src and 0xFF).toByte()
		out[5] = (dst and 0xFF).toByte()
		out[6] = ((seq ushr 8) and 0xFF).toByte()
		out[7] = (seq and 0xFF).toByte()
		out[8] = (cmdType and 0xFF).toByte()
		out[9] = (cmdSet and 0xFF).toByte()
		out[10] = (cmdId and 0xFF).toByte()
		if (pl.isNotEmpty()) System.arraycopy(pl, 0, out, 11, pl.size)
		val crc16 = Crc.crc16(out, 0, totalLen - 2)
		out[totalLen - 2] = (crc16 and 0xFF).toByte()
		out[totalLen - 1] = ((crc16 ushr 8) and 0xFF).toByte()
		return out
	}

	/**
	 * Decode a DUML packet. Returns null if validation fails (bad magic, bad CRC-8 header, bad CRC-16, truncated).
	 */
	fun decode(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): DumlPacket? {
		if (len < 13) return null
		if (bytes[off] != MAGIC) return null
		val totalLen = (bytes[off + 1].toInt() and 0xFF) or (((bytes[off + 2].toInt() and 0xFF) and 0x03) shl 8)
		if (totalLen < 13 || totalLen > len) return null
		val crc8Expected = bytes[off + 3].toInt() and 0xFF
		if (Crc.crc8(bytes, off, 3) != crc8Expected) return null
		val crc16Expected = (bytes[off + totalLen - 2].toInt() and 0xFF) or
			((bytes[off + totalLen - 1].toInt() and 0xFF) shl 8)
		if (Crc.crc16(bytes, off, totalLen - 2) != crc16Expected) return null

		val src = bytes[off + 4].toInt() and 0xFF
		val dst = bytes[off + 5].toInt() and 0xFF
		val seq = ((bytes[off + 6].toInt() and 0xFF) shl 8) or (bytes[off + 7].toInt() and 0xFF)
		val cmdType = bytes[off + 8].toInt() and 0xFF
		val cmdSet = bytes[off + 9].toInt() and 0xFF
		val cmdId = bytes[off + 10].toInt() and 0xFF
		val payload = ByteArray(totalLen - 13)
		if (payload.isNotEmpty()) System.arraycopy(bytes, off + 11, payload, 0, payload.size)
		return DumlPacket(src, dst, seq, cmdType, cmdSet, cmdId, payload)
	}
}
