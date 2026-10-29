package com.kitalonlabs.neogold.storage

import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.security.KeystoreHealthStatus
import com.kitalonlabs.neogold.core.security.KeystoreLossException
import com.kitalonlabs.neogold.core.security.KeystoreRecoveryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Keys are checked before the database is touched, and nothing is deleted until the user asks (doc 8 Section 5.1,
 * TC-SEC-07, BUG-2026-011).
 */
class StorageCoordinatorTest {

    private class FakeRecovery(var health: KeystoreHealthStatus = KeystoreHealthStatus.Healthy) : KeystoreRecoveryManager {
        var resets = 0
        val events = mutableListOf<String>()

        override fun checkKeystoreHealth(): KeystoreHealthStatus {
            events += "check"
            return health
        }

        override fun resetEncryptedStores() {
            resets++
            events += "reset"
            health = KeystoreHealthStatus.Healthy
        }
    }

    private val loss = KeystoreHealthStatus.LossDetected("a key is gone", KeystoreLossException("gone"))

    private fun coordinator(
        recovery: FakeRecovery,
        open: suspend () -> String = { "database" },
        onOpened: suspend (String) -> Unit = {}
    ) = StorageCoordinator(recovery, open, onOpened, io = Dispatchers.Unconfined)

    @Test
    fun healthyKeys_openTheDatabase_andRunRetention() = runBlocking<Unit> {
        val ran = mutableListOf<String>()
        val storage = coordinator(FakeRecovery(), onOpened = { ran += "retention:$it" })

        storage.open()

        assertThat(storage.state.value).isEqualTo(StorageState.Ready("database"))
        assertThat(ran).containsExactly("retention:database")
    }

    @Test
    fun lostKey_isCheckedBeforeTheDatabaseIsTouched_andNothingIsDeleted() = runBlocking<Unit> {
        val recovery = FakeRecovery(health = loss)
        var opened = false
        val storage = coordinator(
            recovery,
            open = {
                opened = true
                "database"
            }
        )

        storage.open()

        assertThat(storage.state.value).isEqualTo(StorageState.Unreadable)
        assertThat(opened).isFalse()
        assertThat(recovery.resets).isEqualTo(0)
    }

    @Test
    fun theUserChoosingToReset_resetsOnceAndThenOpensFresh() = runBlocking<Unit> {
        val recovery = FakeRecovery(health = loss)
        val storage = coordinator(recovery)
        storage.open()

        storage.resetAndOpen()

        assertThat(recovery.resets).isEqualTo(1)
        assertThat(recovery.events).containsExactly("check", "reset", "check").inOrder()
        assertThat(storage.state.value).isEqualTo(StorageState.Ready("database"))
    }

    @Test
    fun tryingAgain_afterATemporaryFailure_opensWithoutDeletingAnything() = runBlocking<Unit> {
        val recovery = FakeRecovery(health = loss)
        val storage = coordinator(recovery)
        storage.open()
        recovery.health = KeystoreHealthStatus.Healthy

        storage.open()

        assertThat(recovery.resets).isEqualTo(0)
        assertThat(storage.state.value).isEqualTo(StorageState.Ready("database"))
    }

    @Test
    fun aDatabaseThatDoesNotOpenWithItsKey_isUnreadable_notACrash() = runBlocking<Unit> {
        val storage = coordinator(FakeRecovery(), open = { throw IllegalStateException("file is not a database") })

        storage.open()

        assertThat(storage.state.value).isEqualTo(StorageState.Unreadable)
        assertThat(storage.awaitDatabase()).isNull()
    }

    @Test
    fun aFailingRetentionRun_doesNotStopTheAppOpening() = runBlocking<Unit> {
        val storage = coordinator(FakeRecovery(), onOpened = { error("retention broke") })

        storage.open()

        assertThat(storage.state.value).isEqualTo(StorageState.Ready("database"))
        assertThat(storage.awaitDatabase()).isEqualTo("database")
    }

    @Test
    fun beforeOpening_theStateIsOpening_soNothingReadsEarly() {
        val storage = coordinator(FakeRecovery())

        assertThat(storage.state.value).isEqualTo(StorageState.Opening)
    }
}
