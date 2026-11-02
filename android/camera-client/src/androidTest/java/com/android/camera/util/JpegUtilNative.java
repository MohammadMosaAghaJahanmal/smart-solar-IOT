package com.android.camera.util;

/** Exact stock JNI entry point, used only by the inspected-guest diagnostic. */
public final class JpegUtilNative {
    private JpegUtilNative() {}

    public static native int compressJpegFromYUV420pNative(
        int width, int height,
        Object y, int yPixelStride, int yRowStride,
        Object u, int uPixelStride, int uRowStride,
        Object v, int vPixelStride, int vRowStride,
        Object output, int outputCapacity, int quality,
        int cropLeft, int cropTop, int cropRight, int cropBottom, int rotationQuarterTurns);
}
