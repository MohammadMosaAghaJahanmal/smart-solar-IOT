package com.virtualcamerapro.broker

/** VMOS combines host product/build-user labels with its guest framework build. */
object VmosGuestBuild {
    fun matches(sdk: Int, hardware: String, abis: List<String>, fingerprint: String): Boolean =
        sdk == 28 && hardware == "vphw90" && abis == listOf("arm64-v8a") &&
            Regex("[^\\s:]+:9/PQ3A\\.190801\\.002/eng\\.[A-Za-z0-9_-]+\\.20191015\\.144423:user/release-keys").matches(fingerprint)
}
