#!/system/bin/sh
# Pinned VMOS ROM10075 scaled-NV21 allocation-bounds repair. Guest UID 0 only.
set -eu
umask 077
PATH=/system/bin:/system/xbin
export PATH
action=${1:-inspect}
case "$action" in inspect|install|restore) ;; *) echo 'usage: vmos-guest-nv21-scale-repair.sh inspect|install|restore [candidate]' >&2; exit 2;; esac
candidate=${2:-/data/local/tmp/vcam-nv21-scale-camera.so}
target=/vendor/lib64/hw/camera.vphw90.so
record=/data/local/tmp/vcam-nv21-scale-repair-v1.txt
lock=/vendor/lib64/hw/.vcam-nv21-scale-repair-lock
jlock=/data/local/tmp/.vcam-nv21-scale-repair-journal-lock
provider=/vendor/bin/hw/android.hardware.camera.provider@2.4-service
provider_hash=fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87
factory_hash=362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c
pipe_hash=1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97
factory_fixed=fa79aee82358025a8cb3f776a511417184d39eb45716170f639aa21581a30cf3
pipe_fixed=02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a
hal_size=2109016
locked=0
jlocked=0
changed=0
source_hash=
repair_hash=
backup=
mode=
group=
label=
fail() { echo "VMOS_NV21_SCALE_REPAIR_REFUSED: $*" >&2; exit 1; }
digest() { sha256sum "$1" | cut -d' ' -f1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
owned() { regular "$1" && [ "$(stat -c %u "$1")" = 0 ] && [ "$(stat -c %h "$1")" = 1 ]; }
attrs() { stat -c '%a:%u:%g' "$1"; }
directory() { [ -d "$1" ] && [ ! -L "$1" ]; }
context() { ls -Zd "$1" | awk '{print $1}'; }
known() { owned "$1" && [ "$(stat -c %s "$1")" = "$hal_size" ] && [ "$(digest "$1")" = "$2" ]; }
source_known() { known "$1" "$source_hash"; }
repair_known() { known "$1" "$repair_hash"; }
metadata() { [ "$(attrs "$1")" = "$mode:0:$group" ] && [ "$(context "$1")" = "$label" ]; }
valid_metadata() {
    # The measured original VMOS HAL is exposed as 0777. Retain its exact mode.
    case "$mode" in 444|440|400|644|640|600|755|750|700|777) ;; *) return 1;; esac
    case "$group" in ''|*[!0-9]*) return 1;; esac
    [ "${#group}" -le 10 ] || return 1
    case "$label" in '') return 1;; '?') ;; *[!a-zA-Z0-9_:.,-]*) return 1;; *:*) ;; *) return 1;; esac
    [ "${#label}" -le 128 ]
}
select_pair() {
    case "$source_hash" in "$factory_hash") repair_hash=$factory_fixed;; "$pipe_hash") repair_hash=$pipe_fixed;; *) return 1;; esac
    backup=/vendor/lib64/hw/.vcam-nv21-scale-original-$source_hash.so
}
journal_text() {
    printf 'VCAM_NV21_SCALE_REPAIR_1\nsource_sha256=%s\nrepair_sha256=%s\nmode=%s\nuid=0\ngid=%s\nlabel=%s\n' "$source_hash" "$repair_hash" "$mode" "$group" "$label"
}
journal_known() {
    owned "$record" && [ "$(attrs "$record")" = 600:0:0 ] &&
    [ "$(digest "$record")" = "$(journal_text | sha256sum | cut -d' ' -f1)" ]
}
load_journal() {
    owned "$record" && [ "$(attrs "$record")" = 600:0:0 ] && [ "$(stat -c %s "$record")" -le 512 ] || return 1
    # Bounded data-only parsing; no shell sourcing or evaluation.
    source_hash=$(sed -n '2s/^source_sha256=//p' "$record")
    mode=$(sed -n '4s/^mode=//p' "$record")
    group=$(sed -n '6s/^gid=//p' "$record")
    label=$(sed -n '7s/^label=//p' "$record")
    select_pair && valid_metadata && journal_known
}
set_metadata() {
    chmod "$mode" "$1" && chown "0:$group" "$1" || return 1
    if [ "$(context "$1")" != "$label" ]; then
        [ "$label" != '?' ] && chcon "$label" "$1" || return 1
    fi
    owned "$1" && metadata "$1"
}
stage() {
    input=$1
    output=$2
    [ ! -e "$output" ] && [ ! -L "$output" ] || return 1
    (set -C; : > "$output") || return 1
    cat "$input" > "$output" && set_metadata "$output"
}
provider_known() { owned "$provider" && [ "$(digest "$provider")" = "$provider_hash" ]; }
provider_pid() {
    provider_known || return 1
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
cleanup() {
    result=$?
    trap - EXIT HUP INT TERM
    set +e
    if [ "$result" -ne 0 ] && [ "$changed" = 1 ]; then
        if repair_known "$target" && metadata "$target" && source_known "$backup" && metadata "$backup" && journal_known; then
            if stage "$backup" "$lock/rollback.tmp" && source_known "$lock/rollback.tmp" && mv "$lock/rollback.tmp" "$target"; then
                restart_provider || true
                echo 'Exact source HAL restored after failure; journal retained. Cold-start guest if needed, then rerun restore.' >&2
            fi
        else
            echo 'Rollback refused unexpected target, backup, metadata or journal; all were preserved.' >&2
        fi
    fi
    if [ "$locked" = 1 ]; then
        for item in "$lock/repair.tmp" "$lock/original.tmp" "$lock/rollback.tmp"; do
            if repair_known "$item" || source_known "$item"; then rm "$item"; fi
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
for pin in "$factory_fixed" "$pipe_fixed"; do
    case "$pin" in ''|*[!a-f0-9]*) fail 'No pinned repair release.';; esac
    [ "${#pin}" = 64 ] || fail 'Invalid repair release hash.'
done
for folder in /vendor /vendor/lib64 /vendor/lib64/hw /vendor/bin /vendor/bin/hw /data /data/local /data/local/tmp; do directory "$folder" || fail "Expected real directory: $folder"; done
provider_known || fail 'Provider executable differs from the exact inspected build.'
if [ -e "$record" ] || [ -L "$record" ]; then
    load_journal || fail 'Unrecognized recovery journal; preserved.'
    journal=verified
else
    owned "$target" || fail 'Target is not a single-link root-owned regular HAL.'
    source_hash=$(digest "$target")
    select_pair && source_known "$target" || fail 'No journal and target is neither accepted source HAL; wrappers are unsupported.'
    mode=$(stat -c %a "$target")
    group=$(stat -c %g "$target")
    label=$(context "$target")
    valid_metadata || fail 'Source metadata is not safely representable.'
    journal=absent
fi
if source_known "$target" && metadata "$target"; then current=source
elif repair_known "$target" && metadata "$target"; then current=repaired
else fail 'HAL bytes or metadata differ from the pinned source/repair pair.'; fi
if [ -e "$backup" ] || [ -L "$backup" ]; then
    source_known "$backup" && metadata "$backup" || fail 'Unknown backup bytes or metadata; preserved.'
    saved=verified
else saved=absent; fi
[ "$current" != repaired ] || [ "$saved" = verified ] || fail 'Repaired HAL has no verified source backup.'
echo "GUEST_NV21_SCALE_REPAIR current=$current journal=$journal backup=$saved source=$source_hash repair=$repair_hash mode=$mode gid=$group label=$label"
[ "$action" != inspect ] || exit 0
# Refuse mutations unless the exact provider can be identified before staging.
provider_pid >/dev/null || fail 'Exactly one running verified guest camera provider is required; cold-start guest if it stopped.'
mkdir "$lock" 2>/dev/null || fail 'Private staging lock exists; preserved.'
locked=1
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[ "$(attrs "$lock")" = 700:0:0 ] || fail 'Private staging metadata differs.'
mkdir "$jlock" 2>/dev/null || fail 'Private journal staging lock exists; preserved.'
jlocked=1
[ "$(attrs "$jlock")" = 700:0:0 ] || fail 'Private journal staging metadata differs.'
if [ "$action" = install ]; then
    [ "$current" = source ] && [ "$journal" = absent ] || fail 'Finish the existing installation or recovery first.'
    repair_known "$candidate" || fail 'Candidate is not the pinned repair for the installed source.'
    stage "$candidate" "$lock/repair.tmp" && repair_known "$lock/repair.tmp" && metadata "$lock/repair.tmp" || fail 'Repair staging failed.'
    if [ "$saved" = absent ]; then
        stage "$target" "$lock/original.tmp" && source_known "$lock/original.tmp" && metadata "$lock/original.tmp" || fail 'Source staging failed.'
        [ ! -e "$backup" ] && [ ! -L "$backup" ] || fail 'Backup appeared during staging; preserved.'
        mv -n "$lock/original.tmp" "$backup"
    fi
    source_known "$backup" && metadata "$backup" || fail 'Published backup verification failed.'
    (set -C; : > "$jlock/journal.tmp") || fail 'Could not exclusively create journal stage.'
    journal_text > "$jlock/journal.tmp"
    chmod 600 "$jlock/journal.tmp"
    chown 0:0 "$jlock/journal.tmp"
    [ ! -e "$record" ] && [ ! -L "$record" ] || fail 'Journal appeared during staging; preserved.'
    mv -n "$jlock/journal.tmp" "$record"
    journal_known || fail 'Recovery journal publication failed.'
    source_known "$target" && metadata "$target" || fail 'Source target changed during staging.'
    changed=1
    mv "$lock/repair.tmp" "$target"
    repair_known "$target" && metadata "$target" || fail 'Installed repair read-back failed.'
    restart_provider || fail 'Verified provider did not restart with a new PID; rollback required.'
    repair_known "$target" && metadata "$target" && source_known "$backup" && metadata "$backup" && journal_known || fail 'Post-restart verification failed.'
    changed=0
    echo 'GUEST_NV21_SCALE_REPAIR_INSTALLED; actual camera acceptance remains required.'
else
    [ "$journal" = verified ] && [ "$saved" = verified ] && journal_known || fail 'Verified journal and exact source backup required.'
    if [ "$current" = repaired ]; then
        stage "$backup" "$lock/original.tmp" && source_known "$lock/original.tmp" && metadata "$lock/original.tmp" || fail 'Source restoration staging failed.'
        repair_known "$target" && metadata "$target" || fail 'Repaired target changed during restoration.'
        mv "$lock/original.tmp" "$target"
    fi
    source_known "$target" && metadata "$target" || fail 'Restored source verification failed.'
    restart_provider || fail 'Original HAL restored but verified provider restart failed; journal retained. Cold-start guest then rerun restore.'
    source_known "$target" && metadata "$target" && source_known "$backup" && metadata "$backup" && journal_known || fail 'Post-restart restoration verification failed.'
    rm "$record"
    echo 'GUEST_NV21_SCALE_REPAIR_RESTORED; exact source bytes and metadata verified; pinned backup retained.'
fi
