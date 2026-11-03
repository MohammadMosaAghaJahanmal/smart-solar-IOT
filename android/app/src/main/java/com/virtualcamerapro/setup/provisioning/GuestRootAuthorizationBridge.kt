package com.virtualcamerapro.setup.provisioning

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.virtualcamerapro.broker.VmosHostIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Opens only the original daemon's pending consent UI; never creates a socket or grants root. */
internal class GuestRootAuthorizationBridge private constructor(
    private val context: Context,
    private val managerUid: Int,
    private val command: String,
    private val socketTable: File?,
    private val beforeEndpoints: Set<RootRequestIdentity.Endpoint>,
    private val beforePids: Set<Int>,
    private val beforeOwnedPipes: Set<RootRequestIdentity.FileKey>,
    private val pinnedLauncher: RootRequestIdentity.FileKey
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val launchLock = Any()
    @Volatile private var worker: Thread? = null
    @Volatile private var result = JSONObject().put("eligible", true).put("launched", false)
        .put("code", "WAITING_FOR_AUTHENTIC_ROOT_REQUEST").put("probeTransport", "anonymous-pipe")
    private var binding: RootRequestIdentity.ProbeClient? = null
    private val observations = linkedMapOf<Int, JSONObject>()

    fun watch(probe: java.lang.Process) {
        worker = Thread {
            val deadline = SystemClock.elapsedRealtime() + 9000
            val stable = RootRequestIdentity.StableDescriptorRequest()
            try {
                while (!closed.get() && probe.isAlive && SystemClock.elapsedRealtime() < deadline) {
                    if (binding == null) {
                        val pipes = ownedPipes() - beforeOwnedPipes
                        val clients = processIds().filter { it !in beforePids }.mapNotNull { pid ->
                            runCatching { inspectProbeClient(pid) }.getOrNull()
                        }.filter { RootRequestIdentity.probeClientMatches(it, beforePids, Process.myPid(), Process.myUid(), command, pipes) }
                        check(clients.size <= 1) { "ROOT_PROBE_CLIENT_IDENTITY_AMBIGUOUS" }
                        binding = clients.singleOrNull()
                    }
                    val ownClient = binding
                    if (ownClient == null) { Thread.sleep(50); continue }
                    check(inspectProbeClient(ownClient.pid) == ownClient && ownClient.stdout in ownedPipes()) {
                        "ROOT_PROBE_ANONYMOUS_PIPE_IDENTITY_CHANGED"
                    }
                    val matches = if (socketTable != null) {
                        RootRequestIdentity.endpoints(readBounded(socketTable, 1_048_576)).filter { it !in beforeEndpoints }
                            .mapNotNull { endpoint -> runCatching { inspect(endpoint, ownClient.stdout) }.onFailure(::failure).getOrNull() }
                    } else {
                        val candidates = descriptorCandidates(ownClient.stdout)
                        result = JSONObject(result.toString()).put("qualifiedDescriptorCandidatePids", JSONArray(candidates.map { it.pid }))
                            .put("descriptorObservations", JSONArray(observations.values.toList()))
                        stable.observe(candidates, SystemClock.elapsedRealtime())?.let { selected ->
                            listOf(Verified(RootRequestIdentity.Endpoint(selected.pid, 0, RootRequestIdentity.predictedRequestPath(selected.pid), ""),
                                0, "VMOS_PINNED_LAUNCHER", selected, candidates.toSet()))
                        }.orEmpty()
                    }
                    check(matches.size <= 1) { "ROOT_REQUEST_IDENTITY_AMBIGUOUS" }
                    matches.singleOrNull()?.let { selected ->
                        check(inspectProbeClient(ownClient.pid) == ownClient && ownClient.stdout in ownedPipes())
                        val repeated = if (selected.descriptor == null) inspect(selected.endpoint, ownClient.stdout) else {
                            val cluster = descriptorCandidates(ownClient.stdout)
                            check(cluster.toSet() == selected.cluster && RootRequestIdentity.uniqueRequestSocketOwner(cluster) == selected.descriptor) {
                                "ROOT_REQUEST_CLUSTER_CHANGED_BEFORE_CONSENT"
                            }
                            check(pinnedLauncher == executableKey("/proc/self/exe") &&
                                GuestAdapterRegistry.sha256(File("/proc/self/exe")) == GuestAdapterRegistry.VMOS_LINKER)
                            selected
                        }
                        check(repeated == selected)
                        synchronized(launchLock) {
                            if (closed.get() || !probe.isAlive || SystemClock.elapsedRealtime() >= deadline) return@Thread
                            check(hasPermissions(context))
                            context.startActivity(Intent().setClassName(MANAGER, "$MANAGER.RequestActivity")
                                .putExtra("socket", selected.endpoint.logicalPath).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            result = JSONObject(result.toString()).put("launched", true).put("code", "NORMAL_SUPERUSER_CONSENT_OPENED")
                                .put("identityAdapter", if (selected.descriptor == null) "full-proc-anonymous-pipe-v2" else "vmos-anonymous-pipe-v2")
                                .put("requestPid", selected.endpoint.pid).put("probeClientPid", ownClient.pid)
                                .put("requestSocketPath", selected.endpoint.logicalPath).put("privateFileBindingUsed", false)
                                .put("anonymousPipeOwnedByController", true).put("requestSocketPathObserved", true)
                                .put("requestCredentialsReadable", selected.descriptor == null)
                                .put("requestCommandLineReadable", selected.descriptor == null)
                                .put("requestStartTicksReadable", selected.descriptor == null).put("autoGrant", false)
                            selected.descriptor?.let { result.put("relatedDaemonProcessCount", selected.cluster.size)
                                .put("requestSocketOwnerSelection", "unique-pid-derived-socket-path") }
                        }
                        return@Thread
                    }
                    Thread.sleep(100)
                }
                if (!closed.get()) result = result.put("code", "AUTHENTIC_ROOT_REQUEST_NOT_OBSERVED")
            } catch (error: Throwable) {
                if (!closed.get()) {
                    Log.e(TAG, "Authentic guest root request inspection failed", error)
                    result = result.put("code", "ROOT_REQUEST_INSPECTION_FAILED").put("lastInspectionFailure", error.message?.take(180))
                }
            }
        }.apply { name = "VMOS-root-consent"; isDaemon = true; start() }
    }

    private data class Verified(val endpoint: RootRequestIdentity.Endpoint, val startTicks: Long, val executable: String,
        val descriptor: RootRequestIdentity.DescriptorRequest? = null,
        val cluster: Set<RootRequestIdentity.DescriptorRequest> = emptySet())

    private fun failure(error: Throwable) {
        result = JSONObject(result.toString()).put("lastInspectionFailure", error.message?.take(180) ?: error.javaClass.simpleName)
    }

    /** The readable same-UID direct child identifies our ProcessBuilder stdout pipe. */
    private fun inspectProbeClient(pid: Int): RootRequestIdentity.ProbeClient {
        val path = "/proc/$pid"
        val first = RootRequestIdentity.startTicks(readBounded(File("$path/stat"), 8192))
        val status = readBounded(File("$path/status"), 32768)
        val argv = readBounded(File("$path/cmdline"), 4096).trimEnd('\u0000').split('\u0000')
        val stdout = pipeOutput(path)
        check(RootRequestIdentity.startTicks(readBounded(File("$path/stat"), 8192)) == first)
        return RootRequestIdentity.ProbeClient(pid, RootRequestIdentity.parentPid(status), first, RootRequestIdentity.uidFields(status), argv, stdout)
    }

    private fun descriptorCandidates(pipe: RootRequestIdentity.FileKey) = processIds().filter { it !in beforePids }.mapNotNull { pid ->
        runCatching { inspectDescriptorRequest(pid, pipe) }.onFailure(::failure).getOrNull()
    }

    /** No regular file is trusted: this guest allows foreign UIDs to open private files. */
    private fun inspectDescriptorRequest(pid: Int, pipe: RootRequestIdentity.FileKey): RootRequestIdentity.DescriptorRequest? {
        if (pid == Process.myPid() || pid in beforePids) return null
        val proc = "/proc/$pid"
        if (runCatching { pipeOutput(proc) }.getOrNull() != pipe) return null
        val directory = directoryKey(proc)
        check(VmosHostIdentity.isLauncherPath(Os.readlink("$proc/exe"), fromProcExe = true))
        val executable = executableKey("$proc/exe")
        val denied = listOf("status", "stat", "cmdline").all { metadataDenied("$proc/$it") }
        if (!denied) return null // excludes our ordinary su client and its ordinary fork
        val sockets = (0..127).mapNotNull { fd -> runCatching { RootRequestIdentity.socketInode(Os.readlink("$proc/fd/$fd")) }.getOrNull() }.toSet()
        val socketFile = requestSocketKey(pid)
        val candidate = RootRequestIdentity.DescriptorRequest(pid, directory, executable, pipe, sockets, socketFile)
        check(RootRequestIdentity.descriptorRequestMatches(candidate, beforePids, Process.myPid(), pipe, pinnedLauncher, denied, true))
        check(directoryKey(proc) == directory && executableKey("$proc/exe") == executable && pipeOutput(proc) == pipe && requestSocketKey(pid) == socketFile)
        check(GuestAdapterRegistry.sha256(File("/system/xbin/daemonsu")) == SU_SHA)
        if (observations.size < 32 || pid in observations) observations[pid] = JSONObject().put("pid", pid)
            .put("pipeMatched", true).put("requestSocketPresent", socketFile != null).put("socketDescriptorCount", sockets.size)
        return candidate
    }

    private fun inspect(endpoint: RootRequestIdentity.Endpoint, pipe: RootRequestIdentity.FileKey): Verified {
        check(endpoint.pid != Process.myPid())
        val proc = "/proc/${endpoint.pid}"
        val ticks = RootRequestIdentity.startTicks(readBounded(File("$proc/stat"), 8192))
        check(RootRequestIdentity.expectedPendingCredentials(RootRequestIdentity.uidFields(readBounded(File("$proc/status"), 32768)), managerUid))
        val argv = readBounded(File("$proc/cmdline"), 4096).trimEnd('\u0000').split('\u0000')
        check(argv in listOf(listOf("/system/xbin/daemonsu", "--daemon"), listOf("/system/xbin/su", "--daemon")))
        val exe = Os.readlink("$proc/exe")
        val hash = GuestAdapterRegistry.sha256(File("$proc/exe"))
        check((exe in setOf("/system/xbin/daemonsu", "/system/xbin/su") && hash == SU_SHA) ||
            VmosHostIdentity.isPinnedLauncher(exe, hash, fromProcExe = true))
        check(GuestAdapterRegistry.sha256(File("/system/xbin/daemonsu")) == SU_SHA && pipeOutput(proc) == pipe)
        val fds = File("$proc/fd").listFiles() ?: error("Root descriptors unavailable")
        check(fds.size <= 256 && fds.any { runCatching { RootRequestIdentity.socketInode(Os.readlink(it.absolutePath)) == endpoint.inode }.getOrDefault(false) })
        check(requestSocketKey(endpoint.pid) != null)
        check(RootRequestIdentity.startTicks(readBounded(File("$proc/stat"), 8192)) == ticks)
        return Verified(endpoint, ticks, exe)
    }

    fun report(): JSONObject = JSONObject(result.toString())
    override fun close() {
        synchronized(launchLock) { closed.set(true) }
        worker?.interrupt()
        try { worker?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        Log.i(TAG, "Guest root consent bridge completed: ${report()}")
    }

    companion object {
        private const val TAG = "VcamRootConsent"
        private const val MANAGER = "com.koushikdutta.superuser"
        private const val MANAGER_APK_SHA = "1d8c6b0d8467bd7b6b4670c81ce618a1f0fbcd1a6e1847a108351b1ddca90f5f"
        private const val SU_SHA = "27dc135ac7e931415216a96a28100fcbf396e3e0fbaeb036d810fdeaa57a554a"
        private val required = listOf("android.permission.REQUEST_SUPERUSER", "android.permission.START_ANY_ACTIVITY")
        fun capability(context: Context): JSONObject = JSONObject().put("developmentBootstrap", true)
            .put("signaturePrivilegesGranted", hasPermissions(context)).put("normalUserConsentRequired", true)
            .put("probeTransport", "anonymous-pipe").put("privateFileBindingEnabled", false)

        fun beforeProbe(context: Context, executable: String, command: String): GuestRootAuthorizationBridge? = runCatching {
            if (!hasPermissions(context) || File("/system/bin/app_process32").exists()) return null
            GuestAdapterRegistry.requireSupportedGuest()
            check(context.packageName == "com.virtualcamerapro" && command.length <= 2048)
            check(executable == "/system/xbin/su" && GuestAdapterRegistry.sha256(File(executable)) == SU_SHA)
            val manager = context.packageManager.getApplicationInfo(MANAGER, 0)
            check(manager.uid >= 10000 && manager.uid != Process.myUid())
            check(manager.dataDir in setOf("/data/data/$MANAGER", "/data/user/0/$MANAGER"))
            check(GuestAdapterRegistry.sha256(File(manager.sourceDir)) == MANAGER_APK_SHA)
            val table = readableSocketTable()
            val launcher = executableKey("/proc/self/exe")
            val launcherPath = Os.readlink("/proc/self/exe")
            val launcherHash = GuestAdapterRegistry.sha256(File("/proc/self/exe"))
            check(VmosHostIdentity.isPinnedLauncher(launcherPath,launcherHash,fromProcExe = true)) {
                "VMOS launcher identity differs: layout=${VmosHostIdentity.isLauncherPath(launcherPath,true)} sha256=$launcherHash"
            }
            check(executableKey("/proc/self/exe") == launcher)
            GuestRootAuthorizationBridge(context, manager.uid, command, table?.first,
                table?.let { RootRequestIdentity.endpoints(it.second).toSet() }.orEmpty(), processIds().toSet(), ownedPipes(), launcher)
        }.onFailure { Log.e(TAG, "Guest root consent bridge eligibility failed", it) }.getOrNull()

        private fun pipeOutput(proc: String): RootRequestIdentity.FileKey {
            val link = Os.readlink("$proc/fd/1")
            val inode = RootRequestIdentity.pipeInode(link) ?: error("Probe stdout is not an anonymous pipe")
            return Os.stat("$proc/fd/1").let {
                check(it.st_mode and OsConstants.S_IFMT == OsConstants.S_IFIFO && it.st_ino == inode)
                RootRequestIdentity.FileKey(it.st_dev, it.st_ino)
            }
        }
        private fun ownedPipes(): Set<RootRequestIdentity.FileKey> {
            val fds = File("/proc/self/fd").list() ?: error("Own pipe descriptors unavailable")
            check(fds.size <= 4096)
            return fds.mapNotNull { fd ->
                if (fd.toIntOrNull() == null) null else runCatching {
                    val path = "/proc/self/fd/$fd"
                    val inode = RootRequestIdentity.pipeInode(Os.readlink(path)) ?: return@runCatching null
                    Os.stat(path).let { stat ->
                        check(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFIFO && stat.st_ino == inode)
                        RootRequestIdentity.FileKey(stat.st_dev, stat.st_ino)
                    }
                }.getOrNull()
            }.toSet()
        }
        private fun requestSocketKey(pid: Int): RootRequestIdentity.FileKey? = try {
            Os.lstat(RootRequestIdentity.predictedRequestPath(pid)).let {
                check(it.st_mode and OsConstants.S_IFMT == OsConstants.S_IFSOCK && it.st_nlink == 1L && it.st_ino > 0) {
                    "ROOT_REQUEST_PATH_IS_NOT_A_REAL_SOCKET"
                }
                RootRequestIdentity.FileKey(it.st_dev, it.st_ino)
            }
        } catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) null else throw error }
        private fun readableSocketTable(): Pair<File, String>? {
            val paths = listOf("/proc/self/net/unix", "/proc/${Process.myPid()}/net/unix", "/proc/net/unix")
            var absent = 0
            for (path in paths) {
                try { return File(path).let { it to readBounded(it, 1_048_576) } }
                catch (error: Exception) {
                    try { Os.close(Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)) }
                    catch (os: ErrnoException) { if (os.errno == OsConstants.ENOENT) absent++ }
                }
            }
            check(absent == paths.size) { "Guest Unix socket table is unreadable rather than absent" }
            return null
        }
        private fun processIds(): List<Int> {
            val entries = File("/proc").list() ?: error("Guest process enumeration unavailable")
            check(entries.size <= 20000)
            return entries.mapNotNull { it.toIntOrNull()?.takeIf { pid -> pid > 1 } }
        }
        private fun directoryKey(path: String) = Os.stat(path).let {
            check(it.st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR && it.st_ino > 0)
            RootRequestIdentity.FileKey(it.st_dev, it.st_ino)
        }
        private fun executableKey(path: String) = Os.stat(path).let {
            check(it.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG && it.st_ino > 0)
            RootRequestIdentity.FileKey(it.st_dev, it.st_ino)
        }
        private fun metadataDenied(path: String): Boolean = try {
            Os.close(Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)); false
        } catch (error: ErrnoException) { check(error.errno == OsConstants.EACCES); true }
        private fun hasPermissions(context: Context) = required.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        private fun readBounded(file: File, maximum: Int): String = file.inputStream().use { stream ->
            val bytes = ByteArray(maximum + 1); var count = 0
            while (count < bytes.size) { val n = stream.read(bytes, count, bytes.size - count); if (n < 0) break; count += n }
            check(count <= maximum); bytes.copyOf(count).toString(Charsets.UTF_8)
        }
    }
}
