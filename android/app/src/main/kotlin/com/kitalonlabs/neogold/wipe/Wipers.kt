package com.kitalonlabs.neogold.wipe

import com.kitalonlabs.neogold.core.data.purge.DataWiper
import com.kitalonlabs.neogold.core.security.pin.PinHmacKeyProvider
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException

/**
 * The parts of "Delete all data" that :core:data does not own (FR-33, BUG-2026-009, TC-LOG-11). Each one reports
 * whether nothing of it is left, and none throws.
 */

/** Removes a folder and everything in it: export staging, crash reports. */
class DirectoryWiper(override val name: String, private val directory: File) : DataWiper {
    override fun wipe(): Boolean {
        directory.deleteRecursively()
        return !directory.exists()
    }
}

/** Removes the PIN verifier key K9. */
class PinKeyWiper(private val pinHmacKey: PinHmacKeyProvider) : DataWiper {
    override val name: String = "pin-verifier-key"

    @Suppress("TooGenericExceptionCaught") // must not throw; a key that cannot be removed is reported instead
    override fun wipe(): Boolean = try {
        pinHmacKey.deleteKey()
        !pinHmacKey.hasKey()
    } catch (_: Exception) {
        false
    }
}

/**
 * Removes every Keystore entry whose alias starts with one of [prefixes] or equals one of [exactAliases]: the device
 * signing keys K6 (`neogold_device_key_<study>`), and the crash report key.
 */
class KeystoreAliasWiper(
    override val name: String,
    private val keyStore: () -> KeyStore,
    private val prefixes: List<String>,
    private val exactAliases: List<String> = emptyList()
) : DataWiper {

    override fun wipe(): Boolean = try {
        val store = keyStore()
        val matching = store.aliases().toList().filter { alias ->
            alias in exactAliases || prefixes.any { alias.startsWith(it) }
        }
        matching.forEach { store.deleteEntry(it) }
        matching.none { store.containsAlias(it) }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: ProviderException) {
        false
    }
}
