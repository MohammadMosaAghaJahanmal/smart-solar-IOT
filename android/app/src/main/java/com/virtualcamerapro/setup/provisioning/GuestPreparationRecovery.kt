package com.virtualcamerapro.setup.provisioning

/** Only an unfinished repair owned by this installation may be replayed. */
internal object GuestPreparationRecovery {
    fun unfinishedOwnedJpeg(owned: Boolean, completed: Boolean, factoryHal: Boolean, recoveryJournalPresent: Boolean): Boolean =
        owned && !completed && factoryHal && recoveryJournalPresent
}
