#!/system/bin/sh
# Exact VMOS alpha13 / ROM10075 guest repair. Never run on a physical Android OS.
# This script does not invoke ADB, obtain root, or redirect camera pixels.
set -eu
umask 077
PATH=/system/bin:/system/xbin
export PATH

action=${1:-inspect}
case "$action" in inspect|install|restore) ;; *) echo 'usage: vmos-guest-jpeg-repair.sh inspect|install|restore [candidate]' >&2; exit 2;; esac
candidate=${2:-/data/local/tmp/vcam-jpeg-stride.so}
target=/vendor/lib64/camera.device@3.2-impl.so
backup=/vendor/lib64/.vcam-camera-device-3.2-original-be8cbff7.so
helper=/vendor/lib64/hw/camera.vphw90.jpeg.so
helper_link=/vendor/lib/hw/camera.vphw90.jpeg.so
record=/data/local/tmp/vcam-jpeg-repair-v1.txt
legacy=/data/local/tmp/vcam-jpeg-repair.json
lock=/vendor/lib64/.vcam-jpeg-repair-lock
temporary=$lock/importer.tmp
record_tmp=$lock/journal.tmp
lock_owner=$lock/owner.txt
original=be8cbff7d20ec7f9e8ea16645ca919196e174911bb7cfcfbcac458acaa8b6acc
patched=b572750c30143af55a8b8ec4bc1a60017cac3ffec512deb5edffb054877817ab
provider=/vendor/bin/hw/android.hardware.camera.provider@2.4-service
provider_hash=fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87
helper_hash=0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3
camera=/vendor/lib64/hw/camera.vphw90.so
camera_hash=362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c
changed=0
link_created=0
lib_created=0
hw_created=0
locked=0
staged=0
record_staged=0
owner_created=0
owner_text=

fail() { echo "VMOS_JPEG_REPAIR_REFUSED: $*" >&2; exit 1; }
digest() { sha256sum "$1" | cut -d' ' -f1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
known() { regular "$1" && [ "$(digest "$1")" = "$2" ]; }
owned_file() { regular "$1" && [ "$(stat -c %u "$1")" = 0 ] && [ "$(stat -c %h "$1")" = 1 ]; }
attributes() { stat -c '%a:%u:%g' "$1"; }
directory() { [ -d "$1" ] && [ ! -L "$1" ]; }
process_start() {
    process_stat=$(cat "/proc/$1/stat") || return 1
    process_stat=${process_stat##*) }
    set -- $process_stat
    [ "$#" -ge 20 ] || return 1
    shift 19
    case "$1" in ''|*[!0-9]*) return 1;; esac
    printf '%s' "$1"
}
acquire_lock() {
    if ! mkdir "$lock" 2>/dev/null; then
        directory "$lock" && [ "$(stat -c %u "$lock")" = 0 ] && [ "$(stat -c %a "$lock")" = 700 ] || fail 'Unknown repair lock directory was preserved.'
        owned_file "$lock_owner" && [ "$(stat -c %s "$lock_owner")" -le 256 ] || fail 'Repair lock has no verified process owner; it was preserved.'
        old_owner=$(cat "$lock_owner")
        [ "$(printf '%s\n' "$old_owner" | wc -l | tr -d ' ')" = 3 ] || fail 'Unknown repair lock owner fields.'
        [ "$(printf '%s\n' "$old_owner" | head -1)" = VCAM_JPEG_LOCK_1 ] || fail 'Unknown repair lock owner version.'
        owner_pid=$(printf '%s\n' "$old_owner" | sed -n '2s/^pid=//p')
        owner_start=$(printf '%s\n' "$old_owner" | sed -n '3s/^start=//p')
        case "$owner_pid:$owner_start" in *[!0-9:]*|:*) fail 'Unknown repair lock process identity.';; esac
        [ "$owner_pid" -gt 1 ] && [ -n "$owner_start" ] || fail 'Invalid repair lock process identity.'
        if [ -d "/proc/$owner_pid" ] || kill -0 "$owner_pid" 2>/dev/null; then
            live_start=$(process_start "$owner_pid") || fail 'Repair lock owner liveness could not be verified.'
            [ "$live_start" != "$owner_start" ] || fail 'Another verified repair operation is still active.'
        fi
        # Only one recovery attempt can inspect/remove this stale lock. Unknown
        # files or an interrupted recovery remain preserved for diagnostics.
        mkdir "$lock/recovery" 2>/dev/null || fail 'Another recovery or an unrecognized recovery marker exists.'
        [ "$(cat "$lock_owner")" = "$old_owner" ] || fail 'Repair lock ownership changed during recovery.'
        [ ! -e "$temporary" ] && [ ! -L "$temporary" ] && [ ! -e "$record_tmp" ] && [ ! -L "$record_tmp" ] || fail 'Interrupted staging files were preserved for inspection.'
        for lock_entry in "$lock"/* "$lock"/.[!.]* "$lock"/..?*; do
            [ -e "$lock_entry" ] || [ -L "$lock_entry" ] || continue
            case "$lock_entry" in "$lock_owner"|"$lock/recovery") ;; *) fail 'Unknown repair lock contents were preserved.';; esac
        done
        rm "$lock_owner"
        rmdir "$lock/recovery"
        rmdir "$lock" || fail 'Unknown repair lock contents were preserved.'
        mkdir "$lock" 2>/dev/null || fail 'Another repair acquired the recovered lock.'
    fi
    locked=1
    trap cleanup EXIT
    trap 'exit 1' HUP INT TERM
    own_start=$(process_start "$$") || fail 'Could not establish this repair process start identity.'
    owner_text=$(printf 'VCAM_JPEG_LOCK_1\npid=%s\nstart=%s' "$$" "$own_start")
    printf '%s\n' "$owner_text" > "$lock_owner"
    owner_created=1
    chmod 600 "$lock_owner"
}
require_guest() {
    [ "$(id -u)" = 0 ] || fail 'Run under independently verified guest UID 0.'
    [ "$(getprop ro.hardware)" = vphw90 ] || fail 'Expected vphw90 guest.'
    [ "$(getprop ro.build.version.sdk)" = 28 ] || fail 'Expected API 28 guest.'
    [ "$(getprop ro.product.cpu.abilist)" = arm64-v8a ] || fail 'Expected ARM64-only guest.'
    # VMOS substitutes host product and build-user labels. These labels do not
    # select a patch: all provider/importer/encoder/HAL bytes remain pinned below.
    getprop ro.build.fingerprint | grep -Eq '^[^[:space:]:]+:9/PQ3A\.190801\.002/eng\.[A-Za-z0-9_-]+\.20191015\.144423:user/release-keys$' ||
        fail 'The guest framework build is not the inspected ROM10075 build.'
    [ "$(getenforce)" = Disabled ] || fail 'Only the inspected SELinux-disabled guest is supported.'
    for folder in /vendor /vendor/lib64 /vendor/lib64/hw /vendor/bin /vendor/bin/hw /data /data/local /data/local/tmp; do
        directory "$folder" || fail "Expected a real guest directory: $folder"
    done
    owned_file "$target" || fail 'Importer is not a single-link root-owned regular file.'
    known "$provider" "$provider_hash" || fail 'Camera provider executable differs from the measured build.'
    known "$helper" "$helper_hash" || fail 'ARM64 JPEG helper differs from the measured build.'
    known "$camera" "$camera_hash" || fail 'Restore the broker camera socket redirect first; an original camera HAL is required.'
}
provider_pid() {
    pids=$(pidof android.hardware.camera.provider@2.4-service) || return 1
    case "$pids" in ''|*[!0-9]*) return 1;; esac
    [ "$(tr '\000' '\n' < "/proc/$pids/cmdline" | head -1)" = "$provider" ] || return 1
    printf '%s' "$pids"
}
restart_provider() {
    before=$(provider_pid) || fail 'Could not identify exactly one original guest camera provider.'
    setprop ctl.restart vendor.camera-provider-2-4
    attempts=0
    while [ "$attempts" -lt 15 ]; do
        after=$(provider_pid 2>/dev/null || true)
        if [ -n "$after" ] && [ "$after" != "$before" ] && [ "$(getprop init.svc.vendor.camera-provider-2-4)" = running ]; then
            echo "GUEST_CAMERA_PROVIDER_RESTARTED before=$before after=$after"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    return 1
}
publish_from() {
    source=$1
    [ ! -e "$temporary" ] && [ ! -L "$temporary" ] || fail 'Unique staging pathname is occupied.'
    cp -p "$source" "$temporary"
    staged=1
    [ "$(attributes "$temporary")" = "$(attributes "$source")" ] || fail 'Staged ownership or permissions changed.'
    mv "$temporary" "$target"
    staged=0
}
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    if [ "$result" -ne 0 ] && [ "$changed" = 1 ]; then
        echo 'Repair did not complete; restoring the verified original importer.' >&2
        if known "$backup" "$original" && known "$target" "$patched"; then
            if [ "$staged" = 1 ] && regular "$temporary"; then rm "$temporary"; staged=0; fi
            cp -p "$backup" "$temporary" && mv "$temporary" "$target" || true
            if [ "$link_created" = 1 ] && [ -L "$helper_link" ] && [ "$(readlink "$helper_link")" = "$helper" ]; then rm "$helper_link"; fi
            setprop ctl.restart vendor.camera-provider-2-4 || true
            echo 'The journal and backup are retained; run restore to verify and finish recovery.' >&2
        fi
    fi
    if [ "$staged" = 1 ] && regular "$temporary"; then rm "$temporary"; fi
    if [ "$record_staged" = 1 ] && regular "$record_tmp"; then rm "$record_tmp"; fi
    if [ "$locked" = 1 ]; then
        if [ "$owner_created" = 1 ] && owned_file "$lock_owner" && [ "$(cat "$lock_owner")" = "$owner_text" ]; then rm "$lock_owner"; fi
        rmdir "$lock" || true
    fi
    exit "$result"
}
require_guest
current=$(digest "$target")
case "$current" in "$original"|"$patched") ;; *) fail 'Installed importer is neither the exact original nor this exact repair.';; esac
if [ -e "$helper_link" ] || [ -L "$helper_link" ]; then
    [ -L "$helper_link" ] && [ "$(readlink "$helper_link")" = "$helper" ] || fail 'JPEG compatibility pathname contains another file or link.'
fi
for folder in /vendor/lib /vendor/lib/hw; do
    if [ -e "$folder" ] || [ -L "$folder" ]; then directory "$folder" || fail "Unexpected compatibility parent: $folder"; fi
done
echo "GUEST_JPEG_REPAIR_PROFILE=vmos-android9-arm64-rom10075-jpeg-repair importer=$current"
if [ "$action" = inspect ]; then
    echo "originalBackupVerified=$(if known "$backup" "$original"; then echo true; else echo false; fi)"
    echo "helperCompatibilityLink=$(if [ -L "$helper_link" ]; then readlink "$helper_link"; else echo absent; fi)"
    exit 0
fi
acquire_lock

if [ "$action" = install ]; then
    [ "$current" = "$original" ] || fail 'The repair is already installed; inspect it instead of overwriting recovery state.'
    known "$candidate" "$patched" || fail 'Staged candidate does not match the exact tested four-byte repair.'
    [ ! -e "$record" ] && [ ! -L "$record" ] || fail 'An existing repair journal must be reviewed first.'
    [ ! -e "$legacy" ] && [ ! -L "$legacy" ] || fail 'An existing developer repair journal must be reviewed first.'
    [ ! -e "$helper_link" ] && [ ! -L "$helper_link" ] || fail 'Installation requires the measured missing compatibility pathname.'
    if [ -e "$backup" ] || [ -L "$backup" ]; then
        known "$backup" "$original" && owned_file "$backup" || fail 'Existing recovery copy differs from the exact original.'
        [ "$(attributes "$backup")" = "$(attributes "$target")" ] || fail 'Recovery attributes differ from the installed original.'
    else cp -p "$target" "$backup"; fi
    known "$backup" "$original" && owned_file "$backup" || fail 'Recovery copy verification failed.'
    if [ ! -d /vendor/lib ]; then [ ! -e /vendor/lib ] && [ ! -L /vendor/lib ] || fail 'Unexpected /vendor/lib entry.'; lib_created=1; fi
    if [ ! -d /vendor/lib/hw ]; then [ ! -e /vendor/lib/hw ] && [ ! -L /vendor/lib/hw ] || fail 'Unexpected /vendor/lib/hw entry.'; hw_created=1; fi
    printf 'VCAM_JPEG_REPAIR_1\noriginal=%s\npatched=%s\nhelper_link_created=1\nlib_created=%s\nhw_created=%s\n' "$original" "$patched" "$lib_created" "$hw_created" > "$record_tmp"
    record_staged=1
    chmod 600 "$record_tmp"
    mv "$record_tmp" "$record"
    record_staged=0
    # Copy the verified bytes while retaining the exact original attributes.
    cp -p "$backup" "$temporary"
    staged=1
    cat "$candidate" > "$temporary"
    known "$temporary" "$patched" || fail 'Staged repair read-back hash mismatch.'
    [ "$(attributes "$temporary")" = "$(attributes "$backup")" ] || fail 'Staged repair attributes changed.'
    mv "$temporary" "$target"
    staged=0
    changed=1
    mkdir -p /vendor/lib/hw
    ln -s "$helper" "$helper_link"
    link_created=1
    restart_provider || fail 'Repaired guest camera provider did not restart within 15 seconds.'
    known "$target" "$patched" || fail 'Installed repair read-back hash mismatch.'
    changed=0
    echo 'GUEST_JPEG_REPAIR_INSTALLED; real Camera2 YUV/JPEG acceptance is still required.'
else
    known "$backup" "$original" && owned_file "$backup" || fail 'Exact root-owned original recovery copy is unavailable.'
    [ "$(attributes "$backup")" = "$(attributes "$target")" ] || fail 'Importer attributes differ from the preserved original.'
    if owned_file "$record"; then
        journal=$record
        expected=$(printf 'VCAM_JPEG_REPAIR_1\noriginal=%s\npatched=%s\nhelper_link_created=1' "$original" "$patched")
        [ "$(head -4 "$record")" = "$expected" ] || fail 'Repair journal identity changed.'
        [ "$(wc -l < "$record" | tr -d ' ')" = 6 ] || fail 'Unexpected repair journal fields.'
        case "$(sed -n '5p' "$record")" in lib_created=0) lib_created=0;; lib_created=1) lib_created=1;; *) fail 'Invalid directory journal.';; esac
        case "$(sed -n '6p' "$record")" in hw_created=0) hw_created=0;; hw_created=1) hw_created=1;; *) fail 'Invalid directory journal.';; esac
    elif owned_file "$legacy"; then
        [ ! -e "$record" ] && [ ! -L "$record" ] || fail 'An unrecognized modern journal must not be ignored.'
        journal=$legacy
        # The measured initial developer installation wrote exactly this JSON.
        expected=$(printf '{"originalSha256":"%s","patchedSha256":"%s","backup":"%s","helperLinkCreated":true}' "$original" "$patched" "$backup")
        [ "$(cat "$legacy")" = "$expected" ] || fail 'Legacy developer repair journal differs from the measured operation.'
        # Legacy journal did not record directory creation. Preserve directories.
    else fail 'No verified modern or measured legacy installation journal exists.'; fi
    archive=$journal.restored.$$.txt
    [ ! -e "$archive" ] && [ ! -L "$archive" ] || fail 'Recovery journal archive pathname is occupied.'
    if [ "$current" = "$patched" ]; then publish_from "$backup"; fi
    known "$target" "$original" || fail 'Original importer restoration read-back failed.'
    if [ -L "$helper_link" ]; then rm "$helper_link"; fi
    restart_provider || fail 'Original guest provider restart failed; recovery copy is retained.'
    mv "$journal" "$archive"
    if [ "$hw_created" = 1 ]; then rmdir /vendor/lib/hw 2>/dev/null || true; fi
    if [ "$lib_created" = 1 ]; then rmdir /vendor/lib 2>/dev/null || true; fi
    echo "GUEST_JPEG_REPAIR_RESTORED; original importer verified, repair link absent, journal=$archive"
fi
