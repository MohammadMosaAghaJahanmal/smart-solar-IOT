This temporary observer diagnoses the stock VMOS Camera2 software JPEG path. It
does not repair color, transform pixels, or change the camera HAL or broker.

The inspected Android 9 ARM64 ROM10075 original is
`/system/lib64/libjni_jpegutil.so`, 465864 bytes, SHA-256
`6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5`.
The installer preserves it at
`/system/lib64/.vcam-original-libjni_jpegutil.so`, including its original mode,
owner, group and SELinux label. The observer checks its identity and hash before
and after `dlopen`, and checks that the resolved entry belongs to that exact path.
Its unique SONAME avoids aliasing the original library.

Both original Java JNI entry points are forwarded. The original has no
`JNI_OnLoad` or `JNI_OnUnload`; neither does the observer. The original's libjpeg
and C++ implementation exports are not exposed by this observer. Actual stock
Camera2 VDEX inspection found only the JPEG JNI declaration; this is a temporary
diagnostic replacement for that application, not a general replacement library.

`VcamJpegJni` logs the actual JPEG width, height, quality, crop, quarter-turn
rotation, three plane pixel/row strides and direct capacities, output capacity,
adjacent UV pointer relation, and aggregate statistics from 64 bounded samples
per plane. It logs at most 256 JPEG calls per process. A mean value of 125000 is
a sample mean of 125. No source pixel sequences or media are logged. Invalid
observer bounds skip sampling; original arguments and return values still pass
unchanged. Observation does not prove the Java buffer lifetime outside the call.

Build with `pwsh -File scripts/build-vmos-jpegutil-diagnostic.ps1`.
The frozen artifact is `artifacts/vmos/jpegutil-diagnostic/libjni_jpegutil.so`,
540232 bytes, SHA-256
`938d8afa8b6daa6e4d5942cc4ef10237e506623ea35e97bf9a0b21a65122d9e6`.
The exact inspected guest presents stock libraries as mode 0777; the observer
accepts that specific measured exception while retaining all identity/hash checks.
Other group/other writable modes remain rejected.
Identity properties use the Android O property read callback API with a 256-byte
bound; the measured 93-character read-only fingerprint exceeds the old property
API limit. Rejection logs contain only a reason code, never property contents.

The device owner must run the guarded script only inside the measured guest as
UID 0, after staging that artifact at
`/data/local/tmp/vcam-jpegutil-diagnostic.so` with the same metadata as the original:

```
sh /data/local/tmp/vmos-guest-jpegutil-diagnostic.sh inspect
sh /data/local/tmp/vmos-guest-jpegutil-diagnostic.sh install
# Launch the stock camera and reproduce the shutter result; collect VcamJpegJni.
sh /data/local/tmp/vmos-guest-jpegutil-diagnostic.sh restore
```

Installation and restoration force-stop only `com.android.camera2`; they do not
restart a provider or launch a UI. A strict 0600 journal records recovery state.
Unknown files or metadata cause refusal and are preserved. The verified original
backup remains after restoration.

Offline validation: `native/vmos-jpegutil-diagnostic/test-native.py` verifies every
JNI argument, object and return/error, both exports, bounded padded/cropped plane
sampling, invalid-capacity skips, no input/output mutation, aggregate log values,
and hash refusal with a fake native encoder. Run it under Linux with the local
NDK JNI header path. `tests/test_vmos_guest_jpegutil_diagnostic.py` covers 16
filesystem identity, publication, rollback and strict metadata cases using the
actual frozen original and candidate artifacts. Device acceptance remains a
separate required observation.
