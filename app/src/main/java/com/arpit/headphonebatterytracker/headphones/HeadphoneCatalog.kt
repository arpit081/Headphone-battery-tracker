package com.arpit.headphonebatterytracker.headphones

/**
 * A product photo in app/src/main/assets. Cup positions are fractions of the image size, measured on
 * the photo, so the mode waves line up with the ear cups.
 */
data class PhotoSpec(
    val assetPath: String,
    val leftCup: Pair<Float, Float>,
    /** Half the left cup's width as a fraction of the image width. */
    val leftCupRadius: Float,
    val rightCup: Pair<Float, Float>,
    val rightCupRadius: Float,
)

/** Which drawing the home screen uses for a model. */
enum class Illustration { GENERIC, OVER_EAR }

/**
 * A headphone model the app knows about.
 * [claimedHours] holds the manufacturer's battery claim per mode; a missing mode means
 * there is no official figure (see missingdata.md).
 */
data class HeadphoneModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val nameMatches: List<String>,
    val claimedHours: Map<TrackingMode, Double>,
    val claimSource: String,
    /** True when the app can read live battery and ANC/Ambient mode over Sony's control channel. */
    val supportsSonyProtocol: Boolean = false,
    val illustration: Illustration = Illustration.GENERIC,
    /** Real photo shown on the home screen when the file exists; otherwise [illustration] is drawn. */
    val photo: PhotoSpec? = null,
)

object HeadphoneCatalog {
    val models: List<HeadphoneModel> = listOf(
        HeadphoneModel(
            id = "sony_wh1000xm5",
            displayName = "Sony WH-1000XM5",
            brand = "Sony",
            nameMatches = listOf("WH-1000XM5"),
            claimedHours = mapOf(
                TrackingMode.ANC to 30.0,
                TrackingMode.NORMAL to 40.0,
            ),
            claimSource = "https://www.androidcentral.com/audio/sony-wh-1000xm5-launch",
            supportsSonyProtocol = true,
            illustration = Illustration.OVER_EAR,
            // Three-quarter view; positions measured on photos/xm5.png after trimming to 194×298.
            photo = PhotoSpec(
                assetPath = "headphones/wh1000xm5.png",
                leftCup = 0.19f to 0.72f,
                leftCupRadius = 0.23f,
                rightCup = 0.63f to 0.79f,
                rightCupRadius = 0.36f,
            ),
        ),
    )

    fun match(deviceName: String): HeadphoneModel? =
        models.firstOrNull { model ->
            model.nameMatches.any { deviceName.contains(it, ignoreCase = true) }
        }
}
