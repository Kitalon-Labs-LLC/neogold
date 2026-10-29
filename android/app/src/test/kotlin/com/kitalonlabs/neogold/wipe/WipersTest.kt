package com.kitalonlabs.neogold.wipe

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.pin.FakePinHmacKeyProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec

/** The parts of "Delete all data" that live outside the database (FR-33, TC-LOG-11, BUG-2026-009). */
class WipersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun keyStoreWith(vararg aliases: String): KeyStore {
        val store = KeyStore.getInstance("JCEKS")
        store.load(null, null)
        val password = KeyStore.PasswordProtection(charArrayOf('x'))
        for (alias in aliases) {
            store.setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(32) { 7 }, "AES")), password)
        }
        return store
    }

    private fun KeyStore.has(alias: String) = containsAlias(alias)

    @Test
    fun directoryWiper_removesTheFolderAndEverythingInIt() {
        val staging = temp.newFolder("export_staging")
        File(staging, "checkpoint.json").writeText("keyset")
        File(staging, "nested").mkdirs()
        File(staging, "nested/part.enc").writeText("ciphertext")

        val wiped = DirectoryWiper("export-staging", staging).wipe()

        assertThat(wiped).isTrue()
        assertThat(staging.exists()).isFalse()
    }

    @Test
    fun directoryWiper_treatsAMissingFolderAsAlreadyGone() {
        assertThat(DirectoryWiper("crash-reports", File(temp.root, "never_made")).wipe()).isTrue()
    }

    @Test
    fun keystoreAliasWiper_removesDeviceKeysOfEveryStudy_andNamedAliases_butNotOthers() {
        val store = keyStoreWith(
            "neogold_device_key_DHK-01",
            "neogold_device_key_CTG-02",
            "neogold_crash_master_key",
            "someone_elses_key"
        )
        val wiper = KeystoreAliasWiper(
            name = "keystore-keys",
            keyStore = { store },
            prefixes = listOf("neogold_device_key_"),
            exactAliases = listOf("neogold_crash_master_key")
        )

        assertThat(wiper.wipe()).isTrue()

        assertThat(store.has("neogold_device_key_DHK-01")).isFalse()
        assertThat(store.has("neogold_device_key_CTG-02")).isFalse()
        assertThat(store.has("neogold_crash_master_key")).isFalse()
        assertThat(store.has("someone_elses_key")).isTrue()
    }

    @Test
    fun keystoreAliasWiper_reportsAKeystoreThatCannotBeOpened_insteadOfThrowing() {
        val wiper = KeystoreAliasWiper(
            name = "keystore-keys",
            keyStore = { throw java.security.KeyStoreException("down") },
            prefixes = listOf("neogold_device_key_")
        )

        assertThat(wiper.wipe()).isFalse()
    }

    @Test
    fun pinKeyWiper_removesK9() {
        val key = FakePinHmacKeyProvider()
        assertThat(key.hasKey()).isTrue()

        assertThat(PinKeyWiper(key).wipe()).isTrue()

        assertThat(key.hasKey()).isFalse()
    }
}
