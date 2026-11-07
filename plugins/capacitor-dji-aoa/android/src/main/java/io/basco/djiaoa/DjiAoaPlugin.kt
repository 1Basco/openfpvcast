package io.basco.djiaoa

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
	name = "DjiAoa",
	permissions = [
		Permission(strings = [android.Manifest.permission.RECORD_AUDIO], alias = "microphone")
	]
)
class DjiAoaPlugin : Plugin() {

	private val usbManager: UsbManager
		get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

	private var service: UsbAoaService? = null
	private var pendingConnectCall: PluginCall? = null
	private var debugEnabledDefault = false
	private var debugSkipVideoDefault = true

	private var previewSurfaceView: SurfaceView? = null
	private var videoDecoder: VideoDecoder? = null

	/** Multiplexes the video byte stream to preview / recorder / broadcaster. Created on connect. */
	private var fanout: VideoFanout? = null
	/** The exact sink instance the preview decoder registered, kept so it can be removed by identity. */
	private var previewSink: ((ByteArray) -> Unit)? = null

	private var recorder: Mp4Recorder? = null
	private var recorderSink: ((ByteArray) -> Unit)? = null
	private var recorderUsesAudio = false

	private var broadcaster: WebRtcBroadcaster? = null
	private var broadcasterSink: ((ByteArray) -> Unit)? = null

	@Volatile
	private var lastState: JSObject = JSObject().put("state", "disconnected")

	// Drives the JS `previewState` event: the UI needs a cheap "video is flowing" signal to
	// switch between the disconnected and streaming layouts. The fan-out wrapper stamps
	// lastVideoAtMs on every chunk; a 1 Hz poller on the main looper emits `previewState` only
	// on active/inactive transitions.
	@Volatile private var lastVideoAtMs = 0L
	private var previewStateActive = false
	private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val videoFlowWatcher = object : Runnable {
		override fun run() {
			val active = service != null &&
				android.os.SystemClock.elapsedRealtime() - lastVideoAtMs < VIDEO_STALL_MS
			if (active != previewStateActive) {
				previewStateActive = active
				val evt = JSObject().put("active", active)
				videoDecoder?.let {
					if (it.videoWidth > 0) {
						evt.put("width", it.videoWidth)
						evt.put("height", it.videoHeight)
					}
				}
				notifyListeners("previewState", evt)
			}
			mainHandler.postDelayed(this, 1000)
		}
	}

	private val receiver = object : BroadcastReceiver() {
		override fun onReceive(ctx: Context, intent: Intent) {
			when (intent.action) {
				UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
					emitStateAttached(currentAccessory())
				}
				UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
					stopRecorderIfActive("detached")
					stopBroadcasterIfActive("detached")
					service?.close("detached")
					service = null
					emitState(JSObject().put("state", "disconnected").put("reason", "detached"))
				}
				ACTION_USB_PERMISSION -> {
					val acc: UsbAccessory? = if (Build.VERSION.SDK_INT >= 33) {
						intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
					} else {
						@Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
					}
					val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
					if (granted && acc != null) {
						openAndReply(acc)
					} else {
						emitState(JSObject().put("state", "permission_denied"))
						pendingConnectCall?.reject("USB permission denied")
						pendingConnectCall = null
					}
				}
			}
		}
	}

	override fun load() {
		val filter = IntentFilter().apply {
			addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
			addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
			addAction(ACTION_USB_PERMISSION)
		}
		if (Build.VERSION.SDK_INT >= 33) {
			context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
		} else {
			@Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
		}
		// Surface any accessory that was already attached when the app launched (typical
		// flow: user opens the app from the USB-attached intent).
		currentAccessory()?.let { emitStateAttached(it) }
		mainHandler.postDelayed(videoFlowWatcher, 1000)
	}

	override fun handleOnDestroy() {
		mainHandler.removeCallbacks(videoFlowWatcher)
		try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
		stopRecorderIfActive("plugin-destroyed")
		stopBroadcasterIfActive("plugin-destroyed")
		videoDecoder?.close()
		videoDecoder = null
		previewSurfaceView?.let { sv ->
			activity?.runOnUiThread { (sv.parent as? ViewGroup)?.removeView(sv) }
		}
		previewSurfaceView = null
		previewSink = null
		fanout?.clear()
		fanout = null
		service?.close("plugin-destroyed")
		service = null
	}

	@PluginMethod
	fun listAccessories(call: PluginCall) {
		val arr = com.getcapacitor.JSArray()
		usbManager.accessoryList?.forEach { acc ->
			val item = JSObject()
			item.put("manufacturer", acc.manufacturer ?: "")
			item.put("model", acc.model ?: "")
			item.put("version", acc.version ?: "")
			item.put("description", acc.description ?: "")
			item.put("hasPermission", usbManager.hasPermission(acc))
			arr.put(item)
		}
		val ret = JSObject()
		ret.put("accessories", arr)
		call.resolve(ret)
	}

	@PluginMethod
	fun connect(call: PluginCall) {
		val acc = currentAccessory() ?: return call.reject("No accessory attached")
		if (usbManager.hasPermission(acc)) {
			openAndReply(acc, call)
			return
		}
		pendingConnectCall = call
		emitState(JSObject().put("state", "requesting_permission"))
		val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
		val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
		val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
		usbManager.requestPermission(acc, pi)
	}

	@PluginMethod
	fun disconnect(call: PluginCall) {
		service?.close("explicit")
		service = null
		emitState(JSObject().put("state", "disconnected").put("reason", "explicit"))
		call.resolve()
	}

	@PluginMethod
	fun getConnectionState(call: PluginCall) {
		call.resolve(lastState)
	}

	@PluginMethod
	fun sendCameraCommand(call: PluginCall) {
		val svc = service ?: return call.reject("Not connected")
		val cmdId = call.getInt("cmdId") ?: return call.reject("cmdId required")
		val payloadB64 = call.getString("payload") ?: ""
		val dst = call.getInt("dst") ?: DumlAddr.CAMERA
		val timeout = call.getInt("timeoutMs")?.toLong() ?: 1500L
		val cmdSet = call.getInt("cmdSet") ?: DumlCmdSet.CAMERA
		val cmdType = call.getInt("cmdType") ?: DumlCmdType.REQUEST
		val payload = if (payloadB64.isEmpty()) ByteArray(0) else Base64.decode(payloadB64, Base64.NO_WRAP)
		Thread {
			val result = svc.sendCameraCommand(cmdId, payload, dst = dst, timeoutMs = timeout, cmdSet = cmdSet, cmdType = cmdType)
			val ret = JSObject()
			ret.put("ack", result.ackAsBase64())
			ret.put("timedOut", result.timedOut)
			result.error?.let { ret.put("error", it) }
			call.resolve(ret)
		}.start()
	}

	@PluginMethod
	fun startRecording(call: PluginCall) {
		if (service == null) return call.reject("Not connected")
		val fo = fanout ?: return call.reject("Video pipeline not ready")
		if (recorder != null) return call.reject("Already recording")

		val recordAudio = call.getBoolean("recordAudio") ?: true
		val fps = call.getInt("fps") ?: DEFAULT_RECORD_FPS
		val recordingId = "r-" + System.currentTimeMillis().toString(16)

		if (recordAudio && !hasAudioPermission()) {
			return call.reject("RECORD_AUDIO permission not granted")
		}

		recorderUsesAudio = recordAudio
		val rec = Mp4Recorder(context, recordingId, fps, recordAudio) { state, error, durationMs ->
			emitRecordingState(recordingId, state, error, durationMs)
		}
		val sink: (ByteArray) -> Unit = rec.videoSink
		recorderSink = sink
		fo.addSink(sink)
		rec.start()
		recorder = rec
		syncCastService()

		val ret = JSObject()
		ret.put("recordingId", recordingId)
		// The file is created on the first keyframe; the real path arrives via `stopRecording`
		// and the `recordingState` event. Return the id now so the UI can track it.
		ret.put("outputPath", "")
		call.resolve(ret)
	}

	@PluginMethod
	fun stopRecording(call: PluginCall) {
		val id = call.getString("recordingId")
		val rec = recorder
		if (rec == null || (id != null && id != rec.recordingId)) {
			return call.reject("No active recording with that id")
		}
		recorderSink?.let { fanout?.removeSink(it) }
		recorderSink = null
		recorder = null
		val result = rec.stop()
		syncCastService()
		val ret = JSObject()
		ret.put("outputPath", result?.outputPath ?: "")
		ret.put("durationMs", result?.durationMs ?: 0L)
		ret.put("sizeBytes", result?.sizeBytes ?: 0L)
		call.resolve(ret)
	}

	@PluginMethod
	fun listRecordings(call: PluginCall) {
		val arr = com.getcapacitor.JSArray()
		try {
			for (m in MediaStoreOutput.list(context)) {
				val item = JSObject()
				for ((k, v) in m) item.put(k, v)
				arr.put(item)
			}
		} catch (e: Throwable) {
			return call.reject("listRecordings failed: ${e.message}")
		}
		call.resolve(JSObject().put("recordings", arr))
	}

	@PluginMethod
	fun deleteRecording(call: PluginCall) {
		val uri = call.getString("uri") ?: return call.reject("uri required")
		val ok = MediaStoreOutput.delete(context, uri)
		if (ok) call.resolve() else call.reject("delete failed")
	}

	@PluginMethod
	fun showPreview(call: PluginCall) {
		val x = call.getInt("x") ?: 0
		val y = call.getInt("y") ?: 0
		val w = call.getInt("width") ?: 0
		val h = call.getInt("height") ?: 0
		if (w <= 0 || h <= 0) return call.reject("width/height must be > 0")
		val act = activity ?: return call.reject("no activity")
		act.runOnUiThread {
			val sv = ensurePreviewView()
			// The N3 always streams a 16:9 frame (even with the camera set to 4:3 — that gets
			// pillarboxed inside a 16:9 frame by the goggles). The decoder scales to fill the
			// SurfaceView, so to avoid stretching we letterbox: fit the largest 16:9 rect inside the
			// requested (x,y,w,h) box and center it. In a portrait box this yields a centered
			// horizontal strip; the WebView shows through the bars above/below.
			val ar = VIDEO_ASPECT_W.toFloat() / VIDEO_ASPECT_H.toFloat()
			var fw = w
			var fh = Math.round(w / ar)
			if (fh > h) { fh = h; fw = Math.round(h * ar) }
			val fx = x + (w - fw) / 2
			val fy = y + (h - fh) / 2
			// LayoutParams MUST match the parent's type. Capacitor's WebView lives inside a
			// CoordinatorLayout, which casts every child's params to CoordinatorLayout.LayoutParams
			// during measure — handing it a FrameLayout.LayoutParams crashes the whole activity.
			val lp: ViewGroup.MarginLayoutParams = when (sv.parent) {
				is CoordinatorLayout -> CoordinatorLayout.LayoutParams(fw, fh)
				is FrameLayout -> FrameLayout.LayoutParams(fw, fh)
				else -> ViewGroup.MarginLayoutParams(fw, fh)
			}
			lp.leftMargin = fx
			lp.topMargin = fy
			sv.layoutParams = lp
			sv.visibility = View.VISIBLE
			sv.bringToFront()
		}
		call.resolve()
	}

	@PluginMethod
	fun hidePreview(call: PluginCall) {
		val act = activity ?: return call.resolve()
		act.runOnUiThread {
			previewSurfaceView?.visibility = View.GONE
		}
		call.resolve()
	}

	private fun ensurePreviewView(): SurfaceView {
		previewSurfaceView?.let { return it }
		val act = activity!!
		val parent = bridge.webView.parent as ViewGroup
		val sv = SurfaceView(act)
		sv.holder.addCallback(object : SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder) {
				val dec = VideoDecoder(holder.surface)
				dec.start()
				videoDecoder = dec
				// Register the decoder as a fan-out sink (not the sole sink) so recording and
				// WebRTC can consume the same stream. Keep the exact instance for removal.
				val sink: (ByteArray) -> Unit = dec::feed
				previewSink = sink
				fanout?.addSink(sink)
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

			override fun surfaceDestroyed(holder: SurfaceHolder) {
				previewSink?.let { fanout?.removeSink(it) }
				previewSink = null
				videoDecoder?.close()
				videoDecoder = null
			}
		})
		// Add SurfaceView as a sibling on top of the WebView. Outside the rect we set on
		// it via LayoutParams, the WebView shows through.
		parent.addView(sv)
		previewSurfaceView = sv
		return sv
	}

	@PluginMethod
	fun startBroadcast(call: PluginCall) {
		if (service == null) return call.reject("Not connected")
		val fo = fanout ?: return call.reject("Video pipeline not ready")
		broadcaster?.let { if (it.running) return call.resolve(broadcastStateJs()) }

		val port = call.getInt("port") ?: WebRtcBroadcaster.DEFAULT_PORT
		val quality = BroadcastQuality.from(call.getString("quality"))
		val bc = WebRtcBroadcaster(context) { _, _, _ ->
			notifyListeners("broadcastState", broadcastStateJs())
		}
		// The heavy lifting (libwebrtc init, server bind) must stay off the main thread.
		Thread {
			try {
				val url = bc.start(port, quality)
				val sink = bc.sink
				broadcasterSink = sink
				fo.addSink(sink)
				broadcaster = bc
				syncCastService()
				notifyListeners("broadcastState", broadcastStateJs())
				call.resolve(JSObject().put("url", url))
			} catch (e: Throwable) {
				try { bc.stop() } catch (_: Throwable) {}
				call.reject("startBroadcast failed: ${e.message}")
			}
		}.start()
	}

	@PluginMethod
	fun stopBroadcast(call: PluginCall) {
		Thread {
			stopBroadcasterIfActive("explicit")
			notifyListeners("broadcastState", broadcastStateJs())
			call.resolve()
		}.start()
	}

	@PluginMethod
	fun getBroadcastState(call: PluginCall) {
		call.resolve(broadcastStateJs())
	}

	private fun broadcastStateJs(): JSObject {
		val bc = broadcaster
		val ret = JSObject()
		ret.put("running", bc?.running == true)
		bc?.url?.let { ret.put("url", it) }
		ret.put("viewers", bc?.viewers ?: 0)
		// Report the profile the running graph was built with, not the one the UI wants — they
		// diverge until the broadcast is restarted.
		bc?.let { ret.put("quality", it.quality.jsName) }
		return ret
	}

	@PluginMethod
	fun requestAudioPermission(call: PluginCall) {
		if (hasAudioPermission()) {
			call.resolve(JSObject().put("granted", true))
			return
		}
		requestPermissionForAlias("microphone", call, "audioPermissionCallback")
	}

	@PermissionCallback
	private fun audioPermissionCallback(call: PluginCall) {
		call.resolve(JSObject().put("granted", hasAudioPermission()))
	}

	@PluginMethod
	fun setDebug(call: PluginCall) {
		val enabled = call.getBoolean("enabled") ?: false
		val skipVideo = call.getBoolean("skipVideo") ?: true
		service?.let {
			it.debugEnabled = enabled
			it.debugSkipVideo = skipVideo
		}
		// Also remember as defaults so a fresh service applies them on reconnect.
		debugEnabledDefault = enabled
		debugSkipVideoDefault = skipVideo
		call.resolve()
	}

	/** Stop and finalize any in-flight recording (called on detach / destroy). */
	private fun stopRecorderIfActive(reason: String) {
		val rec = recorder ?: return
		android.util.Log.i("DjiAoa", "stopping active recording ($reason)")
		recorderSink?.let { fanout?.removeSink(it) }
		recorderSink = null
		recorder = null
		try { rec.stop() } catch (_: Throwable) {}
		syncCastService()
	}

	/** Stop and tear down the broadcast (called on detach / destroy / explicit stop). */
	private fun stopBroadcasterIfActive(reason: String) {
		val bc = broadcaster ?: return
		android.util.Log.i("DjiAoa", "stopping broadcast ($reason)")
		broadcasterSink?.let { fanout?.removeSink(it) }
		broadcasterSink = null
		broadcaster = null
		try { bc.stop() } catch (_: Throwable) {}
		syncCastService()
	}

	/**
	 * Keep the foreground CastService in sync with what is actually running: recording and/or
	 * broadcast keep it alive (with the mic type only while recording uses audio); when both
	 * are idle it stops.
	 */
	private fun syncCastService() {
		val rec = recorder != null
		val bcast = broadcaster?.running == true
		if (!rec && !bcast) {
			CastService.stop(context)
			return
		}
		val text = when {
			rec && bcast -> "Recording + broadcasting"
			rec -> "Recording flight"
			else -> "Broadcasting live view"
		}
		CastService.start(context, rec && recorderUsesAudio, text)
	}

	private fun hasAudioPermission(): Boolean =
		context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
			android.content.pm.PackageManager.PERMISSION_GRANTED

	private fun emitRecordingState(recordingId: String, state: String, error: String?, durationMs: Long?) {
		val evt = JSObject()
		evt.put("recordingId", recordingId)
		evt.put("state", state)
		error?.let { evt.put("error", it) }
		durationMs?.let { evt.put("durationMs", it) }
		notifyListeners("recordingState", evt)
	}

	/** Emit a raw H.264 chunk to JS via the `video` event (fan-out fallback / legacy path). */
	private fun emitVideoEvent(payload: ByteArray, receivedAtNs: Long) {
		val evt = JSObject()
		evt.put("data", Base64.encodeToString(payload, Base64.NO_WRAP))
		evt.put("receivedAtMs", receivedAtNs / 1_000_000)
		notifyListeners("video", evt)
	}

	private fun currentAccessory(): UsbAccessory? {
		val list = usbManager.accessoryList
		return if (list != null && list.isNotEmpty()) list[0] else null
	}

	private fun openAndReply(acc: UsbAccessory, call: PluginCall? = pendingConnectCall) {
		pendingConnectCall = null
		val svc = service ?: UsbAoaService(usbManager, ServiceCallbacks()).also { service = it }
		svc.debugEnabled = debugEnabledDefault
		svc.debugSkipVideo = debugSkipVideoDefault
		// Route all video through the fan-out. With no sink registered it falls back to the
		// legacy JS `video` event, so behaviour is unchanged until preview/recording attach.
		val fo = fanout ?: VideoFanout { bytes -> emitVideoEvent(bytes, System.nanoTime()) }.also { fanout = it }
		svc.videoBytesSink = { bytes ->
			lastVideoAtMs = android.os.SystemClock.elapsedRealtime()
			fo.dispatch(bytes)
		}
		previewSink?.let { fo.addSink(it) }
		val ok = svc.open(acc)
		if (!ok) {
			emitState(JSObject().put("state", "disconnected").put("reason", "open-failed"))
			call?.reject("openAccessory failed")
			return
		}
		val ret = JSObject().put("sessionId", svc.sessionId)
		val info = JSObject()
		info.put("manufacturer", acc.manufacturer ?: "")
		info.put("model", acc.model ?: "")
		info.put("version", acc.version ?: "")
		info.put("description", svc.accessoryDescription ?: "")
		info.put("hasPermission", true)
		val state = JSObject()
		state.put("state", "connected")
		state.put("sessionId", svc.sessionId)
		state.put("goggleModel", acc.model ?: "Goggles")
		// Seed from whatever the service already knows. A fresh session clears it, so this is
		// normally "unknown" and the first 0x82 reply fills it in — but if a code was already
		// resolved, saying "unknown" here would strand the UI on it, since only a *change* to the
		// code is forwarded afterwards.
		val known = svc.lastDeviceModel
		state.put("cameraModel", when (known?.camera) {
			CameraFamily.O4_LITE -> "o4-lite"
			CameraFamily.O4_PRO -> "o4-pro"
			else -> "unknown"
		})
		svc.lastDeviceCode?.let {
			state.put("droneCode", it)
			state.put("droneModel", known?.displayName ?: it)
		}
		emitState(state)
		call?.resolve(ret)
	}

	private fun emitStateAttached(acc: UsbAccessory?) {
		if (acc == null) return
		val state = JSObject()
		state.put("state", "attached")
		val info = JSObject()
		info.put("manufacturer", acc.manufacturer ?: "")
		info.put("model", acc.model ?: "")
		info.put("version", acc.version ?: "")
		info.put("description", acc.description ?: "")
		info.put("hasPermission", usbManager.hasPermission(acc))
		state.put("accessory", info)
		emitState(state)
	}

	private fun emitState(state: JSObject) {
		lastState = state
		notifyListeners("connectionState", state)
	}

	inner class ServiceCallbacks : UsbAoaService.Callbacks {
		override fun onVideo(payload: ByteArray, receivedAtNs: Long) {
			emitVideoEvent(payload, receivedAtNs)
		}

		override fun onTelemetry(t: Telemetry) {
			notifyListeners("telemetry", JSObject.fromJSONObject(t.toJson()))
		}

		override fun onDuml(p: DumlPacket) {
			if (service?.debugEnabled != true) return
			val evt = JSObject()
			evt.put("src", p.src)
			evt.put("dst", p.dst)
			evt.put("seq", p.seq)
			evt.put("cmdType", p.cmdType)
			evt.put("cmdSet", p.cmdSet)
			evt.put("cmdId", p.cmdId)
			evt.put("payloadHex", toHex(p.payload))
			evt.put("atMs", System.currentTimeMillis())
			notifyListeners("duml", evt)
		}

		override fun onPortRaw(port: Int, payload: ByteArray) {
			val evt = JSObject()
			evt.put("port", port)
			evt.put("hex", toHex(payload))
			evt.put("atMs", System.currentTimeMillis())
			notifyListeners("portRaw", evt)
		}

		override fun onPortRawTx(port: Int, payload: ByteArray) {
			val evt = JSObject()
			evt.put("port", port)
			evt.put("hex", toHex(payload))
			evt.put("atMs", System.currentTimeMillis())
			notifyListeners("portRawTx", evt)
		}

		override fun onDumlTx(p: DumlPacket) {
			val evt = JSObject()
			evt.put("src", p.src)
			evt.put("dst", p.dst)
			evt.put("seq", p.seq)
			evt.put("cmdType", p.cmdType)
			evt.put("cmdSet", p.cmdSet)
			evt.put("cmdId", p.cmdId)
			evt.put("payloadHex", toHex(p.payload))
			evt.put("atMs", System.currentTimeMillis())
			notifyListeners("dumlTx", evt)
		}

		override fun onDeviceCode(code: String, model: DroneModel?) {
			val merged = JSObject()
			val keys = lastState.keys()
			while (keys.hasNext()) {
				val k = keys.next()
				merged.put(k, lastState.get(k))
			}
			merged.put("droneCode", code)
			merged.put("droneModel", model?.displayName ?: code)
			val cam = when (model?.camera) {
				CameraFamily.O4_LITE -> "o4-lite"
				CameraFamily.O4_PRO -> "o4-pro"
				else -> "unknown"
			}
			merged.put("cameraModel", cam)
			emitState(merged)
		}

		override fun onClosed(reason: String?) {
			val state = JSObject().put("state", "disconnected")
			reason?.let { state.put("reason", it) }
			emitState(state)
		}
	}

	companion object {
		private const val ACTION_USB_PERMISSION = "io.basco.djiaoa.USB_PERMISSION"
		private const val DEFAULT_RECORD_FPS = 60
		/** No video bytes for this long → the stream is considered stalled/inactive. */
		private const val VIDEO_STALL_MS = 2000L
		// The N3 always streams a 16:9 frame; used to letterbox the preview so it isn't stretched.
		private const val VIDEO_ASPECT_W = 16
		private const val VIDEO_ASPECT_H = 9
		private val HEX = "0123456789abcdef".toCharArray()
		private fun toHex(b: ByteArray): String {
			val out = CharArray(b.size * 2)
			for (i in b.indices) {
				val v = b[i].toInt() and 0xFF
				out[i * 2] = HEX[v ushr 4]
				out[i * 2 + 1] = HEX[v and 0x0F]
			}
			return String(out)
		}
	}
}
