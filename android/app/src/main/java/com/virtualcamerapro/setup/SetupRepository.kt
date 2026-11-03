package com.virtualcamerapro.setup

import android.content.Context
import org.json.JSONObject

/** Owned only by :camera; controller preferences live in their existing separate repository. */
class SetupRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @Synchronized fun lastSnapshot(): JSONObject? = runCatching {
        preferences.getString("snapshot", null)?.let(::JSONObject)
    }.getOrNull()
    @Synchronized fun saveSnapshot(snapshot: JSONObject) {
        check(preferences.edit().putString("snapshot", snapshot.toString()).commit()) { "Setup progress could not be persisted" }
    }
    /** Historical diagnostics only; this must never be restored as live root authorization. */
    @Synchronized fun lastRootAuthorizationCheck(): JSONObject? = runCatching {
        preferences.getString("lastRootAuthorizationCheck", null)?.let(::JSONObject)
            ?.takeIf { it.optBoolean("authorizationRequested") && it.optLong("completedAt") > 0 }
    }.getOrNull()
    @Synchronized fun saveRootAuthorizationCheck(report: JSONObject) {
        check(preferences.edit().putString("lastRootAuthorizationCheck", report.toString()).commit()) {
            "The last guest root authorization result could not be preserved"
        }
    }
    @Synchronized fun preferences(): JSONObject = JSONObject()
        .put("autoRecover", runCatching { preferences.getBoolean("autoRecover", true) }.getOrDefault(false))
        .put("autoResume", runCatching { preferences.getBoolean("autoResume", false) }.getOrDefault(false))
    @Synchronized fun setPreferences(autoRecover: Boolean, autoResume: Boolean) {
        check(preferences.edit().putBoolean("autoRecover", autoRecover).putBoolean("autoResume", autoResume).commit())
    }
    @Synchronized fun approve(identity: String) {
        check(preferences.edit().putString("approvedIdentity", identity).putLong("approvedAt", System.currentTimeMillis()).commit())
    }
    @Synchronized fun approved(identity: String) = runCatching { preferences.getString("approvedIdentity", null) == identity }.getOrDefault(false)
    @Synchronized fun approveRepairPlan(identity: String) {
        check(preferences.edit().putString("approvedRepairPlan",identity).remove("automaticRepairAttempt").commit())
    }
    @Synchronized fun repairPlanApproved(identity: String) = preferences.getString("approvedRepairPlan",null) == identity
    @Synchronized fun historicalApprovalForGuest(fingerprint: String): Boolean = runCatching {
        val approved = preferences.getString("approvedIdentity",null) ?: return false
        val verified = preferences.getString("verifiedIdentity",null) ?: return false
        approved == verified && approved.startsWith("$fingerprint|") &&
            JSONObject(preferences.getString("verification",null) ?: "{}").optBoolean("verified")
    }.getOrDefault(false)
    @Synchronized fun claimAutomaticRepair(key: String): Boolean {
        if (preferences.getString("automaticRepairAttempt",null) == key) return false
        check(preferences.edit().putString("automaticRepairAttempt",key).commit())
        return true
    }
    @Synchronized fun clearAutomaticRepairAttempt() { check(preferences.edit().remove("automaticRepairAttempt").commit()) }
    @Synchronized fun revokeRepairApproval() {
        check(preferences.edit().remove("approvedRepairPlan").remove("approvedIdentity").remove("automaticRepairAttempt").commit())
    }
    @Synchronized fun verified(identity: String, evidence: JSONObject) {
        check(preferences.edit().putString("verifiedIdentity", identity).putLong("lastVerifiedAt", System.currentTimeMillis())
            .putString("verification", evidence.toString()).remove("automaticRepairAttempt").commit())
    }
    @Synchronized fun verification(identity: String): JSONObject? = runCatching {
        if (preferences.getString("verifiedIdentity", null) == identity)
            preferences.getString("verification", null)?.let(::JSONObject)?.takeIf { it.optBoolean("verified") }
        else null
    }.getOrNull()
    @Synchronized fun lastVerifiedAt() = runCatching { preferences.getLong("lastVerifiedAt", 0) }.getOrDefault(0L)
    @Synchronized fun clearVerification() {
        check(preferences.edit().remove("verifiedIdentity").remove("verification").remove("lastVerifiedAt").commit())
    }
    companion object { const val PREFERENCES = "virtual-camera-unified-setup-v1" }
}
