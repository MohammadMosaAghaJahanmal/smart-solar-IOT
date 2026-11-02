The producer uses Kotlin's Android EGL/GLES bindings and MediaCodec. The camera HAL,
YUV conversion, JPEG compression and CameraService routing run in AOSP C++. No
custom JNI hop or per-frame JavaScript/Binder pixel copy is needed. This directory
is compiled into both the APK (local preview) and the platform broker (camera input).
