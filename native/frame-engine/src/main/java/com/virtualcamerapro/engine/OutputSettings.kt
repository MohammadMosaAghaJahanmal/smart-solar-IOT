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
    val panY: Double = 0.0,
    val replacementMode: String = "single"
) {
    init {
        require(width in 32..4096 && height in 32..4096 && width % 2 == 0 && height % 2 == 0 &&
            width.toLong() * height <= 8_847_360) { "Use an advertised even camera size within the frame-memory limit" }
        require(rotation in listOf(0, 90, 180, 270)) { "Invalid rotation" }
        require(scaleMode in listOf("fit", "fill")) { "Invalid scale mode" }
        require(cameraId.matches(Regex("[A-Za-z0-9_]{1,32}")) && !cameraId.startsWith("v0_")) { "Invalid physical camera ID" }
        require(replacementMode in listOf("single", "both")) { "Invalid camera replacement mode" }
        require(zoom.isFinite() && zoom in 1.0..8.0) { "Zoom must be between 1 and 8" }
        require(panX.isFinite() && panY.isFinite() && panX in -1.0..1.0 && panY in -1.0..1.0) { "Pan must be between -1 and 1" }
    }
}
