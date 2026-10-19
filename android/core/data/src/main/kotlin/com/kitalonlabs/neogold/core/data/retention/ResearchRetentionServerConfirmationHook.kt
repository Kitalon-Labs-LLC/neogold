package com.kitalonlabs.neogold.core.data.retention

/**
 * Interface hook for confirming research session status on the Study Intake Service
 * before deleting research images and rows during research local retention (doc 5 Section 2.5).
 *
 * Implemented in P20 (Study sync client).
 */
interface ResearchRetentionServerConfirmationHook {
    /**
     * Checks whether the server confirms the session is in a final state ('COMPLETE' or 'REJECTED').
     * If the server reports '404' or 'RECEIVING', this returns false so items can be re-queued.
     */
    suspend fun isServerConfirmed(sessionId: String): Boolean
}

class NoOpResearchRetentionServerConfirmationHook : ResearchRetentionServerConfirmationHook {
    override suspend fun isServerConfirmed(sessionId: String): Boolean {
        // Default safe behavior: do not delete local research data until P20 wires server verification
        return false
    }
}
