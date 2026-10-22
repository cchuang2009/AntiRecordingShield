package com.antirecording.shield

/**
 * Flags Bluetooth / Wi-Fi names that look like a camera or recorder (webcam, IP camera, dash/body/action cam, spy cam,
 * DVR/NVR, voice recorder ...).  A name match is only a HINT: many hidden cameras advertise nothing, and ordinary
 * devices can have such names (e.g. a phone called "Camera remote").  Extend [KEYWORDS] for your region.
 */
object SuspectDetector {
    private val KEYWORDS = listOf(
        "cam", "camera", "webcam", "ipc", "ipcam", "ip[ _-]?camera", "dvr", "nvr", "cctv", "spy", "pinhole",
        "mini[ _-]?cam", "dash[ _-]?cam", "body[ _-]?cam", "action[ _-]?cam", "gopro", "insta360",
        "recorder", "voice[ _-]?rec", "v380", "icsee", "xmeye", "yoosee", "ezviz", "hikvision", "hikam",
        "reolink", "wyze", "tapo[ _-]?c", "imou", "arlo", "eufycam", "nest[ _-]?cam"
    )
    // keyword must not be glued to other letters on either side ("cam" matches CAM_01 / My Cam, not "camp" or "scam")
    private val rx = Regex("(?<![a-z])(" + KEYWORDS.joinToString("|") + ")(?![a-z])", RegexOption.IGNORE_CASE)

    /** @return the matching part of the name, or null when the name looks ordinary. */
    fun matchName(name: String?): String? = name?.takeIf { it.isNotBlank() }?.let { rx.find(it)?.value }
}
