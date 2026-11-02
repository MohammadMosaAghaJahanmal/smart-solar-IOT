# VMOS JPEG range forwarding shim

**The frozen `f11711b8...` candidate failed guest acceptance and was restored to
the original compatibility symlink. Do not install it.** Four independent
Camera2/CameraX JPEG checks failed while it was present and passed immediately
after restoration. It emitted no `VcamJpegColor` initialization logs.

Source inspection found an exact-build loader defect: the guest fingerprint is
93 bytes, longer than `PROP_VALUE_MAX` (92). Android 9 Bionic's legacy
`__system_property_get` supplies a compatibility value for a long read-only
property, so the fingerprint suffix guard can silently reject before the old
first log. The source now uses `__system_property_find` plus
`__system_property_read_callback`, retains the exact suffix guard, and records
bounded loader rejection stages. This explains a concrete possible early reject;
the corrected candidate still needs direct guest validation. The separate
development artifact `camera.vphw90.jpeg.diagnostic.so` is 597304 bytes,
SHA-256 `66e3fb347b8fd25e712b077f9129767e340433ff62b697a0f9bbb028fe7581e1`.
The frozen release binary, manifest and installer pins remain unchanged.

This candidate attempts to correct one measured defect in the Android 9 ARM64 ROM10075
JPEG encoder. The proxy supplies limited-range BT.601 NV21, which the inspected
encoder passes unchanged to its full-range JFIF raw-data compressor. Existing
device captures reproduce the defect exactly: solid red becomes `(239,15,15)`
and blue becomes `(16,15,239)`. Preview expects limited range and must retain it.

The separate ARM64 library replaces only the previously created compatibility
symlink `/vendor/lib/hw/camera.vphw90.jpeg.so`. It loads the unchanged original
`/vendor/lib64/hw/camera.vphw90.jpeg.so`, verifies its SHA-256 and ELF identity,
copies tight NV21 into a bounded temporary buffer, expands sample range only at
the JPEG entry point, then calls the original encoder. It changes neither proxy,
broker, preview conversion, camera socket redirect nor original lib64 encoder.
Its distinct SONAME is `libvcam_jpeg_color.so`.

This range correction does **not** establish a fix for the user's separate,
severely green stock Camera2 software JPEG/OCR capture. Missing chroma or an incorrect buffer layout
cannot be recovered through range expansion. Source/result evidence is retained
in `artifacts/vmos/color-fidelity-audit.json`; that consumer path requires its own
device diagnosis and acceptance.

## Exact ABI and scope

The five exports follow the original one-pointer `JpegStub` ABI:

```c
void JpegStub_init(JpegStub *stub);
void JpegStub_cleanup(JpegStub *stub);
int JpegStub_compress(JpegStub *stub, const void *nv21,
                      int width, int height, int quality, ExifData *exif);
void JpegStub_getCompressedImage(JpegStub *stub, void *output);
size_t JpegStub_getCompressedSize(JpegStub *stub);
```

Signatures agree with the exact runtime's disassembly in
`artifacts/vmos/research/jpeg-helper.disassembly.txt` (`init` VA `0x1bdc`,
`compress` `0x1c4c`, `copy` `0x1d18`, 64-bit size return `0x1d60`) and the
[AOSP JpegStub implementation](https://android.googlesource.com/device/generic/goldfish/+/a11bd58ddc5ce90e7bc66e1cf9c6ed0bad61c376/camera/jpeg-stub/JpegStub.cpp).
The range distinction is described by Android's
[data-space definitions](https://developer.android.com/ndk/reference/group/a-data-space):
JFIF uses full-range Y/Cb/Cr, unlike limited-range television YUV.

The production loader accepts only API 28, `vphw90`, ARM64-only and the inspected
framework-build fingerprint suffix. The original encoder must remain a regular
68120-byte ARM64 ELF with SHA-256
`0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3`.
There is no environment-variable path override or fallback to an unknown encoder.

Dimensions must be positive, even, and at most 4096 per axis. Input conversion
and accepted compressed output are each capped at 32 MiB. Input pixels remain
unchanged; luma and interleaved V/U are copied without swapping channels.
Luma maps `16..235` to `0..255`; chroma expands around exact neutral `128` with
symmetric signed rounding. Allocation failure returns `ENOMEM`, invalid input
returns an error, and a failed compression exposes size zero so an old JPEG
cannot be copied as a new result. Quality and the opaque EXIF pointer are
forwarded without alteration.

The original ABI supplies **no buffer lengths or stride**. The verified caller
must provide tightly packed NV21 and output capacity at least equal to the
reported compressed size. The shim cannot validate the actual caller allocation
or infer padding from width/height. Its conversion helper has explicit
stride/length arguments for CPU testing; production always uses `stride=width`.

## Build and verify

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/build-vmos-jpeg-color.ps1
```

The result is `artifacts/vmos/jpeg-color/camera.vphw90.jpeg.so`, accompanied by
`shim.json`. The current candidate is 595888 bytes, SHA-256
`f11711b80bfb312cc9477303ed6539dd74b8c801d1b6a40adc448f4cab2ddc5a`.
The install utility intentionally pins this exact candidate; rebuilding a changed
source requires a fresh audit, test run and explicit pin update.

Linux/WSL CPU checks:

```sh
g++ -std=c++17 -O2 -Wall -Wextra -Werror native/vmos-jpeg-color/test-range.cpp -o /tmp/vcam-jpeg-range-test
/tmp/vcam-jpeg-range-test artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.jpeg.so
python3 native/vmos-jpeg-color/test-forwarding.py
python3 native/vmos-jpeg-color/test-guest-identity.py
python3 -m unittest discover -s tests -p 'test_vmos_guest_jpeg_color.py' -v
```

The conversion tests cover a 4096-point RGB palette against an independent JFIF
inverse, with worst pre-compression channel error at most 3; neutral grays remain
neutral. They also cover SHA-256 vectors/the exact helper, stride padding,
unchanged input/guard bytes, odd/invalid dimensions, short buffers, overlap,
overflow and memory limits. The forwarding test uses a separately hash-pinned
fake encoder to verify ABI/EXIF/quality, allocation and encoder failures, output
bounds, no stale result, runtime hash refusal and eight concurrent stub instances.
It does not substitute for the real ARM64 encoder's device JPEG test.
The identity regression compiles the production function against property API
fixtures that reproduce the measured 93-byte fingerprint and the legacy API's
compatibility value. It also checks rejection of a changed suffix, missing
property, physical hardware, another SDK and a different ABI list.

Twenty-two isolated filesystem tests exercise the actual release artifact's
install/restore guards and recovery. No CPU test accesses a phone.

## Guest maintenance and rollback

After independently verifying guest UID 0, the original helper/importer/provider
hashes, and an idle camera pipeline, transfer the candidate and
`scripts/vmos-guest-jpeg-color.sh` into the guest. The tool obtains no root and
invokes no ADB:

```sh
sh /data/local/tmp/vmos-guest-jpeg-color.sh inspect
sh /data/local/tmp/vmos-guest-jpeg-color.sh install /data/local/tmp/vcam-jpeg-color.so
sh /data/local/tmp/vmos-guest-jpeg-color.sh restore
```

Install requires the exact original compatibility symlink and verified candidate,
and records its prior target in `/data/local/tmp/vcam-jpeg-color-v1.txt`. The
original importer repair is required; either the original HAL or the exact broker
socket redirect is allowed. Private staging and journal publication each stay
within their destination filesystem. Restore replaces only this exact installed
shim with the original symlink and archives the validated journal under a unique
name. Unknown files, changed hashes, attributes or journals cause refusal.

A failed install attempts exact-link rollback and keeps its recovery journal.
Provider restart requires the exact running provider PID, executable and command
line. If the provider stopped, restoration can put the exact original link back
but cannot verify a restart from a missing PID: cold-start the guest and rerun
restore to finish validation and archive the journal. Do not discard that journal
or remove unrelated files to bypass refusal.

Device acceptance remains required: source-color chart, actual Camera2/Camera1
JPEGs, unchanged preview colors and original-camera behavior, plus the user's
specific OCR application's saved result. Log tag `VcamJpegColor` records only
input dimensions, quality and bounded Y/U/V summary means to distinguish range
errors from missing Camera1 chroma; it never logs source image bytes.
