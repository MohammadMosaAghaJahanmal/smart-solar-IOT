The exact VMOS Android 9 ARM64 ROM10075 stock camera loses chroma when its preview
is 1920×1080 and its software-JPEG ImageReader still target is 1920×1088.

Measured evidence:

* `artifacts/vmos/ocr-stock-preview-streams.txt`: repeating SurfaceTexture preview
  1920×1080, one-shot ImageReader still 1920×1088.
* `artifacts/vmos/ocr-jni-boundary.log`: the actual stock JPEG JNI receives valid
  Y and two adjacent NV21 U/V direct buffers, row stride 1920, pixel stride 2;
  both chroma planes have sample min/max/mean zero before JPEG compression.
* `artifacts/vmos/multistream-before/.../metrics.json`: the independent matching
  preview-only repeat followed by YUV still reproduces all-zero chroma and neon
  green. The isolated 1920×1088 YUV stream and native/JNI encoders have normal color.

`QemuSensor::captureNV21` routes this same-width changed-height request through
`captureNV21Wapper` instead of restarting the source. Its
`CameraQemuClient::queryFrameNV21Wapper` successfully scales an original NV21 frame
into a newly allocated target buffer, then incorrectly uses the original reply
length to validate copies from the scaled buffer. It copies Y and rejects UV:

```
source NV21 bytes       = 1920 * 1080 * 3 / 2 = 3110400
scaled Y bytes         = 1920 * 1088         = 2088960
scaled UV bytes        = scaled Y / 2        = 1044480
wrong available UV     = 3110400 - 2088960    = 1021440
correct available UV   = target allocation - Y = 1044480
```

The three-instruction candidate derives availability from the scaled allocation:

| Virtual address | File offset | Original | Candidate |
|---|---|---|---|
| `0x4b2e0` | `0x452e0` | `ldr x4,[sp,#0x78]` | `add x4,x24,x25` |
| `0x4b338` | `0x45338` | `ldr x8,[sp,#0x78]` | `add x8,x13,x27` |
| `0x4b398` | `0x45398` | `ldr x8,[sp,#0x78]` | `add x8,x13,x27` |

All three instructions are reached after the unchanged scaler call at `0x4b2d8`.
The target allocation at `0x4b2b4` is `x24 + x25`, target UV plus target Y. Its
pointer is preserved in `x28`. Y copies from `x28`; successful Y copy restores
`x13` from the saved target-Y size; `x27` holds target UV size. Both interleaved
and planar chroma copy branches read from the scaled buffer after that offset.
The original comparisons, failure branches, copies, source geometry, format,
socket path, gralloc validation and timestamp behavior remain unchanged.

This changes output bounds only. The existing scaler's input handling and
unchecked allocation/libyuv returns remain unchanged. The inspected camera caller
supplies positive even dimensions and all Y/U/V buffers. This is not support for
additional formats, malformed buffer contracts or another VMOS build.

`scripts/prepare-vmos-nv21-scale-repair.py` requires the complete exact source
hash, ELF ABI and surrounding instructions; refuses existing different evidence;
and prepares two separate profiles without changing canonical app assets:

| Exact source | Repaired candidate, 2109016 bytes |
|---|---|
| Original `362261e9…1ec6d3c` | `fa79aee82358025a8cb3f776a511417184d39eb45716170f639aa21581a30cf3` |
| Socket redirect `1c80cb97…73a97` | `02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a` |

Artifacts are under `artifacts/vmos/nv21-scale-repair/`, named
`camera.vphw90.original.nv21-scale.so` and
`camera.vphw90.transport.nv21-scale.so`. Original recovery remains SHA `362261e9…`;
do not relabel the repaired original as the true recovery original.

`scripts/vmos-guest-nv21-scale-repair.sh inspect|install|restore [candidate]`
is restricted to the exact guest, guest UID0 and either exact source/repair pair.
The default candidate is `/data/local/tmp/vcam-nv21-scale-camera.so`. It preserves
mode 0777, UID/GID and SELinux context, keeps an exact full-source-hash backup,
journals before replacement, restarts only the pinned guest provider and checks
its new PID. Unknown bytes, symlinks, hardlinks, metadata and recovery records are
preserved and cause refusal. Failed restart rolls back recognized bytes and
retains the recovery journal. The broker installation record is not edited by
this diagnostic installer; app integration requires a separate explicit profile.

Offline verification completed:

* 5 exact binary patch-scope, hash-refusal, preservation and bounds tests.
* 20 actual-artifact installer identity/metadata/publication/restart/rollback tests.
* 20 actual ARM64 post-scale copy paths executed under Unicorn: original failure
  reproduced as successful Y copy, UV untouched and EINVAL; repaired NV21/planar,
  tight/padded, identity/up/down/mixed geometries copy independent varying-color
  bytes exactly, with unchanged input, canaries and timestamp behavior.

The emulation supplies the completed scaled image and stubs only memcpy/free/log
external calls; it does not emulate libyuv or Android gralloc. Its evidence is
`artifacts/vmos/nv21-scale-repair/arm64-copy-emulation.json`.

Device acceptance subsequently passed through the broker-managed installation:
the matching independent still test changed from U/V means 0/0 to
125.496/131.872, all six stock JNI JPEGs passed, and the actual stock shutter and
OCR-saved photo retained normal colors. The temporary stock JNI observer was
removed afterward. The standalone maintenance installer remains offline-verified;
it was not used to duplicate the successful broker installation. Exact evidence
and the frozen tested APK are pinned in
`artifacts/vmos/green-fix-acceptance.json`. This result covers the measured guest
and reproduced still path, not arbitrary phones or Android versions.
