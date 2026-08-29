package io.basco.djiaoa

import org.json.JSONObject

/**
 * Telemetry the app actually consumes: the aircraft's **armed** state, and nothing else.
 *
 * Scope decision (2026-08-29): the app does not chase flight telemetry. Everything a pilot
 * wants to read mid-flight — battery voltage, link/signal strength, altitude, speed — is
 * already composited into the video by the goggles' own OSD, which is what the LiveView
 * stream carries. Duplicating it as parsed data would mean reversing DJI's iLink/protobuf
 * OSD topics for values the user can already see, so that whole direction is out of scope.
 *
 * `armed` stays because it is not a display value: auto-record-on-arm needs it as a signal.
 */
data class Telemetry(
	val timestampMs: Long,
	val armed: Boolean
) {
	fun toJson(): JSONObject = JSONObject()
		.put("timestampMs", timestampMs)
		.put("armed", armed)
}

object TelemetryParser {

	/**
	 * Parse the camera status push (src 0x01, cmdSet 0x02, cmdId 0x80) — the N3's armed signal.
	 *
	 * HARDWARE-CONFIRMED (live N3 capture, 2026-08-07, two arm/disarm cycles, 800 packets):
	 * byte 0 flips 0x01 → 0x81 on arm and back on disarm — bit 7 is the only bit in the whole
	 * push that discriminates the states. (It plausibly reflects the air unit's own onboard
	 * recording, which starts/stops with arming — equivalent for auto-record purposes.)
	 *
	 * The classic DUML v1 "OSD General Data" push (cmdSet 0x03 / cmdId 0x43) used to be parsed
	 * here for attitude/GPS/velocity. It was removed: a 2026-08-07 N3 capture showed the goggles
	 * never emit it, its byte offsets were never confirmed on any hardware we have, and the
	 * fields it produced are out of scope per the note on [Telemetry].
	 */
	fun parseCameraStatusPush(payload: ByteArray, timestampMs: Long = System.currentTimeMillis()): Telemetry? {
		if (payload.isEmpty()) return null
		return Telemetry(timestampMs = timestampMs, armed = (payload[0].toInt() and 0x80) != 0)
	}
}
