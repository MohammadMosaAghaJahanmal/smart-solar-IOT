package com.virtualcamerapro.setup

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import com.virtualcamerapro.setup.provisioning.RootAccessManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only coverage of the daemon's PATH_MAX argument limit after explicit root setup. */
@RunWith(AndroidJUnit4::class)
class UnifiedRootOperationTest {
    @Test fun longReadOnlyOperationUsesAnonymousStandardInput() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("unifiedRootApproved") == "true")
        assertTrue(GuestAdapterRegistry.profileMatches())
        assertTrue(Process.myUid() >= 10000)
        val root = RootAccessManager(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue(root.check(true).getBoolean("verified"))
        val command = "printf 'BEGIN_UID='; /system/bin/id -u\n" + "# bounded read-only script padding\n".repeat(200) + "printf 'END_OK\\n'\n"
        assertTrue(command.toByteArray().size > 4096)
        assertEquals("BEGIN_UID=0\nEND_OK\n", root.approved(command))
    }
}
