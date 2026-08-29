package io.basco.djiaoa

/**
 * DJI drone identifier → human name + camera family.
 *
 * Codes come from DJI's device-type table (~140 entries total). Only the ones we currently differentiate are listed; everything else falls through to
 * UNKNOWN and the UI uses a permissive form (no model-specific validation).
 *
 * If you need a code that isn't here yet, add it with its product string.
 */
enum class CameraFamily { O4_LITE, O4_PRO, UNKNOWN }

data class DroneModel(
	val code: String,
	val displayName: String,
	val camera: CameraFamily
)

object DeviceTable {
	private val byCode: Map<String, DroneModel> = listOf(
		// The base "O4 Air Unit" code is the Lite; the Pro variant gets a 5-suffix.
		DroneModel("ZA530", "DJI O4 Air Unit", CameraFamily.O4_LITE),
		DroneModel("ZA5305", "DJI O4 Air Unit Pro", CameraFamily.O4_PRO),
	).associateBy { it.code }

	fun lookup(code: String?): DroneModel? = code?.let { byCode[it.uppercase()] }
}
