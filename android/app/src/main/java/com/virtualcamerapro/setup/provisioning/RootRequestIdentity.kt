package com.virtualcamerapro.setup.provisioning

/** Data-only parsing shared by the guarded bridge and its offline security tests. */
internal object RootRequestIdentity {
    // The registered VMOS daemon differs from upstream Koush Superuser. Its
    // SHA-256-pinned ELF uses this literal at 0xc0caf, loaded at 0x6b64/0x6b74
    // into the "%s/.socket%d" formatter. The upstream private cache path is
    // deliberately not accepted for this exact binary adapter.
    const val REQUEST_SOCKET_BASE = "/dev/socket/com.koushikdutta.superuser"
    data class Endpoint(val pid: Int, val inode: Long, val logicalPath: String, val kernelPath: String)
    data class ProcessKey(val pid: Int, val startTicks: Long)
    data class FileKey(val device: Long, val inode: Long)
    data class DescriptorRequest(val pid: Int, val directory: FileKey, val executable: FileKey,
        val stdout: FileKey, val socketInodes: Set<Long>, val requestSocketFile: FileKey? = null)
    data class ProbeClient(val pid: Int, val parentPid: Int, val startTicks: Long, val uids: List<Int>,
        val argv: List<String>, val stdout: FileKey)

    fun probeClientMatches(client: ProbeClient, beforePids: Set<Int>, ownPid: Int, ownUid: Int,
        command: String, newlyOwnedPipes: Set<FileKey>): Boolean = client.pid > 1 && client.pid !in beforePids &&
        client.parentPid == ownPid && client.startTicks > 0 && client.uids == List(4) { ownUid } &&
        client.argv == listOf("/system/xbin/su", "-c", command) && client.stdout.inode > 0 &&
        client.stdout in newlyOwnedPipes

    /**
     * Exact VMOS proc adapter. A virtual UID reported by stat is deliberately
     * absent: that guest rewrites it to the caller's UID. The anonymous pipe
     * is the request binding; a process merely appearing at the right time is
     * never sufficient. Path/permission checks are performed by the caller.
     */
    fun descriptorRequestMatches(request: DescriptorRequest, beforePids: Set<Int>, ownPid: Int,
        privateOutput: FileKey, pinnedExecutable: FileKey, metadataAccessDenied: Boolean,
        privateOutputPathMatches: Boolean): Boolean = request.pid > 1 && request.pid != ownPid &&
        request.pid !in beforePids && request.directory.device >= 0 && request.directory.inode > 0 &&
        request.stdout == privateOutput && privateOutput.inode > 0 &&
        request.executable == pinnedExecutable && pinnedExecutable.inode > 0 &&
        metadataAccessDenied && privateOutputPathMatches && request.socketInodes.isNotEmpty() &&
        request.socketInodes.size <= 128 && request.socketInodes.all { it > 0 }

    /**
     * Both the waiting Samsung-compatibility parent and request child retain
     * the probe pipe. The child can close inherited sockets, so socket counts
     * and set containment do not authenticate which PID owns the request.
     * Require one exact PID-derived socket pathname observed by lstat instead.
     * Its filesystem inode is deliberately not equated with a kernel FD inode.
     */
    fun uniqueRequestSocketOwner(candidates: List<DescriptorRequest>): DescriptorRequest? {
        if (candidates.isEmpty() || candidates.size > 4 || candidates.map { it.pid }.toSet().size != candidates.size) return null
        if (candidates.map { it.stdout }.toSet().size != 1 || candidates.map { it.executable }.toSet().size != 1) return null
        return candidates.filter { it.requestSocketFile?.let { socket -> socket.device >= 0 && socket.inode > 0 } == true }.singleOrNull()
    }

    /** Ambiguity, disappearance or changed cluster metadata restarts settling. */
    class StableDescriptorRequest(private val settlingMs: Long = 300) {
        private var previous: DescriptorRequest? = null
        private var previousCluster: Set<DescriptorRequest> = emptySet()
        private var firstSeen = 0L
        private var observations = 0
        fun observe(candidates: List<DescriptorRequest>, nowMs: Long): DescriptorRequest? {
            require(nowMs >= 0 && settlingMs >= 0)
            val candidate = uniqueRequestSocketOwner(candidates)
            val cluster = candidates.toSet()
            if (candidate == null || candidate != previous || cluster != previousCluster || nowMs < firstSeen) {
                previous = candidate; previousCluster = cluster; firstSeen = nowMs; observations = if (candidate == null) 0 else 1
                return null
            }
            observations++
            return candidate.takeIf { observations >= 3 && nowMs - firstSeen >= settlingMs }
        }
    }

    fun predictedRequestPath(pid: Int): String {
        require(pid > 1)
        return "$REQUEST_SOCKET_BASE/.socket$pid"
    }

    fun endpoints(table: String): List<Endpoint> {
        require(table.length <= 1_048_576) { "Unix socket table exceeds its inspection bound" }
        return table.lineSequence().mapNotNull { line ->
            val values = line.trim().split(Regex("\\s+"), limit = 8)
            if (values.size != 8 || values[3] != "00010000" || values[4] != "0001" || values[5] != "01") return@mapNotNull null
            val inode = values[6].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val path = values[7]
            if (!path.startsWith('/') || path.length > 4096) return@mapNotNull null
            val match = Regex("/dev/socket/com\\.koushikdutta\\.superuser/\\.socket([0-9]+)$").find(path)
                ?: return@mapNotNull null
            val pid = match.groupValues[1].toIntOrNull()?.takeIf { it > 1 } ?: return@mapNotNull null
            // Only this logical guest pathname is sent to the root manager. A
            // kernel's VMOS backing-directory prefix is never executed or opened.
            Endpoint(pid, inode, predictedRequestPath(pid), path)
        }.toList()
    }

    fun startTicks(stat: String): Long {
        require(stat.length <= 8192)
        val close = stat.lastIndexOf(')')
        require(close > 0)
        return stat.substring(close + 1).trim().split(Regex("\\s+")).getOrNull(19)?.toLongOrNull()
            ?.takeIf { it > 0 } ?: error("Invalid process start identity")
    }

    fun uidFields(status: String): List<Int> {
        require(status.length <= 32768)
        val values = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.map { it.toIntOrNull() ?: -1 }
            ?: error("Process credentials are unavailable")
        require(values.size == 4 && values.all { it >= 0 })
        return values
    }

    fun parentPid(status: String): Int {
        require(status.length <= 32768)
        return status.lineSequence().firstOrNull { it.startsWith("PPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
            ?.takeIf { it > 0 } ?: error("Process parent identity is unavailable")
    }

    fun expectedPendingCredentials(values: List<Int>, managerUid: Int): Boolean =
        // The daemon binds its socket while temporarily using the manager's
        // effective UID, then exec_am restores all identities to root before
        // attempting to launch the request activity (actual binary 0x8558).
        values == listOf(0, managerUid, 0, managerUid) || values == listOf(0, 0, 0, 0)

    fun pipeInode(target: String): Long? = Regex("pipe:\\[([0-9]+)]").matchEntire(target)
        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    fun socketInode(target: String): Long? = Regex("socket:\\[([0-9]+)]").matchEntire(target)
        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
}
