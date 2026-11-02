package com.virtualcamerapro.engine

data class OutputSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val loop: Boolean = true,
    val rotation: Int = 0,
    val scaleMode: String = "fit",
    val cameraId: String = "0",
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0
) {
    init {
        require((width == 1280 && height == 720) || (width == 1920 && height == 1080)) { "Use 720p or 1080p" }
        require(rotation in listOf(0, 90, 180, 270)) { "Invalid rotation" }
        require(scaleMode in listOf("fit", "fill")) { "Invalid scale mode" }
        require(cameraId.matches(Regex("[A-Za-z0-9_]{1,32}")) && !cameraId.startsWith("v0_")) { "Invalid physical camera ID" }
        require(zoom.isFinite() && zoom in 1.0..8.0) { "Zoom must be between 1 and 8" }
        require(panX.isFinite() && panY.isFinite() && panX in -1.0..1.0 && panY in -1.0..1.0) { "Pan must be between -1 and 1" }
    }
}
