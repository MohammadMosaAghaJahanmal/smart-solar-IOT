package com.virtualcamerapro.setup

/** No cached permission or root success can satisfy this policy. */
internal object AutomaticRepairPolicy {
    fun allowed(autoRecover: Boolean, approvedPlan: Boolean, supported: Boolean, permissionsReady: Boolean,
        hostDisabled: Boolean, recoveryMissing: Boolean, idle: Boolean, needsWork: Boolean): Boolean =
        autoRecover && approvedPlan && supported && permissionsReady && !hostDisabled && !recoveryMissing && idle && needsWork
}
