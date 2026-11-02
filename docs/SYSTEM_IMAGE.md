# Reproducible controlled Android image

Target: `android-16.0.0_r1`, Cuttlefish x86_64 phone, `userdebug`, SELinux enforcing.
Use a Linux x86_64 build host with KVM and sufficient AOSP disk/RAM (official
baseline: 400 GB free disk and 64 GB RAM). This Windows workspace has the Android
SDK but no full AOSP checkout and insufficient free disk for that build.

## Fetch Android

Install the dependencies and Repo from the official [AOSP setup guide](https://source.android.com/docs/setup/start/requirements),
then:

```bash
mkdir -p ~/aosp-vcam
cd ~/aosp-vcam
repo init -u https://android.googlesource.com/platform/manifest -b android-16.0.0_r1
repo sync -c -j8
```

Copy/clone this project to the Linux host. Do not copy `node_modules`, `build` or
Gradle caches into the AOSP checkout.

## Controller signing identity

Build `app-debug.apk` with the checked-in standard debug keystore, or provide your
own signing setup. Obtain the signing certificate digest:

```bash
keytool -exportcert -keystore android/app/debug.keystore \
  -alias androiddebugkey -storepass android | sha256sum
```

For the checked-in debug keystore the SHA-256 is:
`FAC61745DC0903786FB9EDE62A962B399F7348F0BB6F899B8332667591033B9C`.
This is a public development certificate, appropriate only for the controlled
test image. The broker itself is signed with that AOSP image's platform key.

## Integrate and build

```bash
export AOSP_ROOT="$HOME/aosp-vcam"
export CONTROLLER_CERT_SHA256=FAC61745DC0903786FB9EDE62A962B399F7348F0BB6F899B8332667591033B9C

# Optional: review only, no filesystem changes in AOSP.
python3 scripts/integrate-aosp.py "$AOSP_ROOT" --cert-sha256 "$CONTROLLER_CERT_SHA256"

# Applies guarded changes, includes the product config, then builds the full image.
bash scripts/build-aosp.sh
```

The integration script requires `frameworks/av` HEAD to be the exact pinned tag.
It refuses incompatible edits and can be rerun on its own installed modifications.
The generated change is also available in
`aosp/camera-routing/frameworks-av.patch` for code review. Do not apply that patch
again if the script already installed it.

Changes include:

- Primary-device routing in CameraService, including Camera1 numeric mapping.
- Virtual HAL discovery independent of the virtual-device flag.
- Controlled system-UID authorization and stable-ID orphan cleanup.
- Platform-signed `VirtualCameraProBroker`, AIDL bindings and native engine.
- Property read/write and virtual camera Binder SELinux permissions.
- Cuttlefish product inclusion of the broker, daemon and integration version.

The dedicated product inherits the pinned x86_64-only Cuttlefish phone target.
It adds the broker, virtual camera daemon, original `Camera2` application,
default broker runtime permissions, boot init action and protocol 2 version marker.
The build script also builds the separate acceptance APK; Soong installs
`android_test` artifacts under `$ANDROID_PRODUCT_OUT/testcases`, so the test runner
installs that APK with `adb install -t` and grants its camera/media permissions.
The installer adds the property type/rules to platform-private SELinux policy;
the CameraService platform domain must be able to read that property. It does
not change the stock Cuttlefish product file.

For another AOSP product, run the integration script with `--apply`, then include:

```make
$(call inherit-product, vendor/virtualcamerapro/build-integration/virtualcamera-pro.mk)
```

Camera provider capabilities and private Camera app extensions must be evaluated
on every physical-device port; the Cuttlefish integration is the baseline, not a
universal flashable image.

## Launch and install

Install Cuttlefish host packages and join the KVM/render/network groups using the
official [Cuttlefish instructions](https://source.android.com/docs/devices/cuttlefish/get-started).
With the matching source tree build environment:

```bash
cd "$AOSP_ROOT"
source build/envsetup.sh
lunch vcam_cf_x86_64_phone-trunk_staging-userdebug
launch_cvd --daemon --gpu_mode=gfxstream
adb wait-for-device
adb shell getprop ro.virtualcamera.pro.version
adb shell service check virtual_camera
adb shell pm path com.virtualcamerapro.broker
adb shell dumpsys media.camera --virtual-camera-pro
```

Use host GPU drivers exposing `GL_EXT_YUV_target`,
`GL_OES_EGL_image_external`, and `GL_OES_EGL_image_external_essl3`. The HAL checks
these and rejects registration if unavailable. Software GL configurations may
not meet this requirement. Keep SELinux enforcing; inspect denial logs and fix
the product policy instead of disabling enforcement.

Expect property `2`, a running `virtual_camera`, a system broker path, and JSON
with `protocol:2`, `initialized:true`, `responsive:true`. With no activation,
`routeInstalled:false` is correct. A stock CameraService dump is evidence that
the framework hook was not installed, regardless of any manually set property.

Install the controller built with the allowed certificate:

```bash
adb install -r /path/to/app-debug.apk
adb reverse tcp:8081 tcp:8081
# Start Metro from the controller project if using the debug build.
adb shell am start -n com.virtualcamerapro/.MainActivity
```

For `app-standalone.apk`, omit Metro and `adb reverse`; the APK contains its bundle.
The original-Camera test handles the pinned Camera2 location/aspect-ratio
first-run controls. Run:

```bash
export ANDROID_SERIAL=YOUR_CUTTLEFISH_SERIAL
bash /path/to/vcamera/scripts/run-system-tests.sh
```

The test APK is platform signed by Soong. Set `SYSTEM_TEST_APK` explicitly if the
build output is on a different host than ADB. Its Gradle APK only proves SDK
compilation and cannot control the broker. The broker accepts this exact test
package only with a matching platform signature and `ro.debuggable=1`.

This reference product is not a phone firmware distribution. An OEM port requires
the matching device tree, kernel, vendor HAL/blobs, partition layout, signing keys,
bootloader unlock support and a device-specific recovery/flash procedure. No
compatible OEM port or recovery/flash procedure has been prepared. The connected
Android 10 Infinix findings are in [physical-device support](PHYSICAL_DEVICE_SUPPORT.md).
Do not flash Cuttlefish images onto an OEM phone.

The Gradle-built broker APK is a compilation artifact only. Installing it with
`adb install` does not give it a system UID, platform certificate, property
permissions or a patched camera framework. Use the broker installed by Soong in
the image. Continue with [acceptance tests](TESTING.md).

For a smaller local installation/connectivity test, see
[the emulator reference](EMULATOR_REFERENCE.md). It installs the production broker
with the matching public AOSP platform key on the SDK's AOSP API 36 image and
tests Binder communication. It does not install this CameraService patch.
