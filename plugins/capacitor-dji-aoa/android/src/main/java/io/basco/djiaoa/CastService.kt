package io.basco.djiaoa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the app process, microphone, and network alive while recording
 * or broadcasting, and satisfies Android 14's requirement that mic/data-sync work run under a
 * typed foreground service. It holds no logic of its own — the recorder and broadcaster live in
 * the plugin (same process); this just owns the foreground notification + service type.
 *
 * Start/stop via [start] / [stop]. Pass `audio=true` when the microphone is in use so the
 * `microphone` service type is declared (it requires the RECORD_AUDIO runtime permission).
 */
class CastService : Service() {

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val audio = intent?.getBooleanExtra(EXTRA_AUDIO, false) ?: false
		val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Recording"
		ensureChannel()
		val notification = buildNotification(text)
		val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
			if (audio) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
			t
		} else 0
		try {
			ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
		} catch (_: Throwable) {
			// If the typed start is rejected (e.g. permission missing), stop rather than crash.
			stopSelf()
		}
		return START_NOT_STICKY
	}

	private fun buildNotification(text: String): Notification {
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Open FPV Cast")
			.setContentText(text)
			.setSmallIcon(android.R.drawable.presence_video_online)
			.setOngoing(true)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.build()
	}

	private fun ensureChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
			if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
				val ch = NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
				ch.setShowBadge(false)
				mgr.createNotificationChannel(ch)
			}
		}
	}

	companion object {
		private const val CHANNEL_ID = "fpv_dji_cast_capture"
		private const val NOTIF_ID = 42
		private const val EXTRA_AUDIO = "audio"
		private const val EXTRA_TEXT = "text"

		fun start(context: Context, audio: Boolean, text: String) {
			val intent = Intent(context, CastService::class.java)
				.putExtra(EXTRA_AUDIO, audio)
				.putExtra(EXTRA_TEXT, text)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				context.startForegroundService(intent)
			} else {
				context.startService(intent)
			}
		}

		fun stop(context: Context) {
			try { context.stopService(Intent(context, CastService::class.java)) } catch (_: Throwable) {}
		}
	}
}
