#!/system/bin/sh
# Exact VMOS ROM10075 only. Diagnostic JNI library; no camera HAL changes.
set -eu
umask 077
PATH=/system/bin:/system/xbin
export PATH
action=${1:-inspect}
case "$action" in inspect|install|restore) ;; *) echo 'usage: vmos-guest-jpegutil-diagnostic.sh inspect|install|restore [candidate]' >&2; exit 2;; esac
candidate=${2:-/data/local/tmp/vcam-jpegutil-diagnostic.so}
target=/system/lib64/libjni_jpegutil.so
backup=/system/lib64/.vcam-original-libjni_jpegutil.so
record=/data/local/tmp/vcam-jpegutil-diagnostic-v1.txt
lock=/system/lib64/.vcam-jpegutil-diagnostic-lock
jlock=/data/local/tmp/.vcam-jpegutil-diagnostic-journal-lock
original_hash=6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5
shim_hash=938d8afa8b6daa6e4d5942cc4ef10237e506623ea35e97bf9a0b21a65122d9e6
shim_size=540232
locked=0
jlocked=0
changed=0
mode=
group=
label=
fail() { echo "VMOS_JPEGUTIL_DIAGNOSTIC_REFUSED: $*" >&2; exit 1; }
digest() { sha256sum "$1" | cut -d' ' -f1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
owned() { regular "$1" && [ "$(stat -c %u "$1")" = 0 ] && [ "$(stat -c %h "$1")" = 1 ]; }
attrs() { stat -c '%a:%u:%g' "$1"; }
directory() { [ -d "$1" ] && [ ! -L "$1" ]; }
context() { ls -Zd "$1" | awk '{print $1}'; }
known() { owned "$1" && [ "$(stat -c %s "$1")" = "$3" ] && [ "$(digest "$1")" = "$2" ]; }
original() { known "$1" "$original_hash" 465864; }
shim() { known "$1" "$shim_hash" "$shim_size"; }
metadata() { [ "$(attrs "$1")" = "$mode:0:$group" ] && [ "$(context "$1")" = "$label" ]; }
valid_metadata() {
    # This inspected VMOS image presents its original system libraries as 0777.
    # Preserve that measured mode; exact guest, bytes, owner and label are pinned.
    case "$mode" in 444|440|400|644|640|600|777) ;; *) return 1;; esac
    case "$group" in ''|*[!0-9]*) return 1;; esac
    [ "${#group}" -le 10 ] || return 1
    case "$label" in '') return 1;; '?' ) ;; *[!a-zA-Z0-9_:.,-]*) return 1;; *:*) ;; *) return 1;; esac
    [ "${#label}" -le 128 ]
}
journal_text() {
    printf 'VCAM_JPEGUTIL_DIAGNOSTIC_1\noriginal_sha256=%s\nshim_sha256=%s\nmode=%s\nuid=0\ngid=%s\nlabel=%s\n' "$original_hash" "$shim_hash" "$mode" "$group" "$label"
}
journal_known() {
    owned "$record" && [ "$(attrs "$record")" = 600:0:0 ] &&
    [ "$(digest "$record")" = "$(journal_text | sha256sum | cut -d' ' -f1)" ]
}
load_journal() {
    owned "$record" && [ "$(attrs "$record")" = 600:0:0 ] && [ "$(stat -c %s "$record")" -le 512 ] || return 1
    # Parse only three bounded metadata fields. Never execute a recovery record.
    mode=$(sed -n '4s/^mode=//p' "$record")
    group=$(sed -n '6s/^gid=//p' "$record")
    label=$(sed -n '7s/^label=//p' "$record")
    valid_metadata && journal_known
}
set_metadata() {
    chmod "$mode" "$1" && chown "0:$group" "$1" || return 1
    if [ "$(context "$1")" != "$label" ]; then
        [ "$label" != '?' ] && chcon "$label" "$1" || return 1
    fi
    owned "$1" && metadata "$1"
}
stage() {
    source=$1
    destination=$2
    [ ! -e "$destination" ] && [ ! -L "$destination" ] || return 1
    (set -C; : > "$destination") || return 1
    cat "$source" > "$destination" && set_metadata "$destination"
}
stop_camera() { am force-stop --user 0 com.android.camera2; }
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$result" -ne 0 ] && [ "$changed" = 1 ]; then
        if shim "$target" && metadata "$target" && original "$backup" && metadata "$backup" && journal_known; then
            if stage "$backup" "$lock/rollback.tmp" && original "$lock/rollback.tmp" && mv "$lock/rollback.tmp" "$target"; then
                stop_camera || true
                echo 'Exact original JNI library restored after failure; recovery journal retained.' >&2
            fi
        else
            echo 'Rollback refused an unexpected target, backup, or journal; all were preserved.' >&2
        fi
    fi
    if [ "$locked" = 1 ]; then
        for item in "$lock/shim.tmp" "$lock/original.tmp" "$lock/rollback.tmp"; do
            if shim "$item" || original "$item"; then rm "$item"; fi
        done
        rmdir "$lock" || true
    fi
    if [ "$jlocked" = 1 ]; then
        if owned "$jlock/journal.tmp" && [ "$(digest "$jlock/journal.tmp")" = "$(journal_text | sha256sum | cut -d' ' -f1)" ]; then rm "$jlock/journal.tmp"; fi
        rmdir "$jlock" || true
    fi
    exit "$result"
}

[ "$(id -u)" = 0 ] || fail 'Guest UID 0 required.'
[ "$(getprop ro.hardware)" = vphw90 ] || fail 'Expected vphw90 guest.'
[ "$(getprop ro.build.version.sdk)" = 28 ] || fail 'Expected API 28 guest.'
[ "$(getprop ro.product.cpu.abilist)" = arm64-v8a ] || fail 'Expected ARM64-only guest.'
case "$(getprop ro.build.fingerprint)" in *:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys) ;; *) fail 'Guest framework fingerprint differs.';; esac
[ "$(getenforce)" = Disabled ] || fail 'Only the measured SELinux-disabled guest is supported.'
case "$shim_hash" in *[!a-f0-9]*|'') fail 'No pinned diagnostic release hash.';; esac
[ "${#shim_hash}" = 64 ] || fail 'Invalid diagnostic release hash.'
case "$shim_size" in ''|*[!0-9]*) fail 'No pinned diagnostic release size.';; esac
for folder in /system /system/lib64 /data /data/local /data/local/tmp; do directory "$folder" || fail "Expected real directory: $folder"; done
if [ -e "$record" ] || [ -L "$record" ]; then
    load_journal || fail 'Unrecognized recovery journal; preserved.'
    journal=verified
else
    original "$target" || fail 'No recovery journal and target is not the measured original.'
    mode=$(stat -c %a "$target")
    group=$(stat -c %g "$target")
    label=$(context "$target")
    valid_metadata || fail 'Original JNI metadata is not safely representable.'
    journal=absent
fi
if original "$target" && metadata "$target"; then current=original
elif shim "$target" && metadata "$target"; then current=diagnostic
else fail 'Target bytes or metadata differ from the exact original and diagnostic library.'; fi
if [ -e "$backup" ] || [ -L "$backup" ]; then
    original "$backup" && metadata "$backup" || fail 'Unknown backup bytes or metadata; preserved.'
    saved=verified
else saved=absent; fi
[ "$current" != diagnostic ] || [ "$saved" = verified ] || fail 'Installed diagnostic has no verified original backup.'
echo "GUEST_JPEGUTIL_DIAGNOSTIC current=$current journal=$journal backup=$saved mode=$mode gid=$group label=$label"
[ "$action" != inspect ] || exit 0
mkdir "$lock" 2>/dev/null || fail 'Private staging lock already exists; preserved.'
locked=1
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[ "$(attrs "$lock")" = 700:0:0 ] || fail 'Private staging metadata differs.'
mkdir "$jlock" 2>/dev/null || fail 'Private journal staging lock already exists; preserved.'
jlocked=1
[ "$(attrs "$jlock")" = 700:0:0 ] || fail 'Private journal staging metadata differs.'
if [ "$action" = install ]; then
    [ "$current" = original ] && [ "$journal" = absent ] || fail 'Finish the existing installation or recovery first.'
    shim "$candidate" && metadata "$candidate" || fail 'Candidate bytes, ownership, mode, group, or label differ.'
    stage "$candidate" "$lock/shim.tmp" && shim "$lock/shim.tmp" && metadata "$lock/shim.tmp" || fail 'Diagnostic staging verification failed.'
    if [ "$saved" = absent ]; then
        stage "$target" "$lock/original.tmp" && original "$lock/original.tmp" && metadata "$lock/original.tmp" || fail 'Original staging verification failed.'
        [ ! -e "$backup" ] && [ ! -L "$backup" ] || fail 'Backup appeared during staging; preserved.'
        mv -n "$lock/original.tmp" "$backup"
    fi
    original "$backup" && metadata "$backup" || fail 'Published original backup verification failed.'
    (set -C; : > "$jlock/journal.tmp") || fail 'Could not exclusively create staged journal.'
    journal_text > "$jlock/journal.tmp"
    chmod 600 "$jlock/journal.tmp"
    chown 0:0 "$jlock/journal.tmp"
    [ ! -e "$record" ] && [ ! -L "$record" ] || fail 'Journal appeared during staging; preserved.'
    mv -n "$jlock/journal.tmp" "$record"
    journal_known || fail 'Recovery journal publication verification failed.'
    original "$target" && metadata "$target" || fail 'Original target changed during staging.'
    changed=1
    mv "$lock/shim.tmp" "$target"
    shim "$target" && metadata "$target" || fail 'Installed diagnostic read-back failed.'
    stop_camera || fail 'Camera force-stop failed; rollback required.'
    shim "$target" && metadata "$target" && original "$backup" && metadata "$backup" && journal_known || fail 'Post-stop verification failed.'
    changed=0
    echo 'GUEST_JPEGUTIL_DIAGNOSTIC_INSTALLED; stock Camera2 was force-stopped; launch it explicitly.'
else
    [ "$journal" = verified ] && [ "$saved" = verified ] && journal_known || fail 'Verified journal and original backup required.'
    if [ "$current" = diagnostic ]; then
        stage "$backup" "$lock/original.tmp" && original "$lock/original.tmp" && metadata "$lock/original.tmp" || fail 'Original restoration staging failed.'
        shim "$target" && metadata "$target" || fail 'Diagnostic target changed during restoration.'
        mv "$lock/original.tmp" "$target"
    fi
    original "$target" && metadata "$target" || fail 'Restored original verification failed.'
    stop_camera || fail 'Camera force-stop failed; exact original and journal retained.'
    original "$target" && metadata "$target" && original "$backup" && metadata "$backup" && journal_known || fail 'Post-stop restoration verification failed.'
    rm "$record"
    echo 'GUEST_JPEGUTIL_DIAGNOSTIC_RESTORED; exact original JNI bytes and metadata verified; pinned backup retained.'
fi
