The provider is AOSP's `frameworks/av/services/camera/virtualcamera`, pinned to
`android-16.0.0_r1`. It implements the AIDL Camera HAL3 device and provider, handles
YUV and BLOB/JPEG outputs, fences and camera metadata. It is linked by Soong as
`virtual_camera`; a second hand-written HAL would duplicate this implementation.
The integration script enables its discovery, and the broker registers a camera
for device 0 rather than a VirtualDeviceManager secondary display.
