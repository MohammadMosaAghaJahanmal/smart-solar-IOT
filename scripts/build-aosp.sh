#!/usr/bin/env bash
set -euo pipefail
: "${AOSP_ROOT:?Set AOSP_ROOT to an android-16.0.0_r1 checkout}"
: "${CONTROLLER_CERT_SHA256:?Set the UI signing certificate SHA-256}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || { echo 'Use a Linux x86_64 AOSP build host'; exit 1; }
[[ -f "$AOSP_ROOT/build/envsetup.sh" ]] || { echo 'A full pinned AOSP checkout is required'; exit 1; }
free_kib=$(df -Pk "$AOSP_ROOT" | awk 'END {print $4}')
(( free_kib >= 150 * 1024 * 1024 )) || { echo 'At least 150 GiB free build space is required after checkout (400 GB for checkout + build)'; exit 1; }
if grep -qi microsoft /proc/version; then
  echo 'WSL: also check physical free space on the Windows volume backing this VHD; df reports virtual capacity.'
fi
python3 "$repo_root/scripts/integrate-aosp.py" "$AOSP_ROOT" --cert-sha256 "$CONTROLLER_CERT_SHA256" --apply
cd "$AOSP_ROOT"
# shellcheck disable=SC1091
source build/envsetup.sh
lunch vcam_cf_x86_64_phone-trunk_staging-userdebug
m -j"${BUILD_JOBS:-8}" VirtualCameraProBroker VirtualCameraProSystemTests virtual_camera libcameraservice
m -j"${BUILD_JOBS:-8}"
