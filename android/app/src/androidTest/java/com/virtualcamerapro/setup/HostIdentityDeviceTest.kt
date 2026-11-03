package com.virtualcamerapro.setup

import android.os.Process
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.broker.VmosHostIdentity
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONObject
import org.junit.Test
import java.io.File

class HostIdentityDeviceTest {
    @Test fun recordActualMappedLauncherIdentityWithoutRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = Os.readlink("/proc/self/exe")
        val hash = GuestAdapterRegistry.sha256(File("/proc/self/exe"))
        File(context.getExternalFilesDir(null),"host-identity.json").writeText(JSONObject()
            .put("uid",Process.myUid()).put("exe",link).put("sha256",hash)
            .put("layoutMatches",VmosHostIdentity.isLauncherPath(link,true))
            .put("pinnedMatches",VmosHostIdentity.isPinnedLauncher(link,hash,true)).toString(2))
    }
}
