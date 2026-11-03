package com.virtualcamerapro.setup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises history persistence in isolated preferences; never invokes root or cameras. */
@RunWith(AndroidJUnit4::class)
class SetupRepositoryTest {
    private lateinit var isolated: IsolatedPreferencesContext

    @Before fun createStorage() {
        isolated = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @After fun removeStorage() { isolated.dispose() }

    private fun report(verified: Boolean, completedAt: Long = 200L) = JSONObject()
        .put("authorizationRequested", true).put("verified", verified)
        .put("code", if (verified) "GUEST_ROOT_VERIFIED" else "VMOS_ROOT_BOOTSTRAP_MISSING")
        .put("checkedAt", 100L).put("completedAt", completedAt)
        .put("authorizationBridge", JSONObject().put("launched", false).put("reason", "No matching pending request"))

    @Test fun actualRootFailureSurvivesReadOnlyHealthSnapshotsAndRepositoryRecreation() {
        val original = report(false)
        SetupRepository(isolated).saveRootAuthorizationCheck(original)
        repeat(3) {
            SetupRepository(isolated).saveSnapshot(JSONObject().put("state", "root_required")
                .put("components", JSONObject().put("root", JSONObject().put("verified", false).put("code", "GUEST_ROOT_NOT_CHECKED"))))
        }
        val retained = requireNotNull(SetupRepository(isolated).lastRootAuthorizationCheck())
        assertFalse(retained.getBoolean("verified"))
        assertEquals("VMOS_ROOT_BOOTSTRAP_MISSING", retained.getString("code"))
        assertEquals(200L, retained.getLong("completedAt"))
        assertEquals("No matching pending request", retained.getJSONObject("authorizationBridge").getString("reason"))
    }

    @Test fun laterSuccessReplacesFailureWithoutCreatingCameraVerificationEvidence() {
        val repository = SetupRepository(isolated)
        repository.saveRootAuthorizationCheck(report(false))
        repository.saveRootAuthorizationCheck(report(true, 500L))
        val current = requireNotNull(SetupRepository(isolated).lastRootAuthorizationCheck())
        assertTrue(current.getBoolean("verified"))
        assertEquals("GUEST_ROOT_VERIFIED", current.getString("code"))
        assertEquals(500L, current.getLong("completedAt"))
        assertNull("A persisted root report cannot establish camera readiness", repository.verification("profile"))
        assertEquals(0L, repository.lastVerifiedAt())
    }

    @Test fun corruptOrUnrequestedHistoryDoesNotBecomeAnAuthorizationResult() {
        val preferences = isolated.getSharedPreferences(SetupRepository.PREFERENCES, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putInt("lastRootAuthorizationCheck", 1).commit())
        assertNull(SetupRepository(isolated).lastRootAuthorizationCheck())
        SetupRepository(isolated).saveRootAuthorizationCheck(report(false).put("authorizationRequested", false))
        assertNull(SetupRepository(isolated).lastRootAuthorizationCheck())
    }

    @Test fun restartedSetupExposesHistoryWithoutReusingHistoricalRootAuthorization() {
        SetupRepository(isolated).saveRootAuthorizationCheck(report(true))
        var foregroundStarts = 0
        val restarted = SetupOrchestrator(isolated) { if (it) foregroundStarts++ }
        try {
            // An unapproved action returns the initial snapshot without launching any worker.
            val snapshot = restarted.execute("check_root", false)
            assertEquals("USER_AUTHORIZATION_REQUIRED", snapshot.getString("actionRejected"))
            val root = snapshot.getJSONObject("components").getJSONObject("root")
            assertFalse("Historical UID-0 success is not a live authorization cache", root.getBoolean("verified"))
            assertTrue(root.getJSONObject("lastAuthorizationCheck").getBoolean("verified"))
            assertFalse(snapshot.getBoolean("ready"))
            assertEquals(0, foregroundStarts)
        } finally { restarted.close() }
    }

    private class IsolatedPreferencesContext(private val actual: Context) : ContextWrapper(actual) {
        private val prefix = "setup-history-regression-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return actual.getSharedPreferences(isolated, mode)
        }
        fun dispose() { names.forEach { actual.deleteSharedPreferences(it) } }
    }
}
