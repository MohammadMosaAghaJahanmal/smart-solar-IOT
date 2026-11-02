package com.virtualcamerapro.engine

import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.File

/** ImageDecoder applies EXIF orientation. Decode size is bounded before allocation. */
class ImageFrameSource(file: File) : AutoCloseable {
    val texture: Int
    val width: Int
    val height: Int
    init {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val ratio = minOf(1.0, 1920.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
        }
        width = bitmap.width; height = bitmap.height
        val names = IntArray(1); GLES20.glGenTextures(1, names, 0); texture = names[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        FrameRenderer.textureParameters(GLES20.GL_TEXTURE_2D)
        try { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) }
        catch(e: Exception) { GLES20.glDeleteTextures(1,intArrayOf(texture),0); throw e }
        finally { bitmap.recycle() }
    }
    override fun close() { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
}
