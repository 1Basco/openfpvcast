package io.basco.djiaoa

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out for the raw H.264 video byte stream (LogicLink port 0x574A).
 *
 * Several consumers may want the video stream at the same time — the live-preview decoder, the
 * MP4 recorder, and the WebRTC broadcaster. The transport exposes a single sink slot, so this
 * multiplexes that one producer to many sinks.
 *
 * IMPORTANT: [dispatch] runs on the USB reader thread (`DjiAoa-reader`), which also drives
 * telemetry parsing and the ~1 Hz video-trigger keepalive. A sink that blocks here stalls USB
 * reads and the goggles stop sending video. Every sink MUST copy the bytes into its own bounded
 * queue and return immediately — never decode / encode / mux inline.
 *
 * When no sink is registered, bytes are handed to [fallback] instead (the legacy JS `video`
 * event path), so behaviour is unchanged while nothing native is attached.
 *
 * Sink identity is by reference: keep the exact lambda instance you pass to [addSink] and hand
 * the same instance to [removeSink] (a fresh `obj::method` reference will NOT match).
 */
class VideoFanout(private val fallback: (ByteArray) -> Unit) {

	private val sinks = CopyOnWriteArrayList<(ByteArray) -> Unit>()

	val hasSinks: Boolean get() = sinks.isNotEmpty()

	fun addSink(sink: (ByteArray) -> Unit) {
		sinks.addIfAbsent(sink)
	}

	fun removeSink(sink: (ByteArray) -> Unit) {
		sinks.remove(sink)
	}

	fun clear() {
		sinks.clear()
	}

	fun dispatch(bytes: ByteArray) {
		if (sinks.isEmpty()) {
			try { fallback(bytes) } catch (_: Throwable) {}
			return
		}
		for (sink in sinks) {
			try { sink(bytes) } catch (_: Throwable) {}
		}
	}
}
