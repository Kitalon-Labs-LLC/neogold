package com.kitalonlabs.neogold.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface MasterKeyProvider {
    fun getOrCreateMasterKey(alias: String): SecretKey
    fun wrap(alias: String, plaintext: ByteArray): ByteArray
    fun unwrap(alias: String, wrappedData: ByteArray): ByteArray
    fun deleteMasterKey(alias: String)
    fun hasMasterKey(alias: String): Boolean

    companion object {
        const val ALIAS_DB_MASTER = "neogold_db_master_key"
        const val ALIAS_FILE_MASTER = "neogold_file_master_key"
        const val ALIAS_PIN_VERIFIER = "neogold_pin_verifier_key"
    }
}

/**
 * Android Keystore implementation for AES-256-GCM master keys.
 *
 * Rules (doc 8 Section 4 K7/K8, Section 5.1):
 * - AES-256-GCM, non-exportable.
 * - Hardware backed: StrongBox where present, TEE otherwise.
 * - No user-authentication requirement (`setUserAuthenticationRequired(false)`),
 *   allowing retention jobs and uploads to run with screen off.
 * - Keystore loss triggers [KeystoreLossException].
 */
class AndroidMasterKeyProvider(
    private val context: Context,
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
) : MasterKeyProvider {

    override fun getOrCreateMasterKey(alias: String): SecretKey {
        try {
            if (keyStore.containsAlias(alias)) {
                val key = keyStore.getKey(alias, null) as? SecretKey
                if (key != null) return key
            }
            return generateMasterKey(alias)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystoreLossException("Master key '$alias' was permanently invalidated", e)
        } catch (e: UnrecoverableKeyException) {
            throw KeystoreLossException("Master key '$alias' is unrecoverable in Keystore", e)
        } catch (e: GeneralSecurityException) {
            throw KeystoreLossException("Failed to access master key '$alias' in Keystore", e)
        }
    }

    private fun generateMasterKey(alias: String): SecretKey {
        val hasStrongBox = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        if (hasStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKeyWithSpec(alias, useStrongBox = true)
            } catch (e: Exception) {
                if (e is StrongBoxUnavailableException || e is ProviderException) {
                    // Fallback to standard TEE
                    return generateKeyWithSpec(alias, useStrongBox = false)
                }
                throw e
            }
        }

        return generateKeyWithSpec(alias, useStrongBox = false)
    }

    private fun generateKeyWithSpec(alias: String, useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    override fun wrap(alias: String, plaintext: ByteArray): ByteArray {
        val masterKey = getOrCreateMasterKey(alias)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv ?: throw GeneralSecurityException("Cipher IV was null")
        val ciphertext = cipher.doFinal(plaintext)

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    override fun unwrap(alias: String, wrappedData: ByteArray): ByteArray {
        if (wrappedData.size < GCM_IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
            throw KeystoreLossException("Wrapped data too short (${wrappedData.size} bytes)")
        }

        val masterKey = try {
            if (!keyStore.containsAlias(alias)) {
                throw KeystoreLossException("Master key '$alias' missing from Keystore")
            }
            val key = keyStore.getKey(alias, null) as? SecretKey
                ?: throw KeystoreLossException("Key '$alias' not found or invalid type in Keystore")
            key
        } catch (e: KeystoreLossException) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystoreLossException("Master key '$alias' permanently invalidated", e)
        } catch (e: UnrecoverableKeyException) {
            throw KeystoreLossException("Master key '$alias' is unrecoverable", e)
        } catch (e: Exception) {
            throw KeystoreLossException("Failed to access master key '$alias' in Keystore", e)
        }

        val iv = wrappedData.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = wrappedData.copyOfRange(GCM_IV_LENGTH_BYTES, wrappedData.size)

        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
            cipher.doFinal(ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystoreLossException("Master key '$alias' permanently invalidated during decryption", e)
        } catch (e: UnrecoverableKeyException) {
            throw KeystoreLossException("Master key '$alias' unrecoverable during decryption", e)
        } catch (e: GeneralSecurityException) {
            throw KeystoreLossException("Failed to unwrap data with master key '$alias': authentication failed or key lost", e)
        } catch (e: Exception) {
            throw KeystoreLossException("Unexpected error unwrapping data with master key '$alias'", e)
        }
    }

    override fun deleteMasterKey(alias: String) {
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (e: KeyStoreException) {
            throw GeneralSecurityException("Failed to delete key '$alias' from Keystore", e)
        }
    }

    override fun hasMasterKey(alias: String): Boolean {
        return try {
            keyStore.containsAlias(alias)
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }
}
