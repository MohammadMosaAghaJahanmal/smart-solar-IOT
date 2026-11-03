package com.virtualcamerapro.setup.provisioning

fun main() {
    // A fresh guest and a preexisting repair have no ownership journal.
    check(!GuestPreparationRecovery.unfinishedOwnedJpeg(false, false, true, false))
    check(!GuestPreparationRecovery.unfinishedOwnedJpeg(false, false, true, true))
    // Repaired bytes can precede provider restart: replay only our incomplete
    // factory-camera transaction, through the strict installer rollback first.
    check(GuestPreparationRecovery.unfinishedOwnedJpeg(true, false, true, true))
    check(!GuestPreparationRecovery.unfinishedOwnedJpeg(true, true, true, true))
    // A configured bridge or missing recovery provenance must be preserved.
    check(!GuestPreparationRecovery.unfinishedOwnedJpeg(true, false, false, true))
    check(!GuestPreparationRecovery.unfinishedOwnedJpeg(true, false, true, false))
    println("GUEST_PREPARATION_RECOVERY_TESTS_PASSED=6")
}
