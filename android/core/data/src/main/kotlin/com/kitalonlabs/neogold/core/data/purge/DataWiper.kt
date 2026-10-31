package com.kitalonlabs.neogold.core.data.purge

/**
 * One more thing "Delete all data" must remove that :core:data does not own: a key in the Keystore, a cache folder, a
 * crash report (BUG-2026-009). The app supplies these, so the purge reaches every place data or key material can sit
 * without this module knowing about research export, crash reports or device signing.
 */
interface DataWiper {
    /** Short name for the incomplete list; never a value from the data being deleted. */
    val name: String

    /** True when nothing of it is left, including when there was nothing to remove. Must not throw. */
    fun wipe(): Boolean
}
