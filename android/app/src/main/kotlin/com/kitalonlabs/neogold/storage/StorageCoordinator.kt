package com.kitalonlabs.neogold.storage

import com.kitalonlabs.neogold.core.security.KeystoreHealthStatus
import com.kitalonlabs.neogold.core.security.KeystoreRecoveryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/** Where the encrypted stores stand (doc 8 Section 5.1). */
sealed interface StorageState<out D> {
    /** Checking the keys and opening the database; nothing may read or write yet. */
    data object Opening : StorageState<Nothing>

    data class Ready<D>(val database: D) : StorageState<D>

    /**
     * Saved data cannot be opened: a Keystore key is gone, or the database does not open with its key. Nothing is
     * deleted until the user says so (TC-SEC-07): the failure may be temporary, and the only way out is a reset.
     */
    data object Unreadable : StorageState<Nothing>
}

/**
 * Checks the Keystore keys before the database is touched, opens the database off the main thread, and holds the
 * result for everything else. [openDatabase] is told to open for real, so a wrong or lost key fails here and not on
 * the first query of some screen.
 *
 * @param onReset what else a reset removes besides the encrypted stores: the other keys, settings, queued work. It runs
 * after the stores are dropped and before they are opened again.
 * @param D the database type; the app uses NeoGoldDatabase, tests use anything
 */
class StorageCoordinator<D : Any>(
    private val recovery: KeystoreRecoveryManager,
    private val openDatabase: suspend () -> D,
    private val onOpened: suspend (D) -> Unit = {},
    private val onReset: suspend () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<StorageState<D>>(StorageState.Opening)
    val state: StateFlow<StorageState<D>> = _state.asStateFlow()

    /** Checks the keys, opens the database, then runs [onOpened] (the retention jobs). Safe to call again to retry. */
    suspend fun open() {
        mutex.withLock {
            _state.value = StorageState.Opening
            _state.value = withContext(io) { check() }
        }
        (state.value as? StorageState.Ready)?.let { ready -> runOnOpened(ready.database) }
    }

    /**
     * The user chose to reset: drop the encrypted stores that cannot be read, run [onReset], then open fresh ones. If
     * the reset itself fails, the data is still unreadable and the user is asked again, never a crash.
     */
    @Suppress("TooGenericExceptionCaught") // a failed reset leaves the state Unreadable; the user can try again
    suspend fun resetAndOpen() {
        val reset = mutex.withLock {
            try {
                withContext(io) {
                    recovery.resetEncryptedStores()
                    onReset()
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Resetting saved data failed: %s", e.javaClass.name)
                _state.value = StorageState.Unreadable
                false
            }
        }
        if (reset) open()
    }

    /** The database once storage has finished opening, or null when it is unreadable. */
    suspend fun awaitDatabase(): D? =
        (state.first { it !is StorageState.Opening } as? StorageState.Ready)?.database

    @Suppress("TooGenericExceptionCaught") // any failure to open means the data cannot be read; the user decides
    private suspend fun check(): StorageState<D> {
        // The whole check is inside the try: the Keystore itself can fail to load on some phones, and that is the case
        // this screen exists for, so it must not crash the app at start.
        return try {
            if (recovery.checkKeystoreHealth() is KeystoreHealthStatus.LossDetected) {
                StorageState.Unreadable
            } else {
                StorageState.Ready(openDatabase())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Checking or opening storage failed: %s", e.javaClass.name)
            StorageState.Unreadable
        }
    }

    @Suppress("TooGenericExceptionCaught") // retention is retried tomorrow; it must never stop the app opening
    private suspend fun runOnOpened(database: D) {
        try {
            onOpened(database)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Start-up retention failed: %s", e.javaClass.name)
        }
    }
}
