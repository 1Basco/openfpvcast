package io.basco.djiaoa

/**
 * DUML CRC algorithms. Validated against real N3 hardware.
 *
 *  - CRC-8:  init = 0x77, poly = 0x8C (reflected). Covers DUML header bytes 0..2.
 *  - CRC-16: init = 0x3692, poly = 0x8408 (reflected CCITT). Covers DUML bytes 0..end-3 (excluding the 2 trailing CRC bytes themselves).
 */
internal object Crc {

	fun crc8(data: ByteArray, off: Int = 0, len: Int = data.size): Int {
		var c = 0x77
		for (i in off until off + len) {
			c = c xor (data[i].toInt() and 0xFF)
			repeat(8) {
				c = if ((c and 1) != 0) (c ushr 1) xor 0x8C else c ushr 1
			}
		}
		return c and 0xFF
	}

	fun crc16(data: ByteArray, off: Int = 0, len: Int = data.size): Int {
		var c = 0x3692
		for (i in off until off + len) {
			c = c xor (data[i].toInt() and 0xFF)
			repeat(8) {
				c = if ((c and 1) != 0) (c ushr 1) xor 0x8408 else c ushr 1
			}
		}
		return c and 0xFFFF
	}
}
