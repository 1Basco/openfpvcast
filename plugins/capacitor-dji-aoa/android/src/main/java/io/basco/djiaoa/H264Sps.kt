package io.basco.djiaoa

/**
 * Minimal H.264 SPS parser — just enough to recover the coded picture width/height so the MP4
 * track header carries correct dimensions. Decoders read the real SPS from csd-0 regardless, but
 * a wrong track-header size makes some players letterbox incorrectly.
 *
 * Input is a single SPS NAL unit WITH its 1-byte NAL header (type 7), WITHOUT the start code.
 */
object H264Sps {

	data class Dim(val width: Int, val height: Int)

	fun parse(sps: ByteArray): Dim? {
		if (sps.size < 4) return null
		// Strip NAL header byte, then un-escape emulation-prevention bytes (00 00 03 -> 00 00).
		val rbsp = unescape(sps, 1)
		val r = BitReader(rbsp)
		try {
			val profileIdc = r.u(8)
			r.u(8)               // constraint flags + reserved
			r.u(8)               // level_idc
			r.ue()               // seq_parameter_set_id

			if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
				val chromaFormatIdc = r.ue()
				if (chromaFormatIdc == 3) r.u(1) // separate_colour_plane_flag
				r.ue()           // bit_depth_luma_minus8
				r.ue()           // bit_depth_chroma_minus8
				r.u(1)           // qpprime_y_zero_transform_bypass_flag
				if (r.u(1) == 1) { // seq_scaling_matrix_present_flag
					val n = if (chromaFormatIdc != 3) 8 else 12
					for (i in 0 until n) {
						if (r.u(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
					}
				}
			}

			r.ue()               // log2_max_frame_num_minus4
			val picOrderCntType = r.ue()
			if (picOrderCntType == 0) {
				r.ue()           // log2_max_pic_order_cnt_lsb_minus4
			} else if (picOrderCntType == 1) {
				r.u(1)           // delta_pic_order_always_zero_flag
				r.se()           // offset_for_non_ref_pic
				r.se()           // offset_for_top_to_bottom_field
				val n = r.ue()
				for (i in 0 until n) r.se()
			}
			r.ue()               // max_num_ref_frames
			r.u(1)               // gaps_in_frame_num_value_allowed_flag

			val picWidthInMbs = r.ue() + 1
			val picHeightInMapUnits = r.ue() + 1
			val frameMbsOnlyFlag = r.u(1)
			if (frameMbsOnlyFlag == 0) r.u(1) // mb_adaptive_frame_field_flag
			r.u(1)               // direct_8x8_inference_flag

			var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
			if (r.u(1) == 1) {   // frame_cropping_flag
				cropLeft = r.ue(); cropRight = r.ue(); cropTop = r.ue(); cropBottom = r.ue()
			}

			val width = picWidthInMbs * 16
			val height = picHeightInMapUnits * 16 * (2 - frameMbsOnlyFlag)
			// crop units: 2 in x, (2 - frame_mbs_only) in y for 4:2:0 (the common case)
			val cropUnitX = 2
			val cropUnitY = 2 * (2 - frameMbsOnlyFlag)
			val w = width - (cropLeft + cropRight) * cropUnitX
			val h = height - (cropTop + cropBottom) * cropUnitY
			if (w <= 0 || h <= 0) return null
			return Dim(w, h)
		} catch (_: Throwable) {
			return null
		}
	}

	private fun skipScalingList(r: BitReader, size: Int) {
		var lastScale = 8
		var nextScale = 8
		for (j in 0 until size) {
			if (nextScale != 0) {
				val delta = r.se()
				nextScale = (lastScale + delta + 256) % 256
			}
			lastScale = if (nextScale == 0) lastScale else nextScale
		}
	}

	private fun unescape(data: ByteArray, from: Int): ByteArray {
		val out = ArrayList<Byte>(data.size - from)
		var zeros = 0
		var i = from
		while (i < data.size) {
			val b = data[i]
			if (zeros >= 2 && b.toInt() == 0x03) {
				// emulation_prevention_three_byte — drop it
				zeros = 0
				i++
				continue
			}
			out.add(b)
			zeros = if (b.toInt() == 0x00) zeros + 1 else 0
			i++
		}
		return out.toByteArray()
	}

	/** Big-endian bit reader with Exp-Golomb support. */
	private class BitReader(private val data: ByteArray) {
		private var bytePos = 0
		private var bitPos = 0

		fun u(n: Int): Int {
			var v = 0
			repeat(n) {
				v = (v shl 1) or bit()
			}
			return v
		}

		fun ue(): Int {
			var zeros = 0
			while (bit() == 0) zeros++
			if (zeros == 0) return 0
			var v = 1
			repeat(zeros) { v = (v shl 1) or bit() }
			return v - 1
		}

		fun se(): Int {
			val k = ue()
			val sign = if (k % 2 == 0) -1 else 1
			return sign * ((k + 1) / 2)
		}

		private fun bit(): Int {
			if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS bit overrun")
			val b = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
			bitPos++
			if (bitPos == 8) { bitPos = 0; bytePos++ }
			return b
		}
	}
}
