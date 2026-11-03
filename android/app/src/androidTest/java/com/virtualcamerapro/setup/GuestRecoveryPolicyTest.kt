package com.virtualcamerapro.setup

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.broker.GuestBootReceiver
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject

/** Policy checks use isolated preferences and never start a camera/root operation. */
@RunWith(AndroidJUnit4::class)
class GuestRecoveryPolicyTest {
    private lateinit var isolated: PolicyContext
    @Before fun createPreferences() { isolated = PolicyContext(InstrumentationRegistry.getInstrumentation().targetContext) }
    @After fun removePreferences() { isolated.dispose() }

    @Test fun recoveryDefaultsToTransportOnlyAndResumeRequiresAnExplicitPreference() {
        val initial = SetupRepository(isolated).preferences()
        assertTrue(initial.getBoolean("autoRecover"))
        assertFalse(initial.getBoolean("autoResume"))
        SetupRepository(isolated).setPreferences(false,true)
        val recreated = SetupRepository(isolated).preferences()
        assertFalse(recreated.getBoolean("autoRecover"))
        assertTrue(recreated.getBoolean("autoResume"))
    }

    @Test fun disabledRecoveryDoesNotStartAServiceForBootOrAppUpdate() {
        SetupRepository(isolated).setPreferences(false,true)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED))
            GuestBootReceiver().onReceive(isolated,Intent(action))
        assertEquals(0,isolated.starts)
    }

    @Test fun corruptedRecoveryPreferencesFailClosedBeforeAnyGuestOperation() {
        assertTrue(isolated.policy().edit().putString("autoRecover","true").putInt("autoResume",1).commit())
        val policy = SetupRepository(isolated).preferences()
        assertFalse(policy.getBoolean("autoRecover"))
        assertFalse(policy.getBoolean("autoResume"))
        GuestBootReceiver().onReceive(isolated,Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(0,isolated.starts)
    }

    @Test fun unrelatedBroadcastDoesNotReadPolicyOrStartAService() {
        GuestBootReceiver().onReceive(isolated,Intent("com.virtualcamerapro.TEST_UNRELATED"))
        assertEquals(0,isolated.reads)
        assertEquals(0,isolated.starts)
    }
    @Test fun approvedPlanAndFailedAttemptSurviveServiceRecreationUntilExplicitRetry() {
        val first = SetupRepository(isolated)
        assertFalse(first.repairPlanApproved("plan-A"))
        first.approveRepairPlan("plan-A")
        assertTrue(first.claimAutomaticRepair("boot-A-plan-A"))
        val restarted = SetupRepository(isolated)
        assertTrue(restarted.repairPlanApproved("plan-A"))
        assertFalse(restarted.repairPlanApproved("changed-system-plan"))
        assertFalse(restarted.claimAutomaticRepair("boot-A-plan-A"))
        restarted.clearAutomaticRepairAttempt()
        assertTrue(restarted.claimAutomaticRepair("boot-A-plan-A"))
        restarted.revokeRepairApproval()
        assertFalse(SetupRepository(isolated).repairPlanApproved("plan-A"))
    }
    @Test fun historicalApprovalRequiresActualMatchingVerificationAndGuest() {
        val repository = SetupRepository(isolated)
        repository.approve("guest-fingerprint|profile|proof")
        assertFalse(repository.historicalApprovalForGuest("guest-fingerprint"))
        repository.verified("guest-fingerprint|other-profile",JSONObject().put("verified",true))
        assertFalse(repository.historicalApprovalForGuest("guest-fingerprint"))
        repository.verified("guest-fingerprint|profile|proof",JSONObject().put("verified",true))
        assertTrue(repository.historicalApprovalForGuest("guest-fingerprint"))
        assertFalse(repository.historicalApprovalForGuest("different-guest"))
        repository.revokeRepairApproval()
        assertFalse(repository.historicalApprovalForGuest("guest-fingerprint"))
    }
    @Test fun successfulVerificationRearmsRecoveryForAnotherRealFailureWithTheSameConditions() {
        val first = SetupRepository(isolated)
        first.approveRepairPlan("approved-plan")
        assertTrue(first.claimAutomaticRepair("same-boot-and-profile"))
        assertFalse(SetupRepository(isolated).claimAutomaticRepair("same-boot-and-profile"))
        first.verified("guest-fingerprint|profile", JSONObject().put("verified", true))
        val recreated = SetupRepository(isolated)
        assertTrue(recreated.repairPlanApproved("approved-plan"))
        assertTrue(recreated.claimAutomaticRepair("same-boot-and-profile"))
        assertFalse(recreated.claimAutomaticRepair("same-boot-and-profile"))
    }

    private class PolicyContext(private val actual: Context) : ContextWrapper(actual) {
        private val name = "guest-recovery-policy-${UUID.randomUUID()}"
        var starts = 0
        var reads = 0
        fun policy(): SharedPreferences = actual.getSharedPreferences(name,Context.MODE_PRIVATE)
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences { reads++; return policy() }
        override fun startForegroundService(service: Intent): ComponentName? { starts++; error("A disabled policy started guest recovery") }
        fun dispose() { actual.deleteSharedPreferences(name) }
    }
}
