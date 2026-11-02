#!/system/bin/sh
# Exact VMOS alpha13 / ROM10075 JPEG-only compatibility shim. Guest UID 0 only.
# This utility never changes the ARM64 helper, importer, or camera socket redirect.
# Scope: JPEG YUV range only; consumer color/Camera1 acceptance remains pending.
# Recovery journal: /data/local/tmp/vcam-jpeg-color-v1.txt. If the provider has
# stopped, cold restart the VMOS guest and rerun restore. Preserve the journal.
set -eu
umask 077
PATH=/system/bin:/system/xbin
export PATH

action=${1:-inspect}
case "$action" in inspect|install|restore) ;; *) echo 'usage: vmos-guest-jpeg-color.sh inspect|install|restore [candidate]' >&2; exit 2;; esac
candidate=${2:-/data/local/tmp/vcam-jpeg-color.so}
target=/vendor/lib/hw/camera.vphw90.jpeg.so
helper=/vendor/lib64/hw/camera.vphw90.jpeg.so
importer=/vendor/lib64/camera.device@3.2-impl.so
camera=/vendor/lib64/hw/camera.vphw90.so
provider=/vendor/bin/hw/android.hardware.camera.provider@2.4-service
record=/data/local/tmp/vcam-jpeg-color-v1.txt
lock=/vendor/lib/hw/.vcam-jpeg-color-lock
journal_lock=/data/local/tmp/.vcam-jpeg-color-journal-lock
shim_stage=$lock/shim.tmp
link_stage=$lock/original-link.tmp
record_stage=$journal_lock/journal.tmp
shim_hash=f11711b80bfb312cc9477303ed6539dd74b8c801d1b6a40adc448f4cab2ddc5a
helper_hash=0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3
importer_hash=b572750c30143af55a8b8ec4bc1a60017cac3ffec512deb5edffb054877817ab
camera_original=362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c
camera_redirect=1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97
provider_hash=fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87
locked=0
journal_locked=0
shim_staged=0
link_staged=0
record_staged=0
changed=0

fail() { echo "VMOS_JPEG_COLOR_REFUSED: $*" >&2; exit 1; }
digest() { sha256sum "$1" | cut -d' ' -f1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
known() { regular "$1" && [ "$(digest "$1")" = "$2" ]; }
owned_file() { regular "$1" && [ "$(stat -c %u "$1")" = 0 ] && [ "$(stat -c %h "$1")" = 1 ]; }
directory() { [ -d "$1" ] && [ ! -L "$1" ]; }
attributes() { stat -c '%a:%u:%g' "$1"; }
original_link() { [ -L "$target" ] && [ "$(readlink "$target")" = "$helper" ]; }
installed_shim() { known "$target" "$shim_hash" && owned_file "$target" && [ "$(attributes "$target")" = 755:0:0 ]; }
elf64_arm64() {
    [ "$(od -An -v -tx1 -N6 "$1" | tr -d ' \n')" = 7f454c460201 ] &&
    [ "$(od -An -v -tx1 -j16 -N4 "$1" | tr -d ' \n')" = 0300b700 ]
}
journal_text() {
    printf 'VCAM_JPEG_COLOR_1\nshim_sha256=%s\noriginal_helper_sha256=%s\nprior_link=%s\ntarget=%s\n' "$shim_hash" "$helper_hash" "$helper" "$target"
}
journal_known() {
    owned_file "$record" && [ "$(attributes "$record")" = 600:0:0 ] &&
    [ "$(digest "$record")" = "$(journal_text | sha256sum | cut -d' ' -f1)" ]
}
require_guest() {
    [ "$(id -u)" = 0 ] || fail 'Run under independently verified guest UID 0.'
    [ "$(getprop ro.hardware)" = vphw90 ] || fail 'Expected vphw90 guest.'
    [ "$(getprop ro.build.version.sdk)" = 28 ] || fail 'Expected API 28 guest.'
    [ "$(getprop ro.product.cpu.abilist)" = arm64-v8a ] || fail 'Expected ARM64-only guest.'
    case "$(getprop ro.build.fingerprint)" in
        *:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys) ;;
        *) fail 'The guest framework build is not the inspected ROM10075 build.';;
    esac
    [ "$(getenforce)" = Disabled ] || fail 'Only the inspected SELinux-disabled guest is supported.'
    case "$shim_hash" in *[!a-f0-9]*|'') fail 'This utility has no pinned release shim.';; esac
    [ "${#shim_hash}" = 64 ] || fail 'Invalid pinned release shim hash.'
    for folder in /vendor /vendor/lib /vendor/lib/hw /vendor/lib64 /vendor/lib64/hw /vendor/bin /vendor/bin/hw /data /data/local /data/local/tmp; do
        directory "$folder" || fail "Expected a real guest directory: $folder"
    done
    known "$provider" "$provider_hash" || fail 'Camera provider executable differs from the measured build.'
    known "$helper" "$helper_hash" || fail 'Original ARM64 JPEG helper differs from the measured build.'
    known "$importer" "$importer_hash" || fail 'The exact measured JPEG importer repair is required first.'
    regular "$camera" || fail 'The guest camera HAL is not a regular file.'
    camera_hash=$(digest "$camera")
    case "$camera_hash" in "$camera_original"|"$camera_redirect") ;;
        *) fail 'Guest camera HAL is neither the measured original nor the exact broker redirect.';;
    esac
}
provider_pid() {
    known "$provider" "$provider_hash" || return 1
    [ "$(getprop init.svc.vendor.camera-provider-2-4)" = running ] || return 1
    pids=$(pidof android.hardware.camera.provider@2.4-service) || return 1
    case "$pids" in ''|*[!0-9]*) return 1;; esac
    [ "$pids" -gt 1 ] || return 1
    [ "$(tr '\000' '\n' < "/proc/$pids/cmdline" | head -1)" = "$provider" ] || return 1
    printf '%s' "$pids"
}
restart_provider() {
    before=$(provider_pid) || return 1
    setprop ctl.restart vendor.camera-provider-2-4 || return 1
    attempts=0
    while [ "$attempts" -lt 15 ]; do
        after=$(provider_pid 2>/dev/null || true)
        if [ -n "$after" ] && [ "$after" != "$before" ]; then
            echo "GUEST_CAMERA_PROVIDER_RESTARTED before=$before after=$after"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    return 1
}
stage_original_link() {
    [ ! -e "$link_stage" ] && [ ! -L "$link_stage" ] || return 1
    link_staged=1
    ln -s "$helper" "$link_stage" || return 1
    [ "$(readlink "$link_stage")" = "$helper" ]
}
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$result" -ne 0 ] && [ "$changed" = 1 ]; then
        echo 'Installation did not complete; attempting exact original-link rollback.' >&2
        if installed_shim && known "$helper" "$helper_hash" && journal_known; then
            if stage_original_link && mv "$link_stage" "$target"; then
                link_staged=0
                restart_provider || true
                echo 'Exact original JPEG compatibility link restored; journal retained for verified recovery.' >&2
            fi
        fi
    fi
    if [ "$shim_staged" = 1 ] && owned_file "$shim_stage"; then rm "$shim_stage"; fi
    if [ "$link_staged" = 1 ] && [ -L "$link_stage" ] && [ "$(readlink "$link_stage")" = "$helper" ]; then rm "$link_stage"; fi
    if [ "$record_staged" = 1 ] && owned_file "$record_stage"; then rm "$record_stage"; fi
    if [ "$journal_locked" = 1 ]; then rmdir "$journal_lock" || true; fi
    if [ "$locked" = 1 ]; then rmdir "$lock" || true; fi
    exit "$result"
}

require_guest
if original_link; then current=original_link
elif installed_shim; then current=jpeg_color_shim
else fail 'Compatibility pathname is neither the exact original symlink nor this exact installed shim.'; fi
if [ -e "$record" ] || [ -L "$record" ]; then journal_known || fail 'Existing JPEG color recovery journal is unrecognized; it was preserved.'; journal=verified
else journal=absent; fi
echo "GUEST_JPEG_COLOR_PROFILE=vmos-android9-arm64-rom10075 scope=jpeg_range_only acceptance=pending current=$current journal=$journal shim=$shim_hash"
if [ "$action" = inspect ]; then exit 0; fi

mkdir "$lock" 2>/dev/null || fail 'Another color operation or a preserved recovery lock exists.'
locked=1
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[ "$(attributes "$lock")" = 700:0:0 ] || fail 'Private staging directory ownership or permissions differ.'
mkdir "$journal_lock" 2>/dev/null || fail 'Another journal operation or a preserved journal staging lock exists.'
journal_locked=1
[ "$(attributes "$journal_lock")" = 700:0:0 ] || fail 'Private journal staging ownership or permissions differ.'

if [ "$action" = install ]; then
    original_link || fail 'Installation requires the exact measured JPEG compatibility symlink.'
    [ "$journal" = absent ] || fail 'Finish the existing recovery journal before another installation.'
    known "$candidate" "$shim_hash" && elf64_arm64 "$candidate" || fail 'Candidate is not the exact pinned ARM64 JPEG color shim.'
    [ ! -e "$shim_stage" ] && [ ! -L "$shim_stage" ] || fail 'Private shim staging pathname is occupied.'
    (set -C; : > "$shim_stage") || fail 'Could not exclusively create the private staged shim.'
    shim_staged=1
    cat "$candidate" > "$shim_stage"
    chmod 755 "$shim_stage"
    chown 0:0 "$shim_stage"
    known "$shim_stage" "$shim_hash" && elf64_arm64 "$shim_stage" && owned_file "$shim_stage" && [ "$(attributes "$shim_stage")" = 755:0:0 ] || fail 'Staged shim read-back or attributes differ.'
    [ ! -e "$record_stage" ] && [ ! -L "$record_stage" ] || fail 'Private journal staging pathname is occupied.'
    (set -C; : > "$record_stage") || fail 'Could not exclusively create the private staged journal.'
    record_staged=1
    journal_text > "$record_stage"
    chmod 600 "$record_stage"
    chown 0:0 "$record_stage"
    [ ! -e "$record" ] && [ ! -L "$record" ] || fail 'A recovery journal appeared during staging.'
    mv "$record_stage" "$record"
    record_staged=0
    journal_known || fail 'Published recovery journal read-back failed.'
    original_link || fail 'Original JPEG compatibility symlink changed during staging.'
    changed=1
    mv "$shim_stage" "$target"
    shim_staged=0
    installed_shim || fail 'Installed JPEG color shim read-back failed.'
    restart_provider || fail 'JPEG color shim provider restart failed; rollback is required.'
    known "$helper" "$helper_hash" && installed_shim && journal_known || fail 'Post-restart helper, shim, or recovery journal changed.'
    changed=0
    echo 'GUEST_JPEG_COLOR_INSTALLED; JPEG range-only shim; actual consumer color acceptance is still required.'
else
    [ "$journal" = verified ] && journal_known || fail 'A verified exact JPEG color recovery journal is required.'
    archive=$record.restored.$$.txt
    [ ! -e "$archive" ] && [ ! -L "$archive" ] || fail 'Recovery journal archive pathname is occupied.'
    if installed_shim; then
        stage_original_link || fail 'Could not stage the exact original JPEG compatibility link.'
        installed_shim || fail 'Installed shim changed during restoration staging.'
        mv "$link_stage" "$target"
        link_staged=0
    else original_link || fail 'Unknown compatibility pathname; restoration refused.'; fi
    original_link && known "$helper" "$helper_hash" || fail 'Original JPEG helper link restoration read-back failed.'
    restart_provider || fail 'Original helper provider restart failed; exact link and journal are retained. If the provider stopped, cold restart the VMOS guest, then rerun restore.'
    original_link && known "$helper" "$helper_hash" && journal_known || fail 'Post-restart original helper or recovery journal changed.'
    [ ! -e "$archive" ] && [ ! -L "$archive" ] || fail 'Recovery archive pathname became occupied.'
    mv "$record" "$archive"
    echo "GUEST_JPEG_COLOR_RESTORED; exact original JPEG compatibility link verified; journal=$archive"
fi
