package io.basco.djiaoa

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds the live AOA session: opens the accessory, runs reader/writer threads,
 * parses LogicLink + DUML, dispatches frames to listeners.
 *
 * Single-instance: one accessory is connected at a time.
 */
class UsbAoaService(
	private val usbManager: UsbManager,
	private val callbacks: Callbacks
) {

	interface Callbacks {
		fun onVideo(payload: ByteArray, receivedAtNs: Long)
		fun onTelemetry(t: Telemetry)
		fun onDuml(p: DumlPacket)
		fun onClosed(reason: String?)
		fun onPortRaw(port: Int, payload: ByteArray) {}
		fun onPortRawTx(port: Int, payload: ByteArray) {}
		fun onDumlTx(p: DumlPacket) {}
		fun onDeviceCode(code: String, model: DroneModel?) {}
	}

	/**
	 * Optional fast-path video sink. When non-null, video port bytes are routed here
	 * directly (e.g. into a native MediaCodec decoder) and the `onVideo` callback is
	 * NOT fired — that path goes through the JS bridge and adds latency, which we
	 * want to skip while the live preview SurfaceView is active.
	 */
	@Volatile
	var videoBytesSink: ((ByteArray) -> Unit)? = null

	/**
	 * Last product code accepted from a `0x00/0x82` reply, and the model it resolved to.
	 *
	 * `lastDeviceCode` exists to stop the UI flickering between peers: the FC, gimbal, camera and
	 * goggles each answer with their own code, ~1 Hz, forever. Only a *change* is forwarded.
	 *
	 * That dedupe used to be write-only, which made it a trap: a `connected` state is emitted with
	 * `cameraModel: "unknown"`, and if this service had already seen the code before that emission,
	 * no change ever follows and the model stays `unknown` for the whole session. It is exactly how
	 * an O4 Pro (`ZA5305`) reported `unknown` on 2026-08-29 while the correct code was on the wire
	 * the entire time. Exposing it lets the plugin seed the connected state from what we already
	 * know instead of waiting for a change that will not come.
	 */
	@Volatile
	var lastDeviceCode: String? = null
		private set

	@Volatile
	var lastDeviceModel: DroneModel? = null
		private set

	@Volatile
	var debugEnabled: Boolean = false
	@Volatile
	var debugSkipVideo: Boolean = true

	private var pfd: ParcelFileDescriptor? = null
	private var inStream: FileInputStream? = null
	private var outStream: FileOutputStream? = null
	private var accessory: UsbAccessory? = null

	private val running = AtomicBoolean(false)
	private val seq = AtomicInteger(1)
	private val txQueue = LinkedBlockingQueue<ByteArray>()
	private val pendingAcks = ConcurrentHashMap<Int, AckWaiter>()

	private var readerThread: Thread? = null
	private var writerThread: Thread? = null
	private var videoTriggerThread: Thread? = null

	private val linkParser = LogicLink(::onFrame)

	@Volatile
	var sessionId: String = ""
		private set

	val accessoryDescription: String?
		get() = accessory?.let {
			val parts = listOfNotNull(it.manufacturer, it.model, it.version).filter { s -> s.isNotEmpty() }
			parts.joinToString(" ").ifEmpty { it.description ?: "Accessory" }
		}

	fun open(acc: UsbAccessory): Boolean {
		if (running.get()) return true
		val opened = usbManager.openAccessory(acc) ?: run {
			Log.e(TAG, "openAccessory returned null")
			return false
		}
		pfd = opened
		inStream = FileInputStream(opened.fileDescriptor)
		outStream = FileOutputStream(opened.fileDescriptor)
		accessory = acc
		// A new session starts with no model known — and the JS side is about to be told exactly
		// that. Clearing the dedupe here means the next 0x82 reply (they arrive ~1 Hz) re-announces
		// the code instead of being swallowed as "unchanged".
		lastDeviceCode = null
		lastDeviceModel = null
		sessionId = "s-" + System.currentTimeMillis().toString(16)
		running.set(true)
		readerThread = Thread(::readerLoop, "DjiAoa-reader").also { it.isDaemon = true; it.start() }
		writerThread = Thread(::writerLoop, "DjiAoa-writer").also { it.isDaemon = true; it.start() }
		// Stream-start trigger: the N3 only pushes video (port 0x574A) after the mobile side sends
		// the camcap_common + APP-register frames on port 0x5749, resent ~1 Hz as a keepalive.
		// Without the keepalive, only telemetry flows.
		videoTriggerThread = Thread(::videoTriggerLoop, "DjiAoa-videoTrigger").also { it.isDaemon = true; it.start() }
		Log.i(TAG, "Opened accessory: ${accessoryDescription}")
		return true
	}

	fun close(reason: String? = null) {
		if (!running.compareAndSet(true, false)) return
		try { videoTriggerThread?.interrupt() } catch (_: Throwable) {}
		videoTriggerThread = null
		try { inStream?.close() } catch (_: Throwable) {}
		try { outStream?.close() } catch (_: Throwable) {}
		try { pfd?.close() } catch (_: Throwable) {}
		inStream = null; outStream = null; pfd = null; accessory = null
		// Wake waiters
		for ((_, w) in pendingAcks) w.fail("connection closed")
		pendingAcks.clear()
		callbacks.onClosed(reason)
	}

	fun sendOnPort(port: Int, payload: ByteArray) {
		if (!running.get()) return
		if (debugEnabled) callbacks.onPortRawTx(port, payload)
		txQueue.put(LogicLinkEncoder.wrap(port, payload))
	}

	/**
	 * Send a DUML camera command. Returns the ACK payload (without DUML framing) or null on timeout.
	 * The command rides the DUML control port `DEFAULT_CONTROL_PORT` = 0x7530 (30000) — the port all
	 * DUML control uses on a live N3 (confirmed by hardware capture + knowledge-n3.json).
	 */
	fun sendCameraCommand(
		cmdId: Int,
		payload: ByteArray,
		dst: Int = DumlAddr.CAMERA,
		timeoutMs: Long = 1500,
		controlPort: Int = DEFAULT_CONTROL_PORT,
		cmdSet: Int = DumlCmdSet.CAMERA,
		cmdType: Int = DumlCmdType.REQUEST
	): SendResult {
		val s = seq.getAndIncrement() and 0xFFFF
		// cmdType defaults to just REQUEST (0x40). Setting bit 7 on an outgoing camcap request makes
		// the camera silently drop the packet (observed as ACK timeouts on every Apply ISO).
		// cmdSet defaults to CAMERA (0x02) but can be overridden to probe other subsystems.
		val frame = Duml.encode(
			src = DumlAddr.MOBILE,
			dst = dst,
			seq = s,
			cmdType = cmdType,
			cmdSet = cmdSet,
			cmdId = cmdId,
			payload = payload
		)
		val waiter = AckWaiter()
		pendingAcks[s] = waiter
		try {
			if (debugEnabled) {
				callbacks.onPortRawTx(controlPort, frame)
				callbacks.onDumlTx(
					DumlPacket(
						src = DumlAddr.MOBILE,
						dst = dst,
						seq = s,
						cmdType = cmdType,
						cmdSet = cmdSet,
						cmdId = cmdId,
						payload = payload
					)
				)
			}
			txQueue.put(LogicLinkEncoder.wrap(controlPort, frame))
			val result = waiter.await(timeoutMs)
			return result
		} finally {
			pendingAcks.remove(s)
		}
	}

	private fun readerLoop() {
		val buf = ByteArray(64 * 1024)
		val input = inStream ?: return
		try {
			while (running.get()) {
				val n = try { input.read(buf) } catch (e: Throwable) {
					if (running.get()) Log.w(TAG, "read failed: ${e.message}")
					break
				}
				if (n <= 0) {
					Log.w(TAG, "EOF on accessory input")
					break
				}
				linkParser.feed(buf, 0, n)
			}
		} finally {
			close("reader-exit")
		}
	}

	private fun writerLoop() {
		val out = outStream ?: return
		try {
			while (running.get()) {
				val pkt = try { txQueue.take() } catch (_: InterruptedException) { break }
				try {
					out.write(pkt)
					out.flush()
				} catch (e: Throwable) {
					Log.w(TAG, "write failed: ${e.message}")
					break
				}
			}
		} finally {
			close("writer-exit")
		}
	}

	/**
	 * Video stream-start keepalive. The N3 gates video (port 0x574A) behind the mobile side sending
	 * two pre-built LogicLink frames on port 0x5749 — camcap_common then APP-register — resent ~1 Hz.
	 * These are byte-exact camcap_common handshake frames with their CRCs already baked in, so we
	 * write the raw bytes (NOT via sendOnPort, which would re-wrap them). Enqueuing on txQueue
	 * lets the single writer thread serialize them with any DUML commands and flush each.
	 */
	private fun videoTriggerLoop() {
		while (running.get()) {
			try {
				txQueue.put(VIDEO_TRIGGER_CAMCAP.copyOf())
				txQueue.put(VIDEO_TRIGGER_APPREG.copyOf())
			} catch (_: Throwable) {}
			try { Thread.sleep(VIDEO_TRIGGER_INTERVAL_MS) } catch (_: InterruptedException) { break }
		}
	}

	private fun onFrame(port: Int, payload: ByteArray) {
		if (debugEnabled && !(debugSkipVideo && port == LogicLink.VIDEO_PORT)) {
			callbacks.onPortRaw(port, payload)
		}
		if (port == LogicLink.VIDEO_PORT) {
			val sink = videoBytesSink
			if (sink != null) {
				try { sink(payload) } catch (_: Throwable) {}
			} else {
				callbacks.onVideo(payload, System.nanoTime())
			}
			return
		}
		val duml = Duml.decode(payload) ?: run {
			// Unknown port + non-DUML payload. Ignore quietly.
			return
		}
		// Reply delivery: keyed by seq, dispatched when the DUML REPLY bit is set, i.e.
		// `(cmdType & 0x80) != 0`. A reply may or may not also keep the REQUEST bit set
		// (devices vary), so the only safe test is bit 7.
		if ((duml.cmdType and DumlCmdType.REPLY) != 0) {
			pendingAcks[duml.seq]?.deliver(duml.payload)
		}
		// Camera status push — the N3's authoritative armed signal (hardware-confirmed
		// 2026-08-07: byte 0 bit 7 tracks arm/disarm within ~1-4 s across arm cycles).
		if (duml.cmdSet == DumlCmdSet.CAMERA && duml.cmdId == 0x80 && duml.src == DumlAddr.CAMERA) {
			TelemetryParser.parseCameraStatusPush(duml.payload)?.let { callbacks.onTelemetry(it) }
		}
		// Device-type response (cmdSet 0x00 cmdId 0x82) — payload starts with ASCII drone code.
		// Multiple peers (FC, gimbal, camera, RC) each respond with their own product code, so
		// we'd flicker between them. Only accept codes we actually recognize in DeviceTable,
		// which today only contains drone codes (ZA530, ZA5305). Unknown codes get logged
		// for future expansion but never override a previously detected model.
		if (duml.cmdSet == 0x00 && duml.cmdId == 0x82 && duml.payload.size >= 3) {
			val code = extractAsciiPrefix(duml.payload)
			val model = DeviceTable.lookup(code)
			if (model != null && code != lastDeviceCode) {
				lastDeviceCode = code
				lastDeviceModel = model
				callbacks.onDeviceCode(code, model)
			}
		}
		callbacks.onDuml(duml)
	}

	private fun extractAsciiPrefix(b: ByteArray): String {
		val sb = StringBuilder()
		for (x in b) {
			val v = x.toInt() and 0xFF
			if (v in 0x30..0x7E) sb.append(v.toChar()) else break
			if (sb.length >= 16) break
		}
		return sb.toString()
	}

	companion object {
		private const val TAG = "DjiAoa"

		/**
		 * Control port for DUML commands. Confirmed against a live N3 (manufacturer
		 * `com.dji.logiclink`): all DUML traffic flows on port 0x7530 (30000).
		 */
		const val DEFAULT_CONTROL_PORT = 0x7530

		/** How often the video stream-start frames are resent — the camcap keepalive runs at ~1 Hz. */
		private const val VIDEO_TRIGGER_INTERVAL_MS = 1000L

		private fun hexToBytes(s: String): ByteArray {
			val out = ByteArray(s.length / 2)
			for (i in out.indices) {
				out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
			}
			return out
		}

		/**
		 * Pre-built LogicLink frames (port 0x5749) that start the N3 video stream: camcap_common
		 * (0x00/0x99 → 0x28) then APP-register (0x00/0x88 → 0x3C).
		 * CRC-8/CRC-16 are already baked into these bytes — write them raw, do not re-encode.
		 */
		private val VIDEO_TRIGGER_CAMCAP = hexToBytes(
			"55cc49572d000000552d04f20228f3fe40009902020000d507000000000013000d0063616d6361705f636f6d6d6f6e00000000d093923a"
		)
		private val VIDEO_TRIGGER_APPREG = hexToBytes(
			"55cc49571b000000551b0475023cf4fe400088170000230041505000000000000258a63418"
		)
	}

	private class AckWaiter {
		private val lock = Object()
		private var done = false
		private var payload: ByteArray? = null
		private var error: String? = null

		fun await(timeoutMs: Long): SendResult {
			val deadline = System.currentTimeMillis() + timeoutMs
			synchronized(lock) {
				while (!done) {
					val rem = deadline - System.currentTimeMillis()
					if (rem <= 0) return SendResult(null, true, "timeout")
					try { (lock as java.lang.Object).wait(rem) } catch (_: InterruptedException) {}
				}
				return if (error != null) SendResult(null, false, error)
				else SendResult(payload, false, null)
			}
		}

		fun deliver(b: ByteArray) {
			synchronized(lock) {
				if (done) return
				payload = b
				done = true
				(lock as java.lang.Object).notifyAll()
			}
		}

		fun fail(reason: String) {
			synchronized(lock) {
				if (done) return
				error = reason
				done = true
				(lock as java.lang.Object).notifyAll()
			}
		}
	}
}

data class SendResult(val ack: ByteArray?, val timedOut: Boolean, val error: String?) {
	fun ackAsBase64(): String =
		if (ack != null) Base64.encodeToString(ack, Base64.NO_WRAP) else ""
}
