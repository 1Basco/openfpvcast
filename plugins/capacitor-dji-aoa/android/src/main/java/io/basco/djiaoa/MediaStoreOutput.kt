package io.basco.djiaoa

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/**
 * Destination for a recording under `Movies/OpenFpvCast/`, abstracting the Android 10+
 * scoped-storage split:
 *  - API 29+ (Q): a `MediaStore.Video` entry created with `IS_PENDING=1`, written through the
 *    returned FileDescriptor, then un-pended (or deleted) on finish/abort. Returns a `content://`.
 *  - API 26–28: a plain `File` under the public Movies dir, media-scanned on finish.
 */
class MediaStoreOutput private constructor(
	private val context: Context,
	val displayName: String,
	val uri: Uri?,
	val file: File?,
	private val pfd: ParcelFileDescriptor
) {
	val fileDescriptor: java.io.FileDescriptor get() = pfd.fileDescriptor

	/** Public path/URI string handed back to the JS layer. */
	val outputPath: String get() = uri?.toString() ?: file?.absolutePath ?: displayName

	/** Finalize a good recording. Call AFTER `muxer.stop()`. */
	fun finish() {
		try { pfd.close() } catch (_: Throwable) {}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
			val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
			try { context.contentResolver.update(uri, values, null, null) } catch (_: Throwable) {}
		} else if (file != null) {
			try {
				MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
			} catch (_: Throwable) {}
		}
	}

	/** Discard a failed/empty recording. */
	fun abort() {
		try { pfd.close() } catch (_: Throwable) {}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
			try { context.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
		} else {
			try { file?.delete() } catch (_: Throwable) {}
		}
	}

	fun sizeBytes(): Long = try {
		when {
			file != null -> file.length()
			uri != null -> context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
			else -> 0L
		}
	} catch (_: Throwable) { 0L }

	companion object {
		private const val REL_DIR = "Movies/OpenFpvCast"
		private const val SUBDIR = "OpenFpvCast"

		fun create(context: Context, displayName: String): MediaStoreOutput {
			return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val values = ContentValues().apply {
					put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
					put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
					put(MediaStore.Video.Media.RELATIVE_PATH, REL_DIR)
					put(MediaStore.Video.Media.IS_PENDING, 1)
				}
				val resolver = context.contentResolver
				val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
				val uri = resolver.insert(collection, values)
					?: throw IllegalStateException("MediaStore insert failed")
				val pfd = resolver.openFileDescriptor(uri, "w")
					?: throw IllegalStateException("openFileDescriptor failed")
				MediaStoreOutput(context, displayName, uri, null, pfd)
			} else {
				@Suppress("DEPRECATION")
				val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
				if (!dir.exists()) dir.mkdirs()
				val file = File(dir, displayName)
				val pfd = ParcelFileDescriptor.open(
					file,
					ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
				)
				MediaStoreOutput(context, displayName, null, file, pfd)
			}
		}

		/** List the recordings in the output directory as JSON-ready maps, newest first. */
		fun list(context: Context): List<Map<String, Any>> {
			val out = mutableListOf<Map<String, Any>>()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
				val projection = arrayOf(
					MediaStore.Video.Media._ID,
					MediaStore.Video.Media.DISPLAY_NAME,
					MediaStore.Video.Media.SIZE,
					MediaStore.Video.Media.DURATION,
					MediaStore.Video.Media.DATE_ADDED
				)
				val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
				val args = arrayOf("%$SUBDIR%")
				val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
				context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
					val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
					val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
					val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
					val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
					val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
					while (c.moveToNext()) {
						val id = c.getLong(idCol)
						val uri = android.content.ContentUris.withAppendedId(collection, id)
						out.add(
							mapOf(
								"uri" to uri.toString(),
								"path" to uri.toString(),
								"name" to (c.getString(nameCol) ?: ""),
								"sizeBytes" to c.getLong(sizeCol),
								"durationMs" to c.getLong(durCol),
								"createdAtMs" to c.getLong(dateCol) * 1000L
							)
						)
					}
				}
			} else {
				@Suppress("DEPRECATION")
				val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
				dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
					?.sortedByDescending { it.lastModified() }
					?.forEach { f ->
						out.add(
							mapOf(
								"uri" to Uri.fromFile(f).toString(),
								"path" to f.absolutePath,
								"name" to f.name,
								"sizeBytes" to f.length(),
								"durationMs" to 0L,
								"createdAtMs" to f.lastModified()
							)
						)
					}
			}
			return out
		}

		fun delete(context: Context, uriOrPath: String): Boolean {
			return try {
				if (uriOrPath.startsWith("content://")) {
					context.contentResolver.delete(Uri.parse(uriOrPath), null, null) > 0
				} else {
					File(Uri.parse(uriOrPath).path ?: uriOrPath).delete()
				}
			} catch (_: Throwable) { false }
		}
	}
}
