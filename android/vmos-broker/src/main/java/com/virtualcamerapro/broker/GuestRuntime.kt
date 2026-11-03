package com.virtualcamerapro.broker

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Base64
import org.json.JSONObject
import com.virtualcamerapro.probe.CameraFrameProbe
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class GuestFailure(val code: String, message: String, val recovery: String) : RuntimeException(message)

/** Restricts every installation operation to one measured guest HAL, never the phone HAL. */
internal class GuestRuntime(private val context: Context) {
    val stateDir = File(context.filesDir, "camera-runtime")
    private val executable = File(context.filesDir, "vmos-camera-proxy")
    private val previousExecutable = File(context.filesDir, "vmos-camera-proxy.previous")
    private val proxyDeployment = File(context.filesDir, "proxy-deployment.json")
    private val original = File(HAL)
    private val cropTransport = File(CROP_TRANSPORT)
    private val backup = File("/vendor/lib64/hw/.vcam-original-$ORIGINAL_SHA")
    private val installRecord = File(context.filesDir, "guest-installation.json")
    private val baselineFailure = File(context.filesDir, "guest-baseline-failure.json")
    private var process: Process? = null
    private val integrated get() = context.packageName == "com.virtualcamerapro"

    fun requireGuest() {
        if (Build.VERSION.SDK_INT != 28 || Build.HARDWARE != "vphw90" || "arm64-v8a" !in Build.SUPPORTED_ABIS)
            throw GuestFailure("VMOS_GUEST_REQUIRED", "This camera backend requires the validated ARM64 Android 9 vphw90 guest",
                if (integrated) "Open Virtual Camera Pro inside the compatible VMOS guest and use its setup assistant. This APK does not alter the physical phone."
                else "Open the controller and broker inside that VMOS guest. This APK does not alter the physical phone.")
    }

    fun identity(): JSONObject = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("hardware", Build.HARDWARE)
        .put("fingerprint", Build.FINGERPRINT).put("backend", BACKEND).put("runtimeDirectory", stateDir.absolutePath)

    private fun prepareExecutable() {
        requireGuest()
        if (!stateDir.exists() && !stateDir.mkdir()) error("Cannot create the private guest runtime directory")
        Os.chmod(stateDir.absolutePath, 0x1c0) // 0700
        val expected = context.assets.open("vmos-camera-proxy.sha256").bufferedReader().use { it.readText().trim() }
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled proxy digest" }
        if (!executable.isFile || sha256(executable) != expected) {
            if (executable.isFile && sha256(executable) in KNOWN_PROXY_SHAS)
                writeAtomic(previousExecutable, executable.readBytes(), 0x1c0)
            val temporary = File(context.filesDir, "vmos-camera-proxy.new")
            context.assets.open("vmos-camera-proxy").use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() } }
            check(sha256(temporary) == expected) { "Bundled proxy SHA256 mismatch" }
            Os.chmod(temporary.absolutePath, 0x1c0)
            check(temporary.renameTo(executable)) { "Cannot install private proxy executable" }
        }
    }

    fun runProxy(vararg arguments: String): String {
        val child = ProcessBuilder(listOf(executable.absolutePath) + arguments).redirectErrorStream(true).start()
        val text = StringBuilder()
        val reader = Thread {
            child.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; synchronized(text) { if (text.length < 32768) text.append(buffer, 0, minOf(size, 32768 - text.length)) } }
            }
        }.apply { isDaemon = true; start() }
        if (!child.waitFor(8, TimeUnit.SECONDS)) { child.destroyForcibly(); reader.join(1000); throw GuestFailure("PROXY_HANDSHAKE_TIMEOUT", "The actual guest camera factory did not complete its handshake", "The VMOS camera transport must be identified before installing a bridge.") }
        reader.join(1000)
        val result = synchronized(text) { text.toString() }
        if (child.exitValue() != 0) throw GuestFailure("PROXY_HANDSHAKE_FAILED", result.take(4096).ifBlank { "Native camera proxy exited with ${child.exitValue()}" }, "Inspect the real guest transport; no HAL change is permitted until its factory query succeeds.")
        return result
    }

    private fun launchProxy(): Process {
        val uid = android.os.Process.myUid()
        check(uid >= 10000) { "The camera worker must belong to the ordinary application UID" }
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        // Enter through the guest's ordinary system shell and verify its UID
        // before exec. The inspected VMOS build can mislabel a direct private
        // ELF launch immediately after its root installer has finished.
        val command = "[ \"$(/system/bin/id -u)\" = '$uid' ] || exit 73\nexec " +
            listOf(executable.absolutePath,"--listen",SOCKET,"--original",ORIGINAL_SOCKET,"--state-dir",stateDir.absolutePath).joinToString(" ") { quote(it) }
        return ProcessBuilder("/system/bin/sh","-c",command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(context.filesDir,"proxy.log"))).start()
    }

    @Synchronized fun start(): String {
        prepareExecutable()
        if (process?.isAlive != true) {
            // An existing native proxy may outlive a Java service restart. Never
            // unlink its listening socket or kill a process by an untrusted PID.
            val existing = runCatching { runProxy("--list-cameras", "--original", SOCKET) }.getOrNull()
            if (existing != null && existing.contains("name=")) return existing
            val direct = runProxy("--list-cameras")
            check(direct.contains("name=")) { "The original camera factory returned no named cameras" }
            runProxy("--selftest")
            process = launchProxy()
        }
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (process?.isAlive == false) throw GuestFailure("PROXY_START_FAILED", "The guest proxy exited with code ${process?.exitValue()} before its camera socket became ready", "Read the broker's private proxy log and check guest socket access.")
            val query = runCatching { runProxy("--list-cameras", "--original", SOCKET) }.getOrNull()
            if (query != null && query.contains("name=")) {
                val identity = proxyIdentity(status().getInt("pid"))
                writeAtomic(proxyDeployment, JSONObject().put("pid", identity.pid).put("startTicks", identity.startTicks)
                    .put("sha256", sha256(executable)).toString().toByteArray(), 0x180)
                // Strict signal authorization belongs to explicit upgrades;
                // ordinary transport liveness remains a real factory query.
                return query
            }
            Thread.sleep(100)
        }
        throw GuestFailure("PROXY_NOT_REACHABLE", "The proxy did not forward an actual camera factory query", "No HAL change was made. Check guest socket permissions.")
    }

    private fun installationRecord(requireConfigured: Boolean): JSONObject {
        requireGuest()
        if (!installRecord.isFile) {
            val previous = runCatching { JSONObject(baselineFailure.readText()) }.getOrNull()
            if (previous?.optString("originalSha256") == ORIGINAL_SHA && sha256(original) == ORIGINAL_SHA)
                throw GuestFailure("VMOS_ORIGINAL_CAMERA_PIPELINE_FAILED", previous.getString("message"), previous.getString("recovery"))
            throw GuestFailure("VMOS_SETUP_REQUIRED", "The guest camera bridge has not been installed and verified",
                if (integrated) "Open Virtual Camera Pro inside the guest and complete its setup assistant."
                else "Open Virtual Camera VMOS Guest Service inside the guest and run its setup check.")
        }
        val record = JSONObject(installRecord.readText())
        val cropProfile = record.optString("profile") == CROP_PROFILE
        val expected = if (cropProfile) bundledWrapperHash() else record.optString("patchedSha256").also {
            check(it in setOf(LEGACY_PATCHED_SHA, PATCHED_SHA)) { "Unknown guest transport revision" }
        }
        check(record.optString("originalSha256") == ORIGINAL_SHA && record.optString("patchedSha256") == expected && record.optString("socket") == SOCKET) { "Guest installation record does not match this backend" }
        if (cropProfile) check(record.optString("transportPath") == CROP_TRANSPORT && record.optString("transportSha256") == PATCHED_SHA &&
            record.optInt("transportMode") == 0x1a4 && record.optString("previousHalSha256") == PATCHED_SHA) { "Guest crop installation record does not match its pinned transport" }
        if (requireConfigured && record.optString("stage") != "configured") throw GuestFailure("VMOS_SETUP_INCOMPLETE", "The guest camera bridge setup did not finish its provider and Camera2 verification", "Use the guest setup screen to restore the preserved original camera before retrying setup.")
        return record
    }

    fun installed(): JSONObject {
        val record = installationRecord(true)
        val expected = record.getString("patchedSha256")
        if (!original.isFile || sha256(original) != expected)
            throw GuestFailure("VMOS_HAL_NOT_CONFIGURED", "The guest camera HAL does not match the verified bridge installation", "Use the guest setup screen to inspect or restore the exact original HAL.")
        if (!backup.isFile || sha256(backup) != ORIGINAL_SHA) throw GuestFailure("VMOS_RECOVERY_MISSING", "The original guest HAL recovery copy is missing or changed", "Restore the verified original guest HAL before attempting further configuration.")
        if (record.optString("profile") == CROP_PROFILE && (!cropTransport.isFile || sha256(cropTransport) != PATCHED_SHA ||
                Os.stat(cropTransport.absolutePath).st_mode and 0x1ff != 0x1a4))
            throw GuestFailure("VMOS_CROP_TRANSPORT_CHANGED", "The crop adapter's preserved transport HAL is missing or changed", "Restore the original guest camera using the setup screen before reinstalling this exact guest profile.")
        return record
    }

    fun hasInstallationRecord(): Boolean = installRecord.isFile

    /** The running worker's launch record, rather than replaced on-disk bytes. */
    fun hasCurrentProxyBundle(): Boolean = runCatching {
        val deployment = JSONObject(proxyDeployment.readText())
        val live = proxyIdentity(status().getInt("pid"))
        deployment.getInt("pid") == live.pid && deployment.getString("startTicks") == live.startTicks &&
            deployment.getString("sha256") == bundledProxyHash() && sha256(executable) == bundledProxyHash()
    }.getOrDefault(false)

    /** Factory-only liveness check. Never starts a proxy or restarts a provider. */
    fun checkTransport(): String {
        installed()
        val factory = runProxy("--list-cameras", "--original", SOCKET)
        check(factory.contains("name=")) { "The installed proxy returned no actual guest cameras" }
        return factory
    }

    /** Recover a configured bridge after guest/process restart, without reviving media. */
    fun recoverInstalled(allowRestart: Boolean = true, restart: () -> Unit): JSONObject {
        installed()
        val factory = start()
        val manager = context.getSystemService(CameraManager::class.java)
        var ids = manager.cameraIdList
        var restarted = false
        if (ids.isEmpty()) {
            if (!allowRestart) throw GuestFailure("VMOS_RECOVERY_REQUIRES_IDLE", "The guest provider exposes no cameras while selected media is in use",
                "Deactivate the guest virtual camera before recovering its provider.")
            // Recheck the complete pinned installation immediately before root
            // access. The callback independently verifies the exact provider.
            installed()
            restart()
            restarted = true
            val deadline = SystemClock.elapsedRealtime() + 8000
            do {
                ids = manager.cameraIdList
                if (ids.isNotEmpty()) break
                Thread.sleep(200)
            } while (SystemClock.elapsedRealtime() < deadline)
        }
        if (ids.isEmpty()) throw GuestFailure("VMOS_CAMERA_PROVIDER_NOT_READY", "The verified guest bridge still exposes no Camera2 cameras",
            "Open the guest service setup, inspect the transport and restore the preserved original guest camera if recovery fails.")
        return identity().put("installed", true).put("proxyFactoryVerified", true)
            .put("cameraFactory", factory).put("cameraIds", org.json.JSONArray(ids.toList()))
            .put("providerRestarted", restarted).put("sourceRestored", false)
    }

    /** Read-only checks plus the private proxy process; does not replace a HAL. */
    fun preflight(): JSONObject {
        requireGuest()
        val current = sha256(original)
        val alreadyInstalled = runCatching { installed() }.getOrNull()
        if (current != ORIGINAL_SHA && alreadyInstalled == null) throw GuestFailure("VMOS_HAL_UNRECOGNIZED", "The guest camera HAL hash is not the measured version", "Do not patch another VMOS release using this backend.")
        val cameras = start()
        return identity().put("currentHalSha256", current).put("cameraFactory", cameras)
            .put("proxyFactoryVerified", true).put("installed", alreadyInstalled != null)
    }

    /** Explicit setup operation only. The caller must have a tested provider restart path. */
    @Synchronized fun install(restart: () -> Unit): JSONObject {
        val checks = preflight()
        runCatching { installed() }.getOrNull()?.let {
            val current = if (it.optString("profile") != CROP_PROFILE && it.getString("patchedSha256") == LEGACY_PATCHED_SHA)
                upgradeScaleRepair(it, restart) else it
            return checks.put("installation", current).put("cameraPipeline", verifyCameraPipeline())
        }
        GuestRoot(context).verifyProvider()
        // A camera redirect cannot repair a broken VMOS graphics-buffer import.
        // Establish the original Camera2 baseline before changing the guest HAL.
        try { verifyCameraPipeline(); baselineFailure.delete() }
        catch (error: Throwable) {
            val message = "The latest test of the original VMOS camera failed to deliver real YUV and JPEG frames: ${error.message}"
            val recovery = "The original camera HAL was preserved. This VMOS ROM must support both preview and JPEG capture on this phone. Retry guest setup after repairing the VMOS camera pipeline."
            writeAtomic(baselineFailure, JSONObject().put("originalSha256", ORIGINAL_SHA)
                .put("message", message).put("recovery", recovery).toString(2).toByteArray(), 0x180)
            throw GuestFailure("VMOS_ORIGINAL_CAMERA_PIPELINE_FAILED", message, recovery)
        }
        check(sha256(original) == ORIGINAL_SHA) { "The original guest HAL changed during preflight" }
        val attributes = Os.stat(original.absolutePath)
        val originalMode = attributes.st_mode and 0xfff
        val label = try { Os.getxattr(original.absolutePath, "security.selinux") }
        catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENODATA && error.errno != OsConstants.ENOTSUP) throw error
            null
        }
        val bytes = original.readBytes()
        val old = ORIGINAL_SOCKET.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val replacement = SOCKET.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        check(replacement.size <= old.size)
        val offsets = (0..bytes.size - old.size).filter { at -> old.indices.all { bytes[at + it] == old[it] } }
        check(offsets.size == 1) { "Expected exactly one audited camera socket pathname" }
        val offset = offsets.single()
        repeat(old.size) { bytes[offset + it] = if (it < replacement.size) replacement[it] else 0 }
        applyScaleRepair(bytes)
        val patchedHash = digest(bytes)
        check(patchedHash == PATCHED_SHA) { "The staged guest camera redirect differs from the pinned audited patch" }
        if (backup.exists()) check(sha256(backup) == ORIGINAL_SHA) { "An existing recovery copy is not the exact original HAL" }
        else writeAtomic(backup, original.readBytes(), 0x1a4)
        val record = JSONObject().put("originalSha256", ORIGINAL_SHA).put("patchedSha256", patchedHash)
            .put("socket", SOCKET).put("patchOffset", offset).put("hardware", Build.HARDWARE).put("sdk", Build.VERSION.SDK_INT)
            .put("originalMode", originalMode).put("originalUid", attributes.st_uid).put("originalGid", attributes.st_gid)
            .put("originalSelinuxLabel", label?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
            .put("stage", "prepared")
        // Persist recovery attributes before the HAL rename. An interrupted setup
        // remains restorable while support refuses to advertise it as configured.
        writeAtomic(installRecord, record.toString().toByteArray(), 0x180)
        try {
            writeHal(bytes, record)
            check(sha256(original) == patchedHash) { "Guest HAL read-back hash differs from the staged bridge" }
            restart()
            val pipeline = verifyCameraPipeline()
            record.put("stage", "configured")
            writeAtomic(installRecord, record.toString().toByteArray(), 0x180)
            return checks.put("installation", record).put("cameraPipeline", pipeline)
        } catch (error: Throwable) {
            runCatching {
                check(sha256(original) in setOf(ORIGINAL_SHA, PATCHED_SHA) && sha256(backup) == ORIGINAL_SHA) { "Unknown HAL change prevents automatic rollback" }
                record.put("stage", "restoring")
                writeAtomic(installRecord, record.toString().toByteArray(), 0x180)
                writeHal(backup.readBytes(), record)
                restart()
                verifyCameraPipeline()
                installRecord.delete()
            }
                .onFailure { rollback -> error.addSuppressed(rollback) }
            throw error
        }
    }

    /** Exact ROM10075 output-size accounting fix; retain a reversible old profile. */
    private fun upgradeScaleRepair(previous: JSONObject, restart: () -> Unit): JSONObject {
        GuestRoot(context).verifyProvider()
        check(!File(stateDir, "source.cfg").exists()) { "Deactivate the source before updating the guest camera" }
        check(sha256(original) == LEGACY_PATCHED_SHA && sha256(backup) == ORIGINAL_SHA)
        val before = original.readBytes()
        val after = before.copyOf().also { applyScaleRepair(it) }
        check(digest(after) == PATCHED_SHA)
        val next = JSONObject(previous.toString()).put("patchedSha256", PATCHED_SHA)
            .put("scaleRepair", "nv21-scaled-allocation-bounds-v1")
            .put("previousInstallation", JSONObject(previous.toString())).put("stage", "prepared")
        writeAtomic(installRecord, next.toString().toByteArray(), 0x180)
        try {
            writeHal(after, next)
            check(sha256(original) == PATCHED_SHA)
            restart()
            verifyCameraPipeline()
            next.put("stage", "configured")
            writeAtomic(installRecord, next.toString().toByteArray(), 0x180)
            return next
        } catch (error: Throwable) {
            runCatching {
                check(sha256(original) in setOf(LEGACY_PATCHED_SHA, PATCHED_SHA) && sha256(backup) == ORIGINAL_SHA) {
                    "Unknown HAL change prevents scale-repair rollback"
                }
                next.put("stage", "restoring")
                writeAtomic(installRecord, next.toString().toByteArray(), 0x180)
                writeHal(before, previous)
                val recovery = JSONObject(previous.toString()).put("stage", "restoring")
                writeAtomic(installRecord, recovery.toString().toByteArray(), 0x180)
                restart()
                verifyCameraPipeline()
                recovery.put("stage", "configured")
                writeAtomic(installRecord, recovery.toString().toByteArray(), 0x180)
            }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    private fun applyScaleRepair(bytes: ByteArray) {
        // ELF virtual addresses 0x4b2e0/0x4b338/0x4b398 map to these file offsets.
        // Each load of original reply length becomes the allocated scaled Y+UV
        // size. Existing bounds branches and all input handling remain intact.
        val edits = listOf(
            Triple(0x452e0, "e43f40f9", "0403198b"),
            Triple(0x45338, "e83f40f9", "a8011b8b"),
            Triple(0x45398, "e83f40f9", "a8011b8b"))
        fun decode(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        check(bytes.size == 2109016 && digest(bytes) == LEGACY_PATCHED_SHA) { "Unsupported guest HAL before scale repair" }
        for ((offset, old, replacement) in edits) {
            val expected = decode(old)
            check(bytes.copyOfRange(offset, offset + 4).contentEquals(expected)) { "Scale-repair instruction differs" }
            decode(replacement).copyInto(bytes, offset)
        }
        check(digest(bytes) == PATCHED_SHA) { "Scale-repair result differs from audited binary" }
    }

    @Synchronized fun restore(restart: () -> Unit) {
        requireGuest()
        if (!backup.isFile || sha256(backup) != ORIGINAL_SHA) error("Exact original guest HAL is unavailable; restoration refused")
        val current = sha256(original)
        val record = if (installRecord.isFile) installationRecord(false) else null
        if (current != ORIGINAL_SHA) check(current == record?.getString("patchedSha256") ||
            (current == LEGACY_PATCHED_SHA && record?.optString("stage") in setOf("prepared", "restoring") &&
                record?.optJSONObject("previousInstallation")?.optString("patchedSha256") == LEGACY_PATCHED_SHA) ||
            (record?.optString("profile") == CROP_PROFILE && record.optString("stage") in setOf("prepared", "restoring") && current == PATCHED_SHA)) { "Unknown HAL change; restoration refused" }
        GuestRoot(context).verifyProvider()
        deactivate()
        record?.let { it.put("stage", "restoring"); writeAtomic(installRecord, it.toString().toByteArray(), 0x180) }
        if (current != ORIGINAL_SHA) writeHal(backup.readBytes(), requireNotNull(record))
        check(sha256(original) == ORIGINAL_SHA)
        restart()
        verifyCameraPipeline()
        // The restored HAL talks directly to the original factory. Retire its
        // now-unused owned proxy before dropping the recovery transaction, so
        // clearing this app's data cannot orphan an unverifiable live daemon.
        stopRestoredProxy()
        check(!installRecord.exists() || installRecord.delete()) { "Could not finish the guest restoration journal" }
    }

    /** Optional, explicit upgrade for apps that issue standard camera crop requests. */
    @Synchronized fun installCropAdapter(restart: () -> Unit): JSONObject {
        requireGuest()
        check(!File(stateDir, "source.cfg").exists()) { "Deactivate the selected guest camera source before updating its proxy" }
        prepareExecutable()
        val rollbackProxy = upgradeProxyForSetup(restart)
        try {
        if (rollbackProxy != null) restart()
        // Establish the independently verified base installation first. This
        // upgrade retains that working profile if the new adapter cannot open.
        install(restart)
        val previous = installed()
        if (previous.optString("profile") == CROP_PROFILE)
            return identity().put("installation", previous).put("cameraPipeline", verifyCameraPipeline())
        GuestRoot(context).verifyProvider()
        check(sha256(original) == PATCHED_SHA && sha256(backup) == ORIGINAL_SHA)
        val previousBytes = original.readBytes()
        val expected = bundledWrapperHash()
        val wrapper = context.assets.open("camera.vphw90.crop-wrapper.so").use { it.readBytes() }
        check(wrapper.size in 65536..4_194_304 && digest(wrapper) == expected) { "Bundled crop adapter does not match its pinned digest" }
        if (cropTransport.exists()) check(sha256(cropTransport) in setOf(LEGACY_PATCHED_SHA, PATCHED_SHA)) { "Unknown preserved transport HAL; crop upgrade refused" }
        val transportAttributes = JSONObject(previous.toString()).put("originalMode", 0x1a4)
        // Keep the main HAL's measured attributes unchanged. Its immutable
        // preserved transport receives its own explicit, nonwritable mode.
        if (!cropTransport.exists() || sha256(cropTransport) != PATCHED_SHA || Os.stat(cropTransport.absolutePath).st_mode and 0x1ff != 0x1a4)
            writeHal(previousBytes, transportAttributes, cropTransport)
        check(sha256(cropTransport) == PATCHED_SHA)
        val record = JSONObject(previous.toString()).put("profile", CROP_PROFILE).put("patchedSha256", expected)
            .put("transportPath", CROP_TRANSPORT).put("transportSha256", PATCHED_SHA)
            .put("transportMode", 0x1a4)
            .put("previousHalSha256", PATCHED_SHA).put("previousInstallation", JSONObject(previous.toString()))
            .put("stage", "prepared")
        writeAtomic(installRecord, record.toString().toByteArray(), 0x180)
        try {
            writeHal(wrapper, record)
            check(sha256(original) == expected && sha256(cropTransport) == PATCHED_SHA)
            restart()
            // The wrapper requires a real full-crop control ACK on camera open.
            // Camera2 then proves that forwarding still delivers YUV and JPEG.
            val pipeline = verifyCameraPipeline()
            record.put("stage", "configured")
            writeAtomic(installRecord, record.toString().toByteArray(), 0x180)
            return identity().put("installation", record).put("cameraPipeline", pipeline)
                .put("standardCropAdapter", true).put("cropPixelAcceptanceVerified", false)
        } catch (error: Throwable) {
            runCatching {
                check(sha256(original) in setOf(expected, PATCHED_SHA) && sha256(backup) == ORIGINAL_SHA && sha256(cropTransport) == PATCHED_SHA) {
                    "Unknown HAL or recovery change prevents crop upgrade rollback"
                }
                record.put("stage", "restoring")
                writeAtomic(installRecord, record.toString().toByteArray(), 0x180)
                writeHal(previousBytes, previous)
                check(sha256(original) == PATCHED_SHA)
                previous.put("stage", "restoring")
                writeAtomic(installRecord, previous.toString().toByteArray(), 0x180)
                restart()
                verifyCameraPipeline()
                previous.put("stage", if (rollbackProxy == null) "configured" else "restoring")
                writeAtomic(installRecord, previous.toString().toByteArray(), 0x180)
            }.onFailure { rollback -> error.addSuppressed(rollback) }
            throw error
        }
        } catch (error: Throwable) {
            if (rollbackProxy != null) runCatching {
                check(sha256(original) == ORIGINAL_SHA || (sha256(original) == PATCHED_SHA && sha256(backup) == ORIGINAL_SHA)) {
                    "HAL recovery did not complete; retaining the current proxy for the installed adapter"
                }
                rollbackProxy()
                // Restoring the daemon invalidates existing provider sockets.
                // Finish with a new provider and actual pixels from final state.
                restart()
                verifyCameraPipeline()
                if (installRecord.isFile && sha256(original) == PATCHED_SHA) {
                    val restored = installationRecord(false)
                    check(restored.optString("profile") != CROP_PROFILE)
                    restored.put("stage", "configured")
                    writeAtomic(installRecord, restored.toString().toByteArray(), 0x180)
                }
            }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    private data class ProxyIdentity(val pid: Int, val startTicks: String, val socketDevice: Long, val socketInode: Long)

    private fun boundedFile(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val bytes = ByteArray(limit + 1)
        var used = 0
        while (used < bytes.size) { val read = input.read(bytes, used, bytes.size - used); if (read < 0) break; used += read }
        check(used <= limit) { "Guest proxy identity field exceeds its bound" }
        bytes.copyOf(used)
    }

    /** A PID from JSON alone is never authority to signal a process. */
    private fun proxyIdentity(pid: Int): ProxyIdentity {
        requireGuest()
        check(pid > 1 && pid != android.os.Process.myPid()) { "Invalid guest proxy PID" }
        val uid = android.os.Process.myUid()
        val directory = Os.lstat(stateDir.absolutePath)
        check(directory.st_uid == uid && directory.st_mode and 0x3f == 0 &&
            directory.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR) { "Guest proxy state directory is not private and owned" }
        val socket = Os.lstat(SOCKET)
        check(socket.st_uid == uid && socket.st_mode and OsConstants.S_IFMT == OsConstants.S_IFSOCK) { "Guest proxy socket is not an owned socket" }
        val proc = File("/proc/$pid")
        val command = boundedFile(File(proc, "cmdline"), 4096).toString(Charsets.UTF_8).trimEnd('\u0000').split('\u0000')
        check(command == listOf(executable.absolutePath, "--listen", SOCKET, "--original", ORIGINAL_SOCKET, "--state-dir", stateDir.absolutePath)) { "Guest proxy argv does not match the fixed owned service" }
        val status = boundedFile(File(proc, "status"), 16384).toString(Charsets.UTF_8)
        val uids = status.lineSequence().firstOrNull { it.startsWith("Uid:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
        check(uids?.size == 4 && uids.all { it.toIntOrNull() == uid }) { "Guest proxy process does not have the broker's four UIDs" }
        val link = Os.readlink(File(proc, "exe").absolutePath)
        val direct = (link == executable.absolutePath || link == "${executable.absolutePath} (deleted)") &&
            sha256(File(proc, "exe")) in KNOWN_PROXY_SHAS + bundledProxyHash()
        val mapped = VmosHostIdentity.isPinnedLauncher(link, sha256(File(proc, "exe")), fromProcExe = true) &&
            ((executable.isFile && sha256(executable) in KNOWN_PROXY_SHAS + bundledProxyHash()) &&
                (!previousExecutable.exists() || sha256(previousExecutable) in KNOWN_PROXY_SHAS))
        check(direct || mapped) { "Guest proxy executable is not the known native binary or pinned VMOS launcher" }
        val stat = boundedFile(File(proc, "stat"), 4096).toString(Charsets.UTF_8)
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        val ticks = fields.getOrNull(19)?.takeIf { it.matches(Regex("[0-9]+")) } ?: error("Missing guest proxy process start identity")
        return ProxyIdentity(pid, ticks, socket.st_dev, socket.st_ino)
    }

    private fun bundledProxyHash(): String = context.assets.open("vmos-camera-proxy.sha256").bufferedReader().use { it.readText().trim() }

    private fun proxyProcessPresent(pid: Int): Boolean {
        check(pid > 1 && pid != android.os.Process.myPid()) { "Invalid guest proxy PID" }
        return try { Os.kill(pid, 0); true }
        catch (error: ErrnoException) {
            if (error.errno == OsConstants.ESRCH) false else throw error
        }
    }

    /** A stale status PID alone cannot prove that its socket is no longer live. */
    private fun stopRestoredProxy() {
        val pid = status().getInt("pid")
        if (proxyProcessPresent(pid)) stopVerifiedProxy(proxyIdentity(pid))
        android.net.LocalSocket().use { socket ->
            try {
                socket.connect(android.net.LocalSocketAddress(SOCKET, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (error: java.io.IOException) {
                var cause: Throwable? = error
                while (cause != null) {
                    if (cause is ErrnoException && cause.errno in setOf(OsConstants.ECONNREFUSED, OsConstants.ENOENT)) return
                    cause = cause.cause
                }
                // Android 9 LocalSocketImpl can discard the ErrnoException.
                if (error.message?.matches(Regex(".*(?:ECONNREFUSED|ENOENT|Connection refused|No such file or directory).*")) == true) return
                throw IllegalStateException("Restored proxy socket liveness could not be established", error)
            }
            error("A live proxy still owns the restored guest camera transport")
        }
    }

    private fun stopVerifiedProxy(identity: ProxyIdentity) {
        check(!File(stateDir, "source.cfg").exists()) { "A live source prevents proxy replacement" }
        check(proxyIdentity(identity.pid) == identity) { "Guest proxy identity changed before shutdown" }
        Os.kill(identity.pid, OsConstants.SIGTERM)
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!proxyProcessPresent(identity.pid)) { process = null; return }
            Thread.sleep(50)
        }
        // Escalate only after rechecking UID, exact argv, executable, start time
        // and the same owned socket. A reused/changed PID is never signalled.
        check(proxyIdentity(identity.pid) == identity) { "Guest proxy identity changed during bounded shutdown" }
        Os.kill(identity.pid, OsConstants.SIGKILL)
        val killedDeadline = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < killedDeadline) {
            if (!proxyProcessPresent(identity.pid)) { process = null; return }
            Thread.sleep(50)
        }
        error("The verified guest proxy did not stop within its bounded deadline")
    }

    private fun upgradeProxyForSetup(restart: () -> Unit): (() -> Unit)? {
        start()
        val running = proxyIdentity(status().getInt("pid"))
        val expected = bundledProxyHash()
        val deployment = runCatching { JSONObject(proxyDeployment.readText()) }.getOrNull()
        if (deployment?.optInt("pid") == running.pid && deployment.optString("startTicks") == running.startTicks &&
            deployment.optString("sha256") == expected) return null
        val previous = previousExecutable.takeIf { it.isFile && sha256(it) in KNOWN_PROXY_SHAS }?.readBytes()
            ?: throw GuestFailure("VMOS_PROXY_UPDATE_UNVERIFIED", "The running proxy version cannot be matched to a preserved known executable",
                "Use the guest setup screen after restarting the guest. Unknown proxy processes are never terminated.")
        fun rollback() {
            val live = runCatching { proxyIdentity(status().getInt("pid")) }.getOrNull()
            if (live != null) stopVerifiedProxy(live)
            writeAtomic(executable, previous, 0x1c0)
            // start() normally refreshes the bundled executable. Launch the
            // preserved known binary directly for this explicit rollback.
            process = launchProxy()
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (runCatching { runProxy("--list-cameras", "--original", SOCKET).contains("name=") }.getOrDefault(false)) return
                Thread.sleep(100)
            }
            error("The preserved guest proxy failed its real factory rollback check")
        }
        try {
            stopVerifiedProxy(running)
            start()
            val replacement = proxyIdentity(status().getInt("pid"))
            check(replacement.pid != running.pid || replacement.startTicks != running.startTicks)
        } catch (error: Throwable) {
            runCatching {
                rollback()
                restart()
                verifyCameraPipeline()
            }.onFailure { error.addSuppressed(it) }
            throw error
        }
        return ::rollback
    }

    private fun bundledWrapperHash(): String = context.assets.open("camera.vphw90.crop-wrapper.so.sha256")
        .bufferedReader().use { it.readText().trim() }.also { check(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled crop adapter digest" } }

    private fun verifyCameraPipeline(): JSONObject {
        val factory = start()
        check(factory.contains("name=")) { "The actual guest camera factory returned no cameras after restart" }
        val ids = context.getSystemService(CameraManager::class.java).cameraIdList
        check(ids.isNotEmpty()) { "The guest provider exposed no Camera2 cameras after restart" }
        val proof = CameraFrameProbe(context).capture(ids.first(), 300)
        check(proof.previewFrames >= 3 && proof.lastTimestamp > proof.firstTimestamp && proof.jpeg.isNotEmpty()) { "The guest camera failed independent YUV/JPEG verification after restart" }
        return proof.metrics()
    }

    private fun writeHal(bytes: ByteArray, record: JSONObject, destination: File = original) {
        check(destination.absolutePath == HAL || destination.absolutePath == CROP_TRANSPORT)
        val mode = record.getInt("originalMode")
        val uid = record.getInt("originalUid")
        val gid = record.getInt("originalGid")
        val temporary = File(original.parentFile, ".${original.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Os.chmod(temporary.absolutePath, mode)
            val attributes = Os.stat(temporary.absolutePath)
            if (attributes.st_uid != uid || attributes.st_gid != gid) {
                runCatching { Os.chown(temporary.absolutePath, uid, gid) }.getOrElse {
                    GuestRoot(context).preserveHalAttributes(temporary, uid, gid, mode)
                }
            }
            val verified = Os.stat(temporary.absolutePath)
            check(verified.st_uid == uid && verified.st_gid == gid && verified.st_mode and 0xfff == mode) { "The staged guest HAL did not preserve original owner, group and mode" }
            record.optString("originalSelinuxLabel").takeIf { it.isNotEmpty() }?.let { encoded ->
                val label = Base64.decode(encoded, Base64.NO_WRAP)
                // VMOS guest files inherit the host app's label. Reapplying an
                // identical label is denied by the host even to guest root.
                if (!Os.getxattr(temporary.absolutePath, "security.selinux").contentEquals(label))
                    Os.setxattr(temporary.absolutePath, "security.selinux", label, 0)
                check(Os.getxattr(temporary.absolutePath, "security.selinux").contentEquals(label)) { "The staged guest HAL did not preserve its security label" }
            }
            check(temporary.renameTo(destination)) { "Cannot atomically replace the verified guest HAL" }
        } finally { temporary.delete() }
    }

    fun status(): JSONObject = JSONObject(File(stateDir, "status.json").readText())
    fun deactivate() { val config = File(stateDir, "source.cfg"); check(!config.exists() || config.delete()) { "Could not remove the source lease" } }

    companion object {
        const val BACKEND = "vmos_camera_proxy"
        const val HAL = "/vendor/lib64/hw/camera.vphw90.so"
        const val ORIGINAL_SHA = "362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c"
        const val LEGACY_PATCHED_SHA = "1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97"
        const val PATCHED_SHA = "02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a"
        const val CROP_PROFILE = "vmos-camera3-crop-v1"
        const val CROP_TRANSPORT = "/vendor/lib64/hw/.vcam-transport-camera.vphw90.so"
        const val SOCKET = "/data/local/tmp/vcam_pipe"
        const val ORIGINAL_SOCKET = "/dev/socket/socket/qemu_pipe"
        private val KNOWN_PROXY_SHAS = setOf("8855fbf54d0ecae94ba2c13338c52cf90d2997eba465f4ad548477f30e0c4591",
            "53e32e2d80beefbb77399d34974d89da4fbf8a8fd08313fa67c1b51edee6cee3",
            "1162c3ef0f3dc4a9b2ebb8fabf558dbbe31aead0ef4fa0c04897c5ca10721bcc",
            "540d4fb9593574f51a8cf94da2d2dc43aded5b777ee86720c8eba0ccd406e2c6",
            "14232f10185851bded52a882fcb1d6509e8560fcccb8fe8034163770ee160d1e",
            "f6d508f0b5412fca1f169a082eec4ce66c844168e9cfae26490e838d546d1e11",
            "97e4b1ae9be3a8fe422e8a37175f3f440e558ecf3ca64cf38ac1264c41c7a08d")
        fun sha256(file: File): String = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
            while (true) { val size = input.read(bytes); if (size < 0) break; hash.update(bytes, 0, size) }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun writeAtomic(file: File, bytes: ByteArray, mode: Int) {
            val temporary = File(file.parentFile, ".${file.name}.${java.util.UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }; Os.chmod(temporary.absolutePath, mode)
                check(temporary.renameTo(file)) { "Cannot atomically publish ${file.name}" }
            } finally { temporary.delete() }
        }
    }
}
