package com.virtualcamerapro.setup.provisioning

/** CPU-only adversarial parser checks; no root commands or Android device access. */
fun main() {
    var checked = 0
    fun checkCase(name: String, block: () -> Unit) {
        try { block(); checked++ } catch (error: Throwable) { throw AssertionError(name, error) }
    }
    fun line(path: String, flags: String = "00010000", type: String = "0001", state: String = "01", inode: String = "19092") =
        "0000000000000000: 00000002 00000000 $flags $type $state $inode $path"
    val logical = "/dev/socket/com.koushikdutta.superuser/.socket2426"
    checkCase("the exact real filesystem endpoint is recognized") {
        check(RootRequestIdentity.endpoints(line(logical)).single() == RootRequestIdentity.Endpoint(2426, 19092, logical, logical))
    }
    checkCase("VMOS kernel backing prefix is never sent to the guest root manager") {
        val physical = "/data/user/0/com.vmos.virtual/private/guestroot$logical"
        val parsed = RootRequestIdentity.endpoints(line(physical)).single()
        check(parsed.logicalPath == logical && parsed.kernelPath == physical)
    }
    checkCase("upstream cache paths cannot select the registered VMOS binary adapter") {
        check(RootRequestIdentity.endpoints(line("/data/data/com.koushikdutta.superuser/cache/.socket2426")).isEmpty())
        check(RootRequestIdentity.endpoints(line("/data/user/0/com.koushikdutta.superuser/cache/.socket2426")).isEmpty())
    }
    checkCase("abstract sockets cannot impersonate a filesystem request") {
        check(RootRequestIdentity.endpoints(line("@$logical")).isEmpty())
    }
    checkCase("connected or non-listening endpoints are not root requests") {
        check(RootRequestIdentity.endpoints(line(logical, flags = "00000000")).isEmpty())
        check(RootRequestIdentity.endpoints(line(logical, state = "03")).isEmpty())
    }
    checkCase("datagram endpoints are not root requests") {
        check(RootRequestIdentity.endpoints(line(logical, type = "0002")).isEmpty())
    }
    checkCase("other packages and cache suffixes are refused") {
        for (path in listOf("/dev/socket/other.app/.socket2426", "$logical.extra", "$logical/child", logical.replace(".socket2426", ".socket0"))) {
            check(RootRequestIdentity.endpoints(line(path)).isEmpty())
        }
    }
    checkCase("invalid PID and kernel inode representations are refused") {
        check(RootRequestIdentity.endpoints(line(logical, inode = "0")).isEmpty())
        check(RootRequestIdentity.endpoints(line(logical, inode = "999999999999999999999999")).isEmpty())
        check(RootRequestIdentity.endpoints(line(logical.replace("2426", "999999999999999999999"))).isEmpty())
    }
    checkCase("oversized proc tables are rejected before parsing") {
        check(runCatching { RootRequestIdentity.endpoints(" ".repeat(1_048_577)) }.isFailure)
    }
    checkCase("both measured daemon credential stages are recognized") {
        check(RootRequestIdentity.expectedPendingCredentials(listOf(0, 10047, 0, 10047), 10047))
        check(RootRequestIdentity.expectedPendingCredentials(listOf(0, 0, 0, 0), 10047))
    }
    checkCase("unprivileged and unrelated effective UIDs are refused") {
        for (uids in listOf(listOf(10048, 10048, 10048, 10048), listOf(10047, 10047, 10047, 10047), listOf(0, 10050, 0, 10050))) {
            check(!RootRequestIdentity.expectedPendingCredentials(uids, 10047))
        }
    }
    checkCase("complete four-field status identity is required") {
        check(RootRequestIdentity.uidFields("Name:\tsu\nUid:\t0\t10047\t0\t10047\n") == listOf(0, 10047, 0, 10047))
        check(runCatching { RootRequestIdentity.uidFields("Uid:\t0\t10047\n") }.isFailure)
        check(runCatching { RootRequestIdentity.uidFields("Uid:\t0\tnan\t0\t10047\n") }.isFailure)
    }
    checkCase("process names with spaces and parentheses preserve the start token") {
        val fields = MutableList(20) { "0" }.apply { this[0] = "S"; this[19] = "909244" }
        check(RootRequestIdentity.startTicks("2426 (su daemon (worker)) ${fields.joinToString(" ")}") == 909244L)
        check(runCatching { RootRequestIdentity.startTicks("2426 (su) S 1") }.isFailure)
    }
    checkCase("FD socket identities cannot be spoofed by partial matches") {
        check(RootRequestIdentity.socketInode("socket:[19092]") == 19092L)
        check(RootRequestIdentity.socketInode("pipe:[19092]") == null)
        check(RootRequestIdentity.socketInode("socket:[19092]extra") == null)
    }
    val privateOutput = RootRequestIdentity.FileKey(66332, 99221)
    val executable = RootRequestIdentity.FileKey(66332, 3675952)
    val request = RootRequestIdentity.DescriptorRequest(2468, RootRequestIdentity.FileKey(4, 998877),
        executable, privateOutput, setOf(80808), RootRequestIdentity.FileKey(66332, 99441))
    fun matches(candidate: RootRequestIdentity.DescriptorRequest = request, before: Set<Int> = setOf(2000),
        hidden: Boolean = true, privatePath: Boolean = true) = RootRequestIdentity.descriptorRequestMatches(
        candidate, before, 1000, privateOutput, executable, hidden, privatePath)
    checkCase("an exact private descriptor handoff can select the restricted proc adapter") {
        check(matches())
    }
    checkCase("a process that existed before the real probe cannot be selected") {
        check(!matches(before = setOf(request.pid)))
    }
    checkCase("a matching inode on another device is not this output") {
        check(!matches(request.copy(stdout = privateOutput.copy(device = 123))))
    }
    checkCase("a same-device unrelated file is not this output") {
        check(!matches(request.copy(stdout = privateOutput.copy(inode = 99222))))
    }
    checkCase("only the hashed launcher executable inode is accepted") {
        check(!matches(request.copy(executable = executable.copy(inode = executable.inode + 1))))
        check(!matches(request.copy(executable = executable.copy(device = executable.device + 1))))
    }
    checkCase("a readable same-UID su client is not a pending root child") {
        check(!matches(hidden = false))
    }
    checkCase("file-descriptor identity does not waive private pathname binding") {
        check(!matches(privatePath = false))
    }
    checkCase("descriptor-free or invalid socket identities cannot select a request") {
        check(!matches(request.copy(socketInodes = emptySet())))
        check(!matches(request.copy(socketInodes = setOf(0))))
        check(!matches(request.copy(socketInodes = (1L..129L).toSet())))
    }
    checkCase("a missing proc generation inode is rejected") {
        check(!matches(request.copy(directory = request.directory.copy(inode = 0))))
    }
    checkCase("a newly observed request must settle over three scans and 300ms") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        check(stable.observe(listOf(request), 1000) == null)
        check(stable.observe(listOf(request), 1300) == null)
        check(stable.observe(listOf(request), 1301) == request)
    }
    checkCase("two plausible processes cannot select a consent pathname") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        check(stable.observe(listOf(request), 1000) == null)
        check(stable.observe(listOf(request, request.copy(pid = 2469)), 1400) == null)
        check(stable.observe(listOf(request), 1500) == null)
        check(stable.observe(listOf(request), 1700) == null)
        check(stable.observe(listOf(request), 1800) == request)
    }
    checkCase("PID generation changes invalidate a prior stable observation") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        stable.observe(listOf(request), 1000)
        stable.observe(listOf(request), 1100)
        val replacement = request.copy(directory = request.directory.copy(inode = 998878))
        check(stable.observe(listOf(replacement), 1400) == null)
        check(stable.observe(listOf(replacement), 1500) == null)
        check(stable.observe(listOf(replacement), 1700) == replacement)
    }
    checkCase("a changed socket FD invalidates a prior stable observation") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        stable.observe(listOf(request), 1000)
        val replacement = request.copy(socketInodes = setOf(80809))
        check(stable.observe(listOf(replacement), 1400) == null)
        check(stable.observe(listOf(replacement), 1500) == null)
        check(stable.observe(listOf(replacement), 1700) == replacement)
    }
    checkCase("disappearance resets the settling time") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        stable.observe(listOf(request), 1000)
        stable.observe(emptyList(), 1400)
        check(stable.observe(listOf(request), 1500) == null)
        check(stable.observe(listOf(request), 1700) == null)
        check(stable.observe(listOf(request), 1800) == request)
    }
    checkCase("predicted paths use only the pinned daemon PID convention") {
        check(RootRequestIdentity.predictedRequestPath(2468) == "/dev/socket/com.koushikdutta.superuser/.socket2468")
        check(runCatching { RootRequestIdentity.predictedRequestPath(0) }.isFailure)
    }
    val waitingParent = request.copy(pid = 2467, directory = request.directory.copy(inode = 998876), socketInodes = setOf(101, 102), requestSocketFile = null)
    val requestLeaf = request.copy(socketInodes = setOf(101, 102, 103))
    checkCase("the observed PID-derived socket selects the request child") {
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(waitingParent, requestLeaf)) == requestLeaf)
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(requestLeaf, waitingParent)) == requestLeaf)
    }
    checkCase("two observed PID-derived request sockets remain ambiguous") {
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(waitingParent.copy(requestSocketFile = request.requestSocketFile), requestLeaf)) == null)
    }
    checkCase("more sockets without an observed PID-derived socket do not select a request") {
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(waitingParent, requestLeaf.copy(socketInodes = setOf(201, 202, 203), requestSocketFile = null))) == null)
    }
    checkCase("duplicate PID or unrelated private output cannot form a request cluster") {
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(waitingParent, requestLeaf.copy(pid = waitingParent.pid))) == null)
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(waitingParent, requestLeaf.copy(stdout = privateOutput.copy(inode = 1)))) == null)
    }
    checkCase("an over-bound process cluster cannot select a request") {
        check(RootRequestIdentity.uniqueRequestSocketOwner((1..5).map { waitingParent.copy(pid = 3000 + it) }) == null)
    }
    checkCase("the complete waiting-parent cluster must remain stable before launch") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        check(stable.observe(listOf(waitingParent, requestLeaf), 1000) == null)
        check(stable.observe(listOf(requestLeaf, waitingParent), 1100) == null)
        check(stable.observe(listOf(waitingParent, requestLeaf), 1300) == requestLeaf)
        val changedParent = waitingParent.copy(directory = waitingParent.directory.copy(inode = 998875))
        check(stable.observe(listOf(changedParent, requestLeaf), 1400) == null)
        check(stable.observe(listOf(changedParent, requestLeaf), 1600) == null)
        check(stable.observe(listOf(changedParent, requestLeaf), 1700) == requestLeaf)
    }
    checkCase("the request child can close an inherited socket without losing pathname identity") {
        check(RootRequestIdentity.uniqueRequestSocketOwner(listOf(waitingParent.copy(socketInodes = setOf(101, 102, 999)), requestLeaf)) == requestLeaf)
    }
    checkCase("a changed request socket filesystem identity restarts settling") {
        val stable = RootRequestIdentity.StableDescriptorRequest()
        stable.observe(listOf(request), 1000)
        val replacement = request.copy(requestSocketFile = request.requestSocketFile!!.copy(inode = 99442))
        check(stable.observe(listOf(replacement), 1400) == null)
        check(stable.observe(listOf(replacement), 1500) == null)
        check(stable.observe(listOf(replacement), 1700) == replacement)
    }
    val client = RootRequestIdentity.ProbeClient(2500, 1000, 90000, List(4) { 10048 },
        listOf("/system/xbin/su", "-c", "fixed-probe"), privateOutput)
    fun clientMatches(value: RootRequestIdentity.ProbeClient = client, pipes: Set<RootRequestIdentity.FileKey> = setOf(privateOutput),
        before: Set<Int> = setOf(2000)) = RootRequestIdentity.probeClientMatches(value, before, 1000, 10048, "fixed-probe", pipes)
    checkCase("a direct same-UID su child must hold a newly owned anonymous pipe") { check(clientMatches()) }
    checkCase("a foreign parent or virtual UID cannot identify the probe client") {
        check(!clientMatches(client.copy(parentPid = 999)))
        check(!clientMatches(client.copy(uids = List(4) { 10051 })))
    }
    checkCase("unrelated commands and old processes cannot identify the probe client") {
        check(!clientMatches(client.copy(argv = listOf("/system/xbin/su", "-c", "other"))))
        check(!clientMatches(before = setOf(client.pid)))
    }
    checkCase("an unowned pipe or a pipe on another device cannot identify the client") {
        check(!clientMatches(pipes = emptySet()))
        check(!clientMatches(client.copy(stdout = privateOutput.copy(device = 44))))
    }
    checkCase("the process parent parser requires a complete valid PPid field") {
        check(RootRequestIdentity.parentPid("Name:\tsu\nPPid:\t1000\n") == 1000)
        check(runCatching { RootRequestIdentity.parentPid("PPid:\tnope\n") }.isFailure)
    }
    println("ROOT_REQUEST_IDENTITY_TESTS_PASSED=$checked")
}
