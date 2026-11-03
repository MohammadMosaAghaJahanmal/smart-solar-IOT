package com.virtualcamerapro.setup.provisioning

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.virtualcamerapro.broker.VmosHostIdentity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Approved guest-only provisioning. Caller serializes this with all broker setup operations. */
class GuestProvisioningManager(private val context: Context) {
    private val root = RootAccessManager(context)
    private val directory = File(context.filesDir, "guest-provisioning")
    private val state = File(directory, "preparation.json")
    private val migration = File(directory, "legacy-migration.json")
    private val installation = File(context.filesDir, "guest-installation.json")
    private val profile get() = GuestAdapterRegistry

    fun inspect(): JSONObject {
        val hashes = profile.hashes()
        val importer = hashes.optString("jpegImporter")
        val compat = jpegCompatibility()
        val supported = profile.profileMatches() && hashes.optString("provider") == profile.PROVIDER_SHA &&
            hashes.optString("hal") in profile.knownHalHashes && hashes.optString("jpegHelper") == profile.JPEG_SHA &&
            importer in setOf(profile.IMPORTER_ORIGINAL, profile.IMPORTER_REPAIRED)
        val previous = readJournal(state)
        val interruptedJpeg = unfinishedOwnedJpeg(previous, hashes.optString("hal"))
        val old = legacyIdentity()
        val migrationRecord = readJournal(migration)
        val importerRecovery = profile.regularOwned(File(IMPORTER_BACKUP), 0) &&
            profile.sha256OrAbsent(File(IMPORTER_BACKUP)) == profile.IMPORTER_ORIGINAL
        return JSONObject().put("profileId", if (supported) profile.PROFILE_ID else JSONObject.NULL)
            .put("profileVersion", profile.PROFILE_VERSION).put("profileSupported", supported)
            .put("hashes", hashes).put("jpegCompatibility", compat)
            .put("jpegRepairRequired", importer == profile.IMPORTER_ORIGINAL || compat == "missing" || interruptedJpeg)
            .put("interruptedOwnedJpegPreparation", interruptedJpeg)
            .put("rootCompatibilityRequired", !entryExists("/system/bin/app_process32"))
            .put("legacyMigrationRequired", migrationRecord?.optString("stage") == "prepared" || old != null && !installation.isFile)
            .put("legacyPackagePresent", old != null).put("integratedInstallationPresent", installation.isFile)
            .put("baselinePrepared", supported && importer == profile.IMPORTER_REPAIRED && importerRecovery && compat == "helper-symlink" && !interruptedJpeg)
            .put("baselineRecoveryVerified", importerRecovery)
            .put("canRestoreOwnedBaseline", previous?.optBoolean("jpegInstalledByThisApplication") == true && importerRecovery &&
                hashes.optString("hal") == profile.FACTORY_HAL && compat in setOf("helper-symlink", "missing"))
            .put("canRollbackLegacyMigration", migrationRecord?.optString("stage") in setOf("prepared", "adopted"))
            .put("previousPreparation", previous ?: JSONObject.NULL)
            .put("migration", migrationRecord ?: JSONObject.NULL)
            .put("root", root.check(false))
    }

    fun prepare(progress: (String, String) -> Unit = { _, _ -> }): JSONObject {
        profile.requireSupportedGuest()
        ensurePrivateDirectory()
        progress("root", "Checking actual guest root authorization")
        requireRootAndSecurity()
        val before = inspect()
        check(before.optBoolean("profileSupported")) { "Guest camera bytes do not match a registered compatible profile" }
        progress("compatibility", "Checking guest root and camera prerequisites")
        installRootCompatibility()
        val sourceHash = profile.sha256(File(profile.IMPORTER))
        val compat = jpegCompatibility()
        if (sourceHash == profile.IMPORTER_ORIGINAL || compat == "missing" ||
            unfinishedOwnedJpeg(readJournal(state), profile.sha256(File(profile.HAL)))) {
            check(profile.sha256(File(profile.HAL)) == profile.FACTORY_HAL) {
                "A preexisting bridge has incomplete baseline repairs; restore its preserved original camera before preparation"
            }
            prepareJpegBaseline(progress)
        } else {
            check(sourceHash == profile.IMPORTER_REPAIRED && compat == "helper-symlink") {
                "Existing camera preparation is unknown and was preserved"
            }
            check(profile.regularOwned(File(IMPORTER_BACKUP), 0) && profile.sha256(File(IMPORTER_BACKUP)) == profile.IMPORTER_ORIGINAL) {
                "A repaired importer needs its exact preserved original recovery copy"
            }
            progress("jpeg", "Existing verified JPEG repairs reused")
        }
        if (!installation.isFile) migrateLegacy(progress)
        else recoverPublishedMigration()
        val result = inspect()
        check(result.optBoolean("baselinePrepared")) { "Guest JPEG baseline preparation did not verify" }
        val record = (readJournal(state) ?: JSONObject()).put("schema", 1).put("profileId", profile.PROFILE_ID)
            .put("profileVersion", profile.PROFILE_VERSION).put("prepared", true)
            .put("completedAt", System.currentTimeMillis()).put("hashes", result.getJSONObject("hashes"))
        writeAtomic(state, record)
        return inspect().put("prepared", true).put("cameraPipelineVerified", false)
    }

    /** Broker/HAL restoration must run first; baseline restore never overwrites a bridge. */
    fun restore(progress: (String, String) -> Unit = { _, _ -> }): JSONObject {
        profile.requireSupportedGuest(); ensurePrivateDirectory(); requireRootAndSecurity()
        progress("restore", "Restoring only guest preparation recorded by this application")
        val record = readJournal(state)
        if (record?.optBoolean("jpegInstalledByThisApplication") == true) {
            check(profile.sha256(File(profile.HAL)) == profile.FACTORY_HAL) { "Restore the guest camera bridge before restoring baseline JPEG preparation" }
            // The root script atomically archives its own journal, but cannot
            // publish this app's private transaction. Persist intent first so
            // a process death between those two publications is retryable.
            record.put("jpegRestoreRequested", true).put("jpegRestoreRequestedAt", System.currentTimeMillis())
            writeAtomic(state, record)
            if (!completedJpegRestore()) runRepair("restore", null)
            val archive = verifyCompletedJpegRestore()
            record.put("jpegInstalledByThisApplication", false).put("prepared", false)
                .put("jpegPrepared", false)
                .put("jpegRestoreRequested", false).put("jpegRestoredAt", System.currentTimeMillis())
                .put("jpegRestoredArchive", archive)
            writeAtomic(state, record)
        }
        val handoff = readJournal(migration)
        if (handoff != null && handoff.optString("stage") in setOf("prepared", "adopted", "completed")) {
            check(profile.sha256(File(profile.HAL)) == profile.FACTORY_HAL) {
                "Restore the integrated guest camera before finalizing preparation restoration"
            }
            // Explicit Restore means return to the original guest camera, not
            // restart the old broker against a HAL it no longer owns.
            handoff.put("stage", "restored").put("restoredAt", System.currentTimeMillis())
                .put("legacyPackageRetainedForRollback", true)
            writeAtomic(migration, handoff)
        }
        if (record?.optBoolean("rootCompatibilityInstalledByThisApplication") == true) {
            root.approved("""
                if [ ! -e /system/bin/app_process32 ] && [ ! -L /system/bin/app_process32 ]; then exit 0; fi
                [ -L /system/bin/app_process32 ] && [ "${'$'}(/system/bin/readlink /system/bin/app_process32)" = app_process64 ] || exit 81
                [ "${'$'}(/system/bin/sha256sum /system/bin/app_process64 | /system/bin/cut -d' ' -f1)" = '$APP_PROCESS_SHA' ] || exit 82
                /system/bin/rm /system/bin/app_process32
                [ ! -e /system/bin/app_process32 ] && [ ! -L /system/bin/app_process32 ] || exit 83
            """.trimIndent())
            record.put("rootCompatibilityInstalledByThisApplication", false)
            writeAtomic(state, record)
        }
        return inspect().put("restoredOwnedPreparation", true)
    }

    private fun completedJpegRestore(): Boolean = !entryExists(JPEG_JOURNAL) && !entryExists(JPEG_LEGACY_JOURNAL) &&
        profile.sha256OrAbsent(File(profile.HAL)) == profile.FACTORY_HAL &&
        profile.sha256OrAbsent(File(profile.IMPORTER)) == profile.IMPORTER_ORIGINAL &&
        !entryExists(profile.JPEG_COMPAT)

    /** Original bytes alone cannot prove the repair script finished its restart/archive transaction. */
    private fun verifyCompletedJpegRestore(): String {
        check(completedJpegRestore()) { "JPEG restoration has no complete original baseline to reconcile" }
        return root.approved("""
            [ ! -e $JPEG_JOURNAL ] && [ ! -L $JPEG_JOURNAL ] && [ ! -e $JPEG_LEGACY_JOURNAL ] && [ ! -L $JPEG_LEGACY_JOURNAL ] || exit 81
            [ ! -e ${profile.JPEG_COMPAT} ] && [ ! -L ${profile.JPEG_COMPAT} ] || exit 81
            for original_file in ${profile.IMPORTER} $IMPORTER_BACKUP; do
                [ -f "${'$'}original_file" ] && [ ! -L "${'$'}original_file" ] || exit 82
                [ "${'$'}(/system/bin/stat -c '%u:%h' "${'$'}original_file")" = '0:1' ] || exit 82
                [ "${'$'}(/system/bin/sha256sum "${'$'}original_file" | /system/bin/cut -d' ' -f1)" = '${profile.IMPORTER_ORIGINAL}' ] || exit 82
            done
            [ "${'$'}(/system/bin/stat -c '%a:%u:%g' ${profile.IMPORTER})" = "${'$'}(/system/bin/stat -c '%a:%u:%g' $IMPORTER_BACKUP)" ] || exit 82
            [ "${'$'}(/system/bin/sha256sum ${profile.HAL} | /system/bin/cut -d' ' -f1)" = '${profile.FACTORY_HAL}' ] || exit 82
            for archive in $JPEG_JOURNAL.restored.*.txt $JPEG_LEGACY_JOURNAL.restored.*.txt; do
                [ -f "${'$'}archive" ] && [ ! -L "${'$'}archive" ] || continue
                [ "${'$'}(/system/bin/stat -c '%u:%h' "${'$'}archive")" = '0:1' ] || continue
                [ "${'$'}(/system/bin/stat -c %s "${'$'}archive")" -le 1024 ] || continue
                case "${'$'}archive" in
                    $JPEG_JOURNAL.restored.*.txt)
                        archive_pid=${'$'}{archive#$JPEG_JOURNAL.restored.}; archive_pid=${'$'}{archive_pid%.txt}
                        case "${'$'}archive_pid" in ''|*[!0-9]*) continue ;; esac
                        expected=${'$'}(printf 'VCAM_JPEG_REPAIR_1\noriginal=%s\npatched=%s\nhelper_link_created=1' '${profile.IMPORTER_ORIGINAL}' '${profile.IMPORTER_REPAIRED}')
                        [ "${'$'}(/system/bin/head -4 "${'$'}archive")" = "${'$'}expected" ] || continue
                        [ "${'$'}(/system/bin/wc -l < "${'$'}archive" | /system/bin/tr -d ' ')" = 6 ] || continue
                        case "${'$'}(/system/bin/sed -n '5p' "${'$'}archive")" in lib_created=0|lib_created=1) ;; *) continue ;; esac
                        case "${'$'}(/system/bin/sed -n '6p' "${'$'}archive")" in hw_created=0|hw_created=1) ;; *) continue ;; esac
                        ;;
                    $JPEG_LEGACY_JOURNAL.restored.*.txt)
                        archive_pid=${'$'}{archive#$JPEG_LEGACY_JOURNAL.restored.}; archive_pid=${'$'}{archive_pid%.txt}
                        case "${'$'}archive_pid" in ''|*[!0-9]*) continue ;; esac
                        expected=${q("{\"originalSha256\":\"${profile.IMPORTER_ORIGINAL}\",\"patchedSha256\":\"${profile.IMPORTER_REPAIRED}\",\"backup\":\"$IMPORTER_BACKUP\",\"helperLinkCreated\":true}")}
                        [ "${'$'}(/system/bin/cat "${'$'}archive")" = "${'$'}expected" ] || continue
                        ;;
                    *) continue ;;
                esac
                printf '%s' "${'$'}archive"
                exit 0
            done
            printf '%s\n' 'No verified restored JPEG journal archive exists; restoration state was preserved.'
            exit 83
        """.trimIndent()).trim().also {
            check(it.startsWith("$JPEG_JOURNAL.restored.") || it.startsWith("$JPEG_LEGACY_JOURNAL.restored."))
        }
    }

    /** Call only after the integrated broker has passed real consumer-frame verification. */
    fun finishLegacyMigration(): JSONObject {
        val record = readJournal(migration) ?: return JSONObject().put("migrationRequired", false)
        // Restore permanently ended the old package's handoff. A later
        // Prepare installs a fresh local bridge and must not adopt or revive
        // that historical package merely because its recovery record remains.
        if (record.optString("stage") == "restored") return JSONObject()
            .put("migrationRequired", false).put("historicalMigration", record)
        if (record.optString("stage") == "completed" && record.optBoolean("legacyLauncherDisabled")) return record
        check(record.optString("stage") in setOf("adopted", "completed") && installation.isFile) { "No complete legacy handoff is pending" }
        val current = JSONObject(installation.readText())
        check(current.optString("stage") == "configured" && current.optString("originalSha256") == profile.FACTORY_HAL)
        val legacy = legacyIdentity()
        if (legacy != null) {
            check(legacy.uid == record.getInt("legacyUid") && legacy.files.absolutePath == record.getString("legacyFiles"))
            // Persist the prior launcher state before changing PackageManager.
            // Retrying after a process death must not adopt Disabled as its old state.
            if (!record.has("legacySetupActivityState")) record.put("legacySetupActivityState", legacy.setupState)
            record.put("legacyLauncherDisablePending", true)
            writeAtomic(migration, record)
            try {
                setLegacyComponent(LEGACY_SETUP, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                record.put("legacyLauncherDisabled", true).put("legacyLauncherDisablePending", false)
            } catch (error: Throwable) {
                runCatching {
                    setLegacyComponent(LEGACY_SETUP, record.getInt("legacySetupActivityState"))
                    record.put("legacyLauncherDisabled", false).put("legacyLauncherDisablePending", false)
                    writeAtomic(migration, record)
                }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
        // The old launcher and boot receiver stay disabled. Private files and
        // the package are retained until external acceptance permits removal.
        record.put("stage", "completed").put("completedAt", System.currentTimeMillis())
            .put("legacyPackageRetainedForRollback", legacy != null)
        writeAtomic(migration, record)
        return record
    }

    private fun requireRootAndSecurity() {
        val report = root.check(true)
        if (!report.optBoolean("verified")) throw GuestProvisioningException("GUEST_ROOT_UNAVAILABLE",
            report.optString("message"), report.optString("recovery"))
        root.approved("[ \"$(/system/bin/getenforce)\" = Disabled ] || { echo 'Unsupported guest security configuration'; exit 74; }")
    }

    private fun installRootCompatibility() {
        if (entryExists("/system/bin/app_process32")) return
        check(profile.regularOwned(File("/system/bin/app_process64"), 0) &&
            profile.sha256(File("/system/bin/app_process64")) == APP_PROCESS_SHA) { "Unknown app_process64; root compatibility link was not created" }
        val record = (readJournal(state) ?: JSONObject()).put("schema", 1)
            .put("rootCompatibilityInstalledByThisApplication", true)
        writeAtomic(state, record)
        root.approved("""
            [ ! -e /system/bin/app_process32 ] && [ ! -L /system/bin/app_process32 ] || exit 81
            [ "${'$'}(/system/bin/sha256sum /system/bin/app_process64 | /system/bin/cut -d' ' -f1)" = '$APP_PROCESS_SHA' ] || exit 82
            /system/bin/ln -s app_process64 /system/bin/app_process32
            [ "${'$'}(/system/bin/readlink /system/bin/app_process32)" = app_process64 ]
        """.trimIndent())
    }

    private fun prepareJpegBaseline(progress: (String, String) -> Unit) {
        progress("jpeg", "Preparing the verified guest JPEG importer and encoder compatibility")
        val importer = File(profile.IMPORTER)
        var original = importer.readBytes()
        // A previous interrupted repair is recovered by its strict journal before a retry.
        if (entryExists(JPEG_JOURNAL) || entryExists(JPEG_LEGACY_JOURNAL)) {
            runRepair("restore", null)
            original = importer.readBytes()
        }
        check(profile.sha256(importer) == profile.IMPORTER_ORIGINAL)
        check(original.size == 200848 && original.sliceArray(0x15d0c..0x15d0f).contentEquals(byteArrayOf(0x27, 0x63, 0x40, 0xb9.toByte())))
        val patched = original.copyOf()
        byteArrayOf(0xe7.toByte(), 0x2f, 0x40, 0xb9.toByte()).copyInto(patched, 0x15d0c)
        val candidate = File(directory, "jpeg-importer.so")
        writeBytesAtomic(candidate, patched)
        check(profile.sha256(candidate) == profile.IMPORTER_REPAIRED)
        // Persist intent before the script writes its independent root-owned system journal.
        val record = (readJournal(state) ?: JSONObject()).put("schema", 1).put("jpegInstalledByThisApplication", true)
            .put("prepared", false).put("jpegPrepared", false)
        writeAtomic(state, record)
        runRepair("install", candidate)
        // The JPEG transaction finishes before the later bridge installation.
        // Publish its own checkpoint: using only the whole-setup `prepared`
        // flag makes the successful repair look interrupted to inspect(), so
        // first-time preparation can never pass its baseline check.
        check(profile.sha256(importer) == profile.IMPORTER_REPAIRED &&
            profile.regularOwned(File(IMPORTER_BACKUP), 0) &&
            profile.sha256(File(IMPORTER_BACKUP)) == profile.IMPORTER_ORIGINAL &&
            jpegCompatibility() == "helper-symlink") { "Guest JPEG repair did not retain its exact baseline and recovery copy" }
        runRepair("inspect", null)
        record.put("jpegPrepared", true).put("jpegPreparedAt", System.currentTimeMillis())
        writeAtomic(state, record)
    }

    private fun unfinishedOwnedJpeg(record: JSONObject?, halHash: String): Boolean =
        GuestPreparationRecovery.unfinishedOwnedJpeg(record?.optBoolean("jpegInstalledByThisApplication") == true,
            record?.optBoolean("prepared") == true || record?.optBoolean("jpegPrepared") == true, halHash == profile.FACTORY_HAL,
            entryExists(JPEG_JOURNAL) || entryExists(JPEG_LEGACY_JOURNAL))

    private fun runRepair(action: String, candidate: File?): String {
        check(action in setOf("inspect", "install", "restore"))
        val bytes = context.assets.open("provisioning/vmos-guest-jpeg-repair.sh").use { it.readBytes() }
        // Execute the APK's verified asset via the anonymous operation pipe.
        // This guest's mapped private-file permissions cannot protect a script
        // pathname from replacement by another virtual UID.
        val arguments = "set -- $action" + (candidate?.let { " ${q(it.absolutePath)}" } ?: "")
        return root.approved(arguments + "\n" + bytes.toString(Charsets.UTF_8), 45)
    }

    private data class LegacyIdentity(val uid: Int, val files: File, val receiverState: Int, val setupState: Int)
    private fun legacyIdentity(): LegacyIdentity? = try {
        val manager = context.packageManager
        val info = manager.getApplicationInfo(LEGACY_PACKAGE, 0)
        if (manager.checkSignatures(context.packageName, LEGACY_PACKAGE) != PackageManager.SIGNATURE_MATCH) null
        else {
            check(info.uid >= 10000 && info.uid != Process.myUid())
            check(info.dataDir in setOf("/data/user/0/$LEGACY_PACKAGE", "/data/data/$LEGACY_PACKAGE"))
            LegacyIdentity(info.uid, File(info.dataDir, "files"),
                manager.getComponentEnabledSetting(ComponentName(LEGACY_PACKAGE, LEGACY_RECEIVER)),
                manager.getComponentEnabledSetting(ComponentName(LEGACY_PACKAGE, LEGACY_SETUP)))
        }
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun migrateLegacy(progress: (String, String) -> Unit) {
        // After an explicit Restore, the factory HAL needs a new local bridge;
        // the preserved disabled legacy package must not be resurrected.
        val historical = readJournal(migration)
        if (historical?.optString("stage") == "restored" && profile.sha256(File(profile.HAL)) == profile.FACTORY_HAL) return
        val legacy = legacyIdentity() ?: run {
            check(profile.sha256(File(profile.HAL)) == profile.FACTORY_HAL) {
                "An existing bridge has no matching signed legacy installation journal; automatic adoption was refused"
            }
            return
        }
        val pending = readJournal(migration)
        if (pending?.optString("stage") == "adopted") {
            check(installation.isFile) { "Migration journal is adopted but installation record is missing" }; return
        }
        if (pending != null && pending.optString("stage") != "rolled_back") {
            rollbackLegacyMigration()
        }
        val oldInstallation = File(legacy.files, "guest-installation.json")
        val oldJson = rootReadLegacyFile(legacy, oldInstallation, 65536)
        val oldRecord = JSONObject(oldJson)
        validateInstallation(oldRecord)
        val status = readLegacyStatus(legacy)
        val pid = status?.optInt("pid", -1) ?: -1
        val proxy = if (pid > 1 && rootProcessExists(pid)) legacyProxyIdentity(legacy, pid) else null
        val socket = rootSocketIdentity(legacy.uid)
        if (socket != null && proxy == null) requireStaleSocket()
        val record = JSONObject().put("schema", 1).put("stage", "prepared").put("legacyUid", legacy.uid)
            .put("legacyFiles", legacy.files.absolutePath).put("legacyReceiverState", legacy.receiverState)
            .put("legacySetupActivityState", legacy.setupState)
            .put("legacyInstallation", oldRecord).put("legacyInstallationText", oldJson)
            .put("proxy", proxy ?: JSONObject.NULL).put("socketDevice", socket?.device ?: -1)
            .put("socketInode", socket?.inode ?: -1).put("socketRootViewUid", socket?.uid ?: -1)
            .put("socketRootViewGid", socket?.gid ?: -1)
            .put("metadataAdapter", "vmos-rom10075-caller-mapped-stat-v1")
            .put("legacyJournalOwnership", "signed-package-path-and-pinned-installation-content")
            .put("createdAt", System.currentTimeMillis())
        writeAtomic(migration, record)
        progress("migration", "Handing the verified legacy camera service to the integrated broker")
        try {
            root.approved("/system/bin/pm disable --user 0 ${q("$LEGACY_PACKAGE/$LEGACY_RECEIVER")} && /system/bin/am force-stop --user 0 $LEGACY_PACKAGE")
            if (proxy != null && rootProcessExists(pid)) stopLegacyProxy(legacy, proxy)
            // Ownership transfer is only allowed after the exact old process has stopped.
            check(proxy == null || !rootProcessExists(pid)) { "The verified legacy proxy did not stop" }
            val now = rootSocketIdentity(legacy.uid)
            if (now != null) {
                check(socket != null && now == socket) { "Legacy transport metadata changed during handoff" }
                requireStaleSocket()
                transferStaleSocket(now, Process.myUid())
            }
            val adopted = JSONObject(oldRecord.toString()).put("migrationSource", LEGACY_PACKAGE)
            writeAtomic(installation, adopted)
            record.put("stage", "adopted").put("adoptedAt", System.currentTimeMillis())
            writeAtomic(migration, record)
        } catch (error: Throwable) {
            runCatching { rollbackLegacyMigration() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    private fun recoverPublishedMigration() {
        val record = readJournal(migration) ?: return
        if (record.optString("stage") != "prepared") return
        val adopted = JSONObject(installation.readText())
        check(adopted.optString("migrationSource") == LEGACY_PACKAGE)
        validateInstallation(adopted)
        val old = legacyIdentity() ?: error("Matching legacy package is required to recover the handoff")
        check(old.uid == record.getInt("legacyUid"))
        val socket = rootSocketIdentity(Process.myUid())
        check(socket == null || socket.device == record.optLong("socketDevice") && socket.inode == record.optLong("socketInode")) {
            "Interrupted migration transport differs from its recorded handoff"
        }
        if (socket != null) requireStaleSocket()
        // The record is published only after receiver disable, force-stop and
        // verified process shutdown. Rechecking the old stopped flag avoids
        // adopting a package that the user manually restarted in between.
        val app = context.packageManager.getApplicationInfo(LEGACY_PACKAGE, 0)
        check(app.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED != 0)
        record.put("stage", "adopted").put("recoveredAt", System.currentTimeMillis())
        writeAtomic(migration, record)
    }

    private fun validateInstallation(record: JSONObject) {
        val actual = profile.sha256(File(profile.HAL))
        check(record.optString("originalSha256") == profile.FACTORY_HAL && record.optString("socket") == profile.SOCKET)
        check(record.optString("stage") == "configured" && record.optString("patchedSha256") == actual)
        check(actual in profile.knownHalHashes - profile.FACTORY_HAL)
        check(profile.sha256(File("/vendor/lib64/hw/.vcam-original-${profile.FACTORY_HAL}")) == profile.FACTORY_HAL)
        check(record.optInt("originalUid", -1) == 0 && record.optInt("originalGid", -1) >= 0 && record.optInt("originalMode", -1) in 0..4095)
        if (actual == profile.CROP_WRAPPER) {
            check(record.optString("profile") == "vmos-camera3-crop-v1" &&
                record.optString("transportPath") == "/vendor/lib64/hw/.vcam-transport-camera.vphw90.so" &&
                record.optString("transportSha256") == profile.REPAIRED_TRANSPORT)
            check(profile.sha256(File(record.getString("transportPath"))) == profile.REPAIRED_TRANSPORT)
        }
    }

    private fun legacyProxyIdentity(legacy: LegacyIdentity, pid: Int): JSONObject {
        check(pid > 1 && pid != Process.myPid())
        val expected = listOf(File(legacy.files, "vmos-camera-proxy").absolutePath, "--listen", profile.SOCKET,
            "--original", "/dev/socket/socket/qemu_pipe", "--state-dir", File(legacy.files, "camera-runtime").absolutePath)
        val command = rootReadProc(pid, "cmdline", 4096).trimEnd('\u0000').split('\u0000')
        check(command == expected) { "Legacy proxy command line differs from the signed broker's fixed process" }
        val status = rootReadProc(pid, "status", 16384)
        val uids = status.lineSequence().firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
        check(uids?.size == 4 && uids.all { it.toIntOrNull() == legacy.uid }) { "Legacy proxy UID identity differs" }
        val executableHash = root.approved("/system/bin/sha256sum /proc/$pid/exe | /system/bin/cut -d' ' -f1").trim()
        val link = root.approved("/system/bin/readlink /proc/$pid/exe").trim()
        val bundled = context.assets.open("vmos-camera-proxy.sha256").bufferedReader().use { it.readText().trim() }
        val nativeHash = root.approved("/system/bin/sha256sum ${q(expected[0])} | /system/bin/cut -d' ' -f1").trim()
        check(nativeHash in profile.knownProxyHashes + bundled)
        check((link == expected[0] || link == "${expected[0]} (deleted)") && executableHash in profile.knownProxyHashes + bundled ||
            VmosHostIdentity.isPinnedLauncher(link, executableHash, fromProcExe = true))
        val stat = rootReadProc(pid, "stat", 4096)
        val ticks = stat.substring(stat.lastIndexOf(')') + 2).split(' ').getOrNull(19)
        check(ticks?.matches(Regex("[0-9]+")) == true)
        val socket = rootSocketIdentity(legacy.uid) ?: error("The verified legacy process has no transport socket")
        return JSONObject().put("pid", pid).put("startTicks", ticks).put("executableHash", executableHash)
            .put("nativeHash", nativeHash).put("command", org.json.JSONArray(expected))
            .put("socketDevice", socket.device).put("socketInode", socket.inode)
            .put("socketRootViewUid", socket.uid).put("socketRootViewGid", socket.gid)
    }

    private fun stopLegacyProxy(legacy: LegacyIdentity, identity: JSONObject) {
        val pid = identity.getInt("pid")
        check(legacyProxyIdentity(legacy, pid).toString() == identity.toString()) { "Legacy process identity changed before shutdown" }
        signalLegacyProxy(legacy, identity, "TERM")
        repeat(30) { if (!rootProcessExists(pid)) return; Thread.sleep(100) }
        check(legacyProxyIdentity(legacy, pid).toString() == identity.toString())
        signalLegacyProxy(legacy, identity, "KILL")
        repeat(20) { if (!rootProcessExists(pid)) return; Thread.sleep(100) }
        error("Verified legacy camera proxy did not stop within its bounded deadline")
    }

    private fun signalLegacyProxy(legacy: LegacyIdentity, identity: JSONObject, signal: String) {
        check(signal in setOf("TERM", "KILL"))
        val pid = identity.getInt("pid")
        val command = identity.getJSONArray("command")
        val expected = (0 until command.length()).joinToString("\n") { command.getString(it) }
        // Repeat every authorization check inside the same root shell immediately
        // before signaling. A PID read before a Superuser prompt is not sufficient.
        root.approved("""
            [ "${'$'}(/system/bin/sed 's/.*) //' /proc/$pid/stat | /system/bin/awk '{print ${'$'}20}')" = '${identity.getString("startTicks")}' ] || exit 81
            [ "${'$'}(/system/bin/awk '/^Uid:/ {print ${'$'}2 ":" ${'$'}3 ":" ${'$'}4 ":" ${'$'}5}' /proc/$pid/status)" = '${legacy.uid}:${legacy.uid}:${legacy.uid}:${legacy.uid}' ] || exit 82
            [ "${'$'}(/system/bin/tr '\000' '\n' < /proc/$pid/cmdline)" = ${q(expected)} ] || exit 83
            [ "${'$'}(/system/bin/sha256sum /proc/$pid/exe | /system/bin/cut -d' ' -f1)" = '${identity.getString("executableHash")}' ] || exit 84
            [ "${'$'}(/system/bin/sha256sum ${q(command.getString(0))} | /system/bin/cut -d' ' -f1)" = '${identity.getString("nativeHash")}' ] || exit 85
            [ -S ${profile.SOCKET} ] && [ ! -L ${profile.SOCKET} ] || exit 86
            [ "${'$'}(/system/bin/stat -c '%u:%g:%d:%i:%h' ${profile.SOCKET})" = '${identity.getInt("socketRootViewUid")}:${identity.getInt("socketRootViewGid")}:${identity.getLong("socketDevice")}:${identity.getLong("socketInode")}:1' ] || exit 86
            /system/bin/kill -$signal $pid
        """.trimIndent())
    }

    private fun rollbackLegacyMigration() {
        val record = readJournal(migration) ?: return
        check(record.optString("stage") in setOf("prepared", "adopted")) { "Migration cannot be rolled back from this state" }
        val legacy = legacyIdentity() ?: error("Matching signed legacy package is unavailable for rollback")
        check(legacy.uid == record.getInt("legacyUid") && legacy.files.absolutePath == record.getString("legacyFiles"))
        validateInstallation(record.getJSONObject("legacyInstallation"))
        // Never overwrite a newly active integrated proxy or another changed socket.
        val socket = rootSocketIdentity(Process.myUid(), legacy.uid)
        if (socket != null) {
            check(socket.device == record.optLong("socketDevice") && socket.inode == record.optLong("socketInode")) {
                "The new broker is running or changed its transport; stop and restore it before rolling migration back"
            }
            val oldProxy = record.optJSONObject("proxy")
            val oldPid = oldProxy?.optInt("pid", -1) ?: -1
            val unchangedOldProxy = oldPid > 1 && rootProcessExists(oldPid) &&
                legacyProxyIdentity(legacy, oldPid).toString() == oldProxy.toString()
            // A failure before stopping the old process leaves its original
            // transport alive. Preserve it and restore its component states;
            // no ownership mutation is needed in that case.
            if (!unchangedOldProxy) {
                requireStaleSocket()
                transferStaleSocket(socket, legacy.uid)
            }
        }
        if (installation.isFile) {
            val adopted = JSONObject(installation.readText())
            check(adopted.optString("migrationSource") == LEGACY_PACKAGE)
            check(installation.delete())
        }
        if (record.has("legacySetupActivityState")) setLegacyComponent(LEGACY_SETUP, record.getInt("legacySetupActivityState"))
        setLegacyComponent(LEGACY_RECEIVER, record.getInt("legacyReceiverState"))
        root.approved("/system/bin/am start-foreground-service --user 0 ${q("$LEGACY_PACKAGE/com.virtualcamerapro.broker.BrokerService")}")
        record.put("stage", "rolled_back").put("rolledBackAt", System.currentTimeMillis())
        writeAtomic(migration, record)
    }

    private fun setLegacyComponent(className: String, state: Int) {
        check(className in setOf(LEGACY_RECEIVER, LEGACY_SETUP))
        val command = when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default-state"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disable-user"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disable-until-used"
            else -> error("Unrecognized legacy component state was preserved")
        }
        root.approved("/system/bin/pm $command --user 0 ${q("$LEGACY_PACKAGE/$className")}")
        check(context.packageManager.getComponentEnabledSetting(ComponentName(LEGACY_PACKAGE, className)) == state) {
            "Legacy component state did not match its requested verified transition"
        }
    }

    /**
     * ROM10075 rewrites pathname stat UID to the caller: root sees 0 and an
     * ordinary app sees its own UID, including for another app's files. That
     * value cannot authenticate the old broker. Only fixed paths belonging to
     * the matching signed package are read; installation bytes and all live
     * process credentials are independently pinned below/above. No arbitrary
     * root-owned file or journal-supplied pathname is accepted by this adapter.
     */
    private fun rootReadLegacyFile(legacy: LegacyIdentity, file: File, maximum: Int): String {
        profile.requireSupportedGuest()
        val current = legacyIdentity()
        check(current != null && current.uid == legacy.uid && current.files == legacy.files) {
            "Signed legacy package identity changed before its migration record was read"
        }
        check(maximum in 1..65536)
        val allowed = setOf(File(legacy.files, "guest-installation.json"), File(legacy.files, "camera-runtime/status.json"))
        check(file in allowed) { "Only fixed legacy migration records may be read" }
        val parents = listOfNotNull(legacy.files.parentFile, legacy.files,
            file.parentFile.takeIf { it != legacy.files })
        val parentChecks = parents.mapIndexed { index, parent -> """
            [ -d ${q(parent.absolutePath)} ] || { printf '%s\n' ${q("Migration parent $index is missing or not a directory: ${parent.absolutePath}")}; exit 80; }
            [ ! -L ${q(parent.absolutePath)} ] || { printf '%s\n' ${q("Migration parent $index is a symbolic link: ${parent.absolutePath}")}; exit 80; }
            parent_$index=${'$'}(/system/bin/stat -c '%u:%g:%f:%d:%i' ${q(parent.absolutePath)}) || { printf '%s\n' ${q("Cannot inspect migration parent $index metadata: ${parent.absolutePath}")}; exit 80; }
            parent_mode=${'$'}(/system/bin/stat -c %a ${q(parent.absolutePath)}) || { printf '%s\n' ${q("Cannot inspect migration parent $index mode: ${parent.absolutePath}")}; exit 80; }
            ${nonWorldWritableMode("parent_mode", "Migration parent $index: ${parent.absolutePath}", 80)}
        """.trimIndent() }.joinToString("\n")
        val parentRechecks = parents.mapIndexed { index, parent ->
            "[ ! -L ${q(parent.absolutePath)} ] && [ \"${'$'}(/system/bin/stat -c '%u:%g:%f:%d:%i' ${q(parent.absolutePath)})\" = \"${'$'}parent_$index\" ] || exit 84"
        }.joinToString("\n")
        val readAndRecheck = if (file == File(legacy.files, "camera-runtime/status.json")) """
            exec 3<${q(file.absolutePath)} || { printf '%s\n' 'Cannot open the fixed legacy status snapshot'; exit 83; }
            expected_fd=${'$'}(printf '%s' "${'$'}before" | /system/bin/cut -d: -f3,5-7)
            opened_fd=${'$'}(/system/bin/stat -L -c '%f:%d:%i:%s' /proc/${'$'}${'$'}/fd/3) || exit 83
            [ "${'$'}opened_fd" = "${'$'}expected_fd" ] || { printf '%s\n' 'Legacy status changed before its snapshot was opened'; exit 84; }
            case "${'$'}(/system/bin/stat -L -c %h /proc/${'$'}${'$'}/fd/3)" in 0|1) ;; *) exit 82 ;; esac
            /system/bin/head -c $maximum <&3 || exit 83
            [ "${'$'}(/system/bin/stat -L -c '%f:%d:%i:%s' /proc/${'$'}${'$'}/fd/3)" = "${'$'}opened_fd" ] || exit 84
            exec 3<&-
        """.trimIndent() else """
            /system/bin/head -c $maximum ${q(file.absolutePath)} || exit 83
            [ ! -L ${q(file.absolutePath)} ] && [ "${'$'}(/system/bin/stat -c '%u:%g:%f:%h:%d:%i:%s' ${q(file.absolutePath)})" = "${'$'}before" ] || exit 84
        """.trimIndent()
        return root.approved("""
            $parentChecks
            [ -f ${q(file.absolutePath)} ] && [ ! -L ${q(file.absolutePath)} ] || exit 81
            file_uid=${'$'}(/system/bin/stat -c %u ${q(file.absolutePath)}) || exit 82
            case "${'$'}file_uid" in ${legacy.uid}|0) ;; *) exit 82 ;; esac
            [ "${'$'}(/system/bin/stat -c %h ${q(file.absolutePath)})" = 1 ] || exit 82
            file_mode=${'$'}(/system/bin/stat -c %a ${q(file.absolutePath)}) || exit 82
            ${nonWorldWritableMode("file_mode", "Migration record: ${file.absolutePath}", 82)}
            file_size=${'$'}(/system/bin/stat -c %s ${q(file.absolutePath)}) || exit 83
            [ "${'$'}file_size" -ge 0 ] && [ "${'$'}file_size" -le $maximum ] || exit 83
            before=${'$'}(/system/bin/stat -c '%u:%g:%f:%h:%d:%i:%s' ${q(file.absolutePath)}) || exit 83
            $readAndRecheck
            $parentRechecks
        """.trimIndent())
    }

    private fun readLegacyStatus(legacy: LegacyIdentity): JSONObject? {
        val file = File(legacy.files, "camera-runtime/status.json")
        val present = root.approved("if [ -e ${q(file.absolutePath)} ] || [ -L ${q(file.absolutePath)} ]; then printf PRESENT; else printf MISSING; fi").trim()
        if (present == "MISSING") return null
        check(present == "PRESENT") { "Could not inspect the fixed legacy status pathname" }
        repeat(3) { attempt ->
            try {
                return JSONObject(rootReadLegacyFile(legacy, file, 32768)).also {
                    check(it.optInt("protocol", -1) == 2 && it.optInt("pid", -1) > 1) {
                        "Legacy status does not contain a valid protocol-2 process hint"
                    }
                }
            } catch (error: Exception) {
                if (attempt == 2) throw IllegalStateException(
                    "Could not read the changing legacy camera status after three bounded snapshots: ${error.message}", error)
                Thread.sleep(100)
            }
        }
        error("Unreachable legacy status snapshot state")
    }

    /** Ordinary File.exists may hide another VMOS app's proc entry from this caller. */
    private fun rootProcessExists(pid: Int): Boolean {
        check(pid > 1 && pid != Process.myPid())
        return when (root.approved("""
            if [ -d /proc/$pid ]; then
                [ -r /proc/$pid/stat ] && [ -r /proc/$pid/status ] && [ -r /proc/$pid/cmdline ] || { printf '%s\n' 'Guest root cannot inspect the legacy process'; exit 81; }
                printf PRESENT
            else printf MISSING; fi
        """.trimIndent()).trim()) {
            "PRESENT" -> true
            "MISSING" -> false
            else -> error("Could not determine legacy process liveness under guest root")
        }
    }

    // Android mksh treats 0771 as decimal unless the base is explicit. Test
    // octal permission digits directly so neither shell arithmetic rules nor
    // malformed/empty stat output can weaken this guard.
    private fun nonWorldWritableMode(variable: String, description: String, code: Int): String {
        check(variable in setOf("parent_mode", "file_mode") && code in setOf(80, 82))
        return """
            case "${'$'}$variable" in ''|*[!0-7]*) printf '%s: %s\n' ${q("$description has invalid octal mode")} "${'$'}$variable"; exit $code ;; esac
            [ "${'$'}{#$variable}" -le 4 ] || { printf '%s: %s\n' ${q("$description has oversized octal mode")} "${'$'}$variable"; exit $code; }
            case "${'$'}$variable" in *[2367]) printf '%s: %s\n' ${q("$description is writable by others")} "${'$'}$variable"; exit $code ;; esac
        """.trimIndent()
    }

    private data class RootSocketIdentity(val uid: Int, val gid: Int, val device: Long, val inode: Long) {
        fun fingerprint() = "$uid:$gid:$device:$inode:1"
    }

    /** Never mix caller-mapped Os.stat ownership with root's mapped stat view. */
    private fun rootSocketIdentity(vararg expectedUids: Int): RootSocketIdentity? {
        profile.requireSupportedGuest()
        check(expectedUids.isNotEmpty() && expectedUids.all { it >= 10000 })
        val output = root.approved("""
            if [ ! -e ${profile.SOCKET} ] && [ ! -L ${profile.SOCKET} ]; then printf MISSING; exit 0; fi
            [ -S ${profile.SOCKET} ] && [ ! -L ${profile.SOCKET} ] || exit 81
            /system/bin/stat -c '%u:%g:%d:%i:%h' ${profile.SOCKET}
        """.trimIndent()).trim()
        if (output == "MISSING") return null
        val values = output.split(':')
        check(values.size == 5 && values[4] == "1") { "Transport metadata is incomplete or has extra links" }
        val identity = RootSocketIdentity(values[0].toInt(), values[1].toInt(), values[2].toLong(), values[3].toLong())
        check(identity.uid in expectedUids || identity.uid == 0) { "Transport owner is outside the exact guest metadata adapter" }
        check(identity.gid >= 0 && identity.device >= 0 && identity.inode > 0)
        return identity
    }

    /** Caller already proved disconnection; repeat fixed pathname/inode checks at mutation. */
    private fun transferStaleSocket(identity: RootSocketIdentity, targetUid: Int) {
        check(targetUid >= 10000)
        root.approved("""
            [ -S ${profile.SOCKET} ] && [ ! -L ${profile.SOCKET} ] || exit 81
            [ "${'$'}(/system/bin/stat -c '%u:%g:%d:%i:%h' ${profile.SOCKET})" = '${identity.fingerprint()}' ] || exit 82
            /system/bin/chown $targetUid:$targetUid ${profile.SOCKET}
            [ -S ${profile.SOCKET} ] && [ ! -L ${profile.SOCKET} ] || exit 83
            [ "${'$'}(/system/bin/stat -c '%d:%i:%h' ${profile.SOCKET})" = '${identity.device}:${identity.inode}:1' ] || exit 83
        """.trimIndent())
    }
    private fun rootReadProc(pid: Int, field: String, maximum: Int): String {
        check(pid > 1 && field in setOf("cmdline", "status", "stat"))
        return root.approved("/system/bin/head -c $maximum /proc/$pid/$field")
    }
    private fun requireStaleSocket() {
        android.net.LocalSocket().use { socket ->
            try {
                socket.connect(android.net.LocalSocketAddress(profile.SOCKET, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (error: java.io.IOException) {
                // Android wraps ErrnoException in IOException; only a proven stale pathname is transferable.
                var cause: Throwable? = error
                while (cause != null) {
                    if (cause is android.system.ErrnoException && cause.errno in setOf(OsConstants.ECONNREFUSED, OsConstants.ENOENT)) return
                    cause = cause.cause
                }
                // Android 9 LocalSocketImpl does not retain errno as a cause.
                if (error.message?.matches(Regex(".*(?:ECONNREFUSED|ENOENT|Connection refused|No such file or directory).*")) == true) return
                throw IllegalStateException("Transport socket liveness could not be established", error)
            }
            error("A live camera proxy still owns the legacy transport; ownership was preserved")
        }
    }
    private fun jpegCompatibility(): String = runCatching {
        val file = File(profile.JPEG_COMPAT)
        val stat = Os.lstat(file.absolutePath)
        when {
            stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFLNK && Os.readlink(file.absolutePath) == profile.JPEG_HELPER -> "helper-symlink"
            else -> "unknown"
        }
    }.getOrElse { if (entryExists(profile.JPEG_COMPAT)) "unknown" else "missing" }
    private fun ensurePrivateDirectory() {
        check(directory.exists() || directory.mkdir()); Os.chmod(directory.absolutePath, 0x1c0)
        val stat = Os.lstat(directory.absolutePath)
        check(stat.st_uid == Process.myUid() && stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && stat.st_mode and 0x3f == 0)
    }
    private fun readJournal(file: File): JSONObject? {
        if (!file.exists()) return null
        check(profile.regularOwned(file, Process.myUid()) && file.length() <= 131072) { "Unrecognized private provisioning journal was preserved" }
        return JSONObject(file.readText())
    }
    private fun writeAtomic(file: File, json: JSONObject) = writeBytesAtomic(file, json.toString().toByteArray())
    private fun writeBytesAtomic(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }; Os.chmod(temporary.absolutePath, 0x180)
            check(temporary.renameTo(file)) { "Could not atomically publish guest provisioning state" }
        } finally { temporary.delete() }
    }
    private fun entryExists(path: String): Boolean = runCatching { Os.lstat(path); true }.getOrDefault(false)
    private fun q(value: String) = RootAccessManager.quote(value)

    companion object {
        const val LEGACY_PACKAGE = "com.virtualcamerapro.broker"
        const val LEGACY_RECEIVER = "com.virtualcamerapro.broker.GuestBootReceiver"
        const val LEGACY_SETUP = "com.virtualcamerapro.broker.SetupActivity"
        private const val IMPORTER_BACKUP = "/vendor/lib64/.vcam-camera-device-3.2-original-be8cbff7.so"
        private const val JPEG_JOURNAL = "/data/local/tmp/vcam-jpeg-repair-v1.txt"
        private const val JPEG_LEGACY_JOURNAL = "/data/local/tmp/vcam-jpeg-repair.json"
        private const val APP_PROCESS_SHA = "88084c741dd5cd83bb4833fd9871bfccb0137d57e9b66a930fa890262e4b664a"
    }
}
