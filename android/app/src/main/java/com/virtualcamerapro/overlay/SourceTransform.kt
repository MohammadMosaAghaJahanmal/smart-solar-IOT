package com.virtualcamerapro.overlay

/** The renderer's bounded source transform; positive pan follows a finger. */
data class SourceTransform(val zoom: Double, val panX: Double, val panY: Double) {
    fun bounded() = SourceTransform(zoom.coerceIn(1.0, 8.0), panX.coerceIn(-1.0, 1.0), panY.coerceIn(-1.0, 1.0))
    fun scale(factor: Double): SourceTransform =
        if (!factor.isFinite() || factor <= 0.0) this else copy(zoom = zoom * factor).bounded()
    fun move(deltaX: Double, deltaY: Double): SourceTransform =
        if (!deltaX.isFinite() || !deltaY.isFinite()) this else copy(panX = panX + deltaX, panY = panY + deltaY).bounded()

    companion object { val ORIGINAL = SourceTransform(1.0, 0.0, 0.0) }
}
