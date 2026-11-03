package com.virtualcamerapro.setup

import com.virtualcamerapro.engine.OutputCapabilities
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.broker.VmosGuestBuild

fun main() {
    var cases = 0
    for (mask in 0..255) {
        val bits = (0..7).map { mask and (1 shl it) != 0 }
        val allowed = AutomaticRepairPolicy.allowed(bits[0],bits[1],bits[2],bits[3],bits[4],bits[5],bits[6],bits[7])
        check(allowed == (mask == (1+2+4+8+64+128)))
        cases++
    }
    val back = OutputCapabilities.Camera("rear_A",listOf(640 to 480,960 to 540))
    val front = OutputCapabilities.Camera("front_B",listOf(640 to 480,1920 to 1080))
    val all = listOf(back,front)
    fun resolve(value: OutputSettings) = OutputCapabilities.resolve(value,all,listOf(back.id,front.id),listOf(640 to 480))
    val adapted = resolve(OutputSettings(cameraId="missing"))
    check(adapted.cameraId == back.id && adapted.width == 960 && adapted.height == 540)
    val requested = OutputSettings(cameraId=front.id,width=1920,height=1080,rotation=270,zoom=2.3,panX=0.7,panY=-0.3,loop=false)
    check(resolve(requested) == requested)
    val both = resolve(requested.copy(replacementMode="both"))
    check(both == requested.copy(replacementMode="both",width=640,height=480))
    check(runCatching { OutputCapabilities.resolve(requested,emptyList(),emptyList(),emptyList()) }.isFailure)
    check(runCatching { OutputCapabilities.resolve(requested.copy(replacementMode="both"),all,emptyList(),emptyList()) }.isFailure)
    check(runCatching { OutputCapabilities.resolve(requested, listOf(front.copy(sizes=emptyList())),emptyList(),emptyList()) }.isFailure)
    check(runCatching { OutputCapabilities.resolve(requested, listOf(front.copy(sizes=listOf(4096 to 4096))),emptyList(),emptyList()) }.isFailure)
    println("PASS $cases exhaustive automatic repair policy cases and 7 dynamic output capability checks")
    for (label in listOf("builds","dpi","another_host-7")) {
        check(VmosGuestBuild.matches(28,"vphw90",listOf("arm64-v8a"),"any/product/device:9/PQ3A.190801.002/eng.$label.20191015.144423:user/release-keys"))
    }
    val fp = "any/product/device:9/PQ3A.190801.002/eng.dpi.20191015.144423:user/release-keys"
    for (changed in listOf(fp.replace("20191015","20191016"),fp.replace("user/","userdebug/"),fp.replace("release-keys","test-keys"),fp+"\n",fp.replace("eng.dpi","eng.bad/name")))
        check(!VmosGuestBuild.matches(28,"vphw90",listOf("arm64-v8a"),changed))
    check(!VmosGuestBuild.matches(33,"vphw90",listOf("arm64-v8a"),fp))
    check(!VmosGuestBuild.matches(28,"physical",listOf("arm64-v8a"),fp))
    println("PASS 10 host-label/guest-framework identity checks")
}
