package com.virtualcamerapro.engine

import kotlin.math.abs
import kotlin.math.ln

/** Adapts an inactive saved selection to actually advertised routes and sizes. */
object OutputCapabilities {
    data class Camera(val id: String, val sizes: List<Pair<Int,Int>>)
    fun resolve(value: OutputSettings, cameras: List<Camera>, bothIds: List<String>, bothSizes: List<Pair<Int,Int>>): OutputSettings {
        require(cameras.isNotEmpty()) { "No mapped camera offers a usable output" }
        val ids = if (value.replacementMode == "both") bothIds else cameras.map { it.id }
        require(ids.isNotEmpty()) { "The detected cameras do not support both-camera replacement" }
        val id = value.cameraId.takeIf { it in ids } ?: ids.first()
        val choices = (if (value.replacementMode == "both") bothSizes else cameras.single { it.id == id }.sizes)
            .filter { (w,h) -> w in 32..4096 && h in 32..4096 && w%2 == 0 && h%2 == 0 && w.toLong()*h <= 8_847_360 }
        require(choices.isNotEmpty()) { "The selected camera has no supported common YUV/JPEG size" }
        val size = choices.firstOrNull { it.first == value.width && it.second == value.height }
            ?: choices.minWith(compareBy<Pair<Int,Int>> {
                abs(ln((it.first.toDouble()/it.second)/(value.width.toDouble()/value.height)))
            }.thenBy { abs(it.first.toLong()*it.second-value.width.toLong()*value.height) }.thenBy { it.first }.thenBy { it.second })
        return value.copy(cameraId=id,width=size.first,height=size.second)
    }
}
